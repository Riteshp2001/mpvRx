/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.gyrolet.mpvrx.database.MpvRxDatabase
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.browser.PathComponent
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import app.gyrolet.mpvrx.utils.storage.MediaScanOptions
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import app.gyrolet.mpvrx.utils.storage.TreeViewScanner
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import app.gyrolet.mpvrx.utils.storage.mediaPathKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Unified repository for ALL media file operations
 * Consolidates FileSystemRepository, VideoRepository functionality
 *
 * This repository handles:
 * - Video folder discovery (album view)
 * - File system browsing (tree view)
 * - Video file listing
 * - Metadata extraction
 * - Path operations
 * - Storage volume detection
 */
object MediaFileRepository : KoinComponent {
  private const val TAG = "MediaFileRepository"
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val database: MpvRxDatabase by inject()

  private fun currentScanOptions(includeAudioOverride: Boolean? = null): MediaScanOptions =
    MediaScanOptions(
      includeNoMediaFolders = foldersPreferences.includeNoMediaFolders.get(),
      hiddenFolderMarkerNames = foldersPreferences.hiddenFolderMarkerNames.get(),
      includeAudio = includeAudioOverride ?: browserPreferences.includeAudioBrowser.get(),
      minimumAudioDurationSeconds = browserPreferences.minimumAudioDurationSeconds.get(),
    )

  private data class TreeViewNewBadgeParams(
    val showNewLabels: Boolean,
    val thresholdDays: Int,
    val playedMediaTitles: Set<String>,
    val newLabelOverrides: Map<String, Boolean>,
  )

  private suspend fun getTreeViewNewBadgeParams(): TreeViewNewBadgeParams {
    val showNewLabels = appearancePreferences.showUnplayedOldVideoLabel.get()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val states = if (showNewLabels) playbackStateRepository.getAllPlaybackStates() else emptyList()
    val playedMediaTitles = states.filter { it.hasBeenWatched }.mapTo(mutableSetOf()) { it.mediaTitle }
    val overrides = states.mapNotNull { state -> state.newLabelOverride?.let { state.mediaTitle to it } }.toMap()

    return TreeViewNewBadgeParams(showNewLabels, thresholdDays, playedMediaTitles, overrides)
  }

  /**
   * Clears all caches
   * Call this when media library changes are detected or when forcing a hard refresh
   */
  fun clearCache() {
    Log.d(TAG, "Clearing all caches (FolderViewScanner + TreeViewScanner)")
    FolderViewScanner.clearCache()
    TreeViewScanner.clearCache()
  }

  /** Invalidates only the album MediaStore snapshot, preserving tree and filesystem indexes. */
  fun invalidateFolderCache() {
    FolderViewScanner.clearCache()
  }

  fun invalidateTreeCache() {
    TreeViewScanner.clearCache()
  }

  // =============================================================================
  // FOLDER OPERATIONS (Album View)
  // =============================================================================

  /**
   * Scans all storage volumes to find all folders containing videos
   */
  suspend fun getAllVideoFolders(
    context: Context,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      try {
        val mediaStoreFolders =
          FolderViewScanner.getAllVideoFolders(
            context,
            currentScanOptions(includeAudioOverride),
            forceFileSystemCheck,
          )
        val indexedFolders =
          FolderViewScanner.getIndexedNoMediaFolders(
            currentScanOptions(includeAudioOverride),
            database.directoryScanDao(),
          )
        (mediaStoreFolders + indexedFolders)
          .distinctBy { it.path.lowercase(Locale.ROOT) }
          .sortedBy { it.name.lowercase(Locale.getDefault()) }
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning for video folders", e)
        emptyList()
      }
    }

  /** Fast MediaStore-only phase. Hidden folders arrive through the incremental flow below. */
  suspend fun getAllVideoFoldersFast(
    context: Context,
    onProgress: ((Int) -> Unit)? = null,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      val options = currentScanOptions(includeAudioOverride)
      val folders = FolderViewScanner
        .getAllVideoFolders(
          context = context,
          options = options,
          forceFileSystemCheck = forceFileSystemCheck,
          onSnapshot = onSnapshot,
        )
      folders.distinctBy { it.path.lowercase(Locale.ROOT) }
        .sortedBy { it.name.lowercase(Locale.getDefault()) }
        .also { onProgress?.invoke(it.size) }
    }

