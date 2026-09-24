/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.navidrome

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.data.navidrome.NavidromeClient
import app.gyrolet.mpvrx.data.network.ServerUrlUtils
import app.gyrolet.mpvrx.data.navidrome.NavidromeSearchResult
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAlbum
import app.gyrolet.mpvrx.domain.navidrome.NavidromeArtist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAuthMode
import app.gyrolet.mpvrx.domain.navidrome.NavidromeMusicTab
import app.gyrolet.mpvrx.domain.navidrome.NavidromePlaylist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.domain.navidrome.NavidromeSong
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.MusicSourceProvider
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.music.MusicSortOrder
import app.gyrolet.mpvrx.ui.browser.music.MusicViewMode
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class NavidromeUiState(
  val servers: List<NavidromeServer> = emptyList(),
  val activeServer: NavidromeServer? = null,
  val isLoading: Boolean = false,
  val error: String? = null,
  val activeTab: NavidromeMusicTab = NavidromeMusicTab.HOME,
  val jumpBackIn: List<NavidromeSong> = emptyList(),
  val playlists: List<NavidromePlaylist> = emptyList(),
  val recentlyAddedAlbums: List<NavidromeAlbum> = emptyList(),
  val artistsToExplore: List<NavidromeArtist> = emptyList(),
  val tracks: List<NavidromeSong> = emptyList(),
  val albums: List<NavidromeAlbum> = emptyList(),
  val artists: List<NavidromeArtist> = emptyList(),
  val searchQuery: String = "",
  val searchResult: NavidromeSearchResult? = null,
  val detailAlbum: NavidromeAlbum? = null,
  val detailArtist: NavidromeArtist? = null,
  val detailPlaylist: NavidromePlaylist? = null,
  val isConnectingServer: Boolean = false,
  val connectServerError: String? = null,
  val sortField: MusicSortField = MusicSortField.TITLE,
  val sortOrder: MusicSortOrder = MusicSortOrder.ASCENDING,
  val viewMode: MusicViewMode = MusicViewMode.GRID,
)

