package app.gyrolet.mpvrx.core.platform

import java.io.File

enum class DesktopOs { Windows, Linux, MacOs }

val currentDesktopOs: DesktopOs by lazy {
  val name = System.getProperty("os.name").orEmpty().lowercase()
  when {
    name.startsWith("windows") -> DesktopOs.Windows
    name.contains("mac") || name.contains("darwin") -> DesktopOs.MacOs
    else -> DesktopOs.Linux
  }
}

/** Per-OS user data locations following each platform's conventions. */
object AppDirectories {
  private const val APP_NAME = "mpvRx"

  val config: File by lazy {
    val home = System.getProperty("user.home")
    when (currentDesktopOs) {
      DesktopOs.Windows -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", APP_NAME)
      DesktopOs.MacOs -> File(home, "Library/Application Support/$APP_NAME")
      DesktopOs.Linux -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", APP_NAME)
    }.also(File::mkdirs)
  }

  val cache: File by lazy {
    val home = System.getProperty("user.home")
    when (currentDesktopOs) {
      DesktopOs.Windows -> File(System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local", "$APP_NAME\\Cache")
      DesktopOs.MacOs -> File(home, "Library/Caches/$APP_NAME")
      DesktopOs.Linux -> File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", APP_NAME)
    }.also(File::mkdirs)
  }

  val thumbnails: File by lazy { File(cache, "thumbnails").also(File::mkdirs) }

  val screenshots: File by lazy {
    val pictures = File(System.getProperty("user.home"), "Pictures")
    File(if (pictures.isDirectory) pictures else config, "mpvRx").also(File::mkdirs)
  }

  /** mpv's own config dir (mpv.conf, input.conf, scripts, shaders) kept with app config. */
  val mpvConfig: File by lazy { File(config, "mpv").also(File::mkdirs) }
}
