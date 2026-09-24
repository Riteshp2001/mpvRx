/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.PlaylistItemInput
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.isNetworkImageFile
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.NetworkBookmarkPreferences
import app.gyrolet.mpvrx.preferences.NetworkFolderBookmark
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.NetworkFolderCard
import app.gyrolet.mpvrx.ui.browser.cards.NetworkImageCard
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import app.gyrolet.mpvrx.ui.imageviewer.ImageViewerItem
import app.gyrolet.mpvrx.ui.imageviewer.ImageViewerScreen
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.NetworkMediaType
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.NetworkSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.PlaylistAddCandidate
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.popUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class NetworkBrowserScreen(
  val connectionId: Long,
  val connectionName: String,
  val currentPath: String = "/",
  /**
   * When set, the browser runs as a picker for that playlist: selecting files is enabled and
   * confirming writes them via `PlaylistRepository`. Null means the normal browse/play mode.
   */
  val targetPlaylistId: Int? = null,
  /** Media type of the target playlist; decides what the picker may offer. Null outside picker use. */
  val targetPlaylistIsAudio: Boolean? = null,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val browserPreferences = koinInject<BrowserPreferences>()
    val bookmarkPreferences = koinInject<NetworkBookmarkPreferences>()
    val playlistRepository = koinInject<PlaylistRepository>()

    val isPickerMode = targetPlaylistId != null

    val networkSortType by browserPreferences.networkSortType.collectAsState()
    val networkSortOrder by browserPreferences.networkSortOrder.collectAsState()
    val networkLayoutMode by browserPreferences.networkLayoutMode.collectAsState()
    val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
    val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
    val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()
    val includeAudioInBrowser by browserPreferences.includeAudioBrowser.collectAsState()
    val includeImagesInBrowser by browserPreferences.includeImagesInBrowser.collectAsState()
    val bookmarks by bookmarkPreferences.bookmarks.collectAsState()
    val normalizedPath = remember(currentPath) { NetworkPath.from(currentPath) }
    val canBookmarkCurrentFolder = normalizedPath.segments.isNotEmpty()
    val isCurrentFolderBookmarked =
      remember(bookmarks, connectionId, normalizedPath.value) {
        bookmarkPreferences.contains(connectionId, normalizedPath.value)
      }

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
    LaunchedEffect(isSearching) {
      if (isSearching) focusRequester.requestFocus()
    }

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    LaunchedEffect(viewModel) {
      viewModel.importedPlaylistId.collect { playlistId ->
        backstack.navigateTo(PlaylistDetailScreen(playlistId))
      }
    }

    // Picker mode: what may be added is decided by the target playlist's own media type, matching
    // how the local flow filters (video.path == isAudio). Outside picker mode the browser keeps
    // following its include-audio preference.
    fun isSelectable(file: NetworkFile): Boolean =
      when (targetPlaylistIsAudio) {
        null -> file.isPlayableNetworkMedia(includeAudioInBrowser)
        true -> file.isPlayableNetworkAudio()
        false -> file.isPlayableNetworkVideo()
      }

    // Only the media files currently visible (search applied) are selectable, so select-all and
    // select-invert match what the user can actually see.
    val selectableVideos =
      remember(files, includeAudioInBrowser, searchQuery, targetPlaylistIsAudio) {
        val visible =
          if (searchQuery.isBlank()) files else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
        visible.filter(::isSelectable)
      }

    // Membership set: the list also renders files the picker cannot add (e.g. .m3u), and toggling
    // one would count it without getSelectedItems() ever returning it.
    val selectablePaths = remember(selectableVideos) { selectableVideos.mapTo(mutableSetOf()) { it.path } }

    // The picker writes straight into the playlist it was opened for; the ordinary browser has no
    // target, so it collects a selection and hands it to AddToPlaylistDialog instead.
    val selectionManager =
      rememberSelectionManager(
        items = selectableVideos,
        getId = { it.path },
        onDeleteItems = { _, _ -> Pair(0, 0) },
      )
    val addToPlaylistDialogOpen = rememberSaveable { mutableStateOf(false) }

    // The manager counts the ids it holds, while getSelectedItems() resolves them against the list
    // it was given. Editing a search query can leave a selected id pointing at a file that is no
    // longer listed, so every count shown here is taken from the resolved list — otherwise the
    // bottom bar promises more files than the dialog can actually add.
    val selectedFiles = selectionManager.getSelectedItems()

    fun addSelectedToPlaylist() {
      val target = targetPlaylistId ?: return
      val selected = selectionManager.getSelectedItems()
      if (selected.isEmpty()) return
      scope.launch {
        withContext(Dispatchers.IO) {
          playlistRepository.addItemsToPlaylist(
            target,
            selected.map { file ->
              PlaylistItemInput(
                filePath = NetworkPlaybackUri.create(connectionId, file.path),
                fileName = file.name,
                // -1 means the share did not report a size.
                fileSize = file.size.takeIf { it > 0L },
              )
            },
          )
        }
        Toast.makeText(
          context,
          context.getString(R.string.playlist_add_videos_success, selected.size),
          Toast.LENGTH_SHORT,
        ).show()
        // Leave the whole picker flow (this browser plus the source picker that opened it).
        backstack.popUpTo { it is PlaylistDetailScreen }
      }
    }

    BackHandler {
      when {
        isSearching -> {
          isSearching = false
          searchQuery = ""
        }
        selectionManager.isInSelectionMode -> selectionManager.clear()
        else -> backstack.popSafely()
      }
    }

    Scaffold(
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (isSearching) {
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
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
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
            title = connectionName,
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectedFiles.size,
            // Folders and playlist files are not selectable here, so the selectable media is the
            // only meaningful total for select-all.
            totalCount = selectableVideos.size,
            onBackClick = {
              if (selectionManager.isInSelectionMode) {
                selectionManager.clear()
              } else {
                backstack.popSafely()
              }
            },
            onCancelSelection = { selectionManager.clear() },
            onSortClick = { sortDialogOpen.value = true },
            onSearchClick = { isSearching = true },
            onSettingsClick = {
              backstack.navigateTo(PreferencesScreen)
            },
            onDeleteClick = null,
            onRenameClick = null,
            isSingleSelection = false,
            onInfoClick = null,
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
            additionalActions = {
              if (canBookmarkCurrentFolder) {
                IconButton(
                  onClick = {
                    bookmarkPreferences.toggle(
                      NetworkFolderBookmark(
                        connectionId = connectionId,
                        path = normalizedPath.value,
                        folderName = normalizedPath.segments.last(),
                      ),
                    )
                  },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Star,
                    contentDescription =
                      stringResource(
                        if (isCurrentFolderBookmarked) {
                          R.string.network_bookmark_remove
                        } else {
                          R.string.network_bookmark_add
                        },
                      ),
                    tint =
                      if (isCurrentFolderBookmarked) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                      },
                  )
                }
              }
            },
          )
        }
      },
      bottomBar = {
        if (selectedFiles.isNotEmpty()) {
          Surface(tonalElevation = 3.dp) {
            Button(
              onClick = {
                if (isPickerMode) addSelectedToPlaylist() else addToPlaylistDialogOpen.value = true
              },
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
              Text(stringResource(R.string.playlist_add_videos_button, selectedFiles.size))
            }
          }
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
        // Upstream added the manual-grid columns; the picker keeps overriding include-audio.
        includeAudio = targetPlaylistIsAudio ?: includeAudioInBrowser,
        searchQuery = searchQuery,
        onRefresh = { viewModel.loadFiles() },
        onFolderClick = { folder ->
          backstack.navigateTo(
            NetworkBrowserScreen(
              connectionId = connectionId,
              connectionName = connectionName,
              currentPath = folder.path,
              targetPlaylistId = targetPlaylistId,
              targetPlaylistIsAudio = targetPlaylistIsAudio,
            ),
          )
        },
        onVideoClick = { video ->
          if (isPickerMode || selectionManager.isInSelectionMode) {
            if (video.path in selectablePaths) selectionManager.toggleFromUser(video)
          } else {
            viewModel.openMedia(video)
          }
        },
        onVideoLongClick = { video ->
          // Playlist files are listed here but cannot be added to a playlist, so they must not
          // open selection mode either.
          if (video.path in selectablePaths) selectionManager.handleLongClick(video)
        },
        isVideoSelected = { video -> selectionManager.isSelected(video) },
        includeImages = includeImagesInBrowser,
        onImageClick = { image, visibleImages ->
          val index = visibleImages.indexOfFirst { it.path == image.path }
          if (index >= 0) {
            backstack.navigateTo(
              ImageViewerScreen(
                connectionId = connectionId,
                folderPath = currentPath,
                items = visibleImages.map {
                  ImageViewerItem(
                    path = it.path,
                    name = it.name,
                    lastModified = it.lastModified,
                  )
                },
                initialIndex = index,
              ),
            )
          }
        },
        modifier = Modifier.padding(padding),
      )

      if (!isPickerMode) {
        AddToPlaylistDialog(
          isOpen = addToPlaylistDialogOpen.value,
          candidates =
            selectedFiles.map { file ->
              PlaylistAddCandidate(
                path = NetworkPlaybackUri.create(connectionId, file.path),
                name = file.name,
                isAudio = file.isPlayableNetworkAudio(),
              )
            },
          onDismiss = { addToPlaylistDialogOpen.value = false },
          onSuccess = { selectionManager.clear() },
        )
      }

      NetworkSortDialog(
        isOpen = sortDialogOpen.value,
        onDismiss = { sortDialogOpen.value = false },
      )
    }
  }
}

