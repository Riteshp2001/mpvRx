/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryContent
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryContent
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object MainScreen : Screen {
  internal enum class MainTab {
    HOME,
    MUSIC,
    RECENTS,
    PLAYLISTS,
    NETWORK,
    JELLYFIN,
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

  @SuppressLint("ComposableNaming")
  @Composable
  override fun Content() {
    var selectedTab by remember {
      mutableStateOf(persistentSelectedTab)
    }

    val density = LocalDensity.current
    val appearancePreferences = koinInject<AppearancePreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val navAnimStyle by playerPreferences.navAnimStyle.collectAsState()
    val animSpeed by playerPreferences.animationSpeed.collectAsState()
    val showHomeTab by appearancePreferences.showHomeTab.collectAsState()
    val showMusicTab by appearancePreferences.showMusicTab.collectAsState()
    val showRecentsTab by appearancePreferences.showRecentsTab.collectAsState()
    val showPlaylistsTab by appearancePreferences.showPlaylistsTab.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val showJellyfinTab by appearancePreferences.showJellyfinTab.collectAsState()
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible

    val visibleTabs =
      remember(
        showHomeTab,
        showMusicTab,
        showRecentsTab,
        showPlaylistsTab,
        showNetworkTab,
        showJellyfinTab,
      ) {
        buildList {
          if (showHomeTab) add(MainTab.HOME)
          if (showMusicTab) add(MainTab.MUSIC)
          if (showRecentsTab) add(MainTab.RECENTS)
          if (showPlaylistsTab) add(MainTab.PLAYLISTS)
          if (showNetworkTab) add(MainTab.NETWORK)
          if (showJellyfinTab) add(MainTab.JELLYFIN)
        }
      }

    // Track whether the floating pill nav bar is on screen so the mini player can
    // sit at the very bottom when navigating to screens without it.
    DisposableEffect(Unit) {
      onDispose {
        NavigationBarState.isNavBarVisible = false
      }
    }
    SideEffect {
      NavigationBarState.isNavBarVisible = !hideNavigationBar && visibleTabs.isNotEmpty() && !isPermissionDenied
    }

    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
      initialPage = visibleTabs.indexOf(selectedTab).coerceAtLeast(0),
      pageCount = { visibleTabs.size },
    )

    // Sync pager → selectedTab when user swipes
    LaunchedEffect(pagerState) {
      snapshotFlow { pagerState.settledPage }
        .collect { page ->
          if (page in visibleTabs.indices) {
            selectedTab = visibleTabs[page]
          }
        }
    }

    val onTabSelected: (MainScreen.MainTab) -> Unit = { tab ->
      scope.launch {
        val page = visibleTabs.indexOf(tab)
        if (page >= 0) {
          pagerState.animateScrollToPage(page)
        }
      }
      selectedTab = tab
    }

    val pagerPositionFloatProvider = remember(pagerState) {
      { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    val mainNavBar = @Composable { modifier: Modifier ->
      ExpressivePillNavigationBar(
        visibleTabs = visibleTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
      )
    }

    LaunchedEffect(selectedTab) {
      android.util.Log.d("MainScreen", "selectedTab changed to: $selectedTab (was $persistentSelectedTab)")
      persistentSelectedTab = selectedTab
      if (selectedTab != MainTab.HOME) {
        NavigationBarState.isDualPaneFolderSelected = false
      }
    }

    LaunchedEffect(visibleTabs) {
      if (visibleTabs.isEmpty()) {
        selectedTab = MainTab.HOME
      } else if (!visibleTabs.contains(selectedTab)) {
        selectedTab = visibleTabs.first()
      }
      val page = visibleTabs.indexOf(selectedTab)
      if (page >= 0) {
        pagerState.scrollToPage(page)
      }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp

    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val selectedTabTitleLength =
      when (selectedTab) {
        MainTab.HOME -> 36.dp
        MainTab.MUSIC -> 36.dp
        MainTab.RECENTS -> 48.dp
        MainTab.PLAYLISTS -> 52.dp
        MainTab.NETWORK -> 50.dp
        MainTab.JELLYFIN -> 44.dp
      }
    val unselectedCount = (visibleTabs.size - 1).coerceAtLeast(0)
    val targetNavBarWidth =
      if (visibleTabs.isEmpty()) 0.dp
      else (22.dp + 6.dp + selectedTabTitleLength + 28.dp) +
        (42.dp * unselectedCount) +
        (4.dp * unselectedCount) +
        12.dp

    val navBarWidth by animateDpAsState(
      targetValue = targetNavBarWidth,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "nav_bar_width",
    )

    val targetOffsetFraction =
      when {
        isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
        isMiniPlayerVisible && (isLandscape || isTablet) -> 0f
        else -> 0.5f
      }

    val animatedOffsetFraction by animateFloatAsState(
      targetValue = targetOffsetFraction,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMedium,
        ),
      label = "nav_bar_position",
    )

    // On portrait phones the edge-to-edge mini player sits above the pill nav bar,
    // so screens/FABs must clear it.
    val miniPlayerNavClearance = if (isMiniPlayerVisible && isPortrait && !isTablet) 96.dp else 0.dp

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        val fabBottomPadding = 88.dp
        val contentBottomPadding = fabBottomPadding + miniPlayerNavClearance
        val context = androidx.compose.ui.platform.LocalContext.current
        val jellyfinViewModel: app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel =
          androidx.lifecycle.viewmodel.compose.viewModel(
            factory =
              app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel.factory(
                context.applicationContext as android.app.Application,
              ),
          )

        if (visibleTabs.isEmpty()) {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
          ) {
            FolderListScreen.Content()
          }
        } else {
          HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().nestedScroll(NavigationBarState.navScrollConnection),
            userScrollEnabled = !isPermissionDenied,
            beyondViewportPageCount = 1,
          ) { page ->
            CompositionLocalProvider(
              LocalNavigationBarHeight provides contentBottomPadding,
              LocalMainNavigationBar provides mainNavBar,
            ) {
              val tab = visibleTabs[page]
              when (tab) {
                MainTab.HOME -> FolderListScreen.Content()
                MainTab.MUSIC -> MusicLibraryContent()
                MainTab.RECENTS -> RecentlyPlayedScreen.Content()
                MainTab.PLAYLISTS -> PlaylistScreen.Content()
                MainTab.NETWORK -> NetworkStreamingScreen.Content()
                MainTab.JELLYFIN -> app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(viewModel = jellyfinViewModel)
              }
            }
          }
        }

        // Animated bottom navigation bar with slide animations
        AnimatedVisibility(
          visible = !hideNavigationBar && visibleTabs.isNotEmpty() && !isPermissionDenied,
          enter =
            slideInVertically(
              animationSpec =
                spring(
                  dampingRatio = AppMotion.Spatial.ExpressiveDp.dampingRatio,
                  stiffness = AppMotion.Spatial.ExpressiveDp.stiffness,
                ),
              initialOffsetY = { fullHeight -> fullHeight * 2 },
            ) + fadeIn(),
          exit =
            slideOutVertically(
              animationSpec =
                spring(
                  dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                  stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                ),
              targetOffsetY = { fullHeight -> fullHeight * 2 },
            ) + fadeOut(),
          modifier =
            Modifier
              .fillMaxWidth()
              .align(Alignment.BottomStart)
              .navigationBarsPadding()
              .padding(bottom = 12.dp),
        ) {
          BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val containerWidth = maxWidth
            val density = LocalDensity.current
            var measuredWidthDp by remember { mutableStateOf(220.dp) }

            val animatedWidthDp by animateDpAsState(
              targetValue = measuredWidthDp,
              animationSpec =
                spring(
                  dampingRatio = Spring.DampingRatioNoBouncy,
                  stiffness = Spring.StiffnessMedium,
                ),
              label = "measured_width_anim",
            )

            val targetLeftPadding =
              when {
                isDualPaneFolderSelected && selectedTab == MainTab.HOME ->
                  ((containerWidth * 0.20f) - (animatedWidthDp / 2)).coerceAtLeast(16.dp)
                isMiniPlayerVisible && (isLandscape || isTablet) ->
                  16.dp
                else ->
                  ((containerWidth - animatedWidthDp) / 2).coerceAtLeast(16.dp)
              }

            val animatedLeftPadding by animateDpAsState(
              targetValue = targetLeftPadding,
              animationSpec =
                spring(
                  dampingRatio = Spring.DampingRatioNoBouncy,
                  stiffness = Spring.StiffnessMedium,
                ),
              label = "pill_left_padding",
            )

            SideEffect {
              NavigationBarState.navbarLeftOffset = animatedLeftPadding
              NavigationBarState.navbarWidth = animatedWidthDp
            }

            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .wrapContentSize(Alignment.TopStart)
                  .padding(start = animatedLeftPadding),
            ) {
              ExpressivePillNavigationBar(
                visibleTabs = visibleTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                modifier =
                  Modifier.onGloballyPositioned { coords ->
                    val w = with(density) { coords.size.width.toDp() }
                    if (w > 0.dp && w != measuredWidthDp) {
                      measuredWidthDp = w
                    }
                  },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ExpressivePillNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val haptics = LocalHapticFeedback.current

  Surface(
    modifier = modifier,
    shape = CircleShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
    border =
      BorderStroke(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
      ),
  ) {
    Row(
      modifier =
        Modifier
          .wrapContentWidth()
          .padding(horizontal = 6.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      visibleTabs.forEach { tab ->
        val isSelected = selectedTab == tab

        val animatedPadding by animateDpAsState(
          targetValue = if (isSelected) 14.dp else 10.dp,
          animationSpec =
            spring(
              dampingRatio = Spring.DampingRatioNoBouncy,
              stiffness = Spring.StiffnessMedium,
            ),
          label = "expressive_nav_padding",
        )

        val animatedContainerColor by animateColorAsState(
          targetValue =
            if (isSelected) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              Color.Transparent
            },
          animationSpec = androidx.compose.animation.core.tween(durationMillis = 180),
          label = "expressive_nav_container_color",
        )

        val animatedContentColor by animateColorAsState(
          targetValue =
            if (isSelected) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          animationSpec = androidx.compose.animation.core.tween(durationMillis = 180),
          label = "expressive_nav_content_color",
        )

        Surface(
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onTabSelected(tab)
          },
          shape = CircleShape,
          color = animatedContainerColor,
          contentColor = animatedContentColor,
          modifier = Modifier.height(44.dp),
        ) {
          Row(
            modifier =
              Modifier
                .padding(horizontal = animatedPadding)
                .animateContentSize(
                  animationSpec =
                    spring(
                      dampingRatio = Spring.DampingRatioNoBouncy,
                      stiffness = Spring.StiffnessMedium,
                    ),
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            when (tab) {
              MainScreen.MainTab.HOME ->
                Icon(
                  Icons.RoundedFilled.Home,
                  contentDescription = stringResource(R.string.ui_home),
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
              MainScreen.MainTab.MUSIC ->
                Icon(
                  Icons.RoundedFilled.Audiotrack,
                  contentDescription = stringResource(R.string.ui_music),
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
              MainScreen.MainTab.RECENTS ->
                Icon(
                  Icons.RoundedFilled.History,
                  contentDescription = stringResource(R.string.ui_recents),
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
              MainScreen.MainTab.PLAYLISTS ->
                Icon(
                  Icons.RoundedFilled.PlaylistPlay,
                  contentDescription = stringResource(R.string.ui_playlists),
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
              MainScreen.MainTab.NETWORK ->
                Icon(
                  Icons.RoundedFilled.BringYourOwnIp,
                  contentDescription = stringResource(R.string.ui_network),
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
              MainScreen.MainTab.JELLYFIN ->
                androidx.compose.material3.Icon(
                  painter = painterResource(R.drawable.ic_jellyfin),
                  contentDescription = "Jellyfin",
                  tint = animatedContentColor,
                  modifier = Modifier.size(22.dp),
                )
            }

            AnimatedVisibility(
              visible = isSelected,
              enter =
                fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) +
                  expandHorizontally(
                    animationSpec =
                      spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                      ),
                  ),
              exit =
                fadeOut(animationSpec = androidx.compose.animation.core.tween(100)) +
                  shrinkHorizontally(
                    animationSpec =
                      spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                      ),
                  ),
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text =
                    when (tab) {
                      MainScreen.MainTab.HOME -> stringResource(R.string.ui_home)
                      MainScreen.MainTab.MUSIC -> stringResource(R.string.ui_music)
                      MainScreen.MainTab.RECENTS -> stringResource(R.string.ui_recents)
                      MainScreen.MainTab.PLAYLISTS -> stringResource(R.string.ui_playlists)
                      MainScreen.MainTab.NETWORK -> stringResource(R.string.ui_network)
                      MainScreen.MainTab.JELLYFIN -> stringResource(R.string.ui_jellyfin)
                    },
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = animatedContentColor,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
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
val LocalMainNavigationBar =
  compositionLocalOf<@Composable (Modifier) -> Unit> {
    { }
  }

/** Builds the [ContentTransform] for tab navigation based on the selected style. */
fun buildNavTransition(
  forward: Boolean,
  style: NavigationAnimStyle,
  speed: Float,
  density: androidx.compose.ui.unit.Density,
): ContentTransform {
  val dir = if (forward) 1 else -1
  val dur = (250 * speed).toInt().coerceAtLeast(60)
  val half = (dur / 2).coerceAtLeast(30)

  return when (style) {
    NavigationAnimStyle.None ->
      (
        fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness)) togetherWith
          fadeOut(spring(stiffness = AppMotion.Spatial.Snappy.stiffness))
      )

    NavigationAnimStyle.Minimal ->
      (
        fadeIn(
          spring(
            dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
            stiffness = AppMotion.Spatial.Standard.stiffness,
          ),
        ) togetherWith
          fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
      )

    NavigationAnimStyle.FlipFade ->
      (
        scaleIn(
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          ),
          initialScale = 0.94f,
        ) +
          fadeIn(
            spring(
              dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
              stiffness = AppMotion.Spatial.Expressive.stiffness,
            ),
          )
      ) togetherWith
        (
          scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 1.06f) +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Depth ->
      (
        slideInHorizontally(
          spring(
            dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
            stiffness = AppMotion.Spatial.Standard.stiffness,
          ),
        ) {
          it * dir
        } +
          fadeIn(
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
          )
      ) togetherWith
        (
          slideOutHorizontally(
            spring(stiffness = AppMotion.Spatial.Standard.stiffness),
          ) { (-it * 0.25f * dir).toInt() } +
            scaleOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness), targetScale = 0.92f) +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Elastic ->
      (
        slideInHorizontally(
          spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 380f),
        ) { it * dir } + fadeIn(spring(stiffness = AppMotion.Spatial.Snappy.stiffness))
      ) togetherWith
        (
          slideOutHorizontally(spring(stiffness = AppMotion.Spatial.Standard.stiffness)) { (-it / 3 * dir) } +
            fadeOut(spring(stiffness = AppMotion.Spatial.Standard.stiffness))
        )

    NavigationAnimStyle.Default -> {
      val slidePx = with(density) { 48.dp.roundToPx() }
      if (forward) {
        (
          slideInHorizontally(
            spring(
              dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
              stiffness = AppMotion.Spatial.Expressive.stiffness,
            ),
          ) {
            slidePx
          } +
            fadeIn(
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
            )
        ) togetherWith
          (
            slideOutHorizontally(
              spring(
                dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                stiffness = AppMotion.Spatial.Standard.stiffness,
              ),
            ) {
              -slidePx
            } +
              fadeOut(
                spring(
                  dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                  stiffness = AppMotion.Spatial.Standard.stiffness,
                ),
              )
          )
      } else {
        (
          slideInHorizontally(
            spring(
              dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
              stiffness = AppMotion.Spatial.Expressive.stiffness,
            ),
          ) {
            -slidePx
          } +
            fadeIn(
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
            )
        ) togetherWith
          (
            slideOutHorizontally(
              spring(
                dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                stiffness = AppMotion.Spatial.Standard.stiffness,
              ),
            ) {
              slidePx
            } +
              fadeOut(
                spring(
                  dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                  stiffness = AppMotion.Spatial.Standard.stiffness,
                ),
              )
          )
      }
    }
  }
}
