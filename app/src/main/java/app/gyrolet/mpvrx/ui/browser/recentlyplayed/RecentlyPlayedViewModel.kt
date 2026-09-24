/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.recentlyplayed

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import app.gyrolet.mpvrx.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.media.HttpUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import kotlin.math.pow

class RecentlyPlayedViewModel(
  application: Application,
) : AndroidViewModel(application) {
  private val recentlyPlayedRepository by inject<RecentlyPlayedRepository>(RecentlyPlayedRepository::class.java)
  private val playlistRepository by inject<PlaylistRepository>(PlaylistRepository::class.java)
  private val metadataCache by inject<VideoMetadataCacheRepository>(VideoMetadataCacheRepository::class.java)

  private val _recentItems = MutableStateFlow<List<RecentlyPlayedItem>>(emptyList())
  val recentItems: StateFlow<List<RecentlyPlayedItem>> = _recentItems.asStateFlow()

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  private val refreshRevision = MutableStateFlow(0L)
  private val completedRefreshRevision = MutableStateFlow(0L)

  init {
    // Observe recently played changes and update automatically
    viewModelScope.launch {
      val db =
        org.koin.java.KoinJavaComponent
          .get<MpvRxDatabase>(MpvRxDatabase::class.java)

      // Combine both flows - entities and playlists
      kotlinx.coroutines.flow
        .combine(
          recentlyPlayedRepository.observeRecentlyPlayed(limit = 50),
          db.recentlyPlayedDao().observeRecentlyPlayedPlaylists(limit = 50),
          refreshRevision,
        ) { entities, playlists, revision ->
          Triple(entities, playlists, revision)
        }.collectLatest { (entities, playlists, revision) ->
          loadRecentVideosFromEntities(entities, playlists)
          completedRefreshRevision.value = revision
        }
    }
  }

  suspend fun refresh() {
    val revision = refreshRevision.updateAndGet { it + 1L }
    completedRefreshRevision.first { it >= revision }
  }

  private suspend fun loadRecentVideosFromEntities(
    allRecentEntities: List<RecentlyPlayedEntity>,
    recentPlaylists: List<app.gyrolet.mpvrx.database.dao.RecentlyPlayedDao.RecentlyPlayedPlaylistInfo>,
  ) {
    try {
      val items = mutableListOf<RecentlyPlayedItem>()
      val previous = _recentItems.value
      val previousVideos = previous.filterIsInstance<RecentlyPlayedItem.VideoItem>().associateBy { it.video.path }
      val pendingMetadata = mutableListOf<Int>()
      var enumerationComplete = false
      fun key(item: RecentlyPlayedItem): String = when (item) {
        is RecentlyPlayedItem.VideoItem -> "video:${item.video.path}"
        is RecentlyPlayedItem.PlaylistItem -> "playlist:${item.playlist.id}"
      }
      val publisher = app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher<RecentlyPlayedItem>(
        onSnapshot = { partial ->
          if (partial.isNotEmpty()) {
            val keys = partial.mapTo(HashSet(), ::key)
            val retained = if (enumerationComplete) emptyList() else previous.filterNot { key(it) in keys }
            _recentItems.value = (partial + retained).sortedByDescending { it.timestamp }
            _isLoading.value = false
          }
        },
        snapshot = { items },
      )

      // Group videos by playlist and standalone videos
      val playlistMap = mutableMapOf<Int, MutableList<Pair<String, Long>>>()
      val standaloneVideos = mutableListOf<Pair<String, Long>>()

      // Get a set of all network playlist IDs to filter them out
      val networkPlaylistIds = mutableSetOf<Int>()
      for (playlistId in allRecentEntities.mapNotNull { it.playlistId }.distinct()) {
        val playlist = playlistRepository.getPlaylistById(playlistId)
        if (playlist?.isM3uPlaylist == true) {
          networkPlaylistIds.add(playlistId)
        }
      }

      for (entity in allRecentEntities) {
        // Skip videos from network playlists
        if (entity.playlistId != null) {
          if (entity.playlistId in networkPlaylistIds) {
            // Skip videos from network playlists
            continue
          }
          playlistMap
            .getOrPut(entity.playlistId) { mutableListOf() }
            .add(Pair(entity.filePath, entity.timestamp))
        } else {
          standaloneVideos.add(Pair(entity.filePath, entity.timestamp))
        }
      }

      // Legacy/duplicate rows for the same file can still exist in the DB (e.g. from before a
      // race-condition fix); collapse them so the LazyColumn never sees two items with the same
      // key. allRecentEntities is ordered by timestamp DESC, so the first occurrence is the newest.
      val distinctStandaloneVideos = standaloneVideos.distinctBy { it.first }

      // Create playlist items (excluding network/M3U playlists)
      for (playlistInfo in recentPlaylists) {
        val playlist = playlistRepository.getPlaylistById(playlistInfo.playlistId)

        // Skip M3U/network playlists - only include local playlists
        if (playlist != null && !playlist.isM3uPlaylist) {
          val playlistVideos = playlistMap[playlistInfo.playlistId] ?: emptyList()
          val mostRecent = playlistVideos.maxByOrNull { it.second }
          if (mostRecent != null) {
            val itemCount = playlistRepository.getPlaylistItemCount(playlist.id)
            items.add(
              RecentlyPlayedItem.PlaylistItem(
                playlist = playlist,
                videoCount = itemCount,
                mostRecentVideoPath = mostRecent.first,
                timestamp = playlistInfo.timestamp,
              ),
            )
            publisher.publishIfNeeded()
          }
        }
      }

      // Create standalone video items
      for ((filePath, timestamp) in distinctStandaloneVideos) {
        val entity = allRecentEntities.find { it.filePath == filePath }

        // Check if this is a network URL
        val isNetworkUri =
          filePath.startsWith("http://", ignoreCase = true) ||
            filePath.startsWith("https://", ignoreCase = true) ||
            filePath.startsWith("rtmp://", ignoreCase = true) ||
            filePath.startsWith("rtsp://", ignoreCase = true)

        // Skip any kind of streaming playlist entries
        if (isStreamingPlaylist(filePath)) {
          // Skip streaming playlist entries
          continue
        }

        val video =
          if (isNetworkUri) {
            // For network URLs, create video object directly using parsed title from entity
            createNetworkVideoFromUrl(filePath, entity?.videoTitle, entity)
          } else {
            // For local files, check if they exist
            val file = File(filePath)
            if (file.exists()) {
              previousVideos[filePath]?.video ?: createVideoFromFilePath(filePath, file, entity?.videoTitle, loadMetadata = false)
            } else {
              recentlyPlayedRepository.deleteByFilePath(filePath)
              null
            }
          }

        if (video != null) {
          if (!isNetworkUri) pendingMetadata += items.size
          items.add(RecentlyPlayedItem.VideoItem(video, timestamp))
          publisher.publishIfNeeded()
        }
      }

      enumerationComplete = true
      _recentItems.value = items.sortedByDescending { it.timestamp }
      if (items.isNotEmpty()) _isLoading.value = false
      for (index in pendingMetadata) {
        val item = items[index] as RecentlyPlayedItem.VideoItem
        val enriched = createVideoFromFilePath(item.video.path, File(item.video.path)) ?: continue
        items[index] = item.copy(video = enriched)
        publisher.publishIfNeeded()
      }
      publisher.publishIfNeeded(force = true)
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e("RecentlyPlayedViewModel", "Error loading recent videos", e)
    } finally {
      if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) _isLoading.value = false
    }
  }

  private suspend fun createVideoFromFilePath(
    filePath: String,
    file: File,
    parsedVideoTitle: String? = null,
    loadMetadata: Boolean = true,
  ): Video? =
    try {
      val context = getApplication<Application>()

      // Extract metadata directly from file using metadata cache
      val uri = Uri.fromFile(file)
      val displayName = file.name
      val title = file.nameWithoutExtension

      // Get metadata from cache or extract it
      val metadataCache by inject<VideoMetadataCacheRepository>(VideoMetadataCacheRepository::class.java)
      val metadata = if (loadMetadata) metadataCache.getOrExtractMetadata(file, uri, displayName) else null

      val duration = metadata?.durationMs ?: 0L
      val width = metadata?.width ?: 0
      val height = metadata?.height ?: 0
      val fps = metadata?.fps ?: 0f
      val size =
        if (metadata?.sizeBytes != null && metadata.sizeBytes > 0) {
          metadata.sizeBytes
        } else {
          file.length()
        }

      val dateModified = file.lastModified() / 1000
      val dateAdded = dateModified
      val parent = file.parent ?: ""
      val bucketId = parent.hashCode().toString()
      val bucketDisplayName = File(parent).name

      val extension = file.extension.lowercase()
      val isAudio = extension in FileTypeUtils.AUDIO_EXTENSIONS

      // Determine mime type from extension
      val mimeType =
        when (extension) {
          "mp3" -> "audio/mpeg"
          "m4a" -> "audio/mp4"
          "aac" -> "audio/aac"
          "flac" -> "audio/flac"
          "wav" -> "audio/wav"
          "ogg" -> "audio/ogg"
          "opus" -> "audio/opus"
          "wma" -> "audio/x-ms-wma"
          "mp4" -> "video/mp4"
          "mkv" -> "video/x-matroska"
          "webm" -> "video/webm"
          "avi" -> "video/x-msvideo"
          "mov" -> "video/quicktime"
          "flv" -> "video/x-flv"
          "wmv" -> "video/x-ms-wmv"
          "m4v" -> "video/x-m4v"
          "3gp" -> "video/3gpp"
          "ts" -> "video/mp2t"
          else -> "video/*"
        }

      Video(
        id = file.absolutePath.hashCode().toLong(),
        title = title,
        displayName = displayName,
        path = filePath,
        uri = uri,
        duration = duration,
        durationFormatted = if (duration > 0L) formatDuration(duration) else "--",
        size = size,
        sizeFormatted = formatFileSize(size),
        dateModified = dateModified,
        dateAdded = dateAdded,
        mimeType = mimeType,
        bucketId = bucketId,
        bucketDisplayName = bucketDisplayName,
        width = width,
        height = height,
        fps = fps,
        resolution = if (isAudio) "--" else VideoScanUtils.formatResolution(width, height),
        isAudio = isAudio,
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (e: Exception) {
      Log.e("RecentlyPlayedViewModel", "Error creating video from path: $filePath", e)
      null
    }

  /**
   * Creates a Video object from a network URL
   */
  private fun createNetworkVideoFromUrl(
    url: String,
    parsedVideoTitle: String?,
    entity: RecentlyPlayedEntity?,
  ): Video {
    // Extract URI components
    val uri = Uri.parse(url)

    // Prefer non-generic title saved with the recent item so network streams keep their resolved name.
    val resolvedTitle =
      parsedVideoTitle?.takeIf { !isGenericStreamName(it) }
        ?: entity?.videoTitle?.takeIf { !isGenericStreamName(it) }
        ?: entity?.fileName?.takeIf { !isGenericStreamName(it) }
        ?: uri.lastPathSegment?.takeIf { !isGenericStreamName(it) }
        ?: parsedVideoTitle?.takeIf { it.isNotBlank() }
        ?: entity?.fileName?.takeIf { it.isNotBlank() }
        ?: "Stream"
    val displayName = resolvedTitle

    // Use metadata from entity if available
    val duration = entity?.duration ?: 0L
    val size = entity?.fileSize ?: 0L
    val width = entity?.width ?: 0
    val height = entity?.height ?: 0

    // Current timestamp for dates (network streams don't have file dates)
    val dateModified = System.currentTimeMillis() / 1000
    val dateAdded = dateModified

    // Use host as bucket ID (grouping by domain)
    val bucketId = (uri.host ?: "network").hashCode().toString()
    val bucketDisplayName = uri.host ?: "Network Streams"

    // Determine mime type based on URL extension, default to generic video
    val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
    val mimeType =
      when (extension) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "m3u8" -> "application/x-mpegURL"
        "m3u" -> "application/x-mpegURL"
        "mpd" -> "application/dash+xml"
        else -> "video/*"
      }
    val isAudio = HttpUtils.isMusicStreamingUrl(uri)

    return Video(
      id = url.hashCode().toLong(),
      title = resolvedTitle,
      displayName = displayName,
      path = url,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateAdded,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = 0f, // Network videos typically don't have fps metadata stored
      resolution = if (isAudio) "--" else VideoScanUtils.formatResolution(width, height),
      isAudio = isAudio,
      artworkUrl = entity?.artworkUrl,
    )
  }

  // Basic video creation function removed as it's no longer used

  suspend fun clearAllRecentlyPlayed() {
    try {
      recentlyPlayedRepository.clearAll()
      // The observe flow will automatically update the UI
    } catch (e: Exception) {
      Log.e("RecentlyPlayedViewModel", "Error clearing recent videos", e)
    }
  }

  suspend fun deleteVideosFromHistory(
    videos: List<Video>,
    deleteFiles: Boolean = false,
  ): Pair<Int, Int> =
    try {
      var successCount = 0
      var failCount = 0

      videos.forEach { video ->
        try {
          // Delete from history database
          recentlyPlayedRepository.deleteByFilePath(video.path)

          // If deleteFiles is true and it's a local file, delete the actual file
          if (deleteFiles) {
            // Check if it's a local file (not a network URL)
            val isNetworkUri =
              video.path.startsWith("http://", ignoreCase = true) ||
                video.path.startsWith("https://", ignoreCase = true) ||
                video.path.startsWith("rtmp://", ignoreCase = true) ||
                video.path.startsWith("rtsp://", ignoreCase = true)

            if (!isNetworkUri) {
              val (deleted, failed) =
                PermissionUtils.StorageOps.deleteVideos(
                  getApplication(),
                  listOf(video),
                )
              if (deleted <= 0 || failed > 0) {
                Log.w("RecentlyPlayedViewModel", "Failed to delete file: ${video.path}")
                failCount++
              } else {
                Log.d("RecentlyPlayedViewModel", "Deleted file: ${video.path}")
              }
            }
          }

          successCount++
        } catch (e: Exception) {
          Log.e("RecentlyPlayedViewModel", "Error deleting video from history: ${video.path}", e)
          failCount++
        }
      }

      Pair(successCount, failCount)
    } catch (e: Exception) {
      Log.e("RecentlyPlayedViewModel", "Error deleting videos from history", e)
      Pair(0, videos.size)
    }

  suspend fun deletePlaylistsFromHistory(playlistIds: List<Int>): Pair<Int, Int> =
    try {
      var successCount = 0
      var failCount = 0

      playlistIds.forEach { playlistId ->
        try {
          recentlyPlayedRepository.deleteByPlaylistId(playlistId)
          successCount++
        } catch (e: Exception) {
          Log.e("RecentlyPlayedViewModel", "Error deleting playlist from history: $playlistId", e)
          failCount++
        }
      }

      Pair(successCount, failCount)
    } catch (e: Exception) {
      Log.e("RecentlyPlayedViewModel", "Error deleting playlists from history", e)
      Pair(0, playlistIds.size)
    }

  suspend fun resolvePlayableRecentVideo(video: Video): Video? =
    withContext(Dispatchers.IO) {
      val path = video.path.takeIf { it.isNotBlank() } ?: video.uri.toString()
      if (path.isBlank()) return@withContext null

      if (isNetworkUri(path)) {
        return@withContext video
      }
      val scheme = runCatching { Uri.parse(path).scheme?.lowercase() }.getOrNull()
      if (scheme != null && scheme != "file") {
        return@withContext video
      }

      val filePath =
        when {
          path.startsWith("file://", ignoreCase = true) -> path.removePrefix("file://")
          video.uri.scheme.equals("file", ignoreCase = true) -> video.uri.path.orEmpty()
          else -> path
        }

      val file = File(filePath)
      if (file.exists() && file.canRead()) {
        video
      } else {
        runCatching { recentlyPlayedRepository.deleteByFilePath(video.path) }
        null
      }
    }

  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--"
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> "${hours}h ${minutes}m ${secs}s"
      minutes > 0 -> "${minutes}m ${secs}s"
      else -> "${secs}s"
    }
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
    return String.format(
      java.util.Locale.getDefault(),
      "%.1f %s",
      bytes / 1024.0.pow(digitGroups.toDouble()),
      units[digitGroups],
    )
  }

  private fun isNetworkUri(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true)

  /**
   * Checks if a URL is likely a streaming playlist (M3U, HLS, DASH, etc.)
   *
   * @param url The URL to check
   * @return True if the URL appears to be a streaming playlist
   */
  private fun isStreamingPlaylist(url: String): Boolean {
    val lowerCaseUrl = url.lowercase()

    // Direct extensions
    if (lowerCaseUrl.endsWith(".m3u") ||
      lowerCaseUrl.endsWith(".m3u8") ||
      lowerCaseUrl.endsWith(".mpd")
    ) {
      return true
    }

    // Common playlist keywords
    if (lowerCaseUrl.contains("playlist") ||
      lowerCaseUrl.contains("manifest")
    ) {
      return true
    }

    // Index files with streaming format indicators
    if (lowerCaseUrl.contains("index") &&
      (
        lowerCaseUrl.contains(".m3u") ||
          lowerCaseUrl.contains("hls") ||
          lowerCaseUrl.contains("dash") ||
          lowerCaseUrl.contains("mpd")
      )
    ) {
      return true
    }

    // IPTV and streaming service patterns
    if (lowerCaseUrl.contains("iptv") ||
      lowerCaseUrl.contains("channel") &&
      lowerCaseUrl.contains("stream")
    ) {
      return true
    }

    return false
  }

  companion object {
    private val GENERIC_STREAM_NAMES =
      setOf(
        "stream",
        "stream.mkv",
        "stream.mp4",
        "stream.ts",
        "stream.webm",
        "stream.avi",
      )

    fun isGenericStreamName(name: String?): Boolean =
      name.isNullOrBlank() || name.trim().lowercase() in GENERIC_STREAM_NAMES

    fun factory(application: Application): ViewModelProvider.Factory =
      viewModelFactory {
        initializer {
          RecentlyPlayedViewModel(application)
        }
      }
  }
}
