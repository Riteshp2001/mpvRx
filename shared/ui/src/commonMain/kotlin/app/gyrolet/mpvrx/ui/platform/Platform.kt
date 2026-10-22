package app.gyrolet.mpvrx.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import app.gyrolet.mpvrx.core.history.PlaybackHistoryRepository
import app.gyrolet.mpvrx.core.media.MediaMetadata
import app.gyrolet.mpvrx.core.media.MediaScanner
import app.gyrolet.mpvrx.core.player.PlaybackController
import app.gyrolet.mpvrx.core.player.PlayerEngine
import app.gyrolet.mpvrx.core.preferences.AppSettings
import kotlinx.coroutines.flow.StateFlow

/** Window / OS integration each platform shell provides (Windows today, Linux/macOS/iOS later). */
interface PlatformBridge {
  val platformName: String

  /** True when the platform has a resizable window (desktop) rather than a fixed screen (mobile). */
  val isDesktop: Boolean

  val isFullscreen: StateFlow<Boolean>

  fun setFullscreen(enabled: Boolean)

  val isAlwaysOnTop: StateFlow<Boolean>

  fun setAlwaysOnTop(enabled: Boolean)

  /** Compact always-on-top window, the desktop counterpart of Android picture-in-picture. */
  val isMiniPlayer: StateFlow<Boolean>

  fun setMiniPlayer(enabled: Boolean)

  suspend fun pickMediaFiles(): List<String>

  suspend fun pickFolder(): String?

  suspend fun pickSubtitle(): String?

  fun revealInFileManager(path: String)

  fun openUrl(url: String)

  fun copyToClipboard(text: String)

  /** Opens the folder where mpv config, scripts and shaders live. */
  fun openConfigFolder()

  val screenshotDirectory: String

  val appVersion: String

  val nativeEngineStatus: String

  /** Invisible pointer used while player controls are hidden; null where pointers do not exist. */
  val hiddenPointerIcon: PointerIcon?

  /** Tints native window chrome (title bar) to match the active theme. */
  fun applyWindowColors(background: Color, content: Color, dark: Boolean) {}
}

/** Thumbnails and probe metadata come from the native player, so platforms supply them. */
interface MediaInfoProvider {
  suspend fun thumbnail(path: String, widthPx: Int): ImageBitmap?

  suspend fun metadata(path: String): MediaMetadata?

  fun cachedMetadata(path: String): MediaMetadata?
}

class AppDependencies(
  val settings: AppSettings,
  val scanner: MediaScanner,
  val history: PlaybackHistoryRepository,
  val engine: PlayerEngine,
  val playback: PlaybackController,
  val mediaInfo: MediaInfoProvider,
  val platform: PlatformBridge,
  /** Native video output for [engine], composed under the player controls. */
  val videoSurface: @Composable (Modifier) -> Unit,
)

val LocalAppDependencies = staticCompositionLocalOf<AppDependencies> { error("AppDependencies not provided") }
