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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryContent
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.roundToInt

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
    val backStack = LocalBackStack.current
    val appearancePreferences = koinInject<AppearancePreferences>()
    val mediaServerPreferences = koinInject<MediaServerPreferences>()
    val musicSourceProvider by mediaServerPreferences.musicSourceProvider.collectAsState()
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

    val coroutineScope = rememberCoroutineScope()

    val initialPageIndex =
      remember(visibleTabs) {
        visibleTabs.indexOf(persistentSelectedTab).coerceAtLeast(0)
      }

    val pagerState =
      rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { visibleTabs.size },
      )
    var tabNavigationJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pagerState, visibleTabs) {
      tabNavigationJob?.cancelAndJoin()
      tabNavigationJob = null
      if (visibleTabs.isEmpty()) {
        persistentSelectedTab = MainTab.HOME
        return@LaunchedEffect
      }

      val restorePage = visibleTabs.indexOf(persistentSelectedTab).takeIf { it >= 0 } ?: 0
      val isRestorePageSettled =
        pagerState.settledPage == restorePage &&
          pagerState.currentPage == restorePage &&
          pagerState.currentPageOffsetFraction == 0f
      if (!isRestorePageSettled && !pagerState.isScrollInProgress) {
        pagerState.scrollToPage(restorePage)
      }

      snapshotFlow { pagerState.settledPage }
        .collect { page ->
          visibleTabs.getOrNull(page)?.let { settledTab ->
            persistentSelectedTab = settledTab
            if (settledTab != MainTab.HOME) {
              NavigationBarState.isDualPaneFolderSelected = false
            }
          }
        }
    }

    val targetPage = pagerState.targetPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
    val selectedTab = visibleTabs.getOrNull(targetPage) ?: visibleTabs.firstOrNull() ?: MainTab.HOME

    val onTabSelected: (MainScreen.MainTab) -> Unit = { tab ->
      val targetIndex = visibleTabs.indexOf(tab)
      val isAlreadySettled =
        targetIndex >= 0 &&
          pagerState.settledPage == targetIndex &&
          !pagerState.isScrollInProgress &&
          pagerState.currentPageOffsetFraction == 0f
      if (targetIndex >= 0) {
        tabNavigationJob?.cancel()
        if (!isAlreadySettled) {
          tabNavigationJob =
            coroutineScope.launch {
              pagerState.animateScrollToPage(
                page = targetIndex,
                animationSpec =
                  spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                  ),
              )
            }
        }
      }
    }

    val pagerFlingBehavior =
      androidx.compose.foundation.pager.PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.2f,
        snapAnimationSpec =
          spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
          ),
      )

    val mainNavBar = @Composable { modifier: Modifier ->
      ExpressivePillNavigationBar(
        visibleTabs = visibleTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        pagerState = pagerState,
        modifier = modifier,
      )
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
    val contentBottomPadding = remember(miniPlayerNavClearance) { 88.dp + miniPlayerNavClearance }
    val context = androidx.compose.ui.platform.LocalContext.current
    val jellyfinViewModel: app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel =
      androidx.lifecycle.viewmodel.compose.viewModel(
        factory =
          app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel.factory(
            context.applicationContext as android.app.Application,
          ),
      )

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        if (visibleTabs.isEmpty()) {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
          ) {
            FolderListScreen.Content()
          }
        } else {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
          ) {
            HorizontalPager(
              state = pagerState,
              modifier = Modifier.fillMaxSize(),
              beyondViewportPageCount = 1,
              flingBehavior = pagerFlingBehavior,
              userScrollEnabled = !isPermissionDenied,
            ) { page ->
              val tab = visibleTabs.getOrNull(page) ?: return@HorizontalPager
              when (tab) {
                MainTab.HOME -> FolderListScreen.Content()
                MainTab.MUSIC -> {
                  if (musicSourceProvider == MusicSourceProvider.JELLYFIN) {
                    val jellyfinUiState by jellyfinViewModel.uiState.collectAsStateWithLifecycle()
                    if (jellyfinUiState.activeServer == null) {
                      Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                      ) {
                        androidx.compose.material3.Card(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          colors =
                            androidx.compose.material3.CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                          Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                          ) {
                            androidx.compose.material3.Icon(
                              painter = painterResource(R.drawable.ic_jellyfin),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_jellyfin),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.pref_jellyfin_no_server),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                backStack.add(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                            androidx.compose.material3.TextButton(
                              onClick = {
                                mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                              },
                            ) {
                              Text(stringResource(R.string.music_source_local))
                            }
                          }
                        }
                      }
                    } else if (!jellyfinUiState.isLoading && !jellyfinUiState.hasMusicLibrary && jellyfinUiState.libraries.isNotEmpty()) {
                      Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center,
                      ) {
                        androidx.compose.material3.Card(
                          shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                          colors =
                            androidx.compose.material3.CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                          Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                          ) {
                            androidx.compose.material3.Icon(
                              painter = painterResource(R.drawable.ic_jellyfin),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_jellyfin),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.jellyfin_no_music_library),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                              },
                            ) {
                              Text(stringResource(R.string.music_source_local))
                            }
                            androidx.compose.material3.TextButton(
                              onClick = {
                                backStack.add(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                          }
                        }
                      }
                    } else {
                      LaunchedEffect(jellyfinUiState.libraries) {
                        jellyfinViewModel.ensureMusicLibraryOpened()
                      }
                      app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(
                        viewModel = jellyfinViewModel,
                        isMusicOnlyMode = true,
                      )
                    }
                  } else {
                    MusicLibraryContent(jellyfinViewModel = jellyfinViewModel)
                  }
                }
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
            val isDualPaneActive = isDualPaneFolderSelected && selectedTab == MainTab.HOME
            val isMiniPlayerActive = isMiniPlayerVisible && (isLandscape || isTablet)
            val isCustomAligned = isDualPaneActive || isMiniPlayerActive

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
                isDualPaneActive ->
                  ((containerWidth * 0.20f) - (animatedWidthDp / 2)).coerceAtLeast(16.dp)
                isMiniPlayerActive ->
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
              val targetLeft = if (isCustomAligned) animatedLeftPadding else ((containerWidth - animatedWidthDp) / 2).coerceAtLeast(16.dp)
              if (NavigationBarState.navbarLeftOffset != targetLeft) {
                NavigationBarState.navbarLeftOffset = targetLeft
              }
              if (NavigationBarState.navbarWidth != animatedWidthDp) {
                NavigationBarState.navbarWidth = animatedWidthDp
              }
            }

            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .then(
                    if (isCustomAligned) {
                      Modifier
                        .wrapContentSize(Alignment.TopStart)
                        .padding(start = animatedLeftPadding)
                    } else {
                      Modifier.wrapContentSize(Alignment.TopCenter)
                    }
                  ),
            ) {
              ExpressivePillNavigationBar(
                visibleTabs = visibleTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                pagerState = pagerState,
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
  pagerState: PagerState? = null,
) {
  val haptics = LocalHapticFeedback.current

  val position =
    if (pagerState != null && visibleTabs.isNotEmpty()) {
      (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(
        0f,
        (visibleTabs.size - 1).toFloat(),
      )
    } else {
      visibleTabs.indexOf(selectedTab).coerceAtLeast(0).toFloat()
    }

  fun activeTabWidth(tab: MainScreen.MainTab): androidx.compose.ui.unit.Dp =
    when (tab) {
      MainScreen.MainTab.HOME -> 92.dp
      MainScreen.MainTab.MUSIC -> 92.dp
      MainScreen.MainTab.RECENTS -> 104.dp
      MainScreen.MainTab.PLAYLISTS -> 108.dp
      MainScreen.MainTab.NETWORK -> 106.dp
      MainScreen.MainTab.JELLYFIN -> 100.dp
    }

  val inactiveTabWidth = 44.dp
  val spacing = 4.dp
  val startPadding = 6.dp

  val tabWidths = remember(position, visibleTabs) {
    visibleTabs.mapIndexed { index, tab ->
      val fraction = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
      androidx.compose.ui.unit.lerp(inactiveTabWidth, activeTabWidth(tab), fraction)
    }
  }

  val tabOffsets = remember(tabWidths) {
    var acc = startPadding
    tabWidths.map { w ->
      val left = acc
      acc += w + spacing
      left
    }
  }

  val pageFloor = position.toInt().coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
  val pageCeil = (pageFloor + 1).coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
  val fraction = (position - pageFloor).coerceIn(0f, 1f)

  val indicatorLeft =
    if (tabOffsets.isNotEmpty()) {
      androidx.compose.ui.unit.lerp(tabOffsets[pageFloor], tabOffsets[pageCeil], fraction)
    } else {
      startPadding
    }

  val indicatorWidth =
    if (tabWidths.isNotEmpty()) {
      androidx.compose.ui.unit.lerp(tabWidths[pageFloor], tabWidths[pageCeil], fraction)
    } else {
      inactiveTabWidth
    }

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
    Box(
      modifier =
        Modifier
          .wrapContentWidth()
          .padding(horizontal = startPadding, vertical = 6.dp),
    ) {
      // Sliding background pill indicator
      Box(
        modifier =
          Modifier
            .offset(x = indicatorLeft - startPadding, y = 0.dp)
            .width(indicatorWidth)
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
      )

      // Tab buttons row positioned directly on top of the track
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        visibleTabs.forEachIndexed { index, tab ->
          val tabFraction = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
          val tabWidth = tabWidths.getOrElse(index) { inactiveTabWidth }

          val contentColor =
            androidx.compose.ui.graphics.lerp(
              MaterialTheme.colorScheme.onSurfaceVariant,
              MaterialTheme.colorScheme.onPrimaryContainer,
              tabFraction,
            )

          Box(
            modifier =
              Modifier
                .width(tabWidth)
                .height(44.dp)
                .clip(CircleShape)
                .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = androidx.compose.material3.ripple(bounded = true),
                ) {
                  haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                  onTabSelected(tab)
                },
            contentAlignment = Alignment.Center,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              when (tab) {
                MainScreen.MainTab.HOME ->
                  Icon(
                    Icons.RoundedFilled.Home,
                    contentDescription = stringResource(R.string.ui_home),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
                MainScreen.MainTab.MUSIC ->
                  Icon(
                    Icons.RoundedFilled.Audiotrack,
                    contentDescription = stringResource(R.string.ui_music),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
                MainScreen.MainTab.RECENTS ->
                  Icon(
                    Icons.RoundedFilled.History,
                    contentDescription = stringResource(R.string.ui_recents),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
                MainScreen.MainTab.PLAYLISTS ->
                  Icon(
                    Icons.RoundedFilled.PlaylistPlay,
                    contentDescription = stringResource(R.string.ui_playlists),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
                MainScreen.MainTab.NETWORK ->
                  Icon(
                    Icons.RoundedFilled.BringYourOwnIp,
                    contentDescription = stringResource(R.string.ui_network),
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
                MainScreen.MainTab.JELLYFIN ->
                  androidx.compose.material3.Icon(
                    painter = painterResource(R.drawable.ic_jellyfin),
                    contentDescription = "Jellyfin",
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                  )
              }

              if (tabFraction > 0.05f) {
                Spacer(modifier = Modifier.width(androidx.compose.ui.unit.lerp(0.dp, 6.dp, tabFraction)))
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
                  color = contentColor,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Clip,
                  modifier =
                    Modifier.graphicsLayer {
                      alpha = ((tabFraction - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    },
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
