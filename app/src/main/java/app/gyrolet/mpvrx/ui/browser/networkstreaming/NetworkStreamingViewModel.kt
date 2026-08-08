/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * ViewModel for managing network connections
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkStreamingViewModel(
  application: Application,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val streamEntryRepository: NetworkStreamEntryRepository by inject()

  /**
   * Observable list of all saved network connections
   */
  val connections: StateFlow<List<NetworkConnection>> =
    repository
      .getAllConnections()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  /**
   * Observable connection statuses
   */
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = repository.connectionStatuses

  val recentLinks: StateFlow<List<NetworkStreamEntryEntity>> =
    streamEntryRepository
      .observeRecentNormalEntries()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  val torrentFiles: StateFlow<List<NetworkStreamEntryEntity>> =
    streamEntryRepository
      .observeTorrentFileEntries()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
      )

  fun recordSubmittedLink(url: String) {
    val source = url.trim()
    if (source.isBlank() || isTorrentSource(source) || !MediaUtils.isURLValid(source)) return
    viewModelScope.launch {
      streamEntryRepository.saveNormalEntry(
        canonicalSourceUri = source,
        fileName = displayNameFor(source),
      )
    }
  }

  fun deleteStreamEntry(stableKey: String) {
    viewModelScope.launch { streamEntryRepository.delete(stableKey) }
  }

  /**
   * Add a new network connection
   */
  fun addConnection(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.addConnection(connection)
    }
  }

  /**
   * Update an existing connection
   */
  fun updateConnection(
    connection: NetworkConnection,
    clearPassword: Boolean = false,
  ) {
    viewModelScope.launch {
      repository.updateConnection(connection, clearPassword)
    }
  }

  /**
   * Delete a connection
   */
  fun deleteConnection(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.deleteConnection(connection)
    }
  }

  /**
   * Connect to a network share
   */
  fun connect(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.connect(connection)
    }
  }

  /**
   * Disconnect from a network share
   */
  fun disconnect(connection: NetworkConnection) {
    viewModelScope.launch {
      repository.disconnect(connection)
    }
  }

  override fun onCleared() {
    super.onCleared()
    // Clean up all connections when ViewModel is destroyed
    viewModelScope.launch {
      repository.disconnectAll()
    }
  }

  companion object {
    private fun displayNameFor(source: String): String =
      runCatching {
        val uri = android.net.Uri.parse(source)
        uri.lastPathSegment
          ?.substringAfterLast('/')
          ?.takeIf { it.isNotBlank() }
          ?: uri.host?.takeIf { it.isNotBlank() }
          ?: source
      }.getOrDefault(source)

    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkStreamingViewModel(application)
        }
      }
  }
}
