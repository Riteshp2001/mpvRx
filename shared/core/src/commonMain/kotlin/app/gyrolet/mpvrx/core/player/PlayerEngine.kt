package app.gyrolet.mpvrx.core.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TrackType { Video, Audio, Subtitle }

data class MediaTrack(
  val id: Int,
  val type: TrackType,
  val title: String?,
  val language: String?,
  val codec: String?,
  val isDefault: Boolean,
  val isExternal: Boolean,
  val isSelected: Boolean,
  val channels: Int? = null,
  val width: Int? = null,
  val height: Int? = null,
) {
  val label: String
    get() =
      buildList {
        title?.takeIf(String::isNotBlank)?.let(::add)
        language?.takeIf(String::isNotBlank)?.let { add(it.uppercase()) }
        codec?.takeIf(String::isNotBlank)?.let { add(it.uppercase()) }
        channels?.takeIf { it > 0 }?.let { add("${it}ch") }
      }.ifEmpty { listOf("Track $id") }.joinToString(" · ")
}

data class Chapter(
  val index: Int,
  val title: String,
  val startSeconds: Double,
)

data class PlayerState(
  val isInitialized: Boolean = false,
  val isIdle: Boolean = true,
  val path: String? = null,
  val mediaTitle: String = "",
  val positionSeconds: Double = 0.0,
  val durationSeconds: Double = 0.0,
  val bufferedSeconds: Double = 0.0,
  val isPaused: Boolean = true,
  val isBuffering: Boolean = false,
  val isSeeking: Boolean = false,
  val volume: Int = 100,
  val maxVolume: Int = 130,
  val isMuted: Boolean = false,
  val speed: Double = 1.0,
  val tracks: List<MediaTrack> = emptyList(),
  val chapters: List<Chapter> = emptyList(),
  val currentChapter: Int = -1,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
  val hwdecCurrent: String? = null,
  val subtitleDelaySeconds: Double = 0.0,
  val audioDelaySeconds: Double = 0.0,
  val loopFile: Boolean = false,
  val abLoopA: Double? = null,
  val abLoopB: Double? = null,
  val videoAspect: Double = 0.0,
  val isAudioOnly: Boolean = false,
  val errorMessage: String? = null,
) {
  val audioTracks get() = tracks.filter { it.type == TrackType.Audio }
  val subtitleTracks get() = tracks.filter { it.type == TrackType.Subtitle }
  val videoTracks get() = tracks.filter { it.type == TrackType.Video }
  val hasVideo get() = videoWidth > 0 && videoHeight > 0
  val progress: Float get() = if (durationSeconds > 0) (positionSeconds / durationSeconds).toFloat().coerceIn(0f, 1f) else 0f
  val chapterTitle: String? get() = chapters.getOrNull(currentChapter)?.title
}

sealed interface PlayerEvent {
  data class FileLoaded(val path: String) : PlayerEvent

  /** [reachedEnd] separates natural EOF from user stop/replace. */
  data class EndOfFile(val path: String?, val reachedEnd: Boolean, val error: String?) : PlayerEvent

  data class Message(val text: String) : PlayerEvent
}

/** Options applied when a file is loaded; null keeps the engine default. */
data class LoadOptions(
  val startSeconds: Double? = null,
  val paused: Boolean = false,
  val externalSubtitles: List<String> = emptyList(),
  val httpHeaders: Map<String, String> = emptyMap(),
)

/**
 * Playback engine shared by every platform UI. Desktop implements it with libmpv's
 * render API; iOS will implement it with MPVKit. Controls never talk to mpv directly.
 */
interface PlayerEngine {
  val state: StateFlow<PlayerState>
  val events: Flow<PlayerEvent>

  fun load(path: String, options: LoadOptions = LoadOptions())

  fun stop()

  fun setPaused(paused: Boolean)

  fun togglePause() = setPaused(!state.value.isPaused)

  fun seekTo(seconds: Double, precise: Boolean = false)

  fun seekBy(seconds: Double, precise: Boolean = false)

  fun setVolume(volume: Int)

  fun setMuted(muted: Boolean)

  fun setSpeed(speed: Double)

  fun selectTrack(type: TrackType, id: Int?)

  fun addSubtitle(path: String)

  fun setSubtitleDelay(seconds: Double)

  fun setAudioDelay(seconds: Double)

  fun setChapter(index: Int)

  fun setLoopFile(loop: Boolean)

  fun setAbLoop(a: Double?, b: Double?)

  fun setVideoAspect(aspect: AspectMode)

  fun setVideoZoom(zoom: Double)

  fun frameStep(forward: Boolean)

  /** Writes a screenshot into [directory]; returns false if unavailable. */
  fun screenshot(directory: String, includeSubtitles: Boolean): Boolean

  fun setProperty(name: String, value: String)

  fun getProperty(name: String): String?

  fun command(vararg args: String)

  fun release()
}

sealed interface AspectMode {
  data object Fit : AspectMode

  data object Crop : AspectMode

  data object Stretch : AspectMode

  data class Ratio(val value: Double, val label: String) : AspectMode
}