/** Minimum total item count before the scrollbar is shown. */
private const val SCROLLBAR_MIN_ITEM_COUNT = 20

@Composable
private fun BrowserSectionHeader(text: String, topPadding: Dp = 16.dp) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 16.dp, top = topPadding, bottom = 8.dp),
  )
}

private inline fun LazyGridScope.browserSection(
  gridColumns: Int,
  items: List<NetworkFile>,
  headerText: String?,
  topPadding: Dp = 16.dp,
  crossinline itemContent: @Composable LazyGridItemScope.(NetworkFile) -> Unit,
) {
  if (items.isNotEmpty()) {
    headerText?.let { header ->
      item(span = { GridItemSpan(gridColumns) }) {
        BrowserSectionHeader(header, topPadding)
      }
    }
    items(items, key = { it.path }) { itemContent(it) }
  }
}

private inline fun LazyListScope.browserSection(
  items: List<NetworkFile>,
  headerText: String?,
  topPadding: Dp = 16.dp,
  crossinline itemContent: @Composable LazyItemScope.(NetworkFile) -> Unit,
) {
  if (items.isNotEmpty()) {
    headerText?.let { header ->
      item {
        BrowserSectionHeader(header, topPadding)
      }
    }
    items(items, key = { it.path }) { itemContent(it) }
  }
}

