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
import app.gyrolet.mpvrx.data.network.proxy.XtreamStreamingProxy
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.XtreamPlaybackUri
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.JellyfinSessionReporter
import app.gyrolet.mpvrx.utils.media.HttpUtils
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.ui.player.controls.components.sheets.EqualizerState
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.io.File
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
  val engine: AudioEngineKind = AudioEngineKind.Mpv,
)

data class PlaybackPositionRestoreOverride(
  val positionSeconds: Double?,
  val paused: Boolean,
)

/** A session-backed property that remains safe to access before playback is initialized. */
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
 * The one process-wide owner of playback state and the active engine.
 *
 * Android screens and the playback service may observe or control this object, but none of them
 * owns the native core. This makes rotation, PiP, background playback, and notification re-entry
 * attachment changes instead of competing create/destroy cycles.
 */
@Suppress("TooManyFunctions")
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object PlaybackSession : MPVLib.EventObserver {
  private const val TAG = "PlaybackSession"
  private const val SEEK_AUDIO_RESTORE_DELAY_MS = 60L
  private const val SEEK_AUDIO_FALLBACK_RESTORE_MS = 750L
  private const val PLAYBACK_TRANSITION_AUDIO_RESTORE_DELAY_MS = 180L
  private const val AMBIENT_SHADER_PREFIX = "ambient_"
  private const val AMBIENT_SHADER_SUFFIX = ".glsl"
  private const val AMBIENT_SCALE_EPSILON = 0.000001
  private val TIMELINE_PROPERTIES =
    setOf(
      "time-pos",
      "duration",
      "playback-time",
      "playtime-remaining",
      "time-remaining",
      "percent-pos",
    )
  private val AUDIO_SUBTITLE_TRACK_PROPERTIES = setOf("aid", "sid", "secondary-sid")

  private enum class EndFileReason {
    EOF,
    STOP,
    QUIT,
    ERROR,
    REDIRECT,
    UNKNOWN,
  }

  private data class NetworkStreamRegistration(
    val proxy: NetworkStreamingProxy? = null,
    val hlsProxy: HlsStreamingProxy? = null,
    val xtreamProxy: XtreamStreamingProxy? = null,
    val streamId: String,
  )

  private data class ResolvedPlayable(
    val uri: String,
    val registration: NetworkStreamRegistration? = null,
  )

  /** Keeps a hardware-decoded track across the loss and replacement of its Android Surface. */
  private data class SuspendedVideoTrack(
    val id: Int,
    val generation: Long,
  )

  private val nativeLock = ReentrantLock(true)
  private val audioPreferences by lazy {
    org.koin.java.KoinJavaComponent.get<AudioPreferences>(AudioPreferences::class.java)
  }
  private val observers = CopyOnWriteArraySet<MPVLib.EventObserver>()
  private val _state = MutableStateFlow(PlaybackSessionState())
  private val _queue = MutableStateFlow(PlaybackQueueState())
  private val _audioState = MutableStateFlow(AudioEngineSnapshot())
  private val _audioEvents = MutableSharedFlow<AudioPlaybackEvent>(extraBufferCapacity = 32)
  private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val audioTransitionBlocks = MutableStateFlow(emptySet<String>())
  private val audioDuckOwners = mutableSetOf<String>()
  private val audioPreloadSequence = AtomicLong()
  @Volatile private var audioEngine: AudioPlaybackEngine? = null
  private var audioReleaseCompletion = CompletableDeferred(Unit)
  private var audioObserversStarted = false
  private var audioProcessing = AudioProcessingSettings()
  private var audioRetryJob: kotlinx.coroutines.Job? = null
  private var audioLoadJob: kotlinx.coroutines.Job? = null
  @Volatile private var audioPreloadJob: kotlinx.coroutines.Job? = null
  private var audioRetryAttempts = 0
  private var acceptedCrossfadeQueue: PlaybackQueueState? = null
  private var audioHistoryGeneration = 0L
  private var audioHistoryAllowed = false
  private var audioHistoryPlaylistId: Int? = null
  private var audioLeavingGeneration = 0L
  private var audioLoopStartMs: Long? = null
  private var audioLoopEndMs: Long? = null
  private data class AudioSave(
    val snapshot: AudioEngineSnapshot,
    val completion: CompletableDeferred<Unit>? = null,
    val queue: PlaybackQueueState = _queue.value,
    val retainQueue: Boolean = audioHistoryAllowed,
  )
  private val audioSaves = Channel<AudioSave>(Channel.UNLIMITED)
  private var lastAudioSaveTime = 0L
  private var audioReporter: JellyfinSessionReporter? = null
  private var audioReporterGeneration = 0L
  private var audioReportedPaused = true
  private var lastAudioReportTime = 0L
  val equalizerState = MutableStateFlow(EqualizerState())
  private val _videoZoom = MutableStateFlow(0f)
  private val _videoPanX = MutableStateFlow(0f)
  private val _videoPanY = MutableStateFlow(0f)
  private val streamSequence = AtomicLong()
  private val observedProperties = mutableSetOf<Pair<String, Int>>()
  private val seekAudioGuardHandler = Handler(Looper.getMainLooper())
  private val playbackTransitionAudioGuardHandler = Handler(Looper.getMainLooper())

  val state: StateFlow<PlaybackSessionState> = _state.asStateFlow()
  val queue: StateFlow<PlaybackQueueState> = _queue.asStateFlow()
  val audioState: StateFlow<AudioEngineSnapshot> = _audioState.asStateFlow()
  val audioEvents = _audioEvents.asSharedFlow()
  val usingExoPlayer: Boolean get() = _state.value.engine == AudioEngineKind.ExoPlayer
  val videoZoom: StateFlow<Float> = _videoZoom.asStateFlow()
  val videoPanX: StateFlow<Float> = _videoPanX.asStateFlow()
  val videoPanY: StateFlow<Float> = _videoPanY.asStateFlow()

  val videoBackgroundPlaybackEnabled: Flow<Boolean> by lazy {
    audioPreferences.videoBackgroundPlaybackChanges(state.map(::videoBackgroundPlaybackId).distinctUntilChanged())
  }

  internal fun videoBackgroundPlaybackId(playbackState: PlaybackSessionState): String? =
    playbackState.currentItem?.takeUnless { it.audiobook != null || it.isDefinitelyAudioOnly() }?.stableId

  fun isVideoBackgroundPlaybackEnabled(): Boolean =
    audioPreferences.getVideoBackgroundPlayback(videoBackgroundPlaybackId(_state.value))

  fun setVideoBackgroundPlaybackEnabled(enabled: Boolean) {
    nativeLock.withLock {
      audioPreferences.setVideoBackgroundPlayback(videoBackgroundPlaybackId(_state.value), enabled)
    }
  }

  fun setVideoTransformZoom(zoom: Float) {
    _videoZoom.value = zoom
  }

  fun setVideoTransformPan(
    x: Float,
    y: Float,
  ) {
    _videoPanX.value = x
    _videoPanY.value = y
  }

  @Volatile
  private var initialized = false
  private var nativeCoreReady = false
  private var pendingEofSeekGeneration: Long? = null
  private var applicationContext: Context? = null
  private var desiredVideoOutput = "gpu"
  private var activeCoreConfigurationKey: String? = null
  private var activeUserScriptsKey: String? = null
  private var attachedSurfaceOwner: Any? = null
  private var activeNetworkStream: NetworkStreamRegistration? = null
  private val auxiliaryNetworkStreams = linkedMapOf<String, NetworkStreamRegistration>()
  private var suspendedVideoTrack: SuspendedVideoTrack? = null
  private var deferredVideoSelectionGeneration: Long? = null
  private var pendingStopClearQueue = false
  private var supersededStopGeneration = 0L
  @Volatile private var desiredPaused = true
  private var loadedGeneration = 0L
  private var loadedPlaybackItem: PlaybackItem? = null
  private var loadedAudiobookDurationMs = 0L
  private var loadedAudiobookEnded = false
  private var speedBeforeAudiobook: Float? = null
  private var defaultUserAgent: String? = null
  private var pendingPositionRestoreGeneration = 0L
  private var pendingPositionRestoreOverride: Pair<Long, PlaybackPositionRestoreOverride>? = null
  private var initialPositionGeneration = 0L
  private var seekAudioGuardToken = 0L
  private var seekAudioGuardPreviousMute: Boolean? = null
  private var playbackTransitionAudioGuardToken = 0L
  private var playbackTransitionAudioGuardPreviousMute: Boolean? = null
  private var playbackTransitionAudioGuardCanRestore = false
  private val activeAmbientShaderPaths = linkedSetOf<String>()
  private var desiredAmbientScaleX = 1.0
  private var desiredAmbientScaleY = 1.0

  val isInitialized: Boolean
    get() = initialized || usingExoPlayer && applicationContext != null && _state.value.currentItem != null

  fun invalidateCoreConfiguration() {
    nativeLock.withLock { activeCoreConfigurationKey = null }
  }

  internal fun userScriptsNeedReload(currentKey: String): Boolean = nativeLock.withLock {
    initialized && activeUserScriptsKey != currentKey
  }

  fun reloadMpvConfig(configPath: String): Boolean =
    nativeLock.withLock {
      activeCoreConfigurationKey = null
      if (!initialized) return@withLock false
      runCatching {
        MPVLib.command("load-config-file", configPath)
        true
      }.onFailure { error ->
        Log.e(TAG, "Failed to reload mpv.conf", error)
      }.getOrDefault(false)
    }

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
    userScriptsKey: String? = null,
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

        val retainedAudio = _state.value.takeIf {
          usingExoPlayer && (audioEngine != null || it.phase == PlaybackPhase.LOADING)
        }
        applicationContext = context.applicationContext
        nativeCoreReady = false
        observedProperties.clear()
        suspendedVideoTrack = null
        deferredVideoSelectionGeneration = null
        pendingStopClearQueue = false
        supersededStopGeneration = 0L
        desiredPaused = retainedAudio?.paused ?: true
        loadedGeneration = retainedAudio?.generation ?: 0L
        defaultUserAgent = null
        pendingPositionRestoreGeneration = 0L
        pendingPositionRestoreOverride = null
        initialPositionGeneration = 0L
        clearSeekAudioGuardLocked(restoreMute = false)
        clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
        resetAmbientShaderTrackingLocked()
        if (retainedAudio == null) updateState { it.copy(phase = PlaybackPhase.INITIALIZING, error = null) }
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
          // Preserve the effective default after mpv.conf has been parsed. Per-media request
          // headers may temporarily override it, but must not leak into the next item.
          defaultUserAgent = MPVLib.getPropertyString("user-agent")
          postInitOptions()
          MPVLib.getPropertyString("vo")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { desiredVideoOutput = it }
          MPVLib.setPropertyString("vo", "null")
          MPVLib.setOptionString("force-window", "no")
          MPVLib.setOptionString("idle", "yes")
          MPVLib.addObserver(this)
          reobserveTrackedProperties()
          observeProperties()
          initialized = true
          activeCoreConfigurationKey = coreConfigurationKey
          activeUserScriptsKey = userScriptsKey
          if (retainedAudio == null) updateState { it.copy(phase = PlaybackPhase.IDLE, paused = true, error = null) }
          startAudioObservers()
          true
        } catch (error: Throwable) {
          runCatching { MPVLib.removeObserver(this) }
          runCatching { MPVLib.destroy() }
          initialized = false
          nativeCoreReady = false
          activeCoreConfigurationKey = null
          activeUserScriptsKey = null
          suspendedVideoTrack = null
          deferredVideoSelectionGeneration = null
          pendingStopClearQueue = false
          supersededStopGeneration = 0L
          desiredPaused = retainedAudio?.paused ?: true
          loadedGeneration = retainedAudio?.generation ?: 0L
          defaultUserAgent = null
          pendingPositionRestoreGeneration = 0L
          pendingPositionRestoreOverride = null
          initialPositionGeneration = 0L
          clearSeekAudioGuardLocked(restoreMute = false)
          clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
          resetAmbientShaderTrackingLocked()
          if (retainedAudio == null) updateState {
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
    ownerIsActive: () -> Boolean = { true },
  ): Boolean =
    withCore(default = false) {
      if (!ownerIsActive() || !surface.isValid) return@withCore false

      // Surface ownership is a renderer concern only. Full player, mini player, PiP and Activity
      // recreation all hand the same live media session between Android Surfaces. Never change
      // `vid` during that handoff or mpv can discard cached packets and refetch normal HTTP data.
      if (_state.value.surfaceAttached && attachedSurfaceOwner !== owner) {
        detachRendererSurfaceLocked()
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
      if (deferredVideoSelectionGeneration == _state.value.generation) {
        MPVLib.setPropertyString("vid", "auto")
        deferredVideoSelectionGeneration = null
      }
      true
    }

  fun resizeSurface(
    width: Int,
    height: Int,
    owner: Any,
  ): Boolean =
    withCore(default = false) {
      if (width <= 0 || height <= 0 || attachedSurfaceOwner !== owner || !_state.value.surfaceAttached) {
        return@withCore false
      }
      MPVLib.setPropertyString("android-surface-size", "${width}x$height")
      true
    }

  fun unbindSurface(owner: Any): Boolean =
    withCore(default = false) {
      if (attachedSurfaceOwner !== owner || !_state.value.surfaceAttached) return@withCore false
      if (_state.value.phase == PlaybackPhase.LOADING) {
        deferredVideoSelectionGeneration = _state.value.generation
        runCatching { MPVLib.setPropertyString("vid", "no") }
      } else {
        suspendVideoTrackForSurfaceLossLocked()
      }
      detachRendererSurfaceLocked()
      true
    }

  /**
   * Detaches only Android's renderer resources. Surface transitions are not media lifecycle events:
   * they must not change video-track selection or disturb the live demuxer/cache.
   */
  private fun detachRendererSurfaceLocked() {
    runCatching { MPVLib.setPropertyString("vo", "null") }
    runCatching { MPVLib.setOptionString("force-window", "no") }
    runCatching { MPVLib.detachSurface() }
    attachedSurfaceOwner = null
    updateState { it.copy(surfaceAttached = false) }
  }

  fun setVideoOutput(videoOutput: String) {
    desiredVideoOutput = videoOutput
    withCore(Unit, allowInitializing = true) {
      // Track selection does not require an Android Surface, but a GPU video output does.
      // bindSurface() performs the real null -> configured output transition later.
      MPVLib.setOptionString("vo", if (_state.value.surfaceAttached) videoOutput else "null")
    }
  }

  fun markBackground() {
    updateState { current ->
      if (current.phase == PlaybackPhase.READY) current.copy(phase = PlaybackPhase.BACKGROUND) else current
    }
  }

  fun markForeground() {
    nativeLock.withLock {
      updateState { current ->
        if (current.phase == PlaybackPhase.BACKGROUND) current.copy(phase = PlaybackPhase.READY) else current
      }
      if (initialized) restoreSuspendedVideoTrackLocked()
    }
  }

  /** Stop playback while keeping the app-scoped core ready for a later screen attachment. */
  fun stop(clearQueue: Boolean = true) {
    if (usingExoPlayer) {
      nativeLock.withLock {
        if (_state.value.phase == PlaybackPhase.STOPPING) {
          pendingStopClearQueue = pendingStopClearQueue || clearQueue
          return
        }
        AudiobookPlayback.capture()
        publishAudioLeaving()
        AudiobookPlayback.clearTimer()
        audioPreloadSequence.incrementAndGet()
        val generation = _state.value.generation + 1
        desiredPaused = true
        loadedPlaybackItem = null
        loadedGeneration = 0L
        pendingStopClearQueue = clearQueue
        updateState { it.copy(phase = PlaybackPhase.STOPPING, generation = generation, paused = true) }
        releaseAudioEngineLocked {
          nativeLock.withLock {
            if (_state.value.generation == generation && _state.value.phase == PlaybackPhase.STOPPING) {
              val previous = _audioState.value
              _audioState.value = AudioEngineSnapshot(volume = previous.volume, muted = previous.muted, speed = previous.speed)
              finalizeStopLocked()
            }
          }
        }
      }
      return
    }
    withCore(Unit) {
      AudiobookPlayback.capture()
      loadedPlaybackItem = null
      AudiobookPlayback.clearTimer()
      val previousPhase = _state.value.phase
      if (previousPhase == PlaybackPhase.STOPPING) {
        pendingStopClearQueue = pendingStopClearQueue || clearQueue
        return@withCore
      }

      val nextGeneration = _state.value.generation + 1L
      supersededStopGeneration = 0L
      suspendedVideoTrack = null
      deferredVideoSelectionGeneration = null
      desiredPaused = true
      loadedGeneration = 0L
      pendingPositionRestoreGeneration = 0L
      pendingPositionRestoreOverride = null
      initialPositionGeneration = 0L

      // Ambient shaders contain dimensions and scale baked for one video. Never leave them attached
      // to the process-wide core after playback ends, even if the Activity/ViewModel that created
      // them has already gone away.
      clearAmbientShadersLocked(resetDesired = true)

      // Stop/quit must silence the native audio output before its decoder/output queues are torn
      // down. Restoring a seek guard's mute state before stop previously let a short buffered tail
      // escape after the Activity had already disappeared.
      beginPlaybackTransitionAudioGuardLocked(canRestore = false)
      clearSeekAudioGuardLocked(restoreMute = false)
      runCatching { MPVLib.setPropertyBoolean("pause", true) }
      pendingStopClearQueue = clearQueue
      updateState {
        it.copy(
          phase = PlaybackPhase.STOPPING,
          generation = nextGeneration,
          activeGeneration = nextGeneration,
          paused = true,
          error = null,
        )
      }
      propBoolean.emit("pause", true)
      clearTimelinePropertiesLocked()
      PlaybackPerformanceTrace.mark("MPV_STOP_SENT", "generation=$nextGeneration")

      if (previousPhase !in setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
        finalizeStopLocked()
      } else {
        runCatching { MPVLib.command("stop") }
          .onFailure { error ->
            Log.e(TAG, "Failed to stop libmpv playback", error)
            finalizeStopLocked()
          }
      }
    }
  }

  /**
   * Resolves the stop/load race for an incoming media request.
   *
   * A genuine session stop still enters [PlaybackPhase.STOPPING] and waits for libmpv to finish.
   * When a newer direct/local request arrives before that END_FILE, however, there is no app-owned
   * proxy lifetime that requires the Activity to wait for the event round-trip. In that case the
   * new generation may supersede the stop and enqueue `loadfile replace` immediately. App-owned
   * network/HLS/sidecar registrations deliberately retain the conservative wait until their
   * lifetime can be transferred with equivalent guarantees.
   */
  suspend fun awaitStopCompletion(timeoutMillis: Long = 3_000L): Boolean {
    if (_state.value.phase != PlaybackPhase.STOPPING) return true
    if (trySupersedeStopForReplacement()) return true

    val startedAt = android.os.SystemClock.elapsedRealtime()
    PlaybackPerformanceTrace.mark("WAIT_FOR_STOP_START")
    val completed =
      withTimeoutOrNull(timeoutMillis) {
        state.first { it.phase != PlaybackPhase.STOPPING }
      } != null
    PlaybackPerformanceTrace.mark(
      "WAIT_FOR_STOP_END",
      "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt},completed=$completed",
    )
    return completed
  }

  private fun trySupersedeStopForReplacement(): Boolean =
    nativeLock.withLock {
      val current = _state.value
      if (current.phase != PlaybackPhase.STOPPING) return@withLock true
      if (usingExoPlayer) return@withLock false
      if (!initialized || activeNetworkStream != null || auxiliaryNetworkStreams.isNotEmpty()) {
        return@withLock false
      }

      pendingStopClearQueue = false
      supersededStopGeneration = current.generation
      updateState {
        it.copy(
          phase = PlaybackPhase.IDLE,
          activeGeneration = 0L,
          paused = true,
          error = null,
        )
      }
      PlaybackPerformanceTrace.mark("WAIT_FOR_STOP_SUPERSEDED", "generation=${current.generation}")
      true
    }

  private fun finalizeStopLocked() {
    if (pendingStopClearQueue) _queue.value = PlaybackQueueState()
    pendingStopClearQueue = false
    supersededStopGeneration = 0L
    releaseActiveNetworkStreamLocked()
    releaseAuxiliaryNetworkStreamsLocked()
    updateState {
      it.copy(
        phase = PlaybackPhase.IDLE,
        activeGeneration = 0L,
        paused = true,
        currentItem = _queue.value.currentItem,
        error = null,
      )
    }
    propBoolean.emit("pause", true)
    clearTimelinePropertiesLocked()
  }

  /**
    * Destroys a core that must not be reused, including process shutdown and an unrecoverable
    * initialization reset. Normal Activity launches reuse the process-wide core.
   */
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
    PlaybackPerformanceTrace.mark("CLOSE_AUDIO_SILENCE")
    if (usingExoPlayer) {
      audioEngine?.silence()
      return
    }
    withCore(Unit) { beginPlaybackTransitionAudioGuardLocked(canRestore = false) }
  }

  private fun destroyLocked() {
    AudiobookPlayback.capture()
    publishAudioLeaving()
    audioPreloadSequence.incrementAndGet()
    releaseAudioEngineLocked()
    _audioState.value = AudioEngineSnapshot()
    audioLeavingGeneration = 0L
    audioHistoryGeneration = 0L
    audioLoopStartMs = null
    audioLoopEndMs = null
    audioDuckOwners.clear()
    loadedPlaybackItem = null
    speedBeforeAudiobook = null
    AudiobookPlayback.clearTimer()
    updateState { it.copy(phase = PlaybackPhase.STOPPING) }
    desiredPaused = true
    loadedGeneration = 0L
    defaultUserAgent = null
    pendingPositionRestoreGeneration = 0L
    pendingPositionRestoreOverride = null
    initialPositionGeneration = 0L
    suspendedVideoTrack = null
    deferredVideoSelectionGeneration = null
    pendingStopClearQueue = false
    supersededStopGeneration = 0L
    clearSeekAudioGuardLocked(restoreMute = false)
    clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
    runCatching { MPVLib.setPropertyBoolean("mute", true) }
    runCatching { MPVLib.setPropertyBoolean("pause", true) }
    runCatching { MPVLib.setPropertyString("vo", "null") }
    runCatching { MPVLib.detachSurface() }
    attachedSurfaceOwner = null
    runCatching { MPVLib.removeObserver(this) }
    runCatching { MPVLib.destroy() }
      .onFailure { error -> Log.e(TAG, "Failed to destroy libmpv", error) }
    releaseActiveNetworkStreamLocked()
    releaseAuxiliaryNetworkStreamsLocked()
    observers.clear()
    observedProperties.clear()
    resetAmbientShaderTrackingLocked()
    _videoZoom.value = 0f
    _videoPanX.value = 0f
    _videoPanY.value = 0f
    initialized = false
    nativeCoreReady = false
    activeCoreConfigurationKey = null
    activeUserScriptsKey = null
    clearTimelinePropertiesLocked()
    updateState { PlaybackSessionState(phase = PlaybackPhase.UNINITIALIZED) }
  }

  fun replaceQueue(
    items: List<PlaybackItem>,
    currentIndex: Int,
    isExplicitQueue: Boolean = false,
    isM3u: Boolean = false,
  ) {
    nativeLock.withLock {
      if (items.isNotEmpty() && _state.value.phase == PlaybackPhase.STOPPING) {
        // A newly accepted launch supersedes the old Activity's pending clear-queue request.
        pendingStopClearQueue = false
      }
      val previous = _queue.value
      val next = PlaybackQueueReducer.replace(previous, items, currentIndex, isExplicitQueue, isM3u)
      _queue.value = next
      if (_queue.value.currentItem?.audiobook != null) {
        _queue.value = PlaybackQueueReducer.setRepeatMode(PlaybackQueueReducer.setShuffleEnabled(_queue.value, false), RepeatMode.OFF)
      }
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
      if (_queue.value.items.any { it.audiobook != null }) return@withLock false
      val next = PlaybackQueueReducer.move(_queue.value, from, to) ?: return@withLock false
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      true
    }

  fun insertQueueItemsNext(items: List<PlaybackItem>): Boolean =
    nativeLock.withLock {
      if (_queue.value.items.any { it.audiobook != null } || items.any { it.audiobook != null }) return@withLock false
      val next = PlaybackQueueReducer.insertNext(_queue.value, items) ?: return@withLock false
      _queue.value = next
      updateState { it.copy(currentItem = next.currentItem) }
      true
    }

  fun appendQueueItems(items: List<PlaybackItem>): Boolean =
    nativeLock.withLock {
      if (_queue.value.items.any { it.audiobook != null } || items.any { it.audiobook != null }) return@withLock false
      val next = PlaybackQueueReducer.append(_queue.value, items) ?: return@withLock false
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
    nativeLock.withLock {
      _queue.value = PlaybackQueueReducer.setRepeatMode(_queue.value, if (_queue.value.currentItem?.audiobook != null) RepeatMode.OFF else repeatMode)
    }
  }

  fun setShuffleEnabled(enabled: Boolean) {
    nativeLock.withLock {
      _queue.value = PlaybackQueueReducer.setShuffleEnabled(_queue.value, enabled && _queue.value.currentItem?.audiobook == null)
    }
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

  internal fun seekAudiobookTrack(bookId: Long, trackId: Long, positionMs: Long, expectedGeneration: Long, resumePlayback: Boolean = false): Boolean =
    nativeLock.withLock {
      val current = _state.value
      if (current.generation != expectedGeneration || current.currentItem?.audiobook?.bookId != bookId ||
        current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
      ) return@withLock false
      val index = _queue.value.items.indexOfFirst { it.audiobook == AudiobookPlaybackInfo(bookId, trackId) }
      if (index < 0) return@withLock false
      val paused = !resumePlayback && (getPropertyBoolean("pause") ?: current.paused)
      val item = selectQueueItem(index) ?: return@withLock false
      if (load(item, initialPositionSeconds = positionMs.coerceAtLeast(0) / 1000.0) < 0) return@withLock false
      setPropertyBoolean("pause", paused)
      true
    }

  fun playQueueItem(index: Int): PlaybackItem? =
    nativeLock.withLock {
      // Unresolved torrent episodes need the player screen's streaming engine; loading the raw
      // magnet/torrent source into mpv would fail and desync the queue.
      if (_queue.value.items.getOrNull(index)?.requiresTorrentResolution() == true) return@withLock null
      val item = selectQueueItem(index) ?: return@withLock null
      load(item)
      item
    }

  fun playNext(): PlaybackItem? =
    nativeLock.withLock {
      if (PlaybackQueueReducer.peekNext(_queue.value)?.requiresTorrentResolution() == true) return@withLock null
      val item = selectNext() ?: return@withLock null
      load(item)
      item
    }

  fun playPrevious(): PlaybackItem? =
    nativeLock.withLock {
      if (PlaybackQueueReducer.peekPrevious(_queue.value)?.requiresTorrentResolution() == true) return@withLock null
      val item = selectPrevious() ?: return@withLock null
      load(item)
      item
    }

  fun load(
    item: PlaybackItem,
    restoreSavedPosition: Boolean = false,
    positionRestoreOverride: PlaybackPositionRestoreOverride? = null,
    initialPositionSeconds: Double? = null,
    flattenEditions: Boolean = false,
    commit: ((() -> Long) -> Long)? = null,
  ): Long {
    val selectedEngine = AudioPlaybackPolicy.engine(
      audioPreferences.audioEngine.get(), item.isDefinitelyAudioOnly(),
    )
    if (selectedEngine == AudioEngineKind.ExoPlayer) {
      return loadAudio(item, positionRestoreOverride, initialPositionSeconds, commit)
    }
    val preparationStartedAt = android.os.SystemClock.elapsedRealtime()
    PlaybackPerformanceTrace.mark("MEDIA_PREPARATION_START")
    val resolved =
      try {
        resolvePlayableUri(item)
      } finally {
        PlaybackPerformanceTrace.mark(
          "MEDIA_PREPARATION_END",
          "durationMs=${android.os.SystemClock.elapsedRealtime() - preparationStartedAt}",
        )
      }
    return try {
      var previous: NetworkStreamRegistration? = null
      var previousAuxiliary = emptyList<NetworkStreamRegistration>()
      val performCommit = {
        nativeLock.withLock {
          val generation =
            load(
              playableUri = resolved.uri,
              item = item,
              restoreSavedPosition = restoreSavedPosition,
              positionRestoreOverride = positionRestoreOverride,
              initialPositionSeconds = initialPositionSeconds,
              flattenEditions = flattenEditions,
            )
          if (generation >= 0L) {
            previous = activeNetworkStream
            activeNetworkStream = resolved.registration
            previousAuxiliary = auxiliaryNetworkStreams.values.toList()
            auxiliaryNetworkStreams.clear()
          }
          generation
        }
      }
      val generation = commit?.invoke(performCommit) ?: performCommit()
      if (generation < 0L) {
        resolved.registration?.let(::releaseNetworkStream)
        generation
      } else {
        val previousRegistration = previous
        if (previousRegistration != null && previousRegistration != resolved.registration) {
          releaseNetworkStream(previousRegistration)
        }
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
    restoreSavedPosition: Boolean = false,
    positionRestoreOverride: PlaybackPositionRestoreOverride? = null,
    initialPositionSeconds: Double? = null,
    flattenEditions: Boolean = false,
  ): Long =
    withCore(default = -1L) {
      if (_state.value.phase == PlaybackPhase.STOPPING) return@withCore -1L
      AudiobookPlayback.capture()
      publishAudioLeaving()
      audioPreloadSequence.incrementAndGet()
      val needsAudioRelease = audioEngine != null || !audioReleaseCompletion.isCompleted
      if (_state.value.engine == AudioEngineKind.ExoPlayer) {
        MPVLib.setPropertyBoolean("mute", _audioState.value.muted)
        MPVLib.setPropertyDouble("volume", _audioState.value.volume.toDouble())
        MPVLib.setPropertyDouble("speed", _audioState.value.speed.toDouble())
      }
      val resolvedItem = item ?: PlaybackItem.fromUri(playableUri)
      loadedPlaybackItem = null
      if (resolvedItem.audiobook != null) AudiobookPlayback.ensureStarted()
      if (resolvedItem.audiobook == null && speedBeforeAudiobook != null) {
        if (!MpvConfigOverridePolicy.isOwnedByMpvConf("speed")) MPVLib.setPropertyDouble("speed", speedBeforeAudiobook!!.toDouble())
        speedBeforeAudiobook = null
      }
      val videoSelection = resolvedItem.videoSelection()
      // Select the track during demuxer initialization. Video output remains `vo=null` until a
      // Surface is attached, so cold starts do not need a post-load track reselect.
      val selectVideoForNewFile = videoSelection == PlaybackVideoSelection.IMMEDIATE

      // An OUTPUT Ambient shader bakes the previous video's aspect ratio into its GLSL. Because the
      // libmpv core outlives PlayerActivity, a late/cancelled Ambient job can otherwise poison the
      // next file even when the UI preference is OFF. Start every replacement load from a clean,
      // identity-scaled shader state; an enabled Ambient mode will re-append after FILE_LOADED.
      clearAmbientShadersLocked(resetDesired = true)

      // A saved video-track id belongs to the outgoing file only. Never carry it into a new load.
      suspendedVideoTrack = null
      desiredPaused = positionRestoreOverride?.paused ?: false
      clearSeekAudioGuardLocked(restoreMute = true)

      // Keep replacement/startup audio muted until mpv has restarted cleanly. FILE_LOADED can be
      // followed by saved-position and audio-track restoration; without this guard tiny fragments
      // from the pre-restore timeline can reach AudioTrack and sound like a glitch/warble.
      beginPlaybackTransitionAudioGuardLocked(canRestore = true)

      val generation = _state.value.generation + 1L
      val initialPosition = initialPositionSeconds?.takeIf { it.isFinite() && it > 0.0 }
      pendingPositionRestoreGeneration =
        generation.takeIf {
          positionRestoreOverride != null || (restoreSavedPosition && !resolvedItem.isDefinitelyAudioOnly())
        } ?: 0L
      pendingPositionRestoreOverride = positionRestoreOverride?.let { generation to it }
      initialPositionGeneration = generation.takeIf { initialPosition != null } ?: 0L
      val holdForPositionRestore = pendingPositionRestoreGeneration == generation
      deferredVideoSelectionGeneration = null
      updateState {
        it.copy(
          phase = PlaybackPhase.LOADING,
          generation = generation,
          paused = holdForPositionRestore || desiredPaused,
          currentItem = resolvedItem,
          error = null,
          engine = AudioEngineKind.Mpv,
        )
      }
      clearTimelinePropertiesLocked()
      val userAgent = PlaybackHttpHeaders.userAgent(resolvedItem.headers)
      val headerFields = PlaybackHttpHeaders.toMpvHeaderFields(resolvedItem.headers)
      // URL-specific headers are request metadata, not a global mpv preference. Always apply the
      // media UA, then restore the post-mpv.conf default for a headerless item.
      MPVLib.setPropertyString("user-agent", userAgent ?: defaultUserAgent.orEmpty())
      MPVLib.setPropertyString("http-header-fields", headerFields)
      MPVLib.setPropertyString("force-media-title", "")

      if (!_state.value.surfaceAttached) {
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setOptionString("force-window", "no")
      }

      // Disable the outgoing track only once this replacement request owns the native lock. Doing
      // it during asynchronous URI preparation can blank playback even when that work is cancelled.
      MPVLib.setPropertyString("vid", "no")

      val loadOptions =
        buildList {
          add("pause=yes")
          add(if (selectVideoForNewFile) "vid=auto" else "vid=no")
          initialPosition?.let { add("start=$it") }
          if (flattenEditions && !MpvConfigOverridePolicy.isOwnedByMpvConf("flatten-editions")) {
            add("flatten-editions=yes")
          }
        }.joinToString(",")
      PlaybackPerformanceTrace.mark("LOADFILE_SENT", "generation=$generation")
      val startNativePlayback = {
        nativeLock.withLock {
          if (_state.value.generation == generation && !usingExoPlayer) {
            MPVLib.command("loadfile", playableUri, "replace", "-1", loadOptions)
          }
        }
      }
      if (needsAudioRelease) releaseAudioEngineLocked { startNativePlayback() } else startNativePlayback()
      propBoolean.emit("pause", holdForPositionRestore || desiredPaused)
      generation
    }

  /** Publishes a terminal UI state when a load never produces a native completion event. */
  fun reportLoadTimeout(
    expectedGeneration: Long,
    message: String,
  ): Boolean =
    nativeLock.withLock {
      val current = _state.value
      if (!initialized || current.generation != expectedGeneration ||
        current.phase !in setOf(PlaybackPhase.LOADING, PlaybackPhase.ERROR)
      ) {
        return@withLock false
      }
      desiredPaused = true
      if (usingExoPlayer) audioEngine?.setPaused(true)
      updateState { it.copy(phase = PlaybackPhase.ERROR, paused = true, error = message) }
      propBoolean.emit("pause", true)
      clearTimelinePropertiesLocked()
      true
    }

  fun isCurrentGeneration(generation: Long): Boolean = generation > 0L && _state.value.generation == generation

  fun isPositionRestorePending(generation: Long): Boolean =
    nativeLock.withLock {
      generation > 0L && pendingPositionRestoreGeneration == generation
    }

  fun positionRestoreOverride(generation: Long): PlaybackPositionRestoreOverride? =
    nativeLock.withLock {
      pendingPositionRestoreOverride?.takeIf { it.first == generation }?.second
    }

  fun wasInitialPositionApplied(generation: Long): Boolean =
    nativeLock.withLock {
      generation > 0L && initialPositionGeneration == generation
    }

  fun completePositionRestore(generation: Long) {
    nativeLock.withLock {
      if (pendingPositionRestoreGeneration != generation) return@withLock
      pendingPositionRestoreGeneration = 0L
      if (pendingPositionRestoreOverride?.first == generation) pendingPositionRestoreOverride = null
      if (initialPositionGeneration == generation) initialPositionGeneration = 0L
      val current = _state.value
      if (current.generation != generation || loadedGeneration != generation || current.phase != PlaybackPhase.LOADING) {
        return@withLock
      }

      if (usingExoPlayer) audioEngine?.setPaused(desiredPaused) else MPVLib.setPropertyBoolean("pause", desiredPaused)
      updateState {
        it.copy(
          phase = if (it.phase == PlaybackPhase.BACKGROUND) PlaybackPhase.BACKGROUND else PlaybackPhase.READY,
          paused = desiredPaused,
          error = null,
        )
      }
      publishTimelinePropertiesLocked()
      propBoolean.emit("pause", desiredPaused)
    }
  }

  fun addObserver(observer: MPVLib.EventObserver) {
    observers += observer
  }

  fun removeObserver(observer: MPVLib.EventObserver) {
    observers -= observer
  }

  fun command(vararg command: String) {
    if (usingExoPlayer) {
      audioCommand(command)
      return
    }
    if (MpvConfigOverridePolicy.shouldSuppress(command)) return
    withCore(Unit) {
      val preparedCommand = prepareSeekCommandLocked(command)
      if (handleAmbientShaderCommandLocked(preparedCommand)) return@withCore
      MPVLib.command(*preparedCommand)
    }
  }

  /** Executes a media-specific command only while its load generation is still current. */
  fun commandForGeneration(
    expectedGeneration: Long,
    vararg command: String,
  ): Boolean =
    nativeLock.withLock {
      if (!isInitialized || _state.value.generation != expectedGeneration) return@withLock false
      if (usingExoPlayer) return@withLock audioCommand(command)
      if (MpvConfigOverridePolicy.shouldSuppress(command)) return@withLock true
      val preparedCommand = prepareSeekCommandLocked(command)
      if (!handleAmbientShaderCommandLocked(preparedCommand)) MPVLib.command(*preparedCommand)
      true
    }

  fun commandNode(vararg command: String): MPVNode? = if (usingExoPlayer) null else withCore(null) { MPVLib.commandNode(*command) }

  internal fun removeVideoFilter(label: String) {
    val removal = arrayOf("vf", "remove", "@$label")
    if (MpvConfigOverridePolicy.shouldSuppress(removal)) return
    withCore(Unit) {
      val filters = MPVLib.getPropertyNode("vf")?.toObject<List<JsonObject>>(Json).orEmpty()
      if (filters.any { (it["label"] as? JsonPrimitive)?.content == label }) {
        MPVLib.command(*removal)
      }
    }
  }

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
  ): Int {
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(name)) return 0
    return withCore(-1, allowInitializing = true) { MPVLib.setOptionString(name, value) }
  }

  /**
   * Applies an app-owned integration prerequisite even when a broad mpv.conf ownership group is
   * selected. Use this only for infrastructure required to connect a bundled component to libmpv,
   * never for a user-facing playback preference.
   */
  fun setIntegrationOptionString(
    name: String,
    value: String,
  ): Int = withCore(-1, allowInitializing = true) { MPVLib.setOptionString(name, value) }

  fun getPropertyInt(property: String): Int? =
    if (usingExoPlayer) {
      if (property == "volume-max") 100 + audioPreferences.volumeBoostCap.get()
      else AudioPropertyAdapter.number(_audioState.value, property)?.toInt()
    } else withReadyCore(null) { MPVLib.getPropertyInt(property) }

  fun setPropertyInt(
    property: String,
    value: Int,
  ) {
    if (usingExoPlayer) {
      setAudioNumber(property, value.toDouble())
      return
    }
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    withCore(Unit) {
      if (property in AUDIO_SUBTITLE_TRACK_PROPERTIES && MPVLib.getPropertyInt(property) == value) {
        return@withCore
      }
      MPVLib.setPropertyInt(property, value)
      if (property == "vid" && value > 0) {
        suspendedVideoTrack = null
        deferredVideoSelectionGeneration = null
      }
    }
  }

  internal fun selectVideoTracks(
    expectedGeneration: Long,
    videoTrackId: Int,
    audioTrackId: Int?,
  ) {
    nativeLock.withLock {
      val current = _state.value
      if (!initialized || current.generation != expectedGeneration || loadedGeneration != expectedGeneration ||
        current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) ||
        MpvConfigOverridePolicy.isOwnedByMpvConf("vid")
      ) return@withLock

      val audioEnabled = MPVLib.getPropertyString("aid") != "no"
      setPropertyInt("vid", videoTrackId)
      if (audioEnabled && audioTrackId != null) setPropertyInt("aid", audioTrackId)
    }
  }

  fun getPropertyDouble(property: String): Double? =
    if (usingExoPlayer) AudioPropertyAdapter.number(_audioState.value, property)
    else withReadyCore(null) { MPVLib.getPropertyDouble(property) }

  fun setPropertyDouble(
    property: String,
    value: Double,
  ) {
    if (usingExoPlayer) {
      setAudioNumber(property, value)
      return
    }
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    withCore(Unit) {
      when (property) {
        "video-scale-x" -> {
          desiredAmbientScaleX = value
          MPVLib.setPropertyDouble(property, value)
        }
        "video-scale-y" -> {
          desiredAmbientScaleY = value
          MPVLib.setPropertyDouble(property, value)
        }
        "time-pos" -> {
          rememberEofSeekLocked(value, listOf("absolute"))
          MPVLib.setPropertyDouble(property, value)
        }
        else -> MPVLib.setPropertyDouble(property, value)
      }
    }
  }

  fun getPropertyFloat(property: String): Float? =
    if (usingExoPlayer) getPropertyDouble(property)?.toFloat() else withReadyCore(null) { MPVLib.getPropertyFloat(property) }

  fun setPropertyFloat(
    property: String,
    value: Float,
  ) {
    if (usingExoPlayer) {
      setAudioNumber(property, value.toDouble())
      return
    }
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    withCore(Unit) { MPVLib.setPropertyFloat(property, value) }
  }

  fun getPropertyBoolean(property: String): Boolean? =
    if (usingExoPlayer) {
      if (property == "pause") _state.value.paused else AudioPropertyAdapter.flag(_audioState.value, property)
    } else withReadyCore(null) { MPVLib.getPropertyBoolean(property) }

  internal fun audiobookProgress(reachedEnd: Boolean = false): AudiobookProgress? = nativeLock.withLock {
    val book = loadedPlaybackItem?.audiobook ?: return@withLock null
    val current = _state.value
    if (loadedGeneration != current.generation || current.phase == PlaybackPhase.STOPPING) return@withLock null
    if (!reachedEnd && current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return@withLock null
    if (reachedEnd) loadedAudiobookEnded = true
    val position = if (reachedEnd) loadedAudiobookDurationMs else {
      val seconds = getPropertyDouble("time-pos")?.takeIf { it.isFinite() } ?: return@withLock null
      (seconds * 1000).toLong().coerceAtLeast(0)
    }
    AudiobookProgress(book, position, loadedAudiobookEnded || getPropertyBoolean("eof-reached") == true,
      android.os.SystemClock.elapsedRealtimeNanos())
  }

  internal fun bookmarkSnapshot(): Pair<PlaybackItem, Long>? = nativeLock.withLock {
    val state = _state.value
    val item = loadedPlaybackItem ?: return@withLock null
    if (state.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND) || loadedGeneration != state.generation ||
      state.currentItem?.stableId != item.stableId || state.currentItem?.audiobook != item.audiobook ||
      getPropertyBoolean("seekable") == false
    ) return@withLock null
    val position = getPropertyDouble("time-pos")?.takeIf { it.isFinite() && it >= 0 } ?: return@withLock null
    item to (position * 1000).toLong()
  }

  internal fun <T> readLoadedPlaybackState(
    mediaIdentifier: String,
    capture: (positionSeconds: Double, durationSeconds: Double) -> T,
  ): T? = nativeLock.withLock {
    val current = _state.value
    val item = loadedPlaybackItem ?: return@withLock null
    if (item.stableId != mediaIdentifier || loadedGeneration != current.generation ||
      current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    ) return@withLock null
    val position = getPropertyDouble("time-pos")?.takeIf { it.isFinite() && it >= 0.0 }
      ?: return@withLock null
    val duration = getPropertyDouble("duration")?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    capture(position, duration)
  }

  internal fun applyAudiobookSpeed(generation: Long, speed: Float) = nativeLock.withLock {
    if (_state.value.generation != generation || _state.value.currentItem?.audiobook == null ||
      !usingExoPlayer && MpvConfigOverridePolicy.isOwnedByMpvConf("speed")
    ) {
      return@withLock
    }
    if (speedBeforeAudiobook == null) speedBeforeAudiobook = getPropertyDouble("speed")?.toFloat() ?: 1f
    setPropertyDouble("speed", speed.coerceIn(0.1f, 4f).toDouble())
  }

  fun setPropertyBoolean(
    property: String,
    value: Boolean,
  ) {
    if (usingExoPlayer) {
      nativeLock.withLock {
        when (property) {
          "pause" -> {
            AudiobookPlayback.onPauseRequested(value)
            if (!value && _state.value.phase == PlaybackPhase.ERROR) {
              val item = _state.value.currentItem ?: return@withLock
              val position = _audioState.value.positionMs / 1000.0
              loadAudio(item, PlaybackPositionRestoreOverride(position, false), null, null)
              return@withLock
            }
            desiredPaused = value
            updateState { it.copy(paused = value) }
            propBoolean.emit("pause", value)
            audioEngine?.setPaused(value)
          }
          "mute" -> {
            _audioState.update { it.copy(muted = value) }
            audioEngine?.setMuted(value)
          }
          else -> Unit
        }
      }
      return
    }
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    withCore(Unit) {
      if (property == "pause") {
        AudiobookPlayback.onPauseRequested(value)
        if (!value && _state.value.phase != PlaybackPhase.LOADING) prepareResumeFromEofLocked()
        desiredPaused = value
        // Loading may include an asynchronous saved-position restore. Record user/service intent
        // now, then apply it once the load owner publishes READY.
        if (_state.value.phase != PlaybackPhase.LOADING) {
          MPVLib.setPropertyBoolean(property, value)
        }
        updateState { it.copy(paused = value) }
        propBoolean.emit(property, value)
      } else if (
        property == "mute" &&
        (playbackTransitionAudioGuardPreviousMute != null || seekAudioGuardPreviousMute != null)
      ) {
        // A user mute/unmute action while either audio guard is active should update the value that
        // will be restored, but must not open a guard and leak seek/transition audio immediately.
        if (playbackTransitionAudioGuardPreviousMute != null) {
          playbackTransitionAudioGuardPreviousMute = value
        }
        if (seekAudioGuardPreviousMute != null) {
          seekAudioGuardPreviousMute = value
        }
        MPVLib.setPropertyBoolean("mute", true)
      } else {
        MPVLib.setPropertyBoolean(property, value)
      }
    }
  }

  private fun rememberEofSeekLocked(target: Double, flags: List<String>): Boolean {
    val current = _state.value
    if (!target.isFinite() || current.currentItem == null || current.currentItem.audiobook != null ||
      loadedGeneration != current.generation ||
      (current.phase != PlaybackPhase.READY && current.phase != PlaybackPhase.BACKGROUND) ||
      MPVLib.getPropertyBoolean("eof-reached") != true || MPVLib.getPropertyBoolean("seekable") == false
    ) return false

    val seeksBackwards = when {
      "absolute-percent" in flags -> target < 100.0
      "absolute" in flags -> target < (MPVLib.getPropertyDouble("duration") ?: Double.POSITIVE_INFINITY)
      else -> target < 0.0
    }
    if (!seeksBackwards) return false
    if (pendingEofSeekGeneration != current.generation) {
      desiredPaused = MPVLib.getPropertyBoolean("pause") ?: current.paused
    }
    pendingEofSeekGeneration = current.generation
    return true
  }

  private fun prepareSeekCommandLocked(command: Array<out String>): Array<out String> {
    if (command.firstOrNull() != "seek") return command
    loadedAudiobookEnded = false
    beginSeekAudioGuardLocked()
    val target = command.getOrNull(1)?.toDoubleOrNull() ?: return command
    val flags = command.getOrNull(2)?.split('+') ?: listOf("relative")
    val fromEof = rememberEofSeekLocked(target, flags)
    if (!fromEof || target != 0.0 || "exact" in flags ||
      ("absolute" !in flags && "absolute-percent" !in flags)
    ) return command

    return command.toMutableList().apply {
      this[2] = (flags.filterNot { it == "keyframes" } + "exact").joinToString("+")
    }.toTypedArray()
  }

  private fun prepareResumeFromEofLocked() {
    val current = _state.value
    if (current.currentItem == null || current.currentItem.audiobook != null ||
      loadedGeneration != current.generation || pendingEofSeekGeneration == current.generation ||
      (current.phase != PlaybackPhase.READY && current.phase != PlaybackPhase.BACKGROUND) ||
      MPVLib.getPropertyBoolean("eof-reached") != true || MPVLib.getPropertyBoolean("seeking") == true ||
      MPVLib.getPropertyBoolean("seekable") == false
    ) return

    val duration = MPVLib.getPropertyDouble("duration")?.takeIf { it.isFinite() && it > 0.0 } ?: return
    val position = MPVLib.getPropertyDouble("time-pos")?.takeIf { it.isFinite() } ?: return
    if (position < duration - 2.0) return
    MPVLib.command(*prepareSeekCommandLocked(arrayOf("seek", "0", "absolute+exact")))
  }

  /** Atomically toggles pause so rapid UI/media-button taps cannot race separate reads and writes. */
  fun togglePause(): Boolean? =
    withCore(default = null) {
      if (usingExoPlayer) {
        val next = !_state.value.paused
        setPropertyBoolean("pause", next)
        return@withCore next
      }
      val currentPaused =
        if (_state.value.phase == PlaybackPhase.LOADING) {
          desiredPaused
        } else {
          MPVLib.getPropertyBoolean("pause") ?: desiredPaused
        }
      val nextPaused = !currentPaused
      AudiobookPlayback.onPauseRequested(nextPaused)
      if (!nextPaused && _state.value.phase != PlaybackPhase.LOADING) prepareResumeFromEofLocked()
      desiredPaused = nextPaused
      if (_state.value.phase != PlaybackPhase.LOADING) {
        MPVLib.setPropertyBoolean("pause", nextPaused)
      }
      updateState { it.copy(paused = nextPaused) }
      propBoolean.emit("pause", nextPaused)
      nextPaused
    }

  fun getPropertyString(property: String): String? =
    if (usingExoPlayer) AudioPropertyAdapter.text(_audioState.value, property)
    else withReadyCore(null) { MPVLib.getPropertyString(property) }

  fun getPropertyNode(property: String): MPVNode? =
    if (usingExoPlayer) null else withReadyCore(null) { MPVLib.getPropertyNode(property) }

  fun setPropertyString(
    property: String,
    value: String,
  ) {
    if (usingExoPlayer) {
      when (property) {
        "pause", "mute" -> setPropertyBoolean(property, value == "yes")
        "aid" -> audioEngine?.selectTrack(if (value == "no") -1 else value.toIntOrNull() ?: 1)
        "ab-loop-a", "ab-loop-b" -> setAudioLoopPoint(property, value.toDoubleOrNull()?.takeIf(Double::isFinite)?.times(1000)?.toLong())
        "speed", "time-pos", "volume" -> value.toDoubleOrNull()?.let { setAudioNumber(property, it) }
        else -> Unit
      }
      return
    }
    if (MpvConfigOverridePolicy.isOwnedByMpvConf(property)) return
    withCore(Unit) {
      if (property in AUDIO_SUBTITLE_TRACK_PROPERTIES && MPVLib.getPropertyString(property) == value) {
        return@withCore
      }
      if (property == "vid") {
        if (value == "no" && _state.value.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) {
          val activeVid = MPVLib.getPropertyInt("vid") ?: -1
          if (activeVid > 0) {
            suspendedVideoTrack = SuspendedVideoTrack(activeVid, _state.value.generation)
          }
        } else if (value != "no") {
          suspendedVideoTrack = null
          deferredVideoSelectionGeneration = null
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
  }

  fun grabThumbnail(dimension: Int): Bitmap? = if (usingExoPlayer) null else withCore(null) { MPVLib.grabThumbnail(dimension) }

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
    if (usingExoPlayer) return
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
    if (usingExoPlayer) return
    if (shouldSuppressTimelineUpdate(property)) {
      propLong.emit(property, null)
      propInt.emit(property, null)
      return
    }
    propLong.emit(property, value)
    propInt.emit(property, value.toInt())
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) {
    if (usingExoPlayer) return
    val effectiveValue =
      if (property == "pause" && _state.value.phase == PlaybackPhase.LOADING) _state.value.paused else value
    if (property == "pause") updateState { it.copy(paused = effectiveValue) }
    propBoolean.emit(property, effectiveValue)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, effectiveValue) } }
  }

  override fun eventProperty(
    property: String,
    value: String,
  ) {
    if (usingExoPlayer) return
    propString.emit(property, value)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: Double,
  ) {
    if (usingExoPlayer) return
    if (shouldSuppressTimelineUpdate(property)) {
      propDouble.emit(property, null)
      propFloat.emit(property, null)
      return
    }
    propDouble.emit(property, value)
    propFloat.emit(property, value.toFloat())
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) {
    if (usingExoPlayer) return
    propNode.emit(property, value)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
  }

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    if (usingExoPlayer) return
    val shouldForward =
      nativeLock.withLock {
        if (usingExoPlayer) return@withLock false
        when (eventId) {
          MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
            // loadfile 'replace' commands can coalesce inside one mpv dispatch batch, in which case
            // mpv only ever starts the newest target and emits a single START_FILE for it. Any
            // per-load FIFO desyncs permanently on that skip, so the started file is always
            // attributed to the latest requested generation.
            if (_state.value.phase != PlaybackPhase.STOPPING) {
              updateState { it.copy(phase = PlaybackPhase.LOADING, activeGeneration = it.generation) }
            }
            if (supersededStopGeneration != 0L && _state.value.generation > supersededStopGeneration) {
              // libmpv normally delivers the old STOP END_FILE before START_FILE for the replacement.
              // Once the new file has started, do not let this marker affect a later genuine stop.
              supersededStopGeneration = 0L
            }
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
            val current = _state.value
            if (current.phase == PlaybackPhase.STOPPING) {
              runCatching { MPVLib.command("stop") }
              return@withLock true
            }
            supersededStopGeneration = 0L
            loadedGeneration = current.generation
            pendingEofSeekGeneration = null
            loadedPlaybackItem = current.currentItem
            loadedAudiobookEnded = false
            loadedAudiobookDurationMs = ((MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000).toLong().coerceAtLeast(0)
            current.currentItem?.let { AudiobookPlayback.onFileLoaded(it, current.generation) }
            val restoringPosition = pendingPositionRestoreGeneration == current.generation
            val appliedPaused = restoringPosition || desiredPaused
            // Track/decoder replacement is now complete. Apply the latest user/service intent
            // once instead of allowing pause writes to race the load operation. Saved-position
            // loads stay paused until PlayerActivity finishes the database lookup and seek.
            MPVLib.setPropertyBoolean("pause", appliedPaused)
            // Surface ownership is the source of truth at this boundary. Activity observers can
            // detach during recreation, and deferred-generation bookkeeping only covers loads that
            // started without video. Repair a disabled selection before exposing READY so an
            // attached player cannot remain on a black frame with audio.
            if (current.surfaceAttached && current.currentItem?.isDefinitelyAudioOnly() != true) {
              val selectedVideoTrack = MPVLib.getPropertyInt("vid")
              if (selectedVideoTrack == null || selectedVideoTrack <= 0) {
                MPVLib.setPropertyString("vid", "auto")
              }
              if (deferredVideoSelectionGeneration == current.generation) {
                deferredVideoSelectionGeneration = null
              }
            }
            updateState {
              it.copy(
                phase =
                  if (restoringPosition) {
                    PlaybackPhase.LOADING
                  } else if (it.phase == PlaybackPhase.BACKGROUND) {
                    PlaybackPhase.BACKGROUND
                  } else {
                    PlaybackPhase.READY
                  },
                activeGeneration = it.generation,
                paused = appliedPaused,
                error = null,
              )
            }
            propBoolean.emit("pause", appliedPaused)
            restoreSuspendedVideoTrackLocked()
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
            val current = _state.value
            if (pendingEofSeekGeneration == current.generation && loadedGeneration == current.generation &&
              (current.phase == PlaybackPhase.READY || current.phase == PlaybackPhase.BACKGROUND)
            ) {
              MPVLib.setPropertyBoolean("pause", desiredPaused)
              updateState { it.copy(paused = desiredPaused) }
              propBoolean.emit("pause", desiredPaused)
            }
            pendingEofSeekGeneration = null
            scheduleSeekAudioGuardRestoreLocked(SEEK_AUDIO_RESTORE_DELAY_MS)
            if (_state.value.phase != PlaybackPhase.STOPPING) {
              schedulePlaybackTransitionAudioGuardRestoreLocked(PLAYBACK_TRANSITION_AUDIO_RESTORE_DELAY_MS)
            }
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
            pendingEofSeekGeneration = null
            val current = _state.value
            val reason = parseEndFileReason(data)
            if (
              supersededStopGeneration != 0L &&
              current.activeGeneration == 0L &&
              reason in setOf(EndFileReason.STOP, EndFileReason.QUIT)
            ) {
              PlaybackPerformanceTrace.mark(
                "SUPERSEDED_STOP_END_FILE",
                "generation=$supersededStopGeneration,reason=${reason.name}",
              )
              supersededStopGeneration = 0L
              return@withLock false
            }
            if (current.phase == PlaybackPhase.STOPPING) {
              if (reason == EndFileReason.REDIRECT) {
                runCatching { MPVLib.command("stop") }
              } else {
                finalizeStopLocked()
              }
              return@withLock true
            }
            if (current.activeGeneration == current.generation) {
              if (reason == EndFileReason.EOF) AudiobookPlayback.capture(reachedEnd = true)
              if (reason == EndFileReason.REDIRECT && loadedGeneration != current.generation) {
                // Redirects emit END_FILE before mpv starts the resolved target. Preserve LOADING;
                // the following START_FILE belongs to the same app-level generation.
                updateState { it.copy(activeGeneration = 0L) }
              } else {
                val failedBeforeReady = loadedGeneration != current.generation
                val isFailure =
                  reason == EndFileReason.ERROR ||
                    (failedBeforeReady && reason !in setOf(EndFileReason.STOP, EndFileReason.QUIT))
                val error =
                  if (isFailure) {
                    parseEndFileError(data)
                      ?: "Playback ended before the media became ready (${reason.name.lowercase()})"
                  } else {
                    null
                  }
                if (isFailure) {
                  Log.w(
                    TAG,
                    "Load generation ${current.generation} failed before FILE_LOADED: $error; " +
                      "event=${runCatching { data.toJson() }.getOrDefault("unavailable")}",
                  )
                }
                updateState {
                  it.copy(
                    phase = if (isFailure) PlaybackPhase.ERROR else PlaybackPhase.IDLE,
                    activeGeneration = 0L,
                    paused = true,
                    error = error,
                  )
                }
                propBoolean.emit("pause", true)
                clearTimelinePropertiesLocked()
              }
            }
            true
          }
          MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
            activeUserScriptsKey = null
            loadedPlaybackItem = null
            releaseActiveNetworkStream()
            releaseAuxiliaryNetworkStreams()
            suspendedVideoTrack = null
            deferredVideoSelectionGeneration = null
            desiredPaused = true
            loadedGeneration = 0L
            pendingEofSeekGeneration = null
            defaultUserAgent = null
            pendingPositionRestoreGeneration = 0L
            pendingPositionRestoreOverride = null
            initialPositionGeneration = 0L
            pendingStopClearQueue = false
            supersededStopGeneration = 0L
            clearSeekAudioGuardLocked(restoreMute = false)
            clearPlaybackTransitionAudioGuardLocked(restoreMute = false)
            resetAmbientShaderTrackingLocked()
            initialized = false
            nativeCoreReady = false
            clearTimelinePropertiesLocked()
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

  private fun shouldSuppressTimelineUpdate(property: String): Boolean =
    property in TIMELINE_PROPERTIES && _state.value.phase == PlaybackPhase.LOADING

  private fun clearTimelinePropertiesLocked() {
    TIMELINE_PROPERTIES.forEach { property ->
      propInt.emit(property, null)
      propLong.emit(property, null)
      propDouble.emit(property, null)
      propFloat.emit(property, null)
    }
  }

  private fun publishTimelinePropertiesLocked() {
    TIMELINE_PROPERTIES.forEach { property ->
      val value = MPVLib.getPropertyDouble(property)
      propInt.emit(property, value?.toInt())
      propLong.emit(property, value?.toLong())
      propDouble.emit(property, value)
      propFloat.emit(property, value?.toFloat())
      observerSnapshot().forEach { observer ->
        runCatching {
          if (value == null) observer.eventProperty(property) else observer.eventProperty(property, value)
        }
      }
    }
  }

  private fun parseEndFileReason(data: MPVNode): EndFileReason {
    val reasonNode = data["reason"]
    return reasonNode?.asString()?.lowercase()?.let { reason ->
      when (reason) {
        "eof" -> EndFileReason.EOF
        "stop" -> EndFileReason.STOP
        "quit" -> EndFileReason.QUIT
        "error" -> EndFileReason.ERROR
        "redirect" -> EndFileReason.REDIRECT
        else -> EndFileReason.UNKNOWN
      }
    } ?: when (reasonNode?.asInt()?.toInt()) {
      0 -> EndFileReason.EOF
      2 -> EndFileReason.STOP
      3 -> EndFileReason.QUIT
      4 -> EndFileReason.ERROR
      5 -> EndFileReason.REDIRECT
      else -> EndFileReason.UNKNOWN
    }
  }

  /** Only a matching END_FILE reason=eof may drive repeat, queue advance, or close-after-end. */
  internal fun isNaturalEndFile(data: MPVNode): Boolean = parseEndFileReason(data) == EndFileReason.EOF

  private fun parseEndFileError(data: MPVNode): String? =
    sequenceOf(data["error"], data["file_error"])
      .mapNotNull { node -> node?.asString() ?: node?.asInt()?.toString() }
      .firstOrNull { value -> value.isNotBlank() && value != "0" }

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
  private fun beginPlaybackTransitionAudioGuardLocked(canRestore: Boolean) {
    if (playbackTransitionAudioGuardPreviousMute == null) {
      // Closing or replacing media can overlap the short seek guard. In that window mpv reports
      // mute=true even when the user was unmuted. Preserve the pre-seek value so a later load does
      // not restore the temporary guard mute and remain permanently silent.
      playbackTransitionAudioGuardPreviousMute =
        seekAudioGuardPreviousMute ?: (MPVLib.getPropertyBoolean("mute") ?: false)
    }
    runCatching { MPVLib.setPropertyBoolean("mute", true) }
    playbackTransitionAudioGuardCanRestore = canRestore
    playbackTransitionAudioGuardToken++
  }

  private fun schedulePlaybackTransitionAudioGuardRestoreLocked(delayMs: Long) {
    if (playbackTransitionAudioGuardPreviousMute == null || !playbackTransitionAudioGuardCanRestore) return
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
    playbackTransitionAudioGuardCanRestore = false
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
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("glsl-shaders")) {
      stalePaths.forEach { path ->
        runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", path) }
          .onFailure { error -> Log.w(TAG, "Failed to remove stale Ambient shader $path", error) }
      }
    }

    if (resetDesired) {
      desiredAmbientScaleX = 1.0
      desiredAmbientScaleY = 1.0
    }
  }

  private fun applyDesiredAmbientScaleLocked() {
    // Bypass the interceptor — we already hold the staged values and need them applied
    // to the renderer immediately after the ambient shader has been installed.
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("video-scale-x")) {
      MPVLib.setPropertyDouble("video-scale-x", desiredAmbientScaleX)
    }
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("video-scale-y")) {
      MPVLib.setPropertyDouble("video-scale-y", desiredAmbientScaleY)
    }
  }

  private fun resetActiveAmbientScaleLocked() {
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("video-scale-x")) {
      runCatching { MPVLib.setPropertyDouble("video-scale-x", 1.0) }
    }
    if (!MpvConfigOverridePolicy.isOwnedByMpvConf("video-scale-y")) {
      runCatching { MPVLib.setPropertyDouble("video-scale-y", 1.0) }
    }
  }

  private fun resetAmbientShaderTrackingLocked() {
    activeAmbientShaderPaths.clear()
    desiredAmbientScaleX = 1.0
    desiredAmbientScaleY = 1.0
  }

  private fun suspendVideoTrackForSurfaceLossLocked() {
    val current = _state.value
    if (current.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)) return
    val activeHardwareDecoder = MPVLib.getPropertyString("hwdec-current").orEmpty()
    if (!activeHardwareDecoder.contains("mediacodec", ignoreCase = true)) return
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

  fun setAudioTransitionBlocked(owner: String, blocked: Boolean) {
    audioTransitionBlocks.update { if (blocked) it + owner else it - owner }
  }

  fun setAudioFocusDucked(owner: String, ducked: Boolean) = nativeLock.withLock {
    if (ducked) audioDuckOwners.add(owner) else audioDuckOwners.remove(owner)
    audioEngine?.setDuckGain(if (audioDuckOwners.isEmpty()) 1f else 0.5f)
  }

  fun configureAudioHistory(allowed: Boolean, playlistId: Int?) = nativeLock.withLock {
    audioHistoryAllowed = allowed
    audioHistoryPlaylistId = playlistId
    if (!allowed) applicationContext?.let { context ->
      audioScope.launch { AudioQueuePersistence.clear(context) }
    }
  }

  internal suspend fun restoreAudioQueue(context: Context, playWhenReady: Boolean): Boolean {
    val expectedGeneration = _state.value.generation
    if (_queue.value.hasItems) return false
    val saved = AudioQueuePersistence.read(context) ?: return false
    return startAudioQueue(context, saved, playWhenReady, expectedGeneration)
  }

  internal suspend fun startAudioQueue(
    context: Context,
    saved: SavedAudioQueue,
    playWhenReady: Boolean,
    expectedGeneration: Long = _state.value.generation,
  ): Boolean {
    val item = saved.queue.currentItem ?: return false
    if (!item.isDefinitelyAudioOnly() || item.requiresTorrentResolution() ||
      audioPreferences.audioEngine.get() != AudioEngineKind.ExoPlayer
    ) return false
    nativeLock.withLock {
      applicationContext = context.applicationContext
      startAudioObservers()
    }
    val generation = kotlinx.coroutines.withContext(Dispatchers.IO) {
      loadAudio(item, PlaybackPositionRestoreOverride(saved.positionMs / 1000.0, !playWhenReady), null, commit = { load ->
        nativeLock.withLock {
          if (_state.value.generation != expectedGeneration || _state.value.phase == PlaybackPhase.STOPPING) -1L else {
            _queue.value = saved.queue
            audioHistoryAllowed = org.koin.java.KoinJavaComponent.get<AdvancedPreferences>(AdvancedPreferences::class.java)
              .enableRecentlyPlayed.get()
            val nextGeneration = load()
            if (nextGeneration >= 0) {
              setPropertyDouble("speed", saved.speed.toDouble())
              setPropertyDouble("volume", saved.volume.toDouble())
              setPropertyBoolean("mute", saved.muted)
            }
            nextGeneration
          }
        }
      })
    }
    return generation >= 0L
  }

  fun saveAudioPlaybackState() {
    nativeLock.withLock {
      val snapshot = _audioState.value
      if (usingExoPlayer && snapshot.ready && snapshot.generation == _state.value.generation) audioSaves.trySend(AudioSave(snapshot))
    }
  }

  suspend fun flushAudioPlaybackState() {
    val completion = CompletableDeferred<Unit>()
    val snapshot = _audioState.value
    if (!snapshot.ready || !usingExoPlayer) return
    audioSaves.send(AudioSave(snapshot, completion))
    completion.await()
  }

  fun nativeAudioFilters(): String {
    val context = applicationContext ?: return ""
    val config = File(context.filesDir, "mpv.conf")
    val text = if (config.exists()) runCatching { config.readText() }.getOrDefault("") else
      org.koin.java.KoinJavaComponent.get<AdvancedPreferences>(AdvancedPreferences::class.java).mpvConf.get()
    return text.lineSequence().map(String::trim).filter {
      !it.startsWith('#') && it.substringBefore('=').trim() in setOf("af", "af-add", "af-append")
    }.map { it.substringAfter('=', "").trim() }.filter(String::isNotBlank).joinToString(",")
  }

  private fun startAudioObservers() {
    if (audioObserversStarted) return
    audioObserversStarted = true
    val playerPreferences = org.koin.java.KoinJavaComponent.get<PlayerPreferences>(PlayerPreferences::class.java)
    audioScope.launch {
      org.koin.java.KoinJavaComponent.get<AdvancedPreferences>(AdvancedPreferences::class.java)
        .enableRecentlyPlayed.changes().collect { enabled ->
          if (!enabled) {
            audioHistoryAllowed = false
            applicationContext?.let { AudioQueuePersistence.clear(it) }
          }
        }
    }
    audioScope.launch(Dispatchers.IO) {
      val repository = org.koin.java.KoinJavaComponent.get<PlaybackStateRepository>(PlaybackStateRepository::class.java)
      val browser = org.koin.java.KoinJavaComponent.get<BrowserPreferences>(BrowserPreferences::class.java)
      for (request in audioSaves) {
        val audio = request.snapshot
        runCatching {
          val item = audio.item ?: return@runCatching
          if (request.retainQueue && audioHistoryAllowed && request.queue.currentItem?.stableId == item.stableId) {
            applicationContext?.let { context ->
              AudioQueuePersistence.save(context, SavedAudioQueue(
                queue = request.queue, positionMs = audio.positionMs, speed = audio.speed,
                paused = audio.paused, volume = audio.volume, muted = audio.muted,
              ))
            }
          }
          val old = repository.getVideoDataByTitle(item.stableId)
          val snapshot = PlaybackStatePersistence.fromAudio(audio, old) ?: return@runCatching
          repository.upsert(PlaybackStatePersistence.buildEntity(old, snapshot, playerPreferences.savePositionOnQuit.get(), browser.watchedThreshold.get()))
          PlaybackStateEvents.notifyChanged(item.stableId)
        }.onFailure { Log.w(TAG, "Could not save the outgoing audio position") }
        request.completion?.complete(Unit)
      }
    }
    audioScope.launch { _queue.collect { saveAudioPlaybackState() } }
    audioScope.launch {
      combine(
        equalizerState,
        audioPreferences.volumeNormalization.changes(),
        audioPreferences.drcEnabled.changes(),
        audioPreferences.audioChannels.changes(),
      ) { equalizer, normalize, compress, channels ->
        AudioProcessingSettings(
          equalizer.isEnabled, equalizer.bandGains, equalizer.volumeBoostDb, normalize, compress,
          when (channels) {
            AudioChannels.Mono -> AudioChannelMix.Mono
            AudioChannels.Stereo -> AudioChannelMix.Stereo
            AudioChannels.ReverseStereo -> AudioChannelMix.ReverseStereo
            else -> AudioChannelMix.Auto
          },
        )
      }.combine(audioPreferences.skipSilence.changes()) { processing, skipSilence ->
        processing.copy(skipSilence = skipSilence)
      }.combine(audioPreferences.replayGain.changes()) { processing, replayGain ->
        processing.copy(replayGain = replayGain)
      }.distinctUntilChanged().collect {
        audioProcessing = it
        audioEngine?.setProcessing(it)
      }
    }
    audioScope.launch {
      combine(
        audioPreferences.preferDolbyAtmos.changes(),
        audioPreferences.spatialAudio.changes(),
        audioPreferences.wifiMaxBitrate.changes(),
        audioPreferences.mobileMaxBitrate.changes(),
      ) { atmos, spatial, wifi, mobile -> AudioOutputSettings(atmos, spatial, wifi, mobile) }
        .distinctUntilChanged().collect { audioEngine?.setOutputSettings(it) }
    }
    audioScope.launch {
      combine(_queue, audioPreferences.crossfadeDurationMs.changes(), playerPreferences.autoplayNextAudio.changes(), audioTransitionBlocks,
        audioPreferences.audioEngine.changes()) { queue, duration, autoplay, blocks, engine ->
        Triple(queue, duration, engine == AudioEngineKind.ExoPlayer &&
          (autoplay || queue.repeatMode == RepeatMode.ALL) && queue.repeatMode != RepeatMode.ONE && blocks.isEmpty())
      }.collect { (queue, duration, allowed) ->
        if (queue != acceptedCrossfadeQueue) {
          cancelAudioPreload()
          audioEngine?.cancelTransition()
        }
        acceptedCrossfadeQueue = null
        audioEngine?.setCrossfade(duration, allowed)
      }
    }
    audioScope.launch {
      audioPreferences.audioPitchCorrection.changes().collect { preservePitch ->
        audioEngine?.setSpeed(_audioState.value.speed, preservePitch)
      }
    }
    audioScope.launch {
      audioPreferences.outputSampleRate.changes().collect { mode -> audioEngine?.setOutputSampleRate(mode) }
    }
    audioScope.launch {
      val syncplay = org.koin.java.KoinJavaComponent.get<app.gyrolet.mpvrx.domain.syncplay.SyncplayManager>(
        app.gyrolet.mpvrx.domain.syncplay.SyncplayManager::class.java,
      )
      syncplay.state.collect { setAudioTransitionBlocked("syncplay", it.isConnected || it.isConnecting) }
    }
  }

  private fun loadAudio(
    item: PlaybackItem,
    restore: PlaybackPositionRestoreOverride?,
    initialSeconds: Double?,
    commit: ((() -> Long) -> Long)?,
    retryAttempt: Int = 0,
  ): Long {
    val performCommit = {
      nativeLock.withLock {
        if (applicationContext == null || _state.value.phase == PlaybackPhase.STOPPING) -1L else {
          audioRetryJob?.cancel()
          audioRetryJob = null
          audioRetryAttempts = retryAttempt
          AudiobookPlayback.capture()
          publishAudioLeaving()
          audioPreloadSequence.incrementAndGet()
          interruptAudioReplacementLocked()
          val previousAudio = _audioState.value.takeIf { usingExoPlayer }
          clearSeekAudioGuardLocked(restoreMute = true)
          clearPlaybackTransitionAudioGuardLocked(restoreMute = true)
          val currentVolume = previousAudio?.volume ?: (if (initialized) MPVLib.getPropertyDouble("volume")?.toFloat() else null) ?: 100f
          val currentMute = previousAudio?.muted ?: (if (initialized) MPVLib.getPropertyBoolean("mute") else null) ?: false
          val currentSpeed = if (item.audiobook == null && speedBeforeAudiobook != null) speedBeforeAudiobook!!.also { speedBeforeAudiobook = null }
            else previousAudio?.speed ?: (if (initialized) MPVLib.getPropertyDouble("speed")?.toFloat() else null) ?: 1f
          if (initialized) {
            MPVLib.setPropertyBoolean("mute", true)
            MPVLib.setPropertyBoolean("pause", true)
            MPVLib.command("stop")
          }
          releaseActiveNetworkStreamLocked()
          releaseAuxiliaryNetworkStreamsLocked()
          val generation = _state.value.generation + 1L
          loadedGeneration = 0L
          loadedPlaybackItem = null
          loadedAudiobookEnded = false
          desiredPaused = restore?.paused ?: false
          pendingPositionRestoreGeneration = 0L
          pendingPositionRestoreOverride = null
          initialPositionGeneration = generation
          val position = ((initialSeconds ?: restore?.positionSeconds ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()
          _audioState.value = AudioEngineSnapshot(generation = generation, item = item, paused = desiredPaused, volume = currentVolume, muted = currentMute, speed = currentSpeed)
          updateState { it.copy(engine = AudioEngineKind.ExoPlayer, phase = PlaybackPhase.LOADING,
            generation = generation, activeGeneration = generation, currentItem = item, paused = desiredPaused, error = null) }
          clearTimelinePropertiesLocked()
          propNode.emit("track-list", null)
          audioLoopStartMs = null
          audioLoopEndMs = null
          val releaseCompletion = audioReleaseCompletion
          audioLoadJob?.cancel()
          audioLoadJob = audioScope.launch {
            var transferred = false
            var source: AudioPlaybackSource? = null
            try {
              releaseCompletion.await()
              kotlinx.coroutines.withContext(Dispatchers.IO) { source = prepareAudioSource(item) }
              if (item.audiobook != null) {
                AudiobookPlayback.ensureStarted()
                kotlinx.coroutines.withContext(Dispatchers.IO) { AudiobookPlayback.applyBookSettings(item, generation) }
              }
              nativeLock.withLock {
                if (applicationContext != null && usingExoPlayer && isCurrentGeneration(generation) && _state.value.phase == PlaybackPhase.LOADING) {
                  val latest = _audioState.value
                  val engine = audioEngine ?: createAudioEngine().also { audioEngine = it }
                  engine.setProcessing(audioProcessing)
                  engine.setOutputSettings(AudioOutputSettings(
                    audioPreferences.preferDolbyAtmos.get(), audioPreferences.spatialAudio.get(),
                    audioPreferences.wifiMaxBitrate.get(), audioPreferences.mobileMaxBitrate.get(),
                  ))
                  engine.setOutputSampleRate(audioPreferences.outputSampleRate.get())
                  engine.setSpeed(latest.speed, audioPreferences.audioPitchCorrection.get())
                  engine.setVolume(latest.volume)
                  engine.setDuckGain(if (audioDuckOwners.isEmpty()) 1f else 0.5f)
                  engine.setMuted(latest.muted)
                  engine.setLoop(null, null)
                  engine.setCrossfade(audioPreferences.crossfadeDurationMs.get(), audioAutoplayAllowed() && audioTransitionBlocks.value.isEmpty())
                  engine.load(checkNotNull(source), generation, position)
                  transferred = true
                }
              }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              if (usingExoPlayer && isCurrentGeneration(generation)) {
                val remote = Uri.parse(item.originalUri).scheme in setOf("http", "https")
                audioFailed(generation, androidx.media3.common.PlaybackException(
                  "Audio source preparation failed", error,
                  if (remote) androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                  else androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                ), position)
              }
            } finally {
              if (!transferred) source?.close()
            }
          }
          generation
        }
      }
    }
    return commit?.invoke(performCommit) ?: performCommit()
  }

  private suspend fun prepareAudioSource(item: PlaybackItem): AudioPlaybackSource {
    val resolved = resolvePlayableUri(item, forExoPlayer = true)
    val context = checkNotNull(applicationContext)
    val resolver = app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
    val declaredStream = item.mimeType?.startsWith("audio/", true) == true ||
      item.mimeType?.lowercase(java.util.Locale.ROOT) in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml")
    val webSource = if (resolved.registration == null && item.networkSource == null) {
      sequenceOf(item.originalUri, resolved.uri).firstOrNull { candidate ->
        resolver.requiresYtdlp(candidate) && (!declaredStream ||
          HttpUtils.isYouTubeUrl(Uri.parse(candidate)) || HttpUtils.isMusicStreamingUrl(Uri.parse(candidate)))
      }
    } else null
    return try {
      if (webSource == null) {
        AudioPlaybackSource(item, resolved.uri) { resolved.registration?.let(::releaseNetworkStream) }
      } else {
        val metered = context.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        val bitrate = if (metered) audioPreferences.mobileMaxBitrate.get() else audioPreferences.wifiMaxBitrate.get()
        val stream = resolver.resolveAudioStream(context, webSource, bitrate, item.headers)
        AudioPlaybackSource(item.copy(
          headers = stream.headers,
          title = item.title ?: stream.title, artist = item.artist ?: stream.artist,
        ), stream.url, stream.mimeType) { resolved.registration?.let(::releaseNetworkStream) }
      }
    } catch (error: Throwable) {
      resolved.registration?.let(::releaseNetworkStream)
      throw error
    }
  }

  private fun interruptAudioReplacementLocked() {
    if (!usingExoPlayer) return
    cancelAudioPreload()
    acceptedCrossfadeQueue = null
    audioEngine?.silence()
  }

  private fun releaseAudioEngineLocked(onReleased: () -> Unit = {}) {
    cancelAudioPreload()
    audioLoadJob?.cancel()
    audioLoadJob = null
    audioRetryJob?.cancel()
    audioRetryJob = null
    val engine = audioEngine
    audioEngine = null
    val previousRelease = audioReleaseCompletion
    val completion = CompletableDeferred<Unit>()
    audioReleaseCompletion = completion
    val finishRelease = {
      audioScope.launch {
        previousRelease.await()
        try {
          onReleased()
        } finally {
          completion.complete(Unit)
        }
      }
      Unit
    }
    if (engine == null) finishRelease() else engine.release(finishRelease)
  }

  private fun createAudioEngine(): AudioPlaybackEngine = ExoAudioEngine(
    checkNotNull(applicationContext),
    org.koin.java.KoinJavaComponent.get<okhttp3.OkHttpClient>(okhttp3.OkHttpClient::class.java),
    pauseRequested = { desiredPaused },
    isCurrentGeneration = { usingExoPlayer && isCurrentGeneration(it) &&
      _state.value.phase !in setOf(PlaybackPhase.STOPPING, PlaybackPhase.IDLE, PlaybackPhase.UNINITIALIZED, PlaybackPhase.ERROR) },
    onSnapshot = ::acceptAudioSnapshot,
    onReady = { generation ->
      nativeLock.withLock {
        if (usingExoPlayer && isCurrentGeneration(generation)) {
          loadedGeneration = generation
          loadedPlaybackItem = _state.value.currentItem
          loadedAudiobookDurationMs = _audioState.value.durationMs
          loadedPlaybackItem?.let { AudiobookPlayback.onFileLoaded(it, generation) }
          _audioEvents.tryEmit(AudioPlaybackEvent.Ready(generation))
        }
      }
    },
    onEnded = ::audioEnded,
    onError = ::audioFailed,
    onPrepareNext = ::prepareNextAudio,
    onHandoff = { next, outgoing ->
      nativeLock.withLock {
        if (!usingExoPlayer || _state.value.paused || _state.value.generation != next.generation || _queue.value != next.before ||
          !audioAutoplayAllowed() || audioTransitionBlocks.value.isNotEmpty() ||
          audioPreferences.audioEngine.get() != AudioEngineKind.ExoPlayer
        ) null else {
          audioRetryAttempts = 0
          publishAudioLeaving(outgoing)
          acceptedCrossfadeQueue = next.after
          _queue.value = next.after
          val generation = next.generation + 1
          loadedGeneration = generation
          loadedPlaybackItem = next.source.item
          updateState { it.copy(generation = generation, activeGeneration = generation, currentItem = next.source.item) }
          generation
        }
      }
    },
  )

  private fun acceptAudioSnapshot(engineSnapshot: AudioEngineSnapshot) {
    nativeLock.withLock {
      val snapshot = engineSnapshot.copy(paused = desiredPaused)
      if (!usingExoPlayer || !isCurrentGeneration(snapshot.generation) ||
        _state.value.phase in setOf(PlaybackPhase.STOPPING, PlaybackPhase.ERROR)
      ) return
      val previous = _audioState.value
      _audioState.value = snapshot
      if (snapshot.ready) {
        loadedGeneration = snapshot.generation
        loadedPlaybackItem = snapshot.item
        loadedAudiobookDurationMs = snapshot.durationMs
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAudioSaveTime >= 5_000L || snapshot.generation != previous.generation || !previous.ready ||
          snapshot.paused != previous.paused || snapshot.speed != previous.speed
        ) {
          lastAudioSaveTime = now
          audioSaves.trySend(AudioSave(snapshot))
        }
      }
      updateAudioReporter(snapshot)
      updateState { it.copy(paused = snapshot.paused, phase = if (snapshot.ready) {
        if (it.phase == PlaybackPhase.BACKGROUND) PlaybackPhase.BACKGROUND else PlaybackPhase.READY
      } else PlaybackPhase.LOADING) }
      val listeners = observerSnapshot()
      for (property in AudioPropertyAdapter.numbers) {
        val value = AudioPropertyAdapter.number(snapshot, property)
        propDouble.emit(property, value)
        propFloat.emit(property, value?.toFloat())
        propInt.emit(property, value?.toInt())
        propLong.emit(property, value?.toLong())
        if (value != null && (snapshot.generation != previous.generation || value != AudioPropertyAdapter.number(previous, property))) {
          listeners.forEach { observer ->
            runCatching {
              if (property in setOf("aid", "vid", "sid", "secondary-sid", "chapter", "chapters")) observer.eventProperty(property, value.toLong())
              else observer.eventProperty(property, value)
            }
          }
        }
      }
      for (property in AudioPropertyAdapter.flags) {
        val value = AudioPropertyAdapter.flag(snapshot, property) ?: continue
        propBoolean.emit(property, value)
        if (property != "eof-reached" && (snapshot.generation != previous.generation || value != AudioPropertyAdapter.flag(previous, property))) {
          listeners.forEach { runCatching { it.eventProperty(property, value) } }
        }
      }
      for (property in AudioPropertyAdapter.strings) {
        val value = AudioPropertyAdapter.text(snapshot, property)
        propString.emit(property, value)
        if (value != null && (snapshot.generation != previous.generation || value != AudioPropertyAdapter.text(previous, property))) {
          listeners.forEach { runCatching { it.eventProperty(property, value) } }
        }
      }
    }
  }

  private fun cancelAudioPreload() {
    audioPreloadSequence.incrementAndGet()
    audioPreloadJob?.cancel()
    audioPreloadJob = null
  }

  private fun prepareNextAudio(generation: Long) {
    cancelAudioPreload()
    val before = _queue.value
    val after = PlaybackQueueReducer.next(before) ?: return
    val item = after.currentItem ?: return
    if (!item.isDefinitelyAudioOnly() || item.audiobook != null || item.requiresTorrentResolution() ||
      audioPreferences.audioEngine.get() != AudioEngineKind.ExoPlayer
    ) return
    val request = audioPreloadSequence.incrementAndGet()
    audioPreloadJob = audioScope.launch(Dispatchers.IO) {
      var source: AudioPlaybackSource? = null
      try {
        source = prepareAudioSource(item)
        nativeLock.withLock {
          val engine = audioEngine
          if (engine != null && usingExoPlayer && isCurrentGeneration(generation) && _queue.value == before && request == audioPreloadSequence.get()) {
            engine.prepareNext(PreparedAudioNext(checkNotNull(source), generation, before, after))
            source = null
          }
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        Log.d(TAG, "Next audio item was not preloaded; using sequential playback")
      } finally {
        source?.close()
      }
    }
  }

  private fun audioAutoplayAllowed(): Boolean {
    val preferences = org.koin.java.KoinJavaComponent.get<PlayerPreferences>(PlayerPreferences::class.java)
    return _queue.value.repeatMode != RepeatMode.ONE &&
      (preferences.autoplayNextAudio.get() || _queue.value.repeatMode == RepeatMode.ALL)
  }

  private fun audioEnded(generation: Long) {
    if (!usingExoPlayer || !isCurrentGeneration(generation) || _state.value.paused) return
    if (_state.value.currentItem?.audiobook != null) {
      AudiobookPlayback.capture(reachedEnd = true)
      if (AudiobookPlayback.handleEndOfFile()) return
    }
    if (_queue.value.repeatMode == RepeatMode.ONE) {
      audioEngine?.seekTo(0)
      audioEngine?.setPaused(false)
      return
    }
    if ((_state.value.currentItem?.audiobook != null || audioAutoplayAllowed()) && hasNext()) {
      audioScope.launch(Dispatchers.IO) {
        nativeLock.withLock {
          if (usingExoPlayer && isCurrentGeneration(generation)) {
            runCatching { playNext() }.onFailure { failAudioState(generation) }
          }
        }
      }
    } else {
      publishAudioLeaving()
      desiredPaused = true
      audioEngine?.setPaused(true)
      updateState { it.copy(paused = true) }
      _audioEvents.tryEmit(AudioPlaybackEvent.Finished(generation))
    }
  }

  private fun audioFailed(generation: Long, error: androidx.media3.common.PlaybackException, positionMs: Long) {
    if (!usingExoPlayer || !isCurrentGeneration(generation)) return
    val item = _state.value.currentItem ?: return
    if (audioRetryJob?.isActive == true) return
    val responseCode = generateSequence<Throwable>(error) { it.cause }
      .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
      .firstOrNull()?.responseCode
    val retryable = when (error.errorCode) {
      androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> true
      androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        responseCode in setOf(401, 403, 408, 410, 429) || responseCode != null && responseCode in 500..599
      else -> false
    }
    if (retryable && audioRetryAttempts < 3) {
      val attempt = ++audioRetryAttempts
      val resumeSeconds = if (_audioState.value.seekable && !_audioState.value.live) positionMs.coerceAtLeast(0) / 1000.0 else 0.0
      updateState { it.copy(phase = PlaybackPhase.LOADING, error = null) }
      audioRetryJob = audioScope.launch {
        kotlinx.coroutines.delay(750L * (1L shl (attempt - 1)))
        if (!usingExoPlayer || !isCurrentGeneration(generation)) return@launch
        audioRetryJob = null
        try {
          nativeLock.withLock {
            if (usingExoPlayer && isCurrentGeneration(generation)) {
              loadAudio(item, PlaybackPositionRestoreOverride(resumeSeconds, desiredPaused), null, null, retryAttempt = attempt)
            }
          }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          audioFailed(generation, error, positionMs)
        }
      }
      return
    }
    val atmosSource = _audioState.value.output.dolbyAtmosSource || item.mimeType == androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC
    if (atmosSource) {
      failAudioState(generation, app.gyrolet.mpvrx.R.string.audio_atmos_unsupported)
    } else {
      failAudioState(generation)
    }
  }

  private fun failAudioState(generation: Long, @androidx.annotation.StringRes messageRes: Int = app.gyrolet.mpvrx.R.string.toast_playback_load_failed) {
    nativeLock.withLock {
      if (!isCurrentGeneration(generation)) return
      audioRetryJob?.cancel()
      audioRetryJob = null
      desiredPaused = true
      audioEngine?.setPaused(true)
      updateState { it.copy(phase = PlaybackPhase.ERROR, paused = true,
        error = applicationContext?.getString(messageRes)) }
    }
  }

  private fun publishAudioLeaving(snapshot: AudioEngineSnapshot = _audioState.value) {
    if (snapshot.generation == audioReporterGeneration) {
      audioReporter?.reportPlaybackStop(snapshot.positionMs)
      audioReporter = null
      audioReporterGeneration = 0L
    }
    if (usingExoPlayer && snapshot.ready && snapshot.generation != audioLeavingGeneration) {
      audioLeavingGeneration = snapshot.generation
      audioSaves.trySend(AudioSave(snapshot))
      _audioEvents.tryEmit(AudioPlaybackEvent.Leaving(snapshot))
    }
  }

  private fun updateAudioReporter(snapshot: AudioEngineSnapshot) {
    val item = snapshot.item ?: return
    if (snapshot.playing && audioHistoryGeneration != snapshot.generation) {
      audioHistoryGeneration = snapshot.generation
      val allowed = audioHistoryAllowed
      val playlistId = audioHistoryPlaylistId
      val context = applicationContext
      if (allowed && context != null) audioScope.launch(Dispatchers.IO) {
        runCatching {
          val uri = Uri.parse(item.originalUri)
          val path = when (uri.scheme) {
            "file" -> uri.path ?: item.originalUri
            "content" -> runCatching {
              context.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
              }
            }.getOrNull() ?: item.originalUri
            else -> item.originalUri
          }
          RecentlyPlayedOps.addRecentlyPlayed(
            filePath = path,
            fileName = item.title ?: snapshot.title ?: uri.lastPathSegment.orEmpty(),
            videoTitle = snapshot.title,
            duration = snapshot.durationMs,
            launchSource = "playlist",
            playlistId = playlistId,
            artworkUrl = item.artworkUri,
          )
        }.onFailure { Log.w(TAG, "Could not record audio playback history") }
      }
    }
    val now = android.os.SystemClock.elapsedRealtime()
    if (snapshot.playing && audioReporterGeneration != snapshot.generation) {
      audioReporter = JellyfinSessionReporter.create(
        url = item.originalUri,
        httpClient = org.koin.java.KoinJavaComponent.get<okhttp3.OkHttpClient>(okhttp3.OkHttpClient::class.java),
        fallbackToken = item.headers.entries.firstOrNull { it.key.equals("X-Emby-Token", true) }?.value,
      )
      audioReporterGeneration = snapshot.generation
      audioReportedPaused = snapshot.paused
      lastAudioReportTime = now
      audioReporter?.reportPlaybackStart(snapshot.positionMs)
    } else if (snapshot.generation == audioReporterGeneration &&
      (now - lastAudioReportTime >= 10_000L || snapshot.paused != audioReportedPaused)
    ) {
      audioReporter?.reportPlaybackProgress(snapshot.positionMs, snapshot.paused)
      audioReportedPaused = snapshot.paused
      lastAudioReportTime = now
    }
  }

  private fun setAudioNumber(property: String, value: Double) {
    if (!value.isFinite()) return
    when (property) {
      "time-pos", "playback-time" -> audioEngine?.seekTo((value.coerceAtLeast(0.0) * 1000).toLong())
      "volume" -> {
        _audioState.update { it.copy(volume = value.toFloat().coerceIn(0f, 300f)) }
        audioEngine?.setVolume(value.toFloat())
      }
      "speed" -> {
        _audioState.update { it.copy(speed = value.toFloat().coerceIn(0.1f, 4f)) }
        audioEngine?.setSpeed(value.toFloat(), audioPreferences.audioPitchCorrection.get())
      }
      "aid" -> audioEngine?.selectTrack(value.toInt())
      "chapter" -> _audioState.value.chapters.getOrNull(value.toInt())?.let { audioEngine?.seekTo(it.positionMs) }
      "ab-loop-a", "ab-loop-b" -> setAudioLoopPoint(property, (value * 1000).toLong())
      else -> Unit
    }
  }

  private fun setAudioLoopPoint(property: String, positionMs: Long?) = nativeLock.withLock {
    if (property == "ab-loop-a") audioLoopStartMs = positionMs else audioLoopEndMs = positionMs
    audioEngine?.setLoop(audioLoopStartMs, audioLoopEndMs)
  }

  private fun audioCommand(command: Array<out String>): Boolean {
    when (command.firstOrNull()) {
      "seek" -> {
        val amount = command.getOrNull(1)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return false
        val mode = command.getOrNull(2).orEmpty()
        val snapshot = _audioState.value
        val position = when {
          "absolute-percent" in mode -> snapshot.durationMs * amount / 100.0
          "relative-percent" in mode -> snapshot.positionMs + snapshot.durationMs * amount / 100.0
          "absolute" in mode -> amount * 1000.0
          else -> snapshot.positionMs + amount * 1000.0
        }
        audioEngine?.seekTo(position.coerceAtLeast(0.0).toLong())
      }
      "cycle" -> if (command.getOrNull(1) == "pause") togglePause() else return false
      "set" -> setPropertyString(command.getOrNull(1) ?: return false, command.getOrNull(2) ?: return false)
      "add" -> {
        val property = command.getOrNull(1) ?: return false
        val delta = command.getOrNull(2)?.toDoubleOrNull() ?: return false
        setAudioNumber(property, (getPropertyDouble(property) ?: 0.0) + delta)
      }
      "stop", "quit" -> stop()
      "playlist-next" -> playNext()
      "playlist-prev" -> playPrevious()
      else -> return false
    }
    return true
  }

  private fun resolvePlayableUri(item: PlaybackItem, forExoPlayer: Boolean = false): ResolvedPlayable {
    if (forExoPlayer && item.networkSource == null) {
      exoLocalUri(item.originalUri)?.let { return ResolvedPlayable(it) }
      exoLocalUri(item.playableUri)?.let { return ResolvedPlayable(it) }
    }
    val xtreamReference = XtreamPlaybackUri.parse(item.playableUri)
    if (xtreamReference != null) {
      val proxy = XtreamStreamingProxy.getInstance()
      val streamId = "xtream-${streamSequence.incrementAndGet()}"
      val uri =
        proxy.registerStream(
          streamId = streamId,
          reference = xtreamReference,
          headers = item.headers,
          mimeType = item.mimeType ?: "application/octet-stream",
        )
      return ResolvedPlayable(uri, NetworkStreamRegistration(xtreamProxy = proxy, streamId = streamId))
    }

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

    if (!forExoPlayer && M3uPlaybackPolicy.shouldProxyHls(item.playableUri, item.mimeType)) {
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
      val context = applicationContext ?: error("Application context is unavailable for content URI playback")
      val refreshedUri =
        Uri.parse(item.originalUri).openContentFd(context)
          ?: error("Unable to reopen content URI for playback")
      return ResolvedPlayable(refreshedUri)
    }

    if (!item.playableUri.startsWith("content://")) return ResolvedPlayable(item.playableUri)
    val context = applicationContext ?: return ResolvedPlayable(item.playableUri)
    return ResolvedPlayable(Uri.parse(item.playableUri).openContentFd(context) ?: item.playableUri)
  }

  private fun exoLocalUri(value: String): String? {
    if (value.startsWith("fd://", ignoreCase = true)) return null
    val uri = Uri.parse(value)
    return when (uri.scheme?.lowercase()) {
      "content", "android.resource" -> uri.toString()
      "file" -> uri.path?.let { Uri.fromFile(File(it)).toString() }
      null -> value.takeIf { File(it).isAbsolute }?.let { Uri.fromFile(File(it)).toString() }
      else -> null
    }
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
      registration.xtreamProxy?.unregisterStream(registration.streamId)
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
