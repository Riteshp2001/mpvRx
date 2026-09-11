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
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.repository.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class PlaylistOption(
  val id: String,
  val name: String,
  val itemCount: Int,
  val subtitle: String? = null,
  val localPlaylist: PlaylistEntity? = null,
  val jellyfinItem: JellyfinItem? = null,
)

class AddToPlaylistViewModel :
  ViewModel(),
  KoinComponent {
  private val repository: PlaylistRepository by inject()
  private val jellyfinRepository: JellyfinRepository by inject()

  private val _playlistOptions = MutableStateFlow<List<PlaylistOption>>(emptyList())
  val playlistOptions: StateFlow<List<PlaylistOption>> = _playlistOptions.asStateFlow()

  private var observeJob: kotlinx.coroutines.Job? = null
  private var activeJellyfinServer: JellyfinServer? = null

  fun loadPlaylists(isAudio: Boolean?, isJellyfin: Boolean = false) {
    observeJob?.cancel()
    observeJob = viewModelScope.launch(Dispatchers.IO) {
      if (isJellyfin) {
        val servers = jellyfinRepository.allServers.firstOrNull().orEmpty()
        val active = servers.firstOrNull()
        activeJellyfinServer = active
        if (active != null) {
          val playlists = jellyfinRepository.getItems(
            server = active,
            parentId = null,
            includeItemTypes = "Playlist",
            limit = 100,
          ).getOrNull()?.items.orEmpty()

          _playlistOptions.value = playlists
            .sortedBy { it.name.lowercase() }
            .map { item ->
              PlaylistOption(
                id = item.id,
                name = item.name,
                itemCount = item.childCount ?: 0,
                subtitle = "${item.childCount ?: 0} items",
                jellyfinItem = item,
              )
            }
        } else {
          _playlistOptions.value = emptyList()
        }
      } else {
        repository.observeAllPlaylists(isAudio).collectLatest { playlists ->
          _playlistOptions.value =
            playlists
              .sortedWith(
                compareByDescending<PlaylistEntity> { repository.isProtectedPlaylist(it) }
                  .thenBy { it.name.lowercase() }
              )
              .map { playlist ->
                PlaylistOption(
                  id = playlist.id.toString(),
                  name = playlist.name,
                  itemCount = repository.getPlaylistItems(playlist.id).size,
                  subtitle = "${repository.getPlaylistItems(playlist.id).size} items",
                  localPlaylist = playlist,
                )
              }
        }
      }
    }
  }

  suspend fun createAndAdd(
    name: String,
    videos: List<Video>,
    isJellyfin: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    if (isJellyfin) {
      val server = activeJellyfinServer ?: jellyfinRepository.allServers.firstOrNull()?.firstOrNull() ?: return@withContext
      val itemIds = videos.map { extractJellyfinItemId(it) }
      jellyfinRepository.createPlaylist(server, name, itemIds)
    } else {
      val isAudio = videos.firstOrNull()?.isAudio ?: return@withContext
      val compatibleVideos = videos.filter { it.isAudio == isAudio }
      val playlistId = repository.createPlaylist(name, isAudio = isAudio).toInt()
      repository.addItemsToPlaylist(playlistId, compatibleVideos.asPlaylistItems())
    }
  }

  suspend fun addToPlaylist(
    option: PlaylistOption,
    videos: List<Video>,
    isJellyfin: Boolean = false,
  ) = withContext(Dispatchers.IO) {
    if (isJellyfin) {
      val server = activeJellyfinServer ?: jellyfinRepository.allServers.firstOrNull()?.firstOrNull() ?: return@withContext
      val itemIds = videos.map { extractJellyfinItemId(it) }
      jellyfinRepository.addToPlaylist(server, option.id, itemIds)
    } else {
      val playlistId = option.id.toIntOrNull() ?: return@withContext
      val isAudio = option.localPlaylist?.isAudio ?: return@withContext
      repository.addItemsToPlaylist(playlistId, videos.filter { it.isAudio == isAudio }.asPlaylistItems())
    }
  }

  private fun extractJellyfinItemId(video: Video): String {
    val path = video.path
    if (path.contains("/Audio/")) {
      val sub = path.substringAfter("/Audio/")
      return sub.substringBefore("/").substringBefore("?")
    }
    if (path.contains("/Videos/")) {
      val sub = path.substringAfter("/Videos/")
      return sub.substringBefore("/").substringBefore("?")
    }
    if (path.contains("/Items/")) {
      val sub = path.substringAfter("/Items/")
      return sub.substringBefore("/").substringBefore("?")
    }
    return video.id.toString()
  }

  private fun List<Video>.asPlaylistItems(): List<Pair<String, String>> =
    map { video -> video.path to video.displayName }
}
