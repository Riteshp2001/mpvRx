package app.gyrolet.mpvrx.ui.browser

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen

import kotlinx.serialization.Serializable

@Serializable
object MainScreen : Screen {
  private enum class MainTab {
    HOME,
    RECENTS,
    PLAYLISTS,
    NETWORK,
  }

  // Use a companion object to store state more persistently
  private var persistentSelectedTab: MainTab = MainTab.HOME
  
  /**
   * Update selection state and navigation bar visibility
   * This method should be called whenever selection changes
   */
  fun updateSelectionState(
    isInSelectionMode: Boolean,
    isOnlyVideosSelected: Boolean,
    selectionManager: Any?,
  ) {
    NavigationBarState.updateSelectionState(
      inSelectionMode = isInSelectionMode,
      onlyVideos = isOnlyVideosSelected,
    )
  }
  
  /**
   * Update permission state to control FAB visibility
   */
  fun updatePermissionState(isDenied: Boolean) {
    NavigationBarState.updatePermissionState(isDenied)
  }

  /**
   * Get current permission denied state
   */
  fun getPermissionDeniedState(): Boolean = NavigationBarState.isPermissionDenied

  /**
   * Update bottom navigation bar visibility based on floating bottom bar state
   */
  fun updateBottomBarVisibility(shouldShow: Boolean) {
    NavigationBarState.updateBottomBarVisibility(shouldShow)
  }

  @Composable
  @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
  override fun Content() {
    var selectedTab by remember {
      mutableStateOf(persistentSelectedTab)
    }

    val density = LocalDensity.current
    val appearancePreferences = koinInject<AppearancePreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val navAnimStyle by playerPreferences.navAnimStyle.collectAsState()
    val animSpeed    by playerPreferences.animationSpeed.collectAsState()
    val showHomeTab by appearancePreferences.showHomeTab.collectAsState()
    val showRecentsTab by appearancePreferences.showRecentsTab.collectAsState()
    val showPlaylistsTab by appearancePreferences.showPlaylistsTab.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    
    val visibleTabs = remember(
      showHomeTab,
      showRecentsTab,
      showPlaylistsTab,
      showNetworkTab,
    ) {
      buildList {
        if (showHomeTab) add(MainTab.HOME)
        if (showRecentsTab) add(MainTab.RECENTS)
        if (showPlaylistsTab) add(MainTab.PLAYLISTS)
        if (showNetworkTab) add(MainTab.NETWORK)
      }
    }
    
    LaunchedEffect(selectedTab) {
      android.util.Log.d("MainScreen", "selectedTab changed to: $selectedTab (was ${persistentSelectedTab})")
      persistentSelectedTab = selectedTab
    }

    LaunchedEffect(visibleTabs) {
      if (visibleTabs.isEmpty()) {
        selectedTab = MainTab.HOME
      } else if (!visibleTabs.contains(selectedTab)) {
        selectedTab = visibleTabs.first()
      }
    }

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      bottomBar = {
        // Animated bottom navigation bar with slide animations
        AnimatedVisibility(
          visible = !hideNavigationBar && visibleTabs.isNotEmpty() && !isPermissionDenied,
          enter = slideInVertically(
            animationSpec = tween(durationMillis = 300),
            initialOffsetY = { fullHeight -> fullHeight }
          ),
          exit = slideOutVertically(
            animationSpec = tween(durationMillis = 300),
            targetOffsetY = { fullHeight -> fullHeight }
          )
        ) {
          NavigationBar(
            modifier = Modifier
              .clip(
                RoundedCornerShape(
                  topStart = 28.dp,
                  topEnd = 28.dp,
                  bottomStart = 0.dp,
                  bottomEnd = 0.dp
                )
              )
          ) {
            visibleTabs.forEach { tab ->
              NavigationBarItem(
                icon = {
                  when (tab) {
                    MainTab.HOME -> Icon(Icons.Filled.Home, contentDescription = "Home")
                    MainTab.RECENTS -> Icon(Icons.Filled.History, contentDescription = "Recents")
                    MainTab.PLAYLISTS -> Icon(Icons.Filled.PlaylistPlay, contentDescription = "Playlists")
                    MainTab.NETWORK -> Icon(Icons.Filled.BringYourOwnIp, contentDescription = "Network")
                  }
                },
                label = {
                  Text(
                    when (tab) {
                      MainTab.HOME -> "Home"
                      MainTab.RECENTS -> "Recents"
                      MainTab.PLAYLISTS -> "Playlists"
                      MainTab.NETWORK -> "Network"
                    }
                  )
                },
                selected = selectedTab == tab,
                onClick = { selectedTab = tab },
              )
            }
          }
        }
      }
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        // Always use 80dp bottom padding regardless of navigation bar visibility
        val fabBottomPadding = 80.dp

        AnimatedContent(
          targetState = selectedTab,
          transitionSpec = {
            val initialIndex = visibleTabs.indexOf(initialState)
            val targetIndex = visibleTabs.indexOf(targetState)
            buildNavTransition(
              forward = targetIndex >= initialIndex,
              style   = navAnimStyle,
              speed   = animSpeed,
              density = density,
            )
          },
          label = "tab_animation"
        ) { targetTab ->
          CompositionLocalProvider(
            LocalNavigationBarHeight provides fabBottomPadding
          ) {
            val effectiveTab = if (visibleTabs.isEmpty()) MainTab.HOME else targetTab
            when (effectiveTab) {
              MainTab.HOME -> FolderListScreen.Content()
              MainTab.RECENTS -> RecentlyPlayedScreen.Content()
              MainTab.PLAYLISTS -> PlaylistScreen.Content()
              MainTab.NETWORK -> NetworkStreamingScreen.Content()
            }
          }
        }
      }
    }
  }
}

