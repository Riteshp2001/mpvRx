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
import android.util.Log
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
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
    browserPreferences.showResolutionChip.get() ||
      browserPreferences.showFramerateInResolution.get() ||
      browserPreferences.showSubtitleIndicator.get() ||
      browserPreferences.showCodecSupportIndicator.get()

  /**
   * Checks if folder-specific metadata is needed
   */
  fun isFolderMetadataNeeded(browserPreferences: BrowserPreferences): Boolean =
    browserPreferences.showTotalDurationChip.get()

  /**
   * Enriches a list of videos with metadata only if needed
   * Processes in batches for better performance
   */
  suspend fun enrichVideosIfNeeded(
    context: Context,
    videos: List<Video>,
    browserPreferences: BrowserPreferences,
    metadataCache: VideoMetadataCacheRepository,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val metadataChipsEnabled = isVideoMetadataNeeded(browserPreferences)
      val hasArchiveEntries = videos.any { video -> ZipArchiveMedia.isPlaybackUri(video.uri.toString()) }
      if (!metadataChipsEnabled && !hasArchiveEntries) {
        return@withContext videos
      }

      // Filter videos that need metadata extraction
      // MediaStore provides width, height, duration but NOT FPS or subtitle info
      // So we need to extract metadata if FPS or subtitle info is missing
      val videosNeedingMetadata =
        videos.filter { video ->
          val needsArchiveMetadata = ZipArchiveMedia.isPlaybackUri(video.uri.toString())
          val needsVideoCodec = browserPreferences.showCodecSupportIndicator.get() && !video.isAudio
          val needsResolution =
            browserPreferences.showResolutionChip.get() && !video.isAudio &&
              (video.width == 0 || video.height == 0)
          val needsFramerate =
            browserPreferences.showFramerateInResolution.get() && !video.isAudio && video.fps == 0f
          val needsSubtitleInfo =
            browserPreferences.showSubtitleIndicator.get() && !video.isAudio && video.subtitleCodec.isEmpty()

          needsArchiveMetadata ||
            needsResolution ||
            needsFramerate ||
            needsSubtitleInfo ||
            (needsVideoCodec && video.videoCodec.isBlank())
        }

      if (videosNeedingMetadata.isEmpty()) {
        return@withContext videos
      }

      Log.d(TAG, "Enriching ${videosNeedingMetadata.size} videos with metadata")

      val enriched = videos.toMutableList()
      val indexes = videos.mapIndexed { index, video -> video.path to index }.toMap()
      for (batch in videosNeedingMetadata.chunked(32)) {
        currentCoroutineContext().ensureActive()
        val metadataMap = extractMetadataByVideoPath(
          context, batch, metadataCache, browserPreferences.showCodecSupportIndicator.get(),
        )
        for (video in batch) {
          val metadata = metadataMap[video.path] ?: continue
          val index = indexes[video.path] ?: continue
          enriched[index] = video.copy(
            duration = metadata.durationMs,
            durationFormatted = formatDuration(metadata.durationMs),
            width = metadata.width,
            height = metadata.height,
            fps = metadata.fps,
            resolution = VideoScanUtils.formatResolutionWithFps(metadata.width, metadata.height, metadata.fps),
            hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles,
            subtitleCodec = metadata.subtitleCodec,
            videoCodec = metadata.videoCodec,
            videoCodecMimeType = metadata.videoCodecMimeType,
          )
        }
        currentCoroutineContext().ensureActive()
        onSnapshot?.invoke(enriched.toList())
      }
      enriched.toList()
    }

  private suspend fun extractMetadataByVideoPath(
    context: Context,
    videos: List<Video>,
    metadataCache: VideoMetadataCacheRepository,
    includeVideoCodec: Boolean,
  ): Map<String, MediaInfoOps.VideoMetadata> {
    val metadataByVideoPath = mutableMapOf<String, MediaInfoOps.VideoMetadata>()
    val localFiles =
      videos.mapNotNull { video ->
        if (ZipArchiveMedia.isPlaybackUri(video.uri.toString())) return@mapNotNull null
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

    videos.asSequence()
      .filter { video -> ZipArchiveMedia.isPlaybackUri(video.uri.toString()) }
      .forEach { video ->
        val file = ZipArchiveMedia.materializeEntry(context, video.uri) ?: return@forEach
        metadataCache.getOrExtractMetadata(
          file = file,
          uri = Uri.fromFile(file),
          displayName = video.displayName,
          includeVideoCodec = includeVideoCodec && !video.isAudio,
        )?.let { metadata -> metadataByVideoPath[video.path] = metadata }
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
  ): VideoFolder =
    withContext(Dispatchers.IO) {
      // If folder duration chip is disabled, return folder as-is
      if (!isFolderMetadataNeeded(browserPreferences)) {
        return@withContext folder
      }

      // If folder already has duration, return as-is
      if (folder.totalDuration > 0) {
        return@withContext folder
      }

      // Extract metadata for all videos in folder
      try {
        if (ZipArchiveMedia.isBrowserPath(folder.path)) {
          val videos =
            ZipArchiveMedia
              .allMedia(context, folder.path, browserPreferences.includeAudioBrowser.get())
              .getOrNull()
              ?: return@withContext folder
          val metadataMap =
            extractMetadataByVideoPath(
              context = context,
              videos = videos,
              metadataCache = metadataCache,
              includeVideoCodec = false,
            )
          return@withContext folder.copy(totalDuration = metadataMap.values.sumOf { metadata -> metadata.durationMs })
        }

        val directory = File(folder.path)
        if (!directory.exists() || !directory.isDirectory) {
          return@withContext folder
        }

        val videoFiles =
          directory.listFiles()?.filter { file ->
            file.isFile && file.extension.lowercase() in VIDEO_EXTENSIONS
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
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
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
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
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

      val enriched = folders.toMutableList()
      val indexes = folders.mapIndexed { index, folder -> folder.path to index }.toMap()
      val publisher = ProgressiveResultsPublisher(onSnapshot) { enriched }
      for (folder in foldersNeedingMetadata) {
        currentCoroutineContext().ensureActive()
        val updated = enrichFolderIfNeeded(context, folder, browserPreferences, metadataCache)
        indexes[folder.path]?.let { enriched[it] = updated }
        processed++
        onProgress?.invoke(processed, total)
        publisher.publishIfNeeded()
      }
      publisher.publishIfNeeded(force = true)
      enriched.toList()
    }

  // Helper: Video file extensions
  private val VIDEO_EXTENSIONS =
    setOf(
      "mp4",
      "mkv",
      "avi",
      "mov",
      "wmv",
      "flv",
      "webm",
      "m4v",
      "3gp",
      "3g2",
      "mpg",
      "mpeg",
      "m2v",
      "ogv",
      "ts",
      "mts",
      "m2ts",
      "vob",
      "divx",
      "xvid",
      "f4v",
      "rm",
      "rmvb",
      "asf",
    )

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
