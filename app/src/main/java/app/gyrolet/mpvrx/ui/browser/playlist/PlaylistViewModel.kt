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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.repository.NetworkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class PlaylistWithCount(
  val playlist: PlaylistEntity,
  val itemCount: Int,
  /** Source kinds actually present among the counted entries; null means a local file. */
  val sources: List<NetworkProtocol?> = emptyList(),
)

class PlaylistViewModel(
  application: Application,
) : androidx.lifecycle.AndroidViewModel(application),
  KoinComponent {
  private val repository: PlaylistRepository by inject()
  private val networkRepository: NetworkRepository by inject()
  // Using MediaFileRepository singleton directly

  private val _playlistsWithCount = MutableStateFlow<List<PlaylistWithCount>>(emptyList())
  val playlistsWithCount: StateFlow<List<PlaylistWithCount>> = _playlistsWithCount.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  // Track if initial load has completed to prevent empty state flicker
  private val _hasCompletedInitialLoad = MutableStateFlow(false)
  val hasCompletedInitialLoad: StateFlow<Boolean> = _hasCompletedInitialLoad.asStateFlow()

  companion object {
    private const val TAG = "PlaylistViewModel"

    fun factory(application: Application) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlaylistViewModel(application) as T
      }
  }

  init {
    // Load cached playlists instantly for immediate display
    viewModelScope.launch(Dispatchers.IO) {
      try {
        // Get initial cached data synchronously
        val cachedPlaylists = repository.getAllPlaylists()
        if (cachedPlaylists.isNotEmpty()) {
          // Show cached data immediately (without video counts for speed)
          val quickLoad =
            visiblePlaylists(cachedPlaylists)
              .map { playlist ->
                PlaylistWithCount(playlist, 0) // Show 0 count initially
              }
          _playlistsWithCount.value = quickLoad
          _hasCompletedInitialLoad.value = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached playlists", e)
      }
    }

    // Then observe for updates with actual counts
    viewModelScope.launch(Dispatchers.IO) {
      repository.observeAllPlaylists().collectLatest { playlistsFromDb ->
        _playlistsWithCount.value = loadPlaylistsWithCounts(playlistsFromDb)
        _hasCompletedInitialLoad.value = true
      }
    }
  }

  /**
   * Get the actual count of videos that exist for a playlist
   */
  private data class PlaylistStats(
    val count: Int,
    val sources: List<NetworkProtocol?>,
  )

  /**
   * Counts the entries that are still usable and reports which sources they come from, so the
   * card can badge every distinct source the playlist actually contains.
   *
   * [protocolById] is resolved once by the caller rather than per playlist.
   */
  private suspend fun getPlaylistStats(
    playlist: PlaylistEntity,
    protocolById: Map<Long, NetworkProtocol>,
  ): PlaylistStats {
    val items = repository.getPlaylistItems(playlist.id)
    if (items.isEmpty()) return PlaylistStats(0, emptyList())

    // M3U entries are remote streams rather than files, so those cards keep their playlist-type
    // badge instead of per-source chips.
    if (playlist.isM3uPlaylist) {
      return PlaylistStats(items.size, emptyList())
    }

    // Parsed once per entry: constructing the URI is the costly part and both the counting and the
    // source badges need the same answer.
    val references = items.map { NetworkPlaybackUri.parse(it.filePath) }
    val networkItems = items.filterIndexed { index, _ -> references[index] != null }
    val localItems = items.filterIndexed { index, _ -> references[index] == null }

    val bucketIds =
      localItems
        .map { item ->
          File(item.filePath).parent ?: ""
        }.toSet()

    val allVideos = MediaFileRepository.getVideosForBuckets(getApplication(), bucketIds, includeAudioOverride = true)
    val knownPaths = allVideos.mapTo(mutableSetOf()) { it.path }

    // Network entries are mpvrx-network:// references with no local file to verify: they always
    // count. Only local entries are checked against MediaStore for a file that still exists —
    // their paths are meaningless as filesystem paths, so mixing them in would drop them entirely.
    val liveLocalItems = localItems.filter { item -> item.filePath in knownPaths }

    val protocols =
      references
        .filterNotNull()
        .mapNotNull { protocolById[it.connectionId] }
        .distinct()
        .sortedBy { it.ordinal }

    val sources =
      buildList {
        if (liveLocalItems.isNotEmpty()) add(null)
        addAll(protocols)
      }

    return PlaylistStats(liveLocalItems.size + networkItems.size, sources)
  }

  /** Resolves connection protocols once, then counts and badges every visible playlist. */
  private suspend fun loadPlaylistsWithCounts(playlists: List<PlaylistEntity>): List<PlaylistWithCount> {
    // Tombstones included: a playlist entry keeps naming its share after that connection is
    // deleted, so the badge stays honest instead of silently dropping to "Local".
    val protocolById = networkRepository.getAllConnectionsIncludingDeleted().associate { it.id to it.protocol }
    return visiblePlaylists(playlists).map { playlist ->
      val stats = getPlaylistStats(playlist, protocolById)
      PlaylistWithCount(playlist, stats.count, stats.sources)
    }
  }

  private fun visiblePlaylists(playlists: List<PlaylistEntity>): List<PlaylistEntity> =
    repository.prioritizeFavorites(playlists)

  fun refresh() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _isLoading.value = true
        val playlistsFromDb = repository.getAllPlaylists()

        _playlistsWithCount.value = loadPlaylistsWithCounts(playlistsFromDb)
      } catch (e: Exception) {
        Log.e(TAG, "Error refreshing playlists", e)
      } finally {
        _isLoading.value = false
      }
    }
  }

  suspend fun createPlaylist(name: String): Long = repository.createPlaylist(name, isAudio = false)

  suspend fun createM3UPlaylist(
    url: String,
    userAgent: String? = null,
  ): Result<Long> = repository.createM3UPlaylist(url, userAgent)

  suspend fun createXtreamPlaylist(
    serverUrl: String,
    username: String,
    password: String,
  ): Result<Long> = repository.createXtreamPlaylist(serverUrl, username, password)

  suspend fun createM3UPlaylistFromFile(uri: android.net.Uri): Result<Long> =
    repository.createM3UPlaylistFromFile(getApplication(), uri)

  suspend fun refreshM3UPlaylist(playlistId: Int): Result<Unit> = repository.refreshM3UPlaylist(playlistId)

  fun isProtectedPlaylist(playlist: PlaylistEntity): Boolean = repository.isProtectedPlaylist(playlist)

  suspend fun deletePlaylist(playlist: PlaylistEntity) {
    repository.deletePlaylist(playlist)
  }

  suspend fun updatePlaylist(playlist: PlaylistEntity) {
    repository.updatePlaylist(playlist)
  }
}
