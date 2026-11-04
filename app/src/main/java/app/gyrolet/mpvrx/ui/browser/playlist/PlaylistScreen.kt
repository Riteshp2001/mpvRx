/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.playlist

import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.MediaLibraryType
import app.gyrolet.mpvrx.preferences.PlaylistSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalIsMainTabPage
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.cards.PlaylistCard
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionListGap
import app.gyrolet.mpvrx.ui.browser.cards.mediaSelectionNeighbors
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.PlaylistSortDialog
import app.gyrolet.mpvrx.ui.browser.fab.FabPlacement
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.browser.selection.SelectionManager
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlaylistActionSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberTabNavigation
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Imported stream and archive lists stay with videos even when they carry audio entries. */
private fun PlaylistEntity.belongsToAudioLibrary(): Boolean = isAudio && !isM3uPlaylist && !isZipPlaylist

private fun PlaylistEntity.isFavoritesPlaylist(): Boolean =
  name.equals(PlaylistRepository.FAVORITES_PLAYLIST_NAME, ignoreCase = true)

/** Added playlists come first; the chosen sort orders them ahead of the built-in favorites. */
private fun arrangePlaylists(
  items: List<PlaylistWithCount>,
  type: PlaylistSortType,
  order: SortOrder,
): List<PlaylistWithCount> {
  val (favorites, others) = items.partition { it.playlist.isFavoritesPlaylist() }
  return sortPlaylists(others, type, order) + favorites
}

private val LIBRARY_SORT_TYPES =
  setOf(PlaylistSortType.Original, PlaylistSortType.Name, PlaylistSortType.Location, PlaylistSortType.DateAdded, PlaylistSortType.ItemCount)

