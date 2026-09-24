/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.filesystem

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.browser.PathComponent
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps
import app.gyrolet.mpvrx.utils.sort.SortUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import app.gyrolet.mpvrx.utils.storage.TreeViewScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * ViewModel for FileSystem Browser - based on Fossify's ItemsFragment logic
 * Handles directory navigation, file loading, sorting, and state management
 */
class FileSystemBrowserViewModel(
  application: Application,
  initialPath: String? = null,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val appearancePreferences: app.gyrolet.mpvrx.preferences.AppearancePreferences by inject()

  // Special marker for "show storage volumes" mode
  // Similar to Fossify's root/home folder detection
  private val STORAGE_ROOTS_MARKER = "__STORAGE_ROOTS__"

  // Home directory - the top-most directory we can navigate to (set once on init)
  // This prevents navigation errors when the app opens to a specific storage volume
  private var homeDirectory: String? = null

  // Current directory path - corresponds to Fossify's currentPath
  // If initialPath is null, we'll determine it after checking storage volumes
  private val _currentPath = MutableStateFlow(initialPath ?: STORAGE_ROOTS_MARKER)
  val currentPath: StateFlow<String> = _currentPath.asStateFlow()

  // Unsorted items from filesystem scan - before sorting is applied
  // Similar to Fossify's items list before sorting
  private val _unsortedItems = MutableStateFlow<List<FileSystemItem>>(emptyList())

  // Sorted and filtered items ready for display
  // Similar to Fossify's final sorted items list
  private val _items = MutableStateFlow<List<FileSystemItem>>(emptyList())
  val items: StateFlow<List<FileSystemItem>> = _items.asStateFlow()

  // Video playback progress map - similar to Fossify's playback tracking
  private val _videoFilesWithPlayback = MutableStateFlow<Map<Long, Float>>(emptyMap())
  val videoFilesWithPlayback: StateFlow<Map<Long, Float>> = _videoFilesWithPlayback.asStateFlow()

  // Set of videos that should show the NEW label in tree view
  private val _newVideoIds = MutableStateFlow<Set<Long>>(emptySet())
  val newVideoIds: StateFlow<Set<Long>> = _newVideoIds.asStateFlow()

  private val _watchedVideoIds = MutableStateFlow<Set<Long>>(emptySet())
  val watchedVideoIds: StateFlow<Set<Long>> = _watchedVideoIds.asStateFlow()

  // Loading state - similar to Fossify's showProgressBar/hideProgressBar
  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  // Error state for displaying error messages
  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  // Breadcrumb components for navigation - similar to Fossify's Breadcrumbs
  private val _breadcrumbs = MutableStateFlow<List<PathComponent>>(emptyList())
  val breadcrumbs: StateFlow<List<PathComponent>> = _breadcrumbs.asStateFlow()

  // Whether we're at the home directory (top-most allowed directory)
  // Similar to Fossify's check for home folder or root
  val isAtRoot: StateFlow<Boolean> =
    MutableStateFlow(initialPath == null).apply {
      viewModelScope.launch {
        _currentPath.collect { path ->
          value = path == STORAGE_ROOTS_MARKER || path == homeDirectory
        }
      }
    }

  // Track if items were deleted/moved leaving folder empty
  private val _itemsWereDeletedOrMoved = MutableStateFlow(false)
  val itemsWereDeletedOrMoved: StateFlow<Boolean> = _itemsWereDeletedOrMoved.asStateFlow()

  // Track previous item count per path to detect if folder became empty
  private val itemCountByPath = mutableMapOf<String, Int>()
  private var directoryLoadJob: Job? = null
  private var displayedDirectoryPath: String? = null

  companion object {
    private const val TAG = "FileSystemBrowserVM"

    fun factory(
      application: Application,
      initialPath: String? = null,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FileSystemBrowserViewModel(application, initialPath) as T
    }
  }

  init {
    // If no initial path was specified, check storage volumes and navigate accordingly
    if (initialPath == null) {
      viewModelScope.launch {
        val roots = MediaFileRepository.getStorageRoots(getApplication(), includeCounts = false)
        if (roots.size == 1) {
          // Only one storage volume, navigate directly to it and set as home
          val singleRoot = roots.first()
          homeDirectory = singleRoot.path
          Log.d(TAG, "Single storage volume found, setting as home: ${singleRoot.path}")
          _currentPath.value = singleRoot.path
        } else {
          // Multiple roots - home is the storage roots view
          homeDirectory = null
        }
        // If multiple roots or none, stay at STORAGE_ROOTS_MARKER
        loadCurrentDirectory()
      }
    } else {
      // Specific path provided - set it as home directory
      homeDirectory = initialPath
      Log.d(TAG, "Initial path provided, setting as home: $initialPath")
      // Load initial directory - similar to Fossify's openPath() in onCreate
      loadCurrentDirectory()
    }

    // Refresh on global media library changes
    // Similar to Fossify's media scan completion listener
    viewModelScope.launch {
      MediaLibraryEvents.changes.collectLatest {
        MediaFileRepository.invalidateTreeCache()
        loadCurrentDirectory()
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest {
        if (_unsortedItems.value.isNotEmpty()) {
          applyPlaybackState(_unsortedItems.value)
        }
      }
    }

    // Apply sorting whenever items or sort preferences change
    // Based on Fossify's ChangeSortingDialog callback and sorting logic
    viewModelScope.launch {
      combine(
        _unsortedItems,
        browserPreferences.folderSortType.changes(),
        browserPreferences.folderSortOrder.changes(),
      ) { items, sortType, sortOrder ->
        // Sort using the same logic as Fossify's FileDirItem.sort()
        SortUtils.sortFileSystemItems(items, sortType, sortOrder)
      }.collectLatest { sortedItems ->
        _items.value = sortedItems
        Log.d(TAG, "Items sorted: ${sortedItems.size} items")
      }
    }

    // Recalculate NEW badges when the appearance preference changes.
    viewModelScope.launch {
      combine(
        appearancePreferences.showUnplayedOldVideoLabel.changes(),
        appearancePreferences.unplayedOldVideoDays.changes(),
      ) { showLabels, thresholdDays ->
        showLabels to thresholdDays
      }.drop(1)
        .collectLatest {
          loadCurrentDirectory()
        }
    }

    // The cached media topology is reusable; only the visible flatten depth changes.
    viewModelScope.launch {
      browserPreferences.treeFlattenDepth
        .changes()
        .drop(1)
        .collectLatest { loadCurrentDirectory() }
    }
  }

  /**
   * Refresh current directory
   * Equivalent to Fossify's refreshFragment() callback
   */
  override fun refresh() {
    Log.d(TAG, "Hard refreshing current directory: ${_currentPath.value}")

    // Set loading state
    _isLoading.value = true

    // Clear all caches to force fresh data from filesystem
    MediaFileRepository.clearCache()
    FolderViewScanner.clearCache()
    TreeViewScanner.clearCache()

    // Trigger media scan to ensure MediaStore is up-to-date
    triggerMediaScan()

    loadCurrentDirectory(forceFileSystemCheck = true)
  }

  /**
   * Trigger a media scan for the current directory
   */
  private fun triggerMediaScan() {
    try {
      val path = _currentPath.value

      // Skip if we're at storage roots marker
      if (path == STORAGE_ROOTS_MARKER || ZipArchiveMedia.isBrowserPath(path)) {
        return
      }

      val folder = File(path)

      if (folder.exists() && folder.isDirectory) {
        // Scan all video files in the folder
        val videoFiles =
          folder.listFiles { file ->
            file.isFile &&
              file.extension.lowercase() in
              listOf(
                "mp4",
                "mkv",
                "avi",
                "mov",
                "wmv",
                "flv",
                "webm",
                "m4v",
                "3gp",
                "mpg",
                "mpeg",
                "ts",
                "m2ts",
              )
          }

        if (!videoFiles.isNullOrEmpty()) {
          val filePaths = videoFiles.map { it.absolutePath }.toTypedArray()

          android.media.MediaScannerConnection.scanFile(
            getApplication(),
            filePaths,
            null, // Let MediaScanner detect MIME types
          ) { scanPath, uri ->
            Log.d(TAG, "Media scan completed for: $scanPath -> $uri")
          }

          Log.d(TAG, "Triggered media scan for ${filePaths.size} files in: $path")
        } else {
          Log.d(TAG, "No video files found in folder: $path")
        }
      } else {
        // Fallback to scanning external storage root
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        android.media.MediaScannerConnection.scanFile(
          getApplication(),
          arrayOf(externalStorage.absolutePath),
          null,
        ) { scanPath, uri ->
          Log.d(TAG, "Media scan completed for: $scanPath -> $uri")
        }
        Log.d(TAG, "Triggered media scan for: ${externalStorage.absolutePath}")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to trigger media scan", e)
    }
  }

  /**
   * Set flag indicating items were deleted or moved
   */
  fun setItemsWereDeletedOrMoved() {
    _itemsWereDeletedOrMoved.value = true
  }

  /**
   * Delete folders (and their contents)
   * Based on Fossify's deleteFiles() logic with folder support
   */
  fun deleteFolders(folders: List<FileSystemItem.Folder>): Pair<Int, Int> {
    var successCount = 0
    var failureCount = 0
    val deleteAll = browserPreferences.deleteFolderAllContents.get()
    val includeAudio = browserPreferences.includeAudioBrowser.get()

    Log.d(TAG, "Deleting ${folders.size} folders (deleteAll=$deleteAll, includeAudio=$includeAudio)")

    folders.forEach { folder ->
      try {
        val dir = File(folder.path)
        if (!dir.exists()) {
          failureCount++
          Log.w(TAG, "Folder does not exist: ${folder.path}")
          return@forEach
        }

        if (deleteAll) {
          if (dir.deleteRecursively()) {
            successCount++
            Log.d(TAG, "Successfully deleted folder (all contents): ${folder.path}")
          } else {
            failureCount++
            Log.w(TAG, "Failed to delete folder: ${folder.path}")
          }
        } else {
          var deletedAny = false
          dir.listFiles()?.forEach { file ->
            if (file.isFile) {
              val ext = file.extension.lowercase()
              val isVideo = ext in FileTypeUtils.VIDEO_EXTENSIONS
              val isAudio = includeAudio && ext in FileTypeUtils.AUDIO_EXTENSIONS
              if (isVideo || isAudio) {
                if (file.delete()) deletedAny = true
              }
            }
          }
          // Remove empty subdirectories
          dir.listFiles()?.forEach { file ->
            if (file.isDirectory) file.delete()
          }
          if (deletedAny) {
            successCount++
            Log.d(TAG, "Deleted media files from folder: ${folder.path}")
          } else {
            failureCount++
            Log.w(TAG, "No media files found in folder: ${folder.path}")
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Exception deleting folder: ${folder.path}", e)
        failureCount++
      }
    }

    if (successCount > 0) {
      _itemsWereDeletedOrMoved.value = true
      MediaLibraryEvents.notifyChanged()
    }

    Log.d(TAG, "Folder deletion complete: $successCount success, $failureCount failed")
    return Pair(successCount, failureCount)
  }

  /**
   * Delete videos - delegates to base class implementation
   * Similar to Fossify's deleteFiles() for individual files
   */
  override suspend fun deleteVideos(videos: List<Video>): Pair<Int, Int> {
    Log.d(TAG, "Deleting ${videos.size} videos")
    val result = super.deleteVideos(videos)

    // Set flag if any deletions were successful
    if (result.first > 0) {
      _itemsWereDeletedOrMoved.value = true
    }

    return result
  }

  /**
   * Rename a video file - delegates to base class implementation
   * Based on Fossify's RenameDialog and file renaming logic
   */
  override suspend fun renameVideo(
    video: Video,
    newDisplayName: String,
  ): Result<Unit> {
    Log.d(TAG, "Renaming video ${video.displayName} to $newDisplayName")
    return super.renameVideo(video, newDisplayName)
  }

  suspend fun renameFolder(
    folder: FileSystemItem.Folder,
    newName: String,
  ): Boolean {
    val ok = StorageOps.renameFolder(getApplication(), folder.path, newName)
    if (ok) {
      setItemsWereDeletedOrMoved()
    }
    return ok
  }

  /**
   * Load current directory contents
   * Main loading logic based on Fossify's getItems() and getRegularItemsOf()
   */
  private fun loadCurrentDirectory(forceFileSystemCheck: Boolean = false) {
    directoryLoadJob?.cancel()
    val path = _currentPath.value
    directoryLoadJob = viewModelScope.launch {
      val previous = if (displayedDirectoryPath == path) _unsortedItems.value else emptyList()
      if (displayedDirectoryPath != path) {
        _unsortedItems.value = emptyList()
        _items.value = emptyList()
        _videoFilesWithPlayback.value = emptyMap()
        _newVideoIds.value = emptySet()
        _watchedVideoIds.value = emptySet()
      }
      displayedDirectoryPath = path
      _isLoading.value = previous.isEmpty()
      _error.value = null
      // Don't reset the flag here - let navigation handle it

      try {
        // Special case: Show storage roots at the special marker
        // Similar to Fossify's StoragePickerDialog logic
        if (path == STORAGE_ROOTS_MARKER) {
          Log.d(TAG, "Loading storage roots")
          _breadcrumbs.value = emptyList()
          val roots = MediaFileRepository.getStorageRoots(
            getApplication(),
            forceFileSystemCheck,
            onSnapshot = { partial ->
              withContext(Dispatchers.Main.immediate) {
                ensureActive()
                if (_currentPath.value == path && partial.isNotEmpty()) {
                  _unsortedItems.value = partial
                  _isLoading.value = false
                }
              }
            },
          )
          ensureActive()
          _unsortedItems.value = roots
          _videoFilesWithPlayback.value = emptyMap()
          _newVideoIds.value = emptySet()
          _watchedVideoIds.value = emptySet()
          Log.d(TAG, "Loaded ${roots.size} storage roots")
        } else {
          // Update breadcrumbs for real paths
          // Similar to Fossify's Breadcrumbs.setBreadcrumb()
          _breadcrumbs.value = if (ZipArchiveMedia.isBrowserPath(path)) {
            ZipArchiveMedia.breadcrumbs(path)
          } else {
            MediaFileRepository.getPathComponents(path)
          }
          Log.d(TAG, "Breadcrumbs updated: ${_breadcrumbs.value.size} components")

          // Get hidden files preference
          // Scan directory - equivalent to Fossify's getRegularItemsOf()
          // Always show only videos (showAllFileTypes = false)
          MediaFileRepository
            .scanDirectory(
              getApplication(),
              path,
              showAllFileTypes = false,
              forceFileSystemCheck = forceFileSystemCheck,
              onSnapshot = { partial ->
                withContext(Dispatchers.Main.immediate) {
                  ensureActive()
                  if (_currentPath.value == path && partial.isNotEmpty()) {
                    val paths = partial.mapTo(HashSet()) { it.path }
                    _unsortedItems.value = partial + _unsortedItems.value.filterNot { it.path in paths }
                    _isLoading.value = false
                  }
                }
              },
            ).onSuccess { items ->
              ensureActive()
              // Get previous count for this path
              val previousCount = itemCountByPath[path] ?: 0

              // Check if folder became empty after having items
              if (previousCount > 0 && items.isEmpty()) {
                _itemsWereDeletedOrMoved.value = true
                Log.d(TAG, "Folder became empty (had $previousCount items before)")
              } else if (items.isNotEmpty()) {
                // Reset flag if folder now has items
                _itemsWereDeletedOrMoved.value = false
              }

              // Update count for this path
              itemCountByPath[path] = items.size

              _unsortedItems.value = items
              if (items.isNotEmpty()) _isLoading.value = false

              val folderCount = items.filterIsInstance<FileSystemItem.Folder>().size
              val videoCount = items.filterIsInstance<FileSystemItem.VideoFile>().size
              Log.d(TAG, "Loaded directory: $path with $folderCount folders, $videoCount videos")

              // ZIP entries need basic metadata even when optional metadata chips are disabled.
              val enrichedItems =
                if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences) ||
                  items.any { item ->
                    item is FileSystemItem.VideoFile && ZipArchiveMedia.isPlaybackUri(item.video.uri.toString())
                  }
                ) {
                  Log.d(TAG, "Enriching $videoCount videos with metadata")
                  val videoFiles = items.filterIsInstance<FileSystemItem.VideoFile>()
                  val videos = videoFiles.map { it.video }
                  val enrichedVideos =
                    withContext(Dispatchers.IO) {
                      MetadataRetrieval.enrichVideosIfNeeded(
                        context = getApplication(),
                        videos = videos,
                        browserPreferences = browserPreferences,
                        metadataCache = metadataCache,
                        onSnapshot = { partial ->
                          withContext(Dispatchers.Main.immediate) {
                            ensureActive()
                            if (_currentPath.value == path) {
                              val byPath = partial.associateBy { it.path }
                              _unsortedItems.value = items.map { item ->
                                if (item is FileSystemItem.VideoFile) item.copy(video = byPath[item.path] ?: item.video) else item
                              }
                            }
                          }
                        },
                      )
                    }

                  // Replace videos in items with enriched versions
                  val enrichedVideoMap = enrichedVideos.associateBy { it.id }
                  items.map { item ->
                    when (item) {
                      is FileSystemItem.VideoFile -> {
                        val enrichedVideo = enrichedVideoMap[item.video.id]
                        if (enrichedVideo != null) {
                          item.copy(video = enrichedVideo)
                        } else {
                          item
                        }
                      }
                      else -> item
                    }
                  }
                } else {
                  items
                }

              ensureActive()
              _unsortedItems.value = enrichedItems

              // Load playback info and NEW-state data for videos in the current tree view.
              applyPlaybackState(enrichedItems)
            }.onFailure { error ->
              if (error is CancellationException) throw error
              ensureActive()
              _error.value = error.message
              Log.e(TAG, "Error loading directory: $path", error)
            }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (e: Exception) {
        ensureActive()
        _error.value = e.message
        Log.e(TAG, "Exception loading directory", e)
      } finally {
        if (isActive) _isLoading.value = false
      }
    }
  }

  /**
   * Load playback progress information for video files
   * Based on playback state tracking (not directly in Fossify, but similar pattern)
   */
  private suspend fun applyPlaybackState(items: List<FileSystemItem>) {
    val videoFiles = items.filterIsInstance<FileSystemItem.VideoFile>()
    val playbackStates = playbackStateRepository.getAllPlaybackStates().associateBy { it.mediaTitle }
    val playbackMap = mutableMapOf<Long, Float>()
    val newIds = mutableSetOf<Long>()
    val watchedIds = mutableSetOf<Long>()
    val currentTime = System.currentTimeMillis()
    val showNewLabels = appearancePreferences.showUnplayedOldVideoLabel.get()
    val thresholdMillis = appearancePreferences.unplayedOldVideoDays.get().toLong() * 24L * 60L * 60L * 1000L
    val watchedThreshold = browserPreferences.watchedThreshold.get()

    Log.d(TAG, "Loading playback info for ${videoFiles.size} videos")

    videoFiles.forEach { videoFile ->
      val video = videoFile.video
      val playbackIdentifiers =
        linkedSetOf(
          PlaybackIdentity.forLocalPath(video.path),
          PlaybackIdentity.forUri(video.uri.toString()),
          PlaybackIdentity.forUri(video.path),
          PlaybackIdentity.forUri("file://${video.path}"),
        )
      val playbackState = playbackIdentifiers.firstNotNullOfOrNull { playbackStates[it] }
      val progressValue =
        if (playbackState != null && video.duration > 0) {
          val durationSeconds = video.duration / 1000.0
          val watched = durationSeconds - playbackState.timeRemaining.toDouble()
          (watched / durationSeconds).toFloat().coerceIn(0f, 1f)
        } else {
          0f
        }
      val isWatched =
        playbackState?.hasBeenWatched == true ||
          (watchedThreshold > 0 && progressValue >= watchedThreshold / 100f)
      if (isWatched) watchedIds += video.id

      if (playbackState != null && progressValue in 0.01f..0.99f) {
        playbackMap[video.id] = progressValue
      }

      val videoAge = currentTime - (video.dateModified * 1000L)
      val isWithinNewWindow = thresholdMillis == 0L || videoAge <= thresholdMillis
      if (showNewLabels && !isWatched && (playbackState?.newLabelOverride ?: isWithinNewWindow)) {
        newIds += video.id
      }
    }

    withContext(Dispatchers.Main.immediate) {
      if (_unsortedItems.value != items) return@withContext
      _videoFilesWithPlayback.value = playbackMap
      _newVideoIds.value = newIds
      _watchedVideoIds.value = watchedIds
      Log.d(TAG, "Loaded playback info for ${playbackMap.size} videos with progress")
    }
  }

  fun setWatched(video: Video, watched: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateOps.setWatched(video, watched)
    }
  }
}
