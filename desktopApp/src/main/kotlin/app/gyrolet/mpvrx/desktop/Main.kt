package app.gyrolet.mpvrx.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.gyrolet.mpvrx.core.history.PlaybackHistoryRepository
import app.gyrolet.mpvrx.core.media.MediaExtensions
import app.gyrolet.mpvrx.core.platform.AppDirectories
import app.gyrolet.mpvrx.core.platform.FileSystemMediaScanner
import app.gyrolet.mpvrx.core.platform.FileTextStorage
import app.gyrolet.mpvrx.core.platform.JsonFileKeyValueBackend
import app.gyrolet.mpvrx.core.player.MpvSettingsBinder
import app.gyrolet.mpvrx.core.player.PlaybackController
import app.gyrolet.mpvrx.core.player.PlayerEngine
import app.gyrolet.mpvrx.core.preferences.AppSettings
import app.gyrolet.mpvrx.core.preferences.KeyValuePreferenceStore
import app.gyrolet.mpvrx.desktop.mpv.LibMpv
import app.gyrolet.mpvrx.desktop.mpv.MpvEngineConfig
import app.gyrolet.mpvrx.desktop.mpv.MpvMediaInfoProvider
import app.gyrolet.mpvrx.desktop.mpv.MpvPlayerEngine
import app.gyrolet.mpvrx.desktop.mpv.MpvVideoSurface
import app.gyrolet.mpvrx.desktop.mpv.UnavailablePlayerEngine
import app.gyrolet.mpvrx.desktop.platform.DesktopPlatformBridge
import app.gyrolet.mpvrx.desktop.platform.WindowsIntegration
import app.gyrolet.mpvrx.ui.MpvrxApp
import app.gyrolet.mpvrx.ui.navigation.Navigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.AppDependencies
import app.gyrolet.mpvrx.ui.resources.Res
import app.gyrolet.mpvrx.ui.resources.app_icon
import app.gyrolet.mpvrx.ui.theme.LocalPlatformAccentColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.resources.painterResource
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main(args: Array<String>) {
  val configStorage = FileTextStorage(AppDirectories.config)
  val cacheStorage = FileTextStorage(AppDirectories.cache)
  val settings = AppSettings(KeyValuePreferenceStore(JsonFileKeyValueBackend(configStorage)))
  val history = PlaybackHistoryRepository(configStorage)
  val scanner = FileSystemMediaScanner()
  val appVersion = System.getProperty("mpvrx.version") ?: "dev"

  val loaded = LibMpv.load()
  val engine: PlayerEngine =
    loaded
      .mapCatching { lib ->
        MpvPlayerEngine(
          lib.api,
          MpvEngineConfig(
            configDir = AppDirectories.mpvConfig,
            screenshotDir = AppDirectories.screenshots,
            hardwareDecoding = settings.decoder.tryHWDecoding.get(),
            extraOptions = settings.advanced.mpvConf.get(),
            logFile = if (settings.advanced.verboseLogging.get()) AppDirectories.config.resolve("mpv.log") else null,
            ytdlPath =
              File(lib.source).parentFile
                ?.listFiles { file -> file.name.lowercase() in setOf("yt-dlp.exe", "yt-dlp") }
                ?.firstOrNull(),
          ),
        )
      }.getOrElse { UnavailablePlayerEngine(it.message ?: "libmpv could not be initialised") }
  val engineStatus =
    loaded.fold(
      onSuccess = { "libmpv API ${it.api.mpv_client_api_version().let { v -> "${v shr 16}.${v and 0xFFFF}" }} · ${it.source}" },
      onFailure = { "Unavailable — ${it.message?.lineSequence()?.firstOrNull()}" },
    )

  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  val playback = PlaybackController(engine, settings, history, scanner, appScope)
  MpvSettingsBinder(engine, settings, appScope, MpvPlayerEngine.HW_DECODING).bind()
  val mediaInfo = MpvMediaInfoProvider(loaded.getOrNull()?.api, AppDirectories.thumbnails, cacheStorage)
  val accent = WindowsIntegration.accentColor()
  val launchFiles = args.map(::File).filter { it.isFile && (MediaExtensions.isMedia(it.name) || it.extension.lowercase() in MediaExtensions.playlist) }
  val launchUrls = args.filter { it.contains("://") }

  application {
    val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition(Alignment.Center))
    val bridge = remember { DesktopPlatformBridge(windowState, appVersion, engineStatus) }
    val navigator = remember { Navigator() }
    val deps =
      remember {
        AppDependencies(
          settings = settings,
          scanner = scanner,
          history = history,
          engine = engine,
          playback = playback,
          mediaInfo = mediaInfo,
          platform = bridge,
          videoSurface = { modifier ->
            if (engine is MpvPlayerEngine) MpvVideoSurface(engine, modifier) else Box(modifier.background(Color.Black))
          },
        )
      }

    Window(
      onCloseRequest = {
        playback.saveCurrentPosition()
        engine.release()
        mediaInfo.release()
        exitApplication()
      },
      state = windowState,
      title = "mpvRx",
      icon = painterResource(Res.drawable.app_icon),
    ) {
      LaunchedEffect(window) {
        bridge.window = window
        window.minimumSize = java.awt.Dimension(420, 260)
        window.dropTarget =
          DropTarget(
            window,
            object : DropTargetAdapter() {
              override fun drop(event: DropTargetDropEvent) {
                event.acceptDrop(DnDConstants.ACTION_COPY)
                val dropped =
                  (event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    .orEmpty()
                    .filterIsInstance<File>()
                    .flatMap { file -> if (file.isDirectory) file.listFiles().orEmpty().sortedBy { it.name }.toList() else listOf(file) }
                    .filter { MediaExtensions.isMedia(it.name) }
                event.dropComplete(dropped.isNotEmpty())
                if (dropped.isNotEmpty()) {
                  playback.playPaths(dropped.map(File::getAbsolutePath))
                  navigator.push(Screen.Player)
                }
              }
            },
          )
        when {
          launchFiles.isNotEmpty() -> {
            playback.playPaths(launchFiles.map(File::getAbsolutePath))
            navigator.push(Screen.Player)
          }
          launchUrls.isNotEmpty() -> {
            playback.playUrl(launchUrls.first())
            navigator.push(Screen.Player)
          }
        }
      }
      CompositionLocalProvider(LocalPlatformAccentColor provides accent) {
        MpvrxApp(deps, navigator)
      }
    }
  }
}
