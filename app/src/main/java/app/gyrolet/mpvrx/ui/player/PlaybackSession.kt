/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import app.gyrolet.mpvrx.data.network.proxy.HlsStreamingProxy
import app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PlaybackSessionState(
  val phase: PlaybackPhase = PlaybackPhase.UNINITIALIZED,
  val generation: Long = 0L,
  val activeGeneration: Long = 0L,
  val surfaceAttached: Boolean = false,
  val paused: Boolean = true,
  val currentItem: PlaybackItem? = null,
  val error: String? = null,
)

/** A native-backed property that remains safe to access before the libmpv core exists. */
class PlaybackProperty<T> internal constructor(
  internal val format: Int,
  private val reader: (String) -> T?,
) {
  private val states = ConcurrentHashMap<String, MutableStateFlow<T?>>()

  operator fun get(property: String): StateFlow<T?> {
    val candidate = MutableStateFlow<T?>(null)
    val existing = states.putIfAbsent(property, candidate)
    val state = existing ?: candidate
    if (existing == null) {
      PlaybackSession.observeProperty(property, format)
      state.value = reader(property)
    }
    return state.asStateFlow()
  }

  internal fun emit(
    property: String,
    value: T?,
  ) {
    states[property]?.value = value
  }

  internal fun reobserve() {
    states.forEach { (property, state) ->
      PlaybackSession.observeProperty(property, format)
      state.value = reader(property)
    }
  }
}

/**
 * The one process-wide owner of libmpv playback state.
 *
 * Android screens and the playback service may observe or control this object, but none of them
 * owns the native core. This makes rotation, PiP, background playback, and notification re-entry
 * attachment changes instead of competing create/destroy cycles.
 */
@Suppress("TooManyFunctions")
object PlaybackSession : MPVLib.EventObserver {
  private const val TAG = "PlaybackSession"
  private const val SEEK_AUDIO_RESTORE_DELAY_MS = 60L
  private const val SEEK_AUDIO_FALLBACK_RESTORE_MS = 750L
  private const val PLAYBACK_TRANSITION_AUDIO_RESTORE_DELAY_MS = 180L
  private const val AMBIENT_SHADER_PREFIX = "ambient_"
  private const val AMBIENT_SHADER_SUFFIX = ".glsl"
  private const val AMBIENT_SCALE_EPSILON = 0.000001

  private data class NetworkStreamRegistration(
    val proxy: NetworkStreamingProxy? = null,
    val hlsProxy: HlsStreamingProxy? = null,
    val streamId: String,
  )

  private data class ResolvedPlayable(
    val uri: String,
    val registration: NetworkStreamRegistration? = null,
  )

  /**
   * Video decoding is intentionally disabled while Android has no render surface. Keep the track
   * id process-wide so Activity recreation (for example notification re-entry) cannot lose it.
   */
  private data class SuspendedVideoTrack(
    val id: Int,
    val generation: Long,
  )

  private val nativeLock = ReentrantLock(true)
  private val observers = CopyOnWriteArraySet<MPVLib.EventObserver>()
  private val pendingGenerations = ArrayDeque<Long>()
  private val _state = MutableStateFlow(PlaybackSessionState())
  private val _queue = MutableStateFlow(PlaybackQueueState())
  private val streamSequence = AtomicLong()
  private val observedProperties = mutableSetOf<Pair<String, Int>>()
  private val seekAudioGuardHandler = Handler(Looper.getMainLooper())
  private val playbackTransitionAudioGuardHandler = Handler(Looper.getMainLooper())

  val state: StateFlow<PlaybackSessionState> = _state.asStateFlow()
  val queue: StateFlow<PlaybackQueueState> = _queue.asStateFlow()

  @Volatile
  private var initialized = false
  private var nativeCoreReady = false
  private var applicationContext: Context? = null
  private var desiredVideoOutput = "gpu"
  private var activeCoreConfigurationKey: String? = null
  private var attachedSurfaceOwner: Any? = null
  private var activeNetworkStream: NetworkStreamRegistration? = null
  private val auxiliaryNetworkStreams = linkedMapOf<String, NetworkStreamRegistration>()
  private var suspendedVideoTrack: SuspendedVideoTrack? = null
  private var desiredPaused = true
  private var seekAudioGuardToken = 0L
  private var seekAudioGuardPreviousMute: Boolean? = null
  private var playbackTransitionAudioGuardToken = 0L
  private var playbackTransitionAudioGuardPreviousMute: Boolean? = null
  private val activeAmbientShaderPaths = linkedSetOf<String>()
  private var desiredAmbientScaleX = 1.0
  private var desiredAmbientScaleY = 1.0

  val isInitialized: Boolean
    get() = initialized

