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
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.VisibleDirectoryWatcher
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps
import app.gyrolet.mpvrx.utils.sort.SortUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
  private val foldersPreferences: FoldersPreferences by inject()
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

  private data class ItemChange(
    val original: FileSystemItem,
    val directory: String,
    val replacement: FileSystemItem?,
    val revision: Long,
    val confirmed: Boolean = false,
  )

  private val itemChanges = MutableStateFlow<Map<String, ItemChange>>(emptyMap())
  private val changeRevision = AtomicLong()
  private var loadedDirectoryPath: String? = null

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
  // Cold starts must show the same loading state as Folders before asynchronous root discovery.
  private val _isLoading = MutableStateFlow(true)
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
  private var watchActive = false
  private val liveDirectoryWatcher = VisibleDirectoryWatcher { MediaLibraryEvents.notifyChanged() }

  fun startWatching() {
    watchActive = true
    liveDirectoryWatcher.watch(_currentPath.value)
  }

  fun stopWatching() {
    watchActive = false
    liveDirectoryWatcher.stop()
  }

  override fun onCleared() {
    liveDirectoryWatcher.stop()
    super.onCleared()
  }

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
      viewModelScope.launch(Dispatchers.IO) {
        val roots = MediaFileRepository.getStorageRoots(getApplication())
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

    viewModelScope.launch {
      _currentPath.collect { path ->
        if (watchActive) liveDirectoryWatcher.watch(path)
      }
    }

    // Refresh on global media library changes
    // Similar to Fossify's media scan completion listener
    viewModelScope.launch(Dispatchers.IO) {
      MediaLibraryEvents.changes.collectLatest {
        MediaFileRepository.invalidateTreeCache()
        // Drops the persisted tree snapshot too, so a mutation that did not move MediaStore's
        // generation (an in-app rename, a move, a new .nomedia folder) cannot be served from it.
        MediaStoreGenerationGuard.invalidate(getApplication())
        loadCurrentDirectory(forceFileSystemCheck = true)
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
        combine(_unsortedItems, itemChanges) { items, changes ->
          val visible = items.filterNot { it.path in changes }.toMutableList()
          changes.values.forEach { change ->
            val replacement = change.replacement
            if (change.directory == _currentPath.value && replacement != null &&
              visible.none { matchesReplacement(it, change) }) visible += replacement
          }
          visible.toList()
        },
        browserPreferences.folderSortType.changes(),
        browserPreferences.folderSortOrder.changes(),
        foldersPreferences.blacklistedFolders.changes(),
        foldersPreferences.blacklistedAudioFolders.changes(),
      ) { items, sortType, sortOrder, videoBlacklist, audioBlacklist ->
        val visibleItems = run {
          items.filter { item ->
            val videoHidden = videoBlacklist.any {
              app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(item.path, it)
            }
            val audioHidden = audioBlacklist.any {
              app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(item.path, it)
            }
            when (item) {
              is FileSystemItem.VideoFile -> if (item.video.isAudio) !audioHidden else !videoHidden
              is FileSystemItem.Folder ->
                !videoHidden || (browserPreferences.includeAudioBrowser.get() && !audioHidden)
            }
          }
        }
        // Sort using the same logic as Fossify's FileDirItem.sort()
        SortUtils.sortFileSystemItems(visibleItems, sortType, sortOrder)
      }.collectLatest { sortedItems ->
        _items.value = sortedItems
        Log.d(TAG, "Items sorted: ${sortedItems.size} items")
      }
    }

    // Blacklist changes must also refresh the cached hierarchy. Simply filtering the top
    // directory's items leaves parent folders counted entirely from excluded descendants.
    viewModelScope.launch {
      combine(
        foldersPreferences.blacklistedFolders.changes(),
        foldersPreferences.blacklistedAudioFolders.changes(),
      ) { video, audio -> video to audio }
        .drop(1)
        .collectLatest { loadCurrentDirectory() }
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
    // A user-driven refresh must never be served from the persisted tree snapshot.
    MediaStoreGenerationGuard.invalidate(getApplication())

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

  /** Keep removals hidden until a scan started after the mutation confirms the new directory. */
  fun beginItemRemoval(items: List<FileSystemItem>): Map<String, Long> = beginItemChanges(items) { null }

  private fun beginItemChanges(
    items: List<FileSystemItem>,
    replacement: (FileSystemItem) -> FileSystemItem?,
  ): Map<String, Long> {
    val directory = _currentPath.value
    val visiblePaths = _unsortedItems.value.mapTo(hashSetOf()) { it.path }
    val changes = items.distinctBy { it.path }.associate { item ->
      val parent = if (item.path in visiblePaths) directory else File(item.path).parent.orEmpty()
      item.path to ItemChange(item, parent, replacement(item), changeRevision.incrementAndGet())
    }
    var added = emptyMap<String, ItemChange>()
    itemChanges.update { current ->
      added = changes.filterKeys { path ->
        path !in current && current.values.none { it.replacement?.path == path }
      }
      current + added
    }
    return added.mapValues { it.value.revision }
  }

  fun confirmItemChanges(request: Map<String, Long>, paths: Collection<String>) {
    val confirmed = paths.toSet()
    itemChanges.update { changes ->
      changes.mapValues { (path, change) ->
        if (path in confirmed && request[path] == change.revision) change.copy(confirmed = true) else change
      }
    }
    if (confirmed.isNotEmpty()) _itemsWereDeletedOrMoved.value = true
  }

  /** Restore failed/cancelled items from their own snapshots, including after an intervening scan. */
  fun finishItemChanges(request: Map<String, Long>) {
    val failed = itemChanges.value.filter { (path, change) -> request[path] == change.revision && !change.confirmed }
    _unsortedItems.update { current ->
      val restored = current.toMutableList()
      failed.values.filter { it.directory == _currentPath.value }.forEach { change ->
        if (restored.none { it.path == change.original.path }) restored += change.original
      }
      restored
    }
    itemChanges.update { changes ->
      changes.filterNot { (path, change) -> request[path] == change.revision && !change.confirmed }
    }
  }

  private fun reconcileItemChanges(path: String, items: List<FileSystemItem>, scanRevision: Long) {
    itemChanges.update { changes ->
      changes.filterNot { (_, change) ->
        change.confirmed && change.directory == path && change.revision <= scanRevision &&
          items.none { it.path == change.original.path && it.name == change.original.name } &&
          (change.replacement == null || items.any { matchesReplacement(it, change) })
      }
    }
  }

  private fun matchesReplacement(item: FileSystemItem, change: ItemChange): Boolean {
    val replacement = change.replacement ?: return false
    val contentRename = (change.original as? FileSystemItem.VideoFile)?.video?.uri?.scheme == "content"
    return item.name == replacement.name &&
      (item.path == replacement.path || (contentRename && item is FileSystemItem.VideoFile))
  }

  fun refreshAfterOperation() {
    MediaFileRepository.invalidateTreeCache()
    MediaStoreGenerationGuard.invalidate(getApplication())
    loadCurrentDirectory(forceFileSystemCheck = true)
  }

  suspend fun deleteItems(items: List<FileSystemItem>): Pair<Int, Int> {
    val request = beginItemRemoval(items)
    val targets = items.distinctBy { it.path }.filter { it.path in request }
    if (targets.isEmpty()) return 0 to 0
    try {
      val folders = targets.filterIsInstance<FileSystemItem.Folder>()
      val videos = targets.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }
      val folderResult = withContext(Dispatchers.IO) { deleteFolders(folders, request) }
      val videoResult = if (videos.isEmpty()) 0 to 0 else {
        super.deleteVideos(videos) { video -> confirmItemChanges(request, listOf(video.path)) }
      }
      return (folderResult.first + videoResult.first) to (folderResult.second + videoResult.second)
    } finally {
      finishItemChanges(request)
      refreshAfterOperation()
    }
  }

  /**
   * Delete folders (and their contents)
   * Based on Fossify's deleteFiles() logic with folder support
   */
  private fun deleteFolders(folders: List<FileSystemItem.Folder>, request: Map<String, Long>): Pair<Int, Int> {
    if (folders.isEmpty()) return 0 to 0
    if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
      Log.w(TAG, "Folder deletion is unavailable with scoped storage")
      return 0 to folders.size
    }
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
            confirmItemChanges(request, listOf(folder.path))
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
            val hasRemainingMedia = dir.walkTopDown().any { file ->
              file.isFile && (file.extension.lowercase() in FileTypeUtils.VIDEO_EXTENSIONS ||
                (includeAudio && file.extension.lowercase() in FileTypeUtils.AUDIO_EXTENSIONS))
            }
            if (!hasRemainingMedia) confirmItemChanges(request, listOf(folder.path))
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
    return deleteItems(videos.map { video ->
      FileSystemItem.VideoFile(video.displayName, video.path, video.dateModified, video)
    })
  }

  /**
   * Rename a video file - delegates to base class implementation
   * Based on Fossify's RenameDialog and file renaming logic
   */
  override suspend fun renameVideo(
    video: Video,
    newDisplayName: String,
  ): Result<Unit> {
    if (newDisplayName == video.displayName) return Result.success(Unit)
    val contentUri = video.uri.scheme == "content"
    val newPath = if (video.path.startsWith('/')) File(File(video.path).parentFile, newDisplayName).absolutePath else video.path
    val renamed = video.copy(
      title = newDisplayName.substringBeforeLast('.'), displayName = newDisplayName, path = newPath,
      uri = if (contentUri) video.uri else android.net.Uri.fromFile(File(newPath)),
    )
    val original = FileSystemItem.VideoFile(video.displayName, video.path, video.dateModified, video)
    val request = beginItemChanges(listOf(original)) {
      FileSystemItem.VideoFile(newDisplayName, newPath, video.dateModified, renamed)
    }
    if (request.isEmpty()) return Result.failure(IllegalStateException("File operation already in progress"))
    try {
      return super.renameVideo(video, newDisplayName).also { result ->
        if (result.isSuccess) confirmItemChanges(request, listOf(video.path))
      }
    } finally {
      finishItemChanges(request)
      refreshAfterOperation()
    }
  }

  suspend fun renameFolder(
    folder: FileSystemItem.Folder,
    newName: String,
  ): Boolean {
    if (newName == folder.name) return true
    val newPath = File(File(folder.path).parentFile, newName).absolutePath
    val request = beginItemChanges(listOf(folder)) { folder.copy(name = newName, path = newPath) }
    if (request.isEmpty()) return false
    try {
      return StorageOps.renameFolder(getApplication(), folder.path, newName).also { ok ->
        if (ok) confirmItemChanges(request, listOf(folder.path))
      }
    } finally {
      finishItemChanges(request)
      refreshAfterOperation()
    }
  }

  /**
   * Load current directory contents
   * Main loading logic based on Fossify's getItems() and getRegularItemsOf()
   */
  private fun loadCurrentDirectory(forceFileSystemCheck: Boolean = false) {
    directoryLoadJob?.cancel()
    val path = _currentPath.value
    directoryLoadJob = viewModelScope.launch {
      val scanRevision = changeRevision.get()
      _isLoading.value = loadedDirectoryPath != path
      _error.value = null
      // Don't reset the flag here - let navigation handle it

      try {
        // Special case: Show storage roots at the special marker
        // Similar to Fossify's StoragePickerDialog logic
        if (path == STORAGE_ROOTS_MARKER) {
          Log.d(TAG, "Loading storage roots")
          _breadcrumbs.value = emptyList()
          val roots = MediaFileRepository.getStorageRoots(getApplication(), forceFileSystemCheck)
          ensureActive()
          _unsortedItems.value = roots
          loadedDirectoryPath = path
          reconcileItemChanges(path, roots, scanRevision)
          _videoFilesWithPlayback.value = emptyMap()
          _newVideoIds.value = emptySet()
          _watchedVideoIds.value = emptySet()
          Log.d(TAG, "Loaded ${roots.size} storage roots")
        } else {
          // Update breadcrumbs for real paths
          // Similar to Fossify's Breadcrumbs.setBreadcrumb()
          _breadcrumbs.value = if (ZipArchiveMedia.isBrowserPath(path)) {
            withContext(Dispatchers.IO) { ZipArchiveMedia.breadcrumbs(getApplication(), path) }
          } else {
            MediaFileRepository.getPathComponents(path)
          }
          Log.d(TAG, "Breadcrumbs updated: ${_breadcrumbs.value.size} components")

          // Get hidden files preference
          // Scan directory - equivalent to Fossify's getRegularItemsOf()
          // Always show only videos (showAllFileTypes = false)
          val scanResult =
            if (ZipArchiveMedia.isBrowserPath(path)) {
              // ZIP imports accept audio too, so the archive browser must list it as well.
              withContext(Dispatchers.IO) { ZipArchiveMedia.scan(getApplication(), path, includeAudio = true) }
            } else {
              MediaFileRepository.scanDirectory(
                getApplication(),
                path,
                showAllFileTypes = false,
                forceFileSystemCheck = forceFileSystemCheck,
              )
            }
          scanResult
            .onSuccess { items ->
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
              loadedDirectoryPath = path
              reconcileItemChanges(path, items, scanRevision)

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
              showDirectoryLoadError(path, error)
              Log.e(TAG, "Error loading directory: $path", error)
            }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (e: Exception) {
        ensureActive()
        showDirectoryLoadError(path, e)
        Log.e(TAG, "Exception loading directory", e)
      } finally {
        if (isActive) _isLoading.value = false
      }
    }
  }

  private fun showDirectoryLoadError(path: String, error: Throwable) {
    if (loadedDirectoryPath == path && _unsortedItems.value.isNotEmpty()) return
    _error.value = error.message
    _unsortedItems.value = emptyList()
    _videoFilesWithPlayback.value = emptyMap()
    _newVideoIds.value = emptySet()
    _watchedVideoIds.value = emptySet()
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
      val playbackIdentifiers = if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
        app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers(video)
      } else linkedSetOf(
          PlaybackIdentity.forLocalPath(video.path),
          PlaybackIdentity.forUri(video.uri.toString()),
          PlaybackIdentity.forUri(video.path),
          PlaybackIdentity.forUri("file://${video.path}"),
        )
      val playbackState = playbackIdentifiers.firstNotNullOfOrNull { playbackStates[it] }
      val scopedInfo = if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
        app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo(
          video, playbackState, currentTime, appearancePreferences.unplayedOldVideoDays.get(), watchedThreshold,
        )
      } else null
      val progressValue =
        if (scopedInfo != null) scopedInfo.progressPercentage ?: 0f
        else if (playbackState != null && video.duration > 0) {
          val durationSeconds = video.duration / 1000.0
          val watched = durationSeconds - playbackState.timeRemaining.toDouble()
          (watched / durationSeconds).toFloat().coerceIn(0f, 1f)
        } else {
          0f
        }
      val isWatched = scopedInfo?.isWatched ?: (
        playbackState?.hasBeenWatched == true ||
          (watchedThreshold > 0 && progressValue >= watchedThreshold / 100f))
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

