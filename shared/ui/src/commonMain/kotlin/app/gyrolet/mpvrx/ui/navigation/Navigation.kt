package app.gyrolet.mpvrx.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import app.gyrolet.mpvrx.core.media.MediaFolder

sealed interface Screen {
  data object Main : Screen

  data class FolderVideos(val folder: MediaFolder) : Screen

  data object Player : Screen

  data class Settings(val page: SettingsPage) : Screen

  data object History : Screen
}

enum class MainTab(val label: String) {
  Home("Home"),
  Music("Music"),
  Network("Network"),
  Profile("Profile"),
}

enum class SettingsPage(val title: String) {
  Appearance("Appearance"),
  Player("Player"),
  Decoder("Decoder"),
  Subtitles("Subtitles"),
  Audio("Audio"),
  Library("Library folders"),
  Advanced("Advanced"),
  Shortcuts("Keyboard shortcuts"),
  About("About"),
}

/** Minimal multiplatform back stack; no Android navigation dependency so iOS/desktop share it. */
@Stable
class Navigator {
  val backStack = mutableStateListOf<Screen>(Screen.Main)
  val current: Screen get() = backStack.last()

  fun push(screen: Screen) {
    if (screen == Screen.Player && current == Screen.Player) return
    backStack.add(screen)
  }

  fun pop(): Boolean {
    if (backStack.size <= 1) return false
    backStack.removeAt(backStack.lastIndex)
    return true
  }

  fun popPlayer() {
    backStack.removeAll { it == Screen.Player }
    if (backStack.isEmpty()) backStack.add(Screen.Main)
  }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator not provided") }