// CompositionLocal for navigation bar height
val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

/** Builds the [ContentTransform] for tab navigation based on the selected style. */
fun buildNavTransition(
  forward: Boolean,
  style: NavigationAnimStyle,
  speed: Float,
  density: androidx.compose.ui.unit.Density,
): ContentTransform {
  val dir  = if (forward) 1 else -1
  val dur  = (250 * speed).toInt().coerceAtLeast(60)
  val half = (dur / 2).coerceAtLeast(30)

  return when (style) {
    NavigationAnimStyle.None ->
      (fadeIn(tween(1)) togetherWith fadeOut(tween(1)))

    NavigationAnimStyle.Minimal ->
      (fadeIn(tween(dur)) togetherWith fadeOut(tween(half)))

    NavigationAnimStyle.FlipFade ->
      (scaleIn(tween(dur), initialScale = 0.94f) + fadeIn(tween(dur))) togetherWith
        (scaleOut(tween(half), targetScale = 1.06f) + fadeOut(tween(half)))

    NavigationAnimStyle.Depth ->
      // New screen slides in fully; old screen scales back slightly (parallax)
      (slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { it * dir } +
        fadeIn(tween(dur))) togetherWith
        (slideOutHorizontally(tween(half, easing = FastOutSlowInEasing)) { (-it * 0.25f * dir).toInt() } +
          scaleOut(tween(half), targetScale = 0.92f) +
          fadeOut(tween(half)))

    NavigationAnimStyle.Elastic ->
      (slideInHorizontally(
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
      ) { it * dir } + fadeIn(tween(80))) togetherWith
        (slideOutHorizontally(tween(half)) { (-it / 3 * dir) } + fadeOut(tween(half)))

    NavigationAnimStyle.Default -> {
      val slidePx = with(density) { 48.dp.roundToPx() }
      if (forward) {
        (slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { slidePx } +
          fadeIn(tween(dur, easing = FastOutSlowInEasing))) togetherWith
          (slideOutHorizontally(tween(dur, easing = FastOutSlowInEasing)) { -slidePx } +
            fadeOut(tween(half, easing = FastOutSlowInEasing)))
      } else {
        (slideInHorizontally(tween(dur, easing = FastOutSlowInEasing)) { -slidePx } +
          fadeIn(tween(dur, easing = FastOutSlowInEasing))) togetherWith
          (slideOutHorizontally(tween(dur, easing = FastOutSlowInEasing)) { slidePx } +
            fadeOut(tween(half, easing = FastOutSlowInEasing)))
      }
    }
  }
}



