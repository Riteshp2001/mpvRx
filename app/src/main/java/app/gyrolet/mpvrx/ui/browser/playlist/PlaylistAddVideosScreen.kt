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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoSortDialog
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListViewModel
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.videolist.VideoListViewModel
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * In-app file picker for adding videos to a playlist: browse storage folders (same folder
 * browsing/sort experience as [app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen]), drill
 * into one and multi-select videos using the app's own [rememberSelectionManager] + [BrowserTopBar]
 * (same as [app.gyrolet.mpvrx.ui.browser.videolist.VideoListScreen]), then add them to the
 * playlist via [PlaylistDetailViewModel.addVideosToPlaylist] — mirrors
 * [app.gyrolet.mpvrx.ui.securefolder.SecureFolderAddFilesScreen] for the Secure Folder flow.
 */
@Serializable
data class PlaylistAddVideosScreen(
  val playlistId: Int,
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val backstack = LocalBackStack.current
    val scope = rememberCoroutineScope()

    val browserPreferences = koinInject<BrowserPreferences>()

    val playlistDetailViewModel: PlaylistDetailViewModel =
      viewModel(
        key = "PlaylistDetailViewModel_$playlistId",
        factory = PlaylistDetailViewModel.factory(application, playlistId),
      )

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
          key = "PlaylistAddVideosVideos_${folder.bucketId}",
          factory = VideoListViewModel.factory(application, folder.bucketId),
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
    val sortedVideos = remember(currentVideos, videoSortType, videoSortOrder) {
      SortUtils.sortVideos(currentVideos, videoSortType, videoSortOrder)
    }

    val selectionManager =
      if (folder != null) {
        key(folder.bucketId) {
          rememberSelectionManager(
            items = sortedVideos,
            getId = { it.id },
            onDeleteItems = { _, _ -> 0 to 0 },
          )
        }
      } else {
        null
      }

    fun addSelectedToPlaylist() {
      val videos = selectionManager?.getSelectedItems() ?: emptyList()
      if (videos.isEmpty()) return
      scope.launch {
        playlistDetailViewModel.addVideosToPlaylist(videos)
        Toast
          .makeText(
            context,
            context.getString(R.string.playlist_add_videos_success, videos.size),
            Toast.LENGTH_SHORT,
          ).show()
        selectionManager?.clear()
        backstack.popSafely()
      }
    }

    BackHandler {
      when {
        selectionManager?.isInSelectionMode == true -> selectionManager.clear()
        folder != null -> selectedFolder = null
        else -> backstack.popSafely()
      }
    }

    Scaffold(
      topBar = {
        if (folder == null) {
          BrowserTopBar(
            title = stringResource(R.string.playlist_add_videos_title),
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = sortedFolders.size,
            onCancelSelection = {},
            onBackClick = { backstack.popSafely() },
            onSortClick = { sortDialogOpen = true },
          )
        } else {
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
        }
      },
      bottomBar = {
        val selectedCount = selectionManager?.selectedCount ?: 0
        if (selectedCount > 0) {
          Surface(tonalElevation = 3.dp) {
            Button(
              onClick = { addSelectedToPlaylist() },
              modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
              Text(stringResource(R.string.playlist_add_videos_button, selectedCount))
            }
          }
        }
      },
    ) { padding ->
      if (folder == null) {
        if (sortedFolders.isEmpty()) {
          EmptyState(
            icon = Icons.RoundedFilled.Folder,
            title = stringResource(R.string.playlist_add_videos_empty_title),
            message = stringResource(R.string.playlist_add_videos_empty_message),
            modifier = Modifier.padding(padding),
          )
        } else {
          LazyColumn(modifier = Modifier.padding(padding)) {
            items(sortedFolders, key = { it.bucketId }) { videoFolder ->
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
          title = stringResource(R.string.playlist_add_videos_empty_title),
          message = stringResource(R.string.playlist_add_videos_empty_message),
          modifier = Modifier.padding(padding),
        )
      } else {
        LazyColumn(modifier = Modifier.padding(padding)) {
          items(sortedVideos, key = { it.id }) { video: Video ->
            VideoCard(
              video = video,
              isSelected = selectionManager?.isSelected(video) == true,
              onClick = { selectionManager?.toggle(video) },
              onThumbClick = { selectionManager?.toggle(video) },
              onLongClick = { selectionManager?.handleLongClick(video) },
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
          }
        }
      }
    }

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
