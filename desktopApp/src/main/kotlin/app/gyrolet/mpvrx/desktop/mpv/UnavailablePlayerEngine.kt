package app.gyrolet.mpvrx.desktop.mpv

import app.gyrolet.mpvrx.core.player.AspectMode
import app.gyrolet.mpvrx.core.player.LoadOptions
import app.gyrolet.mpvrx.core.player.PlayerEngine
import app.gyrolet.mpvrx.core.player.PlayerEvent
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.player.TrackType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update

/** Keeps the library usable when libmpv is missing and explains why playback is unavailable. */
class UnavailablePlayerEngine(private val reason: String) : PlayerEngine {
  private val mutableState = MutableStateFlow(PlayerState(errorMessage = reason))
  override val state: StateFlow<PlayerState> = mutableState
  override val events: Flow<PlayerEvent> = emptyFlow()

  override fun load(path: String, options: LoadOptions) {
    mutableState.update { it.copy(path = path, mediaTitle = path.substringAfterLast('\\').substringAfterLast('/'), errorMessage = reason) }
  }

  override fun stop() = Unit

  override fun setPaused(paused: Boolean) = Unit

  override fun seekTo(seconds: Double, precise: Boolean) = Unit

  override fun seekBy(seconds: Double, precise: Boolean) = Unit

  override fun setVolume(volume: Int) = Unit

  override fun setMuted(muted: Boolean) = Unit

  override fun setSpeed(speed: Double) = Unit

  override fun selectTrack(type: TrackType, id: Int?) = Unit

  override fun addSubtitle(path: String) = Unit

  override fun setSubtitleDelay(seconds: Double) = Unit

  override fun setAudioDelay(seconds: Double) = Unit

  override fun setChapter(index: Int) = Unit

  override fun setLoopFile(loop: Boolean) = Unit

  override fun setAbLoop(a: Double?, b: Double?) = Unit

  override fun setVideoAspect(aspect: AspectMode) = Unit

  override fun setVideoZoom(zoom: Double) = Unit

  override fun frameStep(forward: Boolean) = Unit

  override fun screenshot(directory: String, includeSubtitles: Boolean) = false

  override fun setProperty(name: String, value: String) = Unit

  override fun getProperty(name: String): String? = null

  override fun command(vararg args: String) = Unit

  override fun release() = Unit
}
