package app.gyrolet.mpvrx.ui.browser

import androidx.compose.animation.core.Spring

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    
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

    val mainNavBar = @Composable { modifier: Modifier ->
      NavigationBar(
        modifier = modifier.clip(AppShapeScale.extraLargeIncreased)
      ) {
        visibleTabs.forEach { tab ->
          NavigationBarItem(
            icon = {
              when (tab) {
                MainTab.HOME -> Icon(Icons.RoundedFilled.Home, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_home))
                MainTab.RECENTS -> Icon(Icons.RoundedFilled.History, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_recents))
                MainTab.PLAYLISTS -> Icon(Icons.RoundedFilled.PlaylistPlay, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_playlists))
                MainTab.NETWORK -> Icon(Icons.RoundedFilled.BringYourOwnIp, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_network))
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

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val targetNavBarWidth = if (isDualPaneFolderSelected) screenWidth * 0.4f else screenWidth

    val navBarWidth by animateDpAsState(
      targetValue = targetNavBarWidth,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      ),
      label = "nav_bar_width"
    )

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
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
            LocalNavigationBarHeight provides fabBottomPadding,
            LocalMainNavigationBar provides mainNavBar
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

        // Animated bottom navigation bar with slide animations
        AnimatedVisibility(
          visible = !hideNavigationBar && visibleTabs.isNotEmpty() && !isPermissionDenied,
          enter = slideInVertically(
            animationSpec = spring(
              dampingRatio = AppMotion.Spatial.ExpressiveDp.dampingRatio,
              stiffness = AppMotion.Spatial.ExpressiveDp.stiffness,
            ),
            initialOffsetY = { fullHeight -> fullHeight }
          ),
          exit = slideOutVertically(
            animationSpec = spring(
              dampingRatio = AppMotion.Spatial.StandardDp.dampingRatio,
              stiffness = AppMotion.Spatial.StandardDp.stiffness,
            ),
            targetOffsetY = { fullHeight -> fullHeight }
          ),
          modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
        ) {
          NavigationBar(
            modifier = Modifier
              .width(navBarWidth)
              .clip(AppShapeScale.extraLargeIncreased)
          ) {
            visibleTabs.forEach { tab ->
              NavigationBarItem(
                icon = {
                  when (tab) {
                    MainTab.HOME -> Icon(Icons.RoundedFilled.Home, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_home))
                    MainTab.RECENTS -> Icon(Icons.RoundedFilled.History, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_recents))
                    MainTab.PLAYLISTS -> Icon(Icons.RoundedFilled.PlaylistPlay, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_playlists))
                    MainTab.NETWORK -> Icon(Icons.RoundedFilled.BringYourOwnIp, contentDescription = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_network))
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
    }
  }
}

val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

// CompositionLocal for main navigation bar
val LocalMainNavigationBar = compositionLocalOf<@Composable (Modifier) -> Unit> {
  { }
}

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
      (fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness)) togetherWith fadeOut(spring(stiffness = AppMotion.Spatial.Snappy.stiffness)))

    NavigationAnimStyle.Minimal ->
      (fadeIn(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) togetherWith fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.FlipFade ->
      (scaleIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness), initialScale = 0.94f) + fadeIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness))) togetherWith
        (scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 1.06f) + fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Depth ->
      (slideInHorizontally(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) { it * dir } +
        fadeIn(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness))) togetherWith
        (slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it * 0.25f * dir).toInt() } +
          scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 0.92f) +
          fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Elastic ->
      (slideInHorizontally(
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
      ) { it * dir } + fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness))) togetherWith
        (slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it / 3 * dir) } + fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness)))

    NavigationAnimStyle.Default -> {
      val slidePx = with(density) { 48.dp.roundToPx() }
      if (forward) {
        (slideInHorizontally(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness)) { slidePx } +
          fadeIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness))) togetherWith
          (slideOutHorizontally(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) { -slidePx } +
            fadeOut(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)))
      } else {
        (slideInHorizontally(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness)) { -slidePx } +
          fadeIn(spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness))) togetherWith
          (slideOutHorizontally(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)) { slidePx } +
            fadeOut(spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness)))
      }
    }
  }
}



