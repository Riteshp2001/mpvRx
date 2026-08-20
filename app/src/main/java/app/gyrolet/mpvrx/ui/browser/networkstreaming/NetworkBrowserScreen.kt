/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.NetworkFolderCard
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.NetworkSortDialog
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class NetworkBrowserScreen(
  val connectionId: Long,
  val connectionName: String,
  val currentPath: String = "/",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val browserPreferences = koinInject<BrowserPreferences>()

    val networkSortType by browserPreferences.networkSortType.collectAsState()
    val networkSortOrder by browserPreferences.networkSortOrder.collectAsState()
    val networkLayoutMode by browserPreferences.networkLayoutMode.collectAsState()
    val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
    val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
    val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

    val viewModel: NetworkBrowserViewModel =
      viewModel(
        key = "NetworkBrowser_${connectionId}_$currentPath",
        factory =
          NetworkBrowserViewModel.factory(
            context.applicationContext as android.app.Application,
            connectionId,
            currentPath,
          ),
      )

    val files by viewModel.files.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }
    val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    LaunchedEffect(viewModel) {
      viewModel.importedPlaylistId.collect { playlistId ->
        backstack.add(PlaylistDetailScreen(playlistId))
      }
    }

    BackHandler {
      if (isSearching) {
        isSearching = false
        searchQuery = ""
      } else {
        backstack.popSafely()
      }
    }

    Scaffold(
      topBar = {
        if (isSearching) {
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = {
                  Text(stringResource(R.string.settings_search_title))
                },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.RoundedFilled.Search,
                    contentDescription = stringResource(R.string.settings_search_title),
                  )
                },
                trailingIcon = {
                  IconButton(
                    onClick = {
                      isSearching = false
                      searchQuery = ""
                    },
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = stringResource(R.string.generic_cancel),
                    )
                  }
                },
                modifier = Modifier.focusRequester(focusRequester),
              )
            },
            expanded = false,
            onExpandedChange = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          ) {
            // Empty content for SearchBar
          }
        } else {
          BrowserTopBar(
            title = connectionName,
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = files.size,
            onBackClick = { backstack.popSafely() },
            onCancelSelection = {},
            onSortClick = { sortDialogOpen.value = true },
            onSearchClick = { isSearching = true },
            onSettingsClick = {
              backstack.add(PreferencesScreen)
            },
            onDeleteClick = null,
            onRenameClick = null,
            isSingleSelection = false,
            onInfoClick = null,
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = null,
            onInvertSelection = null,
            onDeselectAll = null,
          )
        }
      },
    ) { padding ->
      NetworkBrowserContent(
        files = files,
        connection = connection,
        isLoading = isLoading && files.isEmpty(),
        isRefreshing = isRefreshing,
        error = error,
        networkSortType = networkSortType,
        networkSortOrder = networkSortOrder,
        networkLayoutMode = networkLayoutMode,
        manualGridColumnsEnabled = manualGridColumnsEnabled,
        videoGridColumnsPortrait = videoGridColumnsPortrait,
        videoGridColumnsLandscape = videoGridColumnsLandscape,
        searchQuery = searchQuery,
        onRefresh = { viewModel.loadFiles() },
        onFolderClick = { folder ->
          backstack.add(
            NetworkBrowserScreen(
              connectionId = connectionId,
              connectionName = connectionName,
              currentPath = folder.path,
            ),
          )
        },
        onVideoClick = { video ->
          viewModel.openMedia(video)
        },
        modifier = Modifier.padding(padding),
      )

      NetworkSortDialog(
        isOpen = sortDialogOpen.value,
        onDismiss = { sortDialogOpen.value = false },
      )
    }
  }
}

