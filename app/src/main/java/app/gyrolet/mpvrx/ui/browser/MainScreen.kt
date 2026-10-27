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
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
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
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
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
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.LocalAppBackgroundBackdrop
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.drawShapeTint
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.tv.RegisterTvContentFocus
import app.gyrolet.mpvrx.ui.tv.TvEdgeToEdgeScreen
import app.gyrolet.mpvrx.ui.tv.TvFocusMemory
import app.gyrolet.mpvrx.ui.tv.TvSafeArea
import app.gyrolet.mpvrx.ui.tv.tryRequestFocus

@Serializable
object MainScreen : Screen, TvEdgeToEdgeScreen {
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
    val legacyNavigationBar by appearancePreferences.legacyNavigationBar.collectAsState()
    val progressiveNavigationBlur by appearancePreferences.progressiveNavigationBlur.collectAsState()
    val appBackgroundBackdrop = LocalAppBackgroundBackdrop.current
    val backdropBackgroundColor = MaterialTheme.colorScheme.background
    val liquidLayerBackdrop = rememberLayerBackdrop {
      // Previews or other hosts without a background capture still need an opaque source.
      if (appBackgroundBackdrop == null) drawRect(backdropBackgroundColor)
      drawContent()
    }
    // Transparent pages contain text without the wallpaper behind it. Composite the real
    // background before blur/refraction so the original sharp text cannot show through.
    val liquidNavigationBackdrop =
      if (appBackgroundBackdrop != null) {
        rememberCombinedBackdrop(appBackgroundBackdrop, liquidLayerBackdrop)
      } else {
        liquidLayerBackdrop
      }
    val hideNavigationBar = NavigationBarState.shouldHideNavigationBar
    val isPermissionDenied = NavigationBarState.isPermissionDenied
    val isDualPaneFolderSelected = NavigationBarState.isDualPaneFolderSelected
    val isMiniPlayerVisible = NavigationBarState.isMiniPlayerVisible

    val bottomNavigationOrder by appearancePreferences.bottomNavigationOrder.collectAsState()
    val visibleTabs =
      remember(showMusicTab, showProfileTab, showNetworkTab, showJellyfinTab, bottomNavigationOrder) {
        mainNavigationTabs(
          showMusic = showMusicTab,
          showProfile = showProfileTab,
          showNetwork = showNetworkTab,
          showJellyfin = showJellyfinTab,
          order = bottomNavigationOrder,
        )
      }
    val navigationTabs = visibleTabs.takeIf { it.size > 1 }.orEmpty()
    val isTelevision = app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(
      androidx.compose.ui.platform.LocalContext.current,
    )
    // The legacy bar spans the bottom edge, so it would ride on top of the keyboard while typing.
    // It leaves as soon as the keyboard starts to open and returns once it has fully closed.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeOpen by remember(imeInsets, density) { derivedStateOf { imeInsets.getBottom(density) > 0 } }
    val showBottomNavigation =
      !isTelevision && !hideNavigationBar && navigationTabs.isNotEmpty() && !isPermissionDenied &&
        !(legacyNavigationBar && isImeOpen)
    val showTvRail = isTelevision && visibleTabs.isNotEmpty() && !isPermissionDenied
    val tvRailFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // TV: follows the settled tab page, so the rail and the screen host can send focus into it.
    val tvContentFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var isTvRailFocused by remember { mutableStateOf(false) }
    var isTvScreenFocused by remember { mutableStateOf(false) }
    RegisterTvContentFocus(tvContentFocusRequester)
    // The exact card that was left when focus went to the rail, else the current tab's first item.
    val focusTvContent: () -> Boolean = {
      TvFocusMemory.restore(MainScreen) || tvContentFocusRequester.tryRequestFocus()
    }

    // Track whether the floating pill nav bar is on screen so the mini player can
    // sit at the very bottom when navigating to screens without it.
    DisposableEffect(Unit) {
      onDispose {
        NavigationBarState.isNavBarVisible = false
        NavigationBarState.isDualPaneLayout = false
        NavigationBarState.dockBackdrop = null
      }
    }
    SideEffect {
      NavigationBarState.isNavBarVisible = backStack.lastOrNull() == MainScreen && showBottomNavigation
      NavigationBarState.dockBackdrop = liquidLayerBackdrop
      NavigationBarState.navigationBarBottomSpacing =
        if (legacyNavigationBar) 0.dp else NavigationBarState.floatingBarSpacing
    }

