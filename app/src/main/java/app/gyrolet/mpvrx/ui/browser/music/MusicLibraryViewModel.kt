/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MusicLibraryViewModel : ViewModel(), KoinComponent {

  private val playlistRepository: PlaylistRepository by inject()
  private val browserPreferences: app.gyrolet.mpvrx.preferences.BrowserPreferences by inject()

  // Keep the unfiltered MediaStore result so changing the minimum-duration preference can update
  // Songs, Albums and Artists immediately without rescanning storage on every slider movement.
  private val _allSongs = MutableStateFlow<List<MusicSong>>(emptyList())

  private val _songs = MutableStateFlow<List<MusicSong>>(emptyList())
  val songs: StateFlow<List<MusicSong>> = _songs.asStateFlow()

  private val _albums = MutableStateFlow<List<MusicAlbum>>(emptyList())
  val albums: StateFlow<List<MusicAlbum>> = _albums.asStateFlow()

  private val _artists = MutableStateFlow<List<MusicArtist>>(emptyList())
  val artists: StateFlow<List<MusicArtist>> = _artists.asStateFlow()

  val playlists: StateFlow<List<PlaylistEntity>> =
    playlistRepository.observeAllPlaylists(isAudio = true)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _selectedTab = MutableStateFlow(MusicTab.SONGS)
  val selectedTab: StateFlow<MusicTab> = _selectedTab.asStateFlow()

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _sortField = MutableStateFlow(MusicSortField.TITLE)
  val sortField: StateFlow<MusicSortField> = _sortField.asStateFlow()

  private val _sortOrder = MutableStateFlow(MusicSortOrder.ASCENDING)
  val sortOrder: StateFlow<MusicSortOrder> = _sortOrder.asStateFlow()

  val viewMode: StateFlow<MusicViewMode> = browserPreferences.musicViewMode.stateIn(viewModelScope)

  private val _selectedAlbum = MutableStateFlow<MusicAlbum?>(null)
  val selectedAlbum: StateFlow<MusicAlbum?> = _selectedAlbum.asStateFlow()

  private val _selectedArtist = MutableStateFlow<MusicArtist?>(null)
  val selectedArtist: StateFlow<MusicArtist?> = _selectedArtist.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  val recentlyPlayedFilePath: StateFlow<String?> =
    RecentlyPlayedOps.observeLastPlayedPath()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val isPlaybackActive: StateFlow<Boolean> =
    PlaybackSession.state
      .map { session -> session.currentItem != null && !session.paused }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

  init {
    viewModelScope.launch {
      browserPreferences.minimumAudioDurationSeconds
        .changes()
        .distinctUntilChanged()
        .collect { minimumSeconds -> applyDurationFilter(minimumSeconds) }
    }
  }

  val filteredSongs: StateFlow<List<MusicSong>> = combine(
    _songs, _searchQuery, _sortField, _sortOrder
  ) { songList, query, field, order ->
    var result = songList
    if (query.isNotBlank()) {
      val q = query.trim().lowercase()
      result = result.filter {
        it.title.lowercase().contains(q) ||
          it.artist.lowercase().contains(q) ||
          it.album.lowercase().contains(q)
      }
    }
    val sorted = when (field) {
      MusicSortField.TITLE -> result.sortedBy { it.title.lowercase() }
      MusicSortField.ARTIST -> result.sortedBy { it.artist.lowercase() }
      MusicSortField.ALBUM -> result.sortedBy { it.album.lowercase() }
      MusicSortField.DURATION -> result.sortedBy { it.durationMs }
      MusicSortField.DATE_ADDED -> result.sortedBy { it.dateAdded }
      MusicSortField.TRACK_COUNT, MusicSortField.YEAR -> result.sortedBy { it.year }
    }
    if (order == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val filteredAlbums: StateFlow<List<MusicAlbum>> = combine(
    _albums, _searchQuery, _sortField, _sortOrder
  ) { albumList, query, field, order ->
    var result = albumList
    if (query.isNotBlank()) {
      val q = query.trim().lowercase()
      result = result.filter {
        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
      }
    }
    val sorted = when (field) {
      MusicSortField.TITLE, MusicSortField.ALBUM -> result.sortedBy { it.title.lowercase() }
      MusicSortField.ARTIST -> result.sortedBy { it.artist.lowercase() }
      MusicSortField.TRACK_COUNT -> result.sortedBy { it.songCount }
      MusicSortField.YEAR -> result.sortedBy { it.year }
      else -> result.sortedBy { it.title.lowercase() }
    }
    if (order == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val filteredArtists: StateFlow<List<MusicArtist>> = combine(
    _artists, _searchQuery, _sortField, _sortOrder
  ) { artistList, query, field, order ->
    var result = artistList
    if (query.isNotBlank()) {
      val q = query.trim().lowercase()
      result = result.filter { it.name.lowercase().contains(q) }
    }
    val sorted = when (field) {
      MusicSortField.TRACK_COUNT -> result.sortedBy { it.songCount }
      else -> result.sortedBy { it.name.lowercase() }
    }
    if (order == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  suspend fun refreshLibrary(context: Context) {
    _isLoading.value = true
    try {
      _allSongs.value = MusicLibraryScanner.scanSongs(context)
      applyDurationFilter(browserPreferences.minimumAudioDurationSeconds.get())
    } catch (e: Exception) {
      e.printStackTrace()
    } finally {
      _isLoading.value = false
    }
  }

  /**
   * Minimum duration is a lower bound only. There is intentionally no upper bound: if the user
   * selects 30 seconds, every 30s, 3min, 30min, or multi-hour audio file remains in the library.
   */
  private fun applyDurationFilter(minimumSeconds: Int) {
    val minimumMs = minimumSeconds.coerceAtLeast(0).toLong() * 1000L
    val visibleSongs =
      if (minimumMs == 0L) {
        _allSongs.value
      } else {
        _allSongs.value.filter { song -> song.durationMs >= minimumMs }
      }

    _songs.value = visibleSongs
    _albums.value = buildAlbums(visibleSongs)
    _artists.value = buildArtists(visibleSongs)

    // Never leave the detail screen pointing at an album/artist that was completely filtered out.
    _selectedAlbum.value = _selectedAlbum.value?.takeIf { selected -> _albums.value.any { it.id == selected.id } }
    _selectedArtist.value = _selectedArtist.value?.takeIf { selected -> _artists.value.any { it.id == selected.id } }
  }

  private fun buildAlbums(songs: List<MusicSong>): List<MusicAlbum> =
    songs
      .groupBy { song -> if (song.albumId > 0) song.albumId else song.album.hashCode().toLong() }
      .map { (albumId, albumSongs) ->
        val firstSong = albumSongs.first()
        MusicAlbum(
          id = albumId,
          title = firstSong.album,
          artist = firstSong.artist,
          songCount = albumSongs.size,
          year = albumSongs.maxOfOrNull { it.year } ?: 0,
          albumArtUri = firstSong.albumArtUri,
        )
      }
      .sortedBy { it.title.lowercase() }

  private fun buildArtists(songs: List<MusicSong>): List<MusicArtist> =
    songs
      .groupBy { it.artist.lowercase().trim() }
      .map { (_, artistSongs) ->
        val firstSong = artistSongs.first()
        MusicArtist(
          id = firstSong.artist.hashCode().toLong(),
          name = firstSong.artist,
          songCount = artistSongs.size,
          albumCount = artistSongs.map { it.albumId }.distinct().size,
        )
      }
      .sortedBy { it.name.lowercase() }

  fun scanLibrary(context: Context) {
    viewModelScope.launch {
      refreshLibrary(context)
    }
  }

  fun setTab(tab: MusicTab) {
    _selectedTab.value = tab
  }

  fun setSearchQuery(query: String) {
    _searchQuery.value = query
  }

  fun setSortField(field: MusicSortField) {
    _sortField.value = field
  }

  fun toggleSortOrder() {
    _sortOrder.value = if (_sortOrder.value == MusicSortOrder.ASCENDING) {
      MusicSortOrder.DESCENDING
    } else {
      MusicSortOrder.ASCENDING
    }
  }

  fun setSortOrder(order: MusicSortOrder) {
    _sortOrder.value = order
  }

  fun toggleViewMode() {
    val nextMode = if (viewMode.value == MusicViewMode.GRID) MusicViewMode.LIST else MusicViewMode.GRID
    browserPreferences.musicViewMode.set(nextMode)
  }

  fun setViewMode(mode: MusicViewMode) {
    browserPreferences.musicViewMode.set(mode)
  }

  fun selectAlbum(album: MusicAlbum?) {
    _selectedAlbum.value = album
  }

  fun selectArtist(artist: MusicArtist?) {
    _selectedArtist.value = artist
  }

  fun playSong(context: Context, song: MusicSong, songList: List<MusicSong> = _songs.value) {
    if (songList.isEmpty()) return
    val index = songList.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
    val playlistUris = ArrayList(songList.map { it.uri })

    val intent = Intent(Intent.ACTION_VIEW, song.uri).apply {
      setClass(context, PlayerActivity::class.java)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra("playlist_index", index)
      putExtra("launch_source", "music_library")
      putExtra("media_library_audio", true)
      putExtra("is_audio", true)
      putParcelableArrayListExtra("playlist", playlistUris)
      putExtra("title", "${song.artist} - ${song.title}")
    }
    context.startActivity(intent)
  }

  fun playAllSongs(context: Context, songsToPlay: List<MusicSong>, shuffle: Boolean = false) {
    if (songsToPlay.isEmpty()) return
    val list = if (shuffle) songsToPlay.shuffled() else songsToPlay
    val firstSong = list.first()
    val playlistUris = ArrayList(list.map { it.uri })

    val intent = Intent(Intent.ACTION_VIEW, firstSong.uri).apply {
      setClass(context, PlayerActivity::class.java)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      putExtra("internal_launch", true)
      putExtra("playlist_index", 0)
      putExtra("launch_source", if (shuffle) "music_shuffle" else "music_play_all")
      putExtra("media_library_audio", true)
      putExtra("is_audio", true)
      putParcelableArrayListExtra("playlist", playlistUris)
      putExtra("title", "${firstSong.artist} - ${firstSong.title}")
    }
    context.startActivity(intent)
  }

  fun createPlaylist(name: String) {
    viewModelScope.launch {
      if (name.isNotBlank()) {
        playlistRepository.createPlaylist(name.trim(), isAudio = true)
      }
    }
  }

  fun deletePlaylist(playlist: PlaylistEntity) {
    viewModelScope.launch {
      playlistRepository.deletePlaylist(playlist)
    }
  }

  suspend fun deleteSongs(context: Context, songsToDelete: List<MusicSong>): Pair<Int, Int> {
    val videos = songsToDelete.map { song ->
      app.gyrolet.mpvrx.domain.media.model.Video(
        id = song.id,
        title = song.title,
        displayName = song.title,
        path = song.path,
        uri = song.uri,
        duration = song.durationMs,
        durationFormatted = android.text.format.DateUtils.formatElapsedTime(song.durationMs / 1000),
        size = 0L,
        sizeFormatted = "",
        dateModified = song.dateAdded,
        dateAdded = song.dateAdded,
        mimeType = "audio/*",
        bucketId = "",
        bucketDisplayName = "",
        width = 0,
        height = 0,
        fps = 0f,
        resolution = "",
        isAudio = true
      )
    }
    val result = app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps.deleteVideos(context.applicationContext as android.app.Application, videos)
    refreshLibrary(context)
    return result
  }
}
