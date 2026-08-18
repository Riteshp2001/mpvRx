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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinAuthMode
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortBy
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinSortOrder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.PlaybackSubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class JellyfinBreadcrumb(
  val id: String,
  val title: String,
  val type: String = "Folder",
)

data class JellyfinUiState(
  val servers: List<JellyfinServer> = emptyList(),
  val activeServer: JellyfinServer? = null,
  val libraries: List<JellyfinItem> = emptyList(),
  val resumeItems: List<JellyfinItem> = emptyList(),
  val currentItems: List<JellyfinItem> = emptyList(),
  val breadcrumbs: List<JellyfinBreadcrumb> = emptyList(),
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
)

class JellyfinViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val jellyfinRepository: JellyfinRepository by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

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
        if (currentActive != null && _uiState.value.libraries.isEmpty()) {
          loadLibraries(currentActive)
        }
      }
    }
  }

  fun selectServer(server: JellyfinServer) {
    _uiState.update {
      it.copy(
        activeServer = server,
        breadcrumbs = emptyList(),
        currentItems = emptyList(),
        resumeItems = emptyList(),
        searchQuery = "",
        error = null,
      )
    }
    loadLibraries(server)
  }

  fun refresh() {
    val active = _uiState.value.activeServer ?: return
    val currentCrumb = _uiState.value.breadcrumbs.lastOrNull()
    if (currentCrumb == null) {
      loadLibraries(active)
    } else {
      loadItems(active, currentCrumb.id, currentCrumb.type, resetPagination = true)
    }
  }

  fun loadLibraries(server: JellyfinServer) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, error = null) }
      loadResumeItems(server)
      val result = jellyfinRepository.getLibraries(server)
      result
        .onSuccess { libs ->
          _uiState.update { it.copy(libraries = libs, isLoading = false, error = null) }
        }.onFailure { err ->
          _uiState.update { it.copy(isLoading = false, error = err.message ?: "Failed to load libraries") }
        }
    }
  }

  fun loadResumeItems(server: JellyfinServer) {
    viewModelScope.launch {
      val result = jellyfinRepository.getResumeItems(server, limit = 12)
      result.onSuccess { resumeList ->
        _uiState.update { it.copy(resumeItems = resumeList) }
      }
    }
  }

  fun setSort(
    sortBy: JellyfinSortBy,
    sortOrder: JellyfinSortOrder,
  ) {
    _uiState.update { it.copy(sortBy = sortBy, sortOrder = sortOrder) }
    val active = _uiState.value.activeServer ?: return
    val currentCrumb = _uiState.value.breadcrumbs.lastOrNull() ?: return
    loadItems(active, currentCrumb.id, currentCrumb.type, resetPagination = true)
  }

  fun toggleUnplayedOnly() {
    val newFilter = !_uiState.value.isUnplayedOnly
    _uiState.update { it.copy(isUnplayedOnly = newFilter) }
    val active = _uiState.value.activeServer ?: return
    val currentCrumb = _uiState.value.breadcrumbs.lastOrNull() ?: return
    loadItems(active, currentCrumb.id, currentCrumb.type, resetPagination = true)
  }

  fun navigateToItem(item: JellyfinItem) {
    val active = _uiState.value.activeServer ?: return
    if (item.isFolder || item.isSeries || item.isSeason) {
      val newCrumb = JellyfinBreadcrumb(id = item.id, title = item.name, type = item.type)
      _uiState.update {
        it.copy(
          breadcrumbs = it.breadcrumbs + newCrumb,
          searchQuery = "",
        )
      }
      loadItems(active, item.id, item.type, resetPagination = true)
    }
  }

  fun navigateBack(): Boolean {
    val currentCrumbs = _uiState.value.breadcrumbs
    if (currentCrumbs.isEmpty()) return false

    val updatedCrumbs = currentCrumbs.dropLast(1)
    _uiState.update {
      it.copy(
        breadcrumbs = updatedCrumbs,
        searchQuery = "",
      )
    }
    val active = _uiState.value.activeServer ?: return true
    val parent = updatedCrumbs.lastOrNull()
    if (parent == null) {
      loadLibraries(active)
    } else {
      loadItems(active, parent.id, parent.type, resetPagination = true)
    }
    return true
  }

  private fun loadItems(
    server: JellyfinServer,
    parentId: String,
    parentType: String,
    resetPagination: Boolean = true,
  ) {
    val startIndex = if (resetPagination) 0 else _uiState.value.startIndex
    val currentList = if (resetPagination) emptyList() else _uiState.value.currentItems

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

      when (parentType) {
        "Series" -> {
          val result = jellyfinRepository.getSeasons(server, parentId)
          result
            .onSuccess { items ->
              _uiState.update {
                it.copy(
                  currentItems = items,
                  totalRecordCount = items.size,
                  hasMore = false,
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
                  error = err.message ?: "Failed to load seasons",
                )
              }
            }
        }

        "Season" -> {
          val seriesId = _uiState.value.breadcrumbs.dropLast(1).lastOrNull { it.type == "Series" }?.id ?: parentId
          val result = jellyfinRepository.getEpisodes(server, seriesId, parentId)
          result
            .onSuccess { items ->
              _uiState.update {
                it.copy(
                  currentItems = items,
                  totalRecordCount = items.size,
                  hasMore = false,
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
                  error = err.message ?: "Failed to load episodes",
                )
              }
            }
        }

        else -> {
          val result =
            jellyfinRepository.getItems(
              server = server,
              parentId = parentId,
              searchTerm = currentState.searchQuery.takeIf { it.isNotBlank() },
              sortBy = currentState.sortBy,
              sortOrder = currentState.sortOrder,
              isPlayed = if (currentState.isUnplayedOnly) false else null,
              startIndex = startIndex,
              limit = 100,
            )

          result
            .onSuccess { queryResult ->
              val combined = currentList + queryResult.items
              val hasMore = combined.size < queryResult.totalRecordCount
              _uiState.update {
                it.copy(
                  currentItems = combined,
                  totalRecordCount = queryResult.totalRecordCount,
                  startIndex = combined.size,
                  hasMore = hasMore,
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
    }
  }

  fun loadMoreItems() {
    val state = _uiState.value
    if (state.isLoading || state.isLoadingMore || !state.hasMore) return
    val active = state.activeServer ?: return
    val currentCrumb = state.breadcrumbs.lastOrNull() ?: return
    loadItems(active, currentCrumb.id, currentCrumb.type, resetPagination = false)
  }

  fun onSearchQueryChanged(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
  }

  fun performSearch(query: String) {
    val active = _uiState.value.activeServer ?: return
    if (query.isBlank()) {
      refresh()
      return
    }
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, currentItems = emptyList(), startIndex = 0, error = null) }
      val currentCrumb = _uiState.value.breadcrumbs.lastOrNull()
      val result =
        jellyfinRepository.getItems(
          server = active,
          parentId = currentCrumb?.id,
          searchTerm = query,
          sortBy = _uiState.value.sortBy,
          sortOrder = _uiState.value.sortOrder,
          startIndex = 0,
          limit = 100,
        )
      result
        .onSuccess { queryResult ->
          _uiState.update {
            it.copy(
              currentItems = queryResult.items,
              totalRecordCount = queryResult.totalRecordCount,
              startIndex = queryResult.items.size,
              hasMore = queryResult.items.size < queryResult.totalRecordCount,
              isLoading = false,
            )
          }
        }.onFailure { err ->
          _uiState.update { it.copy(isLoading = false, error = err.message) }
        }
    }
  }

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
      val cleanUrl = JellyfinClient.normalizeUrl(serverUrl)

      try {
        val serverToSave =
          if (authMode == JellyfinAuthMode.CREDENTIALS) {
            val authResult =
              jellyfinRepository.authenticate(cleanUrl, username, password).getOrThrow()
            JellyfinServer(
              name = serverName.ifBlank { "Jellyfin (${authResult.username})" },
              serverUrl = cleanUrl,
              userId = authResult.userId,
              username = authResult.username,
              accessToken = authResult.accessToken,
              lastConnected = System.currentTimeMillis(),
            )
          } else {
            val user = jellyfinRepository.validateToken(cleanUrl, token).getOrThrow()
            JellyfinServer(
              name = serverName.ifBlank { "Jellyfin (${user.name})" },
              serverUrl = cleanUrl,
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
        loadLibraries(savedServer)
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
          breadcrumbs = emptyList(),
          currentItems = emptyList(),
          libraries = emptyList(),
          resumeItems = emptyList(),
        )
      }
      if (newActive != null) {
        loadLibraries(newActive)
      }
    }
  }

  fun playItem(
    context: Context,
    item: JellyfinItem,
  ) {
    val server = _uiState.value.activeServer ?: return
    val streamUrl = jellyfinRepository.getStreamUrl(server, item)
    val posterUrl = jellyfinRepository.getImageUrl(server, item)
    val backdropUrl = jellyfinRepository.getBackdropUrl(server, item)

    val itemTitle =
      when {
        item.seriesName != null && item.indexNumber != null -> "${item.seriesName} S${item.parentIndexNumber ?: 1}E${item.indexNumber} - ${item.name}"
        else -> item.name
      }

    viewModelScope.launch {
      // If Jellyfin has saved playback position, seed it into PlaybackState
      if (item.playbackPositionTicks != null && item.playbackPositionTicks > 0) {
        val positionSeconds = (item.playbackPositionTicks / JellyfinClient.TICKS_PER_SECOND).toInt()
        withContext(Dispatchers.IO) {
          runCatching {
            playbackStateRepository.upsert(
              PlaybackStateEntity(
                mediaTitle = streamUrl,
                lastPosition = positionSeconds,
                playbackSpeed = 1.0,
                videoZoom = 0f,
                sid = -1,
                secondarySid = -1,
                subDelay = 0,
                subSpeed = 1.0,
                aid = -1,
                audioDelay = 0,
                timeRemaining = (item.durationSeconds - positionSeconds).toInt().coerceAtLeast(0),
              ),
            )
          }
        }
      }

      // Fire scrobble start asynchronously
      jellyfinRepository.reportPlaybackStart(
        serverUrl = server.serverUrl,
        token = server.accessToken,
        itemId = item.id,
        positionTicks = item.playbackPositionTicks ?: 0L,
      )

      val externalSubs: List<PlaybackSubtitleTrack> =
        jellyfinRepository
          .getSubtitleTracks(server = server, itemId = item.id)
          .getOrDefault(emptyList())

      MediaUtils.playFile(
        source = streamUrl,
        context = context,
        launchSource = "jellyfin_stream",
        title = itemTitle,
        mediaDescription = item.overview,
        posterUrl = posterUrl,
        backdropUrl = backdropUrl,
        subtitleTracks = externalSubs,
      )
    }
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
