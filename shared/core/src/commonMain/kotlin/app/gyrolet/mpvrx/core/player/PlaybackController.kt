package app.gyrolet.mpvrx.core.player

import app.gyrolet.mpvrx.core.history.PlaybackHistoryRepository
import app.gyrolet.mpvrx.core.history.PlaybackRecord
import app.gyrolet.mpvrx.core.media.MediaExtensions
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.media.MediaScanner
import app.gyrolet.mpvrx.core.media.isNetworkUrl
import app.gyrolet.mpvrx.core.preferences.AppSettings
import app.gyrolet.mpvrx.core.preferences.PlayerPreferences
import app.gyrolet.mpvrx.core.preferences.RepeatMode
import app.gyrolet.mpvrx.core.preferences.ResumePlaybackMode
import app.gyrolet.mpvrx.core.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Platform-neutral port of the Android PlayerViewModel's session logic: queue building,
 * resume, history, autoplay and preference application. UIs only call into this class.
 */
class PlaybackController(
  private val engine: PlayerEngine,
  private val settings: AppSettings,
  private val history: PlaybackHistoryRepository,
  private val scanner: MediaScanner,
  private val scope: CoroutineScope,
) {
  val queue = PlaybackQueue()
  val playerState: StateFlow<PlayerState> = engine.state

  private val mutableToasts = MutableSharedFlow<String>(extraBufferCapacity = 8)

  /** Short user-facing feedback ("Resumed at 12:31", errors) shown as the player's action pill. */
  val toasts: SharedFlow<String> = mutableToasts

  private val mutableSessionActive = MutableStateFlow(false)
  val sessionActive: StateFlow<Boolean> = mutableSessionActive.asStateFlow()

  /** Set when playback ended and the "close after EOF" preference asks the UI to leave the player. */
  private val mutableCloseRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val closeRequests: SharedFlow<Unit> = mutableCloseRequests

  private var saveJob: Job? = null

  init {
    queue.setRepeatMode(settings.player.repeatMode.get())
    queue.setShuffle(settings.player.shuffleEnabled.get())
    scope.launch {
      engine.events.collect { event ->
        when (event) {
          is PlayerEvent.FileLoaded -> onFileLoaded()
          is PlayerEvent.EndOfFile -> onEndOfFile(event)
          is PlayerEvent.Message -> mutableToasts.tryEmit(event.text)
        }
      }
    }
  }

  fun playFiles(files: List<MediaFile>, startIndex: Int) {
    if (files.isEmpty()) return
    val start = files[startIndex.coerceIn(files.indices)]
    val playlist =
      if (settings.player.shouldBuildQueue(start.isAudio)) files else listOf(start)
    queue.set(playlist.map { QueueItem(it.path, it.displayName, it.isAudio) }, playlist.indexOf(start))
    queue.state.value.current?.let(::loadItem)
  }

  fun playPaths(paths: List<String>, startIndex: Int = 0) {
    if (paths.isEmpty()) return
    queue.set(
      paths.map { path ->
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        QueueItem(path, name.substringBeforeLast('.', name).ifBlank { path }, MediaExtensions.isAudio(name))
      },
      startIndex,
    )
    queue.state.value.current?.let(::loadItem)
  }

  fun playUrl(url: String) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return
    val recents = settings.browser.recentNetworkUrls
    recents.set((listOf(trimmed) + recents.get().filterNot { it == trimmed }).take(20))
    playPaths(listOf(trimmed))
  }

  fun playQueueIndex(index: Int) {
    saveCurrentPosition()
    queue.jumpTo(index)?.let(::loadItem)
  }

  /** Play/pause that also restarts the current item once playback has ended. */
  fun togglePlayPause() {
    val current = queue.state.value.current
    if (engine.state.value.isIdle && current != null) loadItem(current) else engine.togglePause()
  }

  fun next() {
    saveCurrentPosition()
    queue.next()?.let(::loadItem)
  }

  fun previous() {
    // Matches the Android player: restart the current item when more than 3s in.
    if (engine.state.value.positionSeconds > 3) {
      engine.seekTo(0.0)
      return
    }
    saveCurrentPosition()
    queue.previous()?.let(::loadItem)
  }

  fun setRepeatMode(mode: RepeatMode) {
    settings.player.repeatMode.set(mode)
    queue.setRepeatMode(mode)
    engine.setLoopFile(mode == RepeatMode.ONE)
  }

  fun cycleRepeatMode() {
    val next =
      when (queue.state.value.repeatMode) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
      }
    setRepeatMode(next)
    mutableToasts.tryEmit("Repeat: ${next.name.lowercase().replaceFirstChar(Char::uppercase)}")
  }

  fun toggleShuffle() {
    val enabled = !queue.state.value.shuffle
    settings.player.shuffleEnabled.set(enabled)
    queue.setShuffle(enabled)
    mutableToasts.tryEmit(if (enabled) "Shuffle on" else "Shuffle off")
  }

  fun setVolume(volume: Int): Int {
    val capped = volume.coerceIn(0, 100 + settings.audio.volumeBoostCap.get())
    engine.setVolume(capped)
    if (settings.player.rememberVolume.get()) settings.player.lastVolume.set(capped)
    return capped
  }

  fun changeVolumeBy(delta: Int) {
    val applied = setVolume(engine.state.value.volume + delta)
    mutableToasts.tryEmit("Volume $applied%")
  }

  fun setSpeed(speed: Double) {
    engine.setSpeed(speed.coerceIn(0.1, 8.0))
  }

  fun stop() {
    saveCurrentPosition()
    saveJob?.cancel()
    engine.stop()
    mutableSessionActive.value = false
  }

  fun notify(message: String) {
    mutableToasts.tryEmit(message)
  }

  fun saveCurrentPosition() {
    val state = engine.state.value
    val item = queue.state.value.current ?: return
    if (!settings.player.savePositionOnQuit.get() || state.durationSeconds <= 0) return
    history.save(
      PlaybackRecord(
        path = item.path,
        title = state.mediaTitle.ifBlank { item.title },
        positionSeconds = state.positionSeconds,
        durationSeconds = state.durationSeconds,
        lastPlayedMillis = Clock.System.now().toEpochMilliseconds(),
        isAudio = item.isAudio || state.isAudioOnly,
      ),
    )
  }

  private fun loadItem(item: QueueItem) {
    mutableSessionActive.value = true
    scope.launch {
      val subtitles =
        if (!isNetworkUrl(item.path) && settings.subtitles.autoloadMatchingSubtitles.get()) {
          val name = item.path.substringAfterLast('/').substringAfterLast('\\')
          val folder = item.path.dropLast(name.length).trimEnd('/', '\\')
          runCatching {
            scanner.findSidecarSubtitles(MediaFile(item.path, name, folder, 0, 0, item.isAudio))
          }.getOrDefault(emptyList())
        } else {
          emptyList()
        }
      val resume = resumePositionFor(item)
      engine.load(item.path, LoadOptions(startSeconds = resume, externalSubtitles = subtitles))
      resume?.let { mutableToasts.tryEmit("Resumed at ${formatDuration(it)}") }
    }
    startPeriodicSave()
  }

  private fun resumePositionFor(item: QueueItem): Double? {
    val record = history.get(item.path) ?: return null
    if (record.isFinished || record.positionSeconds < 5) return null
    return when (settings.player.resumePlaybackMode.get()) {
      ResumePlaybackMode.Always -> record.positionSeconds
      ResumePlaybackMode.MinimumDuration ->
        record.positionSeconds.takeIf { record.durationSeconds >= settings.player.minimumResumeDurationSeconds.get() }
      ResumePlaybackMode.Never -> null
    }
  }

  private fun onFileLoaded() {
    val player = settings.player
    engine.setSpeed(player.defaultSpeed.get().toDouble())
    if (player.rememberVolume.get()) engine.setVolume(player.lastVolume.get())
    engine.setLoopFile(queue.state.value.repeatMode == RepeatMode.ONE)
  }

  private fun onEndOfFile(event: PlayerEvent.EndOfFile) {
    event.error?.let { mutableToasts.tryEmit("Playback error: $it") }
    if (!event.reachedEnd) return
    queue.state.value.current?.let { item ->
      history.get(item.path)?.let { history.save(it.copy(positionSeconds = it.durationSeconds)) }
    }
    val isAudio = queue.state.value.current?.isAudio == true
    val autoplay = if (isAudio) settings.player.autoplayNextAudio.get() else settings.player.autoplayNextVideo.get()
    val next = if (autoplay) queue.nextAfterCompletion() else null
    when {
      next != null -> loadItem(next)
      settings.player.closeAfterReachingEndOfVideo.get() -> {
        mutableSessionActive.value = false
        mutableCloseRequests.tryEmit(Unit)
      }
    }
  }

  private fun startPeriodicSave() {
    saveJob?.cancel()
    saveJob =
      scope.launch {
        while (isActive) {
          delay(10_000)
          if (!engine.state.value.isPaused) saveCurrentPosition()
        }
      }
  }
}

private fun PlayerPreferences.shouldBuildQueue(isAudio: Boolean): Boolean =
  playlistMode.get() || (if (isAudio) autoplayNextAudio.get() else autoplayNextVideo.get())
