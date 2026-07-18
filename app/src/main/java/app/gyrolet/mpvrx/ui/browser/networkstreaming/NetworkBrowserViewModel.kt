package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.data.network.client.NetworkClientFactory
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")
        _connection.value = connection

        repository.listFiles(connection, currentPath)
          .onSuccess { fileList ->
            _files.value = fileList.sortedWith(
              compareBy<NetworkFile> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
            )
          }
          .onFailure { e ->
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
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")

        if (isM3uFile(file)) {
          openM3uFile(connection, file)
        } else {
          playVideoInternal(connection, file)
        }
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
        val connection = repository.getConnectionById(connectionId)
          ?: throw Exception("Connection not found")
        playVideoInternal(connection, file)
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
    val client = NetworkClientFactory.createClient(connection)
    val content =
      try {
        client.connect().getOrThrow()
        client.getFileStream(file.path).getOrThrow().bufferedReader(Charsets.UTF_8).use { it.readText() }
      } finally {
        client.disconnect()
      }

    if (M3UParser.isLikelyHlsMediaManifest(content)) {
      playVideoInternal(connection, file)
      return
    }

    // Resolve the source URL that the M3U was loaded from.
    // This is essential for resolving relative paths inside the M3U
    // (e.g. "episode02.mkv" -> "davs://server/videos/episode02.mkv").
    // getFileUri() is tried first; if it fails we build a best-effort URL
    // from the connection's base URL + file path.
    val sourceUrl: String = NetworkClientFactory.createClient(connection)
      .getFileUri(file.path)
      .getOrNull()
      ?.toString()
      ?: buildFallbackSourceUrl(connection, file.path)

    Log.d(TAG, "Importing M3U playlist from: $sourceUrl")

    val playlistId = playlistRepository.createM3UPlaylistFromContent(
      content = content,
      sourceName = file.name,
      sourceUrl = sourceUrl,
    ).getOrElse { e ->
      Log.e(TAG, "Failed to create playlist from M3U content", e)
      _error.value = "Failed to import playlist: ${e.message}"
      return
    }
    _importedPlaylistId.emit(playlistId.toInt())
  }

  /**
   * Builds a best-effort URL for the given file path on a network connection.
   * Used as a fallback when [NetworkClientFactory] cannot produce a URI.
   */
  private fun buildFallbackSourceUrl(connection: NetworkConnection, filePath: String): String {
    val protocol = when (connection.protocol) {
      NetworkProtocol.WEBDAV -> if (connection.useHttps) "https" else "http"
      NetworkProtocol.FTP    -> "ftp"
      NetworkProtocol.SMB    -> "smb"
    }
    val defaultPort = connection.protocol.defaultPort
    val portStr = if (connection.port != defaultPort) ":${connection.port}" else ""
    val normalizedPath = if (filePath.startsWith("/")) filePath else "/$filePath"
    return "$protocol://${connection.host}$portStr$normalizedPath"
  }

  private fun playVideoInternal(
    connection: NetworkConnection,
    file: NetworkFile,
  ) {
    // Use proxy server for protocols that need seeking support
    val useProxy = connection.protocol in PROXY_PROTOCOLS

    val playableFiles = currentDirectoryPlayableFiles(file)
    val playlistIndex = playableFiles.indexOfFirst { it.path == file.path }
      .takeIf { it >= 0 }
      ?: 0
    val playlistUris = playableFiles.map { createPlayableNetworkUri(connection, it, useProxy) }
    val uri = playlistUris.getOrNull(playlistIndex)
      ?: createPlayableNetworkUri(connection, file, useProxy)

    // Launch the player
    val intent = Intent(Intent.ACTION_VIEW, uri)
    intent.setClass(application, app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
    intent.putExtra("internal_launch", true)
    intent.putExtra("launch_source", "network_stream")
    intent.putExtra("title", file.name)
    intent.putExtra("filename", file.name)
    // Pass the original network file path for stable media identifier (position saving)
    intent.putExtra("network_file_path", file.path)
    intent.putExtra("network_connection_id", connectionId)
    intent.setDataAndType(uri, file.mimeType ?: "video/*")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    if (playlistUris.size > 1) {
      intent.putParcelableArrayListExtra("playlist", ArrayList(playlistUris))
      intent.putExtra("playlist_index", playlistIndex)
      intent.putStringArrayListExtra("network_playlist_paths", ArrayList(playableFiles.map { it.path }))
      intent.putStringArrayListExtra("network_playlist_titles", ArrayList(playableFiles.map { it.name }))
      intent.putExtra("network_playlist_connection_id", connectionId)
    }

    if (!useProxy) {
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    application.startActivity(intent)
  }

  private fun currentDirectoryPlayableFiles(clickedFile: NetworkFile): List<NetworkFile> {
    val files = _files.value
      .filter { it.isPlayableVideoFile() }

    return if (files.any { it.path == clickedFile.path }) {
      files
    } else {
      listOf(clickedFile)
    }
  }

  private fun createPlayableNetworkUri(
    connection: NetworkConnection,
    file: NetworkFile,
    useProxy: Boolean,
  ): Uri {
    return if (useProxy) {
      val proxy = app.gyrolet.mpvrx.data.network.proxy.NetworkStreamingProxy.getInstance()
      val streamId = buildStableStreamId(file)
      val proxyUrl = proxy.registerStream(
        streamId = streamId,
        connection = connection,
        filePath = file.path,
        fileSize = file.size,
        mimeType = file.mimeType ?: "video/mp4",
      )
      Uri.parse(proxyUrl)
    } else {
      NetworkStreamingProvider.setConnection(connectionId, connection)
      NetworkStreamingProvider.getUri(application, connectionId, file.path)
    }
  }

  private fun buildStableStreamId(file: NetworkFile): String {
    val pathHash = Integer.toUnsignedString(file.path.hashCode(), 36)
    val sizeHash = Integer.toUnsignedString(file.size.hashCode(), 36)
    return "network_${connectionId}_${pathHash}_$sizeHash"
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

    // Protocols that require proxy server for seeking support
    private val PROXY_PROTOCOLS = setOf(
      NetworkProtocol.SMB,
      NetworkProtocol.FTP,
      NetworkProtocol.WEBDAV,
    )

    private val M3U_MIME_TYPES = setOf(
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

