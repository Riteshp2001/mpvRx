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
      TelegramPillNavigationBar(
        visibleTabs = visibleTabs,
        selectedTab = selectedTab,
        pagerPositionFloatProvider = pagerPositionFloatProvider,
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

    val targetNavBarWidth =
      when {
        isDualPaneFolderSelected && selectedTab == MainTab.HOME ->
          (screenWidth * 0.4f - 24.dp).coerceAtLeast(64.dp)
        isMiniPlayerVisible && (isLandscape || isTablet) ->
          (screenWidth * 0.45f).coerceIn(200.dp, 420.dp)
        else ->
          (screenWidth * 0.9f).coerceAtLeast(64.dp)
      }

    // In landscape/tablet single-pane the nav bar slides to the left edge (with a
    // small margin) so the mini player can sit on its right side.
    val leftAlignedOffset =
      ((12.dp + targetNavBarWidth / 2) / screenWidth)
        .coerceAtLeast(0f)

    val targetOffsetFraction =
      when {
        isDualPaneFolderSelected && selectedTab == MainTab.HOME -> 0.2f
        isMiniPlayerVisible && (isLandscape || isTablet) -> leftAlignedOffset
        else -> 0.5f
      }

    val animatedOffsetFraction by animateFloatAsState(
      targetValue = targetOffsetFraction,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
      label = "nav_bar_position",
    )

    val navBarWidth by animateDpAsState(
      targetValue = targetNavBarWidth,
      animationSpec =
        spring(
          dampingRatio = Spring.DampingRatioNoBouncy,
          stiffness = Spring.StiffnessMediumLow,
        ),
      label = "nav_bar_width",
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
            val targetCenter = containerWidth * animatedOffsetFraction
            val leftPadding = (targetCenter - (navBarWidth / 2)).coerceAtLeast(0.dp)

            // Publish the animated nav bar geometry so the mini player overlay can sit
            // on its right side in landscape/tablet single-pane.
            SideEffect {
              NavigationBarState.navbarLeftOffset = leftPadding
              NavigationBarState.navbarWidth = navBarWidth
            }

            Box(
              modifier =
                Modifier
                  .padding(start = leftPadding)
                  .width(navBarWidth),
              contentAlignment = Alignment.Center,
            ) {
              TelegramPillNavigationBar(
                visibleTabs = visibleTabs,
                selectedTab = selectedTab,
                pagerPositionFloatProvider = pagerPositionFloatProvider,
                onTabSelected = onTabSelected,
                modifier = Modifier.fillMaxWidth(),
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
  pagerPositionFloatProvider: () -> Float,
  onTabSelected: (MainScreen.MainTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  val density = LocalDensity.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

  BoxWithConstraints(modifier = modifier) {
    val totalWidth = maxWidth
    val count = visibleTabs.size.coerceAtLeast(1)
    val horizontalPadding = 6.dp
    val availableWidth = totalWidth - (horizontalPadding * 2)
    val itemWidth = availableWidth / count
    val itemWidthPx = with(density) { itemWidth.toPx() }

    // Below this a label like "Playlists" ellipsizes into a fragment, which reads as clutter
    // rather than information, so drop to icons only and reclaim the vertical space.
    val fitsLabels = itemWidth >= 60.dp
    val labelFraction by animateFloatAsState(
      targetValue = if (fitsLabels) NavigationBarState.navLabelVisibility else 0f,
      animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
      label = "nav_label_fraction",
    )
    val showLabels = labelFraction > 0.01f
    val iconSize = 26.dp - (4.dp * labelFraction)
    val rowHeight = 48.dp + (8.dp * labelFraction)

    Surface(
      modifier = Modifier.fillMaxWidth(),
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
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
      ) {
        // Hardware accelerated sliding active pill background
        if (visibleTabs.isNotEmpty()) {
          Box(
            modifier =
              Modifier
                .width(itemWidth)
                .height(rowHeight)
                .graphicsLayer {
                  val currentPos =
                    pagerPositionFloatProvider().coerceIn(
                      0f,
                      (visibleTabs.size - 1).coerceAtLeast(0).toFloat(),
                    )
                  translationX = if (isRtl) -itemWidthPx * currentPos else itemWidthPx * currentPos
                }.clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
          )
        }

        // Tab Items Layer
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          visibleTabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == tab
            val contentColor =
              if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              }

            Box(
              modifier =
                Modifier
                  .weight(1f)
                  .height(rowHeight)
                  .clip(CircleShape)
                  .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onTabSelected(tab) },
                  ),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
              ) {
                Box(
                  modifier =
                    Modifier.graphicsLayer {
                      val currentPos =
                        pagerPositionFloatProvider().coerceIn(
                          0f,
                          (visibleTabs.size - 1).coerceAtLeast(0).toFloat(),
                        )
                      val dist = kotlin.math.abs(currentPos - index)
                      val prog = (1f - dist).coerceIn(0f, 1f)
                      val scale = 1.0f + 0.10f * prog
                      scaleX = scale
                      scaleY = scale
                    },
                  contentAlignment = Alignment.Center,
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
                }
                Spacer(modifier = Modifier.height(3.dp * labelFraction))
                if (showLabels) {
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
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(labelFraction),
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
