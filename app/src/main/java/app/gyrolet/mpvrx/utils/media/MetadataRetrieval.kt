/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Lazy metadata retrieval system
 * Only extracts detailed metadata (duration, framerate, resolution) when chips are enabled
 *
 * This improves app startup performance by deferring expensive metadata extraction
 * until the user actually needs to see the information.
 */
object MetadataRetrieval {
  private const val TAG = "MetadataRetrieval"

  /**
   * Checks if video-specific metadata is needed
   */
  fun isVideoMetadataNeeded(browserPreferences: BrowserPreferences): Boolean =
    browserPreferences.showDurationField.get() ||
      browserPreferences.showResolutionChip.get() ||
      browserPreferences.showFramerateInResolution.get() ||
      browserPreferences.showSubtitleIndicator.get() ||
      browserPreferences.showCodecSupportIndicator.get()

  /**
   * Checks if folder-specific metadata is needed
   */
  fun isFolderMetadataNeeded(browserPreferences: BrowserPreferences): Boolean =
    browserPreferences.showTotalDurationChip.get()

  fun needsVideoMetadata(video: Video, preferences: BrowserPreferences): Boolean {
    if (ZipArchiveMedia.isPlaybackUri(video.uri.toString())) return false
    return (preferences.showDurationField.get() && video.duration <= 0L) ||
      (!video.isAudio && (
        (preferences.showResolutionChip.get() && (video.width == 0 || video.height == 0)) ||
          (preferences.showFramerateInResolution.get() && (!video.fps.isFinite() || video.fps <= 0f)) ||
          (preferences.showSubtitleIndicator.get() && video.subtitleCodec.isEmpty()) ||
          (preferences.showCodecSupportIndicator.get() && video.videoCodec.isBlank())
        ))
  }

  internal fun withMetadata(video: Video, metadata: MediaInfoOps.VideoMetadata): Video {
    val duration = metadata.durationMs.takeIf { it > 0L } ?: video.duration
    // MediaStore's dimensions are what the library and resolution chip report; a probe only
    // fills them in when the index has none, so enrichment never changes a shown resolution.
    val hasIndexedSize = video.width > 0 && video.height > 0
    val width = if (hasIndexedSize) video.width else metadata.width.takeIf { it > 0 } ?: video.width
    val height = if (hasIndexedSize) video.height else metadata.height.takeIf { it > 0 } ?: video.height
    val fps = metadata.fps.takeIf { it.isFinite() && it > 0f } ?: video.fps
    return video.copy(
      duration = duration,
      durationFormatted = if (duration > 0L) formatDuration(duration) else "",
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles || video.hasEmbeddedSubtitles,
      subtitleCodec = metadata.subtitleCodec.ifBlank { video.subtitleCodec },
      videoCodec = metadata.videoCodec.ifBlank { video.videoCodec },
      videoCodecMimeType = metadata.videoCodecMimeType.ifBlank { video.videoCodecMimeType },
    )
  }

  suspend fun restoreCachedContentMetadata(
    videos: List<Video>,
    metadataCache: VideoMetadataCacheRepository,
  ): List<Video> {
    val cached = metadataCache.getCachedContentMetadata(videos)
    return videos.map { video -> cached[video.path]?.let { withMetadata(video, it) } ?: video }
  }

  /**
   * Enriches a list of videos with metadata only if needed
   * Processes in batches for better performance
   */
  suspend fun enrichVideosIfNeeded(
    context: Context,
    videos: List<Video>,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val metadataChipsEnabled = isVideoMetadataNeeded(browserPreferences)
      if (!metadataChipsEnabled) {
        return@withContext videos
      }

      // Filter videos that need metadata extraction
      // MediaStore provides width, height, duration but NOT FPS or subtitle info
      // So we need to extract metadata if FPS or subtitle info is missing
      val videosNeedingMetadata = videos.filter { needsVideoMetadata(it, browserPreferences) }

      if (videosNeedingMetadata.isEmpty()) {
        return@withContext videos
      }

      Log.d(TAG, "Enriching ${videosNeedingMetadata.size} videos with metadata")

      val metadataMap =
        extractMetadataByVideoPath(
          videos = videosNeedingMetadata,
          metadataCache = metadataCache,
          includeVideoCodec = browserPreferences.showCodecSupportIndicator.get(),
        )

      // Update videos with metadata
      videos.map { video ->
        metadataMap[video.path]?.let { withMetadata(video, it) } ?: video
      }
    }