/**
 * Corner label for a row in the videos or images run, or null when it should carry none.
 *
 * Images are only badged while the Images toggle is on — that is the only time the two kinds share
 * one list, and with images off this row set is unambiguous. Audio and playlist rows are left
 * unlabelled on purpose: they share the videos run but calling them "Video" would be a lie.
 */
private fun mediaTypeBadgeFor(
  file: NetworkFile,
  includeImages: Boolean,
  isImage: Boolean,
): NetworkMediaType? =
  when {
    isImage -> NetworkMediaType.IMAGE.takeIf { includeImages }
    !includeImages -> null
    file.isPlayableNetworkVideo() -> NetworkMediaType.VIDEO
    file.isPlayableNetworkAudio() -> NetworkMediaType.AUDIO
    else -> null
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
  includeAudio: Boolean,
  searchQuery: String,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  onVideoLongClick: ((NetworkFile) -> Unit)? = null,
  isVideoSelected: (NetworkFile) -> Boolean = { false },
  includeImages: Boolean = false,
  onImageClick: (NetworkFile, List<NetworkFile>) -> Unit = { _, _ -> },
  modifier: Modifier = Modifier,
) {
  val sortedFiles =
    remember(files, networkSortType, networkSortOrder) {
      files.sortedForNetworkBrowser(networkSortType, networkSortOrder)
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
    isLoading && files.isEmpty() -> {
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

    error != null && files.isEmpty() -> {
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
          message = stringResource(R.string.ui_folder_no_files_or_folders),
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
          message = stringResource(R.string.ui_no_items_match, searchQuery),
        )
      }
    }

    else -> {
      val folders = remember(filteredFiles) { filteredFiles.filter { it.isDirectory } }
      val videos =
        remember(filteredFiles, includeAudio) {
          filteredFiles.filter { it.isPlayableNetworkMedia(includeAudio) || it.isNetworkPlaylistFile() }
        }
      val images =
        remember(filteredFiles, includeImages) {
          if (includeImages) filteredFiles.filter { it.isNetworkImageFile() } else emptyList()
        }
      // Images hidden by the toggle still count as "the folder has content" — reporting an empty
      // folder here would be wrong and leaves the user no way to discover the toggle.
      val hasHiddenImages = !includeImages && filteredFiles.any { it.isNetworkImageFile() }
      if (folders.isEmpty() && videos.isEmpty() && images.isEmpty()) {
        Box(
          modifier = modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          EmptyState(
            icon = Icons.RoundedFilled.Image,
            title = stringResource(R.string.ui_no_items),
            message =
              when {
                hasHiddenImages -> stringResource(R.string.ui_folder_only_images_hidden)
                includeImages -> stringResource(R.string.ui_folder_no_media)
                else -> stringResource(R.string.ui_folder_no_videos_or_folders)
              },
          )
        }
        return
      }

      val isGrid = networkLayoutMode == MediaLayoutMode.GRID

      val configuration = LocalConfiguration.current
      val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
      val isTablet = configuration.smallestScreenWidthDp >= 600
      val dynamicVideos = if (isTablet || isLandscape) 4 else 2
      val gridColumns =
        if (manualGridColumnsEnabled) {
          val pref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
          pref.coerceIn(1, dynamicVideos + 4)
        } else {
          dynamicVideos
        }

      val listState = rememberLazyListState()
      val gridState = rememberLazyGridState()
      val hasEnoughItems = (folders.size + videos.size + images.size) > SCROLLBAR_MIN_ITEM_COUNT

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
        listState = listState,
        modifier = modifier.fillMaxSize(),
      ) {
        val scrollbarLabels =
          remember(folders, videos, images) {
            buildList<String?> {
              // Only the folders run renders a header item, so only it needs a placeholder slot for
              // the glyph list to stay aligned with the rows.
              if (folders.isNotEmpty()) {
                add(null)
                addAll(folders.map { it.name })
              }
              addAll(videos.map { it.name })
              addAll(images.map { it.name })
            }
          }
        val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(bottom = navigationBarHeight),
        ) {
          val folderHeaderText = stringResource(R.string.pref_folders_title)

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
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              browserSection(gridColumns, folders, folderHeaderText, topPadding = 8.dp) { folder ->
                NetworkFolderCard(
                  file = folder,
                  onClick = { onFolderClick(folder) },
                  isGridMode = true,
                )
              }
              browserSection(gridColumns, videos, null) { video ->
                connection?.let { conn ->
                  NetworkVideoCard(
                    file = video,
                    connection = conn,
                    onClick = { onVideoClick(video) },
                    onLongClick = onVideoLongClick?.let { handler -> { handler(video) } },
                    isSelected = isVideoSelected(video),
                    isGridMode = true,
                    mediaType = mediaTypeBadgeFor(video, includeImages, isImage = false),
                  )
                }
              }
              browserSection(gridColumns, images, null) { image ->
                connection?.let { conn ->
                  NetworkImageCard(
                    file = image,
                    connection = conn,
                    onClick = { onImageClick(image, images) },
                    isGridMode = true,
                    mediaType = mediaTypeBadgeFor(image, includeImages, isImage = true),
                  )
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
              browserSection(folders, folderHeaderText, topPadding = 8.dp) { folder ->
                NetworkFolderCard(
                  file = folder,
                  onClick = { onFolderClick(folder) },
                  isGridMode = false,
                )
              }
              browserSection(videos, null) { video ->
                connection?.let { conn ->
                  NetworkVideoCard(
                    file = video,
                    connection = conn,
                    onClick = { onVideoClick(video) },
                    onLongClick = onVideoLongClick?.let { handler -> { handler(video) } },
                    isSelected = isVideoSelected(video),
                    isGridMode = false,
                    mediaType = mediaTypeBadgeFor(video, includeImages, isImage = false),
                  )
                }
              }
              browserSection(images, null) { image ->
                connection?.let { conn ->
                  NetworkImageCard(
                    file = image,
                    connection = conn,
                    onClick = { onImageClick(image, images) },
                    isGridMode = false,
                    mediaType = mediaTypeBadgeFor(image, includeImages, isImage = true),
                  )
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
