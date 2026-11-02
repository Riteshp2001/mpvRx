/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSearchCategory
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionGridGap
import app.gyrolet.mpvrx.ui.browser.cards.mediaSelectionNeighbors
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.JellyfinSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.MusicSortDialog
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.fab.FabPlacement
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.tv.TvScreenFocusHost
import app.gyrolet.mpvrx.ui.tv.isTelevision
import app.gyrolet.mpvrx.ui.tv.tryRequestFocus
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.utils.LocalNavigationPageActive
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.rememberTabNavigation
import app.gyrolet.mpvrx.ui.tv.focusableWhen
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinContent(
  viewModel: JellyfinViewModel,
  modifier: Modifier = Modifier,
  isMusicOnlyMode: Boolean = false,
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val isTelevision = isTelevision()
  val contentFocusRequester = remember { FocusRequester() }
  var musicTabsFocused by remember { mutableStateOf(false) }
  val backstack = LocalBackStack.current
  val browserPreferences = koinInject<BrowserPreferences>()
  val mediaServerPreferences = koinInject<MediaServerPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val currentMusicSource by mediaServerPreferences.musicSourceProvider.collectAsState()
  val navidromeRepository = koinInject<app.gyrolet.mpvrx.repository.NavidromeRepository>()
  val navidromeServers by navidromeRepository.allServers.collectAsState(initial = emptyList())
  val layoutMode by browserPreferences.jellyfinLayoutMode.collectAsState()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()

  val allDownloads by viewModel.downloads.collectAsState()
  val activeSnapshot by viewModel.activeSnapshot.collectAsState(initial = null)
  val downloadedItemIds =
    remember(allDownloads) {
      allDownloads.filter { it.isPlayable }.mapNotNull { it.entity.jellyfinItemId }.toSet()
    }
  val activeDownloadItemIds =
    remember(allDownloads) {
      allDownloads.filter { it.isActive }.mapNotNull { it.entity.jellyfinItemId }.toSet()
    }
  val downloadProgressMap =
    remember(allDownloads, activeSnapshot) {
      val map = mutableMapOf<String, Float>()
      for (dl in allDownloads) {
        val itemId = dl.entity.jellyfinItemId ?: continue
        if (dl.isActive) {
          val progress = if (activeSnapshot?.id == dl.id && (activeSnapshot?.totalBytes ?: 0L) > 0L) {
            (activeSnapshot?.progress ?: 0) / 100f
          } else {
            (dl.entity.progress / 100f).coerceIn(0f, 1f)
          }
          map[itemId] = progress
        }
      }
      map
    }

  val detailItem = uiState.detailItem
  val seriesDownloads =
    remember(allDownloads, detailItem) {
      if (detailItem?.isSeries == true) {
        allDownloads.filter {
          (it.entity.jellyfinItemId == detailItem.id || it.entity.jellyfinSeriesName == detailItem.name) &&
            (it.isActive || it.isCompleted)
        }
      } else emptyList()
    }
  val isSeriesDownloading = remember(seriesDownloads) { seriesDownloads.any { it.isActive } }
  val seriesProgress =
    remember(seriesDownloads, activeSnapshot) {
      if (seriesDownloads.isEmpty()) 0f
      else {
        val total = seriesDownloads.sumOf { dl ->
          if (dl.isCompleted) 1.0
          else if (activeSnapshot?.id == dl.id && (activeSnapshot?.totalBytes ?: 0L) > 0L) {
            ((activeSnapshot?.progress ?: 0) / 100.0)
          } else {
            (dl.entity.progress / 100.0)
          }
        }
        (total / seriesDownloads.size).toFloat().coerceIn(0f, 1f)
      }
    }

  var isAddDialogOpen by remember { mutableStateOf(false) }
  var serverToReauth by remember { mutableStateOf<JellyfinServer?>(null) }
  var isManageServersOpen by rememberSaveable { mutableStateOf(false) }
  var isSearching by rememberSaveable { mutableStateOf(false) }
  var isSeerrRequestsOpen by rememberSaveable { mutableStateOf(false) }
  var isSortDialogOpen by rememberSaveable { mutableStateOf(false) }
  var isFabExpanded by remember { mutableStateOf(false) }
  val isFabVisible = remember { mutableStateOf(true) }
  val searchFocusRequester = remember { FocusRequester() }

  val seerrViewModel: app.gyrolet.mpvrx.ui.browser.jellyfin.seerr.SeerrViewModel =
    androidx.lifecycle.viewmodel.compose.viewModel(
      factory =
        app.gyrolet.mpvrx.ui.browser.jellyfin.seerr.SeerrViewModel.factory(
          context.applicationContext as android.app.Application,
        ),
    )

  val musicTabs = remember {
    listOf(
      JellyfinMusicTab.HOME,
      JellyfinMusicTab.TRACKS,
      JellyfinMusicTab.ALBUMS,
      JellyfinMusicTab.ARTISTS,
      JellyfinMusicTab.PLAYLISTS,
    )
  }
  val musicPagerState = rememberPagerState(
    initialPage = musicTabs.indexOf(uiState.musicActiveTab).coerceAtLeast(0),
    pageCount = { musicTabs.size },
  )
  val navigateMusicTab = rememberTabNavigation(musicPagerState)

  LaunchedEffect(musicPagerState.settledPage, musicPagerState.isScrollInProgress, musicTabs) {
    if (!musicPagerState.isScrollInProgress) {
      musicTabs.getOrNull(musicPagerState.settledPage)?.let { tab ->
        if (uiState.musicActiveTab != tab) {
          viewModel.setMusicTab(tab)
        }
      }
    }
  }

  LaunchedEffect(uiState.musicActiveTab, musicTabs) {
    val targetIndex = musicTabs.indexOf(uiState.musicActiveTab)
    if (targetIndex >= 0) {
      navigateMusicTab(targetIndex)
    }
  }

  val homeListState = rememberLazyListState()
  val libraryListState = remember(uiState.openLibrary, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) { LazyListState() }
  val libraryGridState = remember(uiState.openLibrary, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) { LazyGridState() }

  if (uiState.openLibrary == null) {
    FabScrollHelper.trackScrollForFabVisibility(
      listState = homeListState,
      gridState = null,
      isFabVisible = isFabVisible,
      expanded = isFabExpanded,
      onExpandedChange = { isFabExpanded = it },
    )
  } else {
    FabScrollHelper.trackScrollForFabVisibility(
      listState = libraryListState,
      gridState = if (layoutMode == MediaLayoutMode.GRID) libraryGridState else null,
      isFabVisible = isFabVisible,
      expanded = isFabExpanded,
      onExpandedChange = { isFabExpanded = it },
    )
  }

  val selectionManager =
    rememberSelectionManager(
      items = uiState.currentItems,
      getId = { it.id },
      onDeleteItems = { selectedItems: List<app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem>, _ ->
        val count = selectedItems.size
        viewModel.deleteItems(selectedItems.map { it.id })
        Pair(count, 0)
      },
    )

  // Clear selection when navigating into or out of a folder
  LaunchedEffect(uiState.openLibrary) {
    selectionManager.clear()
  }

  // Clear selection and close detail when active server changes
  LaunchedEffect(uiState.activeServer) {
    selectionManager.clear()
    if (uiState.detailItem != null) {
      viewModel.closeDetail()
    }
  }

  app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect(selectionManager.isInSelectionMode)

  // Intercept back button if searching, selecting, requests open, details open, or browsing inside a folder
  val isBackEnabled =
    if (isMusicOnlyMode) {
      isSearching || selectionManager.isInSelectionMode || uiState.detailItem != null ||
        uiState.personDetail != null ||
        uiState.musicActiveTab != JellyfinMusicTab.HOME ||
        (isFabExpanded && !quickPlayFabDirect)
    } else {
      isSeerrRequestsOpen || isSearching || selectionManager.isInSelectionMode ||
        uiState.detailItem != null || uiState.personDetail != null || uiState.openLibrary != null || (isFabExpanded && !quickPlayFabDirect)
    }

  BackHandler(
    enabled = isBackEnabled,
  ) {
    when {
      isFabExpanded && !quickPlayFabDirect -> {
        isFabExpanded = false
      }
      uiState.personDetail != null -> {
        viewModel.closePerson()
      }
      uiState.detailItem != null -> {
        viewModel.closeDetail()
      }
      isSeerrRequestsOpen -> {
        isSeerrRequestsOpen = false
      }
      isSearching -> {
        isSearching = false
        viewModel.onSearchQueryChanged("")
        viewModel.refresh()
      }
      selectionManager.isInSelectionMode -> {
        selectionManager.clear()
      }
      isMusicOnlyMode -> {
        if (uiState.musicActiveTab != JellyfinMusicTab.HOME) {
          viewModel.setMusicTab(JellyfinMusicTab.HOME)
        }
      }
      else -> {
        viewModel.navigateBack()
      }
    }
  }

  if (isSeerrRequestsOpen) {
    app.gyrolet.mpvrx.ui.browser.jellyfin.seerr.SeerrContent(
      viewModel = seerrViewModel,
      activeJellyfinServer = uiState.activeServer,
      onBackClick = { isSeerrRequestsOpen = false },
      onOpenJellyfinItem = { itemId ->
        isSeerrRequestsOpen = false
        viewModel.openDetailById(itemId)
      },
      modifier = modifier,
    )
    return
  }

  LaunchedEffect(isSearching) {
    if (isSearching) {
      searchFocusRequester.requestFocus()
    }
  }

  val isMusicMode = isMusicOnlyMode || uiState.openLibrary?.isMusic == true

  val pageTitle =
    when {
      isMusicOnlyMode -> stringResource(R.string.ui_music)
      uiState.openLibrary != null -> uiState.openLibrary!!.title
      uiState.activeServer != null -> uiState.activeServer!!.name
      else -> stringResource(R.string.ui_jellyfin)
    }

  val density = LocalDensity.current
  var topBarHeight by remember { mutableStateOf(0.dp) }
  val gutter = jellyfinGutter()

  val isWallpaperActive = app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive.current
  val scrimBaseColor =
    if (MaterialTheme.colorScheme.background == Color.Black) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surface
    }

  val headerContainerColor =
    if (isWallpaperActive) {
      Color.Transparent
    } else if (MaterialTheme.colorScheme.background == Color.Black) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  val headerGradient =
    remember(isWallpaperActive, scrimBaseColor) {
      if (isWallpaperActive) {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
      } else {
        Brush.verticalGradient(
          0.0f to scrimBaseColor.copy(alpha = 0.85f),
          0.6f to scrimBaseColor.copy(alpha = 0.45f),
          1.0f to Color.Transparent,
        )
      }
    }

  val solidAlpha by remember {
    derivedStateOf {
      if (isWallpaperActive) {
        0f
      } else if (!isMusicOnlyMode && uiState.openLibrary == null && uiState.searchQuery.isBlank() && uiState.heroItems.isNotEmpty()) {
        if (homeListState.firstVisibleItemIndex > 0) {
          1f
        } else {
          val scrollOffset = homeListState.firstVisibleItemScrollOffset.toFloat()
          val startOffset = with(density) { 100.dp.toPx() }
          val endOffset = with(density) { 300.dp.toPx() }
          ((scrollOffset - startOffset) / (endOffset - startOffset)).coerceIn(0f, 1f)
        }
      } else {
        1f
      }
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor()),
  ) {
    // Main Body Content with Pull-To-Refresh and FAB / Multi-select overlays
    val isRefreshing = remember { mutableStateOf(false) }
    val navigationBarHeight = LocalNavigationBarHeight.current

    TvScreenFocusHost(
      scope = listOf("jellyfin", uiState.activeServer?.id, uiState.openLibrary?.id, isMusicOnlyMode, uiState.musicActiveTab),
      safeArea = false,
      active = LocalNavigationPageActive.current && !isSearching && !isAddDialogOpen &&
        !isManageServersOpen && !isSortDialogOpen && !isFabExpanded && uiState.detailItem == null && uiState.personDetail == null,
      autoFocus = !musicTabsFocused,
      focusRequestKey = uiState.isLoading to uiState.isMusicLoading,
      modifier = Modifier.tvInitialFocus(contentFocusRequester),
    ) {
    PullRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = { viewModel.refreshSuspend() },
      modifier = Modifier.fillMaxSize(),
    ) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        when {
          // No servers configured
          uiState.servers.isEmpty() -> {
            EmptyServersView(
              onAddClick = { isAddDialogOpen = true },
              modifier = Modifier.padding(top = topBarHeight),
            )
          }

          // Loading state (initial)
          uiState.isLoading && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() && uiState.heroItems.isEmpty() -> {
            LoadingIndicator(modifier = Modifier.padding(top = topBarHeight).size(72.dp))
          }

          // Error state
          uiState.error != null && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() -> {
            ErrorView(
              message = uiState.error ?: "An error occurred",
              onRetry = { viewModel.refresh() },
              onReauthenticate = {
                serverToReauth = uiState.activeServer
                isAddDialogOpen = true
              },
              modifier = Modifier.padding(top = topBarHeight),
            )
          }

          // Root / Discovery Home View (Expressive UI)
          !isMusicOnlyMode && uiState.openLibrary == null && uiState.searchQuery.isBlank() -> {
            val server = uiState.activeServer

            if (server != null) {
              Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                  state = homeListState,
                  modifier = Modifier.fillMaxSize().tvContentFocus(),
                  contentPadding = PaddingValues(
                    top = topBarHeight + 8.dp,
                    bottom = navigationBarHeight + 96.dp,
                  ),
                  verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.SectionGap),
                ) {
                  // 1. Hero Featured Carousel Banner
                  if (uiState.heroItems.isNotEmpty()) {
                    item {
                      JellyfinHeroBanner(
                        autoAdvance = uiState.detailItem == null && !selectionManager.isInSelectionMode &&
                          !isSearching && !isAddDialogOpen && !isManageServersOpen && !isSortDialogOpen && !isSeerrRequestsOpen,
                        items = uiState.heroItems,
                        server = server,
                        onPlay = { item ->
                          if (item.isSeries || item.isFolder || item.isSeason) {
                            viewModel.openDetail(item)
                          } else {
                            viewModel.playItem(context, item)
                          }
                        },
                        onDetails = { item -> viewModel.openDetail(item) },
                      )
                    }
                  }

                  // 2. Libraries Section (above Continue Watching)
                  val homeLibraries = uiState.libraries.filter { library ->
                    library.collectionType?.equals("playlists", ignoreCase = true) != true &&
                      !library.name.equals("playlists", ignoreCase = true) &&
                      library.type != "PlaylistsFolder"
                  }
                  if (homeLibraries.isNotEmpty()) {
                    item {
                      Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.HeaderGap),
                      ) {
                        JellyfinSectionHeader(
                          title = stringResource(R.string.jellyfin_libraries),
                        )

                        JellyfinLibrariesCarousel(
                          libraries = homeLibraries,
                          server = server,
                          onOpen = { library -> viewModel.navigateToItem(library) },
                        )
                      }
                    }
                  }

                  // 3. Continue Watching Section
                  if (uiState.resumeItems.isNotEmpty()) {
                    item {
                      JellyfinHorizontalSection(
                        title = stringResource(R.string.jellyfin_continue_watching),
                        items = uiState.resumeItems,
                        server = server,
                        isContinueWatching = true,
                        onItemClick = { item -> viewModel.playItem(context, item) },
                        onItemLongClick = { item -> viewModel.openDetail(item) },
                      )
                    }
                  }

                  // 4. Per-Library Latest Sections (e.g. Latest Movies, Latest Shows, etc.)
                  if (uiState.librarySections.isNotEmpty()) {
                    items(uiState.librarySections, key = { "lib_section_${it.library.id}" }) { section ->
                      JellyfinHorizontalSection(
                        title = section.title,
                        subtitle = section.subtitle,
                        items = section.items,
                        server = server,
                        onItemClick = { item -> viewModel.openDetail(item) },
                        onItemLongClick = { item -> viewModel.playItem(context, item) },
                        onSeeAll = {
                          viewModel.navigateToItem(section.library)
                        },
                      )
                    }
                  } else {
                    // Fallback: Legacy Latest Movies Section
                    if (uiState.latestMovies.isNotEmpty()) {
                      item {
                        JellyfinHorizontalSection(
                          title = stringResource(R.string.jellyfin_latest_movies),
                          subtitle = stringResource(R.string.seerr_recently_added),
                          items = uiState.latestMovies,
                          server = server,
                          onItemClick = { item -> viewModel.openDetail(item) },
                          onItemLongClick = { item -> viewModel.playItem(context, item) },
                          onSeeAll = {
                            val movieLib = uiState.libraries.find { it.collectionType?.equals("movies", ignoreCase = true) == true }
                            if (movieLib != null) viewModel.navigateToItem(movieLib)
                          },
                        )
                      }
                    }

                    // Fallback: Legacy Latest TV Shows Section
                    if (uiState.latestShows.isNotEmpty()) {
                      item {
                        JellyfinHorizontalSection(
                          title = stringResource(R.string.jellyfin_latest_series),
                          items = uiState.latestShows,
                          server = server,
                          onItemClick = { item -> viewModel.openDetail(item) },
                          onItemLongClick = { item -> viewModel.playItem(context, item) },
                          onSeeAll = {
                            val tvLib = uiState.libraries.find { it.collectionType?.equals("tvshows", ignoreCase = true) == true }
                            if (tvLib != null) viewModel.navigateToItem(tvLib)
                          },
                        )
                      }
                    }
                  }

                  // 6. Recommended For You Section
                  if (uiState.recommendations.isNotEmpty()) {
                    item {
                      JellyfinHorizontalSection(
                        title = stringResource(R.string.jellyfin_top_picks),
                        subtitle = stringResource(R.string.jellyfin_top_picks_subtitle),
                        items = uiState.recommendations,
                        server = server,
                        onItemClick = { item -> viewModel.openDetail(item) },
                        onItemLongClick = { item -> viewModel.playItem(context, item) },
                      )
                    }
                  }

                  // 7. Music Section
                  if (uiState.latestMusic.isNotEmpty()) {
                    item {
                      Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.HeaderGap),
                      ) {
                        JellyfinSectionHeader(
                          title = stringResource(R.string.ui_music),
                          subtitle = stringResource(R.string.jellyfin_music_subtitle),
                          onSeeAll = {
                            val musicLib = uiState.libraries.find { it.collectionType?.equals("music", ignoreCase = true) == true }
                            if (musicLib != null) viewModel.navigateToItem(musicLib)
                          },
                        )

                        LazyRow(
                          horizontalArrangement = Arrangement.spacedBy(JellyfinSpacing.RowItemGap),
                          contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
                        ) {
                          items(uiState.latestMusic, key = { it.id }) { item ->
                            JellyfinMusicCard(
                              item = item,
                              server = server,
                              onClick = {
                                if (item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist" || item.type == "MusicAlbum" || item.type == "Album" || item.type == "Playlist") {
                                  viewModel.openDetail(item)
                                } else {
                                  viewModel.playItem(context, item)
                                }
                              },
                              onLongClick = {
                                if (item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist" || item.type == "MusicAlbum" || item.type == "Album" || item.type == "Playlist") {
                                  viewModel.playItem(context, item)
                                } else {
                                  viewModel.openDetail(item)
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
            }
          }

          // Level / Search View: Inside a Library / Folder / Season / Search results
          else -> {
            val openLib = uiState.openLibrary ?: if (isMusicOnlyMode) viewModel.getMusicLibraryView() else null
            if ((isMusicOnlyMode || openLib?.isMusic == true) && uiState.searchQuery.isBlank() && uiState.activeServer != null) {
              JellyfinMusicView(
                uiState = uiState,
                server = uiState.activeServer!!,
                pagerState = musicPagerState,
                visibleTabs = musicTabs,
                onTabSelected = viewModel::setMusicTab,
                onItemClick = { item ->
                  if (selectionManager.isInSelectionMode) {
                    selectionManager.toggleFromUser(item)
                  } else if (item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist" || item.type == "MusicAlbum" || item.type == "Album" || item.type == "Playlist") {
                    viewModel.openDetail(item)
                  } else if (item.isFolder || item.type == "CollectionFolder") {
                    viewModel.navigateToItem(item)
                  } else {
                    viewModel.playItem(context, item)
                  }
                },
                onItemLongClick = { selectionManager.handleLongClick(it) },
                navigationBarHeight = navigationBarHeight,
                modifier = Modifier.padding(top = topBarHeight),
              )
            } else {
              val items = uiState.currentItems
              val allEpisodes = items.isNotEmpty() && items.all { it.type == "Episode" }
              val isListMode = layoutMode == MediaLayoutMode.LIST || allEpisodes

              if (items.isEmpty() && !uiState.isLoading) {
                val query = uiState.searchQuery
                EmptyState(
                  icon = if (query.isNotBlank()) Icons.RoundedFilled.Search else Icons.RoundedFilled.Movie,
                  title = stringResource(if (query.isNotBlank()) R.string.ui_no_results_found else R.string.ui_empty_folder),
                  message =
                    if (query.isNotBlank()) {
                      stringResource(R.string.jellyfin_no_results_message, query)
                    } else {
                      stringResource(R.string.jellyfin_empty_folder_message)
                    },
                  modifier = Modifier.padding(top = topBarHeight, bottom = navigationBarHeight),
                )
              } else if (isListMode) {
                val listState = libraryListState
                val hasEnoughItems = items.size > 6
                val scrollbarAlpha by animateFloatAsState(
                  targetValue = if (hasEnoughItems) 1f else 0f,
                  label = "scrollbarAlpha",
                )

                val shouldLoadMore =
                  remember {
                    derivedStateOf {
                      val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                      items.isNotEmpty() && lastVisibleIndex >= items.size - 5
                    }
                  }

                LaunchedEffect(shouldLoadMore.value) {
                  if (shouldLoadMore.value && uiState.hasMore && !uiState.isLoading && !uiState.isLoadingMore) {
                    viewModel.loadMoreItems()
                  }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                  LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().tvContentFocus(),
                    // Symmetric gutters; the fast-scroll bar floats over the end gutter.
                    contentPadding = PaddingValues(
                      start = gutter,
                      end = gutter,
                      top = topBarHeight + 8.dp,
                      bottom = navigationBarHeight + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.ListItemGap),
                  ) {
                    items(items, key = { it.id }) { item ->
                      uiState.activeServer?.let { server ->
                        if (allEpisodes || item.type == "Episode") {
                          JellyfinEpisodeCard(
                            item = item,
                            server = server,
                            onPlay = {
                              if (selectionManager.isInSelectionMode) {
                                selectionManager.toggleFromUser(item)
                              } else {
                                viewModel.playItem(context, item)
                              }
                            },
                            onLongClick = { selectionManager.handleLongClick(item) },
                            isSelected = selectionManager.isSelected(item),
                            downloadState =
                              when {
                                item.id in downloadedItemIds -> EpisodeDownloadState.DOWNLOADED
                                item.id in activeDownloadItemIds -> EpisodeDownloadState.ACTIVE
                                else -> EpisodeDownloadState.NOT_DOWNLOADED
                              },
                            downloadProgress = downloadProgressMap[item.id],
                            onDownload = { viewModel.downloadItem(item) },
                            onCancelDownload = { viewModel.cancelDownload(item.id) },
                          )
                        } else {
                          JellyfinListItemCard(
                            item = item,
                            server = server,
                            onClick = {
                              if (selectionManager.isInSelectionMode) {
                                selectionManager.toggleFromUser(item)
                              } else if (item.isFolder || item.isSeries || item.isSeason || item.type == "CollectionFolder") {
                                viewModel.navigateToItem(item)
                              } else if (item.isVideo) {
                                viewModel.openDetail(item)
                              } else {
                                viewModel.playItem(context, item)
                              }
                            },
                            onLongClick = { selectionManager.handleLongClick(item) },
                            isSelected = selectionManager.isSelected(item),
                            isDownloaded = item.id in downloadedItemIds,
                          )
                        }
                      }
                    }
                    if (uiState.isLoadingMore) {
                      item {
                        Box(
                          modifier =
                            Modifier
                              .fillMaxWidth()
                              .padding(16.dp),
                          contentAlignment = Alignment.Center,
                        ) {
                          CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                      }
                    }
                  }

                  if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                    ExpressiveScrollBar(
                      listState = listState,
                      dragLabelProvider = { index ->
                        fastScrollGlyph(items.getOrNull(index)?.name)
                      },
                      modifier =
                        Modifier
                          .align(Alignment.CenterEnd)
                          .padding(end = 2.dp, top = topBarHeight + 6.dp, bottom = navigationBarHeight + 96.dp)
                          .graphicsLayer { alpha = scrollbarAlpha },
                    )
                  }
                }
              } else {
                val gridState = libraryGridState
                val gridColumns by remember(gridState) {
                  derivedStateOf { gridState.layoutInfo.maxSpan.coerceAtLeast(1) }
                }
                val hasEnoughItems = items.size > 6
                val scrollbarAlpha by animateFloatAsState(
                  targetValue = if (hasEnoughItems) 1f else 0f,
                  label = "scrollbarAlpha",
                )

                val shouldLoadMore =
                  remember {
                    derivedStateOf {
                      val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                      items.isNotEmpty() && lastVisibleIndex >= items.size - 8
                    }
                  }

                LaunchedEffect(shouldLoadMore.value) {
                  if (shouldLoadMore.value && uiState.hasMore && !uiState.isLoading && !uiState.isLoadingMore) {
                    viewModel.loadMoreItems()
                  }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                  LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 130.dp),
                    modifier = Modifier.fillMaxSize().tvContentFocus(),
                    // Cards are padding-less now, so the grid edge lands directly on the gutter.
                    contentPadding = PaddingValues(
                      start = gutter,
                      end = gutter,
                      top = topBarHeight + 8.dp,
                      bottom = navigationBarHeight + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
                    horizontalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
                  ) {
                    items(count = items.size, key = { index -> items[index].id }) { index ->
                      val item = items[index]
                      uiState.activeServer?.let { server ->
                        JellyfinPosterCard(
                          item = item,
                          server = server,
                          onClick = {
                            if (selectionManager.isInSelectionMode) {
                              selectionManager.toggleFromUser(item)
                            } else if (item.isFolder || item.isSeason || item.type == "CollectionFolder") {
                              viewModel.navigateToItem(item)
                            } else if (item.isSeries || item.isVideo) {
                              viewModel.openDetail(item)
                            } else {
                              viewModel.playItem(context, item)
                            }
                          },
                          onLongClick = { selectionManager.handleLongClick(item) },
                          isSelected = selectionManager.isSelected(item),
                          isDownloaded = item.id in downloadedItemIds,
                          selectionNeighbors = mediaSelectionNeighbors(index, items.size, gridColumns) { neighbor ->
                            selectionManager.isSelected(items[neighbor])
                          },
                        )
                      }
                    }

                    if (uiState.isLoadingMore) {
                      item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                          modifier =
                            Modifier
                              .fillMaxWidth()
                              .padding(16.dp),
                          contentAlignment = Alignment.Center,
                        ) {
                          CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                      }
                    }
                  }

                  if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                    ExpressiveScrollBar(
                      gridState = gridState,
                      dragLabelProvider = { index ->
                        fastScrollGlyph(items.getOrNull(index)?.name)
                      },
                      modifier =
                        Modifier
                          .align(Alignment.CenterEnd)
                          .padding(end = 2.dp, top = topBarHeight + 6.dp, bottom = navigationBarHeight + 96.dp)
                          .graphicsLayer { alpha = scrollbarAlpha },
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

    // Top Bar Container (Material 3 Expressive BrowserTopBar / SearchBar / TabRow)
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.TopCenter)
        .drawBehind {
          if (!isWallpaperActive) {
            if (solidAlpha < 1f) {
              drawRect(brush = headerGradient, alpha = 1f - solidAlpha)
            }
            if (solidAlpha > 0f) {
              drawRect(color = headerContainerColor, alpha = solidAlpha)
            }
          }
        }
        .onGloballyPositioned { coordinates ->
          topBarHeight = with(density) { coordinates.size.height.toDp() }
        },
    ) {
      if (isSearching) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .statusBarsPadding()
              .padding(vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          InlineSearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChanged,
            onSearch = { query -> viewModel.performSearch(query, debounceMs = 0L) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
            inputFieldModifier = Modifier.focusRequester(searchFocusRequester),
            placeholder = { Text(stringResource(R.string.jellyfin_search_hint)) },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = stringResource(R.string.settings_search_title),
              )
            },
            trailingIcon = {
              IconButton(
                modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false),
                onClick = {
                  if (uiState.searchQuery.isNotEmpty()) {
                    viewModel.onSearchQueryChanged("")
                    viewModel.refresh()
                  } else {
                    isSearching = false
                  }
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Close,
                  contentDescription = stringResource(R.string.generic_cancel),
                )
              }
            },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )

          // Category Filter Chips, scrolling edge to edge from the gutter
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = gutter),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            JellyfinSearchCategory.entries.forEach { category ->
              val isSelected = uiState.searchCategory == category
              FilterChip(
                selected = isSelected,
                onClick = { viewModel.setSearchCategory(category) },
                label = { Text(category.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                modifier = Modifier.tvFocusHighlight(CircleShape),
                shape = CircleShape,
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  ),
              )
            }
          }
        }
      } else {
        BrowserTopBar(
          title = pageTitle,
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
          isInSelectionMode = selectionManager.isInSelectionMode,
          selectedCount = selectionManager.selectedCount,
          totalCount = uiState.currentItems.size,
          onCancelSelection = { selectionManager.clear() },
          onSelectAll = { selectionManager.selectAll() },
          onInvertSelection = { selectionManager.invertSelection() },
          onDeselectAll = { selectionManager.clear() },
          onPlayClick = { viewModel.playSelected(context, selectionManager.getSelectedItems()) },
          isSingleSelection = selectionManager.isSingleSelection,
          onBackClick = if (!isMusicOnlyMode && uiState.openLibrary != null) { { viewModel.navigateBack() } } else null,
          onSortClick = if (if (isMusicMode) uiState.musicActiveTab != JellyfinMusicTab.HOME else uiState.openLibrary != null) {
            { isSortDialogOpen = true }
          } else null,
          onSearchClick = { isSearching = true },
          // Seerr requests only surface on the untouched Jellyfin home page.
          onRequestClick = if (!isMusicOnlyMode && uiState.openLibrary == null && uiState.selectedLibraryId == null) {
            { isSeerrRequestsOpen = true }
          } else {
            null
          },
          onSettingsClick = {
            backstack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
          },
          titleTrailing = if (isMusicOnlyMode) {
            { app.gyrolet.mpvrx.ui.browser.music.MusicSourceDropdown() }
          } else null,
          postSearchActions = {
            if (!selectionManager.isInSelectionMode && !isMusicOnlyMode) {
              IconButton(
                onClick = { backstack.navigateTo(app.gyrolet.mpvrx.ui.downloads.DownloadsScreen) },
                modifier = Modifier.padding(horizontal = 2.dp).tvFocusHighlight(CircleShape, clipToShape = false),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Download,
                  contentDescription = stringResource(R.string.downloads_open_downloads),
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            }
          },
        )
      }

      if (uiState.openLibrary != null && !isMusicMode && !isSearching) {
        JellyfinGenreChipRow(
          genres = uiState.availableGenres,
          selectedGenre = uiState.selectedGenreFilter,
          onSelectGenre = viewModel::setGenreFilter,
        )
      }

      if (isMusicMode && !isSearching) {
        val selectedTabIndex = musicPagerState.currentPage.coerceIn(0, (musicTabs.size - 1).coerceAtLeast(0))

        PrimaryScrollableTabRow(
          selectedTabIndex = selectedTabIndex,
          modifier = Modifier.onFocusChanged { musicTabsFocused = it.hasFocus }.tvFocusGroup(),
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.onSurface,
          // Tabs pad their labels by 16 dp, so this lines the first label up with the gutter.
          edgePadding = (gutter - 16.dp).coerceAtLeast(0.dp),
          divider = {},
        ) {
          musicTabs.forEachIndexed { index, tab ->
            Tab(
              selected = selectedTabIndex == index,
              modifier = Modifier
                .padding(vertical = if (isTelevision) 4.dp else 0.dp)
                .tvFocusHighlight(MaterialTheme.shapes.large, clipToShape = false)
                .onKeyEvent { event ->
                  isTelevision && event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                    contentFocusRequester.tryRequestFocus()
                },
              onClick = {
                viewModel.setMusicTab(tab)
                navigateMusicTab(index)
              },
              text = {
                Text(
                  text = tab.title,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              selectedContentColor = MaterialTheme.colorScheme.onSurface,
              unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        HorizontalDivider()
      }
    }

      // Multi-Select Floating Action Pill (smoothly replaces bottom nav bar)
      androidx.compose.animation.AnimatedVisibility(
        visible = selectionManager.isInSelectionMode,
        enter =
          slideInVertically(
            animationSpec =
              spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
              ),
            initialOffsetY = { fullHeight -> fullHeight * 2 },
          ) + fadeIn(),
        exit =
          slideOutVertically(
            animationSpec =
              spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
              ),
            targetOffsetY = { fullHeight -> fullHeight * 2 },
          ) + fadeOut(),
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
      ) {
        val selectedItems = selectionManager.getSelectedItems()
        val playableCount = selectedItems.count { it.isVideo }

        Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.Center,
        ) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
              if (playableCount > 0) {
                IconButton(
                  modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false),
                  onClick = {
                    viewModel.playSelected(context, selectedItems)
                    selectionManager.clear()
                  },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.PlayArrow,
                    contentDescription = stringResource(R.string.ui_play),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }

              IconButton(
                modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false),
                onClick = {
                  viewModel.markSelectedPlayed(selectedItems, true)
                  selectionManager.clear()
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.CheckCircle,
                  contentDescription = stringResource(R.string.video_action_mark_watched),
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              IconButton(
                modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false),
                onClick = {
                  viewModel.markSelectedPlayed(selectedItems, false)
                  selectionManager.clear()
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.RemoveCircle,
                  contentDescription = stringResource(R.string.video_action_mark_unwatched),
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              if (selectedItems.size == 1) {
                IconButton(
                  modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false),
                  onClick = { viewModel.openDetail(selectedItems.first()) },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Info,
                    contentDescription = stringResource(R.string.jellyfin_details),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }

      FabScrollHelper.FabScrim(
        visible = isFabExpanded && !quickPlayFabDirect,
        onDismiss = { isFabExpanded = false },
      )

      val isFabShouldBeVisible =
        showQuickPlayFab &&
          !selectionManager.isInSelectionMode &&
          !isSearching &&
          isFabVisible.value &&
          uiState.activeServer != null &&
          (uiState.currentItems.isNotEmpty() || uiState.resumeItems.isNotEmpty() || uiState.heroItems.isNotEmpty())

      // Expressive Floating Action Button Menu. It overlays the tab instead of using a Scaffold slot,
      // so it applies the system bar inset itself to line up with every other browser FAB.
      FloatingActionButtonMenu(
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(FabPlacement.overlayInsets)
            .padding(end = FabPlacement.ScaffoldFabSpacing, bottom = FabPlacement.overlayBottomPadding()),
        expanded = isFabExpanded && !quickPlayFabDirect,
        button = {
          ToggleFloatingActionButton(
            modifier =
              Modifier.animateFloatingActionButton(
                visible = isFabShouldBeVisible,
                alignment = Alignment.BottomEnd,
              ).focusableWhen(isFabShouldBeVisible)
                .tvFocusHighlight(MaterialTheme.shapes.large, clipToShape = false),
            checked = isFabExpanded && !quickPlayFabDirect,
              onCheckedChange = {
                if (quickPlayFabDirect) {
                  viewModel.resumeLastPlayed(context)
                } else {
                  isFabExpanded = !isFabExpanded
                }
              },
            ) {
              // The scope's animated checkedProgress drives the icon; a local val here
              // shadowed it before, freezing the FAB on the play icon while expanded.
              val imageVector by remember(quickPlayFabDirect) {
                derivedStateOf {
                  if (checkedProgress > 0.5f && !quickPlayFabDirect) Icons.RoundedFilled.Close else Icons.RoundedFilled.PlayArrow
                }
              }
              Icon(
                imageVector = imageVector,
                contentDescription = stringResource(R.string.ui_quick_play),
                modifier = Modifier.animateIcon({ if (quickPlayFabDirect) 0f else checkedProgress }),
              )
            }
          },
        ) {
          if (!quickPlayFabDirect) {
            FloatingActionButtonMenuItem(
              modifier = Modifier.focusableWhen(isFabExpanded).tvFocusHighlight(CircleShape, clipToShape = false),
              onClick = {
                isFabExpanded = false
                isSearching = true
              },
              icon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
              text = { Text(stringResource(R.string.settings_search_title)) },
            )

            FloatingActionButtonMenuItem(
              modifier = Modifier.focusableWhen(isFabExpanded).tvFocusHighlight(CircleShape, clipToShape = false),
              onClick = {
                isFabExpanded = false
                viewModel.playRandom(context)
              },
              icon = { Icon(Icons.RoundedFilled.Shuffle, contentDescription = null) },
              text = { Text(stringResource(R.string.jellyfin_play_random)) },
            )

            FloatingActionButtonMenuItem(
              modifier = Modifier.focusableWhen(isFabExpanded).tvFocusHighlight(CircleShape, clipToShape = false),
              onClick = {
                isFabExpanded = false
                isManageServersOpen = true
              },
              icon = { Icon(Icons.RoundedFilled.Jellyfin, contentDescription = null) },
              text = { Text(stringResource(R.string.jellyfin_switch_server)) },
            )

            FloatingActionButtonMenuItem(
              modifier = Modifier.focusableWhen(isFabExpanded).tvFocusHighlight(CircleShape, clipToShape = false),
              onClick = {
                isFabExpanded = false
                viewModel.resumeLastPlayed(context)
              },
              icon = { Icon(Icons.RoundedFilled.History, contentDescription = null) },
              text = { Text(stringResource(R.string.pref_advanced_enable_recently_played_title)) },
            )
          }
        }
    }

  // Cinematic Media Detail Sheet (Material 3 Expressive)
  uiState.activeServer?.let { server ->
    JellyfinDetailSheet(
      item = uiState.detailItem,
      server = server,
      seasons = uiState.detailSeasons,
      selectedSeasonId = uiState.selectedDetailSeasonId,
      episodes = uiState.detailEpisodes,
      similarItems = uiState.detailSimilarItems,
      isLoading = uiState.isDetailLoading,
      isEpisodesLoading = uiState.isDetailEpisodesLoading,
      onDismiss = { viewModel.closeDetail() },
      onPlay = { item, fromBeginning -> viewModel.playItem(context, item, fromBeginning) },
      onSelectSeason = { seasonId -> viewModel.selectDetailSeason(seasonId) },
      onToggleFavorite = { item -> viewModel.toggleItemFavorite(item) },
      onTogglePlayed = { item -> viewModel.togglePlayed(item) },
      onItemClick = { item -> viewModel.openDetail(item) },
      onPersonClick = { person -> viewModel.openPerson(person) },
      onDeleteItem = { itemToDelete ->
        viewModel.deleteItem(itemToDelete.id) {
          viewModel.closeDetail()
        }
      },
      onDownload = { itemToDownload -> viewModel.downloadItem(itemToDownload) },
      onCancelDownload = { itemToCancel -> viewModel.cancelDownload(itemToCancel.id) },
      onDownloadSeason = { viewModel.downloadSelectedSeason() },
      onDownloadSeries = { viewModel.downloadWholeSeries() },
      downloadedItemIds = downloadedItemIds,
      activeDownloadItemIds = activeDownloadItemIds,
      downloadProgressMap = downloadProgressMap,
      seriesProgress = seriesProgress,
      isSeriesDownloading = isSeriesDownloading,
    )

    JellyfinPersonSheet(
      person = uiState.personDetail,
      server = server,
      overview = uiState.personOverview,
      media = uiState.personMedia,
      isLoading = uiState.isPersonLoading,
      onDismiss = { viewModel.closePerson() },
      onItemClick = { item ->
        viewModel.openDetail(item)
      },
    )
  }

  if (isMusicMode) {
    val availableFields = remember(uiState.musicActiveTab) {
      when (uiState.musicActiveTab) {
        JellyfinMusicTab.TRACKS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.ALBUM,
          MusicSortField.DURATION,
          MusicSortField.YEAR,
        )
        JellyfinMusicTab.ALBUMS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.YEAR,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        JellyfinMusicTab.ARTISTS -> listOf(
          MusicSortField.ARTIST,
          MusicSortField.TRACK_COUNT,
        )
        JellyfinMusicTab.PLAYLISTS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        else -> emptyList()
      }
    }

    MusicSortDialog(
      isOpen = isSortDialogOpen,
      onDismiss = { isSortDialogOpen = false },
      sortField = uiState.musicSortField,
      sortOrder = uiState.musicSortOrder,
      viewMode = uiState.musicViewMode,
      onSortFieldChange = { viewModel.setMusicSortField(it) },
      onSortOrderChange = { viewModel.setMusicSortOrder(it) },
      onViewModeChange = { viewModel.setMusicViewMode(it) },
      availableFields = availableFields,
      trackCountLabel = if (uiState.musicActiveTab == JellyfinMusicTab.ARTISTS) {
        stringResource(R.string.ui_albums)
      } else MusicSortField.TRACK_COUNT.displayName,
    )
  } else {
    // Standard Material 3 Sort Dialog (matches Home and Network Browser)
    JellyfinSortDialog(
      isOpen = isSortDialogOpen,
      onDismiss = { isSortDialogOpen = false },
      sortBy = uiState.sortBy,
      onSortByChange = { newSort ->
        viewModel.setSort(newSort, uiState.sortOrder)
      },
      sortOrder = uiState.sortOrder,
      onSortOrderChange = { newOrder ->
        viewModel.setSort(uiState.sortBy, newOrder)
      },
      isUnplayedOnly = uiState.isUnplayedOnly,
      onUnplayedOnlyChange = {
        viewModel.toggleUnplayedOnly()
      },
      layoutMode = layoutMode,
      onLayoutModeChange = { newMode ->
        browserPreferences.jellyfinLayoutMode.set(newMode)
      },
    )
  }

  // Manage Servers Dialog
  ManageJellyfinServersDialog(
    isOpen = isManageServersOpen,
    servers = uiState.servers,
    activeServer = uiState.activeServer,
    onDismiss = { isManageServersOpen = false },
    onSelectServer = { viewModel.selectServer(it) },
    onDeleteServer = { viewModel.deleteServer(it) },
    onAddServerClick = { isAddDialogOpen = true },
  )

  // Add Server Dialog
  AddJellyfinServerDialog(
    isOpen = isAddDialogOpen,
    isLoading = uiState.isAuthenticating,
    errorMessage = uiState.authError,
    initialServer = serverToReauth,
    onDismiss = {
      isAddDialogOpen = false
      serverToReauth = null
    },
    onConnect = { serverUrl, serverName, authMode, username, password, token ->
      val existingId = serverToReauth?.id
      viewModel.addServer(
        serverUrl = serverUrl,
        serverName = serverName,
        authMode = authMode,
        username = username,
        password = password,
        token = token,
        existingServerId = existingId,
        onSuccess = {
          isAddDialogOpen = false
          serverToReauth = null
        },
      )
    },
  )
}

@Composable
private fun EmptyServersView(
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  EmptyState(
    icon = Icons.RoundedFilled.Jellyfin,
    title = stringResource(R.string.pref_jellyfin_add_server),
    message = stringResource(R.string.jellyfin_connect_message),
    modifier = modifier,
    action = {
      Button(
        onClick = onAddClick,
        modifier = Modifier.heightIn(min = 48.dp).tvFocusHighlight(CircleShape),
        shape = CircleShape,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Add,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.pref_jellyfin_add_server))
      }
    },
  )
}

@Composable
private fun ErrorView(
  message: String,
  onRetry: () -> Unit,
  onReauthenticate: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val isAuthError = message.contains("401", ignoreCase = true) ||
    message.contains("unauthorized", ignoreCase = true) ||
    message.contains("forbidden", ignoreCase = true) ||
    message.contains("403", ignoreCase = true)

  EmptyState(
    icon = Icons.RoundedFilled.Info,
    title = stringResource(R.string.jellyfin_error_title),
    message = if (isAuthError) stringResource(R.string.jellyfin_auth_error_message) else message,
    modifier = modifier,
    action = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = onRetry,
          modifier = Modifier.heightIn(min = 48.dp).tvFocusHighlight(CircleShape),
          shape = CircleShape,
        ) {
          Text(stringResource(R.string.ui_retry))
        }
        if (isAuthError && onReauthenticate != null) {
          Button(
            onClick = onReauthenticate,
            modifier = Modifier.heightIn(min = 48.dp).tvFocusHighlight(CircleShape),
            shape = CircleShape,
          ) {
            Text(stringResource(R.string.jellyfin_reauthenticate))
          }
        }
      }
    },
  )
}
