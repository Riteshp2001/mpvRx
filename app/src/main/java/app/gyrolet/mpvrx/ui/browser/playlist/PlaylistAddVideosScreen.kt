/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.playlist

import android.app.Application
import android.widget.Toast
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.repository.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.rememberVideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoSortDialog
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListViewModel
import app.gyrolet.mpvrx.ui.browser.networkstreaming.NetworkBrowserScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.videolist.VideoListViewModel
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/** Where the picker sources its videos from. */
private enum class AddSourceTab { LOCAL, NETWORK }

/**
 * In-app file picker for adding videos to a playlist.
 *
 * The local source browses storage folders (same folder browsing/sort experience as
 * [app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen]), drills into one and multi-selects
 * videos using the app's own [rememberSelectionManager] + [BrowserTopBar] (same as
 * [app.gyrolet.mpvrx.ui.browser.videolist.VideoListScreen]), then adds them to the playlist via
 * [PlaylistDetailViewModel.addVideosToPlaylist] — mirrors
 * [app.gyrolet.mpvrx.ui.securefolder.SecureFolderAddFilesScreen] for the Secure Folder flow.
 *
 * The network source lists saved connections and hands off to [NetworkBrowserScreen] with
 * `targetPlaylistId`, which owns the selection state and writes the picked files to the playlist.
 */
