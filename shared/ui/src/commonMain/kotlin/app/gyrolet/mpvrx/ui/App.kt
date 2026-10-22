package app.gyrolet.mpvrx.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.browser.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.HistoryScreen
import app.gyrolet.mpvrx.ui.browser.LibraryViewModel
import app.gyrolet.mpvrx.ui.browser.MusicScreen
import app.gyrolet.mpvrx.ui.browser.NetworkScreen
import app.gyrolet.mpvrx.ui.browser.VideoListScreen
import app.gyrolet.mpvrx.ui.components.ExpressivePillNavigationBar
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.MainTab
import app.gyrolet.mpvrx.ui.navigation.Navigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.AppDependencies
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.player.PlayerScreen
import app.gyrolet.mpvrx.ui.preferences.ProfileScreen
import app.gyrolet.mpvrx.ui.preferences.SettingsPageScreen
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import app.gyrolet.mpvrx.ui.theme.PlayerTheme
import app.gyrolet.mpvrx.ui.util.collectAsState

/** Root composable shared by every platform shell. */
@Composable
fun MpvrxApp(
  deps: AppDependencies,
  navigator: Navigator = remember { Navigator() },
) {
  val scope = rememberCoroutineScope()
  val library = remember { LibraryViewModel(deps, scope) }
  LaunchedEffect(Unit) { library.refresh() }

  CompositionLocalProvider(
    LocalAppDependencies provides deps,
    LocalNavigator provides navigator,
  ) {
    MpvrxTheme(deps.settings.appearance) {
      val miniPlayer by deps.platform.isMiniPlayer.collectAsState()
      val colors = MaterialTheme.colorScheme
      val dark = colors.background.luminance() < 0.5f
      LaunchedEffect(colors.background, colors.onBackground) {
        deps.platform.applyWindowColors(colors.background, colors.onBackground, dark)
      }
      Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (miniPlayer) {
          CompactPlayer()
        } else {
          AnimatedContent(
            targetState = navigator.current,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { screen -> if (screen is Screen.Player) "player" else screen.toString() },
            label = "screen",
          ) { screen ->
            when (screen) {
              Screen.Main -> MainScreen(library)
              is Screen.FolderVideos -> WithMiniBar { VideoListScreen(screen.folder) }
              Screen.Player -> PlayerScreen()
              is Screen.Settings -> WithMiniBar { SettingsPageScreen(screen.page, library) }
              Screen.History -> WithMiniBar { HistoryScreen() }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MainScreen(library: LibraryViewModel) {
  val deps = LocalAppDependencies.current
  val showMusic by deps.settings.appearance.showMusicTab.collectAsState()
  val showNetwork by deps.settings.appearance.showNetworkTab.collectAsState()
  var selected by rememberSaveable { mutableStateOf(MainTab.Home) }
  val tabs =
    MainTab.entries.filter {
      when (it) {
        MainTab.Music -> showMusic
        MainTab.Network -> showNetwork
        else -> true
      }
    }
  val bottomPadding = PaddingValues(bottom = 96.dp)
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().widthIn(max = 1600.dp).align(Alignment.TopCenter)) {
      when (selected) {
        MainTab.Home -> FolderListScreen(library, bottomPadding)
        MainTab.Music -> MusicScreen(library, bottomPadding)
        MainTab.Network -> NetworkScreen(bottomPadding)
        MainTab.Profile -> ProfileScreen(bottomPadding)
      }
    }
    Column(
      Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      NowPlayingBar(Modifier.padding(bottom = 12.dp))
      ExpressivePillNavigationBar(tabs = tabs, selected = selected, onSelect = { selected = it })
    }
  }
}

@Composable
private fun WithMiniBar(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize()) {
    content()
    NowPlayingBar(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp))
  }
}

/** Floating "now playing" pill shown while audio keeps playing outside the player. */
@Composable
private fun NowPlayingBar(modifier: Modifier = Modifier) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val active by deps.playback.sessionActive.collectAsState()
  val state by deps.playback.playerState.collectAsState()
  AnimatedVisibility(
    visible = active && !state.isIdle,
    enter = slideInVertically { it } + fadeIn(),
    exit = slideOutVertically { it } + fadeOut(),
    modifier = modifier,
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.widthIn(min = 360.dp, max = 560.dp).shadow(10.dp, RoundedCornerShape(28.dp), clip = false),
    ) {
      Column {
        Row(
          Modifier.clickable { navigator.push(Screen.Player) }.padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
          ) { Icon(if (state.isAudioOnly) Icons.MusicNote else Icons.Movie, null, tint = MaterialTheme.colorScheme.onPrimary) }
          Spacer(Modifier.width(12.dp))
          Text(
            state.mediaTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = { deps.playback.previous() }) { Icon(Icons.SkipPrevious, "Previous") }
          IconButton(onClick = { deps.playback.togglePlayPause() }) { Icon(if (state.isPaused) Icons.PlayArrow else Icons.Pause, "Play/pause") }
          IconButton(onClick = { deps.playback.next() }) { Icon(Icons.SkipNext, "Next") }
          IconButton(onClick = { deps.playback.stop() }) { Icon(Icons.Close, "Stop") }
        }
        LinearProgressIndicator(
          progress = { state.progress },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp),
          drawStopIndicator = {},
        )
      }
    }
  }
}

/** Picture-in-picture style compact window: video plus hover controls. */
@Composable
private fun CompactPlayer() {
  val deps = LocalAppDependencies.current
  val state by deps.playback.playerState.collectAsState()
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  PlayerTheme {
    Box(Modifier.fillMaxSize().background(Color.Black).hoverable(interaction)) {
      deps.videoSurface(Modifier.fillMaxSize())
      AnimatedVisibility(hovered || state.isPaused, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
          Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
              Triple(Icons.SkipPrevious, "Previous") { deps.playback.previous() },
              Triple(if (state.isPaused || state.isIdle) Icons.PlayArrow else Icons.Pause, "Play/pause") { deps.playback.togglePlayPause() },
              Triple(Icons.SkipNext, "Next") { deps.playback.next() },
            ).forEach { (icon, label, action) ->
              Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)).clickable(onClick = action),
                contentAlignment = Alignment.Center,
              ) { Icon(icon, label, tint = Color.White) }
            }
          }
          IconButton(onClick = { deps.platform.setMiniPlayer(false) }, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(Icons.FitScreen, "Exit picture-in-picture", tint = Color.White)
          }
          LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            drawStopIndicator = {},
            gapSize = 0.dp,
          )
        }
      }
    }
  }
}
