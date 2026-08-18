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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.JellyfinSortDialog
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

  // Intercept back button if searching or browsing inside a Jellyfin folder
  BackHandler(enabled = isSearching || uiState.breadcrumbs.isNotEmpty()) {
    if (isSearching) {
      isSearching = false
      viewModel.onSearchQueryChanged("")
      viewModel.refresh()
    } else {
      viewModel.navigateBack()
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
        isInSelectionMode = false,
        selectedCount = 0,
        totalCount = uiState.currentItems.size,
        onCancelSelection = { },
        onBackClick = if (uiState.breadcrumbs.isNotEmpty()) { { viewModel.navigateBack() } } else null,
        onSortClick = { isSortDialogOpen = true },
        onSearchClick = { isSearching = true },
        onSettingsClick = {
          backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
        },
        additionalActions = {
          IconButton(onClick = { isManageServersOpen = true }) {
            Icon(
              imageVector = Icons.RoundedFilled.BringYourOwnIp,
              contentDescription = "Manage Servers",
            )
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
              .clickable {
                while (uiState.breadcrumbs.isNotEmpty()) {
                  viewModel.navigateBack()
                }
              }.padding(horizontal = 4.dp, vertical = 2.dp),
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
                  val steps = uiState.breadcrumbs.size - 1 - index
                  repeat(steps) { viewModel.navigateBack() }
                }.padding(horizontal = 4.dp, vertical = 2.dp),
          )
        }
      }
    }

    // Main Body Content with Pull-To-Refresh
    val isRefreshing = remember { mutableStateOf(false) }
    PullRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = { viewModel.refresh() },
      modifier =
        Modifier
          .fillMaxSize()
          .weight(1f),
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
          val navigationBarHeight = LocalNavigationBarHeight.current
          val hasEnoughLibraries = uiState.libraries.size > 6
          val scrollbarAlpha by animateFloatAsState(
            targetValue = if (hasEnoughLibraries) 1f else 0f,
            label = "scrollbarAlpha",
          )

          Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navigationBarHeight + 16.dp),
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
                            onClick = { viewModel.playItem(context, resumeItem) },
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
                    .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
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
            val navigationBarHeight = LocalNavigationBarHeight.current
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navigationBarHeight + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                items(items, key = { it.id }) { item ->
                  uiState.activeServer?.let { server ->
                    if (allEpisodes || item.type == "Episode") {
                      JellyfinEpisodeCard(
                        item = item,
                        server = server,
                        onPlay = { viewModel.playItem(context, item) },
                      )
                    } else {
                      JellyfinListItemCard(
                        item = item,
                        server = server,
                        onClick = {
                          if (item.isFolder || item.isSeries || item.isSeason) {
                            viewModel.navigateToItem(item)
                          } else {
                            viewModel.playItem(context, item)
                          }
                        },
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
                      .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
                      .graphicsLayer { alpha = scrollbarAlpha },
                )
              }
            }
          } else {
            val gridState =
              remember(uiState.breadcrumbs, uiState.sortBy, uiState.sortOrder, uiState.isUnplayedOnly) {
                LazyGridState()
              }
            val navigationBarHeight = LocalNavigationBarHeight.current
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
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = navigationBarHeight + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                items(items, key = { it.id }) { item ->
                  uiState.activeServer?.let { server ->
                    JellyfinPosterCard(
                      item = item,
                      server = server,
                      onClick = {
                        if (item.isFolder || item.isSeries || item.isSeason) {
                          viewModel.navigateToItem(item)
                        } else {
                          viewModel.playItem(context, item)
                        }
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
                      .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
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
        Icon(
          imageVector = Icons.RoundedFilled.BringYourOwnIp,
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
