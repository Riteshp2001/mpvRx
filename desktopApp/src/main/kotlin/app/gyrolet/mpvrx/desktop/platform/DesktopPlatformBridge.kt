package app.gyrolet.mpvrx.desktop.platform

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import app.gyrolet.mpvrx.core.media.MediaExtensions
import app.gyrolet.mpvrx.core.platform.AppDirectories
import app.gyrolet.mpvrx.core.platform.DesktopOs
import app.gyrolet.mpvrx.core.platform.currentDesktopOs
import app.gyrolet.mpvrx.ui.platform.PlatformBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager

class DesktopPlatformBridge(
  private val windowState: WindowState,
  override val appVersion: String,
  override val nativeEngineStatus: String,
) : PlatformBridge {
  var window: ComposeWindow? = null

  override val platformName: String =
    when (currentDesktopOs) {
      DesktopOs.Windows -> "Windows"
      DesktopOs.MacOs -> "macOS"
      DesktopOs.Linux -> "Linux"
    }

  override val isDesktop = true

  private val fullscreen = MutableStateFlow(false)
  override val isFullscreen: StateFlow<Boolean> = fullscreen.asStateFlow()

  private val alwaysOnTop = MutableStateFlow(false)
  override val isAlwaysOnTop: StateFlow<Boolean> = alwaysOnTop.asStateFlow()

  private val miniPlayer = MutableStateFlow(false)
  override val isMiniPlayer: StateFlow<Boolean> = miniPlayer.asStateFlow()

  private var restoreSize: DpSize? = null
  private var restorePosition: WindowPosition? = null
  private var restorePlacement: WindowPlacement = WindowPlacement.Floating

  override val screenshotDirectory: String get() = AppDirectories.screenshots.absolutePath

  override val hiddenPointerIcon: PointerIcon =
    PointerIcon(
      Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "hidden"),
    )

  override fun setFullscreen(enabled: Boolean) {
    if (enabled && miniPlayer.value) setMiniPlayer(false)
    if (enabled == fullscreen.value) return
    if (enabled) restorePlacement = windowState.placement
    windowState.placement = if (enabled) WindowPlacement.Fullscreen else restorePlacement
    fullscreen.value = enabled
  }

  override fun setAlwaysOnTop(enabled: Boolean) {
    window?.isAlwaysOnTop = enabled
    alwaysOnTop.value = enabled
  }

  override fun setMiniPlayer(enabled: Boolean) {
    if (enabled == miniPlayer.value) return
    if (enabled) {
      if (fullscreen.value) setFullscreen(false)
      restoreSize = windowState.size
      restorePosition = windowState.position
      restorePlacement = windowState.placement
      windowState.placement = WindowPlacement.Floating
      val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
      val scale = window?.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
      val size = DpSize(480.dp, 270.dp)
      windowState.size = size
      windowState.position =
        WindowPosition(
          ((bounds.x + bounds.width) / scale - size.width.value - 24).dp,
          ((bounds.y + bounds.height) / scale - size.height.value - 24).dp,
        )
      setAlwaysOnTop(true)
    } else {
      setAlwaysOnTop(false)
      windowState.placement = restorePlacement
      restoreSize?.let { windowState.size = it }
      restorePosition?.let { windowState.position = it }
    }
    miniPlayer.value = enabled
  }

  override suspend fun pickMediaFiles(): List<String> =
    withContext(Dispatchers.Main) {
      val dialog = FileDialog(window, "Open media", FileDialog.LOAD)
      dialog.isMultipleMode = true
      dialog.setFilenameFilter { _, name -> MediaExtensions.isMedia(name) || name.substringAfterLast('.').lowercase() in MediaExtensions.playlist }
      if (currentDesktopOs == DesktopOs.Windows) {
        dialog.file = (MediaExtensions.video + MediaExtensions.audio + MediaExtensions.playlist).joinToString(";") { "*.$it" }
      }
      dialog.isVisible = true
      dialog.files.orEmpty().map(File::getAbsolutePath)
    }

  override suspend fun pickSubtitle(): String? =
    withContext(Dispatchers.Main) {
      val dialog = FileDialog(window, "Load subtitle", FileDialog.LOAD)
      dialog.setFilenameFilter { _, name -> MediaExtensions.isSubtitle(name) }
      if (currentDesktopOs == DesktopOs.Windows) dialog.file = MediaExtensions.subtitle.joinToString(";") { "*.$it" }
      dialog.isVisible = true
      dialog.files.firstOrNull()?.absolutePath
    }

  override suspend fun pickFolder(): String? =
    withContext(Dispatchers.Main) {
      if (currentDesktopOs == DesktopOs.MacOs) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = FileDialog(window, "Choose folder", FileDialog.LOAD)
        dialog.isVisible = true
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        return@withContext dialog.files.firstOrNull()?.absolutePath
      }
      val previousLaf = UIManager.getLookAndFeel()
      runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
      try {
        val chooser = JFileChooser().apply {
          fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
          dialogTitle = "Choose a folder to add to the library"
          isAcceptAllFileFilterUsed = false
        }
        if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
      } finally {
        runCatching { UIManager.setLookAndFeel(previousLaf) }
      }
    }

  override fun revealInFileManager(path: String) {
    val file = File(path)
    runCatching {
      when (currentDesktopOs) {
        DesktopOs.Windows ->
          if (file.isDirectory) {
            ProcessBuilder("explorer.exe", file.absolutePath).start()
          } else {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
          }
        DesktopOs.MacOs -> ProcessBuilder("open", "-R", file.absolutePath).start()
        DesktopOs.Linux -> ProcessBuilder("xdg-open", (if (file.isDirectory) file else file.parentFile).absolutePath).start()
      }
    }
  }

  override fun openUrl(url: String) {
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    if (uri.scheme !in setOf("http", "https")) return
    runCatching { Desktop.getDesktop().browse(uri) }
  }

  override fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
  }

  override fun openConfigFolder() = revealInFileManager(AppDirectories.mpvConfig.absolutePath)

  override fun applyWindowColors(background: Color, content: Color, dark: Boolean) {
    val handle = window?.windowHandle ?: return
    WindowsIntegration.applyCaption(handle, dark, background, content)
  }
}