  suspend fun getIndexedNoMediaFolders(): List<VideoFolder> =
    FolderViewScanner.getIndexedNoMediaFolders(currentScanOptions(), database.directoryScanDao())

  /**
   * Scans MediaStore for all audio (music) files and groups them by parent folder.
   * Powers the dedicated Music tab so audio is kept separate from the video browser.
   */
  suspend fun getAllAudioFolders(
    context: Context,
    minimumAudioDurationSeconds: Int = 0,
    onSnapshot: (suspend (List<VideoFolder>) -> Unit)? = null,
  ): List<VideoFolder> =
    withContext(Dispatchers.IO) {
      val aggregates = linkedMapOf<String, AudioFolderAggregate>()
      val publisher = app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher(onSnapshot) {
        aggregates.values.map { folder ->
          VideoFolder(folder.path, leafName(folder.path), folder.path, folder.count, folder.size, folder.duration, folder.lastModified)
        }
      }
      try {
        val projection =
          arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
          )
        context.contentResolver
          .query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null,
          )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
              currentCoroutineContext().ensureActive()
              val path = cursor.getString(dataColumn)
              if (path == null) continue
              val file = File(path)
              // On Android 10+ the file may not be directly accessible via the file system
              // even though it exists in MediaStore. Only skip if the path is invalid.
              val parentPath = file.parent ?: continue
              if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookPath(parentPath)) continue
              val durationMs = cursor.getLong(durationColumn)
              if (minimumAudioDurationSeconds > 0 && durationMs / 1000 < minimumAudioDurationSeconds) continue
              val key = normalizeAudioFolderKey(parentPath)
              val aggregate = aggregates.getOrPut(key) { AudioFolderAggregate(path = parentPath) }
              aggregate.size += cursor.getLong(sizeColumn)
              aggregate.duration += durationMs
              aggregate.lastModified = maxOf(aggregate.lastModified, cursor.getLong(dateColumn))
              aggregate.count += 1
              publisher.publishIfNeeded()
            }
          }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning for audio folders", e)
        if (onSnapshot != null) throw e
      }
      publisher.publishIfNeeded(force = true)
      aggregates.values
        .map { agg ->
          VideoFolder(
            bucketId = agg.path,
            name = leafName(agg.path),
            path = agg.path,
            videoCount = agg.count,
            totalSize = agg.size,
            totalDuration = agg.duration,
            lastModified = agg.lastModified,
          )
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

  /**
   * Searches MediaStore audio by title/artist/album/display name for the Music tab search.
   */
  suspend fun searchAudio(
    context: Context,
    query: String,
    limit: Int = 50,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val results = mutableListOf<Video>()
      try {
        val like = "%${query.trim()}%"
        val selection =
          "${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
            "${MediaStore.Audio.Media.ARTIST} LIKE ? OR " +
            "${MediaStore.Audio.Media.ALBUM} LIKE ? OR " +
            "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?"
        val projection =
          arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
          )
        context.contentResolver
          .query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(like, like, like, like),
            "${MediaStore.Audio.Media.TITLE} ASC",
          )?.use { cursor ->
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val displayColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            var guard = 0
            while (cursor.moveToNext() && guard < limit) {
              guard++
              val path = cursor.getString(dataColumn)
              val file = File(path)
              if (!file.exists()) continue
              if (app.gyrolet.mpvrx.domain.audiobook.AudiobookMarkerUtils.isAudiobookPath(path)) continue
              val durationMs = cursor.getLong(durationColumn)
              val title = cursor.getString(titleColumn) ?: file.nameWithoutExtension
              val displayName = cursor.getString(displayColumn) ?: file.name
              val folderPath = file.parent ?: ""
              val folderName = file.parentFile?.name ?: ""
              results +=
                Video(
                  id = path.hashCode().toLong(),
                  title = title,
                  displayName = displayName,
                  path = path,
                  uri = Uri.fromFile(file),
                  duration = durationMs,
                  durationFormatted = formatDuration(durationMs),
                  size = cursor.getLong(sizeColumn),
                  sizeFormatted = formatFileSize(cursor.getLong(sizeColumn)),
                  dateModified = cursor.getLong(dateColumn),
                  dateAdded = cursor.getLong(dateColumn),
                  mimeType = FileTypeUtils.getMimeTypeFromExtension(file.extension.lowercase()),
                  bucketId = folderPath,
                  bucketDisplayName = folderName,
                  width = 0,
                  height = 0,
                  fps = 0f,
                  resolution = "",
                  isAudio = true,
                )
            }
          }
      } catch (e: Exception) {
        Log.e(TAG, "Error searching audio", e)
      }
      results
    }

  private data class AudioFolderAggregate(
    val path: String,
    var size: Long = 0L,
    var duration: Long = 0L,
    var lastModified: Long = 0L,
    var count: Int = 0,
  )

  private fun normalizeAudioFolderKey(path: String): String =
    path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

  private fun leafName(path: String): String =
    path.replace('\\', '/').trimEnd('/').substringAfterLast('/')

  fun scanNoMediaFoldersIncrementally(
    context: Context,
    forceDiscovery: Boolean = false,
  ): Flow<List<VideoFolder>> =
    FolderViewScanner.scanNoMediaFoldersIncrementally(
      context = context,
      options = currentScanOptions(),
      dao = database.directoryScanDao(),
      forceDiscovery = forceDiscovery,
    )

  /**
   * No-op enrichment - MediaStore already provides all metadata
   * Kept for backward compatibility
   */
  suspend fun enrichVideoFolders(
    context: Context,
    folders: List<VideoFolder>,
    onProgress: ((Int, Int) -> Unit)? = null,
  ): List<VideoFolder> = folders

  // =============================================================================
  // VIDEO FILE OPERATIONS
  // =============================================================================

  /**
   * Gets all videos in a specific folder
   * @param bucketId Folder path
   */
  suspend fun getVideosInFolder(
    context: Context,
    bucketId: String,
    forceFileSystemCheck: Boolean = false,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      try {
        if (ZipArchiveMedia.isBrowserPath(bucketId)) {
          return@withContext ZipArchiveMedia.allMedia(
            context,
            bucketId,
            currentScanOptions(includeAudioOverride).includeAudio,
          ).getOrThrow()
        }
        VideoScanUtils.getVideosInFolder(
          context,
          bucketId,
          currentScanOptions(includeAudioOverride),
          forceFileSystemCheck,
          onSnapshot,
        )
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error getting videos for bucket $bucketId", e)
        if (onSnapshot != null) throw e
        emptyList()
      }
    }

  /**
   * Gets videos from multiple folders
   * Shows all videos including hidden ones.
   */
  suspend fun getVideosForBuckets(
    context: Context,
    bucketIds: Set<String>,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val result = linkedMapOf<String, Video>()
      suspend fun publish(videos: List<Video>) {
        for (media in videos) {
          val key = mediaPathKey(media.path) ?: media.path
          val existing = result[key]
          if (existing == null || shouldReplaceMedia(existing, media)) result[key] = media
        }
        onSnapshot?.invoke(result.values.toList())
      }
      for (id in bucketIds) {
        try {
          publish(getVideosInFolder(
            context,
            id,
            includeAudioOverride = includeAudioOverride,
            onSnapshot = if (onSnapshot == null) null else ::publish,
          ))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.w(TAG, "Unable to scan folder $id", error)
          if (onSnapshot != null) throw error
        }
      }
      result.values.toList()
    }

  private fun shouldReplaceMedia(
    existing: Video,
    candidate: Video,
  ): Boolean {
    val extensionIsAudio = FileTypeUtils.isAudioFile(File(candidate.path))
    val existingClassificationIsCorrect = existing.isAudio == extensionIsAudio
    val candidateClassificationIsCorrect = candidate.isAudio == extensionIsAudio
    if (existingClassificationIsCorrect != candidateClassificationIsCorrect) {
      return candidateClassificationIsCorrect
    }
    if ((existing.duration > 0L) != (candidate.duration > 0L)) return candidate.duration > 0L
    if ((existing.size > 0L) != (candidate.size > 0L)) return candidate.size > 0L
    return candidate.dateModified > existing.dateModified
  }

  /**
   * Creates Video objects from a list of files
   */
  suspend fun getVideosFromFiles(
    context: Context,
    files: List<File>,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      files.mapNotNull { file ->
        try {
          val folderPath = file.parent ?: ""
          val folderName = file.parentFile?.name ?: ""
          createVideoFromFile(context, file, folderPath, folderName)
        } catch (e: Exception) {
          Log.w(TAG, "Error creating video from file: ${file.absolutePath}", e)
          null
        }
      }
    }

  /**
   * Creates a Video object from a file with full metadata extraction
   */
  private suspend fun createVideoFromFile(
    context: Context,
    file: File,
    bucketId: String,
    bucketDisplayName: String,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Extract metadata directly (no cache)
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f
    var hasEmbeddedSubtitles = false
    var subtitleCodec = ""

    // Extract metadata using MediaInfo
    MediaInfoOps.extractBasicMetadata(context, uri, displayName).onSuccess { metadata ->
      if (metadata.sizeBytes > 0) size = metadata.sizeBytes
      duration = metadata.durationMs
      width = metadata.width
      height = metadata.height
      fps = metadata.fps
      hasEmbeddedSubtitles = metadata.hasEmbeddedSubtitles
      subtitleCodec = metadata.subtitleCodec
    }

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }

  /**
   * Creates a Video object from a file with pre-fetched metadata
   * Use this when metadata has already been batch-extracted
   */
  private fun createVideoFromFileWithMetadata(
    file: File,
    bucketId: String,
    bucketDisplayName: String,
    metadata: MediaInfoOps.VideoMetadata?,
  ): Video {
    val path = file.absolutePath
    val displayName = file.name
    val title = file.nameWithoutExtension
    val dateModified = file.lastModified() / 1000

    val extension = file.extension.lowercase()
    val mimeType = FileTypeUtils.getMimeTypeFromExtension(extension)
    val uri = Uri.fromFile(file)

    // Use pre-fetched metadata
    var size = file.length()
    var duration = 0L
    var width = 0
    var height = 0
    var fps = 0f

    metadata?.let {
      if (it.sizeBytes > 0) size = it.sizeBytes
      duration = it.durationMs
      width = it.width
      height = it.height
      fps = it.fps
    }
    val hasEmbeddedSubtitles = metadata?.hasEmbeddedSubtitles ?: false
    val subtitleCodec = metadata?.subtitleCodec ?: ""

    return Video(
      id = path.hashCode().toLong(),
      title = title,
      displayName = displayName,
      path = path,
      uri = uri,
      duration = duration,
      durationFormatted = formatDuration(duration),
      size = size,
      sizeFormatted = formatFileSize(size),
      dateModified = dateModified,
      dateAdded = dateModified,
      mimeType = mimeType,
      bucketId = bucketId,
      bucketDisplayName = bucketDisplayName,
      width = width,
      height = height,
      fps = fps,
      resolution = VideoScanUtils.formatResolutionWithFps(width, height, fps),
      hasEmbeddedSubtitles = hasEmbeddedSubtitles,
      subtitleCodec = subtitleCodec,
    )
  }

  suspend fun getAllVideos(
    context: Context,
    includeAudioOverride: Boolean? = null,
    onSnapshot: (suspend (List<Video>) -> Unit)? = null,
  ): List<Video> =
    withContext(Dispatchers.IO) {
      val videos = linkedMapOf<String, Video>()
      val scanned = hashSetOf<String>()
      suspend fun publish(partial: List<Video>) {
        partial.forEach { videos[mediaPathKey(it.path) ?: it.path] = it }
        onSnapshot?.invoke(videos.values.toList())
      }
      suspend fun scan(folders: List<VideoFolder>) {
        for (folder in folders) {
          if (!scanned.add(folder.bucketId.lowercase(Locale.ROOT))) continue
          publish(getVideosInFolder(context, folder.bucketId, includeAudioOverride = includeAudioOverride,
            onSnapshot = if (onSnapshot == null) null else ::publish))
        }
      }
      val folders = getAllVideoFoldersFast(context, includeAudioOverride = includeAudioOverride,
        onSnapshot = if (onSnapshot == null) null else ::scan)
      scan(folders)
      scan(FolderViewScanner.getIndexedNoMediaFolders(currentScanOptions(includeAudioOverride), database.directoryScanDao()))
      videos.values.toList()
    }

  // =============================================================================
  // FILE SYSTEM BROWSING (Tree View)
  // =============================================================================

  /**
   * Gets the default root path for the filesystem browser
   */
  fun getDefaultRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

  /**
   * Parses a path into breadcrumb components
   */
  fun getPathComponents(path: String): List<PathComponent> {
    if (path.isBlank()) return emptyList()

    val components = mutableListOf<PathComponent>()
    val normalizedPath = path.trimEnd('/')
    val parts = normalizedPath.split("/").filter { it.isNotEmpty() }

    components.add(PathComponent("Root", "/"))

    var currentPath = ""
    for (part in parts) {
      currentPath += "/$part"
      components.add(PathComponent(part, currentPath))
    }

    return components
  }

  /**
   * Scans a directory and returns its contents (folders and video files)
   * OPTIMIZED: Uses UnifiedMediaScanner for fast, consistent results
   * @param showAllFileTypes If true, shows all files. If false, shows only videos.
   * @param useFastCount If true, uses fast shallow counting (immediate children only). If false, uses deep recursive counting.
   */
  suspend fun scanDirectory(
    context: Context,
    path: String,
    showAllFileTypes: Boolean = false,
    useFastCount: Boolean = false,
    forceFileSystemCheck: Boolean = false,
    onSnapshot: (suspend (List<FileSystemItem>) -> Unit)? = null,
  ): Result<List<FileSystemItem>> =
    withContext(Dispatchers.IO) {
      try {
        val scanOptions = currentScanOptions()
        if (ZipArchiveMedia.isBrowserPath(path)) {
          return@withContext ZipArchiveMedia.scan(context, path, scanOptions.includeAudio)
        }
        val directory = File(path)

        // Validation checks
        if (!directory.exists()) {
          return@withContext Result.failure(Exception("Directory does not exist: $path"))
        }

        if (!directory.canRead()) {
          return@withContext Result.failure(Exception("Cannot read directory: $path"))
        }

        if (!directory.isDirectory) {
          return@withContext Result.failure(Exception("Path is not a directory: $path"))
        }

        val items = mutableListOf<FileSystemItem>()
        if (onSnapshot != null) {
          val initial = mutableListOf<FileSystemItem>()
          val noMedia = app.gyrolet.mpvrx.utils.storage.NoMediaPathFilter(scanOptions)
          val publisher = app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher(onSnapshot) { initial }
          for (file in directory.listFiles().orEmpty()) {
            when {
              file.isDirectory && !app.gyrolet.mpvrx.utils.storage.FileFilterUtils.shouldSkipFolder(file, scanOptions, noMedia) ->
                initial += FileSystemItem.Folder(file.name, file.absolutePath, file.lastModified())
              file.isFile && FileTypeUtils.isSupportedMediaFile(file, scanOptions) &&
                !app.gyrolet.mpvrx.utils.storage.FileFilterUtils.shouldSkipFile(file, scanOptions, noMedia) &&
                (!FileTypeUtils.isAudioFile(file) || scanOptions.includesAudioDuration(0L)) -> {
                val video = createVideoFromFileWithMetadata(file, path, directory.name, metadata = null)
                  .copy(isAudio = FileTypeUtils.isAudioFile(file))
                initial += FileSystemItem.VideoFile(file.name, file.absolutePath, file.lastModified(), video)
              }
            }
            publisher.publishIfNeeded()
          }
          if (initial.isNotEmpty()) publisher.publishIfNeeded(force = true)
        }

        // Get folders using TreeViewScanner (instant from cache)
        val (showNewLabels, thresholdDays, playedMediaTitles, newLabelOverrides) = getTreeViewNewBadgeParams()
        val folders =
          TreeViewScanner.getFoldersInDirectory(
            context = context,
            parentPath = path,
            options = scanOptions,
            forceFileSystemCheck = forceFileSystemCheck,
            playedMediaTitles = playedMediaTitles,
            showNewLabels = showNewLabels,
            thresholdDays = thresholdDays,
            maxAutoFlattenLevels = browserPreferences.treeFlattenDepth.get().maxLevels,
            newLabelOverrides = newLabelOverrides,
          )
        folders.forEach { folderData ->
          items.add(
            FileSystemItem.Folder(
              name = folderData.name,
              path = folderData.path,
              lastModified = File(folderData.path).lastModified(),
              videoCount = folderData.videoCount,
              totalSize = folderData.totalSize,
              totalDuration = folderData.totalDuration,
              hasSubfolders = folderData.hasSubfolders,
              newCount = folderData.newCount,
            ),
          )
        }

        items.addAll(ZipArchiveMedia.archiveFoldersIn(directory, scanOptions.includeAudio))
        if (items.isNotEmpty()) onSnapshot?.invoke(items.toList())

        // Get videos in current directory
        val videos = VideoScanUtils.getVideosInFolder(context, path, scanOptions, forceFileSystemCheck,
          onSnapshot = if (onSnapshot == null) null else { partial ->
            onSnapshot(items + partial.map { video ->
              FileSystemItem.VideoFile(video.displayName, video.path, video.dateModified * 1000L, video)
            })
          })
        videos.forEach { video ->
          items.add(
            FileSystemItem.VideoFile(
              name = video.displayName,
              path = video.path,
              lastModified = File(video.path).lastModified(),
              video = video,
            ),
          )
        }

        Result.success(items)
      } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
      } catch (e: SecurityException) {
        Log.e(TAG, "Security exception scanning directory: $path", e)
        Result.failure(Exception("Permission denied: ${e.message}"))
      } catch (e: Exception) {
        Log.e(TAG, "Error scanning directory: $path", e)
        Result.failure(e)
      }
    }

  /**
   * Gets all storage volume roots with recursive video counts
   */
  suspend fun getStorageRoots(
    context: Context,
    forceFileSystemCheck: Boolean = false,
    includeCounts: Boolean = true,
    onSnapshot: (suspend (List<FileSystemItem.Folder>) -> Unit)? = null,
  ): List<FileSystemItem.Folder> =
    withContext(Dispatchers.IO) {
      val roots = mutableListOf<FileSystemItem.Folder>()

      try {
        // Primary storage (internal)
        val primaryStorage = Environment.getExternalStorageDirectory()
        if (primaryStorage.exists() && primaryStorage.canRead()) {
          val primaryPath = primaryStorage.absolutePath

          roots.add(
            FileSystemItem.Folder(
              name = "Internal Storage",
              path = primaryPath,
              lastModified = primaryStorage.lastModified(),
              hasSubfolders = true,
            ),
          )
        }

        // External volumes (SD cards, USB OTG)
        val externalVolumes = StorageVolumeUtils.getExternalStorageVolumes(context)
        for (volume in externalVolumes) {
          val volumePath = StorageVolumeUtils.getVolumePath(volume)
          if (volumePath != null) {
            val volumeDir = File(volumePath)
            if (volumeDir.exists() && volumeDir.canRead()) {
              val volumeName = volume.getDescription(context)

              roots.add(
                FileSystemItem.Folder(
                  name = volumeName,
                  path = volumeDir.absolutePath,
                  lastModified = volumeDir.lastModified(),
                  hasSubfolders = true,
                ),
              )
            }
          }
        }
        onSnapshot?.invoke(roots.toList())
        if (includeCounts) {
          val (showNewLabels, thresholdDays, playedMediaTitles, newLabelOverrides) = getTreeViewNewBadgeParams()
          for (index in roots.indices) {
            currentCoroutineContext().ensureActive()
            val root = roots[index]
            val folderData = TreeViewScanner.getFolderDataRecursive(
              context,
              root.path,
              currentScanOptions(),
              forceFileSystemCheck,
              playedMediaTitles,
              showNewLabels,
              thresholdDays,
              newLabelOverrides,
            )
            roots[index] = root.copy(
              videoCount = folderData?.videoCount ?: 0,
              totalSize = folderData?.totalSize ?: 0L,
              totalDuration = folderData?.totalDuration ?: 0L,
              newCount = folderData?.newCount ?: 0,
            )
            onSnapshot?.invoke(roots.toList())
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(TAG, "Error getting storage roots", e)
      }

      roots.toList()
    }

  // =============================================================================
  // FORMATTING UTILITIES
  // =============================================================================

  private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0s"

    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60

    return when {
      hours > 0 -> String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
      minutes > 0 -> String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
      else -> "${secs}s"
    }
  }

  private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
    return String.format(
      Locale.getDefault(),
      "%.1f %s",
      bytes / 1024.0.pow(digitGroups.toDouble()),
      units[digitGroups],
    )
  }

}
