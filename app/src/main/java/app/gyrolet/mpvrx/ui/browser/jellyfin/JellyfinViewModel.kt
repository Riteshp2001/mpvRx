/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.download.AppDownloadManager
import app.gyrolet.mpvrx.domain.download.DownloadLocations
import app.gyrolet.mpvrx.domain.download.DownloadMetadata
import app.gyrolet.mpvrx.domain.download.DownloadSources
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthMode
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSearchCategory
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

enum class JellyfinMusicTab(val title: String) {
  HOME("Home"),
  TRACKS("Songs"),
  ALBUMS("Albums"),
  ARTISTS("Artists"),
  PLAYLISTS("Playlists"),
}

data class JellyfinLibraryView(
  val id: String,
  val title: String,
  /** Jellyfin IncludeItemTypes requested recursively for this view. */
  val itemTypes: String,
  val collectionType: String? = null,
  val isMusic: Boolean = false,
)

data class JellyfinHomeSection(
  val library: JellyfinItem,
  val title: String,
  val subtitle: String? = null,
  val items: List<JellyfinItem> = emptyList(),
  val isShows: Boolean = false,
)

data class JellyfinUiState(
  val servers: List<JellyfinServer> = emptyList(),
  val activeServer: JellyfinServer? = null,
  val libraries: List<JellyfinItem> = emptyList(),
  val librarySections: List<JellyfinHomeSection> = emptyList(),
  val heroItems: List<JellyfinItem> = emptyList(),
  val resumeItems: List<JellyfinItem> = emptyList(),
  val latestMovies: List<JellyfinItem> = emptyList(),
  val latestShows: List<JellyfinItem> = emptyList(),
  val latestMusic: List<JellyfinItem> = emptyList(),
  val recommendations: List<JellyfinItem> = emptyList(),
  val currentItems: List<JellyfinItem> = emptyList(),
  val openLibrary: JellyfinLibraryView? = null,
  val selectedLibraryId: String? = null,
  val availableGenres: List<String> = emptyList(),
  val selectedGenreFilter: String? = null,
  val sortBy: JellyfinSortBy = JellyfinSortBy.NAME,
  val sortOrder: JellyfinSortOrder = JellyfinSortOrder.ASCENDING,
  val isUnplayedOnly: Boolean = false,
  val totalRecordCount: Int = 0,
  val startIndex: Int = 0,
  val isLoading: Boolean = false,
  val isLoadingMore: Boolean = false,
  val hasMore: Boolean = false,
  val isAuthenticating: Boolean = false,
  val error: String? = null,
  val authError: String? = null,
  val searchQuery: String = "",
  val searchCategory: JellyfinSearchCategory = JellyfinSearchCategory.ALL,

  // Jellyfin Music Tab State (AFinity style)
  val musicActiveTab: JellyfinMusicTab = JellyfinMusicTab.HOME,
  val musicFavorites: List<JellyfinItem> = emptyList(),
  val musicJumpBackIn: List<JellyfinItem> = emptyList(),
  val musicRecentlyPlayedAlbums: List<JellyfinItem> = emptyList(),
  val musicArtistsToExplore: List<JellyfinItem> = emptyList(),
  val musicPlaylists: List<JellyfinItem> = emptyList(),
  val musicArtists: List<JellyfinItem> = emptyList(),
  val musicAlbums: List<JellyfinItem> = emptyList(),
  val musicTracks: List<JellyfinItem> = emptyList(),
  val musicGenres: List<JellyfinItem> = emptyList(),
  val isMusicLoading: Boolean = false,

  // Detail Sheet State
  val detailItem: JellyfinItem? = null,
  val detailSeasons: List<JellyfinItem> = emptyList(),
  val selectedDetailSeasonId: String? = null,
  val detailEpisodes: List<JellyfinItem> = emptyList(),
  val detailSimilarItems: List<JellyfinItem> = emptyList(),
  val isDetailLoading: Boolean = false,
  val isDetailEpisodesLoading: Boolean = false,
) {
  val hasMusicLibrary: Boolean
    get() = activeServer != null && libraries.any { JellyfinViewModel.isMusicLibrary(it) }
}

class JellyfinViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val jellyfinRepository: JellyfinRepository by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()
  private val downloadManager: AppDownloadManager by inject()

  private var loadDashboardJob: Job? = null
  private var loadItemsJob: Job? = null
  private var searchJob: Job? = null
  private var detailJob: Job? = null
  private var seasonEpisodesJob: Job? = null
  private var musicLoadJob: Job? = null
  private var loadedMusicHomeLibraryId: String? = null

  private val _uiState = MutableStateFlow(JellyfinUiState())
  val uiState: StateFlow<JellyfinUiState> = _uiState.asStateFlow()

  init {
    loadServers()
  }

  fun loadServers() {
    viewModelScope.launch {
      jellyfinRepository.allServers.collect { servers ->
        _uiState.update { state ->
          val active = state.activeServer?.let { cur -> servers.find { it.id == cur.id } } ?: servers.firstOrNull()
          state.copy(
            servers = servers,
            activeServer = active,
          )
        }
        val currentActive = _uiState.value.activeServer
        if (currentActive != null && _uiState.value.libraries.isEmpty() && _uiState.value.heroItems.isEmpty()) {
          loadHomeDashboard(currentActive)
        }
      }
    }
  }

  fun selectServer(server: JellyfinServer) {
    _uiState.update {
      it.copy(
        activeServer = server,
        openLibrary = null,
        currentItems = emptyList(),
        resumeItems = emptyList(),
        heroItems = emptyList(),
        latestMovies = emptyList(),
        latestShows = emptyList(),
        librarySections = emptyList(),
        recommendations = emptyList(),
        searchQuery = "",
        detailItem = null,
        error = null,
      )
    }
    loadHomeDashboard(server)
  }

  fun refresh() {
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary
    if (library == null) {
      loadHomeDashboard(active)
    } else {
      loadLibraryItems(active, library, resetPagination = true)
    }
  }

  suspend fun refreshSuspend() {
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary
    if (library == null) {
      loadHomeDashboard(active)
      loadDashboardJob?.join()
    } else {
      loadLibraryItems(active, library, resetPagination = true)
      loadItemsJob?.join()
    }
  }

  fun loadLibraries(server: JellyfinServer) {
    loadHomeDashboard(server)
  }

  fun loadHomeDashboard(server: JellyfinServer) {
    loadDashboardJob?.cancel()
    loadItemsJob?.cancel()
    musicLoadJob?.cancel()
    loadDashboardJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val libsDeferred = async { jellyfinRepository.getLibraries(server) }
        val resumeDeferred = async { jellyfinRepository.getResumeItems(server, limit = 16) }
        val latestDeferred = async { jellyfinRepository.getLatestMedia(server, limit = 32) }
        val suggestionsDeferred = async { jellyfinRepository.getSuggestions(server, limit = 36) }
        val topRatedDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Movie,Series",
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RATING,
              sortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.DESCENDING,
              limit = 36,
            )
          }
        val musicDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Audio,MusicAlbum",
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.DATE_ADDED,
              sortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.DESCENDING,
              limit = 20,
            )
          }
        val heroDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Movie,Series",
              isPlayed = false,
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RANDOM,
              limit = 15,
            )
          }

        val libsResult = libsDeferred.await()
        val resumeResult = resumeDeferred.await()
        val latestResult = latestDeferred.await()
        val suggestionsResult = suggestionsDeferred.await()
        val topRatedResult = topRatedDeferred.await()
        val musicResult = musicDeferred.await()
        val heroResult = heroDeferred.await()

        val libs = sortJellyfinLibraries(libsResult.getOrDefault(emptyList()))
        val resumeRaw = resumeResult.getOrDefault(emptyList())
        val latestRaw = latestResult.getOrDefault(emptyList())
        val suggestionsRaw = suggestionsResult.getOrDefault(emptyList())
        val topRatedRaw = topRatedResult.getOrNull()?.items ?: emptyList()
        val musicRaw = musicResult.getOrNull()?.items ?: emptyList()

        // Helper filter to exclude music and folders from general video home sections
        fun isVideoMedia(item: JellyfinItem): Boolean {
          if (item.isAudio || item.isFolder || item.type == "Folder" || item.type == "MusicAlbum" || item.type == "Audio" || item.type == "MusicArtist" || item.type == "CollectionFolder") return false
          return true
        }

        val resume = resumeRaw.filter { isVideoMedia(it) }

        // Fetch latest media for each non-music library concurrently
        val videoLibs = libs.filter { !isMusicLibrary(it) }
        val librarySectionsDeferred = videoLibs.map { lib ->
          async {
            val isShowLib = isSeriesLibrary(lib)
            val latestItemsResult = jellyfinRepository.getLatestMedia(
              server = server,
              parentId = lib.id,
              limit = 16,
              groupItems = true,
            )
            val rawItems = latestItemsResult.getOrDefault(emptyList()).filter { isVideoMedia(it) }
            val containsShows = rawItems.any { it.isSeries || it.type == "Episode" || it.seriesName != null }
            val isShows = isShowLib || containsShows

            val processedItems = if (isShows) {
              resolveShowsAsSeries(server, rawItems)
            } else {
              rawItems
            }

            if (processedItems.isNotEmpty()) {
              val title = if (lib.name.startsWith("Latest", ignoreCase = true)) {
                lib.name
              } else {
                "Latest ${lib.name}"
              }
              val subtitle = if (isShows) "Newly updated series" else "Newly added to ${lib.name}"
              JellyfinHomeSection(
                library = lib,
                title = title,
                subtitle = subtitle,
                items = processedItems,
                isShows = isShows,
              )
            } else {
              null
            }
          }
        }
        val librarySections = librarySectionsDeferred.awaitAll().filterNotNull()

        val legacyLatestMovies = latestRaw.filter { isVideoMedia(it) && (it.type == "Movie" || it.collectionType?.equals("movies", ignoreCase = true) == true) }
        val legacyLatestShows = resolveShowsAsSeries(
          server,
          latestRaw.filter { isVideoMedia(it) && (it.type == "Series" || it.type == "Episode" || it.collectionType?.equals("tvshows", ignoreCase = true) == true) },
        )

        val latestMovies = librarySections.filter { !it.isShows }.flatMap { it.items }.ifEmpty { legacyLatestMovies }
        val latestShows = librarySections.filter { it.isShows }.flatMap { it.items }.ifEmpty { legacyLatestShows }

        // Top Picks For You: Combined API suggestions + top community-rated items + library sections + latest
        val rawRecommendationCandidates = (suggestionsRaw + topRatedRaw + librarySections.flatMap { it.items } + latestRaw)
          .filter { isVideoMedia(it) && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank()) }

        val recommendations = resolveShowsAsSeries(server, rawRecommendationCandidates)
          .sortedWith(
            compareByDescending<JellyfinItem> { it.isSeries || it.type == "Series" }
              .thenByDescending { it.childCount ?: 0 }
              .thenByDescending { it.communityRating ?: 0.0 }
          )
          .distinctBy { mediaDeduplicationKey(it) }
          .take(36)

        val latestMusic = (musicRaw + latestRaw.filter { it.isAudio || it.type == "MusicAlbum" || it.type == "Audio" })
          .distinctBy { it.id }
          .take(16)

        // Hero Items: 15 unplayed random Movies & TV Series
        val fetchedHero =
          heroResult.getOrNull()?.items?.filter {
            !it.isPlayed && isVideoMedia(it) && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank())
          } ?: emptyList()

        val finalHero =
          if (fetchedHero.isNotEmpty()) {
            resolveShowsAsSeries(server, fetchedHero)
              .distinctBy { mediaDeduplicationKey(it) }
              .take(15)
          } else {
            resolveShowsAsSeries(
              server,
              (librarySections.flatMap { it.items } + recommendations)
                .filter { !it.isPlayed && isVideoMedia(it) && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank()) },
            )
              .distinctBy { mediaDeduplicationKey(it) }
              .take(15)
          }

        _uiState.update {
          it.copy(
            libraries = libs,
            librarySections = librarySections,
            resumeItems = resume,
            latestMovies = latestMovies,
            latestShows = latestShows,
            latestMusic = latestMusic,
            recommendations = recommendations,
            heroItems = finalHero,
            isLoading = false,
            error = if (libs.isEmpty() && latestRaw.isEmpty() && resumeRaw.isEmpty() && librarySections.isEmpty()) libsResult.exceptionOrNull()?.message else null,
          )
        }
      }
  }

  private fun mediaDeduplicationKey(item: JellyfinItem): String {
    if (item.isSeries || item.type == "Series" || item.type == "Episode" || !item.seriesName.isNullOrBlank()) {
      val showName = (item.seriesName ?: item.name).trim().lowercase()
      return "series_$showName"
    }
    val movieName = item.name.trim().lowercase()
    return "movie_$movieName"
  }

  private suspend fun resolveShowsAsSeries(
    server: JellyfinServer,
    items: List<JellyfinItem>,
  ): List<JellyfinItem> {
    if (items.isEmpty()) return emptyList()

    val episodeItems = items.filter { it.type == "Episode" && !it.seriesId.isNullOrBlank() }
    val seriesMap = if (episodeItems.isNotEmpty()) {
      val distinctSeriesIds = episodeItems.mapNotNull { it.seriesId }.distinct()
      distinctSeriesIds.map { seriesId ->
        viewModelScope.async(Dispatchers.IO) {
          seriesId to jellyfinRepository.getItem(server, seriesId).getOrNull()
        }
      }.awaitAll().toMap()
    } else {
      emptyMap()
    }

    return items.map { item ->
      if (item.type == "Episode") {
        val series = item.seriesId?.let { seriesMap[it] }
        series ?: item.copy(
          id = item.seriesId ?: item.id,
          name = item.seriesName ?: item.name,
          type = "Series",
          primaryImageTag = item.seriesPrimaryImageTag ?: item.primaryImageTag,
        )
      } else {
        item
      }
    }.distinctBy { mediaDeduplicationKey(it) }
  }

  private fun isSeriesLibrary(lib: JellyfinItem): Boolean {
    val col = lib.collectionType?.lowercase()?.trim() ?: ""
    val type = lib.type.lowercase().trim()
    val name = lib.name.lowercase().trim()
    return col == "tvshows" || col == "series" || type == "series" ||
      name.contains("show") || name.contains("series") || name.contains("tv") || name.contains("anime") || name.contains("drama")
  }

  private fun isMusicLibrary(item: JellyfinItem): Boolean {
    val col = item.collectionType?.lowercase()?.trim() ?: ""
    val type = item.type.lowercase().trim()
    val name = item.name.lowercase().trim()
    return col == "music" || type == "music" || type == "audio" || (name.contains("music") && !name.contains("video"))
  }

  private fun sortJellyfinLibraries(libs: List<JellyfinItem>): List<JellyfinItem> {
    fun libraryRank(item: JellyfinItem): Int {
      val name = item.name.lowercase().trim()
      val colType = item.collectionType?.lowercase()?.trim() ?: ""
      val type = item.type.lowercase().trim()

      val isAnime = name.contains("anime") || colType.contains("anime")
      val isMovie = !isAnime && (colType == "movies" || type == "movie" || name.contains("movie") || name.contains("film"))
      val isMusic = colType == "music" || type == "audio" || type == "music" || name.contains("music") || name.contains("song") || name.contains("audio")
      val isSeries = !isAnime && (colType == "tvshows" || type == "series" || name.contains("show") || name.contains("series") || name.contains("tv"))

      return when {
        isMovie -> 0
        isMusic -> 1
        isSeries -> 2
        isAnime -> 3
        else -> 4
      }
    }

    return libs.sortedWith(compareBy({ libraryRank(it) }, { it.name.lowercase() }))
  }

  fun setSort(
    sortBy: JellyfinSortBy,
    sortOrder: JellyfinSortOrder,
  ) {
    _uiState.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary ?: return
    loadLibraryItems(active, library, resetPagination = true)
  }

  fun toggleUnplayedOnly() {
    val newFilter = !_uiState.value.isUnplayedOnly
    _uiState.update { it.copy(isUnplayedOnly = newFilter) }
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary ?: return
    loadLibraryItems(active, library, resetPagination = true)
  }

  fun navigateToItem(item: JellyfinItem) {
    val active = _uiState.value.activeServer ?: return
    val isMusic = item.collectionType?.equals("music", ignoreCase = true) == true ||
      item.type == "MusicAlbum" || item.type == "MusicArtist"
    val types =
      when {
        item.type == "MusicAlbum" || item.type == "MusicArtist" -> "Audio"
        item.type == "CollectionFolder" -> libraryItemTypes(item.collectionType)
        item.isFolder && !item.isSeries && !item.isSeason -> libraryItemTypes(item.collectionType)
        else -> null
      }
    if (types == null) {
      openDetail(item)
    } else {
      openLibrary(
        active,
        JellyfinLibraryView(
          id = item.id,
          title = item.name,
          itemTypes = types,
          collectionType = item.collectionType,
          isMusic = isMusic,
        ),
      )
    }
  }

  private fun openLibrary(
    server: JellyfinServer,
    library: JellyfinLibraryView,
  ) {
    _uiState.update {
      it.copy(
        openLibrary = library,
        selectedLibraryId = library.id,
        selectedGenreFilter = null,
        availableGenres = emptyList(),
        searchQuery = "",
        musicActiveTab = JellyfinMusicTab.HOME,
      )
    }
    if (library.isMusic) {
      loadMusicHomeDashboard(server, library)
    } else {
      loadLibraryItems(server, library, resetPagination = true)
      viewModelScope.launch {
        jellyfinRepository.getGenres(server, library.id).onSuccess { genres ->
          if (_uiState.value.openLibrary?.id == library.id) {
            _uiState.update { it.copy(availableGenres = genres) }
          }
        }
      }
    }
  }

  fun ensureMusicLibraryOpened() {
    val active = _uiState.value.activeServer ?: return
    if (_uiState.value.openLibrary?.isMusic == true) return
    val musicLib = _uiState.value.libraries.firstOrNull { isMusicLibrary(it) }
    if (musicLib != null) {
      navigateToItem(musicLib)
    }
  }

  fun setGenreFilter(genre: String?) {
    if (_uiState.value.selectedGenreFilter == genre) return
    _uiState.update { it.copy(selectedGenreFilter = genre) }
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary ?: return
    loadLibraryItems(active, library, resetPagination = true)
  }

  fun navigateBack(): Boolean {
    val library = _uiState.value.openLibrary
    if (library != null) {
      if (library.isMusic && _uiState.value.musicActiveTab != JellyfinMusicTab.HOME) {
        setMusicTab(JellyfinMusicTab.HOME)
        return true
      }
      _uiState.update {
        it.copy(
          openLibrary = null,
          currentItems = emptyList(),
          selectedLibraryId = null,
          selectedGenreFilter = null,
          availableGenres = emptyList(),
          searchQuery = "",
          musicActiveTab = JellyfinMusicTab.HOME,
        )
      }
      val active = _uiState.value.activeServer ?: return true
      loadHomeDashboard(active)
      return true
    }
    return false
  }

  fun loadMusicHomeDashboard(
    server: JellyfinServer,
    library: JellyfinLibraryView,
  ) {
    loadDashboardJob?.cancel()
    loadItemsJob?.cancel()
    musicLoadJob?.cancel()
    loadedMusicHomeLibraryId = null
    musicLoadJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoading = false, isMusicLoading = true) }

      val jumpBackDeferred = async {
        val playedTracks = jellyfinRepository.getItems(
          server = server,
          parentId = library.id,
          includeItemTypes = "Audio",
          sortBy = JellyfinSortBy.DATE_PLAYED,
          sortOrder = JellyfinSortOrder.DESCENDING,
          limit = 30,
        ).getOrNull()?.items.orEmpty()

        val addedTracks = jellyfinRepository.getItems(
          server = server,
          parentId = library.id,
          includeItemTypes = "Audio",
          sortBy = JellyfinSortBy.DATE_ADDED,
          sortOrder = JellyfinSortOrder.DESCENDING,
          limit = 30,
        ).getOrNull()?.items.orEmpty()

        val randomTracks = jellyfinRepository.getItems(
          server = server,
          parentId = library.id,
          includeItemTypes = "Audio",
          sortBy = JellyfinSortBy.RANDOM,
          limit = 30,
        ).getOrNull()?.items.orEmpty()

        val uniquePlayed = playedTracks.distinctBy { it.id }
        val playedIds = uniquePlayed.map { it.id }.toSet()
        val uniqueOthers = (addedTracks + randomTracks)
          .distinctBy { it.id }
          .filter { it.id !in playedIds }

        (uniquePlayed + uniqueOthers).take(24)
      }

      val recentlyPlayedAlbumsDeferred = async {
        jellyfinRepository.getItems(
          server = server,
          parentId = library.id,
          includeItemTypes = "MusicAlbum",
          sortBy = JellyfinSortBy.DATE_ADDED,
          sortOrder = JellyfinSortOrder.DESCENDING,
          limit = 15,
        ).getOrNull()?.items.orEmpty()
      }

      val artistsToExploreDeferred = async {
        val endpointArtists = jellyfinRepository.getArtists(
          server = server,
          parentId = library.id,
          limit = 30,
        ).getOrNull()?.items.orEmpty()

        val itemArtists = jellyfinRepository.getItems(
          server = server,
          parentId = library.id,
          includeItemTypes = "MusicArtist,Artist,AlbumArtist",
          sortBy = JellyfinSortBy.RANDOM,
          limit = 30,
        ).getOrNull()?.items.orEmpty()

        (endpointArtists + itemArtists)
          .filter { it.name.isNotBlank() }
          .distinctBy { if (it.id.isNotBlank()) it.id else it.name.lowercase().trim() }
          .shuffled()
          .take(15)
      }

      val favoritesDeferred = async {
        jellyfinRepository.getItems(
          server = server,
          parentId = null,
          includeItemTypes = "Audio",
          isFavorite = true,
          sortBy = JellyfinSortBy.NAME,
          limit = 50,
        ).getOrNull()?.items.orEmpty()
      }

      val playlistsDeferred = async {
        val serverPlaylists = jellyfinRepository.getItems(
          server = server,
          parentId = null,
          includeItemTypes = "Playlist",
          sortBy = JellyfinSortBy.NAME,
          limit = 500,
        ).getOrNull()?.items.orEmpty()

        val favoritesVirtualPlaylist = JellyfinItem(
          id = "virtual_favorites_playlist",
          name = "Favorites",
          type = "Playlist",
          overview = null,
          isFolder = true,
          isFavorite = true,
          primaryImageTag = null,
          albumPrimaryImageTag = null,
        )

        listOf(favoritesVirtualPlaylist) + serverPlaylists.filter { !it.name.equals("Favorites", ignoreCase = true) }
      }

      val jumpBackIn = jumpBackDeferred.await()
      val recentAlbums = recentlyPlayedAlbumsDeferred.await()
      val artistsToExplore = artistsToExploreDeferred.await()
      val favorites = favoritesDeferred.await()
      val playlists = playlistsDeferred.await()

      loadedMusicHomeLibraryId = library.id
      _uiState.update {
        it.copy(
          musicFavorites = favorites,
          musicPlaylists = playlists,
          musicJumpBackIn = jumpBackIn,
          musicRecentlyPlayedAlbums = recentAlbums,
          musicArtistsToExplore = artistsToExplore,
          isMusicLoading = false,
        )
      }
    }
  }

  fun setMusicTab(tab: JellyfinMusicTab) {
    if (_uiState.value.musicActiveTab == tab) return
    _uiState.update { it.copy(musicActiveTab = tab) }
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary ?: return

    if (tab == JellyfinMusicTab.HOME) {
      if (loadedMusicHomeLibraryId != library.id) loadMusicHomeDashboard(active, library)
    } else {
      loadMusicTabItems(active, library, tab)
    }
  }

  private fun loadMusicTabItems(
    server: JellyfinServer,
    library: JellyfinLibraryView,
    tab: JellyfinMusicTab,
  ) {
    musicLoadJob?.cancel()
    musicLoadJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, isMusicLoading = false) }
      when (tab) {
        JellyfinMusicTab.PLAYLISTS -> {
          val serverPlaylists = jellyfinRepository.getItems(
            server = server,
            parentId = null,
            includeItemTypes = "Playlist",
            sortBy = JellyfinSortBy.NAME,
            limit = 500,
          ).getOrNull()?.items.orEmpty()

          val favoriteTracks = jellyfinRepository.getItems(
            server = server,
            parentId = null,
            includeItemTypes = "Audio",
            isFavorite = true,
            sortBy = JellyfinSortBy.NAME,
            limit = 1,
          ).getOrNull()?.items.orEmpty()

          val favoritesVirtualPlaylist = JellyfinItem(
            id = "virtual_favorites_playlist",
            name = "Favorites",
            type = "Playlist",
            overview = null,
            isFolder = true,
            isFavorite = true,
            primaryImageTag = null,
            albumPrimaryImageTag = null,
          )

          val combinedPlaylists = listOf(favoritesVirtualPlaylist) + serverPlaylists.filter { !it.name.equals("Favorites", ignoreCase = true) }
          _uiState.update { it.copy(musicPlaylists = combinedPlaylists, isLoading = false) }
        }
        JellyfinMusicTab.ARTISTS -> {
          val libraryItemsArtists = jellyfinRepository.getItems(
            server = server,
            parentId = library.id,
            includeItemTypes = "MusicArtist,Artist,AlbumArtist",
            sortBy = JellyfinSortBy.NAME,
            limit = 500,
          ).getOrNull()?.items.orEmpty()

          val rootItemsArtists = jellyfinRepository.getItems(
            server = server,
            parentId = null,
            includeItemTypes = "MusicArtist,Artist,AlbumArtist",
            sortBy = JellyfinSortBy.NAME,
            limit = 500,
          ).getOrNull()?.items.orEmpty()

          val libraryArtistsEndpoint = jellyfinRepository.getArtists(
            server = server,
            parentId = library.id,
            limit = 500,
          ).getOrNull()?.items.orEmpty()

          val rootArtistsEndpoint = jellyfinRepository.getArtists(
            server = server,
            parentId = null,
            limit = 500,
          ).getOrNull()?.items.orEmpty()

          val libraryAlbumArtistsEndpoint = jellyfinRepository.getArtists(
            server = server,
            parentId = library.id,
            limit = 500,
            albumArtistsOnly = true,
          ).getOrNull()?.items.orEmpty()

          val rootAlbumArtistsEndpoint = jellyfinRepository.getArtists(
            server = server,
            parentId = null,
            limit = 500,
            albumArtistsOnly = true,
          ).getOrNull()?.items.orEmpty()

          val allFetched = libraryItemsArtists + rootItemsArtists + libraryArtistsEndpoint + rootArtistsEndpoint + libraryAlbumArtistsEndpoint + rootAlbumArtistsEndpoint

          val combinedArtists = allFetched
            .filter { it.name.isNotBlank() }
            .distinctBy { if (it.id.isNotBlank()) it.id else it.name.lowercase().trim() }
            .sortedBy { it.name.lowercase() }

          _uiState.update { it.copy(musicArtists = combinedArtists, isLoading = false) }
        }
        JellyfinMusicTab.ALBUMS -> {
          val result = jellyfinRepository.getItems(
            server = server,
            parentId = library.id,
            includeItemTypes = "MusicAlbum",
            sortBy = JellyfinSortBy.NAME,
            limit = 500,
          ).getOrNull()?.items.orEmpty()
          _uiState.update { it.copy(musicAlbums = result, isLoading = false) }
        }
        JellyfinMusicTab.TRACKS -> {
          val result = jellyfinRepository.getItems(
            server = server,
            parentId = library.id,
            includeItemTypes = "Audio",
            sortBy = JellyfinSortBy.NAME,
            limit = 500,
          ).getOrNull()?.items.orEmpty()
          _uiState.update { it.copy(musicTracks = result, isLoading = false) }
        }
        JellyfinMusicTab.HOME -> {
          _uiState.update { it.copy(isLoading = false) }
        }
      }
    }
  }

  fun navigateToRoot() {
    navigateBack()
  }

  /** Concrete item types to request recursively so a library lists media, not folders. */
  private fun libraryItemTypes(collectionType: String?): String =
    when (collectionType?.lowercase()) {
      "movies" -> "Movie"
      "tvshows" -> "Series"
      "music" -> "MusicAlbum"
      "musicvideos" -> "MusicVideo"
      "boxsets" -> "BoxSet"
      "books" -> "Book"
      "homevideos", "photos" -> "Video"
      else -> "Movie,Series"
    }

  private fun loadLibraryItems(
    server: JellyfinServer,
    library: JellyfinLibraryView,
    resetPagination: Boolean = true,
  ) {
    val startIndex = if (resetPagination) 0 else _uiState.value.startIndex
    val currentList = if (resetPagination) emptyList() else _uiState.value.currentItems

    if (resetPagination) {
      loadItemsJob?.cancel()
      musicLoadJob?.cancel()
    }

    loadItemsJob =
      viewModelScope.launch {
        if (resetPagination) {
          _uiState.update {
            it.copy(
              isLoading = true,
              currentItems = emptyList(),
              startIndex = 0,
              hasMore = false,
              error = null,
            )
          }
        } else {
          _uiState.update { it.copy(isLoadingMore = true) }
        }

        val currentState = _uiState.value

        jellyfinRepository
          .getItems(
            server = server,
            parentId = library.id,
            includeItemTypes = library.itemTypes,
            sortBy = currentState.sortBy,
            sortOrder = currentState.sortOrder,
            isPlayed = if (currentState.isUnplayedOnly) false else null,
            genres = currentState.selectedGenreFilter,
            startIndex = startIndex,
            limit = 100,
          ).onSuccess { queryResult ->
            val combined = (currentList + queryResult.items).distinctBy { it.id }
            _uiState.update {
              it.copy(
                currentItems = combined,
                totalRecordCount = queryResult.totalRecordCount,
                startIndex = combined.size,
                hasMore = combined.size < queryResult.totalRecordCount,
                isLoading = false,
                isLoadingMore = false,
                error = null,
              )
            }
          }.onFailure { err ->
            _uiState.update {
              it.copy(
                isLoading = false,
                isLoadingMore = false,
                error = err.message ?: "Failed to load items",
              )
            }
          }
      }
  }

  fun loadMoreItems() {
    val state = _uiState.value
    if (state.isLoading || state.isLoadingMore || !state.hasMore) return
    val active = state.activeServer ?: return
    val library = state.openLibrary ?: return
    loadLibraryItems(active, library, resetPagination = false)
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    performSearch(query, debounceMs = 300L)
  }

  fun setSearchCategory(category: JellyfinSearchCategory) {
    _uiState.update { it.copy(searchCategory = category) }
    if (_uiState.value.searchQuery.isNotBlank()) {
      performSearch(_uiState.value.searchQuery, debounceMs = 0L)
    }
  }

  fun performSearch(
    query: String,
    debounceMs: Long = 0L,
  ) {
    val active = _uiState.value.activeServer ?: return
    searchJob?.cancel()
    if (query.isBlank()) {
      refresh()
      return
    }
    searchJob =
      viewModelScope.launch {
        if (debounceMs > 0) {
          delay(debounceMs)
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        val library = _uiState.value.openLibrary

        val includeTypes =
          when (_uiState.value.searchCategory) {
            JellyfinSearchCategory.ALL -> null
            JellyfinSearchCategory.MOVIES -> "Movie"
            JellyfinSearchCategory.SHOWS -> "Series"
            JellyfinSearchCategory.EPISODES -> "Episode"
          }

        val result =
          jellyfinRepository.getItems(
            server = active,
            parentId = library?.id,
            searchTerm = query,
            includeItemTypes = includeTypes,
            sortBy = _uiState.value.sortBy,
            sortOrder = _uiState.value.sortOrder,
            startIndex = 0,
            limit = 100,
          )
        result
          .onSuccess { queryResult ->
            _uiState.update {
              it.copy(
                currentItems = queryResult.items.distinctBy { item -> item.id },
                totalRecordCount = queryResult.totalRecordCount,
                startIndex = queryResult.items.size,
                hasMore = queryResult.items.size < queryResult.totalRecordCount,
                isLoading = false,
                error = null,
              )
            }
          }.onFailure { err ->
            _uiState.update { it.copy(isLoading = false, error = err.message) }
          }
      }
  }

  // ============================================================================
  // Media Details & Series Season/Episode Browsing (Material 3 Expressive)
  // ============================================================================

  fun deleteItem(itemId: String, onSuccess: (() -> Unit)? = null) {
    val active = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      val res = jellyfinRepository.deleteItem(active, itemId)
      res.fold(
        onSuccess = {
          _uiState.update { state ->
            state.copy(
              currentItems = state.currentItems.filter { it.id != itemId },
              detailItem = if (state.detailItem?.id == itemId) null else state.detailItem,
            )
          }
          onSuccess?.invoke()
          refresh()
        },
        onFailure = { err ->
          Log.e("JellyfinViewModel", "Failed to delete item $itemId", err)
        },
      )
    }
  }

  fun deleteItems(itemIds: List<String>, onSuccess: (() -> Unit)? = null) {
    val active = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      for (id in itemIds) {
        jellyfinRepository.deleteItem(active, id)
      }
      _uiState.update { state ->
        state.copy(
          currentItems = state.currentItems.filter { it.id !in itemIds },
        )
      }
      onSuccess?.invoke()
      refresh()
    }
  }

  fun openDetailById(itemId: String) {
    val active = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      val res = jellyfinRepository.getItem(active, itemId)
      res.onSuccess { item ->
        openDetail(item)
      }
    }
  }

  fun openDetail(item: JellyfinItem) {
    val active = _uiState.value.activeServer ?: return
    detailJob?.cancel()
    seasonEpisodesJob?.cancel()

    _uiState.update {
      it.copy(
        detailItem = item,
        detailSeasons = emptyList(),
        selectedDetailSeasonId = null,
        detailEpisodes = emptyList(),
        detailSimilarItems = emptyList(),
        isDetailLoading = true,
      )
    }

    detailJob =
      viewModelScope.launch {
        val freshDeferred = async { jellyfinRepository.getItem(active, item.id) }
        val similarDeferred = async { jellyfinRepository.getSimilarItems(active, item.id, limit = 12) }

        val freshResult = freshDeferred.await()
        val similarResult = similarDeferred.await()

        val fullItem = freshResult.getOrNull() ?: item
        val similar = similarResult.getOrDefault(emptyList())

        _uiState.update {
          it.copy(
            detailItem = fullItem,
            detailSimilarItems = similar,
            isDetailLoading = false,
          )
        }

        if (fullItem.isSeries) {
          val seasonsResult = jellyfinRepository.getSeasons(active, fullItem.id)
          val rawSeasons = seasonsResult.getOrDefault(emptyList())
          val seasons = rawSeasons.sortedWith(
            compareBy<JellyfinItem> { it.indexNumber ?: Int.MAX_VALUE }
              .thenBy { it.name }
          )
          val initialSeason = seasons.firstOrNull()

          _uiState.update {
            it.copy(
              detailSeasons = seasons,
              selectedDetailSeasonId = initialSeason?.id,
            )
          }

          if (initialSeason != null) {
            selectDetailSeason(initialSeason.id)
          }
        } else if (fullItem.type == "MusicArtist" || fullItem.type == "Artist" || fullItem.type == "AlbumArtist") {
          val albumsDeferred = async {
            val byParent = jellyfinRepository.getItems(
              server = active,
              parentId = fullItem.id,
              includeItemTypes = "MusicAlbum",
              sortBy = JellyfinSortBy.NAME,
              limit = 100,
            ).getOrNull()?.items.orEmpty()

            val byArtistId = jellyfinRepository.getItems(
              server = active,
              artistIds = fullItem.id,
              includeItemTypes = "MusicAlbum",
              sortBy = JellyfinSortBy.NAME,
              limit = 100,
            ).getOrNull()?.items.orEmpty()

            val allAlbums = jellyfinRepository.getItems(
              server = active,
              includeItemTypes = "MusicAlbum",
              sortBy = JellyfinSortBy.NAME,
              limit = 300,
            ).getOrNull()?.items.orEmpty()

            val byName = allAlbums.filter { a ->
              a.seriesName?.equals(fullItem.name, ignoreCase = true) == true
            }

            (byParent + byArtistId + byName)
              .filter { it.name.isNotBlank() }
              .distinctBy { it.id }
          }
          val tracksDeferred = async {
            val byParentTracks = jellyfinRepository.getItems(
              server = active,
              parentId = fullItem.id,
              includeItemTypes = "Audio",
              sortBy = JellyfinSortBy.NAME,
              limit = 200,
            ).getOrNull()?.items.orEmpty()

            val byArtistIdTracks = jellyfinRepository.getItems(
              server = active,
              artistIds = fullItem.id,
              includeItemTypes = "Audio",
              sortBy = JellyfinSortBy.NAME,
              limit = 200,
            ).getOrNull()?.items.orEmpty()

            (byParentTracks + byArtistIdTracks)
              .filter { it.name.isNotBlank() }
              .distinctBy { it.id }
          }

          val albums = albumsDeferred.await()
          val tracks = tracksDeferred.await()

          _uiState.update {
            it.copy(
              detailSeasons = albums,
              detailEpisodes = tracks,
            )
          }
        } else if (fullItem.type == "MusicAlbum" || fullItem.type == "Playlist") {
          val tracks =
            if (fullItem.id == "virtual_favorites_playlist" || fullItem.id == "favorites") {
              jellyfinRepository.getItems(
                server = active,
                parentId = null,
                includeItemTypes = "Audio",
                isFavorite = true,
                sortBy = JellyfinSortBy.NAME,
                limit = 500,
              ).getOrNull()?.items.orEmpty()
            } else {
              jellyfinRepository.getItems(
                server = active,
                parentId = fullItem.id,
                includeItemTypes = if (fullItem.type == "MusicAlbum") "Audio" else null,
                limit = 200,
              ).getOrNull()?.items.orEmpty().ifEmpty {
                jellyfinRepository.getItems(
                  server = active,
                  parentId = fullItem.id,
                  limit = 200,
                ).getOrNull()?.items.orEmpty()
              }
            }

          _uiState.update {
            it.copy(
              detailEpisodes = tracks,
            )
          }
        }
      }
  }

  fun selectDetailSeason(seasonId: String) {
    val active = _uiState.value.activeServer ?: return
    val series = _uiState.value.detailItem ?: return
    seasonEpisodesJob?.cancel()

    _uiState.update {
      it.copy(
        selectedDetailSeasonId = seasonId,
        isDetailEpisodesLoading = true,
      )
    }

    seasonEpisodesJob =
      viewModelScope.launch {
        val result = jellyfinRepository.getEpisodes(active, series.id, seasonId)
        val episodes = result.getOrDefault(emptyList())
        _uiState.update {
          it.copy(
            detailEpisodes = episodes,
            isDetailEpisodesLoading = false,
          )
        }
      }
  }

  fun closeDetail() {
    detailJob?.cancel()
    seasonEpisodesJob?.cancel()
    _uiState.update {
      it.copy(
        detailItem = null,
        detailSeasons = emptyList(),
        selectedDetailSeasonId = null,
        detailEpisodes = emptyList(),
        detailSimilarItems = emptyList(),
        isDetailLoading = false,
        isDetailEpisodesLoading = false,
      )
    }
  }

  // ── Downloads ─────────────────────────────────────────────────────────────

  /** Engine download list, exposed for per-item badges and indicators. */
  val downloads get() = downloadManager.downloads

  fun downloadItem(item: JellyfinItem) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val queued = enqueueJellyfinDownload(server, item)
      showDownloadToast(
        if (queued) {
          getApplication<Application>().getString(app.gyrolet.mpvrx.R.string.downloads_started)
        } else {
          getApplication<Application>().getString(app.gyrolet.mpvrx.R.string.downloads_already_downloaded)
        },
      )
    }
  }

  /** Downloads every episode of the currently selected detail season. */
  fun downloadSelectedSeason() {
    val server = _uiState.value.activeServer ?: return
    val episodes = _uiState.value.detailEpisodes.filter { it.type == "Episode" }
    if (episodes.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      var queued = 0
      episodes.forEach { episode ->
        if (enqueueJellyfinDownload(server, episode)) queued++
      }
      showDownloadToast(
        getApplication<Application>().getString(app.gyrolet.mpvrx.R.string.downloads_episodes_queued, queued),
      )
    }
  }

  /** Downloads every episode of every season of the detail series. */
  fun downloadWholeSeries() {
    val server = _uiState.value.activeServer ?: return
    val series = _uiState.value.detailItem?.takeIf { it.isSeries } ?: return
    val seasons = _uiState.value.detailSeasons
    if (seasons.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      var queued = 0
      seasons.forEach { season ->
        val episodes =
          jellyfinRepository
            .getEpisodes(server, series.id, season.id)
            .getOrDefault(emptyList())
            .filter { it.type == "Episode" }
        episodes.forEach { episode ->
          if (enqueueJellyfinDownload(server, episode)) queued++
        }
      }
      showDownloadToast(
        getApplication<Application>().getString(app.gyrolet.mpvrx.R.string.downloads_episodes_queued, queued),
      )
    }
  }

  /**
   * Resolves the direct stream URL plus external subtitle tracks and queues them.
   * Subtitles are saved as sidecars with the video's basename so local playback
   * (and the player's sibling-subtitle autoload) picks them up automatically.
   */
  private suspend fun enqueueJellyfinDownload(
    server: JellyfinServer,
    item: JellyfinItem,
  ): Boolean {
    if (downloadManager.entryForJellyfinItem(item.id) != null) return false

    val streamUrl = jellyfinRepository.getStreamUrl(server, item)
    if (streamUrl.isBlank()) return false
    val subtitleTracks =
      jellyfinRepository
        .getSubtitleTracks(server = server, itemId = item.id)
        .getOrDefault(emptyList())

    val extension =
      item.container
        ?.substringBefore(',')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() && it.length <= 5 }
        ?: "mkv"

    val locations = downloadManager.locations
    val isEpisode = item.type == "Episode"
    val directory: java.io.File
    val fileName: String
    val displayTitle: String
    if (isEpisode) {
      val seriesName = item.seriesName ?: item.name
      val episodeCode = "S%02dE%02d".format(item.parentIndexNumber ?: 1, item.indexNumber ?: 0)
      directory = locations.jellyfinSeasonDir(seriesName, item.parentIndexNumber)
      fileName = "${DownloadLocations.sanitizeName("$episodeCode - ${item.name}")}.$extension"
      displayTitle = "$seriesName $episodeCode - ${item.name}"
    } else {
      val titleWithYear = item.name + (item.productionYear?.let { " ($it)" } ?: "")
      directory = locations.jellyfinMovieDir(titleWithYear)
      fileName = "${DownloadLocations.sanitizeName(titleWithYear)}.$extension"
      displayTitle = titleWithYear
    }

    val meta =
      DownloadMetadata(
        source = DownloadSources.JELLYFIN,
        title = displayTitle,
        posterUrl = jellyfinRepository.getImageUrl(server, item),
        sourceUrl = streamUrl,
        jellyfinServerId = server.id.toString(),
        jellyfinItemId = item.id,
        jellyfinSeriesName = item.seriesName,
        seasonNumber = item.parentIndexNumber,
        episodeNumber = item.indexNumber,
        isAudio = item.isAudio,
      )

    downloadManager.enqueueVideo(
      url = streamUrl,
      directory = directory,
      fileName = fileName,
      meta = meta,
      // Belt and braces for reverse proxies that strip query-string auth.
      headers = mapOf("X-Emby-Token" to server.accessToken),
    )
    downloadManager.enqueueSubtitleSidecars(directory = directory, videoFileName = fileName, tracks = subtitleTracks)
    return true
  }

  /** Plays a completed local copy with its sidecar subtitles; true when handled. */
  private fun playLocalCopyIfAvailable(
    context: Context,
    item: JellyfinItem,
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
  ): Boolean {
    val download = downloadManager.playableForJellyfinItem(item.id) ?: return false
    MediaUtils.playFile(
      source = download.file.absolutePath,
      context = context,
      launchSource = "jellyfin_download",
      title = title,
      subtitles = downloadManager.sidecarSubtitles(download).map { Uri.fromFile(it) },
      posterUrl = posterUrl,
      backdropUrl = backdropUrl,
      isAudio = item.isAudio,
    )
    return true
  }

  private suspend fun showDownloadToast(message: String) {
    withContext(Dispatchers.Main) {
      android.widget.Toast
        .makeText(getApplication(), message, android.widget.Toast.LENGTH_SHORT)
        .show()
    }
  }

  fun toggleItemFavorite(item: JellyfinItem) {
    val server = _uiState.value.activeServer ?: return
    val newFavoriteState = !item.isFavorite

    // Optimistic UI update
    fun updateItemInList(list: List<JellyfinItem>): List<JellyfinItem> =
      list.map { if (it.id == item.id) it.copy(isFavorite = newFavoriteState) else it }

    _uiState.update { state ->
      state.copy(
        heroItems = updateItemInList(state.heroItems),
        resumeItems = updateItemInList(state.resumeItems),
        latestMovies = updateItemInList(state.latestMovies),
        latestShows = updateItemInList(state.latestShows),
        librarySections = state.librarySections.map { it.copy(items = updateItemInList(it.items)) },
        recommendations = updateItemInList(state.recommendations),
        currentItems = updateItemInList(state.currentItems),
        detailItem = if (state.detailItem?.id == item.id) state.detailItem.copy(isFavorite = newFavoriteState) else state.detailItem,
      )
    }

    viewModelScope.launch {
      jellyfinRepository.toggleFavorite(server, item, newFavoriteState)
    }
  }

  // ============================================================================
  // Server Management & Playback
  // ============================================================================

  fun addServer(
    serverUrl: String,
    serverName: String,
    authMode: JellyfinAuthMode,
    username: String = "",
    password: String = "",
    token: String = "",
    existingServerId: Long? = null,
    onSuccess: () -> Unit,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isAuthenticating = true, authError = null) }

      try {
        val serverToSave =
          if (authMode == JellyfinAuthMode.CREDENTIALS) {
            val authResult =
              jellyfinRepository.authenticate(serverUrl, username, password).getOrThrow()

            if (subtitlesPreferences.preferredLanguages.get().isBlank() && !authResult.subtitleLanguage.isNullOrBlank()) {
              subtitlesPreferences.preferredLanguages.set(authResult.subtitleLanguage)
            }
            if (audioPreferences.preferredLanguages.get().isBlank() && !authResult.audioLanguage.isNullOrBlank()) {
              audioPreferences.preferredLanguages.set(authResult.audioLanguage)
            }

            val effectiveUrl = authResult.normalizedServerUrl.ifBlank { JellyfinClient.normalizeUrl(serverUrl) }

            JellyfinServer(
              id = existingServerId ?: 0,
              name = serverName.ifBlank { "Jellyfin (${authResult.username})" },
              serverUrl = effectiveUrl,
              userId = authResult.userId,
              username = authResult.username,
              accessToken = authResult.accessToken,
              lastConnected = System.currentTimeMillis(),
            )
          } else {
            val user = jellyfinRepository.validateToken(serverUrl, token).getOrThrow()

            if (subtitlesPreferences.preferredLanguages.get().isBlank() && !user.subtitleLanguage.isNullOrBlank()) {
              subtitlesPreferences.preferredLanguages.set(user.subtitleLanguage)
            }
            if (audioPreferences.preferredLanguages.get().isBlank() && !user.audioLanguage.isNullOrBlank()) {
              audioPreferences.preferredLanguages.set(user.audioLanguage)
            }

            val effectiveUrl = user.normalizedServerUrl.ifBlank { JellyfinClient.normalizeUrl(serverUrl) }

            JellyfinServer(
              id = existingServerId ?: 0,
              name = serverName.ifBlank { "Jellyfin (${user.name})" },
              serverUrl = effectiveUrl,
              userId = user.id,
              username = user.name,
              accessToken = token.trim(),
              lastConnected = System.currentTimeMillis(),
            )
          }

        val savedServer =
          if (existingServerId != null && existingServerId > 0) {
            jellyfinRepository.updateServer(serverToSave)
            serverToSave
          } else {
            val id = jellyfinRepository.saveServer(serverToSave)
            serverToSave.copy(id = id)
          }
        _uiState.update {
          it.copy(
            isAuthenticating = false,
            authError = null,
            activeServer = savedServer,
          )
        }
        loadHomeDashboard(savedServer)
        onSuccess()
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            isAuthenticating = false,
            authError = e.localizedMessage ?: "Failed to connect to Jellyfin server",
          )
        }
      }
    }
  }

  fun deleteServer(server: JellyfinServer) {
    viewModelScope.launch {
      jellyfinRepository.deleteServer(server)
      val remaining = jellyfinRepository.allServers.firstOrNull() ?: emptyList()
      val newActive = remaining.firstOrNull { it.id != server.id }
      _uiState.update {
        it.copy(
          activeServer = newActive,
          openLibrary = null,
          currentItems = emptyList(),
          libraries = emptyList(),
          resumeItems = emptyList(),
          heroItems = emptyList(),
          latestMovies = emptyList(),
          latestShows = emptyList(),
          librarySections = emptyList(),
        )
      }
      if (newActive != null) {
        loadHomeDashboard(newActive)
      }
    }
  }

  fun playItem(
    context: Context,
    item: JellyfinItem,
    startFromBeginning: Boolean = false,
  ) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      val targetItem = if (item.type == "MusicAlbum" || item.type == "MusicArtist" || (item.isFolder && item.collectionType == "music")) {
        val tracksResult = jellyfinRepository.getItems(server = server, parentId = item.id, includeItemTypes = "Audio").getOrNull()
        tracksResult?.items?.firstOrNull() ?: item
      } else if (item.isSeries) {
        val unplayedEpisode = jellyfinRepository.getItems(
          server = server,
          parentId = item.id,
          includeItemTypes = "Episode",
          sortBy = JellyfinSortBy.NAME,
          isPlayed = false,
          limit = 1,
        ).getOrNull()?.items?.firstOrNull()

        unplayedEpisode ?: jellyfinRepository.getItems(
          server = server,
          parentId = item.id,
          includeItemTypes = "Episode",
          limit = 1,
        ).getOrNull()?.items?.firstOrNull() ?: item
      } else {
        item
      }

      val isAudio = targetItem.isAudio || targetItem.type == "Audio" || targetItem.type == "Song" || item.type == "MusicAlbum" || item.type == "MusicArtist" || item.collectionType == "music"
      val streamUrl = jellyfinRepository.getStreamUrl(server, targetItem)
      val mediaIdentifier = PlaybackIdentity.forUri(streamUrl)
      val posterUrl = jellyfinRepository.getImageUrl(server, targetItem)
      val backdropUrl = jellyfinRepository.getBackdropUrl(server, targetItem)

      val itemTitle =
        when {
          targetItem.seriesName != null && targetItem.indexNumber != null -> "${targetItem.seriesName} S${targetItem.parentIndexNumber ?: 1}E${targetItem.indexNumber} - ${targetItem.name}"
          else -> targetItem.name
        }

      // Offline-first: a completed download plays locally with its sidecar subtitles.
      if (!isAudio) {
        val localDownload = downloadManager.playableForJellyfinItem(targetItem.id)
        if (localDownload != null) {
          if (startFromBeginning) {
            runCatching {
              playbackStateRepository.deleteByTitle(PlaybackIdentity.forUri(localDownload.file.absolutePath))
              playbackStateRepository.deleteByTitle(
                PlaybackIdentity.forUri(Uri.fromFile(localDownload.file).toString()),
              )
            }
          }
          withContext(Dispatchers.Main) {
            playLocalCopyIfAvailable(
              context = context,
              item = targetItem,
              title = itemTitle,
              posterUrl = posterUrl,
              backdropUrl = backdropUrl,
            )
          }
          return@launch
        }
      }

      if (startFromBeginning) {
        runCatching {
          playbackStateRepository.deleteByTitle(mediaIdentifier)
          playbackStateRepository.deleteByTitle(streamUrl)
        }
      }

      val freshItemDeferred =
        async {
          if (!startFromBeginning) {
            jellyfinRepository.getItem(server, targetItem.id).getOrNull()
          } else {
            null
          }
        }

      val subsDeferred =
        async {
          if (!isAudio) {
            jellyfinRepository
              .getSubtitleTracks(server = server, itemId = targetItem.id)
              .getOrDefault(emptyList())
          } else {
            emptyList()
          }
        }

      val freshItem = freshItemDeferred.await() ?: targetItem
      val effectivePositionTicks =
        if (!startFromBeginning) {
          freshItem.playbackPositionTicks ?: targetItem.playbackPositionTicks ?: 0L
        } else {
          0L
        }
      val positionSeconds = (effectivePositionTicks / JellyfinClient.TICKS_PER_SECOND).toInt()

      if (positionSeconds > 0 && !isAudio) {
        val durationSec = (freshItem.runTimeTicks ?: targetItem.runTimeTicks ?: 0L) / JellyfinClient.TICKS_PER_SECOND
        runCatching {
          val existing = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
          val stateToSave =
            existing?.copy(
              lastPosition = positionSeconds,
              timeRemaining = (durationSec - positionSeconds).toInt().coerceAtLeast(0),
            ) ?: PlaybackStateEntity(
              mediaTitle = mediaIdentifier,
              lastPosition = positionSeconds,
              playbackSpeed = 1.0,
              videoZoom = 0f,
              sid = -1,
              secondarySid = -1,
              subDelay = 0,
              subSpeed = 1.0,
              aid = -1,
              audioDelay = 0,
              timeRemaining = (durationSec - positionSeconds).toInt().coerceAtLeast(0),
            )
          playbackStateRepository.upsert(stateToSave)
        }
      }

      // Fire scrobble start non-blocking in background
      launch {
        jellyfinRepository.reportPlaybackStart(
          serverUrl = server.serverUrl,
          token = server.accessToken,
          itemId = targetItem.id,
          positionTicks = effectivePositionTicks,
        )
      }

      val externalSubs = subsDeferred.await()

      var playlistArtists: List<String> = emptyList()
      val playlistData =
        if (isAudio) {
          val audioSource =
            if (item.id == "virtual_favorites_playlist" || item.id == "favorites") {
              jellyfinRepository.getItems(
                server = server,
                parentId = null,
                includeItemTypes = "Audio",
                isFavorite = true,
                sortBy = JellyfinSortBy.NAME,
                limit = 500,
              ).getOrNull()?.items.orEmpty()
            } else if (item.type == "MusicAlbum" || item.type == "MusicArtist" || (item.isFolder && item.collectionType == "music") || item.type == "Playlist") {
              jellyfinRepository.getItems(server = server, parentId = item.id, includeItemTypes = "Audio").getOrNull()?.items.orEmpty().ifEmpty {
                jellyfinRepository.getItems(server = server, parentId = item.id).getOrNull()?.items.orEmpty()
              }
            } else {
              val potentialSources = listOf(
                _uiState.value.detailEpisodes,
                _uiState.value.musicTracks,
                _uiState.value.musicJumpBackIn,
                _uiState.value.musicFavorites,
                _uiState.value.latestMusic,
                _uiState.value.currentItems,
              )
              val matchedList = potentialSources.firstOrNull { list ->
                list.any { it.id == targetItem.id }
              }?.filter { it.isAudio || it.type == "Audio" || it.type == "Song" }

              if (!matchedList.isNullOrEmpty() && matchedList.any { it.id == targetItem.id }) {
                matchedList
              } else {
                listOf(targetItem)
              }
            }

          if (audioSource.isNotEmpty()) {
            val uris = ArrayList<Uri>(audioSource.size)
            val titles = ArrayList<String>(audioSource.size)
            val artworks = ArrayList<String>(audioSource.size)
            playlistArtists = audioSource.map { track -> track.seriesName ?: targetItem.seriesName ?: "" }
            var targetIdx = 0
            audioSource.forEachIndexed { idx, track ->
              if (track.id == targetItem.id) targetIdx = idx
              val tUrl = jellyfinRepository.getStreamUrl(server, track)
              val aUrl = jellyfinRepository.getImageUrl(server, track)
              uris.add(Uri.parse(tUrl))
              titles.add(track.name)
              artworks.add(aUrl)
            }
            Triple(uris, titles, targetIdx) to artworks
          } else {
            Triple(emptyList<Uri>(), emptyList<String>(), 0) to emptyList<String>()
          }
        } else if (targetItem.type == "Episode") {
          val episodesSource =
            _uiState.value.detailEpisodes.ifEmpty {
              _uiState.value.currentItems.filter { it.type == "Episode" }
            }

          if (episodesSource.size > 1) {
            val uris = ArrayList<Uri>(episodesSource.size)
            val titles = ArrayList<String>(episodesSource.size)
            var targetIdx = 0
            episodesSource.forEachIndexed { index, ep ->
              if (ep.id == targetItem.id) targetIdx = index
              uris.add(Uri.parse(jellyfinRepository.getStreamUrl(server, ep)))
              titles.add(
                when {
                  ep.seriesName != null && ep.indexNumber != null ->
                    "${ep.seriesName} S${ep.parentIndexNumber ?: 1}E${ep.indexNumber} - ${ep.name}"
                  else -> ep.name
                },
              )
            }
            Triple(uris, titles, targetIdx) to emptyList<String>()
          } else {
            Triple(emptyList<Uri>(), emptyList<String>(), 0) to emptyList<String>()
          }
        } else {
          Triple(emptyList<Uri>(), emptyList<String>(), 0) to emptyList<String>()
        }

      val (playlistUris, playlistTitles, playlistIndex) = playlistData.first
      val playlistArtworkUrls = playlistData.second

      val headers =
        mapOf(
          "X-Emby-Token" to server.accessToken,
          "X-Emby-Authorization" to JellyfinClient.authHeader(server.accessToken),
        )

      withContext(Dispatchers.Main) {
        MediaUtils.playFile(
          source = streamUrl,
          context = context,
          launchSource = if (isAudio) "jellyfin_music" else "jellyfin_stream",
          title = itemTitle,
          headers = headers,
          mediaDescription = targetItem.overview,
          posterUrl = posterUrl,
          backdropUrl = backdropUrl,
          subtitleTracks = externalSubs,
          playlist = playlistUris,
          playlistIndex = playlistIndex,
          playlistTitles = playlistTitles,
          playlistArtists = playlistArtists,
          playlistArtworkUrls = playlistArtworkUrls,
          isAudio = isAudio,
        )
      }
    }
  }

  fun playSelected(
    context: Context,
    items: List<JellyfinItem>,
  ) {
    val server = _uiState.value.activeServer ?: return
    val videoPlayable = items.filter { it.isVideo }
    val audioPlayable = items.filter { it.isAudio || it.type == "Audio" || it.type == "Song" }
    val isAudio = videoPlayable.isEmpty() && audioPlayable.isNotEmpty()
    val playable = if (isAudio) audioPlayable else videoPlayable
    if (playable.isEmpty()) return

    val firstItem = playable.first()
    val streamUrl = jellyfinRepository.getStreamUrl(server, firstItem)
    val mediaIdentifier = PlaybackIdentity.forUri(streamUrl)
    val posterUrl = jellyfinRepository.getImageUrl(server, firstItem)
    val backdropUrl = jellyfinRepository.getBackdropUrl(server, firstItem)

    val itemTitle =
      when {
        firstItem.seriesName != null && firstItem.indexNumber != null ->
          "${firstItem.seriesName} S${firstItem.parentIndexNumber ?: 1}E${firstItem.indexNumber} - ${firstItem.name}"
        else -> firstItem.name
      }

    val playlistUris = ArrayList<Uri>(playable.size)
    val playlistTitles = ArrayList<String>(playable.size)
    playable.forEach { item ->
      playlistUris.add(Uri.parse(jellyfinRepository.getStreamUrl(server, item)))
      playlistTitles.add(
        when {
          item.seriesName != null && item.indexNumber != null ->
            "${item.seriesName} S${item.parentIndexNumber ?: 1}E${item.indexNumber} - ${item.name}"
          else -> item.name
        },
      )
    }

    val headers =
      mapOf(
        "X-Emby-Token" to server.accessToken,
        "X-Emby-Authorization" to JellyfinClient.authHeader(server.accessToken),
      )

    viewModelScope.launch(Dispatchers.IO) {
      val freshItem = jellyfinRepository.getItem(server, firstItem.id).getOrNull() ?: firstItem
      val effectivePositionTicks = freshItem.playbackPositionTicks ?: firstItem.playbackPositionTicks ?: 0L
      val positionSeconds = (effectivePositionTicks / JellyfinClient.TICKS_PER_SECOND).toInt()

      if (positionSeconds > 0 && !isAudio) {
        val durationSec = (freshItem.runTimeTicks ?: firstItem.runTimeTicks ?: 0L) / JellyfinClient.TICKS_PER_SECOND
        runCatching {
          val existing = playbackStateRepository.getVideoDataByTitle(mediaIdentifier)
          val stateToSave =
            existing?.copy(
              lastPosition = positionSeconds,
              timeRemaining = (durationSec - positionSeconds).toInt().coerceAtLeast(0),
            ) ?: PlaybackStateEntity(
              mediaTitle = mediaIdentifier,
              lastPosition = positionSeconds,
              playbackSpeed = 1.0,
              videoZoom = 0f,
              sid = -1,
              secondarySid = -1,
              subDelay = 0,
              subSpeed = 1.0,
              aid = -1,
              audioDelay = 0,
              timeRemaining = (durationSec - positionSeconds).toInt().coerceAtLeast(0),
            )
          playbackStateRepository.upsert(stateToSave)
        }
      }

      launch {
        jellyfinRepository.reportPlaybackStart(
          serverUrl = server.serverUrl,
          token = server.accessToken,
          itemId = firstItem.id,
          positionTicks = effectivePositionTicks,
        )
      }

      val playlistArtworks = if (isAudio) playable.map { jellyfinRepository.getImageUrl(server, it) } else emptyList()
      withContext(Dispatchers.Main) {
        MediaUtils.playFile(
          source = streamUrl,
          context = context,
          launchSource = if (isAudio) "jellyfin_music" else "jellyfin_stream",
          title = itemTitle,
          headers = headers,
          mediaDescription = firstItem.overview,
          posterUrl = posterUrl,
          backdropUrl = backdropUrl,
          playlist = playlistUris,
          playlistIndex = 0,
          playlistTitles = playlistTitles,
          playlistArtists = if (isAudio) playable.map { it.seriesName.orEmpty() } else emptyList(),
          playlistArtworkUrls = playlistArtworks,
          isAudio = isAudio,
        )
      }
    }
  }

  fun togglePlayed(item: JellyfinItem) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      val targetPlayed = !item.isPlayed
      val result =
        if (targetPlayed) {
          jellyfinRepository.markPlayed(server, item)
        } else {
          jellyfinRepository.markUnplayed(server, item)
        }
      result.onSuccess {
        _uiState.update { state ->
          fun updateList(list: List<JellyfinItem>) =
            list.map {
              if (it.id == item.id) {
                it.copy(
                  isPlayed = targetPlayed,
                  playbackPositionTicks = if (targetPlayed) 0L else it.playbackPositionTicks,
                )
              } else {
                it
              }
            }
          state.copy(
            currentItems = updateList(state.currentItems),
            resumeItems = updateList(state.resumeItems),
            heroItems = updateList(state.heroItems),
            latestMovies = updateList(state.latestMovies),
            latestShows = updateList(state.latestShows),
            librarySections = state.librarySections.map { it.copy(items = updateList(it.items)) },
            detailItem = if (state.detailItem?.id == item.id) state.detailItem.copy(isPlayed = targetPlayed) else state.detailItem,
          )
        }
      }
    }
  }

  fun markSelectedPlayed(
    items: List<JellyfinItem>,
    played: Boolean,
  ) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      val ids = items.map { it.id }.toSet()
      items.forEach { item ->
        launch {
          if (played) {
            jellyfinRepository.markPlayed(server, item)
          } else {
            jellyfinRepository.markUnplayed(server, item)
          }
        }
      }
      _uiState.update { state ->
        fun updateList(list: List<JellyfinItem>) =
          list.map {
            if (it.id in ids) {
              it.copy(
                isPlayed = played,
                playbackPositionTicks = if (played) 0L else it.playbackPositionTicks,
              )
            } else {
              it
            }
          }
        state.copy(
          currentItems = updateList(state.currentItems),
          resumeItems = updateList(state.resumeItems),
          heroItems = updateList(state.heroItems),
          latestMovies = updateList(state.latestMovies),
          latestShows = updateList(state.latestShows),
          librarySections = state.librarySections.map { it.copy(items = updateList(it.items)) },
        )
      }
    }
  }

  fun playRandom(context: Context) {
    val items = (_uiState.value.currentItems.ifEmpty {
      val fromSections = _uiState.value.librarySections.flatMap { it.items }
      if (fromSections.isNotEmpty()) fromSections else _uiState.value.latestMovies + _uiState.value.latestShows
    }).filter { it.isVideo }
    if (items.isNotEmpty()) {
      playItem(context, items.random())
    }
  }

  fun resumeLastPlayed(context: Context) {
    viewModelScope.launch {
      val server = _uiState.value.activeServer ?: return@launch
      val resumeItems = jellyfinRepository.getResumeItems(server, limit = 1).getOrNull()
      val itemToPlay = resumeItems?.firstOrNull() ?: _uiState.value.currentItems.firstOrNull { it.isVideo }
      if (itemToPlay != null) {
        playItem(context, itemToPlay)
      }
    }
  }

  fun getStreamUrl(item: JellyfinItem): String {
    val server = _uiState.value.activeServer ?: return ""
    return jellyfinRepository.getStreamUrl(server, item)
  }

  fun createJellyfinPlaylist(name: String, itemIds: List<String> = emptyList()) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      val res = jellyfinRepository.createPlaylist(server, name, itemIds)
      if (res.isSuccess) {
        val library = _uiState.value.openLibrary
        if (library != null) {
          loadMusicTabItems(server, library, JellyfinMusicTab.PLAYLISTS)
        }
      }
    }
  }

  fun addToJellyfinPlaylist(playlistId: String, itemIds: List<String>) {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch {
      jellyfinRepository.addToPlaylist(server, playlistId, itemIds)
    }
  }

  companion object {
    fun isMusicLibrary(item: JellyfinItem): Boolean {
      val col = item.collectionType?.lowercase()?.trim() ?: ""
      val type = item.type.lowercase().trim()
      val name = item.name.lowercase().trim()
      return col == "music" || type == "music" || type == "audio" || (name.contains("music") && !name.contains("video"))
    }

    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          JellyfinViewModel(application)
        }
      }
  }
}

