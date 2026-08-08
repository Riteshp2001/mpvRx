/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PlaylistOption(
  val playlist: PlaylistEntity,
  val itemCount: Int,
)

class AddToPlaylistViewModel :
  ViewModel(),
  KoinComponent {
  private val repository: PlaylistRepository by inject()

  private val _playlistOptions = MutableStateFlow<List<PlaylistOption>>(emptyList())
  val playlistOptions: StateFlow<List<PlaylistOption>> = _playlistOptions.asStateFlow()

  private var observeJob: kotlinx.coroutines.Job? = null

  fun loadPlaylists(isAudio: Boolean?) {
    observeJob?.cancel()
    observeJob = viewModelScope.launch(Dispatchers.IO) {
      repository.observeAllPlaylists(isAudio).collectLatest { playlists ->
        _playlistOptions.value =
          playlists
            .sortedBy { it.name.lowercase() }
            .map { playlist ->
              PlaylistOption(
                playlist = playlist,
                itemCount = repository.getPlaylistItems(playlist.id).size,
              )
            }
      }
    }
  }

  suspend fun createAndAdd(
    name: String,
    videos: List<Video>,
  ) = withContext(Dispatchers.IO) {
    val isAudio = videos.any { it.isAudio }
    val playlistId = repository.createPlaylist(name, isAudio = isAudio).toInt()
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  suspend fun addToPlaylist(
    playlistId: Int,
    videos: List<Video>,
  ) = withContext(Dispatchers.IO) {
    repository.addItemsToPlaylist(playlistId, videos.asPlaylistItems())
  }

  private fun List<Video>.asPlaylistItems(): List<Pair<String, String>> =
    map { video -> video.path to video.displayName }
}