@Serializable
object PlaylistScreen : Screen {
  @OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    com.google.accompanist.permissions.ExperimentalPermissionsApi::class,
  )
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val backStack = LocalBackStack.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var contentWidthDp by remember { mutableStateOf<Int?>(null) }

    val viewModel: PlaylistViewModel =
      viewModel(factory = PlaylistViewModel.factory(context.applicationContext as android.app.Application))

    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
      viewModel.startMonitoring()
      onPauseOrDispose { viewModel.stopMonitoring() }
    }
    app.gyrolet.mpvrx.utils.permission.PermissionUtils.handleStoragePermission {
      viewModel.refresh(scanLocalFiles = true)
    }

    val playlistsWithCount by viewModel.playlistsWithCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasCompletedInitialLoad by viewModel.hasCompletedInitialLoad.collectAsState()
    val savedSortType by browserPreferences.playlistSortType.collectAsState()
    val sortOrder by browserPreferences.playlistSortOrder.collectAsState()
    val layoutMode by browserPreferences.playlistView.layoutMode.collectAsState()
    val sortType = savedSortType.takeIf { it in LIBRARY_SORT_TYPES } ?: PlaylistSortType.Original

    val favoriteAudioLabel = stringResource(R.string.playlist_favorite_songs)
    val favoriteVideosLabel = stringResource(R.string.playlist_favorite_videos)

    var libraryType by rememberSaveable { mutableStateOf(MediaLibraryType.Video) }
    val pagerState = rememberPagerState(initialPage = libraryType.ordinal) { MediaLibraryType.entries.size }
    val navigateTab = rememberTabNavigation(pagerState)

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val activeQuery = if (isSearching) searchQuery.trim() else ""

    val playlistsByType =
      remember(playlistsWithCount, sortType, sortOrder) {
        val (audio, video) = playlistsWithCount.partition { it.playlist.belongsToAudioLibrary() }
        mapOf(
          MediaLibraryType.Video to arrangePlaylists(video, sortType, sortOrder),
          MediaLibraryType.Audio to arrangePlaylists(audio, sortType, sortOrder),
        )
      }
    val visiblePlaylistsByType =
      remember(playlistsByType, activeQuery, favoriteAudioLabel, favoriteVideosLabel) {
        if (activeQuery.isEmpty()) {
          playlistsByType
        } else {
          playlistsByType.mapValues { (_, items) ->
            items.filter { item ->
              val playlist = item.playlist
              val displayName =
                when {
                  !playlist.isFavoritesPlaylist() -> playlist.name
                  playlist.isAudio -> favoriteAudioLabel
                  else -> favoriteVideosLabel
                }
              displayName.contains(activeQuery, ignoreCase = true) ||
                playlist.name.contains(activeQuery, ignoreCase = true) ||
                playlistSourceLocation(playlist.m3uSourceUrl ?: playlist.xtreamServerUrl)
                  .contains(activeQuery, ignoreCase = true)
            }
          }
        }
      }
    val currentPlaylists = visiblePlaylistsByType.getValue(libraryType)

    val selectionManager =
      rememberSelectionManager(
        items = currentPlaylists,
        getId = { it.playlist.id },
        onDeleteItems = { itemsToDelete, _ ->
          val deletable = itemsToDelete.filterNot { viewModel.isProtectedPlaylist(it.playlist) }
          deletable.forEach { viewModel.deletePlaylist(it.playlist) }
          Pair(deletable.size, 0)
        },
        onOperationComplete = { viewModel.refresh() },
      )
    val selectedPlaylists = selectionManager.getSelectedItems()
    val deletableSelection = selectedPlaylists.filterNot { viewModel.isProtectedPlaylist(it.playlist) }
    val singleRenamable = selectedPlaylists.singleOrNull()?.takeUnless { viewModel.isProtectedPlaylist(it.playlist) }

    LaunchedEffect(isSearching) {
      if (isSearching) {
        focusRequester.requestFocus()
        keyboardController?.show()
      }
    }
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
      if (!pagerState.isScrollInProgress) {
        MediaLibraryType.entries.getOrNull(pagerState.settledPage)?.let { type ->
          if (libraryType != type) {
            selectionManager.clear()
            libraryType = type
          }
        }
      }
    }
    LaunchedEffect(libraryType) { navigateTab(libraryType.ordinal) }

    val listStates = remember { MediaLibraryType.entries.associateWith { LazyListState() } }
    val gridStates = remember { MediaLibraryType.entries.associateWith { LazyGridState() } }
    val isRefreshing = remember { mutableStateOf(false) }
    val isFabVisible = remember { mutableStateOf(true) }
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var directDeleteTarget by remember { mutableStateOf<PlaylistWithCount?>(null) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var showPlaylistActionSheet by remember { mutableStateOf(false) }
    var showCreateAudioDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selectionManager.isInSelectionMode || isSearching) {
      when {
        selectionManager.isInSelectionMode -> selectionManager.clear()
        else -> {
          isSearching = false
          searchQuery = ""
        }
      }
    }
    NavigationBarSelectionEffect(selectionManager.isInSelectionMode)
    FabScrollHelper.trackScrollForFabVisibility(
      listState = listStates.getValue(libraryType),
      gridState = if (layoutMode == MediaLayoutMode.GRID) gridStates.getValue(libraryType) else null,
      isFabVisible = isFabVisible,
      expanded = false,
      onExpandedChange = {},
    )

    PlaylistSortDialog(
      isOpen = showSortDialog,
      onDismiss = { showSortDialog = false },
      isLibrary = true,
      availableWidthDp = contentWidthDp,
    )

    Scaffold(
      containerColor = wallpaperAwareBackgroundColor(),
      topBar = {
        if (isSearching) {
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.ui_search_playlists)) },
            leadingIcon = {
              Icon(Icons.RoundedFilled.Search, contentDescription = stringResource(R.string.settings_search_title))
            },
            trailingIcon = {
              IconButton(
                onClick = {
                  isSearching = false
                  searchQuery = ""
                },
              ) {
                Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.generic_cancel))
              }
            },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
            title = stringResource(R.string.ui_playlists),
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectionManager.selectedCount,
            totalCount = currentPlaylists.size,
            onBackClick = if (LocalIsMainTabPage.current) null else ({ backStack.popSafely() }),
            onCancelSelection = { selectionManager.clear() },
            isSingleSelection = selectionManager.isSingleSelection,
            onSortClick = { showSortDialog = true },
            onSearchClick = { isSearching = true },
            onSettingsClick = { backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen) },
            onRenameClick = singleRenamable?.let { target -> { renameTarget = target.playlist } },
            onDeleteClick = if (deletableSelection.isEmpty()) null else ({ showDeleteDialog = true }),
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
          )
        }
      },
      floatingActionButton = {
        if (!selectionManager.isInSelectionMode && isFabVisible.value) {
          ExtendedFloatingActionButton(
            onClick = {
              if (libraryType == MediaLibraryType.Audio) showCreateAudioDialog = true else showPlaylistActionSheet = true
            },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.ui_create_playlist)) },
            modifier = Modifier.padding(bottom = FabPlacement.scaffoldBottomPadding()),
          )
        }
      },
    ) { paddingValues ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .onSizeChanged { contentWidthDp = with(density) { it.width.toDp().value.toInt() } },
      ) {
        SingleChoiceSegmentedButtonRow(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          MediaLibraryType.entries.forEachIndexed { index, type ->
            SegmentedButton(
              selected = libraryType == type,
              onClick = {
                if (libraryType != type) {
                  selectionManager.clear()
                  libraryType = type
                }
              },
              shape = SegmentedButtonDefaults.itemShape(index, MediaLibraryType.entries.size),
              colors = themedSegmentedButtonColors(),
            ) {
              Text(stringResource(if (type == MediaLibraryType.Audio) R.string.ui_audio_tab else R.string.ui_videos))
            }
          }
        }

        NavigationPager(
          state = pagerState,
          modifier = Modifier.fillMaxWidth().weight(1f),
          allowNestedSwipes = true,
          userScrollEnabled = !selectionManager.isInSelectionMode,
        ) { page ->
          val pageType = MediaLibraryType.entries.getOrNull(page) ?: MediaLibraryType.Video
          val allForType = playlistsByType.getValue(pageType)
          val visibleForType = visiblePlaylistsByType.getValue(pageType)
          when {
            allForType.isEmpty() && (isLoading || !hasCompletedInitialLoad) ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            allForType.isEmpty() ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                  icon = if (pageType == MediaLibraryType.Audio) Icons.RoundedFilled.QueueMusic else Icons.RoundedFilled.PlaylistAdd,
                  title = stringResource(R.string.ui_no_playlists_yet),
                  message =
                    stringResource(
                      if (pageType == MediaLibraryType.Audio) {
                        R.string.playlist_audio_empty_description
                      } else {
                        R.string.playlist_empty_description
                      },
                    ),
                )
              }

            visibleForType.isEmpty() ->
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                  icon = Icons.RoundedFilled.Search,
                  title = stringResource(R.string.ui_no_playlists_found),
                  message = stringResource(R.string.ui_try_a_different_search_term),
                )
              }

            else ->
              PlaylistListContent(
                playlistsWithCount = visibleForType,
                listState = listStates.getValue(pageType),
                gridState = gridStates.getValue(pageType),
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh(scanLocalFiles = true).join() },
                selectionManager = selectionManager,
                onPlaylistClick = { item ->
                  if (selectionManager.isInSelectionMode) {
                    selectionManager.toggleFromUser(item)
                  } else {
                    backStack.navigateTo(PlaylistDetailScreen(item.playlist.id))
                  }
                },
                onPlaylistLongClick = { item -> selectionManager.handleLongClick(item) },
                onPlaylistRename = { item -> renameTarget = item.playlist },
                onPlaylistDelete = { item -> directDeleteTarget = item },
                isPlaylistProtected = { item -> viewModel.isProtectedPlaylist(item.playlist) },
                isInSelectionMode = selectionManager.isInSelectionMode,
              )
          }
        }
      }
    }

    PlaylistActionSheet(
      isOpen = showPlaylistActionSheet,
      savedXtreamServerUrls = playlistsWithCount.mapNotNull { it.playlist.xtreamServerUrl },
      onDismiss = { showPlaylistActionSheet = false },
      onCreatePlaylist = { name -> viewModel.createPlaylist(name) },
      onCreateM3UPlaylistFromFile = viewModel::createM3UPlaylistFromFile,
      onCreateZipPlaylist = viewModel::createZipPlaylist,
      onCreateM3UPlaylist = viewModel::createM3UPlaylist,
      onCreateXtreamPlaylist = viewModel::createXtreamPlaylist,
      context = context,
    )

    if (showCreateAudioDialog) {
      PlaylistNameDialog(
        title = stringResource(R.string.ui_create_playlist),
        initialName = "",
        confirmLabel = stringResource(R.string.ui_create),
        onConfirm = { name ->
          showCreateAudioDialog = false
          scope.launch {
            val id = viewModel.createPlaylist(name, isAudio = true)
            if (id > 0) backStack.navigateTo(PlaylistDetailScreen(id.toInt()))
          }
        },
        onDismiss = { showCreateAudioDialog = false },
      )
    }

    renameTarget?.let { target ->
      PlaylistNameDialog(
        title = stringResource(R.string.ui_rename_playlist),
        initialName = target.name,
        confirmLabel = stringResource(R.string.rename),
        onConfirm = { name ->
          renameTarget = null
          scope.launch {
            viewModel.updatePlaylist(target.copy(name = name))
            selectionManager.clear()
          }
        },
        onDismiss = { renameTarget = null },
      )
    }

    if (showDeleteDialog && deletableSelection.isNotEmpty()) {
      DeleteConfirmationDialog(
        isOpen = true,
        onDismiss = { showDeleteDialog = false },
        onConfirm = {
          selectionManager.deleteSelected()
          showDeleteDialog = false
        },
        itemCount = deletableSelection.size,
        itemType = "playlist",
        itemNames = deletableSelection.map { it.playlist.name },
      )
    }

    directDeleteTarget?.let { target ->
      DeleteConfirmationDialog(
        isOpen = true,
        onDismiss = { directDeleteTarget = null },
        onConfirm = {
          directDeleteTarget = null
          scope.launch {
            viewModel.deletePlaylist(target.playlist)
            viewModel.refresh()
          }
        },
        itemCount = 1,
        itemType = "playlist",
        itemNames = listOf(target.playlist.name),
      )
    }
  }

  @Composable
  private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
  ) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val trimmed = name.trim()
    // "Favorites" is the reserved per-type favorites list; a second one would be undeletable.
    val isValid = trimmed.isNotEmpty() && !trimmed.equals(PlaylistRepository.FAVORITES_PLAYLIST_NAME, ignoreCase = true)
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(title) },
      text = {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text(stringResource(R.string.ui_playlist_name)) },
          singleLine = true,
          isError = trimmed.isNotEmpty() && !isValid,
          modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(onClick = { onConfirm(trimmed) }, enabled = isValid) { Text(confirmLabel) }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
      },
    )
  }

  @Composable
  private fun PlaylistListContent(
    playlistsWithCount: List<PlaylistWithCount>,
    listState: LazyListState,
    gridState: LazyGridState,
    isRefreshing: MutableState<Boolean>,
    onRefresh: suspend () -> Unit,
    selectionManager: SelectionManager<PlaylistWithCount, Int>,
    onPlaylistClick: (PlaylistWithCount) -> Unit,
    onPlaylistLongClick: (PlaylistWithCount) -> Unit,
    onPlaylistRename: (PlaylistWithCount) -> Unit,
    onPlaylistDelete: (PlaylistWithCount) -> Unit,
    isPlaylistProtected: (PlaylistWithCount) -> Boolean,
    modifier: Modifier = Modifier,
    isInSelectionMode: Boolean = false,
  ) {
    val browserPreferences = koinInject<BrowserPreferences>()
    val viewPreferences = browserPreferences.playlistView
    val mediaLayoutMode by viewPreferences.layoutMode.collectAsState()
    val manualGridColumnsEnabled by viewPreferences.manualGridColumnsEnabled.collectAsState()
    val folderGridColumnsPortrait by viewPreferences.gridColumnsPortrait.collectAsState()
    val folderGridColumnsLandscape by viewPreferences.gridColumnsLandscape.collectAsState()
    val navigationBarHeight = LocalNavigationBarHeight.current
    val bottomOverlayClearance = navigationBarHeight
    val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID
    val hasEnoughItems = playlistsWithCount.size > 20
    val bottomPadding =
      if (isInSelectionMode) {
        maxOf(bottomOverlayClearance, NavigationBarState.selectionBarClearance)
      } else {
        bottomOverlayClearance + 72.dp
      }

    val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
      targetValue = if (hasEnoughItems) 1f else 0f,
      animationSpec =
        androidx.compose.animation.core.spring(
          dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
          stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
        ),
      label = "scrollbarAlpha",
    )
    val dragLabel: (Int) -> String? = { index -> fastScrollGlyph(playlistsWithCount.getOrNull(index)?.playlist?.name) }

    PullRefreshBox(
      isRefreshing = isRefreshing,
      onRefresh = onRefresh,
      listState = listState,
      modifier = modifier.fillMaxSize(),
    ) {
      if (isGridMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val configuration = androidx.compose.ui.platform.LocalConfiguration.current
          val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
          val folderGridColumnsPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
          val maximumColumns = playlistGridColumnLimit(maxWidth.value.toInt(), true)
          val folderGridColumns =
            if (manualGridColumnsEnabled && folderGridColumnsPref > 0) {
              folderGridColumnsPref.coerceIn(1, maximumColumns)
            } else {
              maximumColumns
            }

          LazyVerticalGrid(
            columns = GridCells.Fixed(folderGridColumns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
          ) {
            items(count = playlistsWithCount.size, key = { playlistsWithCount[it].playlist.id }) { index ->
              val playlistWithCount = playlistsWithCount[index]
              val neighbors = mediaSelectionNeighbors(index, playlistsWithCount.size, folderGridColumns) { n -> selectionManager.isSelected(playlistsWithCount[n]) }
              PlaylistCard(
                playlist = playlistWithCount.playlist,
                itemCount = playlistWithCount.itemCount,
                sources = playlistWithCount.sources,
                isSelected = selectionManager.isSelected(playlistWithCount),
                selectionNeighbors = neighbors,
                onClick = { onPlaylistClick(playlistWithCount) },
                onLongClick = { onPlaylistLongClick(playlistWithCount) },
                onThumbClick = { onPlaylistClick(playlistWithCount) },
                isGridMode = true,
                onRenameClick =
                  if (isPlaylistProtected(playlistWithCount)) {
                    null
                  } else {
                    { onPlaylistRename(playlistWithCount) }
                  },
                onDeleteClick =
                  if (isPlaylistProtected(playlistWithCount)) {
                    null
                  } else {
                    { onPlaylistDelete(playlistWithCount) }
                  },
              )
            }
          }
          if (hasEnoughItems && scrollbarAlpha > 0.01f) {
            ExpressiveScrollBar(
              gridState = gridState,
              dragLabelProvider = dragLabel,
              modifier =
                Modifier
                  .align(Alignment.CenterEnd)
                  .padding(end = 2.dp, top = 6.dp, bottom = bottomOverlayClearance + 6.dp)
                  .graphicsLayer { alpha = scrollbarAlpha },
            )
          }
        }
      } else {
        Box(modifier = Modifier.fillMaxSize()) {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(MediaSelectionListGap),
          ) {
            items(count = playlistsWithCount.size, key = { playlistsWithCount[it].playlist.id }) { index ->
              val playlistWithCount = playlistsWithCount[index]
              val neighbors = mediaSelectionNeighbors(index, playlistsWithCount.size, 1) { n -> selectionManager.isSelected(playlistsWithCount[n]) }
              PlaylistCard(
                playlist = playlistWithCount.playlist,
                itemCount = playlistWithCount.itemCount,
                sources = playlistWithCount.sources,
                isSelected = selectionManager.isSelected(playlistWithCount),
                selectionNeighbors = neighbors,
                onClick = { onPlaylistClick(playlistWithCount) },
                onLongClick = { onPlaylistLongClick(playlistWithCount) },
                onThumbClick = { onPlaylistClick(playlistWithCount) },
                isGridMode = false,
              )
            }
          }
          if (hasEnoughItems && scrollbarAlpha > 0.01f) {
            ExpressiveScrollBar(
              listState = listState,
              dragLabelProvider = dragLabel,
              modifier =
                Modifier
                  .align(Alignment.CenterEnd)
                  .padding(end = 2.dp, top = 6.dp, bottom = bottomOverlayClearance + 6.dp)
                  .graphicsLayer { alpha = scrollbarAlpha },
            )
          }
        }
      }
    }
  }
}
