package app.gyrolet.mpvrx.ui.browser.playlist

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.M3UVideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.ScrollbarSettings
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Playlist detail screen showing videos in a playlist.
 *
 * **M3U Playlist Behavior:**
 * M3U playlists are treated as channel lists:
 * - Stored stream metadata is surfaced in the UI
 * - Playlist playback keeps channel titles instead of raw URLs
 * - Per-stream headers can be passed to the player for supported sources
 * - Categories and saved streams make large IPTV lists easier to browse
 *
 * **Regular Playlist Behavior:**
 * Local file playlists support full playlist navigation:
 * - Next/previous buttons available during playback
 * - Playlist continuation and shuffle modes
 * - Full playlist loaded into PlayerActivity
 */
@Serializable
data class PlaylistDetailScreen(val playlistId: Int) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()

    // ViewModel
    val viewModel: PlaylistDetailViewModel =
      viewModel(
        key = "PlaylistDetailViewModel_$playlistId",
        factory = PlaylistDetailViewModel.factory(
          context.applicationContext as android.app.Application,
          playlistId,
        ),
      )

    val playlist by viewModel.playlist.collectAsState()
    val videoItems by viewModel.videoItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val videos = videoItems.map { it.video }
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing = remember { mutableStateOf(false) }

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var selectedM3UFilter by rememberSaveable { mutableStateOf(M3U_FILTER_ALL) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val hasFavoriteStreams = remember(videoItems) { videoItems.any { it.playlistItem.isFavorite } }

    LaunchedEffect(categories, hasFavoriteStreams, playlist?.isM3uPlaylist) {
      if (playlist?.isM3uPlaylist != true) {
        selectedM3UFilter = M3U_FILTER_ALL
      } else if (
        selectedM3UFilter != M3U_FILTER_ALL &&
        selectedM3UFilter != M3U_FILTER_FAVORITES &&
        selectedM3UFilter !in categories
      ) {
        selectedM3UFilter = M3U_FILTER_ALL
      } else if (selectedM3UFilter == M3U_FILTER_FAVORITES && !hasFavoriteStreams) {
        selectedM3UFilter = M3U_FILTER_ALL
      }
    }

    val m3uFilteredItems =
      if (playlist?.isM3uPlaylist == true) {
        when (selectedM3UFilter) {
          M3U_FILTER_FAVORITES -> videoItems.filter { it.playlistItem.isFavorite }
          M3U_FILTER_ALL -> videoItems
          else -> videoItems.filter { it.playlistItem.groupTitle == selectedM3UFilter }
        }
      } else {
        videoItems
      }

    // Filter video items based on category and search query
    val filteredVideoItems = if (isSearching && searchQuery.isNotBlank()) {
      m3uFilteredItems.filter { item ->
        item.video.displayName.contains(searchQuery, ignoreCase = true) ||
          item.video.path.contains(searchQuery, ignoreCase = true) ||
          item.playlistItem.groupTitle.orEmpty().contains(searchQuery, ignoreCase = true) ||
          item.playlistItem.tvgId.orEmpty().contains(searchQuery, ignoreCase = true)
      }
    } else {
      m3uFilteredItems
    }

    // Request focus when search is activated
    LaunchedEffect(isSearching) {
      if (isSearching) {
        focusRequester.requestFocus()
        keyboardController?.show()
      }
    }

    // Selection manager - use playlist item ID as unique key, work with filtered items
    val selectionManager =
      rememberSelectionManager(
        items = filteredVideoItems,
        getId = { it.playlistItem.id },
        onDeleteItems = { itemsToDelete, _ ->
          val videosToRemove = itemsToDelete.map { it.video }
          viewModel.removeVideosFromPlaylist(videosToRemove)
          Pair(itemsToDelete.size, 0)
        },
        onOperationComplete = { viewModel.refresh() },
      )

    // UI State
    val listState = rememberLazyListState()
    val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
    val mediaInfoDialogOpen = rememberSaveable { mutableStateOf(false) }
    val selectedVideo = remember { mutableStateOf<Video?>(null) }
    val mediaInfoData = remember { mutableStateOf<MediaInfoOps.MediaInfoData?>(null) }
    val mediaInfoLoading = remember { mutableStateOf(false) }
    val mediaInfoError = remember { mutableStateOf<String?>(null) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }
    var urlDialogContent by remember { mutableStateOf("") }

    // Reorder mode state
    var isReorderMode by rememberSaveable { mutableStateOf(false) }

    // Predictive back: Intercept when in selection mode, reorder mode, or searching
    BackHandler(enabled = selectionManager.isInSelectionMode || isReorderMode || isSearching) {
      when {
        isReorderMode -> isReorderMode = false
        isSearching -> {
          isSearching = false
          searchQuery = ""
        }
        selectionManager.isInSelectionMode -> selectionManager.clear()
      }
    }

    fun launchPlaylistPlayback(item: PlaylistVideoItem, startIndex: Int) {
      val intent =
        Intent(context, PlayerActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = item.video.uri
          putExtra("internal_launch", true)
          putExtra("playlist_index", startIndex)
          putExtra("playlist_id", playlistId)
          putExtra("launch_source", if (playlist?.isM3uPlaylist == true) "m3u_playlist" else "playlist")
          putExtra("title", item.playlistItem.fileName)
          buildM3UHeadersExtra(playlist, item.playlistItem)?.let { putExtra("headers", it) }
        }
      context.startActivity(intent)
    }

    Scaffold(
      topBar = {
        if (isSearching) {
          // Search mode - show search bar
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = { Text("Search videos...") },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search",
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
                      imageVector = Icons.Filled.Close,
                      contentDescription = "Cancel",
                    )
                  }
                },
                modifier = Modifier.focusRequester(focusRequester),
              )
            },
            expanded = false,
            onExpandedChange = { },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          ) {
            // Empty content for SearchBar
          }
        } else {
          BrowserTopBar(
            title = playlist?.name ?: "Playlist",
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectionManager.selectedCount,
            totalCount = videos.size,
            onBackClick = {
              when {
                isReorderMode -> isReorderMode = false
                selectionManager.isInSelectionMode -> selectionManager.clear()
                else -> backStack.popSafely()
              }
            },
            onCancelSelection = { selectionManager.clear() },
            isSingleSelection = selectionManager.isSingleSelection,
            useRemoveIcon = true, // Show remove icon instead of delete for playlist
            onInfoClick =
              if (selectionManager.isSingleSelection) {
                {
                  val item = selectionManager.getSelectedItems().firstOrNull()
                  if (item != null) {
                    if (playlist?.isM3uPlaylist == true) {
                      // For M3U playlists, show URL dialog
                      urlDialogContent = item.video.path
                      showUrlDialog = true
                      selectionManager.clear()
                    } else {
                      // For regular playlists, show MediaInfo activity
                      val intent = Intent(context, app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivity::class.java)
                      intent.action = Intent.ACTION_VIEW
                      intent.data = item.video.uri
                      context.startActivity(intent)
                      selectionManager.clear()
                    }
                  }
                }
              } else {
                null
              },
            onShareClick = if (playlist?.isM3uPlaylist != true) {
              // Hide share button for M3U playlists
              {
                val videosToShare = selectionManager.getSelectedItems().map { it.video }
                MediaUtils.shareVideos(context, videosToShare)
              }
            } else {
              null
            },
            onPlayClick = null, // Don't show play icon in selection mode for playlist
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
            onDeleteClick = { deleteDialogOpen.value = true },
            additionalActions = {
              when {
                // Show done button when in reorder mode
                isReorderMode -> {
                  IconButton(
                    onClick = { isReorderMode = false },
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Check,
                      contentDescription = "Done reordering",
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
                // Show reorder button and play button when not in selection mode
                !selectionManager.isInSelectionMode && videos.isNotEmpty() -> {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    // Search button
                    IconButton(
                      onClick = { isSearching = true },
                    ) {
                      Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search videos",
                        tint = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                    Spacer(modifier = Modifier.width(4.dp))

                    // Reorder button (hide for M3U playlists)
                    if (playlist?.isM3uPlaylist != true) {
                      IconButton(
                        onClick = { isReorderMode = true },
                      ) {
                        Icon(
                          imageVector = Icons.Outlined.SwapVert,
                          contentDescription = "Reorder playlist",
                          tint = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                      Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Play button
                    Button(
                      onClick = {
                        val mostRecentlyPlayedItem = videoItems
                          .filter { it.playlistItem.lastPlayedAt > 0 }
                          .maxByOrNull { it.playlistItem.lastPlayedAt }

                        val itemToPlay = mostRecentlyPlayedItem ?: videoItems.firstOrNull()
                        val startIndex =
                          itemToPlay?.let { candidate ->
                            videoItems.indexOfFirst { it.playlistItem.id == candidate.playlistItem.id }
                          } ?: -1

                        if (itemToPlay != null && startIndex >= 0) {
                          coroutineScope.launch {
                            viewModel.updatePlayHistory(itemToPlay.video.path)
                          }
                          launchPlaylistPlayback(itemToPlay, startIndex)
                        }
                      },
                      colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                      ),
                      shape = MaterialTheme.shapes.large,
                      modifier = Modifier.padding(end = 20.dp),
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Icon(
                          imageVector = Icons.Filled.PlayArrow,
                          contentDescription = null,
                          modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                          text = "Play",
                          style = MaterialTheme.typography.labelLarge,
                          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                      }
                    }
                  }
                }
              }
            },
          )
        }
      },
      floatingActionButton = { },
    ) { padding ->
      // Show "no results" message when searching with no results
      if (isSearching && filteredVideoItems.isEmpty() && searchQuery.isNotBlank()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = null,
              modifier = Modifier.size(64.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "No videos found",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = "Try a different search term",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        val pullToRefreshEnabled =
          !selectionManager.isInSelectionMode && !isReorderMode && !isSearching

        PullRefreshBox(
          isRefreshing = isRefreshing,
          enabled = pullToRefreshEnabled,
          listState = listState,
          modifier = Modifier.fillMaxSize().padding(padding),
          onRefresh = {
            val isM3uPlaylist = playlist?.isM3uPlaylist == true
            if (isM3uPlaylist) {
              val result = viewModel.refreshM3UPlaylist()
              result
                .onSuccess {
                  Toast.makeText(context, "Playlist refreshed successfully", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                  Toast.makeText(context, "Failed to refresh: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } else {
              viewModel.refreshNow()
            }
          },
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            if (playlist?.isM3uPlaylist == true && (categories.isNotEmpty() || hasFavoriteStreams)) {
              M3UPlaylistFilterRow(
                categories = categories,
                hasFavorites = hasFavoriteStreams,
                selectedFilter = selectedM3UFilter,
                onFilterSelected = { selectedM3UFilter = it },
              )
            }

            PlaylistVideoListContent(
              videoItems = filteredVideoItems,
              isLoading = isLoading && videoItems.isEmpty(),
              selectionManager = selectionManager,
              isM3uPlaylist = playlist?.isM3uPlaylist == true,
              isReorderMode = isReorderMode,
              onReorder = { fromIndex, toIndex ->
                coroutineScope.launch {
                  viewModel.reorderPlaylistItems(fromIndex, toIndex)
                }
              },
              onToggleFavorite =
                if (playlist?.isM3uPlaylist == true && !selectionManager.isInSelectionMode) {
                  { item ->
                    coroutineScope.launch {
                      viewModel.toggleFavorite(item.playlistItem.id)
                    }
                  }
                } else {
                  null
                },
              onVideoItemClick = { item ->
                if (selectionManager.isInSelectionMode) {
                  selectionManager.toggle(item)
                } else {
                  coroutineScope.launch {
                    viewModel.updatePlayHistory(item.video.path)
                  }

                  val startIndex = videoItems.indexOfFirst { it.playlistItem.id == item.playlistItem.id }
                  if (startIndex >= 0) {
                    launchPlaylistPlayback(item, startIndex)
                  } else {
                    MediaUtils.playFile(item.video, context, "playlist_detail", title = item.playlistItem.fileName)
                  }
                }
              },
              onVideoItemLongClick = { item ->
                selectionManager.toggle(item)
              },
              listState = listState,
              modifier = Modifier.weight(1f).fillMaxWidth(),
            )
          }
        }
      }

      // Dialogs
      RemoveFromPlaylistDialog(
        isOpen = deleteDialogOpen.value,
        onDismiss = { deleteDialogOpen.value = false },
        onConfirm = { selectionManager.deleteSelected() },
        itemCount = selectionManager.selectedCount,
      )

      // URL Dialog for M3U streams
      if (showUrlDialog) {
        StreamUrlDialog(
          url = urlDialogContent,
          onDismiss = { showUrlDialog = false },
          onCopy = {
            val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Stream URL", urlDialogContent)
            clipboardManager.setPrimaryClip(clip)
            android.widget.Toast.makeText(context, "URL copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
          }
        )
      }
    }
  }
}

@Composable
private fun PlaylistVideoListContent(
  videoItems: List<PlaylistVideoItem>,
  isLoading: Boolean,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<PlaylistVideoItem, Int>,
  isReorderMode: Boolean,
  onReorder: (Int, Int) -> Unit,
  onToggleFavorite: ((PlaylistVideoItem) -> Unit)?,
  onVideoItemClick: (PlaylistVideoItem) -> Unit,
  onVideoItemLongClick: (PlaylistVideoItem) -> Unit,
  listState: androidx.compose.foundation.lazy.LazyListState,
  modifier: Modifier = Modifier,
  isM3uPlaylist: Boolean = false,
) {
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
      )
    }

  // Find the most recently played video (highest lastPlayedAt timestamp)
  val mostRecentlyPlayedItem = remember(videoItems) {
    videoItems.filter { it.playlistItem.lastPlayedAt > 0 }
      .maxByOrNull { it.playlistItem.lastPlayedAt }
  }

  when {
    isLoading -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    videoItems.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.foundation.layout.Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.PlaylistAdd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "No videos in playlist",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "Add videos to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    else -> {
      // Only show scrollbar if list has more than 20 items
      val hasEnoughItems = videoItems.size > 20

      // Animate scrollbar alpha
      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (!hasEnoughItems) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200),
        label = "scrollbarAlpha",
      )

      // Reorderable state
      val reorderableLazyListState = rememberReorderableLazyListState(listState) { from, to ->
        if (isReorderMode) {
          onReorder(from.index, to.index)
        }
      }

      LazyColumnScrollbar(
        state = listState,
        settings = ScrollbarSettings(
          thumbUnselectedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f * scrollbarAlpha),
          thumbSelectedColor = MaterialTheme.colorScheme.primary.copy(alpha = scrollbarAlpha),
        ),
        modifier = modifier.fillMaxSize(),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 8.dp, end = 8.dp),
        ) {
          items(
            count = videoItems.size,
            key = { index -> videoItems[index].playlistItem.id },
          ) { index ->
            ReorderableItem(reorderableLazyListState, key = videoItems[index].playlistItem.id) {
              val item = videoItems[index]

              val progressPercentage = if (item.playlistItem.lastPosition > 0 && item.video.duration > 0) {
                item.playlistItem.lastPosition.toFloat() / item.video.duration.toFloat() * 100f
              } else null

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                // Use M3UVideoCard for streaming URLs, VideoCard for local files
                if (isM3uPlaylist) {
                  M3UVideoCard(
                    title = item.video.displayName,
                    url = item.video.path,
                    logoUrl = item.playlistItem.tvgLogo,
                    groupTitle = item.playlistItem.groupTitle,
                    hasDrm = !item.playlistItem.licenseType.isNullOrBlank() || !item.playlistItem.licenseKey.isNullOrBlank(),
                    hasCustomUserAgent = !item.playlistItem.userAgent.isNullOrBlank(),
                    onClick = { onVideoItemClick(item) },
                    onLongClick = { onVideoItemLongClick(item) },
                    onFavoriteClick = onToggleFavorite?.let { { it(item) } },
                    isSelected = selectionManager.isSelected(item),
                    isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
                    isFavorite = item.playlistItem.isFavorite,
                    modifier = Modifier.weight(1f),
                  )
                } else {
                  VideoCard(
                    video = item.video,
                    progressPercentage = progressPercentage,
                    isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
                    isSelected = selectionManager.isSelected(item),
                    onClick = { onVideoItemClick(item) },
                    onLongClick = { onVideoItemLongClick(item) },
                    onThumbClick = if (tapThumbnailToSelect) {
                      { onVideoItemLongClick(item) }
                    } else {
                      { onVideoItemClick(item) }
                    },
                    showSubtitleIndicator = showSubtitleIndicator,
                    modifier = Modifier.weight(1f),
                    uiConfig = videoCardUiConfig,
                  )
                }

                // Drag handle - only show when in reorder mode, positioned at the end
                if (isReorderMode) {
                  IconButton(
                    onClick = { },
                    modifier = Modifier
                      .size(48.dp)
                      .draggableHandle(),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.DragHandle,
                      contentDescription = "Drag to reorder",
                      tint = MaterialTheme.colorScheme.primary,
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
}

@Composable
private fun M3UPlaylistFilterRow(
  categories: List<String>,
  hasFavorites: Boolean,
  selectedFilter: String,
  onFilterSelected: (String) -> Unit,
) {
  LazyRow(
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      FilterChip(
        selected = selectedFilter == M3U_FILTER_ALL,
        onClick = { onFilterSelected(M3U_FILTER_ALL) },
        label = { Text("All") },
      )
    }

    if (hasFavorites) {
      item {
        FilterChip(
          selected = selectedFilter == M3U_FILTER_FAVORITES,
          onClick = { onFilterSelected(M3U_FILTER_FAVORITES) },
          label = { Text("Saved") },
        )
      }
    }

    items(categories, key = { it }) { category ->
      FilterChip(
        selected = selectedFilter == category,
        onClick = { onFilterSelected(category) },
        label = {
          Text(
            text = category,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        },
      )
    }
  }
}

private fun buildM3UHeadersExtra(
  playlist: PlaylistEntity?,
  item: PlaylistItemEntity,
): Array<String>? {
  val userAgent = item.userAgent?.takeIf { it.isNotBlank() } ?: playlist?.userAgent?.takeIf { it.isNotBlank() }
  return userAgent?.let { arrayOf("User-Agent", it) }
}

@Composable
private fun StreamUrlDialog(
  url: String,
  onDismiss: () -> Unit,
  onCopy: () -> Unit,
) {
  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Stream URL") },
    text = {
      Text(
        text = url,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
      )
    },
    confirmButton = {
      androidx.compose.material3.TextButton(
        onClick = {
          onCopy()
          onDismiss()
        }
      ) {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = null,
          modifier = Modifier.padding(end = 4.dp).size(18.dp)
        )
        Text("Copy")
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(onClick = onDismiss) {
        Text("Close")
      }
    },
  )
}

@Composable
private fun RemoveFromPlaylistDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  itemCount: Int,
) {
  if (!isOpen) return

  val itemText = if (itemCount == 1) "video" else "videos"

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "Remove $itemCount $itemText from playlist?",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
      )
    },
    text = {
      androidx.compose.foundation.layout.Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
      ) {
        androidx.compose.material3.Card(
          colors =
            androidx.compose.material3.CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ),
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(
            text = "The selected $itemText will be removed from this playlist. The original ${if (itemCount == 1) "file" else "files"} will not be deleted.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    },
    confirmButton = {
      androidx.compose.material3.Button(
        onClick = {
          onConfirm()
          onDismiss()
        },
        colors =
          androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          text = "Remove from Playlist",
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text("Cancel", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
  return try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
      cursor.moveToFirst()
      cursor.getString(nameIndex)
    } ?: uri.lastPathSegment
  } catch (e: Exception) {
    uri.lastPathSegment
  }
}

private const val M3U_FILTER_ALL = "__m3u_all__"
private const val M3U_FILTER_FAVORITES = "__m3u_favorites__"

