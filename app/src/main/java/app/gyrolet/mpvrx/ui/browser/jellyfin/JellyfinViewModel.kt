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
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinPerson
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSearchCategory
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.music.MusicSortOrder
import app.gyrolet.mpvrx.ui.browser.music.MusicViewMode
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
  val musicViewMode: MusicViewMode = MusicViewMode.GRID,
  val musicSortField: MusicSortField = MusicSortField.TITLE,
  val musicSortOrder: MusicSortOrder = MusicSortOrder.ASCENDING,
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

  // Person Sheet State
  val personDetail: JellyfinPerson? = null,
  val personOverview: String? = null,
  val personMedia: List<JellyfinItem> = emptyList(),
  val isPersonLoading: Boolean = false,
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
  private val browserPreferences: BrowserPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val downloadManager: AppDownloadManager by inject()

  private var loadDashboardJob: Job? = null
  private var loadItemsJob: Job? = null
  private var searchJob: Job? = null
  private var detailJob: Job? = null
  private var seasonEpisodesJob: Job? = null
  private var personJob: Job? = null
  private var musicLoadJob: Job? = null
  private var loadedMusicHomeLibraryId: String? = null

  private val _uiState = MutableStateFlow(
    JellyfinUiState(
      musicViewMode = browserPreferences.jellyfinMusicViewMode.get(),
      musicSortField = browserPreferences.jellyfinMusicSortField.get(),
      musicSortOrder = browserPreferences.jellyfinMusicSortOrder.get(),
    )
  )
  val uiState: StateFlow<JellyfinUiState> = _uiState.asStateFlow()

  init {
    loadServers()
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest {
        refreshPlaybackStateSilently()
      }
    }
  }

  fun refreshPlaybackStateSilently() {
    val server = _uiState.value.activeServer ?: return
    viewModelScope.launch(Dispatchers.IO) {
      delay(400) // Brief delay to ensure Jellyfin server has committed the stop/progress session data
      val resumeResult = jellyfinRepository.getResumeItems(server, limit = 16).getOrNull()
      if (resumeResult != null) {
        _uiState.update { it.copy(resumeItems = resumeResult) }
      }

      val detail = _uiState.value.detailItem
      if (detail != null) {
        val updatedDetail = jellyfinRepository.getItem(server, detail.id).getOrNull()
        if (updatedDetail != null) {
          _uiState.update { it.copy(detailItem = updatedDetail) }
        }
        val seasonId = _uiState.value.selectedDetailSeasonId
        if (seasonId != null) {
          val episodes = jellyfinRepository.getEpisodes(server, detail.id, seasonId).getOrNull()
          if (episodes != null) {
            _uiState.update { it.copy(detailEpisodes = episodes) }
          }
        }
      }

      val openLib = _uiState.value.openLibrary
      if (openLib != null && _uiState.value.currentItems.isNotEmpty()) {
        val updatedItems = jellyfinRepository.getItems(
          server = server,
          parentId = openLib.id,
          limit = _uiState.value.currentItems.size.coerceAtLeast(50),
          sortBy = _uiState.value.sortBy,
          sortOrder = _uiState.value.sortOrder,
        ).getOrNull()
        if (updatedItems != null && updatedItems.items.isNotEmpty()) {
          _uiState.update { it.copy(currentItems = updatedItems.items) }
        }
      }
    }
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
        val previous = _uiState.value
        suspend fun publish(transform: (JellyfinUiState) -> JellyfinUiState) {
          currentCoroutineContext().ensureActive()
          _uiState.update { if (it.activeServer?.id == server.id) transform(it) else it }
        }
        fun isVideoMedia(item: JellyfinItem): Boolean {
          if (item.isAudio || item.type == "Folder" || item.type == "MusicAlbum" || item.type == "Audio" || item.type == "MusicArtist" || item.type == "CollectionFolder") return false
          if (item.isFolder && !item.isSeries && !item.isSeason && item.type != "Series" && item.type != "Season") return false
          return true
        }

        val libsDeferred = async {
          jellyfinRepository.getLibraries(server).onSuccess { libraries ->
            publish { it.copy(libraries = sortJellyfinLibraries(libraries)) }
          }
        }
        val resumeDeferred = async {
          jellyfinRepository.getResumeItems(server, limit = 16).onSuccess { items ->
            publish { it.copy(resumeItems = items.filter(::isVideoMedia)) }
          }
        }
        val latestDeferred = async {
          jellyfinRepository.getLatestMedia(server, limit = 32).onSuccess { items ->
            val movies = items.filter { isVideoMedia(it) && it.type == "Movie" }
            val shows = resolveShowsAsSeries(server, items.filter { isVideoMedia(it) && (it.type == "Series" || it.type == "Episode") })
            publish { it.copy(latestMovies = movies, latestShows = shows) }
          }
        }
        val suggestionsDeferred = async {
          jellyfinRepository.getSuggestions(server, limit = 36).onSuccess { items ->
            val recommendations = resolveShowsAsSeries(server, items.filter(::isVideoMedia)).distinctBy(::mediaDeduplicationKey)
            publish { it.copy(recommendations = recommendations.take(36)) }
          }
        }
        val topRatedDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Movie,Series",
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RATING,
              sortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.DESCENDING,
              limit = 36,
            ).onSuccess { result ->
              publish { state -> state.copy(recommendations =
                (state.recommendations + result.items.filter(::isVideoMedia)).distinctBy(::mediaDeduplicationKey).take(36)) }
            }
          }
        val musicDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Audio,MusicAlbum",
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.DATE_ADDED,
              sortOrder = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder.DESCENDING,
              limit = 20,
            ).onSuccess { result ->
              publish { it.copy(latestMusic = result.items.distinctBy { item -> item.id }.take(16)) }
            }
          }
        val heroDeferred =
          async {
            jellyfinRepository.getItems(
              server = server,
              includeItemTypes = "Movie,Series",
              isPlayed = false,
              sortBy = app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy.RANDOM,
              limit = 15,
            ).onSuccess { result ->
              val candidates = result.items.filter {
                !it.isPlayed && isVideoMedia(it) && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank())
              }
              val hero = resolveShowsAsSeries(server, candidates).distinctBy(::mediaDeduplicationKey).take(15)
              if (hero.isNotEmpty()) publish { it.copy(heroItems = hero) }
            }
          }

        val libsResult = libsDeferred.await()
        currentCoroutineContext().ensureActive()
        val libs = sortJellyfinLibraries(libsResult.getOrDefault(previous.libraries))

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
            var rawItems = latestItemsResult.getOrDefault(emptyList()).filter { isVideoMedia(it) }

            // Fallback: If getLatestMedia with ParentId returned empty, fetch latest items sorted by DateCreated
            if (rawItems.isEmpty()) {
              val fallbackItemsResult = jellyfinRepository.getItems(
                server = server,
                parentId = lib.id,
                sortBy = JellyfinSortBy.DATE_ADDED,
                sortOrder = JellyfinSortOrder.DESCENDING,
                limit = 16,
              )
              currentCoroutineContext().ensureActive()
              if (latestItemsResult.isFailure && fallbackItemsResult.isFailure) {
                return@async previous.librarySections.firstOrNull { it.library.id == lib.id }
              }
              rawItems = fallbackItemsResult.getOrNull()?.items.orEmpty().filter { isVideoMedia(it) }
            }

            val containsShows = rawItems.any { it.isSeries || it.type == "Series" || it.type == "Episode" || it.seriesName != null }
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
              ).also { section ->
                publish { state ->
                  val sectionsById = (state.librarySections + section).associateBy { it.library.id }
                  state.copy(librarySections = videoLibs.mapNotNull { sectionsById[it.id] })
                }
              }
            } else {
              null
            }
          }
        }
        val resumeResult = resumeDeferred.await()
        val latestResult = latestDeferred.await()
        val suggestionsResult = suggestionsDeferred.await()
        val topRatedResult = topRatedDeferred.await()
        val musicResult = musicDeferred.await()
        val heroResult = heroDeferred.await()

        val resumeRaw = resumeResult.getOrDefault(previous.resumeItems)
        val latestRaw = latestResult.getOrDefault(previous.latestMovies + previous.latestShows)
        val suggestionsRaw = suggestionsResult.getOrDefault(previous.recommendations)
        val topRatedRaw = topRatedResult.getOrNull()?.items.orEmpty()
        val musicRaw = musicResult.getOrNull()?.items ?: previous.latestMusic
        val resume = resumeRaw.filter(::isVideoMedia)
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
          } ?: previous.heroItems

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

        publish {
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
      val changedLibrary = it.openLibrary?.id != library.id
      val changedMusicLibrary = changedLibrary && library.isMusic
      it.copy(
        openLibrary = library,
        currentItems = if (changedLibrary) emptyList() else it.currentItems,
        musicTracks = if (changedMusicLibrary) emptyList() else it.musicTracks,
        musicAlbums = if (changedMusicLibrary) emptyList() else it.musicAlbums,
        musicArtists = if (changedMusicLibrary) emptyList() else it.musicArtists,
        musicPlaylists = if (changedMusicLibrary) emptyList() else it.musicPlaylists,
        musicJumpBackIn = if (changedMusicLibrary) emptyList() else it.musicJumpBackIn,
        musicRecentlyPlayedAlbums = if (changedMusicLibrary) emptyList() else it.musicRecentlyPlayedAlbums,
        musicArtistsToExplore = if (changedMusicLibrary) emptyList() else it.musicArtistsToExplore,
        musicFavorites = if (changedMusicLibrary) emptyList() else it.musicFavorites,
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

  fun getMusicLibraryView(): JellyfinLibraryView? {
    val musicItem = _uiState.value.libraries.firstOrNull { isMusicLibrary(it) } ?: return null
    return JellyfinLibraryView(
      id = musicItem.id,
      title = musicItem.name,
      itemTypes = "Audio",
      collectionType = musicItem.collectionType,
      isMusic = true,
    )
  }

  fun ensureMusicDataLoaded() {
    val active = _uiState.value.activeServer ?: return
    val musicLib = getMusicLibraryView() ?: return
    if (loadedMusicHomeLibraryId != musicLib.id) {
      loadMusicHomeDashboard(active, musicLib)
    }
  }

  fun ensureMusicLibraryOpened() {
    ensureMusicDataLoaded()
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
      _uiState.update { it.copy(isLoading = false, isMusicLoading = true, error = null) }
      var completed = true
      suspend fun publish(transform: (JellyfinUiState) -> JellyfinUiState) {
        currentCoroutineContext().ensureActive()
        _uiState.update { if (it.activeServer?.id == server.id) transform(it) else it }
      }
      suspend fun <T> receive(result: Result<T>): T? {
        currentCoroutineContext().ensureActive()
        return result.onFailure { error ->
          if (error is CancellationException) throw error
          completed = false
          publish { it.copy(error = error.message) }
        }.getOrNull()
      }
      try {
        coroutineScope {
          launch {
            val tracks = linkedMapOf<String, JellyfinItem>()
            val previous = _uiState.value.musicJumpBackIn
            var allTracksLoaded = true
            for (sortBy in listOf(JellyfinSortBy.DATE_PLAYED, JellyfinSortBy.DATE_ADDED, JellyfinSortBy.RANDOM)) {
              val result = receive(jellyfinRepository.getItems(server, parentId = library.id,
                includeItemTypes = "Audio", sortBy = sortBy, sortOrder = JellyfinSortOrder.DESCENDING, limit = 30))
              if (result == null) {
                allTracksLoaded = false
                continue
              }
              result.items.forEach { tracks.putIfAbsent(it.id, it) }
              val visible = (tracks.values.toList() + previous.filterNot { it.id in tracks }).take(24)
              publish { it.copy(musicJumpBackIn = visible) }
            }
            if (allTracksLoaded) publish { it.copy(musicJumpBackIn = tracks.values.take(24)) }
          }
          launch {
            val result = receive(jellyfinRepository.getItems(server, parentId = library.id,
              includeItemTypes = "MusicAlbum", sortBy = JellyfinSortBy.DATE_ADDED,
              sortOrder = JellyfinSortOrder.DESCENDING, limit = 15)) ?: return@launch
            publish { it.copy(musicRecentlyPlayedAlbums = result.items) }
          }
          launch {
            val endpointArtists = receive(jellyfinRepository.getArtists(server, parentId = library.id, limit = 30))
            if (endpointArtists != null) publish { it.copy(musicArtistsToExplore = endpointArtists.items.take(15)) }
            val itemArtists = receive(jellyfinRepository.getItems(server, parentId = library.id,
              includeItemTypes = "MusicArtist,Artist,AlbumArtist", sortBy = JellyfinSortBy.RANDOM, limit = 30))
            if (endpointArtists != null || itemArtists != null) {
              val artists = (endpointArtists?.items.orEmpty() + itemArtists?.items.orEmpty())
                .filter { it.name.isNotBlank() }
                .distinctBy { it.id.ifBlank { it.name.lowercase().trim() } }
                .shuffled().take(15)
              publish { it.copy(musicArtistsToExplore = artists) }
            }
          }
          launch {
            val result = receive(jellyfinRepository.getItems(server, parentId = null,
              includeItemTypes = "Audio", isFavorite = true, sortBy = JellyfinSortBy.NAME, limit = 50)) ?: return@launch
            publish { it.copy(musicFavorites = result.items) }
          }
          launch {
            val favorites = JellyfinItem(id = "virtual_favorites_playlist", name = "Favorites", type = "Playlist",
              overview = null, isFolder = true, isFavorite = true, primaryImageTag = null, albumPrimaryImageTag = null)
            val previous = _uiState.value.musicPlaylists
            val playlists = linkedMapOf(favorites.id to favorites)
            val seen = HashSet<String>()
            var startIndex = 0
            while (true) {
              val result = receive(jellyfinRepository.getItems(server, parentId = null,
                includeItemTypes = "Playlist", sortBy = JellyfinSortBy.NAME,
                startIndex = startIndex, limit = 100)) ?: return@launch
              val newItems = result.items.filter { seen.add(it.id) }
              newItems.filterNot { it.name.equals("Favorites", true) }.forEach { playlists[it.id] = it }
              startIndex += result.items.size
              val finished = newItems.isEmpty() || startIndex >= result.totalRecordCount
              val visible = playlists.values.toList() + if (finished) emptyList() else previous.filterNot { it.id in playlists }
              publish { it.copy(musicPlaylists = visible) }
              if (finished) break
            }
          }
        }
        currentCoroutineContext().ensureActive()
        if (completed) loadedMusicHomeLibraryId = library.id
      } finally {
        if (currentCoroutineContext().isActive) publish { it.copy(isMusicLoading = false) }
      }
    }
  }

  fun setMusicTab(tab: JellyfinMusicTab) {
    if (_uiState.value.musicActiveTab == tab) return
    _uiState.update { it.copy(musicActiveTab = tab) }
    val active = _uiState.value.activeServer ?: return
    val library = _uiState.value.openLibrary ?: getMusicLibraryView() ?: return

    if (tab == JellyfinMusicTab.HOME) {
      if (loadedMusicHomeLibraryId != library.id) loadMusicHomeDashboard(active, library)
    } else {
      loadMusicTabItems(active, library, tab)
    }
  }

  fun setMusicViewMode(mode: MusicViewMode) {
    browserPreferences.jellyfinMusicViewMode.set(mode)
    _uiState.update { it.copy(musicViewMode = mode) }
  }

  fun setMusicSortField(field: MusicSortField) {
    browserPreferences.jellyfinMusicSortField.set(field)
    _uiState.update { it.copy(musicSortField = field) }
  }

  fun setMusicSortOrder(order: MusicSortOrder) {
    browserPreferences.jellyfinMusicSortOrder.set(order)
    _uiState.update { it.copy(musicSortOrder = order) }
  }

  private fun loadMusicTabItems(
    server: JellyfinServer,
    library: JellyfinLibraryView,
    tab: JellyfinMusicTab,
  ) {
    musicLoadJob?.cancel()
    musicLoadJob = viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, isMusicLoading = false, error = null) }
      val previous = when (tab) {
        JellyfinMusicTab.PLAYLISTS -> _uiState.value.musicPlaylists
        JellyfinMusicTab.ARTISTS -> _uiState.value.musicArtists
        JellyfinMusicTab.ALBUMS -> _uiState.value.musicAlbums
        JellyfinMusicTab.TRACKS -> _uiState.value.musicTracks
        JellyfinMusicTab.HOME -> emptyList()
      }
      val fetched = linkedMapOf<String, JellyfinItem>()
      var allRequestsSucceeded = true
      fun itemKey(item: JellyfinItem): String = item.id.ifBlank { item.name.lowercase().trim() }
      suspend fun publish(transform: (JellyfinUiState) -> JellyfinUiState) {
        currentCoroutineContext().ensureActive()
        _uiState.update { state ->
          if (state.activeServer?.id == server.id && state.musicActiveTab == tab) transform(state) else state
        }
      }
      suspend fun publishItems(complete: Boolean = false) {
        val items = fetched.values.toList() + if (complete) emptyList() else previous.filterNot { itemKey(it) in fetched }
        publish { state ->
          when (tab) {
            JellyfinMusicTab.PLAYLISTS -> state.copy(musicPlaylists = items)
            JellyfinMusicTab.ARTISTS -> state.copy(musicArtists = items.sortedBy { it.name.lowercase() })
            JellyfinMusicTab.ALBUMS -> state.copy(musicAlbums = items)
            JellyfinMusicTab.TRACKS -> state.copy(musicTracks = items)
            JellyfinMusicTab.HOME -> state
          }
        }
      }
      suspend fun loadPages(fetch: suspend (Int) -> Result<app.gyrolet.mpvrx.domain.jellyfin.JellyfinQueryResult>) {
        var startIndex = 0
        val seen = HashSet<String>()
        while (true) {
          val page = fetch(startIndex).getOrElse { error ->
            if (error is CancellationException) throw error
            allRequestsSucceeded = false
            publish { it.copy(error = error.message) }
            return
          }
          currentCoroutineContext().ensureActive()
          val newItems = page.items.filter { seen.add(itemKey(it)) }
          if (newItems.isEmpty()) return
          newItems.filter { it.name.isNotBlank() }
            .filterNot { tab == JellyfinMusicTab.PLAYLISTS && it.name.equals("Favorites", true) }
            .forEach { fetched[itemKey(it)] = it }
          publishItems()
          startIndex += page.items.size
          if (startIndex >= page.totalRecordCount) return
        }
      }
      try {
        when (tab) {
          JellyfinMusicTab.PLAYLISTS -> {
            val favorites = JellyfinItem(
              id = "virtual_favorites_playlist",
              name = "Favorites",
              type = "Playlist",
              overview = null,
              isFolder = true,
              isFavorite = true,
              primaryImageTag = null,
              albumPrimaryImageTag = null,
            )
            fetched[favorites.id] = favorites
            publishItems()
            loadPages { startIndex ->
              jellyfinRepository.getItems(server, parentId = null, includeItemTypes = "Playlist",
                sortBy = JellyfinSortBy.NAME, startIndex = startIndex, limit = 100)
            }
          }
          JellyfinMusicTab.ARTISTS -> {
            for (parentId in listOf(library.id, null)) {
              loadPages { startIndex ->
                jellyfinRepository.getItems(server, parentId = parentId, includeItemTypes = "MusicArtist,Artist,AlbumArtist",
                  sortBy = JellyfinSortBy.NAME, startIndex = startIndex, limit = 100)
              }
              for (albumArtistsOnly in listOf(false, true)) {
                loadPages { startIndex ->
                  jellyfinRepository.getArtists(server, parentId = parentId, startIndex = startIndex,
                    limit = 100, albumArtistsOnly = albumArtistsOnly)
                }
              }
            }
          }
          JellyfinMusicTab.ALBUMS, JellyfinMusicTab.TRACKS -> loadPages { startIndex ->
            jellyfinRepository.getItems(server, parentId = library.id,
              includeItemTypes = if (tab == JellyfinMusicTab.ALBUMS) "MusicAlbum" else "Audio",
              sortBy = JellyfinSortBy.NAME, startIndex = startIndex, limit = 100)
          }
          JellyfinMusicTab.HOME -> Unit
        }
        if (allRequestsSucceeded) publishItems(complete = true)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        publish { it.copy(error = error.message) }
      } finally {
        if (currentCoroutineContext().isActive) publish { it.copy(isLoading = false) }
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
              isLoading = it.currentItems.isEmpty(),
              isLoadingMore = false,
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
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
            if (err is kotlinx.coroutines.CancellationException) throw err
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
          val initialSeason = seasons.firstOrNull { !it.isPlayed } ?: seasons.firstOrNull()

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

  fun openPerson(person: JellyfinPerson) {
    val active = _uiState.value.activeServer ?: return
    personJob?.cancel()
    _uiState.update {
      it.copy(
        personDetail = person,
        personOverview = null,
        personMedia = emptyList(),
        isPersonLoading = true,
      )
    }

    personJob =
      viewModelScope.launch {
        val bioDeferred = async { jellyfinRepository.getPerson(active, person.name) }
        val mediaDeferred = async { jellyfinRepository.getPersonMedia(active, personId = person.id, personName = person.name) }

        val bioResult = bioDeferred.await()
        val mediaResult = mediaDeferred.await()

        val bioItem = bioResult.getOrNull()
        val media = mediaResult.getOrDefault(emptyList()).distinctBy { it.id }

        _uiState.update {
          it.copy(
            personDetail = if (bioItem != null && !bioItem.primaryImageTag.isNullOrBlank() && person.primaryImageTag.isNullOrBlank()) {
              person.copy(primaryImageTag = bioItem.primaryImageTag)
            } else {
              person
            },
            personOverview = bioItem?.overview?.takeIf { ov -> ov.isNotBlank() },
            personMedia = media,
            isPersonLoading = false,
          )
        }
      }
  }

  fun closePerson() {
    personJob?.cancel()
    _uiState.update {
      it.copy(
        personDetail = null,
        personOverview = null,
        personMedia = emptyList(),
        isPersonLoading = false,
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
        if (!appearancePreferences.showJellyfinTab.get()) {
          appearancePreferences.showJellyfinTab.set(true)
        }
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
      var playlistDurations: List<Int> = emptyList()
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
            playlistDurations = audioSource.map { it.durationSeconds.toInt() }
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
            playlistDurations = episodesSource.map { it.durationSeconds.toInt() }
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

      if (playlistDurations.isEmpty()) {
        playlistDurations = listOf(targetItem.durationSeconds.toInt())
      }

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
          playlistDurationsSeconds = playlistDurations,
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
          playlistDurationsSeconds = playable.map { it.durationSeconds.toInt() },
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