  private suspend fun extractMetadataByVideoPath(
    videos: List<Video>,
    metadataCache: VideoMetadataCacheRepository,
    includeVideoCodec: Boolean,
  ): Map<String, MediaInfoOps.VideoMetadata> {
    val metadataByVideoPath = mutableMapOf<String, MediaInfoOps.VideoMetadata>()
    for (video in videos.filter { it.uri.scheme == "content" }) {
      kotlinx.coroutines.currentCoroutineContext().ensureActive()
      metadataCache.getOrExtractContentMetadata(
        video,
        includeVideoCodec && !video.isAudio && video.videoCodec.isBlank(),
      )?.let {
        metadataByVideoPath[video.path] = it
      }
    }
    val localFiles =
      videos.mapNotNull { video ->
        if (ZipArchiveMedia.isPlaybackUri(video.uri.toString()) ||
          video.uri.scheme == "content"
        ) return@mapNotNull null
        File(video.path).takeIf(File::isFile)?.let { file -> video to file }
      }

    val localMetadata =
      metadataCache.getOrExtractMetadataBatch(
        files = localFiles.map { (video, file) -> Triple(file, video.uri, video.displayName) },
        videoCodecPaths =
          localFiles
            .asSequence()
            .filter { (video, _) -> includeVideoCodec && !video.isAudio }
            .mapTo(mutableSetOf()) { (_, file) -> file.absolutePath },
      )
    localFiles.forEach { (video, file) ->
      localMetadata[file.absolutePath]?.let { metadata -> metadataByVideoPath[video.path] = metadata }
    }

    return metadataByVideoPath
  }

  /**
   * Enriches a folder with metadata only if needed
   * Calculates total duration by extracting metadata from all videos in the folder
   */
  suspend fun enrichFolderIfNeeded(
    context: Context,
    folder: VideoFolder,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
    audioOnly: Boolean = false,
  ): VideoFolder =
    withContext(Dispatchers.IO) {
      // If folder duration chip is disabled, return folder as-is
      if (!isFolderMetadataNeeded(browserPreferences)) {
        return@withContext folder
      }

      // If folder already has duration, return as-is
      if (folder.totalDuration > 0 && !ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId)) {
        return@withContext folder
      }

      // Extract metadata for all videos in folder
      try {
        if (ZipArchiveMedia.isBrowserPath(folder.path)) {
          return@withContext folder
        }

        if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
          val videos = restoreCachedContentMetadata(
            app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosInFolder(
              context,
              folder.bucketId,
              includeAudioOverride = if (audioOnly) true else null,
            ).filter { !audioOnly || it.isAudio },
            metadataCache,
          )
          var totalDuration = 0L
          for (video in videos) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            totalDuration += video.duration.takeIf { it > 0 }
              ?: metadataCache.getOrExtractContentMetadata(video)?.durationMs ?: 0L
          }
          return@withContext folder.copy(totalDuration = totalDuration)
        }
        val directory = File(folder.path)
        if (!directory.exists() || !directory.isDirectory) {
          return@withContext folder
        }

        val videoFiles =
          directory.listFiles()?.filter { file ->
            file.isFile && FileTypeUtils.isVideoFile(file)
          } ?: emptyList()

        if (videoFiles.isEmpty()) {
          return@withContext folder
        }

        // Batch extract metadata
        val fileTriples =
          videoFiles.map { file ->
            Triple(file, Uri.fromFile(file), file.name)
          }

        val metadataMap = metadataCache.getOrExtractMetadataBatch(fileTriples)

        // Calculate total duration
        val totalDuration = metadataMap.values.sumOf { it.durationMs }

        folder.copy(totalDuration = totalDuration)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error enriching folder metadata: ${folder.name}", e)
        folder
      }
    }

  /**
   * Enriches a list of folders with metadata only if needed
   * Processes in batches for better performance
   */
  suspend fun enrichFoldersIfNeeded(
    context: Context,
    folders: List<VideoFolder>,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      // If folder duration chip is disabled, return folders as-is
      if (!isFolderMetadataNeeded(browserPreferences)) {
        return@withContext folders
      }

      // Filter folders that need metadata extraction
      val foldersNeedingMetadata = folders.filter { it.totalDuration == 0L }

      if (foldersNeedingMetadata.isEmpty()) {
        return@withContext folders
      }

      Log.d(TAG, "Enriching ${foldersNeedingMetadata.size} folders with metadata")

      var processed = 0
      val total = foldersNeedingMetadata.size

      // Process each folder
      val enrichedMap =
        foldersNeedingMetadata.associate { folder ->
          val enriched = enrichFolderIfNeeded(context, folder, browserPreferences, metadataCache)
          processed++
          onProgress?.invoke(processed, total)
          folder.path to enriched
        }

      // Return updated list
      folders.map { folder ->
        enrichedMap[folder.path] ?: folder
      }
    }

  // Helper: Video file extensions
  // Formatting utilities
  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0s"

    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
      minutes > 0 -> String.format("%d:%02d", minutes, secs)
      else -> "${secs}s"
    }
  }

}
