/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.playlist

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.shadow
import app.gyrolet.mpvrx.ui.utils.dragElevation
import app.gyrolet.mpvrx.ui.utils.rememberReorderFeedback
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.browser.components.rememberVideoSwipeActions
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.PlaylistSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.NetworkProbeResult
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.M3UVideoCard
import app.gyrolet.mpvrx.ui.browser.cards.PlaylistBookmarkButton
import app.gyrolet.mpvrx.ui.browser.cards.sourceChipColor
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
data class PlaylistDetailScreen(
  val playlistId: Int,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    var contentWidthDp by remember { mutableStateOf<Int?>(null) }
    val browserPreferences = koinInject<BrowserPreferences>()
    val sortType by browserPreferences.playlistItemSortType.collectAsState()
    val sortOrder by browserPreferences.playlistItemSortOrder.collectAsState()

    // ViewModel
    val viewModel: PlaylistDetailViewModel =
      viewModel(
        key = "PlaylistDetailViewModel_$playlistId",
        factory =
          PlaylistDetailViewModel.factory(
            context.applicationContext as android.app.Application,
            playlistId,
          ),
      )

    val playlist by viewModel.playlist.collectAsState()
    val videoItems by viewModel.videoItems.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val connectionStatuses by viewModel.connectionStatuses.collectAsState()
    val videos = videoItems.map { it.video }
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing = remember { mutableStateOf(false) }
    val playlistTitle =
      when {
        playlist?.name?.equals(PlaylistRepository.FAVORITES_PLAYLIST_NAME, ignoreCase = true) != true ->
          playlist?.name ?: stringResource(R.string.ui_playlist)
        playlist?.isAudio == true -> stringResource(R.string.playlist_favorite_songs)
        else -> stringResource(R.string.playlist_favorite_videos)
      }

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var selectedM3UFilter by rememberSaveable { mutableStateOf(M3U_FILTER_ALL) }
    var isReorderMode by rememberSaveable { mutableStateOf(false) }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val hasFavoriteStreams = remember(videoItems) { videoItems.any { it.playlistItem.isFavorite } }

    LaunchedEffect(categories, hasFavoriteStreams, playlist?.isM3uPlaylist) {
      if (
        selectedM3UFilter != M3U_FILTER_ALL &&
        selectedM3UFilter != M3U_FILTER_FAVORITES &&
        (playlist?.isM3uPlaylist != true || selectedM3UFilter !in categories)
      ) {
        selectedM3UFilter = M3U_FILTER_ALL
      } else if (selectedM3UFilter == M3U_FILTER_FAVORITES && !hasFavoriteStreams) {
        selectedM3UFilter = M3U_FILTER_ALL
      }
    }

    val sortedVideoItems = remember(videoItems, sortType, sortOrder, isReorderMode, playlist?.isM3uPlaylist) {
      val effectiveType = sortType.takeUnless { it == PlaylistSortType.Category && playlist?.isM3uPlaylist != true }
        ?: PlaylistSortType.Original
      if (isReorderMode) videoItems else sortPlaylistItems(videoItems, effectiveType, sortOrder)
    }
    val m3uFilteredItems =
      when (selectedM3UFilter) {
        M3U_FILTER_FAVORITES -> sortedVideoItems.filter { it.playlistItem.isFavorite }
        M3U_FILTER_ALL -> sortedVideoItems
        else -> sortedVideoItems.filter { it.playlistItem.groupTitle == selectedM3UFilter }
      }

    // Filter video items based on category and search query
    val filteredVideoItems =
      if (isSearching && searchQuery.isNotBlank()) {
        m3uFilteredItems.filter { item ->
          item.video.displayName.contains(searchQuery, ignoreCase = true) ||
            item.video.path.contains(searchQuery, ignoreCase = true) ||
            item.playlistItem.groupTitle
              .orEmpty()
              .contains(searchQuery, ignoreCase = true) ||
            item.playlistItem.tvgId
              .orEmpty()
              .contains(searchQuery, ignoreCase = true)
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

    fun launchPlaylistPlayback(
      item: PlaylistVideoItem,
      startIndex: Int,
    ) {
      val isAudio = item.video.isAudio || (playlist?.isAudio == true)
      if (MediaUtils.shouldPlayInMiniPlayerOnly(isAudio)) {
        val queueItems =
          filteredVideoItems.map { playlistEntry ->
            val fallback = Uri.parse(playlistEntry.video.path)
            val uri =
              playlistEntry.video.uri.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
                ?: fallback.takeIf { !it.scheme.isNullOrBlank() }
                ?: Uri.fromFile(java.io.File(playlistEntry.video.path))
            val headerExtra = buildM3UHeadersExtra(playlist, playlistEntry.playlistItem)
            val headersMap = headerExtra?.let { mapOf(it[0] to it[1]) }.orEmpty()
            PlaybackItem.fromUri(
              uri = uri.toString(),
              stableId = playlistEntry.video.path.takeIf(String::isNotBlank)?.let(PlaybackIdentity::forLocalPath),
              title = playlistEntry.playlistItem.fileName,
              mimeType = if (isAudio) "audio/*" else playlistEntry.video.mimeType,
              headers = headersMap,
              durationSeconds = (playlistEntry.video.duration / 1000L).toInt().takeIf { it > 0 },
            )
          }
        MediaUtils.playInMiniPlayer(context, queueItems, startIndex)
        return
      }

      val fallbackUri = Uri.parse(item.video.path)
      val playUri =
        item.video.uri.takeIf { it != Uri.EMPTY && it.toString().isNotBlank() }
          ?: fallbackUri.takeIf { !it.scheme.isNullOrBlank() }
          ?: Uri.fromFile(java.io.File(item.video.path))

      val intent =
        Intent(context, PlayerActivity::class.java).apply {
          action = Intent.ACTION_VIEW
          data = playUri
          putExtra("internal_launch", true)
          putExtra("local_media_path", item.video.path)
          putExtra("playlist_index", startIndex)
          putExtra("playlist_id", playlistId)
          putExtra("launch_source", if (playlist?.isM3uPlaylist == true) "m3u_playlist" else "playlist")
          putExtra("is_audio", item.video.isAudio)
          putExtra("title", item.playlistItem.fileName)
          buildM3UHeadersExtra(playlist, item.playlistItem)?.let { putExtra("headers", it) }
        }
      context.startActivity(intent)
    }

    fun showToast(message: String) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun reportUnavailable(
      item: PlaylistVideoItem,
      @androidx.annotation.StringRes reasonRes: Int,
    ) {
      showToast(
        context.getString(
          R.string.playlist_item_unavailable,
          item.video.displayName,
          context.getString(reasonRes),
        ),
      )
    }

    fun startPlayback(item: PlaylistVideoItem) {
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

    /**
     * Network entries are probed before the player opens, so an offline share reports itself here
     * instead of stalling the player on a stream the proxy cannot reach.
     */
    suspend fun playWithNetworkCheck(item: PlaylistVideoItem) {
      if (!item.isNetwork) {
        startPlayback(item)
        return
      }

      // Only surface the hint if the probe is slow enough to be noticeable on its own. It is
      // cancelled rather than left to expire so the outcome toast can take the screen immediately.
      var hint: Toast? = null
      val hintJob =
        coroutineScope.launch {
          delay(NETWORK_HINT_DELAY_MS)
          hint =
            Toast
              .makeText(context, context.getString(R.string.playlist_connecting_network), Toast.LENGTH_SHORT)
              .also { it.show() }
        }
      val result = viewModel.probeIfNetwork(item)
      hintJob.cancel()
      hint?.cancel()

      when (result) {
        NetworkProbeResult.AUTHENTICATION_FAILED -> reportUnavailable(item, R.string.playlist_unavailable_auth)
        NetworkProbeResult.UNREACHABLE -> reportUnavailable(item, R.string.playlist_unavailable_offline)
        else -> startPlayback(item)
      }
    }

    Scaffold(
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (isSearching) {
          // Search mode - show search bar
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_search_videos),
              )
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.settings_search_title,
                  ),
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
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.generic_cancel,
                    ),
                )
              }
            },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
            title = playlistTitle,
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
            onSortClick = if (isReorderMode) null else ({ showSortDialog = true }),
            useRemoveIcon = true, // Show remove icon instead of delete for playlist
            onInfoClick =
              if (selectionManager.isSingleSelection && playlist?.isXtreamPlaylist != true) {
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
            onShareClick =
              if (playlist?.isM3uPlaylist != true) {
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
                      imageVector = Icons.RoundedFilled.Check,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.ui_done_reordering,
                        ),
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
                        imageVector = Icons.RoundedFilled.Search,
                        contentDescription =
                          androidx.compose.ui.res.stringResource(
                            app.gyrolet.mpvrx.R.string.ui_search_videos_2,
                          ),
                        tint = MaterialTheme.colorScheme.onSurface,
                      )
                    }
                    Spacer(modifier = Modifier.width(4.dp))

                    // Reorder button (hide for M3U playlists)
                    if (playlist?.isM3uPlaylist != true) {
                      IconButton(
                        onClick = {
                          selectedM3UFilter = M3U_FILTER_ALL
                          searchQuery = ""
                          isSearching = false
                          browserPreferences.playlistItemSortType.set(PlaylistSortType.Original)
                          browserPreferences.playlistItemSortOrder.set(SortOrder.Ascending)
                          isReorderMode = true
                        },
                      ) {
                        Icon(
                          imageVector = Icons.RoundedFilled.SwapVert,
                          contentDescription =
                            androidx.compose.ui.res.stringResource(
                              app.gyrolet.mpvrx.R.string.ui_reorder_playlist,
                            ),
                          tint = MaterialTheme.colorScheme.onSurface,
                        )
                      }
                      Spacer(modifier = Modifier.width(4.dp))
                    }

                    IconButton(
                      onClick = {
                        val mostRecentlyPlayedItem =
                          videoItems
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
                      modifier = Modifier.padding(end = 20.dp),
                    ) {
                      Icon(
                        imageVector = Icons.RoundedFilled.PlayArrow,
                        contentDescription =
                          androidx.compose.ui.res
                            .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
                        tint = MaterialTheme.colorScheme.primary,
                      )
                    }
                  }
                }
              }
            },
          )
        }
      },
      floatingActionButton = {
        if (!isSearching && !isReorderMode && !selectionManager.isInSelectionMode) {
          val isAudioPlaylist = playlist?.isAudio == true || videoItems.any { it.video.isAudio }
          val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
          val miniPlayerClearance = app.gyrolet.mpvrx.ui.browser.NavigationBarState.miniPlayerClearance
          ExtendedFloatingActionButton(
            modifier =
              Modifier.padding(
                bottom =
                  maxOf(
                    (navigationBarHeight - 16.dp).coerceAtLeast(0.dp),
                    miniPlayerClearance,
                  ),
              ),
            onClick = { backStack.navigateTo(PlaylistAddVideosScreen(playlistId, isAudio = isAudioPlaylist)) },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
              text = { Text(stringResource(if (isAudioPlaylist) R.string.playlist_add_songs else R.string.playlist_add_videos)) },
          )
        }
      },
    ) { padding ->
      // Show "no results" message when searching with no results
      if (isSearching && filteredVideoItems.isEmpty() && searchQuery.isNotBlank()) {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(padding),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Search,
              contentDescription = null,
              modifier = Modifier.size(64.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_no_videos_found),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_try_a_different_search_term),
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
                  Toast
                    .makeText(
                      context,
                      context.getString(app.gyrolet.mpvrx.R.string.playlist_refresh_success),
                      Toast.LENGTH_SHORT,
                    ).show()
                }.onFailure { error ->
                  Toast
                    .makeText(
                      context,
                      context.getString(
                        R.string.toast_failed_to_refresh,
                        error.message ?: context.getString(R.string.generic_unknown_error),
                      ),
                      Toast.LENGTH_LONG,
                    ).show()
                }
            } else {
              viewModel.refreshNow()
            }
          },
        ) {
          Column(modifier = Modifier.fillMaxSize()) {
            if (!isReorderMode && (hasFavoriteStreams || (playlist?.isM3uPlaylist == true && categories.isNotEmpty()))) {
              M3UPlaylistFilterRow(
                categories = if (playlist?.isM3uPlaylist == true) categories else emptyList(),
                hasFavorites = hasFavoriteStreams,
                selectedFilter = selectedM3UFilter,
                onFilterSelected = { selectedM3UFilter = it },
              )
            }

            PlaylistVideoListContent(
              onChanged = { viewModel.refresh() },
              onDeleted = viewModel::removeVideosFromPlaylist,
              videoItems = filteredVideoItems,
              isLoading = isLoading && videoItems.isEmpty(),
              selectionManager = selectionManager,
              isM3uPlaylist = playlist?.isM3uPlaylist == true,
              isAudio = playlist?.isAudio == true || videoItems.any { it.video.isAudio },
              isSourceDisconnected = { item -> item.isSourceDisconnected(connectionStatuses) },
              isReorderMode = isReorderMode,
              onReorder = { fromIndex, toIndex ->
                coroutineScope.launch {
                  viewModel.reorderPlaylistItems(fromIndex, toIndex)
                }
              },
              onToggleFavorite =
                if (!selectionManager.isInSelectionMode && !isReorderMode) {
                  { item ->
                    coroutineScope.launch {
                      try {
                        viewModel.toggleFavorite(item.playlistItem.id)
                      } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                      } catch (_: Exception) {
                        showToast(context.getString(R.string.playback_bookmark_update_failed))
                      }
                    }
                  }
                } else {
                  null
                },
              onVideoItemClick = { item ->
                when {
                  selectionManager.isInSelectionMode -> selectionManager.toggleFromUser(item)
                  !item.isAvailable ->
                    reportUnavailable(item, item.unavailableReasonRes ?: R.string.playlist_unavailable_file)
                  item.isSourceDisconnected(connectionStatuses) ->
                    showToast(context.getString(R.string.playlist_source_not_connected))
                  else -> coroutineScope.launch { playWithNetworkCheck(item) }
                }
              },
              onVideoItemLongClick = { item ->
                selectionManager.handleLongClick(item)
              },
              listState = listState,
              modifier = Modifier.weight(1f).fillMaxWidth().onSizeChanged {
                contentWidthDp = with(density) { it.width.toDp().value.toInt() }
              },
            )
          }
        }
      }

      // Dialogs
      app.gyrolet.mpvrx.ui.browser.dialogs.PlaylistSortDialog(
        isOpen = showSortDialog,
        onDismiss = { showSortDialog = false },
        isM3uPlaylist = playlist?.isM3uPlaylist == true,
        availableWidthDp = contentWidthDp,
      )

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
            SafeClipboard.copyPlainText(context, "Stream URL", urlDialogContent)
          },
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
  isAudio: Boolean = false,
  onChanged: () -> Unit,
  onDeleted: suspend (List<Video>) -> Unit,
  /** True when the entry's backing network connection is not currently connected. */
  isSourceDisconnected: (PlaylistVideoItem) -> Boolean = { false },
) {
  val swipeActions = rememberVideoSwipeActions(onDeleted = onDeleted, onChanged = onChanged)
  val swipePlaybackInfo = rememberSwipePlaybackInfo(videoItems.map { it.video })
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val viewPreferences = browserPreferences.playlistItemView
  val mediaLayoutMode by viewPreferences.layoutMode.collectAsState()
  val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
  val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val columnPreference = if (landscape) viewPreferences.gridColumnsLandscape else viewPreferences.gridColumnsPortrait
  val requestedColumns by columnPreference.collectAsState()
  val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID && !isReorderMode
  val gridState = rememberLazyGridState()
  val showLocation by viewPreferences.showLocation.collectAsState()
  val showCategory by viewPreferences.showCategory.collectAsState()
  val showStreamDetails by viewPreferences.showStreamDetails.collectAsState()
  val bottomClearance = maxOf(
    app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current,
    app.gyrolet.mpvrx.ui.browser.NavigationBarState.miniPlayerClearance,
  ) + 80.dp
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by viewPreferences.showSubtitleIndicator.collectAsState()
  val unlimitedNameLines by viewPreferences.unlimitedNameLines.collectAsState()
  val showVideoThumbnails by viewPreferences.showThumbnails.collectAsState()
  val showSizeChip by viewPreferences.showSizeChip.collectAsState()
  val showResolutionChip by viewPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by viewPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by viewPreferences.showProgressBar.collectAsState()
  val showDateChip by viewPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by viewPreferences.showCodecSupportIndicator.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by viewPreferences.showExtensionField.collectAsState()
  val showDurationField by viewPreferences.showDurationField.collectAsState()
  val centerGridTitles by viewPreferences.centerGridTitles.collectAsState()
  val musicCoverArtSize by viewPreferences.audioCoverArtSize.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val density = LocalDensity.current
  val audioThumbnailSizePx = with(density) { musicCoverArtSize.dp.roundToPx() }
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showCodecSupportIndicator,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
      showExtensionField,
      showDurationField,
      centerGridTitles,
      thumbnailQuality,
      swipeLeft,
      swipeRight,
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showCodecSupportIndicator = showCodecSupportIndicator,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
        showExtensionField = showExtensionField,
        showDurationField = showDurationField,
        centerGridTitles = centerGridTitles,
        thumbnailQuality = thumbnailQuality,
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
      )
    }

  // Find the most recently played video (highest lastPlayedAt timestamp)
  val mostRecentlyPlayedItem =
    remember(videoItems) {
      videoItems
        .filter { it.playlistItem.lastPlayedAt > 0 }
        .maxByOrNull { it.playlistItem.lastPlayedAt }
    }

  val itemContent: @Composable (PlaylistVideoItem, Boolean, Int, Modifier) -> Unit = { item, grid, columns, itemModifier ->
    val sourceLabel = when {
      isM3uPlaylist -> null
      item.protocol != null -> item.protocol.displayName
      item.isNetwork -> stringResource(R.string.playlist_source_network)
      else -> stringResource(R.string.playlist_source_local)
    }
    val location = item.displayLocation().takeIf { showLocation && it.isNotBlank() }
    val thumbnailClick = {
      if (tapThumbnailToSelect) selectionManager.toggleFromUser(item) else onVideoItemClick(item)
    }
    if (isM3uPlaylist || item.isNetwork) {
      M3UVideoCard(
        title = item.video.displayName,
        url = item.video.path,
        logoUrl = item.playlistItem.tvgLogo,
        groupTitle = item.playlistItem.groupTitle,
        hasDrm = !item.playlistItem.licenseType.isNullOrBlank() || !item.playlistItem.licenseKey.isNullOrBlank(),
        hasCustomUserAgent = !item.playlistItem.userAgent.isNullOrBlank(),
        onClick = { onVideoItemClick(item) },
        onLongClick = { onVideoItemLongClick(item) },
        onThumbClick = thumbnailClick,
        onFavoriteClick = onToggleFavorite?.let { toggle -> { toggle(item) } },
        isSelected = selectionManager.isSelected(item),
        isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
        isFavorite = item.playlistItem.isFavorite,
        video = item.video,
        showSourceWarning = isSourceDisconnected(item),
        sourceLabel = sourceLabel,
        sourceColor = sourceChipColor(item.protocol, item.isNetwork),
        sourceSubtitle = location,
        isGridMode = grid,
        showLocation = showLocation,
        showCategory = showCategory,
        showStreamDetails = showStreamDetails,
        uiConfig = videoCardUiConfig,
        modifier = itemModifier,
      )
    } else {
      VideoCard(
        video = if (isAudio && !item.video.isAudio) item.video.copy(isAudio = true) else item.video,
        onSwipeAction = swipeActions.video.takeUnless { selectionManager.isInSelectionMode || isReorderMode },
        isWatched = swipePlaybackInfo[item.video.path]?.isWatched == true,
        isOldAndUnplayed = swipePlaybackInfo[item.video.path]?.isOldAndUnplayed == true,
        progressPercentage = if (item.playlistItem.lastPosition > 0 && item.video.duration > 0) {
          item.playlistItem.lastPosition.toFloat() / item.video.duration.toFloat() * 100f
        } else null,
        isRecentlyPlayed = item.playlistItem.id == mostRecentlyPlayedItem?.playlistItem?.id,
        isSelected = selectionManager.isSelected(item),
        onClick = { onVideoItemClick(item) },
        onLongClick = { onVideoItemLongClick(item) },
        onThumbClick = thumbnailClick,
        isGridMode = grid,
        gridColumns = columns,
        showSubtitleIndicator = showSubtitleIndicator,
        thumbnailWidthPx = if (isAudio && !grid) audioThumbnailSizePx else null,
        thumbnailHeightPx = if (isAudio && !grid) audioThumbnailSizePx else null,
        sourceLabel = sourceLabel,
        sourceColor = sourceChipColor(item.protocol, item.isNetwork),
        sourceSubtitle = location,
        modifier = itemModifier,
        uiConfig = videoCardUiConfig,
        titleAction = if (onToggleFavorite != null) {
          { PlaylistBookmarkButton(item.playlistItem.isFavorite, onToggle = { onToggleFavorite(item) }) }
        } else null,
      )
    }
  }

  when {
    isLoading && videoItems.isEmpty() -> {
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
          verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
              .spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlaylistAdd,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (isAudio) stringResource(R.string.ui_no_songs_in_playlist)
              else androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_no_videos_in_playlist),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text =
              if (isAudio) stringResource(R.string.ui_add_songs_to_get_started)
              else androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_add_videos_to_get_started),
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
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
            stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
          ),
        label = "scrollbarAlpha",
      )

      // Reorderable state
      val reorderFeedback = rememberReorderFeedback()
      val reorderableLazyListState =
        rememberReorderableLazyListState(listState) { from, to ->
          if (isReorderMode) {
            onReorder(from.index, to.index)
            reorderFeedback.move(from.index, to.index)
          }
        }

      BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maximumColumns = playlistGridColumnLimit(maxWidth.value.toInt())
        val columns = if (manualGrid && requestedColumns > 0) requestedColumns.coerceIn(1, maximumColumns) else maximumColumns
        if (isGridMode) {
          LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomClearance),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(count = videoItems.size, key = { videoItems[it].playlistItem.id }) { index ->
              itemContent(videoItems[index], true, columns, Modifier.fillMaxWidth())
            }
          }
        } else {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = bottomClearance),
        ) {
          items(
            count = videoItems.size,
            key = { index -> videoItems[index].playlistItem.id },
          ) { index ->
            ReorderableItem(reorderableLazyListState, key = videoItems[index].playlistItem.id) { isDragging ->
              val item = videoItems[index]
              val elevation = dragElevation(isDragging)

              Row(
                modifier = Modifier.fillMaxWidth().shadow(elevation, MaterialTheme.shapes.medium, clip = false),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                itemContent(item, false, 1, Modifier.weight(1f))

                if (isReorderMode) {
                  IconButton(
                    onClick = { },
                    modifier =
                      Modifier
                        .size(48.dp)
                        .draggableHandle(
                          interactionSource = reorderFeedback.interactions,
                          onDragStarted = { reorderFeedback.start() },
                        ),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.DragHandle,
                      contentDescription =
                        androidx.compose.ui.res.stringResource(
                          app.gyrolet.mpvrx.R.string.ui_drag_to_reorder,
                        ),
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }
              }
            }
          }
        }
        }

        if (hasEnoughItems && scrollbarAlpha > 0.01f) {
          val scrollbarModifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 6.dp)
            .graphicsLayer { alpha = scrollbarAlpha }
          val labelForIndex: (Int) -> String? = { index ->
            fastScrollGlyph(videoItems.getOrNull(index)?.video?.displayName)
          }
          if (isGridMode) {
            ExpressiveScrollBar(
              gridState = gridState,
              dragLabelProvider = labelForIndex,
              modifier = scrollbarModifier,
            )
          } else {
            ExpressiveScrollBar(
              listState = listState,
              dragLabelProvider = labelForIndex,
              modifier = scrollbarModifier,
            )
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
        label = {
          Text(
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.pref_all_sources),
          )
        },
      )
    }

    if (hasFavorites) {
      item {
        FilterChip(
          selected = selectedFilter == M3U_FILTER_FAVORITES,
          onClick = { onFilterSelected(M3U_FILTER_FAVORITES) },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_saved),
            )
          },
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
    title = {
      Text(
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_stream_url),
      )
    },
    text = {
      Text(
        text = url,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      androidx.compose.material3.TextButton(
        onClick = {
          onCopy()
          onDismiss()
        },
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.ContentCopy,
          contentDescription = null,
          modifier = Modifier.padding(end = 4.dp).size(18.dp),
        )
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_copy),
        )
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(onClick = onDismiss) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
        )
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


  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = pluralStringResource(R.plurals.playlist_remove_dialog_title, itemCount, itemCount),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
      )
    },
    text = {
      androidx.compose.foundation.layout.Column(
        verticalArrangement =
          androidx.compose.foundation.layout.Arrangement
            .spacedBy(16.dp),
      ) {
        androidx.compose.material3.Card(
          colors =
            androidx.compose.material3.CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ),
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Text(
            text = pluralStringResource(R.plurals.playlist_remove_dialog_message, itemCount),
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
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_remove_from_playlist),
          fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      androidx.compose.material3.TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

private fun getFileNameFromUri(
  context: android.content.Context,
  uri: Uri,
): String? =
  try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
      cursor.moveToFirst()
      cursor.getString(nameIndex)
    } ?: uri.lastPathSegment
  } catch (e: Exception) {
    uri.lastPathSegment
  }

private const val M3U_FILTER_ALL = "__m3u_all__"

/**
 * True when the entry belongs to a saved network connection that currently has no live session.
 * Reads the same status map the Network tab renders, so the two can never disagree.
 *
 * A connect already in flight counts as "not disconnected": the probe publishes
 * `isConnecting = true` with `isConnected` still false, and treating that as disconnected would
 * flash the warning dot across every card of that source for the duration of the probe.
 */
private fun PlaylistVideoItem.isSourceDisconnected(statuses: Map<Long, ConnectionStatus>): Boolean {
  val id = connectionId ?: return false
  val status = statuses[id] ?: return true
  return !status.isConnected && !status.isConnecting
}

/** Don't flash a "connecting" hint for probes that finish faster than this. */
private const val NETWORK_HINT_DELAY_MS = 400L
private const val M3U_FILTER_FAVORITES = "__m3u_favorites__"
