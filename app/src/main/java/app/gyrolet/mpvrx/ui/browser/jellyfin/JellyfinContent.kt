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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.animateFloatingActionButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSearchCategory
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
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
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinContent(
  viewModel: JellyfinViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val backstack = LocalBackStack.current
  val browserPreferences = koinInject<BrowserPreferences>()
  val layoutMode by browserPreferences.jellyfinLayoutMode.collectAsState()

  var isAddDialogOpen by remember { mutableStateOf(false) }
  var isManageServersOpen by rememberSaveable { mutableStateOf(false) }
  var isSearching by rememberSaveable { mutableStateOf(false) }
  var isSortDialogOpen by rememberSaveable { mutableStateOf(false) }
  val searchFocusRequester = remember { FocusRequester() }

  val selectionManager =
    rememberSelectionManager(
      items = uiState.currentItems,
      getId = { it.id },
      onDeleteItems = { _, _ -> Pair(0, 0) },
    )

  DisposableEffect(selectionManager.isInSelectionMode) {
    NavigationBarState.updateSelectionState(
      inSelectionMode = selectionManager.isInSelectionMode,
      onlyVideos = true,
    )
    onDispose {
      NavigationBarState.updateSelectionState(inSelectionMode = false)
    }
  }

  // Intercept back button if searching, selecting, details open, or browsing inside a folder
  BackHandler(
    enabled =
      isSearching || selectionManager.isInSelectionMode ||
        uiState.detailItem != null || uiState.openLibrary != null,
  ) {
    when {
      uiState.detailItem != null -> {
        viewModel.closeDetail()
      }
      isSearching -> {
        isSearching = false
        viewModel.onSearchQueryChanged("")
        viewModel.refresh()
      }
      selectionManager.isInSelectionMode -> {
        selectionManager.clear()
      }
      else -> {
        viewModel.navigateBack()
      }
    }
  }

  LaunchedEffect(isSearching) {
    if (isSearching) {
      searchFocusRequester.requestFocus()
    }
  }

  val pageTitle =
    when {
      uiState.openLibrary != null -> uiState.openLibrary!!.title
      uiState.activeServer != null -> uiState.activeServer!!.name
      else -> stringResource(R.string.ui_jellyfin)
    }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
  ) {
    // Top Bar (Material 3 Expressive BrowserTopBar / SearchBar)
    if (isSearching) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SearchBar(
          inputField = {
            SearchBarDefaults.InputField(
              query = uiState.searchQuery,
              onQueryChange = {
                viewModel.onSearchQueryChanged(it)
              },
              onSearch = { viewModel.performSearch(uiState.searchQuery, debounceMs = 0L) },
              expanded = false,
              onExpandedChange = { },
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
              modifier = Modifier.focusRequester(searchFocusRequester),
            )
          },
          expanded = false,
          onExpandedChange = { },
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(28.dp),
          tonalElevation = 6.dp,
        ) { }

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
        onBackClick = if (uiState.openLibrary != null) { { viewModel.navigateBack() } } else null,
        onSortClick = { isSortDialogOpen = true },
        onSearchClick = { isSearching = true },
        onSettingsClick = {
          backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
        },
        additionalActions = {
          if (!selectionManager.isInSelectionMode) {
            IconButton(
              onClick = { isManageServersOpen = true },
              modifier = Modifier.padding(horizontal = 2.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.BringYourOwnIp,
                contentDescription = "Manage Servers",
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }
          }
        },
      )
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
              )
            }

            // Root / Discovery Home View (Expressive UI)
            uiState.openLibrary == null && uiState.searchQuery.isBlank() -> {
              val listState = rememberLazyListState()
              val server = uiState.activeServer

              if (server != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                  LazyColumn(
                    state = listState,
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

                    // 2. Library Filter Chips
                    if (uiState.libraries.isNotEmpty()) {
                      item {
                        JellyfinLibraryChipRow(
                          libraries = uiState.libraries,
                          selectedLibraryId = uiState.selectedLibraryId,
                          onSelectLibrary = { libId ->
                            val selectedLib = uiState.libraries.find { it.id == libId }
                            if (selectedLib != null) {
                              viewModel.navigateToItem(selectedLib)
                            }
                          },
                        )
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

                    // 4. Latest Movies Section
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

                    // 5. Latest TV Shows Section
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

                    // 7. Libraries Section
                    if (uiState.libraries.isNotEmpty()) {
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
                            items(uiState.libraries, key = { it.id }) { library ->
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
                  }
                }
              }
            }

            // Level / Search View: Inside a Library / Folder / Season / Search results
            else -> {
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
                val listState =
                  remember(uiState.openLibrary, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) {
                    LazyListState()
                  }
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navigationBarHeight + 80.dp),
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
                val gridState =
                  remember(uiState.openLibrary, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) {
                    LazyGridState()
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = navigationBarHeight + 80.dp),
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

      if (!selectionManager.isInSelectionMode && uiState.activeServer != null && (uiState.currentItems.isNotEmpty() || uiState.resumeItems.isNotEmpty() || uiState.heroItems.isNotEmpty())) {
        // Expressive Floating Action Button Menu
        var isFabExpanded by remember { mutableStateOf(false) }

        FloatingActionButtonMenu(
          modifier =
            Modifier
              .align(Alignment.BottomEnd)
              .padding(end = 16.dp, bottom = navigationBarHeight + 16.dp),
          expanded = isFabExpanded,
          button = {
            ToggleFloatingActionButton(
              modifier =
                Modifier.animateFloatingActionButton(
                  visible = !isSearching,
                  alignment = Alignment.BottomEnd,
                ),
              checked = isFabExpanded,
              onCheckedChange = { isFabExpanded = !isFabExpanded },
            ) {
              val checkedProgress = if (isFabExpanded) 1f else 0f
              val imageVector by remember {
                derivedStateOf {
                  if (isFabExpanded) Icons.RoundedFilled.Close else Icons.RoundedFilled.PlayArrow
                }
              }
              Icon(
                imageVector = imageVector,
                contentDescription = "Quick Menu",
                modifier = Modifier.animateIcon({ checkedProgress }),
              )
            }
          },
        ) {
          FloatingActionButtonMenuItem(
            onClick = {
              isFabExpanded = false
              viewModel.resumeLastPlayed(context)
            },
            icon = { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null) },
            text = { Text("Resume") },
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
              isSearching = true
            },
            icon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
            text = { Text("Search") },
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
    onDismiss = { isAddDialogOpen = false },
    onConnect = { serverUrl, serverName, authMode, username, password, token ->
      viewModel.addServer(
        serverUrl = serverUrl,
        serverName = serverName,
        authMode = authMode,
        username = username,
        password = password,
        token = token,
        onSuccess = { isAddDialogOpen = false },
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
) {
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
      text = message,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.error,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(16.dp))
    FilledTonalButton(onClick = onRetry) {
      Text("Retry")
    }
  }
}

