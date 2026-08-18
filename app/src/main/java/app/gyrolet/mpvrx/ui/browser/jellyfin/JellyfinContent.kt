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
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
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
  var infoItem by remember { mutableStateOf<JellyfinItem?>(null) }
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

  // Intercept back button if searching, selecting, or browsing inside a Jellyfin folder
  BackHandler(enabled = isSearching || selectionManager.isInSelectionMode || uiState.breadcrumbs.isNotEmpty()) {
    when {
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
      uiState.breadcrumbs.isNotEmpty() -> uiState.breadcrumbs.last().title
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
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        SearchBar(
          inputField = {
            SearchBarDefaults.InputField(
              query = uiState.searchQuery,
              onQueryChange = {
                viewModel.onSearchQueryChanged(it)
                viewModel.performSearch(it)
              },
              onSearch = { viewModel.performSearch(uiState.searchQuery) },
              expanded = false,
              onExpandedChange = { },
              placeholder = { Text(stringResource(R.string.settings_search_title)) },
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
        onBackClick = if (uiState.breadcrumbs.isNotEmpty()) { { viewModel.navigateBack() } } else null,
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

    // Breadcrumbs Trail (When inside subfolders)
    if (uiState.breadcrumbs.isNotEmpty() && !isSearching) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Libraries",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier =
            Modifier
              .clip(RoundedCornerShape(4.dp))
              .clickable { viewModel.navigateToRoot() }
              .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        uiState.breadcrumbs.forEachIndexed { index, crumb ->
          Icon(
            imageVector = Icons.RoundedFilled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          val isLast = index == uiState.breadcrumbs.lastIndex
          Text(
            text = crumb.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
            color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = !isLast) {
                  viewModel.navigateToBreadcrumb(index)
                }.padding(horizontal = 4.dp, vertical = 2.dp),
          )
        }
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
        onRefresh = { viewModel.refresh() },
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
            uiState.isLoading && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() -> {
              CircularProgressIndicator()
            }

            // Error state
            uiState.error != null && uiState.libraries.isEmpty() && uiState.currentItems.isEmpty() -> {
              ErrorView(
                message = uiState.error ?: "An error occurred",
                onRetry = { viewModel.refresh() },
              )
            }

            // Root View: Continue Watching carousel + Libraries
            uiState.breadcrumbs.isEmpty() && uiState.searchQuery.isBlank() -> {
              val listState = rememberLazyListState()
              val hasEnoughLibraries = uiState.libraries.size > 6
              val scrollbarAlpha by animateFloatAsState(
                targetValue = if (hasEnoughLibraries) 1f else 0f,
                label = "scrollbarAlpha",
              )

              Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                  state = listState,
                  modifier = Modifier.fillMaxSize(),
                  contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navigationBarHeight + 80.dp),
                  verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                  // Continue Watching carousel
                  if (uiState.resumeItems.isNotEmpty()) {
                    item {
                      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                          text = "Continue Watching",
                          style = MaterialTheme.typography.titleMedium,
                          fontWeight = FontWeight.Bold,
                        )
                        LazyRow(
                          horizontalArrangement = Arrangement.spacedBy(12.dp),
                          contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                          items(uiState.resumeItems, key = { it.id }) { resumeItem ->
                            uiState.activeServer?.let { server ->
                              JellyfinResumeCard(
                                item = resumeItem,
                                server = server,
                                isSelected = selectionManager.isSelected(resumeItem),
                                isInSelectionMode = selectionManager.isInSelectionMode,
                                onClick = {
                                  if (selectionManager.isInSelectionMode) {
                                    selectionManager.toggle(resumeItem)
                                  } else {
                                    viewModel.playItem(context, resumeItem)
                                  }
                                },
                                onLongClick = { selectionManager.handleLongClick(resumeItem) },
                              )
                            }
                          }
                        }
                      }
                    }
                  }

                  // Libraries Section
                  item {
                    Text(
                      text = "Libraries",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                    )
                  }

                  items(uiState.libraries, key = { it.id }) { library ->
                    JellyfinLibraryCard(
                      item = library,
                      onClick = { viewModel.navigateToItem(library) },
                    )
                  }
                }

                if (hasEnoughLibraries && scrollbarAlpha > 0.01f) {
                  ExpressiveScrollBar(
                    listState = listState,
                    dragLabelProvider = { index ->
                      fastScrollGlyph(uiState.libraries.getOrNull(index)?.name)
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

            // Level View: Inside a Library / Folder / Show / Season / Search results
            else -> {
              val items = uiState.currentItems
              val allEpisodes = items.isNotEmpty() && items.all { it.type == "Episode" }
              val isListMode = layoutMode == MediaLayoutMode.LIST || allEpisodes

              if (items.isEmpty() && !uiState.isLoading) {
                Text(
                  text = "No media found in this folder",
                  style = MaterialTheme.typography.bodyLarge,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              } else if (isListMode) {
                val listState =
                  remember(uiState.breadcrumbs, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) {
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
                              } else if (item.isFolder || item.isSeries || item.isSeason) {
                                viewModel.navigateToItem(item)
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
                  remember(uiState.breadcrumbs, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) {
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
                            } else if (item.isFolder || item.isSeries || item.isSeason) {
                              viewModel.navigateToItem(item)
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
                  onClick = { infoItem = selectedItems.first() },
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

      if (!selectionManager.isInSelectionMode && uiState.activeServer != null && (uiState.currentItems.isNotEmpty() || uiState.resumeItems.isNotEmpty())) {
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

  // Item Info Bottom Sheet
  infoItem?.let { item ->
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { infoItem = null },
      sheetState = sheetState,
    ) {
      Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        HorizontalDivider()

        @Composable
        fun InfoRow(label: String, value: String) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.width(100.dp),
            )
            Text(
              text = value,
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }

        InfoRow("Type", item.type.replace(Regex("([a-z])([A-Z])"), "$1 $2"))
        item.seriesName?.let { InfoRow("Series", it) }
        item.parentIndexNumber?.let { s ->
          item.indexNumber?.let { e ->
            InfoRow("Episode", "S$s E$e")
          }
        }
        item.productionYear?.let { InfoRow("Year", it.toString()) }
        item.communityRating?.let { InfoRow("Rating", "%.1f / 10".format(it)) }
        if (item.durationSeconds > 0) {
          val h = item.durationSeconds / 3600
          val m = (item.durationSeconds % 3600) / 60
          val s = item.durationSeconds % 60
          val dur = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
          InfoRow("Duration", dur)
        }
        item.container?.let { InfoRow("Container", it.uppercase()) }
        if (item.childCount != null && item.childCount > 0) {
          InfoRow("Items", item.childCount.toString())
        }
        item.overview?.let {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(16.dp))
      }
    }
  }
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
