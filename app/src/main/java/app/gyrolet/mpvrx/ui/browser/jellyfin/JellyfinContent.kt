/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSearchCategory
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.JellyfinSortDialog
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
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
  val backstack = LocalBackStack.current
  val browserPreferences = koinInject<BrowserPreferences>()
  val mediaServerPreferences = koinInject<MediaServerPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val currentMusicSource by mediaServerPreferences.musicSourceProvider.collectAsState()
  val layoutMode by browserPreferences.jellyfinLayoutMode.collectAsState()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()

  val allDownloads by viewModel.downloads.collectAsState()
  val downloadedItemIds =
    remember(allDownloads) {
      allDownloads.filter { it.isPlayable }.mapNotNull { it.entity.jellyfinItemId }.toSet()
    }
  val activeDownloadItemIds =
    remember(allDownloads) {
      allDownloads.filter { it.isActive }.mapNotNull { it.entity.jellyfinItemId }.toSet()
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
  val scope = rememberCoroutineScope()

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

  LaunchedEffect(musicPagerState.settledPage, musicTabs) {
    musicTabs.getOrNull(musicPagerState.settledPage)?.let { tab ->
      if (uiState.musicActiveTab != tab) {
        viewModel.setMusicTab(tab)
      }
    }
  }

  LaunchedEffect(uiState.musicActiveTab, musicTabs) {
    val targetIndex = musicTabs.indexOf(uiState.musicActiveTab)
    if (targetIndex >= 0 && musicPagerState.currentPage != targetIndex) {
      musicPagerState.animateScrollToPage(targetIndex)
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

  DisposableEffect(selectionManager.isInSelectionMode) {
    NavigationBarState.updateSelectionState(
      inSelectionMode = selectionManager.isInSelectionMode,
      onlyVideos = true,
    )
    onDispose {
      NavigationBarState.updateSelectionState(inSelectionMode = false)
    }
  }

  // Intercept back button if searching, selecting, requests open, details open, or browsing inside a folder
  val isBackEnabled =
    if (isMusicOnlyMode) {
      isSearching || selectionManager.isInSelectionMode || uiState.detailItem != null ||
        (uiState.openLibrary?.isMusic == true && uiState.musicActiveTab != JellyfinMusicTab.HOME) ||
        (isFabExpanded && !quickPlayFabDirect)
    } else {
      isSeerrRequestsOpen || isSearching || selectionManager.isInSelectionMode ||
        uiState.detailItem != null || uiState.openLibrary != null || (isFabExpanded && !quickPlayFabDirect)
    }

  BackHandler(
    enabled = isBackEnabled,
  ) {
    when {
      isFabExpanded && !quickPlayFabDirect -> {
        isFabExpanded = false
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

  val pageTitle =
    when {
      isMusicOnlyMode -> stringResource(R.string.ui_music)
      uiState.openLibrary != null -> uiState.openLibrary!!.title
      uiState.activeServer != null -> uiState.activeServer!!.name
      else -> stringResource(R.string.ui_jellyfin)
    }

  val headerContainerColor =
    if (MaterialTheme.colorScheme.background == Color.Black) Color.Black else MaterialTheme.colorScheme.surfaceContainer

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
  ) {
    // Top Bar Container (Material 3 Expressive BrowserTopBar / SearchBar / TabRow)
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(headerContainerColor),
    ) {
      if (isSearching) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          InlineSearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChanged,
            onSearch = { query -> viewModel.performSearch(query, debounceMs = 0L) },
            modifier = Modifier.fillMaxWidth(),
            inputFieldModifier = Modifier.focusRequester(searchFocusRequester),
            placeholder = { Text("Search movies, shows, episodes...") },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = stringResource(R.string.settings_search_title),
              )
            },
            trailingIcon = {
              IconButton(
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

          // Category Filter Chips
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            JellyfinSearchCategory.entries.forEach { category ->
              val isSelected = uiState.searchCategory == category
              FilterChip(
                selected = isSelected,
                onClick = { viewModel.setSearchCategory(category) },
                label = { Text(category.displayName, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                shape = RoundedCornerShape(12.dp),
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
          onSortClick = if (uiState.openLibrary != null && !(uiState.openLibrary?.isMusic == true && uiState.musicActiveTab == JellyfinMusicTab.HOME)) {
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
            backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
          },
          preSearchActions = {
            if (!selectionManager.isInSelectionMode) {
              if (isMusicOnlyMode) {
                var isSourceDropdownOpen by remember { mutableStateOf(false) }
                Box {
                  Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier
                      .padding(horizontal = 4.dp, vertical = 6.dp)
                      .clickable { isSourceDropdownOpen = true },
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                      if (currentMusicSource == MusicSourceProvider.JELLYFIN) {
                        androidx.compose.material3.Icon(
                          painter = painterResource(R.drawable.ic_jellyfin),
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                          tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                      } else {
                        Icon(
                          Icons.RoundedFilled.Folder,
                          contentDescription = null,
                          modifier = Modifier.size(16.dp),
                          tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                      }
                      Spacer(Modifier.width(6.dp))
                      Text(
                        text = if (currentMusicSource == MusicSourceProvider.JELLYFIN) {
                          stringResource(R.string.pref_jellyfin_title)
                        } else {
                          stringResource(R.string.music_source_local)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                      )
                      Spacer(Modifier.width(2.dp))
                      Icon(
                        Icons.RoundedFilled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                      )
                    }
                  }

                  DropdownMenu(
                    expanded = isSourceDropdownOpen,
                    onDismissRequest = { isSourceDropdownOpen = false },
                  ) {
                    DropdownMenuItem(
                      text = {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween,
                          modifier = Modifier.fillMaxWidth(),
                        ) {
                          Text(stringResource(R.string.music_source_local))
                          if (currentMusicSource == MusicSourceProvider.LOCAL) {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                              Icons.RoundedFilled.Check,
                              contentDescription = null,
                              modifier = Modifier.size(18.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                          }
                        }
                      },
                      leadingIcon = {
                        Icon(Icons.RoundedFilled.Folder, contentDescription = null)
                      },
                      onClick = {
                        mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.LOCAL)
                        isSourceDropdownOpen = false
                      },
                    )

                    DropdownMenuItem(
                      text = {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.SpaceBetween,
                          modifier = Modifier.fillMaxWidth(),
                        ) {
                          Text(stringResource(R.string.music_source_jellyfin))
                          if (currentMusicSource == MusicSourceProvider.JELLYFIN) {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                              Icons.RoundedFilled.Check,
                              contentDescription = null,
                              modifier = Modifier.size(18.dp),
                              tint = MaterialTheme.colorScheme.primary,
                            )
                          }
                        }
                      },
                      leadingIcon = {
                        androidx.compose.material3.Icon(
                          painter = painterResource(R.drawable.ic_jellyfin),
                          contentDescription = null,
                          modifier = Modifier.size(20.dp),
                          tint = MaterialTheme.colorScheme.primary,
                        )
                      },
                      onClick = {
                        mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.JELLYFIN)
                        isSourceDropdownOpen = false
                      },
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.pref_media_servers_title)) },
                      leadingIcon = {
                        Icon(Icons.RoundedFilled.Settings, contentDescription = null)
                      },
                      onClick = {
                        isSourceDropdownOpen = false
                        backstack.add(app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen)
                      },
                    )
                  }
                }
              }
            }
          },
          postSearchActions = {
            if (!selectionManager.isInSelectionMode) {
              IconButton(
                onClick = { backstack.add(app.gyrolet.mpvrx.ui.downloads.DownloadsScreen) },
                modifier = Modifier.padding(horizontal = 2.dp),
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

      if (uiState.openLibrary != null && uiState.openLibrary?.isMusic != true && !isSearching) {
        JellyfinGenreChipRow(
          genres = uiState.availableGenres,
          selectedGenre = uiState.selectedGenreFilter,
          onSelectGenre = viewModel::setGenreFilter,
        )
      }

      if (uiState.openLibrary?.isMusic == true && !isSearching) {
        val selectedTabIndex = musicPagerState.currentPage.coerceIn(0, (musicTabs.size - 1).coerceAtLeast(0))

        PrimaryScrollableTabRow(
          selectedTabIndex = selectedTabIndex,
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.onSurface,
          edgePadding = 8.dp,
          divider = {},
        ) {
          musicTabs.forEachIndexed { index, tab ->
            Tab(
              selected = selectedTabIndex == index,
              onClick = {
                scope.launch {
                  viewModel.setMusicTab(tab)
                  musicPagerState.animateScrollToPage(index)
                }
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

    // Main Body Content with Pull-To-Refresh and FAB / Multi-select overlays
    val isRefreshing = remember { mutableStateOf(false) }
    val navigationBarHeight = LocalNavigationBarHeight.current

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .weight(1f),
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
              EmptyServersView(onAddClick = { isAddDialogOpen = true })
            }

            // Loading state (initial)
            uiState.isLoading && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() && uiState.heroItems.isEmpty() -> {
              CircularProgressIndicator()
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
              )
            }

            // Root / Discovery Home View (Expressive UI)
            uiState.openLibrary == null && uiState.searchQuery.isBlank() -> {
              val server = uiState.activeServer

              if (server != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                  LazyColumn(
                    state = homeListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = navigationBarHeight + 84.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                  ) {
                    // 1. Hero Featured Carousel Banner
                    if (uiState.heroItems.isNotEmpty()) {
                      item {
                        JellyfinHeroBanner(
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
                          verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                          JellyfinSectionHeader(
                            title = "Libraries",
                          )

                          LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                          ) {
                            items(homeLibraries, key = { it.id }) { library ->
                              JellyfinLibraryCard(
                                item = library,
                                server = server,
                                onClick = { viewModel.navigateToItem(library) },
                              )
                            }
                          }
                        }
                      }
                    }

                    // 3. Continue Watching Section
                    if (uiState.resumeItems.isNotEmpty()) {
                      item {
                        JellyfinHorizontalSection(
                          title = "Continue Watching",
                          subtitle = "Jump back in",
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
                            title = "Latest Movies",
                            subtitle = "Newly added to server",
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
                            title = "Latest TV Shows",
                            subtitle = "Newly updated series",
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
                          title = "Top Picks For You",
                          subtitle = "Popular & trending media",
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
                          verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                          JellyfinSectionHeader(
                            title = "Music",
                            subtitle = "Albums & Tracks",
                            onSeeAll = {
                              val musicLib = uiState.libraries.find { it.collectionType?.equals("music", ignoreCase = true) == true }
                              if (musicLib != null) viewModel.navigateToItem(musicLib)
                            },
                          )

                          LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
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
              val openLib = uiState.openLibrary
              if (openLib?.isMusic == true && uiState.searchQuery.isBlank() && uiState.activeServer != null) {
                JellyfinMusicView(
                  uiState = uiState,
                  server = uiState.activeServer!!,
                  pagerState = musicPagerState,
                  visibleTabs = musicTabs,
                  onTabSelected = { tab ->
                    scope.launch {
                      viewModel.setMusicTab(tab)
                      val targetIndex = musicTabs.indexOf(tab)
                      if (targetIndex >= 0) {
                        musicPagerState.animateScrollToPage(targetIndex)
                      }
                    }
                  },
                  onItemClick = { item ->
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggle(item)
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
                )
              } else {
                val items = uiState.currentItems
                val allEpisodes = items.isNotEmpty() && items.all { it.type == "Episode" }
                val isListMode = layoutMode == MediaLayoutMode.LIST || allEpisodes

              if (items.isEmpty() && !uiState.isLoading) {
                Column(
                  modifier = Modifier.padding(24.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center,
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(48.dp),
                  )
                  Spacer(modifier = Modifier.height(12.dp))
                  Text(
                    text = if (uiState.searchQuery.isNotBlank()) "No results found for \"${uiState.searchQuery}\"" else "No media found in this folder",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                  )
                }
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = navigationBarHeight + 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    items(items, key = { it.id }) { item ->
                      uiState.activeServer?.let { server ->
                        if (allEpisodes || item.type == "Episode") {
                          JellyfinEpisodeCard(
                            item = item,
                            server = server,
                            onPlay = {
                              if (selectionManager.isInSelectionMode) {
                                selectionManager.toggle(item)
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
                            onDownload = { viewModel.downloadItem(item) },
                          )
                        } else {
                          JellyfinListItemCard(
                            item = item,
                            server = server,
                            onClick = {
                              if (selectionManager.isInSelectionMode) {
                                selectionManager.toggle(item)
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
                          .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 80.dp)
                          .graphicsLayer { alpha = scrollbarAlpha },
                    )
                  }
                }
              } else {
                val gridState = libraryGridState
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 24.dp, top = 8.dp, bottom = navigationBarHeight + 80.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    items(items, key = { it.id }) { item ->
                      uiState.activeServer?.let { server ->
                        JellyfinPosterCard(
                          item = item,
                          server = server,
                          onClick = {
                            if (selectionManager.isInSelectionMode) {
                              selectionManager.toggle(item)
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
                          .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 80.dp)
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
                  onClick = {
                    viewModel.playSelected(context, selectedItems)
                    selectionManager.clear()
                  },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }

              IconButton(
                onClick = {
                  viewModel.markSelectedPlayed(selectedItems, true)
                  selectionManager.clear()
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.CheckCircle,
                  contentDescription = "Mark watched",
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              IconButton(
                onClick = {
                  viewModel.markSelectedPlayed(selectedItems, false)
                  selectionManager.clear()
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.RemoveCircle,
                  contentDescription = "Mark unwatched",
                  modifier = Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              if (selectedItems.size == 1) {
                IconButton(
                  onClick = { viewModel.openDetail(selectedItems.first()) },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Info,
                    contentDescription = "Info",
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

      // Expressive Floating Action Button Menu
      FloatingActionButtonMenu(
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 16.dp, bottom = navigationBarHeight + 16.dp),
        expanded = isFabExpanded && !quickPlayFabDirect,
        button = {
          ToggleFloatingActionButton(
            modifier =
              Modifier.animateFloatingActionButton(
                visible = isFabShouldBeVisible,
                alignment = Alignment.BottomEnd,
              ),
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
              onClick = {
                isFabExpanded = false
                isSearching = true
              },
              icon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
              text = { Text("Search") },
            )

            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded = false
                viewModel.playRandom(context)
              },
              icon = { Icon(Icons.RoundedFilled.Shuffle, contentDescription = null) },
              text = { Text("Play Random") },
            )

            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded = false
                isManageServersOpen = true
              },
              icon = { Icon(Icons.RoundedFilled.BringYourOwnIp, contentDescription = null) },
              text = { Text("Switch Server") },
            )

            FloatingActionButtonMenuItem(
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
      onDeleteItem = { itemToDelete ->
        viewModel.deleteItem(itemToDelete.id) {
          viewModel.closeDetail()
        }
      },
      onDownload = { itemToDownload -> viewModel.downloadItem(itemToDownload) },
      onDownloadSeason = { viewModel.downloadSelectedSeason() },
      onDownloadSeries = { viewModel.downloadWholeSeries() },
      downloadedItemIds = downloadedItemIds,
      activeDownloadItemIds = activeDownloadItemIds,
    )
  }

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
private fun EmptyServersView(onAddClick: () -> Unit) {
  Column(
    modifier = Modifier.padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.size(80.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(
          painter = painterResource(R.drawable.ic_jellyfin),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(40.dp),
        )
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(
      text = "Connect to Jellyfin",
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Stream your media library directly with mpvRx hardware acceleration and zero transcoding.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(20.dp))
    Button(onClick = onAddClick) {
      Icon(
        imageVector = Icons.RoundedFilled.Add,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text("Add Jellyfin Server")
    }
  }
}

@Composable
private fun ErrorView(
  message: String,
  onRetry: () -> Unit,
  onReauthenticate: (() -> Unit)? = null,
) {
  val isAuthError = message.contains("401", ignoreCase = true) ||
    message.contains("unauthorized", ignoreCase = true) ||
    message.contains("forbidden", ignoreCase = true) ||
    message.contains("403", ignoreCase = true)

  Column(
    modifier = Modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Info,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.error,
      modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = if (isAuthError) "Authentication failed (HTTP 401). Session expired or unauthorized." else message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(onClick = onRetry) {
        Text("Retry")
      }
      if (isAuthError && onReauthenticate != null) {
        Button(onClick = onReauthenticate) {
          Text("Re-authenticate")
        }
      }
    }
  }
}