@Serializable
data class PlaylistAddVideosScreen(
  val playlistId: Int,
  val isAudio: Boolean = false,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backstack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val browserPreferences = koinInject<BrowserPreferences>()
    val networkRepository = koinInject<NetworkRepository>()
    val videoCardUiConfig = rememberVideoCardUiConfig()
    val videoListState = rememberLazyListState()
    val isVideoListScrolling by
      remember(videoListState) {
        derivedStateOf { videoListState.isScrollInProgress }
      }

    val playlistDetailViewModel: PlaylistDetailViewModel =
      viewModel(
        key = "PlaylistDetailViewModel_$playlistId",
        factory = PlaylistDetailViewModel.factory(application, playlistId),
      )

    var selectedSourceTab by rememberSaveable { mutableStateOf(AddSourceTab.LOCAL) }

    val connections by
      remember(networkRepository) { networkRepository.getAllConnections() }
        .collectAsState(initial = emptyList())

    // Connecting belongs to the Network tab: this picker only ever offers sources that are already
    // connected there. Browsing a share would otherwise establish the connection as a side effect.
    // A connect in flight counts as available, the exact negation of the detail screen's warning
    // predicate, so the two screens can never disagree about a source.
    val connectionStatuses by networkRepository.connectionStatuses.collectAsState()
    val connectedConnections =
      remember(connections, connectionStatuses) {
        connections.filter { connection ->
          connectionStatuses[connection.id]?.let { it.isConnected || it.isConnecting } == true
        }
      }


    fun openNetworkConnection(
      connectionId: Long,
      connectionName: String,
    ) {
      backstack.navigateTo(
        NetworkBrowserScreen(
          connectionId = connectionId,
          connectionName = connectionName,
          targetPlaylistId = playlistId,
          targetPlaylistIsAudio = isAudio,
        ),
      )
    }

    // Folder list step (mirrors FolderListScreen's browsing + sort)
    val folderListViewModel: FolderListViewModel =
      viewModel(factory = FolderListViewModel.factory(application))
    val videoFolders by folderListViewModel.videoFolders.collectAsState()
    val folderSortType by browserPreferences.folderSortType.collectAsState()
    val folderSortOrder by browserPreferences.folderSortOrder.collectAsState()
    val sortedFolders = remember(videoFolders, folderSortType, folderSortOrder) {
      SortUtils.sortFolders(videoFolders, folderSortType, folderSortOrder)
    }

    var selectedFolder by remember { mutableStateOf<VideoFolder?>(null) }
    val folder = selectedFolder

    var sortDialogOpen by remember { mutableStateOf(false) }

    // Video list step (mirrors VideoListScreen's browsing, sort and selection)
    val videoListViewModel: VideoListViewModel? =
      if (folder != null) {
        viewModel(
          key = "PlaylistAddVideosVideos_${folder.bucketId}_$isAudio",
          factory = VideoListViewModel.factory(application, folder.bucketId, includeAudio = isAudio),
        )
      } else {
        null
      }
    val currentVideos: List<Video> =
      if (videoListViewModel != null) {
        videoListViewModel.videos.collectAsState().value
      } else {
        emptyList()
      }
    val videoSortType by browserPreferences.videoSortType.collectAsState()
    val videoSortOrder by browserPreferences.videoSortOrder.collectAsState()
    val sortedVideos = remember(currentVideos, videoSortType, videoSortOrder, isAudio) {
      val filtered = currentVideos.filter { it.isAudio == isAudio }
      SortUtils.sortVideos(filtered, videoSortType, videoSortOrder)
    }

    // Selection manager for multi-select videos step
    val selectionManager =
      if (folder != null) {
        rememberSelectionManager(
          items = sortedVideos,
          getId = { it.id },
          onDeleteItems = { _, _ -> Pair(0, 0) },
        )
      } else {
        null
      }

    fun addSelectedToPlaylist() {
      val videos = selectionManager?.getSelectedItems() ?: emptyList()
      if (videos.isEmpty()) return
      scope.launch {
        playlistDetailViewModel.addVideosToPlaylist(videos)
        withContext(Dispatchers.Main) {
          Toast.makeText(
            context,
            context.getString(
              if (isAudio) R.string.playlist_add_songs_success else R.string.playlist_add_videos_success,
              videos.size,
            ),
            Toast.LENGTH_SHORT,
          ).show()
          backstack.popSafely()
        }
      }
    }

    // The local video step replaces the source tabs with the folder's name.
    val onLocalVideoStep = selectedSourceTab == AddSourceTab.LOCAL && folder != null

    BackHandler {
      when {
        selectionManager?.isInSelectionMode == true -> selectionManager.clear()
        onLocalVideoStep -> selectedFolder = null
        else -> backstack.popSafely()
      }
    }

    Scaffold(
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (onLocalVideoStep) {
          BrowserTopBar(
            title = folder.name,
            isInSelectionMode = selectionManager?.isInSelectionMode == true,
            selectedCount = selectionManager?.selectedCount ?: 0,
            totalCount = sortedVideos.size,
            onCancelSelection = { selectionManager?.clear() },
            onBackClick = { selectedFolder = null },
            onSortClick = { sortDialogOpen = true },
            onSelectAll = { selectionManager?.selectAll() },
            onInvertSelection = { selectionManager?.invertSelection() },
            onDeselectAll = { selectionManager?.clear() },
          )
        } else {
          Column {
            BrowserTopBar(
              title = stringResource(if (isAudio) R.string.playlist_add_songs_title else R.string.playlist_add_videos_title),
              isInSelectionMode = false,
              selectedCount = 0,
              totalCount = if (selectedSourceTab == AddSourceTab.LOCAL) sortedFolders.size else connectedConnections.size,
              onCancelSelection = {},
              onBackClick = { backstack.popSafely() },
              onSortClick =
                if (selectedSourceTab == AddSourceTab.LOCAL) {
                  { sortDialogOpen = true }
                } else {
                  null
                },
            )
            SourceTabRow(
              selectedTab = selectedSourceTab,
              onTabSelected = { selectedSourceTab = it },
            )
          }
        }
      },
      bottomBar = {
        // The selection manager belongs to the local folder step; showing its count while the
        // network source is on screen would offer to add videos the user can no longer see.
        val selectedCount = if (selectedSourceTab == AddSourceTab.LOCAL) selectionManager?.selectedCount ?: 0 else 0
        if (selectedCount > 0) {
          Surface(tonalElevation = 3.dp) {
            Button(
              onClick = { addSelectedToPlaylist() },
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
              Text(
                stringResource(
                  if (isAudio) R.string.playlist_add_songs_button else R.string.playlist_add_videos_button,
                  selectedCount,
                ),
              )
            }
          }
        }
      },
    ) { padding ->
      if (selectedSourceTab == AddSourceTab.NETWORK) {
        NetworkConnectionList(
          connectedConnections = connectedConnections,
          onConnectionClick = { connection -> openNetworkConnection(connection.id, connection.name) },
          modifier = Modifier.padding(padding),
        )
      } else if (folder == null) {
        if (sortedFolders.isEmpty()) {
          EmptyState(
            icon = Icons.RoundedFilled.Folder,
            title = stringResource(if (isAudio) R.string.playlist_add_songs_empty_folder_title else R.string.playlist_add_videos_empty_title),
            message = stringResource(if (isAudio) R.string.playlist_add_songs_empty_folder_message else R.string.playlist_add_videos_empty_message),
            modifier = Modifier.padding(padding),
          )
        } else {
          LazyColumn(modifier = Modifier.padding(padding)) {
            items(
              items = sortedFolders,
              key = { it.bucketId },
              contentType = { "folder" },
            ) { videoFolder ->
              FolderCard(
                folder = videoFolder,
                onClick = { selectedFolder = videoFolder },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
              )
            }
          }
        }
      } else if (sortedVideos.isEmpty()) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(if (isAudio) R.string.playlist_add_songs_empty_title else R.string.playlist_add_videos_empty_title),
          message = stringResource(if (isAudio) R.string.playlist_add_songs_empty_message else R.string.playlist_add_videos_empty_message),
          modifier = Modifier.padding(padding),
        )
      } else {
        LazyColumn(
          state = videoListState,
          modifier = Modifier.padding(padding),
        ) {
          items(
            items = sortedVideos,
            key = { it.id },
            contentType = { "video" },
          ) { video: Video ->
            VideoCard(
              video = video,
              isSelected = selectionManager?.isSelected(video) == true,
              onClick = { selectionManager?.toggle(video) },
              onThumbClick = { selectionManager?.toggle(video) },
              onLongClick = { selectionManager?.handleLongClick(video) },
              allowThumbnailLoading = !isVideoListScrolling,
              uiConfig = videoCardUiConfig,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
          }
        }
      }
    }

    if (selectedSourceTab == AddSourceTab.LOCAL) {
      if (folder == null) {
        FolderSortDialog(
          isOpen = sortDialogOpen,
          onDismiss = { sortDialogOpen = false },
          sortType = folderSortType,
          sortOrder = folderSortOrder,
          onSortTypeChange = { browserPreferences.folderSortType.set(it) },
          onSortOrderChange = { browserPreferences.folderSortOrder.set(it) },
        )
      } else {
        VideoSortDialog(
          isOpen = sortDialogOpen,
          onDismiss = { sortDialogOpen = false },
          sortType = videoSortType,
          sortOrder = videoSortOrder,
          onSortTypeChange = { browserPreferences.videoSortType.set(it) },
          onSortOrderChange = { browserPreferences.videoSortOrder.set(it) },
        )
      }
    }

  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceTabRow(
  selectedTab: AddSourceTab,
  onTabSelected: (AddSourceTab) -> Unit,
  modifier: Modifier = Modifier,
) {
  SingleChoiceSegmentedButtonRow(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    AddSourceTab.entries.forEachIndexed { index, tab ->
      SegmentedButton(
        selected = selectedTab == tab,
        onClick = { if (selectedTab != tab) onTabSelected(tab) },
        shape = SegmentedButtonDefaults.itemShape(index, AddSourceTab.entries.size),
        colors = themedSegmentedButtonColors(),
      ) {
        Text(
          stringResource(
            when (tab) {
              AddSourceTab.LOCAL -> R.string.playlist_source_local
              AddSourceTab.NETWORK -> R.string.playlist_source_network
            },
          ),
        )
      }
    }
  }
}