    val coroutineScope = rememberCoroutineScope()

    val initialPageIndex =
      remember(visibleTabs) {
        visibleTabs.indexOf(persistentSelectedTab).coerceAtLeast(0)
      }

    // The page count and key provider must belong to the same tab snapshot. Reusing the old
    // PagerState while tabs are removed lets its key-index map request an already-removed page.
    val pagerState = key(visibleTabs) {
      rememberPagerState(
        initialPage = initialPageIndex,
        pageCount = { visibleTabs.size },
      )
    }
    var tabNavigationJob by remember { mutableStateOf<Job?>(null) }
    val tabContentAlpha = remember { Animatable(1f) }
    var isDirectTabJumpInProgress by remember { mutableStateOf(false) }

    // Adjacent tabs are pre-composed for instant swipes, but not during startup: composing Music
    // beside Home doubled first-frame work and started its library scan and cover decoding while
    // Home was being scrolled. Pre-compose once the lists have been idle for a moment.
    var adjacentTabsWarm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
      if (adjacentTabsWarm) return@LaunchedEffect
      delay(ADJACENT_TAB_WARMUP_DELAY_MS)
      while (true) {
        val idleFor = android.os.SystemClock.uptimeMillis() - NavigationBarState.lastContentScrollUptimeMs
        if (idleFor >= ADJACENT_TAB_IDLE_MS) break
        delay(ADJACENT_TAB_IDLE_MS - idleFor)
      }
      adjacentTabsWarm = true
    }

    LaunchedEffect(pagerState, visibleTabs) {
      tabNavigationJob?.cancelAndJoin()
      tabNavigationJob = null
      tabContentAlpha.snapTo(1f)
      isDirectTabJumpInProgress = false
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
    // TV: Back from page content first moves focus to the side rail (NuvioTV behaviour); once the
    // rail owns focus, Back falls through to the return-home handler above and then exits.
    BackHandler(enabled = showTvRail && backStack.lastOrNull() == MainScreen && !isTvRailFocused) {
      runCatching { tvRailFocusRequester.requestFocus() }
    }
    val onTvTabSelected: (MainTab) -> Unit = { tab ->
      onTabSelected(tab)
      val pendingJob = tabNavigationJob
      coroutineScope.launch {
        pendingJob?.join()
        // The new tab may still be loading. Retry briefly; if it never gets anything focusable,
        // or the user moves on first, focus simply stays where it is.
        repeat(TV_TAB_FOCUS_ATTEMPTS) {
          withFrameNanos { }
          if (!isTvRailFocused || tvContentFocusRequester.tryRequestFocus()) return@launch
          delay(TV_TAB_FOCUS_RETRY_MS)
        }
      }
    }
    // Focus must never be left on nothing: if a tab change took the focused item with it, move
    // into the tab that is now showing. Opening the screen is left to the screen host.
    LaunchedEffect(isTelevision, pagerState) {
      if (!isTelevision) return@LaunchedEffect
      snapshotFlow { pagerState.settledPage }
        .drop(1)
        .collect {
          withFrameNanos { }
          if (!isTvScreenFocused && !isTvRailFocused && backStack.lastOrNull() == MainScreen) focusTvContent()
        }
    }

    // Keep the app's established Haze path for the normal navigation bar. Liquid Glass uses the
    // separate Kyant layer backdrop declared with the screen state above.
    val navigationBackdrop = rememberHazeState()

    val mainNavBar = @Composable { modifier: Modifier ->
      if (legacyNavigationBar) {
        LegacyMaterialNavigationBar(
          visibleTabs = navigationTabs,
          selectedTab = selectedTab,
          onTabSelected = onTabSelected,
          modifier = modifier,
        )
      } else {
        ExpressivePillNavigationBar(
          visibleTabs = navigationTabs,
          selectedTab = selectedTab,
          onTabSelected = onTabSelected,
          pagerState = pagerState,
          hazeBackdrop = navigationBackdrop,
          kyantBackdrop = liquidNavigationBackdrop,
          liquidGlassEnabled = liquidGlassEnabled,
          modifier = modifier,
        )
      }
    }

    val isDualPaneLayout = !legacyNavigationBar && !isPermissionDenied &&
      isDualPaneFolderSelected && selectedTab == MainTab.HOME
    SideEffect {
      NavigationBarState.isDualPaneLayout = backStack.lastOrNull() == MainScreen && isDualPaneLayout
    }
    val contentBottomPadding =
      when {
        // Lists still scroll to the screen edge, but their last row rests above the overscan
        // margin, the selection bar and the mini player card.
        isTelevision -> TvSafeArea.Vertical + NavigationBarState.bottomOverlayClearance
        isDualPaneLayout -> NavigationBarState.bottomBarClearance
        else -> NavigationBarState.bottomOverlayClearance
      }
    // TV: reserve the rail on the start edge, but avoid wasting a full overscan gutter on
    // the trailing edge. TV lists reserve their own clearance for scaled focus highlights.
    val tvContentPadding =
      if (isTelevision) {
        PaddingValues(
          start = if (showTvRail) TvSideNavigationDefaults.CollapsedWidth else TvSafeArea.Horizontal,
          top = TvSafeArea.Vertical,
          end = 12.dp,
        )
      } else {
        PaddingValues(0.dp)
      }
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
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .onFocusChanged { isTvScreenFocused = it.hasFocus },
      ) {
        if (visibleTabs.isEmpty()) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .padding(tvContentPadding)
                .then(
                    when {
                      liquidGlassEnabled -> Modifier.layerBackdrop(liquidLayerBackdrop)
                      !legacyNavigationBar -> Modifier.hazeSource(navigationBackdrop)
                      else -> Modifier
                    },
                  ),
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
                  .padding(tvContentPadding)
                  .clipToBounds()
                  .graphicsLayer { alpha = tabContentAlpha.value }
                  .nestedScroll(NavigationBarState.navScrollConnection)
                  .then(
                    when {
                      liquidGlassEnabled -> Modifier.layerBackdrop(liquidLayerBackdrop)
                      !legacyNavigationBar -> Modifier.hazeSource(navigationBackdrop)
                      else -> Modifier
                    },
                  ),
              key = { page -> visibleTabs.getOrNull(page)?.name ?: "removed-tab-$page" },
              beyondViewportPageCount = if (adjacentTabsWarm) 1 else 0,
              userScrollEnabled = !isTelevision && !isPermissionDenied && !isDirectTabJumpInProgress,
              activePageFocusRequester = tvContentFocusRequester.takeIf { isTelevision },
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

        if (showTvRail) {
          TvSideNavigation(
            tabs = visibleTabs,
            selectedTab = selectedTab,
            onTabSelected = onTvTabSelected,
            onOpenSettings = { backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen) },
            selectedItemFocusRequester = tvRailFocusRequester,
            onRailFocusChanged = { isTvRailFocused = it },
            onFocusContent = focusTvContent,
            modifier = Modifier.align(Alignment.CenterStart),
          )
        }

        // Animated bottom navigation bar with slide animations
        ProvideLiquidGlassBackdrop(
          backdrop = liquidLayerBackdrop,
          enabled = liquidGlassEnabled,
        ) {
          AnimatedVisibility(
            visible = showBottomNavigation,
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
            Box(Modifier.fillMaxWidth()) {
              // The safe drawing area without the keyboard: the legacy bar leaves while the
              // keyboard is open, so it stays at the bottom edge instead of rising with it.
              val legacyBarInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
              // The legacy bar is edge-to-edge, but its controls must stay above the system
              // navigation area. Paint that bottom inset with the same surface as the bar
              // so content cannot peek through below it (gesture and 3-button modes).
              if (legacyNavigationBar) {
                Spacer(
                  modifier =
                    Modifier
                      .align(Alignment.BottomCenter)
                      .fillMaxWidth()
                      .windowInsetsBottomHeight(legacyBarInsets)
                      .background(NavigationBarDefaults.containerColor),
                )
              }
              Box(Modifier.matchParentSize()) {
                // Haze only runs while its navigation surface is visible.
                if (!liquidGlassEnabled && progressiveNavigationBlur && !legacyNavigationBar) {
                  ProgressiveNavigationBackdrop(
                    state = navigationBackdrop,
                    modifier =
                      Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (isDualPaneFolderSelected && selectedTab == MainTab.HOME) 0.4f else 1f)
                        .align(Alignment.BottomStart),
                  )
                }
              }
              BoxWithConstraints(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                      (if (legacyNavigationBar) legacyBarInsets else WindowInsets.safeDrawing)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .padding(
                      top = 24.dp,
                      bottom = if (legacyNavigationBar) 0.dp else NavigationBarState.floatingBarSpacing,
                    ),
              ) {
                if (legacyNavigationBar) {
                  LegacyMaterialNavigationBar(
                    visibleTabs = navigationTabs,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    modifier = Modifier.fillMaxWidth(),
                  )
                } else {
                  val horizontalMargin by animateDpAsState(
                    targetValue = if (NavigationBarState.isMiniPlayerDocked) NavigationBarState.floatingBarSpacing
                      else NavigationBarState.navigationPillMaxMargin - 30.dp * NavigationBarState.navLabelVisibility,
                    animationSpec = if (navStyle == NavigationAnimStyle.None) snap() else tween(300, easing = NavigationBarEasing),
                    label = "navigation_margin",
                  )
                  val centerFraction = animateFloatAsState(
                    targetValue = when {
                      isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
                      NavigationBarState.isMiniPlayerDocked -> 0.5f
                      isMiniPlayerVisible && NavigationBarState.canPlaceMiniPlayerBesideNavigation(maxWidth) -> 0f
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
                      val maxNuvioWidth = minOf(NavigationBarState.navigationPillMaxWidth.roundToPx(), availableWidth)
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
                    kyantBackdrop = liquidNavigationBackdrop,
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
  }
}

@Composable
private fun ProgressiveNavigationBackdrop(
  state: HazeState,
  modifier: Modifier = Modifier,
) {
  val surfaceColor = MaterialTheme.colorScheme.surface
  BoxWithConstraints(modifier = modifier) {
    val heightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
    val blurStyle = remember(surfaceColor, heightPx) {
      HazeBlurStyle {
        blurRadius(28.dp)
        noiseFactor(0f)
        backgroundColor(surfaceColor)
        colorEffects(listOf(HazeColorEffect.tint(surfaceColor.copy(alpha = 0.16f))))
        fallbackColorEffect(HazeColorEffect.tint(surfaceColor.copy(alpha = 0.85f)))
        progressive(HazeProgressive.verticalGradient(endY = heightPx * 0.75f))
        mask(
          Brush.verticalGradient(
            0f to Color.Transparent,
            0.5f to Color.Black,
            1f to Color.Black,
          ),
        )
      }
    }
    Box(Modifier.fillMaxSize().hazeBlur(input = HazeInput.Sources(state), style = blurStyle))
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
  val dockProgress = NavigationBarState.dockProgress
  val standaloneCorner = (48.dp + labelHeight * labelFraction) / 2f
  val joiningCorner = standaloneCorner * (1f - dockProgress) + NavigationBarState.connectedDockCorner * dockProgress
  val outsideCorner = standaloneCorner * (1f - dockProgress) + 20.dp * dockProgress
  val navigationShape = if (dockProgress <= 0f) CircleShape else RoundedCornerShape(
    topStart = joiningCorner,
    topEnd = joiningCorner,
    bottomEnd = outsideCorner,
    bottomStart = outsideCorner,
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
      .widthIn(max = NavigationBarState.navigationPillMaxWidth)
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
                shape = { navigationShape },
                effects = {
                  liquidGlassEffects(
                    glassSettings, 8.dp.toPx(), 24.dp.toPx() * (1f - dockProgress), 24.dp.toPx() * (1f - dockProgress),
                    vibrant = true,
                    refractionEnabled = !reducedMotion && dockProgress < 1f,
                  )
                },
                highlight = {
                  glassSettings.highlight(Highlight.Ambient.copy(alpha = 0.58f * glowStrength * (1f - dockProgress)))
                },
                shadow = {
                  Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(alpha = 0.14f * glowStrength * (1f - dockProgress)),
                  )
                },
                onDrawSurface = {
                  drawShapeTint(navigationShape, glassSettings.surfaceColor(surfaceColor.copy(alpha = 0.34f)))
                },
              ).navigationGlassRim(glowStrength * glassSettings.highlightStrength.coerceAtMost(1f), navigationShape, dockProgress)
              .drawWithContent {
                drawContent()
                drawNavigationJellyGlow(motion.frame, accentColor.copy(alpha = glowStrength))
              },
        )
      } else {
        Box(
          modifier = Modifier.matchParentSize()
            .clip(navigationShape)
            .drawWithContent {
              drawContent()
              drawNavigationJellyGlow(motion.frame, accentColor.copy(alpha = glowStrength))
            },
        ) {
          NavigationGlassSurface(hazeBackdrop, surfaceColor, glowStrength, Modifier.matchParentSize(), navigationShape, dockProgress)
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
internal fun mainNavigationLabel(tab: MainScreen.MainTab): String =
  stringResource(when (tab) {
    MainScreen.MainTab.HOME -> R.string.ui_home
    MainScreen.MainTab.MUSIC -> R.string.ui_music
    MainScreen.MainTab.NETWORK -> R.string.ui_network
    MainScreen.MainTab.JELLYFIN -> R.string.ui_jellyfin
    MainScreen.MainTab.PROFILE -> R.string.ui_profile
  })

@Composable
private fun LegacyMaterialNavigationBar(
  visibleTabs: List<MainScreen.MainTab>,
  selectedTab: MainScreen.MainTab,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  NavigationBar(
    windowInsets = WindowInsets(0),
    modifier =
      modifier.onGloballyPositioned { coordinates ->
        NavigationBarState.navbarWidth = with(density) { coordinates.size.width.toDp() }
        NavigationBarState.navigationPillHeight = with(density) { coordinates.size.height.toDp() }
        NavigationBarState.navbarLeftOffset = with(density) { coordinates.positionInRoot().x.toDp() }
      },
  ) {
    visibleTabs.forEach { tab ->
      NavigationBarItem(
        selected = tab == selectedTab,
        onClick = { onTabSelected(tab) },
        icon = {
          MainTabIcon(
            tab = tab,
            tint = LocalContentColor.current,
            contentDescription = null,
          )
        },
        label = { Text(mainNavigationLabel(tab)) },
      )
    }
  }
}

@Composable
internal fun MainTabIcon(
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
private const val ADJACENT_TAB_WARMUP_DELAY_MS = 2_500L
private const val ADJACENT_TAB_IDLE_MS = 1_200L
private const val TV_TAB_FOCUS_ATTEMPTS = 6
private const val TV_TAB_FOCUS_RETRY_MS = 100L

/**
 * Bottom-navigation tab order. Home is the permanent root so Back never exits directly from another
 * tab. The Profile tab absorbs Recents, Playlists and Snapshots, which stay reachable from inside it.
 */
internal fun mainNavigationTabs(
  showMusic: Boolean,
  showProfile: Boolean,
  showNetwork: Boolean,
  showJellyfin: Boolean,
  order: List<String> = emptyList(),
): List<MainScreen.MainTab> {
  val available = buildList {
    add(MainScreen.MainTab.HOME)
    if (showMusic) add(MainScreen.MainTab.MUSIC)
    if (showNetwork) add(MainScreen.MainTab.NETWORK)
    if (showJellyfin) add(MainScreen.MainTab.JELLYFIN)
    if (showProfile) add(MainScreen.MainTab.PROFILE)
  }
  val byName = available.associateBy { it.name }
  return listOf(MainScreen.MainTab.HOME) +
    (order.mapNotNull(byName::get) + available)
      .distinct()
      .filterNot { it == MainScreen.MainTab.HOME }
}

// Content clearance above the visible app bars, excluding system insets.
val LocalNavigationBarHeight = compositionLocalOf { 0.dp }

// True for a screen hosted as a bottom-navigation page; false when the same screen is pushed on the back stack.
val LocalIsMainTabPage = compositionLocalOf { false }

// CompositionLocal for main navigation bar
val LocalMainNavigationBar =
  compositionLocalOf<@Composable (Modifier) -> Unit> {
    { }
  }