class NavidromeViewModel(
  application: Application,
) : AndroidViewModel(application), KoinComponent {
  private val navidromeRepository: NavidromeRepository by inject()
  private val navidromeClient: NavidromeClient by inject()
  private val mediaServerPreferences: MediaServerPreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()

  private val _uiState = MutableStateFlow(
    NavidromeUiState(
      sortField = browserPreferences.navidromeSortField.get(),
      sortOrder = browserPreferences.navidromeSortOrder.get(),
      viewMode = browserPreferences.navidromeViewMode.get(),
    )
  )
  val uiState: StateFlow<NavidromeUiState> = _uiState.asStateFlow()
  private val loadGeneration = java.util.concurrent.atomic.AtomicLong()
  private var loadJob: kotlinx.coroutines.Job? = null
  private var loadedServer: NavidromeServer? = null
  private var searchJob: kotlinx.coroutines.Job? = null
  private var detailJob: Job? = null

  init {
    viewModelScope.launch {
      navidromeRepository.allServers.collect { servers ->
        _uiState.update { current ->
          val currentActive = current.activeServer
          val newActive = if (currentActive != null && servers.any { it.id == currentActive.id }) {
            servers.first { it.id == currentActive.id }
          } else {
            servers.firstOrNull()
          }
          current.copy(servers = servers, activeServer = newActive)
        }
        if (_uiState.value.activeServer != null) {
          loadAllData()
        }
      }
    }
    viewModelScope.launch {
      navidromeRepository.favoriteUpdates.collect { (songId, isFav) ->
        applyFavoriteUpdate(songId, isFav)
      }
    }
  }

  fun setMusicTab(tab: NavidromeMusicTab) {
    _uiState.update { it.copy(activeTab = tab) }
  }

  fun selectServer(server: NavidromeServer) {
    _uiState.update { it.copy(activeServer = server) }
    loadAllData()
  }

  fun deleteServer(server: NavidromeServer) {
    viewModelScope.launch(Dispatchers.IO) {
      navidromeRepository.deleteServer(server)
    }
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    if (query.isBlank()) {
      searchJob?.cancel()
      _uiState.update { it.copy(searchResult = null) }
    } else {
      performSearch(query)
    }
  }

  private fun performSearch(query: String) {
    val server = _uiState.value.activeServer ?: return
    searchJob?.cancel()
    searchJob = viewModelScope.launch(Dispatchers.IO) {
      val result = navidromeRepository.search(server, query).getOrNull()
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      _uiState.update {
        if (it.activeServer?.id == server.id && it.searchQuery == query) it.copy(searchResult = result) else it
      }
    }
  }

  fun refresh() {
    loadAllData()
  }

  suspend fun refreshSuspend() {
    loadAllDataInternal()
  }

  private fun loadAllData() {
    viewModelScope.launch {
      loadAllDataInternal()
    }
  }

  private suspend fun loadAllDataInternal() = withContext(Dispatchers.Main.immediate) {
    val server = _uiState.value.activeServer ?: return@withContext
    loadJob?.cancel()
    loadJob = currentCoroutineContext()[Job]
    val generation = loadGeneration.incrementAndGet()
    val changedServer = loadedServer?.id != server.id
    loadedServer = server
    if (changedServer) {
      searchJob?.cancel()
      detailJob?.cancel()
    }
    _uiState.update {
      if (changedServer) it.copy(isLoading = true, error = null, tracks = emptyList(), albums = emptyList(),
        artists = emptyList(), playlists = emptyList(), jumpBackIn = emptyList(), recentlyAddedAlbums = emptyList(),
        artistsToExplore = emptyList(), searchResult = null, detailAlbum = null, detailArtist = null, detailPlaylist = null)
      else it.copy(isLoading = true, error = null)
    }
    suspend fun publish(transform: (NavidromeUiState) -> NavidromeUiState) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      _uiState.update { state ->
        if (generation == loadGeneration.get() && state.activeServer?.id == server.id) transform(state) else state
      }
    }
    suspend fun failed(error: Throwable) {
      if (error is kotlinx.coroutines.CancellationException) throw error
      publish { it.copy(error = error.message) }
    }

    try {
      coroutineScope {
        launch {
          navidromeRepository.getRandomSongs(server, 50).onSuccess { songs ->
            publish { state ->
              val favoriteIds = state.playlists.firstOrNull { it.id == "virtual_favorites_playlist" }?.songs.orEmpty().mapTo(HashSet()) { it.id }
              val tracks = songs.map { if (it.id in favoriteIds) it.copy(isFavorite = true) else it }
              state.copy(tracks = tracks, jumpBackIn = tracks.take(12))
            }
          }.onFailure { failed(it) }
        }
        launch {
          navidromeRepository.getPlaylists(server).onSuccess { playlists ->
            publish { state -> state.copy(playlists =
              state.playlists.filter { it.id == "virtual_favorites_playlist" } + playlists.filterNot { it.name.equals("Favorites", true) }) }
          }.onFailure { failed(it) }
        }
        launch {
          navidromeRepository.getStarred(server).onSuccess { songs ->
            val starred = songs.map { it.copy(isFavorite = true) }
            val favoriteIds = starred.mapTo(HashSet()) { it.id }
            val favorites = NavidromePlaylist(id = "virtual_favorites_playlist", name = "Favorites", songCount = starred.size,
              durationSeconds = starred.sumOf { it.durationSeconds }, songs = starred)
            publish { state -> state.copy(
              playlists = listOf(favorites) + state.playlists.filterNot { it.name.equals("Favorites", true) },
              tracks = state.tracks.map { it.copy(isFavorite = it.id in favoriteIds) },
              jumpBackIn = state.jumpBackIn.map { it.copy(isFavorite = it.id in favoriteIds) }) }
          }.onFailure { failed(it) }
        }
        launch {
          navidromeRepository.getAlbums(server, type = "recent", size = 20).onSuccess { albums ->
            publish { it.copy(recentlyAddedAlbums = albums) }
          }.onFailure { failed(it) }
        }
        launch {
          val previous = _uiState.value.albums
          val albums = linkedMapOf<String, NavidromeAlbum>()
          var offset = 0
          while (true) {
            val page = navidromeRepository.getAlbums(server, type = "alphabeticalByName", size = 100, offset = offset)
              .onFailure { failed(it) }.getOrNull() ?: break
            val previousSize = albums.size
            page.forEach { albums[it.id] = it }
            val complete = page.size < 100 || albums.size == previousSize
            val visible = albums.values.toList() + if (complete) emptyList() else previous.filterNot { it.id in albums }
            publish { it.copy(albums = visible) }
            if (complete) break
            offset += page.size
          }
        }
        launch {
          navidromeRepository.getArtists(server).onSuccess { artists ->
            publish { it.copy(artists = artists, artistsToExplore = artists.shuffled().take(15)) }
          }.onFailure { failed(it) }
        }
      }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      failed(e)
    } finally {
      if (generation == loadGeneration.get()) {
        _uiState.update { it.copy(isLoading = false) }
        loadJob = null
      }
    }
  }

  fun openAlbumDetail(album: NavidromeAlbum) {
    val server = _uiState.value.activeServer ?: return
    detailJob?.cancel()
    _uiState.update { it.copy(detailAlbum = album, detailArtist = null, detailPlaylist = null) }
    detailJob = viewModelScope.launch {
      val fullAlbum = navidromeRepository.getAlbum(server, album.id).getOrNull()
      currentCoroutineContext().ensureActive()
      if (fullAlbum != null) {
        _uiState.update {
          if (it.activeServer?.id == server.id && it.detailAlbum?.id == album.id) it.copy(detailAlbum = fullAlbum) else it
        }
      }
    }
  }

  fun openArtistDetail(artist: NavidromeArtist) {
    val server = _uiState.value.activeServer ?: return
    detailJob?.cancel()
    _uiState.update { it.copy(detailArtist = artist, detailAlbum = null, detailPlaylist = null) }
    detailJob = viewModelScope.launch {
      val fullArtist = navidromeRepository.getArtist(server, artist.id).getOrNull()
      currentCoroutineContext().ensureActive()
      if (fullArtist != null) {
        _uiState.update {
          if (it.activeServer?.id != server.id || it.detailArtist?.id != artist.id) return@update it
          it.copy(
            detailArtist = fullArtist.copy(
              artistImageUrl = fullArtist.artistImageUrl ?: artist.artistImageUrl,
              coverArtId = fullArtist.coverArtId ?: artist.coverArtId,
            )
          )
        }
      }
    }
  }

  fun openPlaylistDetail(playlist: NavidromePlaylist) {
    val server = _uiState.value.activeServer ?: return
    detailJob?.cancel()
    if (playlist.id == "virtual_favorites_playlist" || playlist.id == "favorites") {
      _uiState.update { it.copy(detailPlaylist = playlist, detailAlbum = null, detailArtist = null) }
      detailJob = viewModelScope.launch {
        val starredSongs = navidromeRepository.getStarred(server).getOrNull()?.map { it.copy(isFavorite = true) } ?: return@launch
        currentCoroutineContext().ensureActive()
        _uiState.update { current ->
          if (current.activeServer?.id != server.id || current.detailPlaylist?.id != playlist.id) return@update current
          val updatedFav = playlist.copy(
            songCount = starredSongs.size,
            durationSeconds = starredSongs.sumOf { s -> s.durationSeconds },
            songs = starredSongs,
          )
          current.copy(
            detailPlaylist = updatedFav,
            playlists = listOf(updatedFav) + current.playlists.filterNot { it.id == "virtual_favorites_playlist" || it.id == "favorites" },
          )
        }
      }
      return
    }
    _uiState.update { it.copy(detailPlaylist = playlist, detailAlbum = null, detailArtist = null) }
    detailJob = viewModelScope.launch {
      val fullPlaylist = navidromeRepository.getPlaylist(server, playlist.id).getOrNull()
      currentCoroutineContext().ensureActive()
      if (fullPlaylist != null) {
        _uiState.update {
          if (it.activeServer?.id == server.id && it.detailPlaylist?.id == playlist.id) it.copy(detailPlaylist = fullPlaylist) else it
        }
      }
    }
  }

  fun closeDetail() {
    detailJob?.cancel()
    _uiState.update { it.copy(detailAlbum = null, detailArtist = null, detailPlaylist = null) }
  }

  fun playSong(context: Context, song: NavidromeSong) {
    val server = _uiState.value.activeServer ?: return
    val contextTracks = _uiState.value.detailAlbum?.songs
      ?: _uiState.value.detailPlaylist?.songs
      ?: _uiState.value.tracks
    val trackList = if (contextTracks.any { it.id == song.id }) contextTracks else listOf(song)
    val startIndex = trackList.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    playTrackList(context, server, trackList, startIndex)
  }

  fun playAll(context: Context, songs: List<NavidromeSong>, startIndex: Int = 0) {
    val server = _uiState.value.activeServer ?: return
    if (songs.isEmpty()) return
    playTrackList(context, server, songs, startIndex)
  }

  fun shufflePlay(context: Context, songs: List<NavidromeSong>) {
    val server = _uiState.value.activeServer ?: return
    if (songs.isEmpty()) return
    playTrackList(context, server, songs.shuffled(), 0)
  }

  private fun playTrackList(
    context: Context,
    server: NavidromeServer,
    songs: List<NavidromeSong>,
    startIndex: Int,
  ) {
    if (songs.isEmpty()) return
    val currentSong = songs.getOrNull(startIndex) ?: songs.first()
    val posterUrl = navidromeRepository.getSongCoverArtUrl(server, currentSong)

    val playlistUris = songs.map { Uri.parse(navidromeRepository.getStreamUrl(server, it.id)) }
    val streamUrl = playlistUris.getOrNull(startIndex)?.toString() ?: navidromeRepository.getStreamUrl(server, currentSong.id)
    val playlistTitles = songs.map { it.title }
    val playlistArtists = songs.map { it.artist }
    val playlistArtworkUrls = songs.map { navidromeRepository.getSongCoverArtUrl(server, it) ?: "" }

    MediaUtils.playFile(
      source = streamUrl,
      context = context,
      launchSource = "navidrome_music",
      title = currentSong.title,
      posterUrl = posterUrl,
      playlist = playlistUris,
      playlistIndex = startIndex,
      playlistTitles = playlistTitles,
      playlistArtists = playlistArtists,
      playlistArtworkUrls = playlistArtworkUrls,
      isAudio = true,
      playlistDurationsSeconds = songs.map { it.durationSeconds },
    )
  }

  fun setSortField(field: MusicSortField) {
    browserPreferences.navidromeSortField.set(field)
    _uiState.update { it.copy(sortField = field) }
  }

  fun setSortOrder(order: MusicSortOrder) {
    browserPreferences.navidromeSortOrder.set(order)
    _uiState.update { it.copy(sortOrder = order) }
  }

  fun setViewMode(mode: MusicViewMode) {
    browserPreferences.navidromeViewMode.set(mode)
    _uiState.update { it.copy(viewMode = mode) }
  }

  fun toggleFavorite(song: NavidromeSong) {
    val server = _uiState.value.activeServer ?: return
    val newFav = !song.isFavorite
    applyFavoriteUpdate(song.id, newFav, song)
    viewModelScope.launch(Dispatchers.IO) {
      navidromeRepository.toggleFavorite(server, song, newFav)
    }
  }

  fun applyFavoriteUpdate(songId: String, isFav: Boolean, songHint: NavidromeSong? = null) {
    _uiState.update { current ->
      val updatedTracks = current.tracks.map { if (it.id == songId) it.copy(isFavorite = isFav) else it }
      val updatedJump = current.jumpBackIn.map { if (it.id == songId) it.copy(isFavorite = isFav) else it }
      val updatedSearch = current.searchResult?.let { res ->
        res.copy(songs = res.songs.map { if (it.id == songId) it.copy(isFavorite = isFav) else it })
      }
      val updatedDetailAlbum = current.detailAlbum?.let { alb ->
        alb.copy(songs = alb.songs.map { if (it.id == songId) it.copy(isFavorite = isFav) else it })
      }

      val existingFavPlaylist = current.playlists.firstOrNull { it.id == "virtual_favorites_playlist" || it.id == "favorites" }
      val targetSong = songHint
        ?: current.tracks.firstOrNull { it.id == songId }
        ?: current.jumpBackIn.firstOrNull { it.id == songId }
        ?: current.detailPlaylist?.songs?.firstOrNull { it.id == songId }
        ?: current.detailAlbum?.songs?.firstOrNull { it.id == songId }

      val updatedFavSongs = if (existingFavPlaylist != null) {
        if (isFav) {
          if (existingFavPlaylist.songs.any { it.id == songId }) {
            existingFavPlaylist.songs.map { if (it.id == songId) it.copy(isFavorite = true) else it }
          } else if (targetSong != null) {
            listOf(targetSong.copy(isFavorite = true)) + existingFavPlaylist.songs
          } else existingFavPlaylist.songs
        } else {
          existingFavPlaylist.songs.filterNot { it.id == songId }
        }
      } else {
        if (isFav && targetSong != null) listOf(targetSong.copy(isFavorite = true)) else emptyList()
      }

      val updatedFavPlaylist = (existingFavPlaylist ?: NavidromePlaylist(
        id = "virtual_favorites_playlist",
        name = "Favorites",
      )).copy(
        songCount = updatedFavSongs.size,
        durationSeconds = updatedFavSongs.sumOf { it.durationSeconds },
        songs = updatedFavSongs,
      )

      val otherPlaylists = current.playlists.filterNot { it.id == "virtual_favorites_playlist" || it.id == "favorites" }
      val updatedPlaylists = listOf(updatedFavPlaylist) + otherPlaylists

      val updatedDetailPlaylist = if (current.detailPlaylist?.id == "virtual_favorites_playlist" || current.detailPlaylist?.id == "favorites") {
        updatedFavPlaylist
      } else {
        current.detailPlaylist?.copy(
          songs = current.detailPlaylist.songs.map { if (it.id == songId) it.copy(isFavorite = isFav) else it }
        )
      }

      current.copy(
        tracks = updatedTracks,
        jumpBackIn = updatedJump,
        searchResult = updatedSearch,
        detailAlbum = updatedDetailAlbum,
        detailPlaylist = updatedDetailPlaylist,
        playlists = updatedPlaylists,
      )
    }
  }

  fun toggleAlbumFavorite(album: NavidromeAlbum) {
    val server = _uiState.value.activeServer ?: return
    val newFav = !album.isFavorite
    viewModelScope.launch(Dispatchers.IO) {
      navidromeRepository.toggleAlbumFavorite(server, album, newFav)
      _uiState.update { current ->
        current.copy(
          albums = current.albums.map { if (it.id == album.id) it.copy(isFavorite = newFav) else it },
          recentlyAddedAlbums = current.recentlyAddedAlbums.map { if (it.id == album.id) it.copy(isFavorite = newFav) else it },
          detailAlbum = if (current.detailAlbum?.id == album.id) current.detailAlbum.copy(isFavorite = newFav) else current.detailAlbum,
        )
      }
    }
  }

  fun toggleArtistFavorite(artist: NavidromeArtist) {
    val server = _uiState.value.activeServer ?: return
    val newFav = !artist.isFavorite
    viewModelScope.launch(Dispatchers.IO) {
      navidromeRepository.toggleArtistFavorite(server, artist, newFav)
      _uiState.update { current ->
        current.copy(
          artists = current.artists.map { if (it.id == artist.id) it.copy(isFavorite = newFav) else it },
          artistsToExplore = current.artistsToExplore.map { if (it.id == artist.id) it.copy(isFavorite = newFav) else it },
          detailArtist = if (current.detailArtist?.id == artist.id) current.detailArtist.copy(isFavorite = newFav) else current.detailArtist,
        )
      }
    }
  }

  fun connectServer(
    serverUrl: String,
    serverName: String,
    authMode: NavidromeAuthMode = NavidromeAuthMode.CREDENTIALS,
    username: String = "",
    password: String = "",
    token: String = "",
    existingServer: NavidromeServer? = null,
    onSuccess: () -> Unit,
  ) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(isConnectingServer = true, connectServerError = null) }
      val cleanUrl = serverUrl.trim().trimEnd('/')

      val effectiveUsername = if (authMode == NavidromeAuthMode.TOKEN && username.isBlank()) {
        val extracted = navidromeClient.extractUsername(token)
        extracted ?: ""
      } else {
        username.trim()
      }

      if (effectiveUsername.isBlank()) {
        _uiState.update {
          it.copy(
            isConnectingServer = false,
            connectServerError = "Username is required for Subsonic authentication",
          )
        }
        return@launch
      }

      val displayName = serverName.trim().ifBlank {
        runCatching { Uri.parse(cleanUrl).host.orEmpty() }.getOrDefault("").ifBlank { "Navidrome ($effectiveUsername)" }
      }

      val candidateUrls = ServerUrlUtils.generateCandidateUrls(cleanUrl, defaultPort = 4533)
      val urlsToTry = if (candidateUrls.isNotEmpty()) candidateUrls else listOf(cleanUrl)

      var successfulServer: NavidromeServer? = null
      var lastError: String? = null

      for (candUrl in urlsToTry) {
        val testServer = NavidromeServer(
          id = existingServer?.id ?: 0,
          name = displayName,
          serverUrl = candUrl,
          username = effectiveUsername,
          password = password,
          token = token,
          authMode = authMode,
          lastConnected = System.currentTimeMillis(),
        )
        val pingResult = navidromeRepository.ping(testServer)
        if (pingResult.isSuccess) {
          successfulServer = testServer
          break
        } else {
          lastError = pingResult.exceptionOrNull()?.message
        }
      }

      if (successfulServer != null) {
        val savedId = if (existingServer != null) {
          navidromeRepository.updateServer(successfulServer)
          existingServer.id
        } else {
          navidromeRepository.saveServer(successfulServer)
        }
        val savedServer = successfulServer.copy(id = savedId)
        _uiState.update {
          it.copy(
            isConnectingServer = false,
            connectServerError = null,
            activeServer = savedServer,
          )
        }
        mediaServerPreferences.musicSourceProvider.set(MusicSourceProvider.NAVIDROME)
        withContext(Dispatchers.Main) {
          onSuccess()
        }
      } else {
        val err = lastError ?: "Failed to connect to Navidrome server"
        _uiState.update {
          it.copy(isConnectingServer = false, connectServerError = err)
        }
      }
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NavidromeViewModel(application)
        }
      }
  }
}