@Composable
private fun NetworkBrowserContent(
  files: List<NetworkFile>,
  connection: NetworkConnection?,
  isLoading: Boolean,
  isRefreshing: MutableState<Boolean>,
  error: String?,
  networkSortType: NetworkSortType,
  networkSortOrder: SortOrder,
  networkLayoutMode: MediaLayoutMode,
  manualGridColumnsEnabled: Boolean,
  videoGridColumnsPortrait: Int,
  videoGridColumnsLandscape: Int,
  searchQuery: String,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sortedFiles =
    remember(files, networkSortType, networkSortOrder) {
      val (dirList, fileList) = files.partition { it.isDirectory }

      val sortedDirs =
        when (networkSortType) {
          NetworkSortType.Title ->
            if (networkSortOrder.isAscending) dirList.sortedBy { it.name.lowercase() }
            else dirList.sortedByDescending { it.name.lowercase() }
          NetworkSortType.Date ->
            if (networkSortOrder.isAscending) dirList.sortedBy { it.lastModified }
            else dirList.sortedByDescending { it.lastModified }
          NetworkSortType.Size ->
            if (networkSortOrder.isAscending) dirList.sortedBy { it.size }
            else dirList.sortedByDescending { it.size }
        }

      val sortedMedia =
        when (networkSortType) {
          NetworkSortType.Title ->
            if (networkSortOrder.isAscending) fileList.sortedBy { it.name.lowercase() }
            else fileList.sortedByDescending { it.name.lowercase() }
          NetworkSortType.Date ->
            if (networkSortOrder.isAscending) fileList.sortedBy { it.lastModified }
            else fileList.sortedByDescending { it.lastModified }
          NetworkSortType.Size ->
            if (networkSortOrder.isAscending) fileList.sortedBy { it.size }
            else fileList.sortedByDescending { it.size }
        }

      sortedDirs + sortedMedia
    }

  val filteredFiles =
    remember(sortedFiles, searchQuery) {
      if (searchQuery.isBlank()) {
        sortedFiles
      } else {
        sortedFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
      }
    }

  when {
    isLoading -> {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    error != null -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(R.string.ui_error_loading_files),
          message = error,
        )
      }
    }

    files.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(R.string.ui_empty_folder),
          message = "This folder contains no files or directories",
        )
      }
    }

    filteredFiles.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Search,
          title = stringResource(R.string.settings_search_title),
          message = "No items match '$searchQuery'",
        )
      }
    }

    else -> {
      val folders = remember(filteredFiles) { filteredFiles.filter { it.isDirectory } }
      val videos =
        remember(filteredFiles) {
          filteredFiles.filter { !it.isDirectory && (it.mimeType?.startsWith("video/") == true || it.isM3uFile()) }
        }
      val isGrid = networkLayoutMode == MediaLayoutMode.GRID

      val configuration = LocalConfiguration.current
      val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
      val isTablet = configuration.smallestScreenWidthDp >= 600
      val gridColumns =
        if (manualGridColumnsEnabled) {
          if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
        } else {
          if (isTablet || isLandscape) 4 else 2
        }

      val listState = rememberLazyListState()
      val gridState = rememberLazyGridState()
      val hasEnoughItems = (folders.size + videos.size) > 20

      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hasEnoughItems) 1f else 0f,
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
            stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
          ),
        label = "scrollbarAlpha",
      )

      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
      ) {
        val scrollbarLabels =
          remember(folders, videos) {
            buildList<String?> {
              if (folders.isNotEmpty()) {
                add(null)
                addAll(folders.map { it.name })
              }
              if (videos.isNotEmpty()) {
                add(null)
                addAll(videos.map { it.name })
              }
            }
          }
        val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(bottom = navigationBarHeight),
        ) {
          if (isGrid) {
            LazyVerticalGrid(
              columns = GridCells.Fixed(gridColumns),
              state = gridState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 8.dp,
                  bottom = navigationBarHeight,
                ),
            ) {
              if (folders.isNotEmpty()) {
                item(span = { GridItemSpan(gridColumns) }) {
                  Text(
                    text = stringResource(R.string.pref_folders_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = folders,
                  key = { it.path },
                ) { folder ->
                  NetworkFolderCard(
                    file = folder,
                    onClick = { onFolderClick(folder) },
                    isGridMode = true,
                    modifier = Modifier,
                  )
                }
              }

              if (videos.isNotEmpty()) {
                item(span = { GridItemSpan(gridColumns) }) {
                  Text(
                    text = stringResource(R.string.ui_videos),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = videos,
                  key = { it.path },
                ) { video ->
                  connection?.let { conn ->
                    NetworkVideoCard(
                      file = video,
                      connection = conn,
                      onClick = { onVideoClick(video) },
                      isGridMode = true,
                      modifier = Modifier,
                    )
                  }
                }
              }
            }
          } else {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 8.dp,
                  bottom = navigationBarHeight,
                ),
            ) {
              if (folders.isNotEmpty()) {
                item {
                  Text(
                    text = stringResource(R.string.pref_folders_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = folders,
                  key = { it.path },
                ) { folder ->
                  NetworkFolderCard(
                    file = folder,
                    onClick = { onFolderClick(folder) },
                    isGridMode = false,
                    modifier = Modifier,
                  )
                }
              }

              if (videos.isNotEmpty()) {
                item {
                  Text(
                    text = stringResource(R.string.ui_videos),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = videos,
                  key = { it.path },
                ) { video ->
                  connection?.let { conn ->
                    NetworkVideoCard(
                      file = video,
                      connection = conn,
                      onClick = { onVideoClick(video) },
                      isGridMode = false,
                      modifier = Modifier,
                    )
                  }
                }
              }
            }
          }

          if (hasEnoughItems && scrollbarAlpha > 0.01f) {
            ExpressiveScrollBar(
              listState = if (!isGrid) listState else null,
              gridState = if (isGrid) gridState else null,
              dragLabelProvider = { index: Int ->
                fastScrollGlyph(scrollbarLabels.getOrNull(index))
              },
              modifier =
                Modifier
                  .align(Alignment.CenterEnd)
                  .padding(end = 4.dp)
                  .graphicsLayer { alpha = scrollbarAlpha },
            )
          }
        }
      }
    }
  }
}

private fun NetworkFile.isM3uFile(): Boolean {
  val lowerName = name.lowercase()
  val lowerPath = path.substringBefore('?').lowercase()
  return lowerName.endsWith(".m3u") ||
    lowerName.endsWith(".m3u8") ||
    lowerPath.endsWith(".m3u") ||
    lowerPath.endsWith(".m3u8") ||
    mimeType in
    setOf(
      "application/x-mpegurl",
      "application/vnd.apple.mpegurl",
      "audio/x-mpegurl",
      "audio/mpegurl",
    )
}
