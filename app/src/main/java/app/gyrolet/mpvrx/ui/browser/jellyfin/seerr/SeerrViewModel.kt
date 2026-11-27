/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin.seerr

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.domain.seerr.JellyseerrRequest
import app.gyrolet.mpvrx.domain.seerr.JellyseerrUser
import app.gyrolet.mpvrx.domain.seerr.MediaDetails
import app.gyrolet.mpvrx.domain.seerr.MediaStatus
import app.gyrolet.mpvrx.domain.seerr.MediaType
import app.gyrolet.mpvrx.domain.seerr.RequestStatus
import app.gyrolet.mpvrx.domain.seerr.SearchResultItem
import app.gyrolet.mpvrx.preferences.SeerrPreferences
import app.gyrolet.mpvrx.repository.SeerrRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

enum class SeerrMainTab {
  ALL,
  MOVIES,
  TV,
  MY_REQUESTS,
}

data class SeerrUiState(
  val isConnected: Boolean = false,
  val currentUser: JellyseerrUser? = null,
  val serverUrl: String = "",
  val apiKey: String = "",
  val isConnecting: Boolean = false,
  val connectionError: String? = null,
  val isConnectionDialogOpen: Boolean = false,
  val isLoadingContent: Boolean = false,
  val recentlyAdded: List<SearchResultItem> = emptyList(),
  val activeRequests: List<JellyseerrRequest> = emptyList(),
  val availableRequests: List<JellyseerrRequest> = emptyList(),
  val trendingItems: List<SearchResultItem> = emptyList(),
  val popularMovies: List<SearchResultItem> = emptyList(),
  val popularTv: List<SearchResultItem> = emptyList(),
  val upcomingMovies: List<SearchResultItem> = emptyList(),
  val upcomingTv: List<SearchResultItem> = emptyList(),
  val searchQuery: String = "",
  val searchResults: List<SearchResultItem> = emptyList(),
  val isSearching: Boolean = false,
  val selectedTab: SeerrMainTab = SeerrMainTab.ALL,
  val selectedSearchItem: SearchResultItem? = null,
  val selectedMediaDetails: MediaDetails? = null,
  val isDetailLoading: Boolean = false,
  val isDetailSheetOpen: Boolean = false,
  val isRequesting: Boolean = false,
  val radarrServers: List<app.gyrolet.mpvrx.domain.seerr.SeerrRadarrServer> = emptyList(),
  val sonarrServers: List<app.gyrolet.mpvrx.domain.seerr.SeerrSonarrServer> = emptyList(),
  val isLoadingServers: Boolean = false,
  val actionMessage: String? = null,
)

class SeerrViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val seerrRepository: SeerrRepository by inject()
  private val seerrPreferences: SeerrPreferences by inject()

  private val _uiState =
    MutableStateFlow(
      SeerrUiState(
        isConnected = seerrPreferences.isLoggedIn.get(),
        serverUrl = seerrPreferences.serverUrl.get(),
        apiKey = seerrPreferences.apiKey.get(),
      ),
    )
  val uiState: StateFlow<SeerrUiState> = _uiState.asStateFlow()

  private var searchJob: Job? = null
  private var dashboardJob: Job? = null
  private var detailJob: Job? = null

  init {
    viewModelScope.launch {
      seerrRepository.currentUser.collect { user ->
        _uiState.update { it.copy(currentUser = user) }
      }
    }
    viewModelScope.launch {
      seerrRepository.isAuthenticated.collect { isAuth ->
        _uiState.update { it.copy(isConnected = isAuth) }
      }
    }

    if (seerrPreferences.isLoggedIn.get()) {
      viewModelScope.launch {
        seerrRepository.getCurrentUser().onSuccess { loadDashboard() }
      }
    }
  }

  fun openConnectionDialog() {
    _uiState.update {
      it.copy(
        isConnectionDialogOpen = true,
        connectionError = null,
        serverUrl = seerrPreferences.serverUrl.get(),
        apiKey = seerrPreferences.apiKey.get(),
      )
    }
  }

  fun closeConnectionDialog() {
    _uiState.update { it.copy(isConnectionDialogOpen = false, connectionError = null) }
  }

  fun connectWithCredentials(
    serverUrl: String,
    user: String,
    pass: String,
    useJellyfin: Boolean,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isConnecting = true, connectionError = null) }
      val cleanUrl = serverUrl.trim().removeSuffix("/")
      val candidateUrls = seerrRepository.generateCandidateUrls(cleanUrl)
      var validUrl: String? = null

      for (cand in candidateUrls) {
        if (seerrRepository.verifyServer(cand)) {
          validUrl = cand
          break
        }
      }

      val targetUrl = validUrl ?: cleanUrl
      val res = seerrRepository.login(targetUrl, user, pass, useJellyfin)
      res.fold(
        onSuccess = { loggedInUser ->
          _uiState.update {
            it.copy(
              isConnecting = false,
              isConnected = true,
              currentUser = loggedInUser,
              serverUrl = targetUrl,
              isConnectionDialogOpen = false,
              connectionError = null,
            )
          }
          loadDashboard()
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isConnecting = false,
              connectionError = err.message ?: "Failed to connect to server",
            )
          }
        },
      )
    }
  }

  fun connectWithApiKey(
    serverUrl: String,
    apiKey: String,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isConnecting = true, connectionError = null) }
      val cleanUrl = serverUrl.trim().removeSuffix("/")
      val candidateUrls = seerrRepository.generateCandidateUrls(cleanUrl)
      var validUrl: String? = null

      for (cand in candidateUrls) {
        if (seerrRepository.verifyServer(cand)) {
          validUrl = cand
          break
        }
      }

      val targetUrl = validUrl ?: cleanUrl
      val res = seerrRepository.loginWithApiKey(targetUrl, apiKey)
      res.fold(
        onSuccess = { loggedInUser ->
          _uiState.update {
            it.copy(
              isConnecting = false,
              isConnected = true,
              currentUser = loggedInUser,
              serverUrl = targetUrl,
              apiKey = apiKey,
              isConnectionDialogOpen = false,
              connectionError = null,
            )
          }
          loadDashboard()
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isConnecting = false,
              connectionError = err.message ?: "Invalid API Key or Server URL",
            )
          }
        },
      )
    }
  }

  fun disconnect() {
    viewModelScope.launch {
      seerrRepository.logout()
      _uiState.update {
        it.copy(
          isConnected = false,
          currentUser = null,
          isConnectionDialogOpen = false,
          recentlyAdded = emptyList(),
          activeRequests = emptyList(),
          availableRequests = emptyList(),
          trendingItems = emptyList(),
          popularMovies = emptyList(),
          popularTv = emptyList(),
          upcomingMovies = emptyList(),
          upcomingTv = emptyList(),
        )
      }
    }
  }

  fun setTab(tab: SeerrMainTab) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  fun loadDashboard() {
    if (!seerrPreferences.isLoggedIn.get() && !_uiState.value.isConnected) return
    dashboardJob?.cancel()
    dashboardJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isLoadingContent = true) }

        val recentDeferred = async { seerrRepository.getRecentlyAdded(20) }
        val reqDeferred = async { seerrRepository.getRequests(take = 50) }
        val trendingDeferred = async { seerrRepository.getTrending(1) }
        val popMoviesDeferred = async { seerrRepository.getDiscoverMovies(1, "popularity.desc") }
        val popTvDeferred = async { seerrRepository.getDiscoverTv(1, "popularity.desc") }
        val upMoviesDeferred = async { seerrRepository.getUpcomingMovies(1) }
        val upTvDeferred = async { seerrRepository.getUpcomingTv(1) }

        val recentRes = recentDeferred.await()
        val requestsRes = reqDeferred.await()
        val trendingRes = trendingDeferred.await()
        val popMoviesRes = popMoviesDeferred.await()
        val popTvRes = popTvDeferred.await()
        val upMoviesRes = upMoviesDeferred.await()
        val upTvRes = upTvDeferred.await()

        val allRequests = requestsRes.getOrDefault(emptyList())
        val activeReqs =
          allRequests.filter {
            it.status != RequestStatus.COMPLETED.value &&
              it.status != RequestStatus.DECLINED.value
          }
        val availableReqs = allRequests.filter { it.status == RequestStatus.COMPLETED.value }

        _uiState.update {
          it.copy(
            isLoadingContent = false,
            recentlyAdded = recentRes.getOrDefault(emptyList()),
            activeRequests = activeReqs,
            availableRequests = availableReqs,
            trendingItems = trendingRes.getOrNull()?.results ?: emptyList(),
            popularMovies = popMoviesRes.getOrNull()?.results ?: emptyList(),
            popularTv = popTvRes.getOrNull()?.results ?: emptyList(),
            upcomingMovies = upMoviesRes.getOrNull()?.results ?: emptyList(),
            upcomingTv = upTvRes.getOrNull()?.results ?: emptyList(),
          )
        }
      }
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    searchJob?.cancel()
    if (query.isBlank()) {
      _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
      return
    }
    searchJob =
      viewModelScope.launch {
        delay(350)
        performSearch(query)
      }
  }

  fun performSearch(query: String) {
    if (query.isBlank()) return
    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isSearching = true) }
        val res = seerrRepository.searchMedia(query.trim())
        _uiState.update {
          it.copy(
            isSearching = false,
            searchResults = res.getOrNull()?.results ?: emptyList(),
          )
        }
      }
  }

  fun clearSearch() {
    searchJob?.cancel()
    _uiState.update { it.copy(searchQuery = "", searchResults = emptyList(), isSearching = false) }
  }

  fun openDetail(item: SearchResultItem) {
    detailJob?.cancel()
    _uiState.update {
      it.copy(
        selectedSearchItem = item,
        selectedMediaDetails = null,
        isDetailLoading = true,
        isDetailSheetOpen = true,
      )
    }

    if (_uiState.value.radarrServers.isEmpty() || _uiState.value.sonarrServers.isEmpty()) {
      loadServerConfigs()
    }

    detailJob =
      viewModelScope.launch {
        val res =
          if (item.getMediaType() == MediaType.TV) {
            seerrRepository.getTvDetails(item.id)
          } else {
            seerrRepository.getMovieDetails(item.id)
          }
        _uiState.update {
          it.copy(
            selectedMediaDetails = res.getOrNull(),
            isDetailLoading = false,
          )
        }
      }
  }

  fun loadServerConfigs() {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoadingServers = true) }
      val radarr = seerrRepository.getRadarrServers().getOrDefault(emptyList())
      val sonarr = seerrRepository.getSonarrServers().getOrDefault(emptyList())
      _uiState.update {
        it.copy(
          radarrServers = radarr,
          sonarrServers = sonarr,
          isLoadingServers = false,
        )
      }
    }
  }

  fun openDetailFromRequest(request: JellyseerrRequest) {
    val tmdbId = request.media.tmdbId ?: return
    val mediaType = request.getMediaType()
    val searchItem =
      SearchResultItem(
        id = tmdbId,
        mediaType = mediaType.value,
        title = request.media.title,
        name = request.media.name,
        posterPath = request.media.posterPath,
        backdropPath = request.media.backdropPath,
        mediaInfo = request.media,
      )
    openDetail(searchItem)
  }

  fun closeDetail() {
    detailJob?.cancel()
    _uiState.update {
      it.copy(
        isDetailSheetOpen = false,
        selectedSearchItem = null,
        selectedMediaDetails = null,
      )
    }
  }

  fun requestMedia(
    seasons: List<Int>?,
    is4k: Boolean,
    serverId: Int? = null,
    profileId: Int? = null,
    rootFolder: String? = null,
  ) {
    val searchItem = _uiState.value.selectedSearchItem ?: return
    val mediaType = searchItem.getMediaType()
    val mediaId = searchItem.id

    viewModelScope.launch {
      _uiState.update { it.copy(isRequesting = true) }
      val res =
        seerrRepository.createRequest(
          mediaId = mediaId,
          mediaType = mediaType,
          seasons = seasons,
          is4k = is4k,
          serverId = serverId,
          profileId = profileId,
          rootFolder = rootFolder,
        )
      res.fold(
        onSuccess = { req ->
          val resolvedMediaStatus =
            when {
              req.media.status != null && req.media.status != MediaStatus.UNKNOWN.value -> req.media.status
              req.status == RequestStatus.PENDING.value -> MediaStatus.PENDING.value
              else -> MediaStatus.PROCESSING.value
            }

          val updatedMediaInfo =
            req.media.copy(
              status = resolvedMediaStatus,
              requests = listOf(req) + (req.media.requests ?: emptyList()),
              seasons =
                req.media.seasons?.map { s ->
                  if (seasons?.contains(s.seasonNumber) == true) {
                    s.copy(status = MediaStatus.PROCESSING.value)
                  } else {
                    s
                  }
                }
                  ?: seasons?.map {
                    app.gyrolet.mpvrx.domain.seerr
                      .MediaInfoSeason(seasonNumber = it, status = MediaStatus.PROCESSING.value)
                  },
            )

          _uiState.update { state ->
            val curDetails = state.selectedMediaDetails
            val newDetails = curDetails?.copy(mediaInfo = updatedMediaInfo)
            val curSearchItem = state.selectedSearchItem
            val newSearchItem = curSearchItem?.copy(mediaInfo = updatedMediaInfo)

            state.copy(
              isRequesting = false,
              selectedMediaDetails = newDetails ?: curDetails,
              selectedSearchItem = newSearchItem ?: curSearchItem,
              activeRequests = listOf(req) + state.activeRequests.filter { it.id != req.id },
              actionMessage = "Request submitted successfully",
            )
          }

          refreshDetailSilently(mediaId, mediaType)
          loadDashboard()
        },
        onFailure = { err ->
          _uiState.update {
            it.copy(
              isRequesting = false,
              actionMessage = "Failed: ${err.message}",
            )
          }
        },
      )
    }
  }

  private fun refreshDetailSilently(
    mediaId: Int,
    mediaType: MediaType,
  ) {
    viewModelScope.launch {
      val res =
        if (mediaType == MediaType.TV) {
          seerrRepository.getTvDetails(mediaId, forceRefresh = true)
        } else {
          seerrRepository.getMovieDetails(mediaId, forceRefresh = true)
        }
      res.onSuccess { freshDetails ->
        _uiState.update { state ->
          if (state.selectedSearchItem?.id == mediaId) {
            state.copy(
              selectedMediaDetails = freshDetails,
              selectedSearchItem = state.selectedSearchItem.copy(mediaInfo = freshDetails.mediaInfo),
            )
          } else {
            state
          }
        }
      }
    }
  }

  fun approveRequest(requestId: Int) {
    viewModelScope.launch {
      val res = seerrRepository.approveRequest(requestId)
      res.onSuccess { req ->
        _uiState.update { it.copy(actionMessage = "Request approved") }
        val tmdbId = req.media.tmdbId ?: req.media.id
        if (tmdbId > 0) {
          val mediaType = req.getMediaType()
          seerrRepository.clearCacheForMedia(mediaType.value, tmdbId)
          refreshDetailSilently(tmdbId, mediaType)
        }
        loadDashboard()
      }
    }
  }

  fun declineRequest(requestId: Int) {
    viewModelScope.launch {
      val res = seerrRepository.declineRequest(requestId)
      res.onSuccess { req ->
        _uiState.update { it.copy(actionMessage = "Request declined") }
        val tmdbId = req.media.tmdbId ?: req.media.id
        if (tmdbId > 0) {
          val mediaType = req.getMediaType()
          seerrRepository.clearCacheForMedia(mediaType.value, tmdbId)
          refreshDetailSilently(tmdbId, mediaType)
        }
        loadDashboard()
      }
    }
  }

  fun deleteRequest(
    requestId: Int,
    tmdbId: Int? = null,
    mediaType: String? = null,
  ) {
    viewModelScope.launch {
      val res = seerrRepository.deleteRequest(requestId)
      res.fold(
        onSuccess = {
          _uiState.update { state ->
            val curDetails = state.selectedMediaDetails
            val newDetails =
              if (tmdbId != null &&
                curDetails?.id == tmdbId
              ) {
                curDetails.copy(mediaInfo = null)
              } else {
                curDetails
              }
            val curSearchItem = state.selectedSearchItem
            val newSearchItem =
              if (tmdbId != null &&
                curSearchItem?.id == tmdbId
              ) {
                curSearchItem.copy(mediaInfo = null)
              } else {
                curSearchItem
              }

            state.copy(
              activeRequests = state.activeRequests.filter { it.id != requestId },
              availableRequests = state.availableRequests.filter { it.id != requestId },
              selectedMediaDetails = newDetails,
              selectedSearchItem = newSearchItem,
              actionMessage = "Request deleted",
            )
          }
          if (tmdbId != null && mediaType != null) {
            val mType = MediaType.fromApiString(mediaType)
            seerrRepository.clearCacheForMedia(mediaType, tmdbId)
            refreshDetailSilently(tmdbId, mType)
          }
          loadDashboard()
        },
        onFailure = { err ->
          _uiState.update { it.copy(actionMessage = "Failed to delete request: ${err.message}") }
        },
      )
    }
  }

  fun deleteMedia(
    mediaId: Int,
    tmdbId: Int? = null,
    mediaType: String? = null,
  ) {
    viewModelScope.launch {
      val res = seerrRepository.deleteMedia(mediaId)
      res.fold(
        onSuccess = {
          _uiState.update { state ->
            val curDetails = state.selectedMediaDetails
            val newDetails =
              if (tmdbId != null &&
                curDetails?.id == tmdbId
              ) {
                curDetails.copy(mediaInfo = null)
              } else {
                curDetails
              }
            val curSearchItem = state.selectedSearchItem
            val newSearchItem =
              if (tmdbId != null &&
                curSearchItem?.id == tmdbId
              ) {
                curSearchItem.copy(mediaInfo = null)
              } else {
                curSearchItem
              }

            state.copy(
              activeRequests = state.activeRequests.filter { it.media.id != mediaId },
              availableRequests = state.availableRequests.filter { it.media.id != mediaId },
              selectedMediaDetails = newDetails,
              selectedSearchItem = newSearchItem,
              actionMessage = "Media deleted from Seerr",
            )
          }
          if (tmdbId != null && mediaType != null) {
            val mType = MediaType.fromApiString(mediaType)
            seerrRepository.clearCacheForMedia(mediaType, tmdbId)
            refreshDetailSilently(tmdbId, mType)
          }
          loadDashboard()
        },
        onFailure = { err ->
          _uiState.update { it.copy(actionMessage = "Failed to delete media: ${err.message}") }
        },
      )
    }
  }

  fun clearActionMessage() {
    _uiState.update { it.copy(actionMessage = null) }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SeerrViewModel(application) as T
      }
  }
}
