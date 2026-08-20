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
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.RecentlyPlayedEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.recentlyplayed.RecentlyPlayedMediaClassifier
import app.gyrolet.mpvrx.domain.recentlyplayed.RecentlyPlayedPlaylistSummary
import app.gyrolet.mpvrx.domain.recentlyplayed.repository.RecentlyPlayedRepository
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

data class RecentlyPlayedUiState(
  val items: List<RecentlyPlayedItem> = emptyList(),
  val isLoading: Boolean = true,
)

data class RecentPlaybackRequest(
  val source: String,
  val title: String?,
)

private data class RecentlyPlayedSnapshot(
  val entities: List<RecentlyPlayedEntity>,
  val playlists: List<RecentlyPlayedPlaylistSummary>,
)

class RecentlyPlayedViewModel(
  application: Application,
  private val recentlyPlayedRepository: RecentlyPlayedRepository,
  private val playlistRepository: PlaylistRepository,
  private val metadataCache: VideoMetadataCacheRepository,
) : AndroidViewModel(application) {
  private val _uiState = MutableStateFlow(RecentlyPlayedUiState())
  val uiState: StateFlow<RecentlyPlayedUiState> = _uiState.asStateFlow()

  private val loadMutex = Mutex()

  @Volatile
  private var latestSnapshot: RecentlyPlayedSnapshot? = null

  init {
    viewModelScope.launch {
      combine(
        recentlyPlayedRepository.observeRecentlyPlayed(limit = RECENT_ITEM_LIMIT),
        recentlyPlayedRepository.observeRecentlyPlayedPlaylists(limit = RECENT_ITEM_LIMIT),
        ::RecentlyPlayedSnapshot,
      ).collectLatest { snapshot ->
        latestSnapshot = snapshot
        loadSnapshot(snapshot)
      }
    }
  }

  /** Re-resolves the latest database snapshot so stale files and metadata are refreshed on demand. */
  suspend fun refresh() {
    latestSnapshot?.let { snapshot -> loadSnapshot(snapshot) }
  }

  private suspend fun loadSnapshot(snapshot: RecentlyPlayedSnapshot) {
    loadMutex.withLock {
      _uiState.value = _uiState.value.copy(isLoading = true)
      try {
        val items = withContext(Dispatchers.IO) { buildRecentItems(snapshot) }
        if (latestSnapshot == snapshot) {
          _uiState.value = RecentlyPlayedUiState(items = items, isLoading = false)
        }
      } catch (cancellation: CancellationException) {
        if (latestSnapshot == snapshot) {
          _uiState.value = _uiState.value.copy(isLoading = false)
        }
        throw cancellation
      } catch (error: Exception) {
        Log.e(TAG, "Error loading recent media", error)
        if (latestSnapshot == snapshot) {
          // Retain the last usable content instead of replacing it with a misleading empty state.
          _uiState.value = _uiState.value.copy(isLoading = false)
        }
      }
    }
  }

  private suspend fun buildRecentItems(snapshot: RecentlyPlayedSnapshot): List<RecentlyPlayedItem> {
    val playlistIds =
      buildSet {
        snapshot.entities.mapNotNullTo(this) { it.playlistId }
        snapshot.playlists.mapTo(this) { it.playlistId }
      }
    val playlistsById =
      playlistIds.mapNotNull { playlistId ->
        playlistRepository.getPlaylistById(playlistId)?.let { playlistId to it }
      }.toMap()
    val localPlaylistIds =
      playlistsById
        .filterValues { playlist -> !playlist.isM3uPlaylist }
        .keys
    val entriesByPlaylist =
      snapshot.entities
        .filter { entity -> entity.playlistId in localPlaylistIds }
        .groupBy { entity -> checkNotNull(entity.playlistId) }

    val items = mutableListOf<RecentlyPlayedItem>()
    snapshot.playlists.forEach { summary ->
      val playlist = playlistsById[summary.playlistId] ?: return@forEach
      if (playlist.isM3uPlaylist) return@forEach

      val mostRecentEntry =
        entriesByPlaylist[summary.playlistId]
          ?.maxByOrNull { entity -> entity.timestamp }
          ?: return@forEach
      items +=
        RecentlyPlayedItem.PlaylistItem(
          playlist = playlist,
          videoCount = playlistRepository.getPlaylistItemCount(playlist.id),
          mostRecentVideoPath = mostRecentEntry.filePath,
          timestamp = summary.timestamp,
        )
    }

    for (entity in snapshot.entities) {
      if (entity.playlistId != null || RecentlyPlayedMediaClassifier.isStreamingPlaylist(entity.filePath)) {
        continue
      }
      resolveRecentVideo(entity)?.let { video ->
        items += RecentlyPlayedItem.VideoItem(video = video, timestamp = entity.timestamp)
      }
    }

    return items.sortedByDescending { item -> item.timestamp }
  }

  private suspend fun resolveRecentVideo(entity: RecentlyPlayedEntity): Video? {
    val source = entity.filePath
    val sourceUri = runCatching { Uri.parse(source) }.getOrNull()
    val scheme = sourceUri?.scheme?.lowercase()

    if (RecentlyPlayedMediaClassifier.isRemote(source) ||
      RecentlyPlayedMediaClassifier.isContentUri(source) ||
      (scheme != null && scheme != "file")
    ) {
      return createUriVideo(entity, sourceUri ?: Uri.parse(source))
    }

    val filePath =
      if (RecentlyPlayedMediaClassifier.isFileUri(source)) {
        sourceUri?.path.orEmpty()
      } else {
        source
      }
    val file = File(filePath)
    if (!file.exists() || !file.canRead()) {
      recentlyPlayedRepository.deleteByFilePath(source)
      return null
    }

    return createFileVideo(entity, file)
  }

  private suspend fun createFileVideo(
    entity: RecentlyPlayedEntity,
    file: File,
  ): Video? =
    try {
      val uri = Uri.fromFile(file)
      val displayName = file.name
      val metadata = metadataCache.getOrExtractMetadata(file, uri, displayName)
      val duration = metadata?.durationMs ?: entity.duration
      val width = metadata?.width ?: entity.width
      val height = metadata?.height ?: entity.height
      val fps = metadata?.fps ?: 0f
      val size = metadata?.sizeBytes?.takeIf { it > 0 } ?: file.length()
      val parent = file.parent.orEmpty()
      val extension = file.extension.lowercase()
      val isAudio = extension in FileTypeUtils.AUDIO_EXTENSIONS

      Video(
        id = file.absolutePath.hashCode().toLong(),
        title = entity.videoTitle?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
        displayName = displayName,
        path = entity.filePath,
        uri = uri,
        duration = duration,
        durationFormatted = formatDuration(duration),
        size = size,
        sizeFormatted = formatFileSize(size),
        dateModified = file.lastModified() / 1000,
        dateAdded = file.lastModified() / 1000,
        mimeType = mimeTypeForExtension(extension, isAudio),
        bucketId = parent.hashCode().toString(),
        bucketDisplayName = File(parent).name,
        width = width,
        height = height,
        fps = fps,
        resolution = if (isAudio) "--" else formatResolution(width, height),
        isAudio = isAudio,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Exception) {
      Log.e(TAG, "Error creating recent media for ${entity.filePath}", error)
      null
    }

  private fun createUriVideo(
    entity: RecentlyPlayedEntity,
    uri: Uri,
  ): Video {
    val resolvedTitle =
      entity.videoTitle?.takeIf { !RecentlyPlayedMediaClassifier.isGenericStreamName(it) }
        ?: entity.fileName.takeIf { !RecentlyPlayedMediaClassifier.isGenericStreamName(it) }
        ?: uri.lastPathSegment?.takeIf { !RecentlyPlayedMediaClassifier.isGenericStreamName(it) }
        ?: entity.videoTitle?.takeIf { it.isNotBlank() }
        ?: entity.fileName.takeIf { it.isNotBlank() }
        ?: "Stream"
    val extension = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val contentMimeType =
      if (RecentlyPlayedMediaClassifier.isContentUri(entity.filePath)) {
        runCatching { getApplication<Application>().contentResolver.getType(uri) }.getOrNull()
      } else {
        null
      }
    val isAudio = contentMimeType?.startsWith("audio/") == true || extension in FileTypeUtils.AUDIO_EXTENSIONS
    val mimeType = contentMimeType ?: mimeTypeForExtension(extension, isAudio)
    val bucketName =
      uri.host?.takeIf { it.isNotBlank() }
        ?: if (uri.scheme.equals("content", ignoreCase = true)) "Device" else "Network Streams"

    return Video(
      id = entity.filePath.hashCode().toLong(),
      title = resolvedTitle,
      displayName = entity.fileName.takeIf { it.isNotBlank() } ?: resolvedTitle,
      path = entity.filePath,
      uri = uri,
      duration = entity.duration,
      durationFormatted = formatDuration(entity.duration),
      size = entity.fileSize,
      sizeFormatted = formatFileSize(entity.fileSize),
      dateModified = entity.timestamp / 1000,
      dateAdded = entity.timestamp / 1000,
      mimeType = mimeType,
      bucketId = bucketName.hashCode().toString(),
      bucketDisplayName = bucketName,
      width = entity.width,
      height = entity.height,
      fps = 0f,
      resolution = if (isAudio) "--" else formatResolution(entity.width, entity.height),
      isAudio = isAudio,
    )
  }

  suspend fun lastPlayedRequest(): RecentPlaybackRequest? =
    withContext(Dispatchers.IO) {
      recentlyPlayedRepository.getRecentlyPlayed(limit = RECENT_ITEM_LIMIT).firstNotNullOfOrNull { entity ->
        val path = entity.filePath
        val uri = runCatching { Uri.parse(path) }.getOrNull()
        val isUriSource = uri?.scheme?.let { scheme -> !scheme.equals("file", ignoreCase = true) } == true
        val localPath = if (RecentlyPlayedMediaClassifier.isFileUri(path)) uri?.path.orEmpty() else path
        val localFile = File(localPath)
        if (!isUriSource && (!localFile.exists() || !localFile.canRead())) {
          runCatching { recentlyPlayedRepository.deleteByFilePath(path) }
          return@firstNotNullOfOrNull null
        }

        RecentPlaybackRequest(
          source = path,
          title =
            entity.videoTitle?.takeIf { it.isNotBlank() }
              ?: entity.fileName.takeIf { it.isNotBlank() },
        )
      }
    }

  suspend fun deleteVideosFromHistory(
    videos: List<Video>,
    deleteFiles: Boolean = false,
  ): Pair<Int, Int> =
    withContext(Dispatchers.IO) {
      var successCount = 0
      var failureCount = 0

      videos.forEach { video ->
        try {
          val shouldDeleteSource = deleteFiles && !RecentlyPlayedMediaClassifier.isRemote(video.path)
          if (shouldDeleteSource) {
            val sourceVideo =
              if (RecentlyPlayedMediaClassifier.isFileUri(video.path)) {
                video.copy(path = Uri.parse(video.path).path.orEmpty())
              } else {
                video
              }
            val (deleted, failed) =
              PermissionUtils.StorageOps.deleteVideos(
                getApplication(),
                listOf(sourceVideo),
              )
            if (deleted <= 0 || failed > 0) {
              Log.w(TAG, "Failed to delete recent media source: ${video.path}")
              failureCount++
              return@forEach
            }
          }

          recentlyPlayedRepository.deleteByFilePath(video.path)
          successCount++
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          Log.e(TAG, "Error deleting recent media: ${video.path}", error)
          failureCount++
        }
      }

      successCount to failureCount
    }

  suspend fun deletePlaylistsFromHistory(playlistIds: List<Int>): Pair<Int, Int> =
    withContext(Dispatchers.IO) {
      var successCount = 0
      var failureCount = 0

      playlistIds.forEach { playlistId ->
        try {
          recentlyPlayedRepository.deleteByPlaylistId(playlistId)
          successCount++
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (error: Exception) {
          Log.e(TAG, "Error deleting playlist history: $playlistId", error)
          failureCount++
        }
      }

      successCount to failureCount
    }

  suspend fun resolvePlayableRecentVideo(video: Video): Video? =
    withContext(Dispatchers.IO) {
      val path = video.path.takeIf { it.isNotBlank() } ?: video.uri.toString()
      if (path.isBlank()) return@withContext null

      val uri = runCatching { Uri.parse(path) }.getOrNull()
      val scheme = uri?.scheme?.lowercase()
      if (scheme != null && scheme != "file") return@withContext video

      val filePath = if (RecentlyPlayedMediaClassifier.isFileUri(path)) uri?.path.orEmpty() else path
      val file = File(filePath)
      if (file.exists() && file.canRead()) {
        video
      } else {
        runCatching { recentlyPlayedRepository.deleteByFilePath(video.path) }
        null
      }
    }

  private fun mimeTypeForExtension(
    extension: String,
    isAudio: Boolean,
  ): String =
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
      else -> if (isAudio) "audio/*" else "video/*"
    }

  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "--"
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60

    return when {
      hours > 0 -> "${hours}h ${minutes}m ${remainingSeconds}s"
      minutes > 0 -> "${minutes}m ${remainingSeconds}s"
      else -> "${remainingSeconds}s"
    }
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroup =
      (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0))
        .toInt()
        .coerceIn(units.indices)
    return String.format(
      java.util.Locale.getDefault(),
      "%.1f %s",
      bytes / 1024.0.pow(digitGroup.toDouble()),
      units[digitGroup],
    )
  }

  private fun formatResolution(
    width: Int,
    height: Int,
  ): String {
    if (width <= 0 || height <= 0) return "--"

    return when {
      width >= 7680 || height >= 4320 -> "4320p"
      width >= 3840 || height >= 2160 -> "2160p"
      width >= 2560 || height >= 1440 -> "1440p"
      width >= 1920 || height >= 1080 -> "1080p"
      width >= 1280 || height >= 720 -> "720p"
      width >= 854 || height >= 480 -> "480p"
      width >= 640 || height >= 360 -> "360p"
      width >= 426 || height >= 240 -> "240p"
      width >= 256 || height >= 144 -> "144p"
      else -> "${height}p"
    }
  }

  private companion object {
    const val TAG = "RecentlyPlayedViewModel"
    const val RECENT_ITEM_LIMIT = 50
  }
}
