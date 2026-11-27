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
import app.gyrolet.mpvrx.database.repository.PlaylistItemInput
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.repository.JellyfinRepository
import kotlinx.coroutines.CancellationException
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
  val localPlaylist: PlaylistEntity? = null,
  val jellyfinItem: JellyfinItem? = null,
)

/**
 * One entry the add-to-playlist dialog can write.
 *
 * Deliberately not a [Video]: network files carry only a path, a name and an audio flag, so
 * fabricating the other twenty Video fields would push placeholder durations and bucket ids into
 * the playlist UI. Playlist rows only ever need [path] and [name], which is exactly what the
 * network picker already builds by hand.
 */
data class PlaylistAddCandidate(
  val path: String,
  val name: String,
  val isAudio: Boolean,
  /** Only read in Jellyfin mode; [toPlaylistCandidate] derives it from the stream path. */
  val jellyfinItemId: String? = null,
)

fun List<Video>.toPlaylistCandidates(): List<PlaylistAddCandidate> = map { it.toPlaylistCandidate() }

private fun Video.toPlaylistCandidate(): PlaylistAddCandidate =
  PlaylistAddCandidate(
    path = path,
    name = displayName,
    isAudio = isAudio,
    jellyfinItemId = jellyfinItemIdFromPath(path, id),
  )

class AddToPlaylistViewModel :
  ViewModel(),
  KoinComponent {
  private val repository: PlaylistRepository by inject()
  private val jellyfinRepository: JellyfinRepository by inject()

  private val _playlistOptions = MutableStateFlow<List<PlaylistOption>>(emptyList())
  val playlistOptions: StateFlow<List<PlaylistOption>> = _playlistOptions.asStateFlow()
  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private var observeJob: kotlinx.coroutines.Job? = null
  private var activeJellyfinServer: JellyfinServer? = null
  private var loadGeneration = 0L

  fun loadPlaylists(
    isAudio: Boolean?,
    isJellyfin: Boolean = false,
  ) {
    observeJob?.cancel()
    val generation = ++loadGeneration
    _isLoading.value = true
    _playlistOptions.value = emptyList()
    observeJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          if (isJellyfin) {
            val servers = jellyfinRepository.allServers.firstOrNull().orEmpty()
            val active = servers.firstOrNull()
            activeJellyfinServer = active
            if (active != null) {
              val playlists =
                jellyfinRepository
                  .getItems(
                    server = active,
                    parentId = null,
                    includeItemTypes = "Playlist",
                    limit = 100,
                  ).getOrNull()
                  ?.items
                  .orEmpty()

              _playlistOptions.value =
                playlists
                  .sortedBy { it.name.lowercase() }
                  .map { item ->
                    PlaylistOption(
                      id = item.id,
                      name = item.name,
                      itemCount = item.childCount ?: 0,
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
                      .thenBy { it.name.lowercase() },
                  ).map { playlist ->
                    val itemCount = repository.getPlaylistItems(playlist.id).size
                    PlaylistOption(
                      id = playlist.id.toString(),
                      name = playlist.name,
                      itemCount = itemCount,
                      localPlaylist = playlist,
                    )
                  }
              if (loadGeneration == generation) _isLoading.value = false
            }
          }
        } finally {
          if (loadGeneration == generation) _isLoading.value = false
        }
      }
  }

  suspend fun createAndAdd(
    name: String,
    candidates: List<PlaylistAddCandidate>,
    isJellyfin: Boolean = false,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      if (isJellyfin) {
        val server =
          activeJellyfinServer ?: jellyfinRepository.allServers.firstOrNull()?.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No Jellyfin server is available"))
        jellyfinRepository.createPlaylist(server, name, candidates.mapNotNull { it.jellyfinItemId }).map { Unit }
      } else {
        try {
          val isAudio =
            candidates.firstOrNull()?.isAudio
              ?: throw IllegalArgumentException("No compatible items were selected")
          val playlistId = repository.createPlaylist(name, isAudio = isAudio).toInt()
          repository.addItemsToPlaylist(playlistId, candidates.filter { it.isAudio == isAudio }.asPlaylistItems())
          Result.success(Unit)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Result.failure(error)
        }
      }
    }

  suspend fun addToPlaylist(
    option: PlaylistOption,
    candidates: List<PlaylistAddCandidate>,
    isJellyfin: Boolean = false,
  ): Result<Unit> =
    withContext(Dispatchers.IO) {
      if (isJellyfin) {
        val server =
          activeJellyfinServer ?: jellyfinRepository.allServers.firstOrNull()?.firstOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No Jellyfin server is available"))
        jellyfinRepository.addToPlaylist(server, option.id, candidates.mapNotNull { it.jellyfinItemId })
      } else {
        try {
          val playlistId = option.id.toIntOrNull() ?: throw IllegalArgumentException("Invalid playlist")
          val isAudio = option.localPlaylist?.isAudio ?: throw IllegalArgumentException("Unknown playlist media type")
          repository.addItemsToPlaylist(playlistId, candidates.filter { it.isAudio == isAudio }.asPlaylistItems())
          Result.success(Unit)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Result.failure(error)
        }
      }
    }

  private fun List<PlaylistAddCandidate>.asPlaylistItems(): List<PlaylistItemInput> =
    map { candidate -> PlaylistItemInput(candidate.path, candidate.name) }
}

/** Jellyfin stream URLs carry the item id as their first path segment; other sources use the media id. */
private fun jellyfinItemIdFromPath(
  path: String,
  fallbackId: Long,
): String {
  for (marker in listOf("/Audio/", "/Videos/", "/Items/")) {
    if (path.contains(marker)) {
      return path.substringAfter(marker).substringBefore("/").substringBefore("?")
    }
  }
  return fallbackId.toString()
}
