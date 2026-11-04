/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.playlist

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.entities.PlaylistItemEntity
import app.gyrolet.mpvrx.database.repository.PlaylistItemInput
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import android.net.Uri
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.DlnaPath
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.repository.NetworkProbeResult
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.extractLocalPath
import app.gyrolet.mpvrx.ui.player.resolveUri
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

data class PlaylistVideoItem(
  val playlistItem: PlaylistItemEntity,
  val video: Video,
  val isNetwork: Boolean = false,
  /** Backing connection for network entries; null for local files. Drives the source warning dot. */
  val connectionId: Long? = null,
  /** Network transport of the backing connection; null means the entry is a local file. */
  val protocol: NetworkProtocol? = null,
  /** Display name of the backing connection; null for local files. */
  val connectionName: String? = null,
  /** Share-relative path for network entries, parent directory for local ones. */
  val sourcePath: String? = null,
  val isAvailable: Boolean = true,
  /** String resource appended to the item name when the entry cannot be played. Null while [isAvailable]. */
  @androidx.annotation.StringRes val unavailableReasonRes: Int? = null,
)

class PlaylistDetailViewModel(
  application: Application,
  private val playlistId: Int,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val playlistRepository: PlaylistRepository by inject()
  private val networkRepository: NetworkRepository by inject()
  // Using MediaFileRepository singleton directly

  private val _playlist = MutableStateFlow<PlaylistEntity?>(null)
  val playlist: StateFlow<PlaylistEntity?> = _playlist.asStateFlow()

  private val _videoItems = MutableStateFlow<List<PlaylistVideoItem>>(emptyList())
  val videoItems: StateFlow<List<PlaylistVideoItem>> = _videoItems.asStateFlow()

  private val _categories = MutableStateFlow<List<String>>(emptyList())
  val categories: StateFlow<List<String>> = _categories.asStateFlow()

  // Start as loading to avoid briefly showing "No videos in playlist" before the first DB emission arrives,
  // especially noticeable for very large playlists.
  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  /**
   * Live per-connection status, read straight from [NetworkRepository] so the playlist's source
   * dots always agree with what the Network tab shows for the same connection.
   */
  val connectionStatuses: StateFlow<Map<Long, ConnectionStatus>> = networkRepository.connectionStatuses

  companion object {
    private const val TAG = "PlaylistDetailViewModel"

    /** Upper bound on the pre-playback reachability probe; see [probeIfNetwork]. */
    private const val NETWORK_PROBE_TIMEOUT_MS = 6_000L

    fun factory(
      application: Application,
      playlistId: Int,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PlaylistDetailViewModel(application, playlistId) as T
    }
  }

  init {
    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.observeDistinctCategories(playlistId).collectLatest { categories ->
        _categories.value = categories
      }
    }

    // Observe playlist items and load video metadata
    viewModelScope.launch(Dispatchers.IO) {
      combine(
        playlistRepository.observePlaylistItems(playlistId),
        // Connections are part of the input: deleting or re-creating one must re-evaluate every
        // entry that references it instead of waiting for the playlist itself to be reloaded.
        networkRepository.observeAllConnectionsIncludingDeleted(),
        playlistRepository.observePlaylistById(playlistId),
      ) { items, connections, playlist -> Triple(items, connections, playlist) }
        .collectLatest { (items, connections, playlist) ->
        _playlist.value = playlist
        _isLoading.value = true
        try {
          _videoItems.value = buildVideoItems(playlist, items, connections)
        } finally {
          _isLoading.value = false
        }
      }
    }
  }

  /** Initial loading and refresh must resolve identical URIs and metadata for the view chips. */
  private suspend fun buildVideoItems(
    playlist: PlaylistEntity?,
    items: List<PlaylistItemEntity>,
    connections: List<NetworkConnection>,
  ): List<PlaylistVideoItem> {
    if (items.isEmpty()) return emptyList()
    if (playlist?.isM3uPlaylist == true) return buildM3UVideoItems(playlist, items)

    val networkRefs = items.map { NetworkPlaybackUri.parse(it.filePath) }
    val archiveSources = items.map { ZipArchiveMedia.sourceOf(it.filePath) }
    val availableArchives = archiveSources.filterNotNull().distinct()
      .associateWith { ZipArchiveMedia.sourceAvailable(getApplication(), it) }
    val connectionsById = connections.associateBy { it.id }

    // For regular playlists, use the existing logic with MediaFileRepository
    val fileObjects =
      items.map { item ->
        when {
          item.filePath.startsWith("content://") || item.filePath.startsWith("file://") -> {
            val uri = Uri.parse(item.filePath)
            val resolved = uri.resolveUri(getApplication(), allowFdFallback = false)
            if (!resolved.isNullOrBlank()) File(resolved) else File(uri.path ?: item.filePath)
          }
          else -> File(item.filePath)
        }
      }

    // Get unique bucket IDs from local playlist items' parent folders.
    // Network entries are excluded: their paths are not filesystem paths and would
    // otherwise inject junk buckets into the MediaStore lookup.
    val bucketIds =
      items.indices
        .filter { networkRefs[it] == null && archiveSources[it] == null }
        .mapNotNull { fileObjects[it].parent }
        .filter { it.isNotBlank() }
        .toSet()

    // Get all videos and audio files from those folders (uses cache)
    val allVideos = MediaFileRepository.getVideosForBuckets(getApplication(), bucketIds, includeAudioOverride = true)

    // Match videos by path, maintaining playlist order
    return items.mapIndexedNotNull { index, item ->
      archiveSources[index]?.let { source ->
        return@mapIndexedNotNull buildArchiveVideoItem(item, source, availableArchives[source] == true)
      }
      val networkRef = networkRefs[index]
      if (networkRef != null) {
        return@mapIndexedNotNull buildNetworkVideoItem(item, networkRef, connectionsById[networkRef.connectionId])
      }

      val file = fileObjects.getOrNull(index) ?: File(item.filePath)
      val fileExists = file.exists()
      val isAudioFile = FileTypeUtils.isAudioFile(file)
      val matchedVideo =
        allVideos.find { video ->
          video.path == item.filePath ||
            video.path == file.absolutePath ||
            video.uri.toString() == item.filePath
        }
      val video = (matchedVideo?.let { if (isAudioFile && !it.isAudio) it.copy(isAudio = true) else it }) ?: run {
        if (fileExists) {
          MediaFileRepository.getVideosFromFiles(getApplication(), listOf(file)).firstOrNull()?.let {
            if (isAudioFile && !it.isAudio) it.copy(isAudio = true) else it
          }
        } else {
          null
        }
      } ?: run {
        val rawUri =
          when {
            item.filePath.startsWith("content://") || item.filePath.startsWith("http://") || item.filePath.startsWith("https://") -> Uri.parse(item.filePath)
            fileExists -> Uri.fromFile(file)
            else -> Uri.parse(item.filePath)
          }
        Video(
          id = item.id.toLong(),
          title = item.fileName,
          displayName = item.fileName,
          path = if (fileExists) file.absolutePath else item.filePath,
          uri = rawUri,
          duration = 0L,
          durationFormatted = "--",
          size = if (fileExists) file.length() else 0L,
          sizeFormatted = if (fileExists) MediaUtils.formatFileSize(file.length()) else "--",
          dateModified = if (fileExists) file.lastModified() / 1000L else item.addedAt / 1000L,
          dateAdded = item.addedAt / 1000L,
          mimeType = if (isAudioFile) "audio/*" else "video/*",
          bucketId = file.parent ?: "",
          bucketDisplayName = file.parentFile?.name ?: "",
          width = 0,
          height = 0,
          fps = 0f,
          resolution = "--",
          isAudio = isAudioFile,
        )
      }
      val available = matchedVideo != null || isLocalEntryAvailable(item.filePath, fileExists)
      PlaylistVideoItem(
        playlistItem = item,
        video = video,
        sourcePath = file.parent,
        isAvailable = available,
        unavailableReasonRes = if (available) null else R.string.playlist_unavailable_file,
      )
    }
  }

  override fun refresh() {
    // Refresh is handled automatically through Flow observation
    viewModelScope.launch(Dispatchers.IO) { refreshNow() }
  }

  /**
   * Refresh playlist items and associated [Video] metadata, awaiting completion.
   *
   * This is useful for UI gestures like pull-to-refresh that need to know when refreshing is done.
   */
  suspend fun refreshNow() = withContext(Dispatchers.IO) {
    try {
      _isLoading.value = true
      playlistRepository.removeDeletedZipPlaylists()
      val playlist = playlistRepository.getPlaylistById(playlistId)
      _playlist.value = playlist
      _videoItems.value = buildVideoItems(
        playlist,
        playlistRepository.getPlaylistItems(playlistId),
        networkRepository.getAllConnectionsIncludingDeleted(),
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Error refreshing playlist videos", e)
    } finally {
      _isLoading.value = false
    }
  }

  /**
   * Null for local entries — there is nothing to probe. For network entries, classifies whether
   * the backing connection is usable *before* the player opens, so an offline server produces a
   * prompt instead of a long misleading "loading" state.
   *
   * Bounded: a route that silently drops packets would otherwise wait out the HTTP client's own
   * connect timeout. A premature UNREACHABLE is recoverable — tapping the entry again re-probes.
   */
  suspend fun probeIfNetwork(item: PlaylistVideoItem): NetworkProbeResult? {
    if (!item.isNetwork) return null
    val ref = NetworkPlaybackUri.parse(item.playlistItem.filePath) ?: return NetworkProbeResult.UNREACHABLE
    val connection = networkRepository.getConnectionById(ref.connectionId) ?: return NetworkProbeResult.UNREACHABLE
    val startedAt = SystemClock.elapsedRealtime()
    val result =
      withTimeoutOrNull(NETWORK_PROBE_TIMEOUT_MS) { networkRepository.probe(connection) }
        ?: NetworkProbeResult.UNREACHABLE
    Log.d(
      TAG,
      "Probe connection=${ref.connectionId} -> $result in ${SystemClock.elapsedRealtime() - startedAt}ms",
    )
    return result
  }

  suspend fun removeVideosFromPlaylist(videos: List<Video>) {
    val items = playlistRepository.getPlaylistItems(playlistId)
    val videoPaths = videos.map { it.path }.toSet()
    val itemsToRemove = items.filter { it.filePath in videoPaths }
    playlistRepository.removeItemsFromPlaylist(itemsToRemove)
  }

  suspend fun addVideosToPlaylist(videos: List<Video>) {
    if (_playlist.value?.isZipPlaylist == true) return
    val isAudio = _playlist.value?.isAudio ?: return
    val compatibleVideos = videos.filter { it.isAudio == isAudio }
    playlistRepository.addItemsToPlaylist(playlistId, compatibleVideos.map { PlaylistItemInput(it.path, it.displayName) })
  }

  suspend fun updatePlayHistory(
    filePath: String,
    position: Long = 0,
  ) {
    playlistRepository.updatePlayHistory(playlistId, filePath, position)
  }

  suspend fun reorderPlaylistItems(
    fromIndex: Int,
    toIndex: Int,
  ) {
    val currentItems = _videoItems.value.toMutableList()
    if (fromIndex < 0 || fromIndex >= currentItems.size || toIndex < 0 || toIndex >= currentItems.size) {
      return
    }

    // Reorder the list locally first
    val item = currentItems.removeAt(fromIndex)
    currentItems.add(toIndex, item)
    _videoItems.value = currentItems

    // Persist to database
    val newOrder = currentItems.map { it.playlistItem.id }
    playlistRepository.reorderPlaylistItems(playlistId, newOrder)
  }

  suspend fun refreshM3UPlaylist(): Result<Unit> =
    try {
      _isLoading.value = true
      playlistRepository.refreshM3UPlaylist(playlistId)
    } finally {
      _isLoading.value = false
    }

  suspend fun toggleFavorite(itemId: Int) {
    playlistRepository.toggleFavorite(itemId)
  }

  private fun buildArchiveVideoItem(item: PlaylistItemEntity, source: String, available: Boolean): PlaylistVideoItem {
    val isAudio = FileTypeUtils.isAudioFile(File(item.fileName))
    val video = Video(
      id = item.id.toLong(),
      title = item.fileName,
      displayName = item.fileName,
      path = item.filePath,
      uri = Uri.parse(item.filePath),
      duration = 0L,
      durationFormatted = "",
      size = item.fileSize ?: 0L,
      sizeFormatted = item.fileSize?.takeIf { it > 0L }?.let(MediaUtils::formatFileSize).orEmpty(),
      dateModified = item.addedAt / 1000L,
      dateAdded = item.addedAt / 1000L,
      mimeType = if (isAudio) "audio/*" else "video/*",
      bucketId = ZipArchiveMedia.browserPath(source),
      bucketDisplayName = _playlist.value?.name.orEmpty(),
      width = 0,
      height = 0,
      fps = 0f,
      resolution = "",
      isAudio = isAudio,
    )
    return PlaylistVideoItem(
      playlistItem = item,
      video = video,
      sourcePath = source,
      isAvailable = available,
      unavailableReasonRes = if (available) null else R.string.playlist_unavailable_file,
    )
  }


  /**
   * Builds a playlist entry backed by a saved network connection.
   *
   * Availability is deliberately shallow: only the connection *config* is checked, never the
   * server. Probing reachability here would block list loading, and a connection that is simply
   * offline must not be reported as missing — that case is left to the player's error path.
   */
  private fun buildNetworkVideoItem(
    item: PlaylistItemEntity,
    ref: NetworkPlaybackUri.Reference,
    connection: NetworkConnection?,
  ): PlaylistVideoItem {
    val isAudioFile = FileTypeUtils.isAudioFile(File(item.fileName)) ||
      (connection?.protocol == NetworkProtocol.DLNA && DlnaPath.isAudio(ref.path))
    // A tombstoned connection still names the share it pointed at, so the badge keeps saying
    // "WebDAV", but it is not usable: the row exists only so a re-created connection can revive it.
    val available = connection != null && !connection.isDeleted
    return PlaylistVideoItem(
      playlistItem = item,
      video =
        Video(
          id = item.id.toLong(),
          title = item.fileName,
          displayName = item.fileName,
          path = item.filePath,
          uri = Uri.parse(item.filePath),
          duration = 0L,
          durationFormatted = "--",
          size = item.fileSize ?: 0L,
          sizeFormatted = item.fileSize?.let { MediaUtils.formatFileSize(it) } ?: "--",
          dateModified = item.addedAt / 1000L,
          dateAdded = item.addedAt / 1000L,
          mimeType = if (isAudioFile) "audio/*" else "video/*",
          bucketId = "network_${ref.connectionId}",
          bucketDisplayName = connection?.name ?: "",
          width = 0,
          height = 0,
          fps = 0f,
          resolution = "--",
          isAudio = isAudioFile,
        ),
      isNetwork = true,
      connectionId = ref.connectionId,
      protocol = connection?.protocol,
      connectionName = connection?.name,
      sourcePath = if (connection?.protocol == NetworkProtocol.DLNA) null else ref.path.value,
      isAvailable = available,
      unavailableReasonRes = if (available) null else R.string.playlist_unavailable_connection,
    )
  }

  /**
   * Only plain filesystem paths can be judged with [File.exists]. A `content://` entry needs a
   * provider and `http(s)://` is remote, so neither can be probed here — both stay playable
   * rather than risking a false "missing" report.
   */
  private fun isLocalEntryAvailable(
    filePath: String,
    fileExists: Boolean,
  ): Boolean =
    when {
      filePath.startsWith("content://", ignoreCase = true) -> true
      filePath.startsWith("http://", ignoreCase = true) -> true
      filePath.startsWith("https://", ignoreCase = true) -> true
      else -> fileExists
    }

  private suspend fun buildM3UVideoItems(
    playlist: PlaylistEntity?,
    items: List<PlaylistItemEntity>,
  ): List<PlaylistVideoItem> =
    withContext(Dispatchers.IO) {
      items.mapNotNull { item ->
        try {
          val mediaReference = M3UParser.normalizeLocalMediaReference(item.filePath)
          val mediaUri = android.net.Uri.parse(mediaReference)
          val isNetwork = mediaUri.scheme?.lowercase() !in setOf(null, "file", "content")

          var resolvedVideo: Video? = null
          val localPath =
            if (!isNetwork) {
              if (mediaUri.scheme.equals("content", true) || mediaUri.scheme.equals("file", true)) {
                mediaUri.extractLocalPath()
              } else {
                mediaReference.substringBefore('|')
              }
            } else {
              null
            }

          if (localPath != null) {
            val file = File(localPath)
            if (file.exists()) {
              val videos = MediaFileRepository.getVideosFromFiles(getApplication(), listOf(file))
              resolvedVideo =
                videos.firstOrNull()?.copy(
                  id = item.id.toLong(),
                )
            }
          }

          val fallbackPath = localPath ?: mediaReference
          val fallbackUri =
            if (localPath != null) {
              android.net.Uri.fromFile(File(localPath))
            } else {
              android.net.Uri.parse(mediaReference)
            }

          val video =
            resolvedVideo ?: Video(
              id = item.id.toLong(),
              title = item.fileName,
              displayName = item.fileName,
              path = fallbackPath,
              uri = fallbackUri,
              duration = 0L,
              durationFormatted = "--",
              size = 0L,
              sizeFormatted = "--",
              dateModified = item.addedAt / 1000L,
              dateAdded = item.addedAt / 1000L,
              mimeType = "video/*",
              bucketId = "m3u_playlist_$playlistId",
              bucketDisplayName = playlist?.name ?: "M3U Playlist",
              width = 0,
              height = 0,
              fps = 0f,
              resolution = "--",
            )
          PlaylistVideoItem(item, video)
        } catch (e: Exception) {
          Log.w(TAG, "Failed to create video item for URL: ${item.filePath}", e)
          null
        }
      }
    }
}
