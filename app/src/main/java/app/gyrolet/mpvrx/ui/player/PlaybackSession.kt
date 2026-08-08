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
import android.util.Log
import android.view.Surface
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

  private data class NetworkStreamRegistration(
    val proxy: NetworkStreamingProxy,
    val streamId: String,
  )

  private data class ResolvedPlayable(
    val uri: String,
    val registration: NetworkStreamRegistration? = null,
  )

  private val nativeLock = ReentrantLock(true)
  private val observers = CopyOnWriteArraySet<MPVLib.EventObserver>()
  private val pendingGenerations = ArrayDeque<Long>()
  private val _state = MutableStateFlow(PlaybackSessionState())
  private val _queue = MutableStateFlow(PlaybackQueueState())
  private val streamSequence = AtomicLong()
  private val observedProperties = mutableSetOf<Pair<String, Int>>()

  val state: StateFlow<PlaybackSessionState> = _state.asStateFlow()
  val queue: StateFlow<PlaybackQueueState> = _queue.asStateFlow()

  @Volatile
  private var initialized = false
  private var applicationContext: Context? = null
  private var desiredVideoOutput = "gpu"
  private var activeNetworkStream: NetworkStreamRegistration? = null
  private val auxiliaryNetworkStreams = linkedMapOf<String, NetworkStreamRegistration>()

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
    initOptions: () -> Unit,
    postInitOptions: () -> Unit,
    observeProperties: () -> Unit,
  ): Result<Boolean> =
    runCatching {
      nativeLock.withLock {
        if (initialized) return@withLock false

        applicationContext = context.applicationContext
        observedProperties.clear()
        updateState { it.copy(phase = PlaybackPhase.INITIALIZING, error = null) }
        try {
          MPVLib.create(context.applicationContext)
          MPVLib.setOptionString("config", "yes")
          MPVLib.setOptionString("config-dir", configDir)
          MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir)
          MPVLib.setOptionString("icc-cache-dir", cacheDir)
          initOptions()
          MPVLib.init()
          postInitOptions()
          MPVLib.setOptionString("force-window", "no")
          MPVLib.setOptionString("idle", "yes")
          MPVLib.addObserver(this)
          reobserveTrackedProperties()
          observeProperties()
          initialized = true
          updateState { it.copy(phase = PlaybackPhase.IDLE, error = null) }
          true
        } catch (error: Throwable) {
          runCatching { MPVLib.removeObserver(this) }
          runCatching { MPVLib.destroy() }
          initialized = false
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
  ): Boolean =
    withCore(default = false) {
      if (!surface.isValid) return@withCore false
      if (_state.value.surfaceAttached) runCatching { MPVLib.detachSurface() }
      MPVLib.attachSurface(surface)
      width?.takeIf { it > 0 }?.let { resolvedWidth ->
        height?.takeIf { it > 0 }?.let { resolvedHeight ->
          MPVLib.setPropertyString("android-surface-size", "${resolvedWidth}x$resolvedHeight")
        }
      }
      MPVLib.setOptionString("force-window", "yes")
      MPVLib.setPropertyString("vo", desiredVideoOutput)
      updateState { it.copy(surfaceAttached = true) }
      true
    }

  fun resizeSurface(
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    withCore(Unit) { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
  }

  fun unbindSurface() {
    withCore(Unit) {
      if (!_state.value.surfaceAttached) return@withCore
      runCatching { MPVLib.setPropertyString("vo", "null") }
      runCatching { MPVLib.setOptionString("force-window", "no") }
      runCatching { MPVLib.detachSurface() }
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
    updateState { current ->
      if (current.phase == PlaybackPhase.BACKGROUND) current.copy(phase = PlaybackPhase.READY) else current
    }
  }

  /** Stop playback while keeping the app-scoped core ready for a later screen attachment. */
  fun stop(clearQueue: Boolean = true) {
    withCore(Unit) {
      val nextGeneration = _state.value.generation + 1L
      pendingGenerations.clear()
      runCatching { MPVLib.command("stop") }
      runCatching { MPVLib.setPropertyBoolean("pause", true) }
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
    }
    releaseActiveNetworkStream()
    releaseAuxiliaryNetworkStreams()
  }

  /** Native destruction is reserved for process-level shutdown or an unrecoverable init reset. */
  fun destroy() {
    nativeLock.withLock {
      if (!initialized) return
      updateState { it.copy(phase = PlaybackPhase.STOPPING) }
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
      initialized = false
      updateState { PlaybackSessionState(phase = PlaybackPhase.UNINITIALIZED) }
    }
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

  fun load(item: PlaybackItem): Long {
    val resolved = resolvePlayableUri(item)
    return try {
      val generation = load(playableUri = resolved.uri, item = item)
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
  ): Long =
    withCore(default = -1L) {
      val generation = _state.value.generation + 1L
      pendingGenerations.addLast(generation)
      val resolvedItem = item ?: PlaybackItem.fromUri(playableUri)
      updateState {
        it.copy(
          phase = PlaybackPhase.LOADING,
          generation = generation,
          currentItem = resolvedItem,
          error = null,
        )
      }
      val userAgent = resolvedItem.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
      val headerFields =
        resolvedItem.headers.entries
          .filterNot { it.key.equals("User-Agent", ignoreCase = true) }
          .joinToString(",") { (name, value) -> "$name: ${value.replace(",", "\\,")}" }
      MPVLib.setPropertyString("user-agent", userAgent.orEmpty())
      MPVLib.setPropertyString("http-header-fields", headerFields)
      MPVLib.command("loadfile", playableUri, "replace", "-1", "pause=no")
      updateState { it.copy(paused = false) }
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
    withCore(Unit) { MPVLib.command(*command) }
  }

  /** Executes a media-specific command only while its load generation is still current. */
  fun commandForGeneration(
    expectedGeneration: Long,
    vararg command: String,
  ): Boolean =
    nativeLock.withLock {
      if (!initialized || _state.value.generation != expectedGeneration) return@withLock false
      MPVLib.command(*command)
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

  fun getPropertyInt(property: String): Int? = withCore(null) { MPVLib.getPropertyInt(property) }

  fun setPropertyInt(
    property: String,
    value: Int,
  ) = withCore(Unit) { MPVLib.setPropertyInt(property, value) }

  fun getPropertyDouble(property: String): Double? = withCore(null) { MPVLib.getPropertyDouble(property) }

  fun setPropertyDouble(
    property: String,
    value: Double,
  ) = withCore(Unit) { MPVLib.setPropertyDouble(property, value) }

  fun getPropertyFloat(property: String): Float? = withCore(null) { MPVLib.getPropertyFloat(property) }

  fun setPropertyFloat(
    property: String,
    value: Float,
  ) = withCore(Unit) { MPVLib.setPropertyFloat(property, value) }

  fun getPropertyBoolean(property: String): Boolean? = withCore(null) { MPVLib.getPropertyBoolean(property) }

  fun setPropertyBoolean(
    property: String,
    value: Boolean,
  ) = withCore(Unit) {
    MPVLib.setPropertyBoolean(property, value)
    if (property == "pause") {
      updateState { it.copy(paused = value) }
      propBoolean.emit(property, value)
    }
  }

  /** Atomically toggles pause so rapid UI/media-button taps cannot race separate reads and writes. */
  fun togglePause(): Boolean? =
    withCore(default = null) {
      val nextPaused = !(MPVLib.getPropertyBoolean("pause") ?: _state.value.paused)
      MPVLib.setPropertyBoolean("pause", nextPaused)
      updateState { it.copy(paused = nextPaused) }
      propBoolean.emit("pause", nextPaused)
      nextPaused
    }

  fun getPropertyString(property: String): String? = withCore(null) { MPVLib.getPropertyString(property) }

  fun getPropertyNode(property: String): MPVNode? = withCore(null) { MPVLib.getPropertyNode(property) }

  fun setPropertyString(
    property: String,
    value: String,
  ) = withCore(Unit) { MPVLib.setPropertyString(property, value) }

  fun grabThumbnail(dimension: Int): Bitmap? = withCore(null) { MPVLib.grabThumbnail(dimension) }

  fun grabThumbnailFast(
    path: String,
    position: Double = 0.0,
    dimension: Int,
    useHwDec: Boolean = true,
  ): Bitmap? = withCore(null) { MPVLib.grabThumbnailFast(path, position, dimension, useHwDec) }

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
      auxiliaryNetworkStreams[uri] = NetworkStreamRegistration(proxy, streamId)
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
    if (property == "pause") updateState { it.copy(paused = value) }
    propBoolean.emit(property, value)
    observerSnapshot().forEach { observer -> runCatching { observer.eventProperty(property, value) } }
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
              updateState {
                it.copy(
                  phase = if (it.phase == PlaybackPhase.BACKGROUND) PlaybackPhase.BACKGROUND else PlaybackPhase.READY,
                  error = null,
                )
              }
              true
            }
          }
          MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> {
            releaseActiveNetworkStream()
            releaseAuxiliaryNetworkStreams()
            initialized = false
            updateState { it.copy(phase = PlaybackPhase.UNINITIALIZED, surfaceAttached = false) }
            true
          }
          else -> true
        }
      }

    if (shouldForward) {
      observerSnapshot().forEach { observer -> runCatching { observer.event(eventId, data) } }
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
      return ResolvedPlayable(uri, NetworkStreamRegistration(proxy, streamId))
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
    runCatching { registration.proxy.unregisterStream(registration.streamId) }
      .onFailure { error -> Log.w(TAG, "Failed to release network stream", error) }
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

  private inline fun updateState(transform: (PlaybackSessionState) -> PlaybackSessionState) {
    _state.update(transform)
  }
}
