/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.browser

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object MainScreen : Screen {
  internal enum class MainTab {
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
      TelegramPillNavigationBar(
        visibleTabs = visibleTabs,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        modifier = modifier
      )
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
    val targetNavBarWidth = (screenWidth - 64.dp).coerceAtMost(320.dp)

    val targetOffsetFraction = if (isDualPaneFolderSelected) 0.2f else 0.5f

    val animatedOffsetFraction by animateFloatAsState(
      targetValue = targetOffsetFraction,
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
      ),
      label = "nav_bar_position"
    )

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
        val fabBottomPadding = 88.dp

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
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomStart)
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
        ) {
          BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val containerWidth = maxWidth
            val targetCenter = containerWidth * animatedOffsetFraction
            val leftPadding = (targetCenter - (navBarWidth / 2)).coerceAtLeast(0.dp)

            Box(
              modifier = Modifier
                .padding(start = leftPadding)
                .width(navBarWidth),
              contentAlignment = Alignment.Center
            ) {
              TelegramPillNavigationBar(
                visibleTabs = visibleTabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.fillMaxWidth()
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TelegramPillNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectedIndex = remember(selectedTab, visibleTabs) {
    visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
  }

  val smoothSpring = remember {
    spring<Float>(
      dampingRatio = 0.82f,
      stiffness = 300f
    )
  }

  val animatedIndex by animateFloatAsState(
    targetValue = selectedIndex.toFloat(),
    animationSpec = smoothSpring,
    label = "pill_slide"
  )

  val density = LocalDensity.current

  BoxWithConstraints(modifier = modifier) {
    val totalWidth = maxWidth
    val count = visibleTabs.size.coerceAtLeast(1)
    val horizontalPadding = 6.dp
    val availableWidth = totalWidth - (horizontalPadding * 2)
    val itemWidth = availableWidth / count
    val itemWidthPx = with(density) { itemWidth.toPx() }

    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
      border = BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
      )
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = horizontalPadding, vertical = 6.dp)
      ) {
        // Hardware accelerated sliding active pill background
        if (visibleTabs.isNotEmpty()) {
          Box(
            modifier = Modifier
              .width(itemWidth)
              .height(56.dp)
              .graphicsLayer {
                translationX = itemWidthPx * animatedIndex
              }
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer)
          )
        }

        // Tab Items Layer
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          visibleTabs.forEach { tab ->
            val selected = selectedTab == tab

            val contentColor by animateColorAsState(
              targetValue = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
              animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
              label = "pill_fg"
            )

            val iconScale by animateFloatAsState(
              targetValue = if (selected) 1.10f else 1.0f,
              animationSpec = smoothSpring,
              label = "icon_scale"
            )

            Box(
              modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .clip(CircleShape)
                .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = { onTabSelected(tab) }
                ),
              contentAlignment = Alignment.Center
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                Box(
                  modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                  },
                  contentAlignment = Alignment.Center
                ) {
                  when (tab) {
                    MainScreen.MainTab.HOME -> Icon(
                      Icons.RoundedFilled.Home,
                      contentDescription = stringResource(R.string.ui_home),
                      tint = contentColor,
                      modifier = Modifier.size(22.dp)
                    )
                    MainScreen.MainTab.RECENTS -> Icon(
                      Icons.RoundedFilled.History,
                      contentDescription = stringResource(R.string.ui_recents),
                      tint = contentColor,
                      modifier = Modifier.size(22.dp)
                    )
                    MainScreen.MainTab.PLAYLISTS -> Icon(
                      Icons.RoundedFilled.PlaylistPlay,
                      contentDescription = stringResource(R.string.ui_playlists),
                      tint = contentColor,
                      modifier = Modifier.size(22.dp)
                    )
                    MainScreen.MainTab.NETWORK -> Icon(
                      Icons.RoundedFilled.BringYourOwnIp,
                      contentDescription = stringResource(R.string.ui_network),
                      tint = contentColor,
                      modifier = Modifier.size(22.dp)
                    )
                  }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                  text = when (tab) {
                    MainScreen.MainTab.HOME -> "Home"
                    MainScreen.MainTab.RECENTS -> "Recents"
                    MainScreen.MainTab.PLAYLISTS -> "Playlists"
                    MainScreen.MainTab.NETWORK -> "Network"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                  color = contentColor,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center
                )
              }
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
