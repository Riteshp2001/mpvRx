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
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.repository.NetworkProbeResult
import app.gyrolet.mpvrx.repository.NetworkRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.extractLocalPath
import app.gyrolet.mpvrx.ui.player.resolveUri
import app.gyrolet.mpvrx.utils.media.M3UParser
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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
  private val refreshRevision = MutableStateFlow(0L)
  private val completedRefreshRevision = MutableStateFlow(-1L)

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
    // Observe playlist info
    viewModelScope.launch(Dispatchers.IO) {
      playlistRepository.observePlaylistById(playlistId).collectLatest { playlist ->
        _playlist.value = playlist
      }
    }

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
        refreshRevision,
      ) { items, connections, revision -> Triple(items, connections, revision) }
        .collectLatest { (items, connections, revision) ->
        _isLoading.value = _videoItems.value.isEmpty()
        try {
          if (items.isEmpty()) {
            _videoItems.value = emptyList()
          } else {
            // Check if this is an M3U playlist
            val playlist = playlistRepository.observePlaylistById(playlistId).first()
            val isM3uPlaylist = playlist?.isM3uPlaylist == true

            if (isM3uPlaylist) {
              buildM3UVideoItems(playlist, items, loadMetadata = false)
              val videoItems = buildM3UVideoItems(playlist, items)

              currentCoroutineContext().ensureActive()
              Log.d(TAG, "Loaded ${videoItems.size} M3U playlist items")
              _videoItems.value = videoItems
            } else {
              val networkRefs = items.map { NetworkPlaybackUri.parse(it.filePath) }
              val connectionsById = connections.associateBy { it.id }
              publishBasicPlaylistItems(items, connectionsById)

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
                  .filter { networkRefs[it] == null }
                  .mapNotNull { fileObjects[it].parent }
                  .filter { it.isNotBlank() }
                  .toSet()

              // Get all videos and audio files from those folders (uses cache)
              val allVideos = MediaFileRepository.getVideosForBuckets(
                getApplication(),
                bucketIds,
                includeAudioOverride = true,
                onSnapshot = ::publishScannedPlaylistVideos,
              )
              val videosByPath = allVideos.flatMap { listOf(it.path to it, it.uri.toString() to it) }.toMap()

              // Match videos by path, maintaining playlist order
              val videoItems =
                items.mapIndexedNotNull { index, item ->
                  currentCoroutineContext().ensureActive()
                  val networkRef = networkRefs[index]
                  if (networkRef != null) {
                    return@mapIndexedNotNull buildNetworkVideoItem(item, networkRef, connectionsById[networkRef.connectionId])
                  }

                  val file = fileObjects.getOrNull(index) ?: File(item.filePath)
                  val fileExists = file.exists()
                  val isAudioFile = FileTypeUtils.isAudioFile(file)
                  val matchedVideo = videosByPath[item.filePath] ?: videosByPath[file.absolutePath]
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
                      sizeFormatted = "--",
                      dateModified = item.addedAt,
                      dateAdded = item.addedAt,
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

              currentCoroutineContext().ensureActive()
              Log.d(TAG, "Loaded ${videoItems.size} items out of ${items.size} playlist items")
              _videoItems.value = videoItems
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.e(TAG, "Error loading playlist videos", error)
        } finally {
          if (currentCoroutineContext().isActive) {
            _isLoading.value = false
            completedRefreshRevision.value = revision
          }
        }
      }
    }
  }

  override fun refresh() {
    refreshRevision.update { it + 1L }
  }

  /**
   * Refresh playlist items and associated [Video] metadata, awaiting completion.
   *
   * This is useful for UI gestures like pull-to-refresh that need to know when refreshing is done.
   */
  suspend fun refreshNow() {
    val revision = refreshRevision.updateAndGet { it + 1L }
    completedRefreshRevision.first { it >= revision }
  }

  private suspend fun publishBasicPlaylistItems(
    items: List<PlaylistItemEntity>,
    connections: Map<Long, NetworkConnection>,
  ) {
    val previous = _videoItems.value.associateBy { it.playlistItem.id }
    val result = mutableListOf<PlaylistVideoItem>()
    val publisher = ProgressiveResultsPublisher<PlaylistVideoItem>(
      onSnapshot = { partial ->
        if (partial.isNotEmpty()) {
          val byId = partial.associateBy { it.playlistItem.id }
          _videoItems.value = items.mapNotNull { byId[it.id] ?: previous[it.id] }
          _isLoading.value = false
        }
      },
      snapshot = { result },
    )
    for (item in items) {
      currentCoroutineContext().ensureActive()
      val ref = NetworkPlaybackUri.parse(item.filePath)
      val existing = previous[item.id]
      result += when {
        ref != null -> buildNetworkVideoItem(item, ref, connections[ref.connectionId])
        else -> {
          val uri = Uri.parse(item.filePath)
          val file = File(if (uri.scheme == "file") uri.path.orEmpty() else item.filePath)
          val exists = file.isFile
          val audio = FileTypeUtils.isAudioFile(file)
          val available = isLocalEntryAvailable(item.filePath, exists)
          val size = if (exists) file.length() else item.fileSize ?: 0L
          val video = existing?.takeIf { it.playlistItem.filePath == item.filePath }?.video ?: Video(
            id = item.id.toLong(),
            title = item.fileName,
            displayName = item.fileName,
            path = item.filePath,
            uri = if (uri.scheme == null) Uri.fromFile(file) else uri,
            duration = 0L,
            durationFormatted = "--",
            size = size,
            sizeFormatted = MediaUtils.formatFileSize(size),
            dateModified = item.addedAt,
            dateAdded = item.addedAt,
            mimeType = if (audio) "audio/*" else "video/*",
            bucketId = file.parent.orEmpty(),
            bucketDisplayName = file.parentFile?.name.orEmpty(),
            width = 0,
            height = 0,
            fps = 0f,
            resolution = "--",
            isAudio = audio,
          )
          PlaylistVideoItem(
            playlistItem = item,
            video = video,
            sourcePath = file.parent,
            isAvailable = available,
            unavailableReasonRes = if (available) null else R.string.playlist_unavailable_file,
          )
        }
      }
      publisher.publishIfNeeded()
    }
    publisher.publishIfNeeded(force = true)
  }

  private suspend fun publishScannedPlaylistVideos(videos: List<Video>) {
    currentCoroutineContext().ensureActive()
    val byPath = videos.flatMap { listOf(it.path to it, it.uri.toString() to it) }.toMap()
    _videoItems.value = _videoItems.value.map { item ->
      val video = byPath[item.video.path] ?: byPath[item.playlistItem.filePath]
      if (video != null && !item.isNetwork) item.copy(video = video, isAvailable = true, unavailableReasonRes = null) else item
    }
  }

  suspend fun updatePlaylistName(newName: String) {
    _playlist.value?.let { playlist ->
      playlistRepository.updatePlaylist(playlist.copy(name = newName))
    }
  }

  suspend fun removeVideoFromPlaylist(video: Video) {
    val items = playlistRepository.getPlaylistItems(playlistId)
    val itemToRemove = items.find { it.filePath == video.path }
    itemToRemove?.let {
      playlistRepository.removeItemFromPlaylist(it)
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
    val isAudioFile = FileTypeUtils.isAudioFile(File(item.fileName))
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
          dateModified = item.addedAt,
          dateAdded = item.addedAt,
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
      sourcePath = ref.path.value,
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
    loadMetadata: Boolean = true,
  ): List<PlaylistVideoItem> =
    withContext(Dispatchers.IO) {
      val previous = _videoItems.value.associateBy { it.playlistItem.id }
      val partialItems = mutableListOf<PlaylistVideoItem>()
      val publisher = ProgressiveResultsPublisher<PlaylistVideoItem>(
        onSnapshot = { partial ->
          val byId = partial.associateBy { it.playlistItem.id }
          _videoItems.value = items.mapNotNull { byId[it.id] ?: previous[it.id] }
          if (partial.isNotEmpty()) _isLoading.value = false
        },
        snapshot = { partialItems },
      )
      val result = items.mapNotNull { item ->
        currentCoroutineContext().ensureActive()
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

          if (loadMetadata && localPath != null) {
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
            resolvedVideo ?: previous[item.id]?.takeIf { it.playlistItem.filePath == item.filePath }?.video ?: Video(
              id = item.id.toLong(),
              title = item.fileName,
              displayName = item.fileName,
              path = fallbackPath,
              uri = fallbackUri,
              duration = 0L,
              durationFormatted = "--",
              size = 0L,
              sizeFormatted = "--",
              dateModified = item.addedAt,
              dateAdded = item.addedAt,
              mimeType = if (playlist?.isAudio == true) "audio/*" else "video/*",
              bucketId = "m3u_playlist_$playlistId",
              bucketDisplayName = playlist?.name ?: "M3U Playlist",
              width = 0,
              height = 0,
              fps = 0f,
              resolution = "--",
              isAudio = playlist?.isAudio == true,
            )
          PlaylistVideoItem(item, video).also {
            partialItems += it
            publisher.publishIfNeeded()
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (e: Exception) {
          Log.w(TAG, "Failed to create video item for URL: ${item.filePath}", e)
          null
        }
      }
      publisher.publishIfNeeded(force = true)
      result
    }
}
