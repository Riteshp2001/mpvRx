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
import app.gyrolet.mpvrx.domain.network.isNetworkImageFile
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.player.NetworkPlaybackSource
import app.gyrolet.mpvrx.ui.player.PlaybackItem
import app.gyrolet.mpvrx.ui.player.PreparedPlaybackLaunchStore
import app.gyrolet.mpvrx.ui.player.PlayerActivity
import app.gyrolet.mpvrx.utils.media.M3UParseResult
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.M3UPlaylistItem
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

private val NETWORK_M3U_MIME_TYPES =
  setOf(
    "application/x-mpegurl",
    "application/vnd.apple.mpegurl",
    "audio/x-mpegurl",
    "audio/mpegurl",
  )

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
  private val browserPreferences: BrowserPreferences by inject()
  private val playerPreferences: PlayerPreferences by inject()

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
  private var loadJob: kotlinx.coroutines.Job? = null
  private val loadGeneration = java.util.concurrent.atomic.AtomicLong()

  /**
   * Load files in the current directory
   */
  fun loadFiles() {
    val generation = loadGeneration.incrementAndGet()
    loadJob?.cancel()
    val previous = _files.value
    loadJob = viewModelScope.launch {
      _isLoading.value = _files.value.isEmpty()
      _error.value = null

      try {
        val connection =
          repository.getConnectionById(connectionId)
            ?: throw Exception("Connection not found")
        _connection.value = connection

        repository
          .listFiles(connection, currentPath, onSnapshot = { partial ->
            withContext(Dispatchers.Main.immediate) {
              ensureActive()
              if (generation == loadGeneration.get() && partial.isNotEmpty()) {
                val paths = partial.mapTo(HashSet()) { it.path }
                _files.value = partial + previous.filterNot { it.path in paths }
                _isLoading.value = false
              }
            }
          })
          .onSuccess { fileList ->
            ensureActive()
            if (generation != loadGeneration.get()) return@onSuccess
            _files.value =
              // A stable base order for consumers that do not re-sort. Display order is applied in
              // NetworkBrowserScreen and the playback queue re-sorts in
              // currentDirectoryPlayableFiles, so the preference-based sort that used to run here
              // was always overwritten.
              fileList.sortedWith(
                compareBy<NetworkFile> { !it.isDirectory }.thenBy { it.name.lowercase() },
              )
          }.onFailure { e ->
            if (e is CancellationException) throw e
            ensureActive()
            _error.value = e.message ?: "Unknown error"
          }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        ensureActive()
        _error.value = e.message ?: "Unknown error"
      } finally {
        if (generation == loadGeneration.get()) _isLoading.value = false
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

        if (file.isNetworkPlaylistFile()) {
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
    val launchToken = PreparedPlaybackLaunchStore.stage(
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
    intent.putExtra(PlayerActivity.EXTRA_PREPARED_PLAYBACK_TOKEN, launchToken)
    intent.putExtra("title", file.name)
    intent.putExtra("filename", file.name)
    intent.putExtra("network_file_path", file.path)
    intent.putExtra("network_connection_id", connectionId)
    intent.putExtra("playlist_index", playlistIndex)
    // A "video/*" fallback for audio would suppress the audio-only player UI and prefs.
    val fallbackMimeType = if (file.isPlayableNetworkAudio()) "audio/*" else "video/*"
    intent.setDataAndType(uri, file.mimeType ?: fallbackMimeType)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    application.startActivity(intent)
  }

  private fun currentDirectoryPlayableFiles(clickedFile: NetworkFile): List<NetworkFile> {
    val includeAudio = browserPreferences.includeAudioBrowser.get()
    if (!playerPreferences.playlistMode.get() || !clickedFile.isPlayableNetworkMedia(includeAudio)) {
      return listOf(clickedFile)
    }

    val files =
      _files.value
        .filter { it.isPlayableNetworkMedia(includeAudio) }
        .sortedForNetworkBrowser(
          sortType = browserPreferences.networkSortType.get(),
          sortOrder = browserPreferences.networkSortOrder.get(),
        )

    return if (files.any { it.path == clickedFile.path }) {
      files
    } else {
      (files + clickedFile).distinctBy(NetworkFile::path)
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
          NetworkProtocol.SFTP -> "sftp"
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
      "sftp" -> 22
      "https" -> 443
      else -> 80
    }

  companion object {
    private const val TAG = "NetworkBrowserVM"

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

internal fun NetworkFile.isPlayableNetworkVideo(): Boolean {
  if (isDirectory || isNetworkPlaylistFile()) return false
  if (mimeType?.startsWith("video/", ignoreCase = true) == true) return true

  val cleanName = name.substringBefore('?').substringBefore('#')
  val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
  return extension in FileTypeUtils.VIDEO_EXTENSIONS
}

internal fun NetworkFile.isPlayableNetworkAudio(): Boolean {
  if (isDirectory || isNetworkPlaylistFile()) return false
  if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true

  val cleanName = name.substringBefore('?').substringBefore('#')
  val extension = cleanName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
  return extension in FileTypeUtils.AUDIO_EXTENSIONS
}

internal fun NetworkFile.isPlayableNetworkMedia(includeAudio: Boolean): Boolean =
  isPlayableNetworkVideo() || (includeAudio && isPlayableNetworkAudio())

internal fun NetworkFile.isNetworkPlaylistFile(): Boolean {
  val lowerName = name.lowercase()
  val lowerPath = path.substringBefore('?').substringBefore('#').lowercase()
  return lowerName.endsWith(".m3u") ||
    lowerName.endsWith(".m3u8") ||
    lowerPath.endsWith(".m3u") ||
    lowerPath.endsWith(".m3u8") ||
    mimeType in NETWORK_M3U_MIME_TYPES
}

internal fun List<NetworkFile>.sortedForNetworkBrowser(
  sortType: NetworkSortType,
  sortOrder: SortOrder,
): List<NetworkFile> {
  val directories = filter(NetworkFile::isDirectory)
  val images = filter { it.isNetworkImageFile() }
  val media = filter { !it.isDirectory && !it.isNetworkImageFile() }

  fun List<NetworkFile>.sortedGroup(): List<NetworkFile> =
    when (sortType) {
      NetworkSortType.Title ->
        if (sortOrder.isAscending) sortedBy { it.name.lowercase() } else sortedByDescending { it.name.lowercase() }
      NetworkSortType.Date ->
        if (sortOrder.isAscending) sortedBy(NetworkFile::lastModified) else sortedByDescending(NetworkFile::lastModified)
      NetworkSortType.Size ->
        if (sortOrder.isAscending) sortedBy(NetworkFile::size) else sortedByDescending(NetworkFile::size)
    }

  return directories.sortedGroup() + media.sortedGroup() + images.sortedGroup()
}
