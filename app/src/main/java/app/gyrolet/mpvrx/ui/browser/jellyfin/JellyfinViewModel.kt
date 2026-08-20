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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
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

data class JellyfinLibraryView(
  val id: String,
  val title: String,
  /** Jellyfin IncludeItemTypes requested recursively for this view. */
  val itemTypes: String,
)

data class JellyfinUiState(
  val servers: List<JellyfinServer> = emptyList(),
  val activeServer: JellyfinServer? = null,
  val libraries: List<JellyfinItem> = emptyList(),
  val heroItems: List<JellyfinItem> = emptyList(),
  val resumeItems: List<JellyfinItem> = emptyList(),
  val latestMovies: List<JellyfinItem> = emptyList(),
  val latestShows: List<JellyfinItem> = emptyList(),
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

  // Detail Sheet State
  val detailItem: JellyfinItem? = null,
  val detailSeasons: List<JellyfinItem> = emptyList(),
  val selectedDetailSeasonId: String? = null,
  val detailEpisodes: List<JellyfinItem> = emptyList(),
  val detailSimilarItems: List<JellyfinItem> = emptyList(),
  val isDetailLoading: Boolean = false,
  val isDetailEpisodesLoading: Boolean = false,
)

class JellyfinViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val jellyfinRepository: JellyfinRepository by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val subtitlesPreferences: SubtitlesPreferences by inject()
  private val audioPreferences: AudioPreferences by inject()

  private var loadDashboardJob: Job? = null
  private var loadItemsJob: Job? = null
  private var searchJob: Job? = null
  private var detailJob: Job? = null
  private var seasonEpisodesJob: Job? = null

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
    loadDashboardJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true, error = null) }

        val libsDeferred = async { jellyfinRepository.getLibraries(server) }
        val resumeDeferred = async { jellyfinRepository.getResumeItems(server, limit = 16) }
        val latestDeferred = async { jellyfinRepository.getLatestMedia(server, limit = 24) }
        val suggestionsDeferred = async { jellyfinRepository.getSuggestions(server, limit = 16) }
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
        val heroResult = heroDeferred.await()

        val libs = sortJellyfinLibraries(libsResult.getOrDefault(emptyList()))
        val resume = resumeResult.getOrDefault(emptyList())
        val latest = latestResult.getOrDefault(emptyList())
        val suggestions = suggestionsResult.getOrDefault(emptyList())

        val latestMovies = latest.filter { it.type == "Movie" || it.collectionType?.equals("movies", ignoreCase = true) == true }
        val latestShows = latest.filter { it.type == "Series" || it.type == "Episode" || it.collectionType?.equals("tvshows", ignoreCase = true) == true }

        // Hero Items: 15 unplayed random Movies & TV Series (AFinity logic, excluding continue watching)
        val fetchedHero =
          heroResult.getOrNull()?.items?.filter {
            !it.isPlayed && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank())
          } ?: emptyList()

        val finalHero =
          if (fetchedHero.isNotEmpty()) {
            fetchedHero.take(15)
          } else {
            (latest + suggestions)
              .filter { !it.isPlayed && (!it.backdropImageTag.isNullOrBlank() || !it.primaryImageTag.isNullOrBlank()) }
              .distinctBy { it.id }
              .take(15)
          }

        _uiState.update {
          it.copy(
            libraries = libs,
            resumeItems = resume,
            latestMovies = latestMovies,
            latestShows = latestShows,
            recommendations = suggestions.ifEmpty { latest.take(12) },
            heroItems = finalHero,
            isLoading = false,
            error = if (libs.isEmpty() && latest.isEmpty() && resume.isEmpty()) libsResult.exceptionOrNull()?.message else null,
          )
        }
      }
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
    // Only real containers open a grid; a series or season opens its own detail page with
    // seasons and episodes instead of being browsed as a folder.
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
      openLibrary(active, JellyfinLibraryView(item.id, item.name, types))
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
      )
    }
    loadLibraryItems(server, library, resetPagination = true)
    viewModelScope.launch {
      jellyfinRepository.getGenres(server, library.id).onSuccess { genres ->
        if (_uiState.value.openLibrary?.id == library.id) {
          _uiState.update { it.copy(availableGenres = genres) }
        }
      }
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
    if (_uiState.value.openLibrary == null) return false
    _uiState.update {
      it.copy(
        openLibrary = null,
        currentItems = emptyList(),
        selectedLibraryId = null,
        selectedGenreFilter = null,
        availableGenres = emptyList(),
        searchQuery = "",
      )
    }
    val active = _uiState.value.activeServer ?: return true
    loadHomeDashboard(active)
    return true
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
          val seasons = seasonsResult.getOrDefault(emptyList())
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
              name = serverName.ifBlank { "Jellyfin (${user.name})" },
              serverUrl = effectiveUrl,
              userId = user.id,
              username = user.name,
              accessToken = token.trim(),
              lastConnected = System.currentTimeMillis(),
            )
          }

        val id = jellyfinRepository.saveServer(serverToSave)
        val savedServer = serverToSave.copy(id = id)
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
    val streamUrl = jellyfinRepository.getStreamUrl(server, item)
    val mediaIdentifier = PlaybackIdentity.forUri(streamUrl)
    val posterUrl = jellyfinRepository.getImageUrl(server, item)
    val backdropUrl = jellyfinRepository.getBackdropUrl(server, item)

    val itemTitle =
      when {
        item.seriesName != null && item.indexNumber != null -> "${item.seriesName} S${item.parentIndexNumber ?: 1}E${item.indexNumber} - ${item.name}"
        else -> item.name
      }

    viewModelScope.launch(Dispatchers.IO) {
      if (startFromBeginning) {
        runCatching {
          playbackStateRepository.deleteByTitle(mediaIdentifier)
          playbackStateRepository.deleteByTitle(streamUrl)
        }
      }

      val freshItemDeferred =
        async {
          if (!startFromBeginning) {
            jellyfinRepository.getItem(server, item.id).getOrNull()
          } else {
            null
          }
        }

      val subsDeferred =
        async {
          jellyfinRepository
            .getSubtitleTracks(server = server, itemId = item.id)
            .getOrDefault(emptyList())
        }

      val freshItem = freshItemDeferred.await() ?: item
      val effectivePositionTicks =
        if (!startFromBeginning) {
          freshItem.playbackPositionTicks ?: item.playbackPositionTicks ?: 0L
        } else {
          0L
        }
      val positionSeconds = (effectivePositionTicks / JellyfinClient.TICKS_PER_SECOND).toInt()

      if (positionSeconds > 0) {
        val durationSec = (freshItem.runTimeTicks ?: item.runTimeTicks ?: 0L) / JellyfinClient.TICKS_PER_SECOND
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
          itemId = item.id,
          positionTicks = effectivePositionTicks,
        )
      }

      val externalSubs = subsDeferred.await()

      // If playing an episode, extract surrounding episode playlist from current detail list or current view
      val (playlistUris, playlistTitles, playlistIndex) =
        if (item.type == "Episode") {
          val episodesSource =
            _uiState.value.detailEpisodes.ifEmpty {
              _uiState.value.currentItems.filter { it.type == "Episode" }
            }

          if (episodesSource.size > 1) {
            val uris = ArrayList<Uri>(episodesSource.size)
            val titles = ArrayList<String>(episodesSource.size)
            var targetIdx = 0
            episodesSource.forEachIndexed { index, ep ->
              if (ep.id == item.id) targetIdx = index
              uris.add(Uri.parse(jellyfinRepository.getStreamUrl(server, ep)))
              titles.add(
                when {
                  ep.seriesName != null && ep.indexNumber != null ->
                    "${ep.seriesName} S${ep.parentIndexNumber ?: 1}E${ep.indexNumber} - ${ep.name}"
                  else -> ep.name
                },
              )
            }
            Triple(uris, titles, targetIdx)
          } else {
            Triple(emptyList<Uri>(), emptyList<String>(), 0)
          }
        } else {
          Triple(emptyList<Uri>(), emptyList<String>(), 0)
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
          launchSource = "jellyfin_stream",
          title = itemTitle,
          headers = headers,
          mediaDescription = item.overview,
          posterUrl = posterUrl,
          backdropUrl = backdropUrl,
          subtitleTracks = externalSubs,
          playlist = playlistUris,
          playlistIndex = playlistIndex,
          playlistTitles = playlistTitles,
        )
      }
    }
  }

  fun playSelected(
    context: Context,
    items: List<JellyfinItem>,
  ) {
    val server = _uiState.value.activeServer ?: return
    val playable = items.filter { it.isVideo }
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

      if (positionSeconds > 0) {
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

      withContext(Dispatchers.Main) {
        MediaUtils.playFile(
          source = streamUrl,
          context = context,
          launchSource = "jellyfin_stream",
          title = itemTitle,
          headers = headers,
          mediaDescription = firstItem.overview,
          posterUrl = posterUrl,
          backdropUrl = backdropUrl,
          playlist = playlistUris,
          playlistIndex = 0,
          playlistTitles = playlistTitles,
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
        )
      }
    }
  }

  fun playRandom(context: Context) {
    val items = (_uiState.value.currentItems.ifEmpty { _uiState.value.latestMovies + _uiState.value.latestShows }).filter { it.isVideo }
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

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          JellyfinViewModel(application)
        }
      }
  }
}