  val propInt = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_INT64, ::getPropertyInt)
  val propLong = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_INT64) { property -> getPropertyInt(property)?.toLong() }
  val propBoolean = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_FLAG, ::getPropertyBoolean)
  val propDouble = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_DOUBLE, ::getPropertyDouble)
  val propFloat = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_DOUBLE, ::getPropertyFloat)
  val propString = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_STRING, ::getPropertyString)
  val propNode = PlaybackProperty(MPVLib.MpvFormat.MPV_FORMAT_NODE, ::getPropertyNode)

  /** Returns true when this call created the core, false when it was already alive. */
  fun initialize(
    context: Context,
    configDir: String,
    cacheDir: String,
    coreConfigurationKey: String,
    initOptions: () -> Unit,
    postInitOptions: () -> Unit,
    observeProperties: () -> Unit,
  ): Result<Boolean> =
    runCatching {
      nativeLock.withLock {
        if (initialized && activeCoreConfigurationKey == coreConfigurationKey) {
          return@withLock false
        }
        if (initialized) {
          Log.i(TAG, "Playback core configuration changed; recreating the libmpv core")
          destroyLocked()
        }

        applicationContext = context.applicationContext
        nativeCoreReady = false
        observedProperties.clear()
        suspendedVideoTrack = null
        desiredPaused = true
        clearSeekAudioGuardLocked(restoreMute = false)
        clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
        resetAmbientShaderTrackingLocked()
        updateState { it.copy(phase = PlaybackPhase.INITIALIZING, error = null) }
        try {
          MPVLib.create(context.applicationContext)
          MPVLib.setOptionString("config", "yes")
          MPVLib.setOptionString("config-dir", configDir)
          MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
          MPVLib.setOptionString("icc-cache-dir", cacheDir)
          // Keep app defaults before initialization. libmpv then parses the native mpv.conf once
          // during init, preserving its profiles, includes and quoting without runtime replay.
          initOptions()
          MPVLib.init()
          // Runtime properties do not exist between MPVLib.create() and MPVLib.init(). Keep option
          // writes available in that window, but permit property reads only from this point on.
          nativeCoreReady = true
          postInitOptions()
          MPVLib.setOptionString("force-window", "no")
          MPVLib.setOptionString("idle", "yes")
          MPVLib.addObserver(this)
          reobserveTrackedProperties()
          observeProperties()
          initialized = true
          activeCoreConfigurationKey = coreConfigurationKey
          updateState { it.copy(phase = PlaybackPhase.IDLE, paused = true, error = null) }
          true
        } catch (error: Throwable) {
          runCatching { MPVLib.removeObserver(this) }
          runCatching { MPVLib.destroy() }
          initialized = false
          nativeCoreReady = false
          activeCoreConfigurationKey = null
          suspendedVideoTrack = null
          desiredPaused = true
          clearSeekAudioGuardLocked(restoreMute = false)
          clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
          resetAmbientShaderTrackingLocked()
          updateState {
            it.copy(
              phase = PlaybackPhase.ERROR,
              error = error.message ?: error.javaClass.simpleName,
            )
          }
          throw error
        }
      }
    }

  fun bindSurface(
    surface: Surface,
    width: Int? = null,
    height: Int? = null,
    owner: Any,
  ): Boolean =
    withCore(default = false) {
      if (!surface.isValid) return@withCore false

      // Replacing an already-attached Surface must also detach the hardware video decoder first.
      // Otherwise MediaCodec can race the old ANativeWindow while the new Surface is attached.
      if (_state.value.surfaceAttached && attachedSurfaceOwner !== owner) {
        suspendVideoTrackForSurfaceLossLocked()
        runCatching { MPVLib.detachSurface() }
      }
      MPVLib.attachSurface(surface)
      width?.takeIf { it > 0 }?.let { resolvedWidth ->
        height?.takeIf { it > 0 }?.let { resolvedHeight ->
          MPVLib.setPropertyString("android-surface-size", "${resolvedWidth}x$resolvedHeight")
        }
      }
      MPVLib.setOptionString("force-window", "yes")
      MPVLib.setPropertyString("vo", desiredVideoOutput)
      attachedSurfaceOwner = owner
      updateState { it.copy(surfaceAttached = true) }
      restoreSuspendedVideoTrackLocked()
      true
    }

  fun resizeSurface(
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    withCore(Unit) { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
  }

  fun unbindSurface(owner: Any) {
    withCore(Unit) {
      if (attachedSurfaceOwner !== owner) return@withCore
      if (!_state.value.surfaceAttached) return@withCore

      // Never leave a hardware video decoder attached to a Surface that Android is destroying.
      // Audio remains untouched, so background/audio-only playback continues normally.
      suspendVideoTrackForSurfaceLossLocked()
      runCatching { MPVLib.setPropertyString("vo", "null") }
      runCatching { MPVLib.setOptionString("force-window", "no") }
      runCatching { MPVLib.detachSurface() }
      attachedSurfaceOwner = null
      updateState { it.copy(surfaceAttached = false) }
    }
  }

  fun setVideoOutput(videoOutput: String) {
    desiredVideoOutput = videoOutput
    withCore(Unit, allowInitializing = true) {
      MPVLib.setOptionString("vo", videoOutput)
    }
  }

  fun markBackground() {
    updateState { current ->
      if (current.phase == PlaybackPhase.READY) current.copy(phase = PlaybackPhase.BACKGROUND) else current
    }
  }

  fun markForeground() {
    withCore(Unit) {
      updateState { current ->
        if (current.phase == PlaybackPhase.BACKGROUND) current.copy(phase = PlaybackPhase.READY) else current
      }
      restoreSuspendedVideoTrackLocked()
    }
  }

  /** Stop playback while keeping the app-scoped core ready for a later screen attachment. */
  fun stop(clearQueue: Boolean = true) {
    withCore(Unit) {
      val nextGeneration = _state.value.generation + 1L
      pendingGenerations.clear()
      suspendedVideoTrack = null
      desiredPaused = true

      // Ambient shaders contain dimensions and scale baked for one video. Never leave them attached
      // to the process-wide core after playback ends, even if the Activity/ViewModel that created
      // them has already gone away.
      clearAmbientShadersLocked(resetDesired = true)

      // Stop/quit must silence the native audio output before its decoder/output queues are torn
      // down. Restoring a seek guard's mute state before stop previously let a short buffered tail
      // escape after the Activity had already disappeared.
      clearSeekAudioGuardLocked(restoreMute = true)
      beginPlaybackTransitionAudioGuardLocked()
      runCatching { MPVLib.setPropertyBoolean("pause", true) }
      runCatching { MPVLib.command("stop") }

      if (clearQueue) _queue.value = PlaybackQueueState()
      updateState {
        it.copy(
          phase = PlaybackPhase.IDLE,
          generation = nextGeneration,
          activeGeneration = nextGeneration,
          paused = true,
          currentItem = if (clearQueue) null else _queue.value.currentItem,
          error = null,
        )
      }
      propBoolean.emit("pause", true)
    }
    releaseActiveNetworkStream()
    releaseAuxiliaryNetworkStreams()
  }


  /** Native destruction is reserved for process-level shutdown or an unrecoverable init reset. */
  fun destroy() {
    nativeLock.withLock {
      if (!initialized) return
      destroyLocked()
    }
  }

  /**
   * Silence the audio output immediately without pausing/stopping playback or altering the
   * playback position.
   *
   * The deferred [onDestroy]/[cleanupMPV] teardown ([stop]) already mutes via the playback
   * transition audio guard, but it only runs after the Android Activity is actually destroyed —
   * leaving a window where the native audio buffer keeps playing after the user closes the player.
   * Calling this the instant the user initiates a close mutes the output synchronously so no
   * buffered tail escapes. It does not change `time-pos`, so the resume position captured in
   * `onDestroy` stays accurate. The guard armed here is never restored on this teardown path
   * ([stop] does not schedule a restore, and SHUTDOWN clears it with `restoreMute = false`),
   * so the output remains muted through destruction.
   */
  fun muteForTeardown() {
    withCore(Unit) { beginPlaybackTransitionAudioGuardLocked() }
  }

  private fun destroyLocked() {
    updateState { it.copy(phase = PlaybackPhase.STOPPING) }
    desiredPaused = true
    suspendedVideoTrack = null
    clearSeekAudioGuardLocked(restoreMute = false)
    clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
    runCatching { MPVLib.setPropertyBoolean("mute", true) }
    runCatching { MPVLib.setPropertyBoolean("pause", true) }
    runCatching { MPVLib.setPropertyString("vo", "null") }
    runCatching { MPVLib.detachSurface() }
    runCatching { MPVLib.removeObserver(this) }
    runCatching { MPVLib.destroy() }
      .onFailure { error -> Log.e(TAG, "Failed to destroy libmpv", error) }
    releaseActiveNetworkStreamLocked()
    releaseAuxiliaryNetworkStreamsLocked()
    observers.clear()
    pendingGenerations.clear()
    observedProperties.clear()
    resetAmbientShaderTrackingLocked()
    initialized = false
    nativeCoreReady = false
    activeCoreConfigurationKey = null
    updateState { PlaybackSessionState(phase = PlaybackPhase.UNINITIALIZED) }
  }

  fun replaceQueue(
    items: List<PlaybackItem>,
    currentIndex: Int,
    isExplicitQueue: Boolean = false,
    isM3u: Boolean = false,
  ) {
    nativeLock.withLock {
      _queue.value = PlaybackQueueReducer.replace(_queue.value, items, currentIndex, isExplicitQueue, isM3u)
      updateState { it.copy(currentItem = _queue.value.currentItem) }
    }
  }

  fun clearQueue() {
    replaceQueue(emptyList(), -1)
  }

  fun moveQueueItem(
    from: Int,
    to: Int,
  ): Boolean =
    nativeLock.withLock {
      val next = PlaybackQueueReducer.move(_queue.value, from, to) ?: return@withLock false
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      true
    }

  fun selectQueueItem(index: Int): PlaybackItem? =
    nativeLock.withLock {
      val next = PlaybackQueueReducer.select(_queue.value, index) ?: return@withLock null
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      next.currentItem
    }

  fun setRepeatMode(repeatMode: RepeatMode) {
    nativeLock.withLock { _queue.value = PlaybackQueueReducer.setRepeatMode(_queue.value, repeatMode) }
  }

  fun setShuffleEnabled(enabled: Boolean) {
    nativeLock.withLock { _queue.value = PlaybackQueueReducer.setShuffleEnabled(_queue.value, enabled) }
  }

  fun hasNext(): Boolean = nativeLock.withLock { PlaybackQueueReducer.hasNext(_queue.value) }

  fun hasPrevious(): Boolean = nativeLock.withLock { PlaybackQueueReducer.hasPrevious(_queue.value) }

  fun selectNext(): PlaybackItem? =
    nativeLock.withLock {
      val next = PlaybackQueueReducer.next(_queue.value) ?: return@withLock null
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      next.currentItem
    }

  fun selectPrevious(): PlaybackItem? =
    nativeLock.withLock {
      val next = PlaybackQueueReducer.previous(_queue.value) ?: return@withLock null
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      next.currentItem
    }

  fun playQueueItem(index: Int): PlaybackItem? =
    nativeLock.withLock {
      val item = selectQueueItem(index) ?: return@withLock null
      load(item)
      item
    }

  fun playNext(): PlaybackItem? =
    nativeLock.withLock {
      val item = selectNext() ?: return@withLock null
      load(item)
      item
    }

  fun playPrevious(): PlaybackItem? =
    nativeLock.withLock {
      val item = selectPrevious() ?: return@withLock null
      load(item)
      item
    }

  fun load(
    item: PlaybackItem,
    selectVideo: Boolean? = null,
  ): Long {
    val resolved = resolvePlayableUri(item)
    return try {
      val generation = load(playableUri = resolved.uri, item = item, selectVideo = selectVideo)
      if (generation < 0L) {
        resolved.registration?.let(::releaseNetworkStream)
        generation
      } else {
        val (previous, previousAuxiliary) =
          nativeLock.withLock {
            val old = activeNetworkStream
            activeNetworkStream = resolved.registration
            val auxiliary = auxiliaryNetworkStreams.values.toList()
            auxiliaryNetworkStreams.clear()
            old to auxiliary
          }
        if (previous != null && previous != resolved.registration) releaseNetworkStream(previous)
        previousAuxiliary.forEach(::releaseNetworkStream)
        generation
      }
    } catch (error: Throwable) {
      resolved.registration?.let(::releaseNetworkStream)
      throw error
    }
  }

  private fun load(
    playableUri: String,
    item: PlaybackItem? = null,
    selectVideo: Boolean? = null,
  ): Long =
    withCore(default = -1L) {
      // A preceding surface detach may have left the outgoing file at vid=no. Select video for the
      // incoming file when the caller expects to render it, or when a valid render Surface is
      // attached. Callers must pass an explicit choice for foreground loads: on cold start the
      // Surface attaches only after this load reaches mpv, and a global vid=no would make a
      // video-only (soundless) file abort with "No video or audio streams selected". The choice is
      // file-local in the load command instead of mutating the process-wide vid property.
      val selectVideoForNewFile = selectVideo ?: _state.value.surfaceAttached

      // An OUTPUT Ambient shader bakes the previous video's aspect ratio into its GLSL. Because the
      // libmpv core outlives PlayerActivity, a late/cancelled Ambient job can otherwise poison the
      // next file even when the UI preference is OFF. Start every replacement load from a clean,
      // identity-scaled shader state; an enabled Ambient mode will re-append after FILE_LOADED.
      clearAmbientShadersLocked(resetDesired = true)

      // A saved video-track id belongs to the outgoing file only. Never carry it into a new load.
      suspendedVideoTrack = null
      desiredPaused = false
      clearSeekAudioGuardLocked(restoreMute = true)

      // Keep replacement/startup audio muted until mpv has restarted cleanly. FILE_LOADED can be
      // followed by saved-position and audio-track restoration; without this guard tiny fragments
      // from the pre-restore timeline can reach AudioTrack and sound like a glitch/warble.
      beginPlaybackTransitionAudioGuardLocked()

      val generation = _state.value.generation + 1L
      pendingGenerations.addLast(generation)
      val resolvedItem = item ?: PlaybackItem.fromUri(playableUri)
      updateState {
        it.copy(
          phase = PlaybackPhase.LOADING,
          generation = generation,
          paused = false,
          currentItem = resolvedItem,
          error = null,
        )
      }
      val userAgent = PlaybackHttpHeaders.userAgent(resolvedItem.headers)
      val headerFields = PlaybackHttpHeaders.toMpvHeaderFields(resolvedItem.headers)
      MPVLib.setPropertyString("user-agent", userAgent.orEmpty())
      MPVLib.setPropertyString("http-header-fields", headerFields)
      MPVLib.setPropertyString("force-media-title", "")



      // Keep the native core paused while tracks/decoder/output are being replaced. When a valid
      // render Surface is attached, explicitly make vid=auto file-local to this new load. This
      // prevents a preceding vid=no from producing "No video or audio streams selected" on
      // video-only files while preserving video suppression for true background/no-Surface loads.
      val loadOptions = if (selectVideoForNewFile) "pause=yes,vid=auto" else "pause=yes"
      MPVLib.command("loadfile", playableUri, "replace", "-1", loadOptions)
      propBoolean.emit("pause", false)
      generation
    }

  fun isCurrentGeneration(generation: Long): Boolean = generation > 0L && _state.value.generation == generation

  fun addObserver(observer: MPVLib.EventObserver) {
    observers += observer
  }

  fun removeObserver(observer: MPVLib.EventObserver) {
    observers -= observer
  }

  fun command(vararg command: String) {
    withCore(Unit) {
      if (command.firstOrNull() == "seek") beginSeekAudioGuardLocked()
      if (handleAmbientShaderCommandLocked(command)) return@withCore
      MPVLib.command(*command)
    }
  }

  /** Executes a media-specific command only while its load generation is still current. */
  fun commandForGeneration(
    expectedGeneration: Long,
    vararg command: String,
  ): Boolean =
    nativeLock.withLock {
      if (!initialized || _state.value.generation != expectedGeneration) return@withLock false
      if (command.firstOrNull() == "seek") beginSeekAudioGuardLocked()
      if (!handleAmbientShaderCommandLocked(command)) MPVLib.command(*command)
      true
    }

  fun commandNode(vararg command: String): MPVNode? = withCore(null) { MPVLib.commandNode(*command) }

  fun observeProperty(
    property: String,
    format: Int,
  ) {
    withCore(Unit, allowInitializing = true) {
      if (observedProperties.add(property to format)) MPVLib.observeProperty(property, format)
    }
  }

  fun setOptionString(
    name: String,
    value: String,
  ): Int = withCore(-1, allowInitializing = true) { MPVLib.setOptionString(name, value) }

  fun getPropertyInt(property: String): Int? = withReadyCore(null) { MPVLib.getPropertyInt(property) }

  fun setPropertyInt(
    property: String,
    value: Int,
  ) = withCore(Unit) {
    MPVLib.setPropertyInt(property, value)
    if (property == "vid" && value > 0) suspendedVideoTrack = null
  }

  fun getPropertyDouble(property: String): Double? = withReadyCore(null) { MPVLib.getPropertyDouble(property) }

  fun setPropertyDouble(
    property: String,
    value: Double,
  ) = withCore(Unit) {
    when (property) {
      "video-scale-x" -> {
        desiredAmbientScaleX = value
        MPVLib.setPropertyDouble(property, value)
      }
      "video-scale-y" -> {
        desiredAmbientScaleY = value
        MPVLib.setPropertyDouble(property, value)
      }
      else -> MPVLib.setPropertyDouble(property, value)
    }
  }

  fun getPropertyFloat(property: String): Float? = withReadyCore(null) { MPVLib.getPropertyFloat(property) }

  fun setPropertyFloat(
    property: String,
    value: Float,
  ) = withCore(Unit) { MPVLib.setPropertyFloat(property, value) }

  fun getPropertyBoolean(property: String): Boolean? = withReadyCore(null) { MPVLib.getPropertyBoolean(property) }

  fun setPropertyBoolean(
    property: String,
    value: Boolean,
  ) = withCore(Unit) {
    if (property == "pause") {
      desiredPaused = value
      // During a replacement load the native core deliberately stays paused until FILE_LOADED.
      // Record user/service intent now, then apply it once decoder/track setup is complete.
      if (_state.value.phase != PlaybackPhase.LOADING) {
        MPVLib.setPropertyBoolean(property, value)
      }
      updateState { it.copy(paused = value) }
      propBoolean.emit(property, value)
    } else if (property == "mute" && playbackTransitionAudioGuardPreviousMute != null) {
      // A user mute/unmute action during startup/teardown should update the value that will be
      // restored, but must not open the guard and leak transition audio immediately.
      playbackTransitionAudioGuardPreviousMute = value
      MPVLib.setPropertyBoolean("mute", true)
    } else {
      MPVLib.setPropertyBoolean(property, value)
    }
  }

  /** Atomically toggles pause so rapid UI/media-button taps cannot race separate reads and writes. */
  fun togglePause(): Boolean? =
    withCore(default = null) {
      val currentPaused =
        if (_state.value.phase == PlaybackPhase.LOADING) {
          desiredPaused
        } else {
          MPVLib.getPropertyBoolean("pause") ?: desiredPaused
        }
      val nextPaused = !currentPaused
      desiredPaused = nextPaused
      if (_state.value.phase != PlaybackPhase.LOADING) {
        MPVLib.setPropertyBoolean("pause", nextPaused)
      }
      updateState { it.copy(paused = nextPaused) }
      propBoolean.emit("pause", nextPaused)
      nextPaused
    }

  fun getPropertyString(property: String): String? = withReadyCore(null) { MPVLib.getPropertyString(property) }

  fun getPropertyNode(property: String): MPVNode? = withReadyCore(null) { MPVLib.getPropertyNode(property) }

  fun setPropertyString(
    property: String,
    value: String,
  ) = withCore(Unit) {
    if (property == "vid") {
      if (value == "no" && _state.value.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
        val activeVid = MPVLib.getPropertyInt("vid") ?: -1
        if (activeVid > 0) {
          suspendedVideoTrack = SuspendedVideoTrack(activeVid, _state.value.generation)
        }
      } else if (value != "no") {
        suspendedVideoTrack = null
      }
    }

    // Some shader-stack managers replace the whole list instead of using change-list/remove.
    // If that replacement drops Ambient, restore the base video scale before the next frame.
    if (property == "glsl-shaders" && activeAmbientShaderPaths.isNotEmpty() && !value.contains(AMBIENT_SHADER_PREFIX)) {
      resetActiveAmbientScaleLocked()
      activeAmbientShaderPaths.clear()
    }
    MPVLib.setPropertyString(property, value)
  }

  fun grabThumbnail(dimension: Int): Bitmap? = withCore(null) { MPVLib.grabThumbnail(dimension) }

  fun grabThumbnailFast(
    path: String,
    position: Double = 0.0,
    dimension: Int,
    useHwDec: Boolean = true,
  ): Bitmap? {
    // Fast thumbnails use their own native player. Holding nativeLock while they decode a
    // network/keyframe-heavy source blocks every command sent to the active player, including
    // seek, pause and surface updates. Only snapshot core availability under the lock.
    if (!nativeLock.withLock { initialized }) return null
    return MPVLib.grabThumbnailFast(path, position, dimension, useHwDec)
  }

  fun setThumbnailJavaVM(context: Context) {
    withCore(Unit) { MPVLib.setThumbnailJavaVM(context.applicationContext) }
  }

  /**
   * Registers a network sidecar (for example an external subtitle) under this media generation.
   * Sidecars are released automatically on the next load, stop, shutdown, or core destruction.
   */
  fun registerAuxiliaryNetworkStream(
    connectionId: Long,
    filePath: String,
    fileSize: Long = -1L,
    mimeType: String = "application/octet-stream",
    expectedGeneration: Long? = null,
  ): String? =
    nativeLock.withLock {
      if (!initialized || (expectedGeneration != null && _state.value.generation != expectedGeneration)) {
        return@withLock null
      }

      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "sidecar-${streamSequence.incrementAndGet()}"
      val uri =
        proxy.registerStream(
          streamId = streamId,
          connectionId = connectionId,
          filePath = filePath,
          fileSize = fileSize,
          mimeType = mimeType,
        )
      auxiliaryNetworkStreams[uri] = NetworkStreamRegistration(proxy = proxy, streamId = streamId)
      uri
    }

  fun unregisterAuxiliaryNetworkStream(uri: String) {
    val registration = nativeLock.withLock { auxiliaryNetworkStreams.remove(uri) }
    registration?.let(::releaseNetworkStream)
  }

  override fun eventProperty(property: String) {
    propBoolean.emit(property, null)
    propString.emit(property, null)
    propDouble.emit(property, null)
    propFloat.emit(property, null)
    propLong.emit(property, null)
    propInt.emit(property, null)
    propNode.emit(property, null)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property) } }
  }

  override fun eventProperty(
    property: String,
    value: Long,
  ) {
    propLong.emit(property, value)
    propInt.emit(property, value.toInt())
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    val effectiveValue =
      if (property == "pause" && _state.value.phase == PlaybackPhase.LOADING) desiredPaused else value
    if (property == "pause") updateState { it.copy(paused = effectiveValue) }
    propBoolean.emit(property, effectiveValue)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, effectiveValue) } }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    propString.emit(property, value)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    propDouble.emit(property, value)
    propFloat.emit(property, value.toFloat())
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    propNode.emit(property, value)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    val shouldForward =
      nativeLock.withLock {
        when (eventId) {
          MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
            val active = if (pendingGenerations.isEmpty()) _state.value.generation else pendingGenerations.removeFirst()
            updateState { it.copy(phase = PlaybackPhase.LOADING, activeGeneration = active) }
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
            val current = _state.value
            if (current.activeGeneration != 0L && current.activeGeneration != current.generation) {
              Log.d(TAG, "Ignoring stale FILE_LOADED generation ${current.activeGeneration}; current=${current.generation}")
              false
            } else {
              // Track/decoder replacement is now complete. Apply the latest user/service intent
              // once instead of allowing pause writes to race the load operation.
              MPVLib.setPropertyBoolean("pause", desiredPaused)
              updateState {
                it.copy(
                  phase = if (it.phase == PlaybackPhase.BACKGROUND) PlaybackPhase.BACKGROUND else PlaybackPhase.READY,
                  paused = desiredPaused,
                  error = null,
                )
              }
              propBoolean.emit("pause", desiredPaused)
              restoreSuspendedVideoTrackLocked()
              true
            }
          }
          MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
            scheduleSeekAudioGuardRestoreLocked(SEEK_AUDIO_RESTORE_DELAY_MS)
            schedulePlaybackTransitionAudioGuardRestoreLocked(PLAYBACK_TRANSITION_AUDIO_RESTORE_DELAY_MS)
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
            // A failed/finished file no longer has readable timeline properties. Publishing IDLE
            // prevents UI polling from repeatedly querying unavailable time-pos/duration values.
            val current = _state.value
            if (current.activeGeneration == current.generation) {
              updateState { it.copy(phase = PlaybackPhase.IDLE, activeGeneration = 0L, paused = true) }
              propBoolean.emit("pause", true)
            }
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
            releaseActiveNetworkStream()
            releaseAuxiliaryNetworkStreams()
            suspendedVideoTrack = null
            desiredPaused = true
            clearSeekAudioGuardLocked(restoreMute = false)
            clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
            resetAmbientShaderTrackingLocked()
            initialized = false
            nativeCoreReady = false
            updateState { it.copy(phase = PlaybackPhase.UNINITIALIZED, surfaceAttached = false, paused = true) }
            true
          }
          else -> true
        }
      }

    if (shouldForward) {
      observerSnapshot().forEach { observer -> runCatching { observer.event(eventId, data) } }
    }
  }

  /**
   * Rapid keyframe/exact seeks can expose tiny decoded audio fragments between decoder flushes,
   * which sounds like echo/warble while scrubbing. Temporarily mute only the active seek window;
   * the user's original mute state is restored after mpv reports playback restart.
   */
  private fun beginSeekAudioGuardLocked() {
    if (_state.value.paused || _state.value.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return

    if (seekAudioGuardPreviousMute == null) {
      val wasMuted = MPVLib.getPropertyBoolean("mute") ?: false
      seekAudioGuardPreviousMute = wasMuted
      if (!wasMuted) runCatching { MPVLib.setPropertyBoolean("mute", true) }
    }

    seekAudioGuardToken++
    scheduleSeekAudioGuardRestoreLocked(SEEK_AUDIO_FALLBACK_RESTORE_MS)
  }

  private fun scheduleSeekAudioGuardRestoreLocked(delayMs: Long) {
    if (seekAudioGuardPreviousMute == null) return
    val token = seekAudioGuardToken
    seekAudioGuardHandler.postDelayed(
      {
        nativeLock.withLock {
          if (!initialized || token != seekAudioGuardToken) return@withLock
          clearSeekAudioGuardLocked(restoreMute = true)
        }
      },
      delayMs,
    )
  }

  private fun clearSeekAudioGuardLocked(restoreMute: Boolean) {
    val previousMute = seekAudioGuardPreviousMute
    seekAudioGuardPreviousMute = null
    seekAudioGuardToken++
    if (restoreMute && initialized && previousMute != null) {
      runCatching { MPVLib.setPropertyBoolean("mute", previousMute) }
    }
  }

  /**
   * Mutes only decoder/output transitions. Unlike the seek guard this can span stop -> next load,
   * which guarantees that an Android AudioTrack cannot drain a stale tail after quit and that the
   * first audible samples of a new file are from its settled timeline/track state.
   */
  private fun beginPlaybackTransitionAudioGuardLocked() {
    if (playbackTransitionAudioGuardPreviousMute == null) {
      playbackTransitionAudioGuardPreviousMute = MPVLib.getPropertyBoolean("mute") ?: false
    }
    runCatching { MPVLib.setPropertyBoolean("mute", true) }
    playbackTransitionAudioGuardToken++
  }

  private fun schedulePlaybackTransitionAudioGuardRestoreLocked(delayMs: Long) {
    if (playbackTransitionAudioGuardPreviousMute == null) return
    val token = ++playbackTransitionAudioGuardToken
    playbackTransitionAudioGuardHandler.postDelayed(
      {
        nativeLock.withLock {
          if (!initialized || token != playbackTransitionAudioGuardToken) return@withLock
          clearPlaybackTransitionAudioGuardLocked(restoreMute = true)
        }
      },
      delayMs,
    )
  }

  private fun clearPlaybackTransitionAudioGuardLocked(restoreMute: Boolean) {
    val previousMute = playbackTransitionAudioGuardPreviousMute
    playbackTransitionAudioGuardPreviousMute = null
    playbackTransitionAudioGuardToken++
    if (restoreMute && initialized && previousMute != null) {
      runCatching { MPVLib.setPropertyBoolean("mute", previousMute) }
    }
  }

  private fun handleAmbientShaderCommandLocked(command: Array<out String>): Boolean {
    if (command.size < 3 || command[0] != "change-list" || command[1] != "glsl-shaders") return false

    val action = command[2]
    if (action == "clr") {
      if (activeAmbientShaderPaths.isNotEmpty()) resetActiveAmbientScaleLocked()
      activeAmbientShaderPaths.clear()
      MPVLib.command(*command)
      return true
    }

    val path = command.getOrNull(3) ?: return false
    val isAmbient = isAmbientShaderPath(path)

    if (action == "set" && !isAmbient && activeAmbientShaderPaths.isNotEmpty()) {
      resetActiveAmbientScaleLocked()
      activeAmbientShaderPaths.clear()
      MPVLib.command(*command)
      return true
    }
    if (!isAmbient) return false

    when (action) {
      "remove" -> {
        // Reset first so there is never a rendered frame with Ambient's expanded source quad but
        // without the remapping shader that restores the original picture in the centre.
        resetActiveAmbientScaleLocked()
        activeAmbientShaderPaths.remove(path)
        MPVLib.command(*command)
      }
      "append", "add", "pre", "set" -> {
        // A cancelled/debounced Ambient coroutine may finish its file write after Ambient was turned
        // off. Identity scale is the process-wide teardown state, so never let that late shader
        // resurrect itself. This is especially important for Flow because SCALE_X/Y are baked into
        // the GLSL and can crop the centre picture even after the real video scale was reset.
        if (ambientScaleIsIdentityLocked()) {
          activeAmbientShaderPaths.remove(path)
          resetActiveAmbientScaleLocked()
          Log.w(TAG, "Ignored stale Ambient shader install while scale is identity: $path")
          return true
        }

        // Install the shader first. Only then expose the staged scale values to the renderer.
        MPVLib.command(*command)
        if (action == "set") activeAmbientShaderPaths.clear()
        activeAmbientShaderPaths += path
        applyDesiredAmbientScaleLocked()
      }
      else -> return false
    }
    return true
  }

  private fun isAmbientShaderPath(path: String): Boolean {
    val fileName = path.substringAfterLast('/').substringAfterLast('\\')
    return fileName.startsWith(AMBIENT_SHADER_PREFIX) && fileName.endsWith(AMBIENT_SHADER_SUFFIX)
  }

  private fun ambientScaleIsIdentityLocked(): Boolean =
    kotlin.math.abs(desiredAmbientScaleX - 1.0) <= AMBIENT_SCALE_EPSILON &&
      kotlin.math.abs(desiredAmbientScaleY - 1.0) <= AMBIENT_SCALE_EPSILON

  private fun clearAmbientShadersLocked(resetDesired: Boolean) {
    // Reset the real video quad before removing OUTPUT remappers. This avoids exposing a single
    // expanded/cropped frame during teardown.
    resetActiveAmbientScaleLocked()

    val stalePaths = activeAmbientShaderPaths.toList()
    activeAmbientShaderPaths.clear()
    stalePaths.forEach { path ->
      runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", path) }
        .onFailure { error -> Log.w(TAG, "Failed to remove stale Ambient shader $path", error) }
    }

    if (resetDesired) {
      desiredAmbientScaleX = 1.0
      desiredAmbientScaleY = 1.0
    }
  }

  private fun applyDesiredAmbientScaleLocked() {
    // Bypass the interceptor — we already hold the staged values and need them applied
    // to the renderer immediately after the ambient shader has been installed.
    MPVLib.setPropertyDouble("video-scale-x", desiredAmbientScaleX)
    MPVLib.setPropertyDouble("video-scale-y", desiredAmbientScaleY)
  }

  private fun resetActiveAmbientScaleLocked() {
    runCatching { MPVLib.setPropertyDouble("video-scale-x", 1.0) }
    runCatching { MPVLib.setPropertyDouble("video-scale-y", 1.0) }
  }

  private fun resetAmbientShaderTrackingLocked() {
    activeAmbientShaderPaths.clear()
    desiredAmbientScaleX = 1.0
    desiredAmbientScaleY = 1.0
  }

  private fun suspendVideoTrackForSurfaceLossLocked() {
    val current = _state.value
    if (current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return
    val activeVid = MPVLib.getPropertyInt("vid") ?: -1
    if (activeVid > 0) {
      suspendedVideoTrack = SuspendedVideoTrack(activeVid, current.generation)
      // Stop video decoding before the ANativeWindow disappears. Audio remains active.
      runCatching { MPVLib.setPropertyString("vid", "no") }
    }
  }

  private fun restoreSuspendedVideoTrackLocked() {
    val suspended = suspendedVideoTrack ?: return
    val current = _state.value
    if (suspended.generation != current.generation) {
      suspendedVideoTrack = null
      return
    }
    if (!current.surfaceAttached || current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return

    runCatching { MPVLib.setPropertyInt("vid", suspended.id) }
      .onSuccess {
        suspendedVideoTrack = null
        Log.d(TAG, "Restored video track ${suspended.id} after Surface reattachment")
      }.onFailure { error ->
        Log.w(TAG, "Failed to restore video track ${suspended.id} after Surface reattachment", error)
      }
  }

  private fun resolvePlayableUri(item: PlaybackItem): ResolvedPlayable {
    val reference =
      NetworkPlaybackUri.parse(item.playableUri)
        ?: item.networkSource?.let { source ->
          NetworkPlaybackUri.parse(NetworkPlaybackUri.create(source.connectionId, source.relativePath))
        }
    if (reference != null) {
      val proxy = NetworkStreamingProxy.getInstance()
      val streamId = "playback-${streamSequence.incrementAndGet()}"
      val uri =
        proxy.registerStream(
          streamId = streamId,
          connectionId = reference.connectionId,
          filePath = reference.path.value,
          mimeType = item.mimeType ?: "application/octet-stream",
        )
      return ResolvedPlayable(uri, NetworkStreamRegistration(proxy = proxy, streamId = streamId))
    }

    if (M3uPlaybackPolicy.shouldProxyHls(item.playableUri, item.mimeType)) {
      val hlsProxy = HlsStreamingProxy.getInstance()
      val streamId = "hls-${streamSequence.incrementAndGet()}"
      val userAgent = PlaybackHttpHeaders.userAgent(item.headers)
      val uri =
        hlsProxy.registerStream(
          streamId = streamId,
          sourceUrl = item.playableUri,
          headers = item.headers,
          userAgent = userAgent,
        )
      Log.d(TAG, "Routing HLS stream through HlsStreamingProxy: $uri")
      return ResolvedPlayable(uri, NetworkStreamRegistration(hlsProxy = hlsProxy, streamId = streamId))
    }

    // fd:// descriptors are single-use: mpv consumes and closes them on their first load. Replays
    // of queue items or persisted sessions must re-open a fresh descriptor from the content URI.
    if (item.playableUri.startsWith("fd://") && item.originalUri.startsWith("content://")) {
      applicationContext?.let { context ->
        Uri.parse(item.originalUri).openContentFd(context)?.let { return ResolvedPlayable(it) }
      }
    }

    if (!item.playableUri.startsWith("content://")) return ResolvedPlayable(item.playableUri)
    val context = applicationContext ?: return ResolvedPlayable(item.playableUri)
    return ResolvedPlayable(Uri.parse(item.playableUri).openContentFd(context) ?: item.playableUri)
  }

  private fun releaseActiveNetworkStream() {
    val registration = nativeLock.withLock { activeNetworkStream.also { activeNetworkStream = null } }
    registration?.let(::releaseNetworkStream)
  }

  private fun releaseActiveNetworkStreamLocked() {
    val registration = activeNetworkStream
    activeNetworkStream = null
    registration?.let(::releaseNetworkStream)
  }

  private fun releaseAuxiliaryNetworkStreams() {
    val registrations =
      nativeLock.withLock {
        auxiliaryNetworkStreams.values.toList().also { auxiliaryNetworkStreams.clear() }
      }
    registrations.forEach(::releaseNetworkStream)
  }

  private fun releaseAuxiliaryNetworkStreamsLocked() {
    val registrations = auxiliaryNetworkStreams.values.toList()
    auxiliaryNetworkStreams.clear()
    registrations.forEach(::releaseNetworkStream)
  }

  private fun releaseNetworkStream(registration: NetworkStreamRegistration) {
    runCatching {
      registration.proxy?.unregisterStream(registration.streamId)
      registration.hlsProxy?.unregisterStream(registration.streamId)
    }.onFailure { error -> Log.w(TAG, "Failed to release network stream", error) }
  }

  private fun observerSnapshot(): List<MPVLib.EventObserver> = observers.toList()

  /** Re-register property flows when a previously destroyed native core is recreated. */
  private fun reobserveTrackedProperties() {
    propBoolean.reobserve()
    propString.reobserve()
    propDouble.reobserve()
    propFloat.reobserve()
    propLong.reobserve()
    propInt.reobserve()
    propNode.reobserve()
  }

  private inline fun <T> withCore(
    default: T,
    allowInitializing: Boolean = true,
    block: () -> T,
  ): T =
    nativeLock.withLock {
      if (!initialized && !(allowInitializing && _state.value.phase == PlaybackPhase.INITIALIZING)) {
        return@withLock default
      }
      block()
    }

  private inline fun <T> withReadyCore(
    default: T,
    block: () -> T,
  ): T =
    nativeLock.withLock {
      if (!nativeCoreReady) return@withLock default
      block()
    }

  private inline fun updateState(transform: (PlaybackSessionState) -> PlaybackSessionState) {
    _state.update(transform)
  }
}