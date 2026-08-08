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
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.NetworkPlaybackSource
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URI

/**
 * ViewModel for browsing files on a network share
 * Follows MVVM pattern with proper separation of concerns
 */
class NetworkBrowserViewModel(
  private val application: Application,
  private val connectionId: Long,
  private val currentPath: String,
) : AndroidViewModel(application),
  KoinComponent {
  private val repository: NetworkRepository by inject()
  private val playlistRepository: PlaylistRepository by inject()

  private val _files = MutableStateFlow<List<NetworkFile>>(emptyList())
  val files: StateFlow<List<NetworkFile>> = _files.asStateFlow()

  private val _connection = MutableStateFlow<NetworkConnection?>(null)
  val connection: StateFlow<NetworkConnection?> = _connection.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  private val _importedPlaylistId = MutableSharedFlow<Int>()
  val importedPlaylistId: SharedFlow<Int> = _importedPlaylistId.asSharedFlow()

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    viewModelScope.launch {
      _isLoading.value = true
      _error.value = null

      try {
        val connection =
          repository.getConnectionById(connectionId)
            ?: throw Exception("Connection not found")
        _connection.value = connection

        repository
          .listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _files.value =
              fileList.sortedWith(
                compareBy<NetworkFile> { !it.isDirectory }
                  .thenBy { it.name.lowercase() },
              )
          }.onFailure { e ->
            _error.value = e.message ?: "Unknown error"
          }
      } catch (e: Exception) {
        _error.value = e.message ?: "Unknown error"
      } finally {
        _isLoading.value = false
      }
    }
  }

  /**
   * Play a video file
   */
  fun openMedia(file: NetworkFile) {
    viewModelScope.launch {
      try {
        val connection =
          repository.getConnectionById(connectionId)
            ?: throw Exception("Connection not found")

        if (isM3uFile(file)) {
          openM3uFile(connection, file)
        } else {
          playVideoInternal(connection, file)
        }
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Log.e(TAG, "Error opening network media", e)
        _error.value = e.message ?: "Unknown error"
      }
    }
  }

  /**
   * Play a video file
   */
  fun playVideo(file: NetworkFile) {
    viewModelScope.launch {
      try {
        val connection =
          repository.getConnectionById(connectionId)
            ?: throw Exception("Connection not found")
        playVideoInternal(connection, file)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (e: Exception) {
        Log.e(TAG, "Error playing video", e)
        _error.value = e.message ?: "Unknown error"
      }
    }
  }

  private suspend fun openM3uFile(
    connection: NetworkConnection,
    file: NetworkFile,
  ) {
    val client = repository.createClient(connection.id).getOrThrow()
    val sourceUrl = NetworkPlaybackUri.create(connection.id, file.path)
    val parseResult =
      try {
        client.connect().getOrThrow()
        M3UParser.parseFromStream(
          inputStream = client.getFileStream(file.path).getOrThrow(),
          sourceUrl = sourceUrl,
          overridePlaylistName = file.name,
        )
      } finally {
        withContext(NonCancellable) { client.disconnect() }
      }

    if (M3UParser.shouldPlayHlsDirectly(parseResult)) {
      playVideoInternal(connection, file)
      return
    }

    val parsed =
      parseResult as? M3UParseResult.Success
        ?: throw IllegalArgumentException((parseResult as M3UParseResult.Error).message)
    val persistentResult =
      parsed.copy(items = parsed.items.map { item -> item.forSavedConnection(connection) })

    val playlistId =
      playlistRepository
        .createM3UPlaylistFromParsed(
          parseResult = persistentResult,
          sourceName = file.name,
        ).getOrElse { e ->
          Log.e(TAG, "Failed to create playlist from M3U content", e)
          _error.value = "Failed to import playlist: ${e.message}"
          return
        }
    _importedPlaylistId.emit(playlistId.toInt())
  }

  private fun playVideoInternal(
    connection: NetworkConnection,
    file: NetworkFile,
  ) {
    val playableFiles = currentDirectoryPlayableFiles(file)
    val playlistIndex =
      playableFiles
        .indexOfFirst { it.path == file.path }
        .takeIf { it >= 0 }
        ?: 0
    val queueItems =
      playableFiles.map { networkFile ->
        val networkUri = NetworkPlaybackUri.create(connection.id, networkFile.path)
        PlaybackItem.fromUri(
          uri = networkUri,
          title = networkFile.name,
          mimeType = networkFile.mimeType,
          networkSource = NetworkPlaybackSource(connection.id, networkFile.path),
        )
      }
    PlaybackSession.replaceQueue(
      items = queueItems,
      currentIndex = playlistIndex,
      isExplicitQueue = true,
    )
    val uri = Uri.parse(queueItems[playlistIndex].originalUri)

    val intent = Intent(Intent.ACTION_VIEW, uri)
    intent.setClass(application, PlayerActivity::class.java)
    intent.putExtra("internal_launch", true)
    intent.putExtra("launch_source", "network_stream")
    intent.putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_QUEUE, true)
    intent.putExtra("title", file.name)
    intent.putExtra("filename", file.name)
    intent.putExtra("network_file_path", file.path)
    intent.putExtra("network_connection_id", connectionId)
    intent.putExtra("playlist_index", playlistIndex)
    intent.setDataAndType(uri, file.mimeType ?: "video/*")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    application.startActivity(intent)
  }

  private fun currentDirectoryPlayableFiles(clickedFile: NetworkFile): List<NetworkFile> {
    val files =
      _files.value
        .filter { it.isPlayableVideoFile() }

    return if (files.any { it.path == clickedFile.path }) {
      files
    } else {
      listOf(clickedFile)
    }
  }

  private fun M3UPlaylistItem.forSavedConnection(connection: NetworkConnection): M3UPlaylistItem {
    val resource = url.substringBefore('|')
    NetworkPlaybackUri.parse(resource)?.let { reference ->
      if (reference.connectionId == connection.id) {
        return copy(url = NetworkPlaybackUri.create(connection.id, reference.path.value))
      }
    }

    val absolutePath = absoluteConnectionPath(resource, connection) ?: return copy(url = M3UParser.sanitizeSourceUrl(url))
    return copy(url = NetworkPlaybackUri.create(connection.id, absolutePath.value))
  }

  private fun absoluteConnectionPath(
    rawUri: String,
    connection: NetworkConnection,
  ): NetworkPath? =
    runCatching {
      val uri = URI(rawUri)
      val expectedScheme =
        when (connection.protocol) {
          NetworkProtocol.SMB -> "smb"
          NetworkProtocol.FTP -> "ftp"
          NetworkProtocol.WEBDAV -> if (connection.useHttps) "https" else "http"
        }
      if (!uri.scheme.equals(expectedScheme, ignoreCase = true) ||
        !uri.host.equals(connection.host.trim('[', ']'), ignoreCase = true) ||
        uri.rawQuery != null ||
        uri.rawFragment != null
      ) {
        return@runCatching null
      }

      val actualPort = uri.port.takeIf { it >= 0 } ?: defaultPort(expectedScheme)
      if (actualPort != connection.port) return@runCatching null

      val root = NetworkPath.from(connection.path).segments
      val fullPath = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
      val matchesRoot =
        root.indices.all { index ->
          val actual = fullPath.getOrNull(index) ?: return@all false
          if (connection.protocol == NetworkProtocol.SMB) actual.equals(root[index], ignoreCase = true) else actual == root[index]
        }
      if (!matchesRoot) return@runCatching null
      NetworkPath.from(fullPath.drop(root.size).joinToString("/"))
    }.getOrNull()

  private fun defaultPort(scheme: String): Int =
    when (scheme.lowercase()) {
      "smb" -> 445
      "ftp" -> 21
      "https" -> 443
      else -> 80
    }

  private fun NetworkFile.isPlayableVideoFile(): Boolean {
    if (isDirectory || isM3uFile(this)) {
      return false
    }

    val mime = mimeType?.lowercase()
    if (mime?.startsWith("video/") == true) {
      return true
    }

    val cleanName = name.substringBefore('?').substringBefore('#')
    val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in FileTypeUtils.VIDEO_EXTENSIONS
  }

  private fun isM3uFile(file: NetworkFile): Boolean {
    val lowerName = file.name.lowercase()
    val lowerPath = file.path.substringBefore('?').lowercase()
    return lowerName.endsWith(".m3u") ||
      lowerName.endsWith(".m3u8") ||
      lowerPath.endsWith(".m3u") ||
      lowerPath.endsWith(".m3u8") ||
      file.mimeType in M3U_MIME_TYPES
  }

  companion object {
    private const val TAG = "NetworkBrowserVM"

    private val M3U_MIME_TYPES =
      setOf(
        "application/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "audio/x-mpegurl",
        "audio/mpegurl",
      )

    fun factory(
      application: Application,
      connectionId: Long,
      currentPath: String,
    ): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          NetworkBrowserViewModel(application, connectionId, currentPath)
        }
      }
  }
}
