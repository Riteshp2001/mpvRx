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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ProvideLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.LiquidGlassStyle
import app.gyrolet.mpvrx.presentation.components.LiquidGlassSurface
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.presentation.components.rememberLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryContent
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryViewModel
import app.gyrolet.mpvrx.ui.browser.music.MusicTab
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistScreen
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.utils.navigationDurationMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
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
    SNAPSHOTS,
  }

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
    val playerPreferences = koinInject<PlayerPreferences>()
    val navStyle by playerPreferences.appNavStyle.collectAsState()
    val animSpeed by playerPreferences.animationSpeed.collectAsState()
    val duration = navigationDurationMillis(animSpeed)
    var persistentSelectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val mediaServerPreferences = koinInject<MediaServerPreferences>()
    val musicSourceProvider by mediaServerPreferences.musicSourceProvider.collectAsState()
    val musicLibraryViewModel: MusicLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val localMusicTabs by musicLibraryViewModel.visibleTabs.collectAsStateWithLifecycle()
    val showMusicTab by appearancePreferences.showMusicTab.collectAsState()
    val showRecentsTab by appearancePreferences.showRecentsTab.collectAsState()
    val showPlaylistsTab by appearancePreferences.showPlaylistsTab.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val showJellyfinTab by appearancePreferences.showJellyfinTab.collectAsState()
    val showSnapshotTab by appearancePreferences.showSnapshotTab.collectAsState()
    val liquidGlassEnabled by appearancePreferences.liquidGlassEnabled.collectAsState()
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible

    val visibleTabs =
      remember(
        showMusicTab,
        showRecentsTab,
        showPlaylistsTab,
        showNetworkTab,
        showJellyfinTab,
        showSnapshotTab,
      ) {
        buildList {
          // Home is the permanent root so Back never exits directly from another tab.
          add(MainTab.HOME)
          if (showMusicTab) add(MainTab.MUSIC)
          if (showRecentsTab) add(MainTab.RECENTS)
          if (showPlaylistsTab) add(MainTab.PLAYLISTS)
          if (showNetworkTab) add(MainTab.NETWORK)
          if (showJellyfinTab) add(MainTab.JELLYFIN)
          if (showSnapshotTab) add(MainTab.SNAPSHOTS)
        }
      }
    val navigationTabs = visibleTabs.takeIf { it.size > 1 }.orEmpty()

    // Track whether the floating pill nav bar is on screen so the mini player can
    // sit at the very bottom when navigating to screens without it.
    DisposableEffect(Unit) {
      onDispose {
        NavigationBarState.isNavBarVisible = false
      }
    }
    SideEffect {
      NavigationBarState.isNavBarVisible =
        backStack.lastOrNull() == MainScreen && !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied
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
      if (!isRestorePageSettled) {
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

    LaunchedEffect(persistentSelectedTab) {
      if (persistentSelectedTab == MainTab.MUSIC) {
        musicLibraryViewModel.setTab(localMusicTabs.firstOrNull() ?: MusicTab.SONGS)
      }
    }

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
              if (navStyle == NavigationAnimStyle.None) {
                pagerState.scrollToPage(targetIndex)
              } else {
                pagerState.animateScrollToPage(
                  page = targetIndex,
                  animationSpec = tween(duration, easing = FastOutSlowInEasing),
                )
              }
            }
        }
      }
    }

    val shouldReturnHome by remember(pagerState) {
      derivedStateOf {
        pagerState.settledPage != 0 || pagerState.currentPage != 0 || pagerState.isScrollInProgress
      }
    }
    // Register before page content: selection, search and nested navigation handle Back first.
    BackHandler(enabled = backStack.lastOrNull() == MainScreen && shouldReturnHome) {
      onTabSelected(MainTab.HOME)
    }

    val mainNavBar = @Composable { modifier: Modifier ->
      ExpressivePillNavigationBar(
        visibleTabs = navigationTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        pagerState = pagerState,
        modifier = modifier,
      )
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // On portrait phones the edge-to-edge mini player sits above the pill nav bar,
    // so screens/FABs must clear it.
    val miniPlayerNavClearance = if (isMiniPlayerVisible && isPortrait && !isTablet) 96.dp else 0.dp
    val contentBottomPadding = (if (navigationTabs.isEmpty()) 0.dp else 88.dp) + miniPlayerNavClearance
    val context = androidx.compose.ui.platform.LocalContext.current
    val jellyfinViewModel: app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel =
      androidx.lifecycle.viewmodel.compose.viewModel(
        factory =
          app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinViewModel.factory(
            context.applicationContext as android.app.Application,
          ),
      )
    val navidromeViewModel: app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeViewModel =
      androidx.lifecycle.viewmodel.compose.viewModel(
        factory =
          app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeViewModel.factory(
            context.applicationContext as android.app.Application,
          ),
      )
    val navigationBackdrop = rememberLiquidGlassBackdrop()

    // Scaffold with bottom navigation bar
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      containerColor = wallpaperAwareBackgroundColor(),
    ) { paddingValues ->
      Box(modifier = Modifier.fillMaxSize()) {
        if (visibleTabs.isEmpty()) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .captureLiquidGlassBackdrop(navigationBackdrop, liquidGlassEnabled),
          ) {
            CompositionLocalProvider(
              LocalNavigationBarHeight provides contentBottomPadding,
              LocalMainNavigationBar provides mainNavBar,
            ) {
              FolderListScreen.Content()
            }
          }
        } else {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
          ) {
            NavigationPager(
              state = pagerState,
              modifier =
                Modifier
                  .fillMaxSize()
                  .clipToBounds()
                  .captureLiquidGlassBackdrop(navigationBackdrop, liquidGlassEnabled),
              key = { page -> visibleTabs[page].name },
              beyondViewportPageCount = 1,
              userScrollEnabled = !isPermissionDenied,
            ) { page ->
              val tab = visibleTabs.getOrNull(page) ?: return@NavigationPager
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
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
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
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                              },
                            ) {
                              Text(stringResource(R.string.generic_configure))
                            }
                          }
                        }
                      }
                    } else {
                      LaunchedEffect(jellyfinUiState.libraries) {
                        jellyfinViewModel.ensureMusicDataLoaded()
                      }
                      app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(
                        viewModel = jellyfinViewModel,
                        isMusicOnlyMode = true,
                      )
                    }
                  } else if (musicSourceProvider == MusicSourceProvider.NAVIDROME) {
                    val navidromeUiState by navidromeViewModel.uiState.collectAsStateWithLifecycle()
                    if (navidromeUiState.activeServer == null) {
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
                              painter = painterResource(R.drawable.ic_navidrome),
                              contentDescription = null,
                              modifier = Modifier.size(56.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                              text = stringResource(R.string.music_source_navidrome),
                              style = MaterialTheme.typography.titleLarge,
                              fontWeight = FontWeight.Bold,
                            )
                            Text(
                              text = stringResource(R.string.pref_navidrome_no_server),
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            androidx.compose.material3.FilledTonalButton(
                              onClick = {
                                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
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
                    } else {
                      app.gyrolet.mpvrx.ui.browser.navidrome.NavidromeContent(
                        viewModel = navidromeViewModel,
                        isMusicOnlyMode = true,
                      )
                    }
                  } else if (musicSourceProvider == MusicSourceProvider.AUDIOBOOKS) {
                    app.gyrolet.mpvrx.ui.browser.audiobooks.AudiobookLibraryContent(
                      isMusicTabMode = true,
                    )
                  } else {
                    MusicLibraryContent(
                      musicViewModel = musicLibraryViewModel,
                      jellyfinViewModel = jellyfinViewModel,
                      navidromeViewModel = navidromeViewModel,
                    )
                  }
                }
                MainTab.RECENTS -> RecentlyPlayedScreen.Content()
                MainTab.PLAYLISTS -> PlaylistScreen.Content()
                MainTab.NETWORK -> NetworkStreamingScreen.Content()
                MainTab.JELLYFIN -> app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(viewModel = jellyfinViewModel)
                MainTab.SNAPSHOTS -> app.gyrolet.mpvrx.ui.framecapture.SnapshotScreen.Content()
              }
            }
          }
        }

        // Animated bottom navigation bar with slide animations
        ProvideLiquidGlassBackdrop(
          backdrop = navigationBackdrop,
          enabled = liquidGlassEnabled,
        ) {
          AnimatedVisibility(
            visible = !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied,
            enter = if (navStyle == NavigationAnimStyle.None) EnterTransition.None else
              slideInVertically(
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight * 2 },
              ) + fadeIn(tween(duration)),
            exit = if (navStyle == NavigationAnimStyle.None) ExitTransition.None else
              slideOutVertically(
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> fullHeight * 2 },
              ) + fadeOut(tween(duration)),
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
              val centerFraction = animateFloatAsState(
                targetValue = when {
                  isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
                  isMiniPlayerVisible && (isLandscape || isTablet) -> 0f
                  else -> 0.5f
                },
                animationSpec = if (navStyle == NavigationAnimStyle.None) snap() else tween(duration, easing = FastOutSlowInEasing),
                label = "pill_alignment",
              )

              ExpressivePillNavigationBar(
                visibleTabs = navigationTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                pagerState = pagerState,
                modifier = Modifier
                  .layout { measurable, constraints ->
                    val margin = 16.dp.roundToPx()
                    val placeable = measurable.measure(
                      constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)),
                    )
                    layout(constraints.maxWidth, placeable.height) {
                      // Place using the actual width, avoiding springs chasing animated measurements.
                      val start = (constraints.maxWidth * centerFraction.value - placeable.width / 2f)
                        .roundToInt().coerceAtLeast(margin)
                      placeable.placeRelative(start, 0)
                    }
                  }
                  .onGloballyPositioned { coords ->
                    val width = with(density) { coords.size.width.toDp() }
                    NavigationBarState.navbarWidth = width
                    NavigationBarState.navbarLeftOffset =
                      (containerWidth * centerFraction.value - width / 2).coerceAtLeast(16.dp)
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
internal fun ExpressivePillNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
  pagerState: PagerState? = null,
) {
  if (visibleTabs.isEmpty()) return
  val initialFocusRequester = rememberTvInitialFocusRequester(visibleTabs.isNotEmpty())

  val position =
    if (pagerState != null) {
      (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(
        0f,
        (visibleTabs.size - 1).toFloat(),
      )
    } else {
      visibleTabs.indexOf(selectedTab).coerceAtLeast(0).toFloat()
    }

  fun activeTabWidth(tab: MainScreen.MainTab) =
    when (tab) {
      MainScreen.MainTab.HOME -> 92.dp
      MainScreen.MainTab.MUSIC -> 92.dp
      MainScreen.MainTab.RECENTS -> 104.dp
      MainScreen.MainTab.PLAYLISTS -> 108.dp
      MainScreen.MainTab.NETWORK -> 106.dp
      MainScreen.MainTab.JELLYFIN -> 100.dp
      MainScreen.MainTab.SNAPSHOTS -> 100.dp
    }

  val inactiveTabWidth = 44.dp
  val spacing = 4.dp
  val startPadding = 6.dp
  val tabWidths =
    visibleTabs.mapIndexed { index, tab ->
      val fraction = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
      androidx.compose.ui.unit.lerp(inactiveTabWidth, activeTabWidth(tab), fraction)
    }
  val tabOffsets =
    buildList {
      var offset = startPadding
      tabWidths.forEach { width ->
        add(offset)
        offset += width + spacing
      }
    }
  val pageFloor = position.toInt().coerceIn(visibleTabs.indices)
  val pageCeil = (pageFloor + 1).coerceIn(visibleTabs.indices)
  val pageFraction = (position - pageFloor).coerceIn(0f, 1f)
  val indicatorLeft = androidx.compose.ui.unit.lerp(tabOffsets[pageFloor], tabOffsets[pageCeil], pageFraction)
  val indicatorWidth = androidx.compose.ui.unit.lerp(tabWidths[pageFloor], tabWidths[pageCeil], pageFraction)

  LiquidGlassSurface(
    modifier = modifier,
    shape = CircleShape,
    style = LiquidGlassStyle.Navigation,
    glassColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.32f),
    fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Box(
      modifier =
        Modifier
          .wrapContentWidth()
          .padding(horizontal = startPadding, vertical = 6.dp),
    ) {
      Box(
        modifier =
          Modifier
            .offset(x = indicatorLeft - startPadding)
            .width(indicatorWidth)
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
      )

      Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        visibleTabs.forEachIndexed { index, tab ->
          key(tab) {
            val activeFraction = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
            val label =
              when (tab) {
                MainScreen.MainTab.HOME -> stringResource(R.string.ui_home)
                MainScreen.MainTab.MUSIC -> stringResource(R.string.ui_music)
                MainScreen.MainTab.RECENTS -> stringResource(R.string.ui_recents)
                MainScreen.MainTab.PLAYLISTS -> stringResource(R.string.ui_playlists)
                MainScreen.MainTab.NETWORK -> stringResource(R.string.ui_network)
                MainScreen.MainTab.JELLYFIN -> stringResource(R.string.ui_jellyfin)
                MainScreen.MainTab.SNAPSHOTS -> stringResource(R.string.ui_snapshots)
              }
            val contentColor =
              androidx.compose.ui.graphics.lerp(
                MaterialTheme.colorScheme.onSurfaceVariant,
                MaterialTheme.colorScheme.onPrimaryContainer,
                activeFraction,
              )

            Box(
              modifier =
                Modifier
                  .width(tabWidths[index])
                  .height(44.dp)
                  .then(if (tab == selectedTab) Modifier.tvInitialFocus(initialFocusRequester) else Modifier)
                  .tvFocusHighlight(CircleShape, focusedScale = 1.06f)
                  .clip(CircleShape)
                  .selectable(
                    selected = tab == selectedTab,
                    role = Role.Tab,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                  ) {
                    onTabSelected(tab)
                  },
              contentAlignment = Alignment.Center,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                MainTabIcon(tab, contentColor, label)
                if (activeFraction > 0.05f) {
                  Spacer(modifier = Modifier.width(androidx.compose.ui.unit.lerp(0.dp, 6.dp, activeFraction)))
                  Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier =
                      Modifier.graphicsLayer {
                        alpha = ((activeFraction - 0.25f) / 0.75f).coerceIn(0f, 1f)
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
}

@Composable
private fun MainTabIcon(
  tab: MainScreen.MainTab,
  tint: Color,
  contentDescription: String?,
) {
  val icon = when (tab) {
    MainScreen.MainTab.HOME -> Icons.RoundedFilled.Home
    MainScreen.MainTab.MUSIC -> Icons.RoundedFilled.Audiotrack
    MainScreen.MainTab.RECENTS -> Icons.RoundedFilled.History
    MainScreen.MainTab.PLAYLISTS -> Icons.RoundedFilled.Subscriptions
    MainScreen.MainTab.NETWORK -> Icons.RoundedFilled.BringYourOwnIp
    MainScreen.MainTab.JELLYFIN -> null
    MainScreen.MainTab.SNAPSHOTS -> Icons.RoundedFilled.Image
  }
  if (icon == null) {
    androidx.compose.material3.Icon(
      painter = painterResource(R.drawable.ic_jellyfin),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(22.dp),
    )
  } else {
    Icon(
      icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(22.dp),
    )
  }
}

val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

// CompositionLocal for main navigation bar
val LocalMainNavigationBar =
  compositionLocalOf<@Composable (Modifier) -> Unit> {
    { }
  }
