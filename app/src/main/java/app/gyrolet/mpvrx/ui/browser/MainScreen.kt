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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ProvideLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryContent
import app.gyrolet.mpvrx.ui.browser.music.MusicLibraryViewModel
import app.gyrolet.mpvrx.ui.browser.music.MusicTab
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkStreamingScreen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.player.NavigationAnimStyle
import app.gyrolet.mpvrx.ui.utils.navigationDurationMillis
import app.gyrolet.mpvrx.ui.utils.navigationTabAnimationSpec
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object MainScreen : Screen {
  internal enum class MainTab {
    HOME,
    MUSIC,
    NETWORK,
    JELLYFIN,
    PROFILE,
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
    val reduceMotion = AppMotion.shouldReduceMotion()
    var persistentSelectedTab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val mediaServerPreferences = koinInject<MediaServerPreferences>()
    val musicSourceProvider by mediaServerPreferences.musicSourceProvider.collectAsState()
    val musicLibraryViewModel: MusicLibraryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val localMusicTabs by musicLibraryViewModel.visibleTabs.collectAsStateWithLifecycle()
    val showMusicTab by appearancePreferences.showMusicTab.collectAsState()
    val showProfileTab by appearancePreferences.showProfileTab.collectAsState()
    val showNetworkTab by appearancePreferences.showNetworkTab.collectAsState()
    val showJellyfinTab by appearancePreferences.showJellyfinTab.collectAsState()
    val liquidGlassEnabled by appearancePreferences.liquidGlassEnabled.collectAsState()
    val liquidLayerBackdrop = rememberLayerBackdrop()
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible

    val visibleTabs =
      remember(
        showMusicTab,
        showProfileTab,
        showNetworkTab,
        showJellyfinTab,
      ) {
        mainNavigationTabs(
          showMusic = showMusicTab,
          showProfile = showProfileTab,
          showNetwork = showNetworkTab,
          showJellyfin = showJellyfinTab,
        )
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
    val tabContentAlpha = remember { Animatable(1f) }
    var isDirectTabJumpInProgress by remember { mutableStateOf(false) }

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
      if (targetIndex >= 0 && !isAlreadySettled) {
        // Bottom-nav taps are destination changes, not a request to visibly traverse every
        // intermediate tab. Keep the native pager animation for adjacent destinations, but
        // fade through long jumps so heavyweight Music/Network pages are never animated across.
        val previousJob = tabNavigationJob
        previousJob?.cancel()
        tabNavigationJob =
          coroutineScope.launch {
            previousJob?.join()

            val currentIndex = pagerState.currentPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
            val isLongJump = abs(targetIndex - currentIndex) > 1

            tabContentAlpha.stop()
            tabContentAlpha.snapTo(1f)

            if (navStyle == NavigationAnimStyle.None || reduceMotion) {
              isDirectTabJumpInProgress = true
              try {
                pagerState.scrollToPage(targetIndex)
              } finally {
                tabContentAlpha.snapTo(1f)
                isDirectTabJumpInProgress = false
              }
            } else if (!isLongJump) {
              pagerState.animateScrollToPage(
                page = targetIndex,
                animationSpec = navigationTabAnimationSpec(navStyle, animSpeed),
              )
            } else {
              isDirectTabJumpInProgress = true
              try {
                // Fade the current destination out first. The actual pager jump happens while
                // content is hidden, then the destination fades in after it has been composed.
                tabContentAlpha.animateTo(
                  targetValue = 0f,
                  animationSpec =
                    tween(
                      durationMillis = navigationDurationMillis(animSpeed, 85),
                      easing = FastOutSlowInEasing,
                    ),
                )
                pagerState.scrollToPage(targetIndex)
                withFrameNanos { }
                tabContentAlpha.animateTo(
                  targetValue = 1f,
                  animationSpec =
                    tween(
                      durationMillis = navigationDurationMillis(animSpeed, 145),
                      easing = FastOutSlowInEasing,
                    ),
                )
              } finally {
                tabContentAlpha.snapTo(1f)
                isDirectTabJumpInProgress = false
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

    // Keep the app's established Haze path for the normal navigation bar. Liquid Glass uses the
    // separate Kyant layer backdrop declared with the screen state above.
    val navigationBackdrop = rememberHazeState()

    val mainNavBar = @Composable { modifier: Modifier ->
      ExpressivePillNavigationBar(
        visibleTabs = navigationTabs,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        pagerState = pagerState,
        hazeBackdrop = navigationBackdrop,
        kyantBackdrop = liquidLayerBackdrop,
        liquidGlassEnabled = liquidGlassEnabled,
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
    val contentBottomPadding = (if (navigationTabs.isEmpty()) 0.dp else NavigationBarState.navigationBarClearance) + miniPlayerNavClearance
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
                .layerBackdrop(liquidLayerBackdrop)
                .hazeSource(navigationBackdrop),
          ) {
            CompositionLocalProvider(
              LocalNavigationBarHeight provides contentBottomPadding,
              LocalMainNavigationBar provides mainNavBar,
              LocalIsMainTabPage provides true,
            ) {
              FolderListScreen.Content()
            }
          }
        } else {
          CompositionLocalProvider(
            LocalNavigationBarHeight provides contentBottomPadding,
            LocalMainNavigationBar provides mainNavBar,
            LocalIsMainTabPage provides true,
          ) {
            NavigationPager(
              state = pagerState,
              modifier =
                Modifier
                  .fillMaxSize()
                  .clipToBounds()
                  .graphicsLayer { alpha = tabContentAlpha.value }
                  .nestedScroll(NavigationBarState.navScrollConnection)
                  .layerBackdrop(liquidLayerBackdrop)
                  .hazeSource(navigationBackdrop),
              key = { page -> visibleTabs[page].name },
              beyondViewportPageCount = 1,
              userScrollEnabled = !isPermissionDenied && !isDirectTabJumpInProgress,
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
                          shape = MaterialTheme.shapes.extraLarge,
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
                          shape = MaterialTheme.shapes.extraLarge,
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
                          shape = MaterialTheme.shapes.extraLarge,
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
                MainTab.NETWORK -> NetworkStreamingScreen.Content()
                MainTab.JELLYFIN -> app.gyrolet.mpvrx.ui.browser.jellyfin.JellyfinContent(viewModel = jellyfinViewModel)
                MainTab.PROFILE -> app.gyrolet.mpvrx.ui.browser.profile.ProfileScreen.Content()
              }
            }
          }
        }

        // Animated bottom navigation bar with slide animations
        ProvideLiquidGlassBackdrop(
          backdrop = liquidLayerBackdrop,
          enabled = liquidGlassEnabled,
        ) {
          AnimatedVisibility(
            visible = !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied,
            enter = if (navStyle == NavigationAnimStyle.None) EnterTransition.None else
              slideInVertically(
                animationSpec = tween(duration, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight / 2 },
              ) + fadeIn(tween((duration * 0.82f).roundToInt())),
            exit = if (navStyle == NavigationAnimStyle.None) ExitTransition.None else
              slideOutVertically(
                animationSpec = tween((duration * 0.78f).roundToInt(), easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> fullHeight / 2 },
              ) + fadeOut(tween((duration * 0.62f).roundToInt())),
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart),
          ) {
            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .navigationBarsPadding()
                  .padding(bottom = 8.dp),
            ) {
              val horizontalMargin by animateDpAsState(
                targetValue = 58.dp - 30.dp * NavigationBarState.navLabelVisibility,
                animationSpec = if (navStyle == NavigationAnimStyle.None) snap() else tween(300, easing = NavigationBarEasing),
                label = "navigation_margin",
              )
              val centerFraction = animateFloatAsState(
                targetValue = when {
                  isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
                  isMiniPlayerVisible && (isLandscape || isTablet) -> 0f
                  else -> 0.5f
                },
                animationSpec = if (navStyle == NavigationAnimStyle.None) snap() else tween(duration, easing = FastOutSlowInEasing),
                label = "pill_alignment",
              )

              val navModifier = Modifier
                .layout { measurable, constraints ->
                  val margin = horizontalMargin.roundToPx()
                  val paneWidth =
                    if (isDualPaneFolderSelected && selectedTab == MainTab.HOME) {
                      (constraints.maxWidth * 0.4f).roundToInt()
                    } else {
                      constraints.maxWidth
                    }
                  val availableWidth = (paneWidth - margin * 2).coerceAtLeast(0)
                  val maxNuvioWidth = minOf(400.dp.roundToPx(), availableWidth)
                  val placeable =
                    measurable.measure(
                      constraints.copy(minWidth = 0, maxWidth = maxNuvioWidth),
                    )
                  layout(constraints.maxWidth, placeable.height) {
                    val desired =
                      (constraints.maxWidth * centerFraction.value - placeable.width / 2f)
                        .roundToInt()
                    val maxStart = (paneWidth - margin - placeable.width).coerceAtLeast(margin)
                    val start = desired.coerceIn(margin, maxStart)
                    placeable.placeRelative(start, 0)
                  }
                }

              ExpressivePillNavigationBar(
                visibleTabs = navigationTabs,
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                pagerState = pagerState,
                hazeBackdrop = navigationBackdrop,
                kyantBackdrop = liquidLayerBackdrop,
                liquidGlassEnabled = liquidGlassEnabled,
                modifier = navModifier,
              )
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressivePillNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
  pagerState: PagerState? = null,
  hazeBackdrop: HazeState? = null,
  kyantBackdrop: Backdrop? = null,
  liquidGlassEnabled: Boolean = false,
) {
  if (visibleTabs.isEmpty()) return

  val glassSettings = rememberLiquidGlassSettings()
  val initialFocusRequester = rememberTvInitialFocusRequester(visibleTabs.isNotEmpty())
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val selectedIndex = pagerState?.targetPage?.takeIf { it in visibleTabs.indices }
    ?: visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
  val labels = visibleTabs.map { tab -> mainNavigationLabel(tab) }
  fun visualIndex(index: Int) = if (isRtl) visibleTabs.lastIndex - index else index
  val motion = remember(visibleTabs, isRtl) { NavigationJellyMotion(visualIndex(selectedIndex), visibleTabs.size) }
  val playerPreferences = koinInject<PlayerPreferences>()
  val navStyle by playerPreferences.appNavStyle.collectAsState()
  val reducedMotion = AppMotion.shouldReduceMotion() || navStyle == NavigationAnimStyle.None
  val density = LocalDensity.current
  val currentSelectedTab by rememberUpdatedState(selectedTab)
  val currentOnTabSelected by rememberUpdatedState(onTabSelected)
  val appearancePreferences = koinInject<AppearancePreferences>()
  val navigationHaptics = app.gyrolet.mpvrx.ui.utils.rememberAppHaptics()
  val glowEnabled by appearancePreferences.navigationBarGlow.collectAsState()
  val glowStrength by animateFloatAsState(
    targetValue = if (glowEnabled) 1f else 0f,
    animationSpec = if (reducedMotion) snap() else tween(420, easing = NavigationBarEasing),
    label = "navigation_glow",
  )
  val compactIcons = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
  val iconSize = if (compactIcons) 24.dp else MainNavigationIconSize
  val labelStyle = if (compactIcons) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
  val activeLabelStyle =
    if (compactIcons) MaterialTheme.typography.labelSmallEmphasized else MaterialTheme.typography.labelMediumEmphasized
  val labelHeight = with(density) { labelStyle.lineHeight.toDp() }
  val labelFraction by animateFloatAsState(
    targetValue = NavigationBarState.navLabelVisibility,
    animationSpec = if (reducedMotion) snap() else tween(300, easing = NavigationBarEasing),
    label = "navigation_labels",
  )
  LaunchedEffect(selectedTab) { NavigationBarState.expandNavLabels() }
  LaunchedEffect(selectedIndex, motion, reducedMotion) {
    if (reducedMotion) motion.snapTo(visualIndex(selectedIndex)) else motion.select(visualIndex(selectedIndex))
  }
  LaunchedEffect(motion.running, reducedMotion) {
    if (!motion.running || reducedMotion) return@LaunchedEffect
    var previousFrame = withFrameNanos { it }
    while (motion.running) {
      withFrameNanos { now ->
        motion.advance((now - previousFrame) / 1_000_000_000.0)
        previousFrame = now
      }
    }
  }

  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
  val accentColor = MaterialTheme.colorScheme.primary
  val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
  val selectedSurface = accentColor.copy(alpha = 0.15f)
  val selectedContent = accentColor
  val accentBrush = Brush.linearGradient(listOf(accentColor, MaterialTheme.colorScheme.secondary))

  val tabRow: @Composable (Boolean) -> Unit = { active ->
      Row(
        modifier = Modifier.fillMaxSize().padding(4.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
      ) {
        visibleTabs.forEachIndexed { index, tab ->
          key(tab) {
            val label = labels[index]
            val contentColor = if (active) selectedContent else mutedColor

            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .graphicsLayer {
                    val contentScale = if (active) motion.frame.contentScale else 1f
                    scaleX = contentScale
                    scaleY = contentScale
                  },
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                Box(Modifier.size(iconSize).graphicsLayer { translationY = 2.dp.toPx() * labelFraction }
                  .then(if (active && tab != MainScreen.MainTab.PROFILE) Modifier.navigationAccentMask(accentBrush) else Modifier)) {
                  MainTabIcon(tab, if (active && tab != MainScreen.MainTab.PROFILE) Color.White else contentColor, null, iconSize)
                }
                Box(Modifier.height(labelHeight * labelFraction).fillMaxWidth().clipToBounds().graphicsLayer { alpha = labelFraction }) {
                Text(
                  text = label,
                  style = if (active) activeLabelStyle else labelStyle,
                  color = contentColor,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                }
              }
            }
          }
        }
      }
  }

  val baseModifier =
    modifier
      .widthIn(max = 400.dp)
      .fillMaxWidth()
      .height(48.dp + labelHeight * labelFraction)
      .onSizeChanged { motion.resize(it.width / density.density, it.height / density.density) }
      .onGloballyPositioned { coordinates ->
        // The outer layout fills the screen; measure the real pill for mini-player clearance.
        NavigationBarState.navbarWidth = with(density) { coordinates.size.width.toDp() }
        NavigationBarState.navigationPillHeight = with(density) { coordinates.size.height.toDp() }
        NavigationBarState.navbarLeftOffset = with(density) { coordinates.positionInRoot().x.toDp() }
      }
      .then(
        if (reducedMotion) Modifier else Modifier.pointerInput(motion, density, isRtl, navigationHaptics) {
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            motion.begin(down.position.x / density.density, down.position.y / density.density)
            var claimed = false
            var finished = false
            try {
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!motion.dragging) { finished = true; break }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1) break
                val delta = change.position - down.position
                if (maxOf(kotlin.math.abs(delta.x), kotlin.math.abs(delta.y)) > viewConfiguration.touchSlop) claimed = true
                if (claimed) change.consume()
                awaitPointerEvent(PointerEventPass.Main)
                if (!motion.dragging) { finished = true; break }
                if (change.isConsumed && !claimed) break
                motion.drag(delta.x / density.density, delta.y / density.density)
                if (!change.pressed) {
                  val target = motion.finish()
                  finished = true
                  val targetTab = visibleTabs[if (isRtl) visibleTabs.lastIndex - target else target]
                  if (targetTab != currentSelectedTab) navigationHaptics.selection(true)
                  currentOnTabSelected(targetTab)
                  change.consume()
                  break
                }
              }
            } finally {
              if (!finished) {
                val restoreIndex = visualIndex(visibleTabs.indexOf(currentSelectedTab).coerceAtLeast(0))
                if (reducedMotion) motion.snapTo(restoreIndex) else motion.select(restoreIndex)
              }
            }
          }
        },
      )

  Box(baseModifier) {
    Box(
      Modifier.matchParentSize().graphicsLayer {
        scaleX = motion.frame.trackScale
        scaleY = motion.frame.trackScale
      }.graphicsLayer {
        val frame = motion.frame
        transformOrigin = TransformOrigin(if (size.width > 0) frame.originX * density.density / size.width else 0.5f, 0.5f)
        scaleX = frame.trackScaleX
        translationY = frame.trackOffsetY * density.density
      }.graphicsLayer {
        translationX = motion.frame.panelOffset * density.density
      },
    ) {
      if (liquidGlassEnabled && kyantBackdrop != null) {
        Box(
          modifier =
            Modifier
              .matchParentSize()
              .drawBackdrop(
                backdrop = kyantBackdrop,
                shape = { Capsule() },
                effects = {
                  liquidGlassEffects(glassSettings, 8.dp.toPx(), 24.dp.toPx(), 24.dp.toPx(), vibrant = true)
                },
                highlight = {
                  glassSettings.highlight(Highlight.Ambient.copy(alpha = 0.58f * glowStrength))
                },
                shadow = {
                  glassSettings.shadow(Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(alpha = 0.14f * glowStrength),
                  ))
                },
                onDrawSurface = {
                  drawRect(glassSettings.surfaceColor(surfaceColor.copy(alpha = 0.34f)))
                },
              ).navigationGlassRim(glowStrength * glassSettings.highlightStrength.coerceAtMost(1f))
              .drawWithContent {
                drawContent()
                drawNavigationJellyGlow(motion.frame, accentColor.copy(alpha = glowStrength))
              },
        )
      } else {
        Box(
          modifier = Modifier.matchParentSize()
            .clip(CircleShape)
            .drawWithContent {
              drawContent()
              drawNavigationJellyGlow(motion.frame, accentColor.copy(alpha = glowStrength))
            },
        ) {
          NavigationGlassSurface(hazeBackdrop, surfaceColor, glowStrength, Modifier.matchParentSize())
        }
      }
      Box(Modifier.matchParentSize().drawWithContent {
        clipPath(navigationJellyPath(motion.frame, visibleTabs.size), ClipOp.Difference) {
          this@drawWithContent.drawContent()
        }
      }) { tabRow(false) }
      Box(Modifier.matchParentSize().drawWithContent {
        val pillPath = navigationJellyPath(motion.frame, visibleTabs.size)
        drawPath(pillPath, selectedSurface)
        clipPath(pillPath) {
          drawNavigationJellyGlow(motion.frame, accentColor.copy(alpha = glowStrength))
          this@drawWithContent.drawContent()
        }
      }) { tabRow(true) }
      Row(Modifier.matchParentSize().padding(horizontal = 4.dp).selectableGroup()) {
        visibleTabs.forEachIndexed { index, tab ->
          Box(
            Modifier.weight(1f).fillMaxHeight()
              .then(if (tab == selectedTab) Modifier.tvInitialFocus(initialFocusRequester) else Modifier)
              .tvFocusHighlight(CircleShape, focusedScale = 1.04f)
              .clip(CircleShape)
              .selectable(
                selected = tab == selectedTab,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
              ) {
                if (tab != selectedTab) navigationHaptics.selection(true)
                if (reducedMotion) motion.snapTo(visualIndex(index)) else motion.select(visualIndex(index))
                onTabSelected(tab)
              }
              .semantics { contentDescription = labels[index] },
          )
        }
      }
    }
  }
}

@Composable
private fun mainNavigationLabel(tab: MainScreen.MainTab): String =
  stringResource(when (tab) {
    MainScreen.MainTab.HOME -> R.string.ui_home
    MainScreen.MainTab.MUSIC -> R.string.ui_music
    MainScreen.MainTab.NETWORK -> R.string.ui_network
    MainScreen.MainTab.JELLYFIN -> R.string.ui_jellyfin
    MainScreen.MainTab.PROFILE -> R.string.ui_profile
  })

@Composable
private fun MainTabIcon(
  tab: MainScreen.MainTab,
  tint: Color,
  contentDescription: String?,
  iconSize: androidx.compose.ui.unit.Dp = MainNavigationIconSize,
) {
  if (tab == MainScreen.MainTab.PROFILE) {
    app.gyrolet.mpvrx.ui.browser.profile.ProfileAvatar(
      size = iconSize,
      tint = tint,
      contentDescription = contentDescription,
    )
    return
  }
  val icon = when (tab) {
    MainScreen.MainTab.HOME -> Icons.RoundedFilled.Home
    MainScreen.MainTab.MUSIC -> Icons.RoundedFilled.Audiotrack
    MainScreen.MainTab.NETWORK -> Icons.RoundedFilled.BringYourOwnIp
    MainScreen.MainTab.JELLYFIN -> null
    MainScreen.MainTab.PROFILE -> Icons.RoundedFilled.AccountCircle
  }
  if (icon == null) {
    androidx.compose.material3.Icon(
      painter = painterResource(R.drawable.ic_jellyfin),
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(iconSize),
    )
  } else {
    Icon(
      icon,
      contentDescription = contentDescription,
      tint = tint,
      modifier = Modifier.size(iconSize),
    )
  }
}

private val MainNavigationIconSize = 28.dp
private val NavigationBarEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Bottom-navigation tab order. Home is the permanent root so Back never exits directly from another
 * tab. The Profile tab absorbs Recents, Playlists and Snapshots, which stay reachable from inside it.
 */
internal fun mainNavigationTabs(
  showMusic: Boolean,
  showProfile: Boolean,
  showNetwork: Boolean,
  showJellyfin: Boolean,
): List<MainScreen.MainTab> =
  buildList {
    add(MainScreen.MainTab.HOME)
    if (showMusic) add(MainScreen.MainTab.MUSIC)
    if (showNetwork) add(MainScreen.MainTab.NETWORK)
    if (showJellyfin) add(MainScreen.MainTab.JELLYFIN)
    if (showProfile) add(MainScreen.MainTab.PROFILE)
  }

val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

// True for a screen hosted as a bottom-navigation page; false when the same screen is pushed on the back stack.
val LocalIsMainTabPage = compositionLocalOf { false }

// CompositionLocal for main navigation bar
val LocalMainNavigationBar =
  compositionLocalOf<@Composable (Modifier) -> Unit> {
    { }
  }