@Composable
private fun NetworkConnectionList(
  connectedConnections: List<NetworkConnection>,
  onConnectionClick: (NetworkConnection) -> Unit,
  modifier: Modifier = Modifier,
) {
  // No "add connection" affordance here: creating a connection does not connect it, and
  // connecting only happens on the Network tab, so the button could never unblock this screen.
  if (connectedConnections.isEmpty()) {
    Column(
      modifier = modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      EmptyState(
        icon = Icons.RoundedFilled.SignalWifiStatusbarConnectedNoInternet4,
        title = stringResource(R.string.playlist_connect_source_title),
        message = stringResource(R.string.playlist_connect_source_message),
      )
    }
  } else {
    LazyColumn(modifier = modifier) {
      items(
        items = connectedConnections,
        key = { it.id },
        contentType = { "connection" },
      ) { connection ->
        NetworkConnectionPickerRow(
          connection = connection,
          onClick = { onConnectionClick(connection) },
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
      }
    }
  }
}

/**
 * A connection row stripped down to "pick this source": the full
 * [app.gyrolet.mpvrx.ui.browser.cards.NetworkConnectionCard] is built around an explicit
 * connect/disconnect lifecycle plus edit/delete actions, none of which belong in a picker — its
 * browse action is only reachable once the connection is already established.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkConnectionPickerRow(
  connection: NetworkConnection,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    onClick = onClick,
    modifier = modifier.fillMaxWidth(),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = connection.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "${connection.protocol.displayName} • ${connection.host}:${connection.port}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
        imageVector = Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
