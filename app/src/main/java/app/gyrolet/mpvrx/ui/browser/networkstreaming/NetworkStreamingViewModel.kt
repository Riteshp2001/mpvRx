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
import app.gyrolet.mpvrx.domain.torrent.parseMagnet
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class TorrentStreamGroup(
  val id: String,
  val infoHash: String?,
  val title: String,
  val canonicalSourceUri: String,
  val files: List<NetworkStreamEntryEntity>,
  val totalSize: Long,
  val updatedAt: Long,
)

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

  val torrentGroups: StateFlow<List<TorrentStreamGroup>> =
    torrentFiles
      .map(::groupTorrentFiles)
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

  fun deleteTorrentGroup(group: TorrentStreamGroup) {
    viewModelScope.launch {
      val infoHash = group.infoHash
      if (infoHash != null) {
        streamEntryRepository.deleteTorrentGroup(infoHash)
      } else {
        group.files.forEach { streamEntryRepository.delete(it.stableKey) }
      }
    }
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
    private fun groupTorrentFiles(entries: List<NetworkStreamEntryEntity>): List<TorrentStreamGroup> =
      entries
        .groupBy { entry -> entry.infoHash?.trim()?.lowercase() ?: "source:${entry.canonicalSourceUri}" }
        .map { (groupKey, groupEntries) ->
          val files =
            groupEntries.sortedWith(
              compareBy<NetworkStreamEntryEntity> { it.fileIndex ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.fileName },
            )
          val newestEntry = groupEntries.maxBy { it.updatedAt }
          val infoHash = newestEntry.infoHash?.trim()?.lowercase()?.takeIf(String::isNotEmpty)
          TorrentStreamGroup(
            id = infoHash ?: groupKey,
            infoHash = infoHash,
            title = torrentGroupTitle(newestEntry.canonicalSourceUri, infoHash, files),
            canonicalSourceUri = newestEntry.canonicalSourceUri,
            files = files,
            totalSize = files.fold(0L) { total, file -> safeAdd(total, file.fileSize.coerceAtLeast(0L)) },
            updatedAt = groupEntries.maxOf { it.updatedAt },
          )
        }
        .sortedWith(
          compareByDescending<TorrentStreamGroup> { it.updatedAt }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
        )

    private fun torrentGroupTitle(
      source: String,
      infoHash: String?,
      files: List<NetworkStreamEntryEntity>,
    ): String {
      runCatching { parseMagnet(source)?.displayName?.trim() }
        .getOrNull()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

      commonRoot(files)?.let { return it }

      runCatching {
        android.net.Uri.parse(source).lastPathSegment
          ?.substringAfterLast('/')
          ?.removeSuffix(".torrent")
          ?.trim()
      }.getOrNull()?.takeIf { it.isNotEmpty() && !it.startsWith("magnet:", ignoreCase = true) }?.let { return it }

      files.singleOrNull()?.fileName
        ?.substringBeforeLast('.', missingDelimiterValue = files.single().fileName)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

      return infoHash?.take(8)?.uppercase()?.let { "Torrent $it" } ?: "Torrent"
    }

    private fun commonRoot(files: List<NetworkStreamEntryEntity>): String? {
      val paths =
        files.map { entry ->
          entry.filePath
            .orEmpty()
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter(String::isNotBlank)
        }
      if (paths.isEmpty() || paths.any { it.isEmpty() }) return null
      val candidate = paths.first().firstOrNull() ?: return null
      return candidate.takeIf { root ->
        paths.all { path -> path.size > 1 && path.first().equals(root, ignoreCase = true) }
      }
    }

    private fun safeAdd(
      first: Long,
      second: Long,
    ): Long = if (Long.MAX_VALUE - first < second) Long.MAX_VALUE else first + second

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
