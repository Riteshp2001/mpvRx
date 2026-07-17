package app.gyrolet.mpvrx.ui.browser.folderlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class FolderWithNewCount(
  val folder: VideoFolder,
  val newVideoCount: Int = 0,
)

class FolderListViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: app.gyrolet.mpvrx.preferences.BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private val _allVideoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  private val _videoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  val videoFolders: StateFlow<List<VideoFolder>> = _videoFolders.asStateFlow()

  private val _foldersWithNewCount = MutableStateFlow<List<FolderWithNewCount>>(emptyList())
  val foldersWithNewCount: StateFlow<List<FolderWithNewCount>> = _foldersWithNewCount.asStateFlow()

  // Only show loading on fresh install (when there's no cached data)
  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  // Track if initial load has completed to prevent empty state flicker
  private val _hasCompletedInitialLoad = MutableStateFlow(false)
  val hasCompletedInitialLoad: StateFlow<Boolean> = _hasCompletedInitialLoad.asStateFlow()

  // Track if folders were deleted leaving list empty
  private val _foldersWereDeleted = MutableStateFlow(false)
  val foldersWereDeleted: StateFlow<Boolean> = _foldersWereDeleted.asStateFlow()

  // Track previous folder count to detect if all folders were deleted
  private var previousFolderCount = 0

  /*
   * TRACKING LOADING STATE
   */
  private val _scanStatus = MutableStateFlow<String?>(null)
  val scanStatus: StateFlow<String?> = _scanStatus.asStateFlow()

  private val _isEnriching = MutableStateFlow(false)
  val isEnriching: StateFlow<Boolean> = _isEnriching.asStateFlow()

  // Track the current scan job to prevent concurrent scans
  private var currentScanJob: Job? = null
  private var newCountJob: Job? = null
  private var cacheWriteJob: Job? = null

  companion object {
    private const val TAG = "FolderListViewModel"

    fun factory(application: Application) =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderListViewModel(application) as T
      }
  }

  init {
    // Load cached folders instantly for immediate display
    val hasCachedData = loadCachedFolders()

    // If no cached data (first launch), scan immediately. Otherwise defer to not slow down app launch
    if (!hasCachedData) {
      loadVideoFolders()
    } else {
      viewModelScope.launch(Dispatchers.IO) {
        kotlinx.coroutines.delay(2000) // Wait 2 seconds before refreshing
        loadVideoFolders()
      }
    }

    // Refresh folders on global media library changes
    viewModelScope.launch(Dispatchers.IO) {
      MediaLibraryEvents.changes.collectLatest {
        // A media event affects the MediaStore snapshot, not the tree cache or persisted
        // .nomedia fingerprints. Known hidden roots will be checked incrementally below.
        MediaFileRepository.invalidateFolderCache()
        loadVideoFolders()
      }
    }

    // Filter folders based on blacklist
    viewModelScope.launch {
      combine(_allVideoFolders, foldersPreferences.blacklistedFolders.changes()) { folders, blacklist ->
        folders.filter { folder -> folder.path !in blacklist }
      }.collectLatest { filteredFolders ->
        // Check if folders became empty after having folders
        if (previousFolderCount > 0 && filteredFolders.isEmpty()) {
          _foldersWereDeleted.value = true
          Log.d(TAG, "Folders became empty (had $previousFolderCount folders before)")
        } else if (filteredFolders.isNotEmpty()) {
          // Reset flag if folders now exist
          _foldersWereDeleted.value = false
        }

        // Update previous count
        previousFolderCount = filteredFolders.size

        _videoFolders.value = filteredFolders
        // Calculate new video counts for each folder
        calculateNewVideoCounts(filteredFolders)

        // Save to cache for next app launch (save unfiltered list)
        saveFoldersToCache(_allVideoFolders.value)
      }
    }
  }

  private fun loadCachedFolders(): Boolean {
    var hasCachedData = false
    val prefs =
      getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
    val cachedJson = prefs.getString(currentFolderCacheKey(), null)

    if (cachedJson != null) {
      try {
        // Parse JSON and restore folders
        val folders = parseFoldersFromJson(cachedJson)
        if (folders.isNotEmpty()) {
          Log.d(TAG, "Loaded ${folders.size} folders from cache instantly")
          hasCachedData = true
          viewModelScope.launch(Dispatchers.IO) {
            _allVideoFolders.value = folders
            _hasCompletedInitialLoad.value = true
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached folders", e)
      }
    }

    return hasCachedData
  }

  private fun saveFoldersToCache(folders: List<VideoFolder>) {
    cacheWriteJob?.cancel()
    cacheWriteJob = viewModelScope.launch(Dispatchers.IO) {
      delay(750)
      try {
        val prefs =
          getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
        val json = serializeFoldersToJson(folders)
        prefs.edit().putString(currentFolderCacheKey(), json).apply()
        Log.d(TAG, "Saved ${folders.size} folders to cache")
      } catch (e: Exception) {
        Log.e(TAG, "Error saving folders to cache", e)
      }
    }
  }

  private fun currentFolderCacheKey(): String =
    "folders_${if (foldersPreferences.includeNoMediaFolders.get()) "with_nomedia" else "exclude_nomedia"}" +
      "_audio_${browserPreferences.includeAudioBrowser.get()}_${browserPreferences.minimumAudioDurationSeconds.get()}"

  private fun serializeFoldersToJson(folders: List<VideoFolder>): String {
    // Simple JSON serialization
    return folders.joinToString(separator = "|") { folder ->
      "${folder.bucketId}::${folder.name}::${folder.path}::${folder.videoCount}::${folder.totalSize}::${folder.totalDuration}::${folder.lastModified}"
    }
  }

  private fun parseFoldersFromJson(json: String): List<VideoFolder> {
    return try {
      json.split("|").mapNotNull { item ->
        val parts = item.split("::")
        if (parts.size == 7) {
          VideoFolder(
            bucketId = parts[0],
            name = parts[1],
            path = parts[2],
            videoCount = parts[3].toIntOrNull() ?: 0,
            totalSize = parts[4].toLongOrNull() ?: 0L,
            totalDuration = parts[5].toLongOrNull() ?: 0L,
            lastModified = parts[6].toLongOrNull() ?: 0L,
          )
        } else null
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing cached folders", e)
      emptyList()
    }
  }

  private fun calculateNewVideoCounts(folders: List<VideoFolder>) {
    newCountJob?.cancel()
    newCountJob = viewModelScope.launch(Dispatchers.IO) {
      delay(400)
      try {
        val showLabel = appearancePreferences.showUnplayedOldVideoLabel.get()
        if (!showLabel) {
          // If feature is disabled, just return folders with 0 count
          _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
          return@launch
        }

        val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
        val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
        val currentTime = System.currentTimeMillis()

        val foldersWithCounts = folders.map { folder ->
          try {
            // Get all videos in this folder
            val videos = app.gyrolet.mpvrx.repository.MediaFileRepository
              .getVideosInFolder(getApplication(), folder.bucketId)

            // Count new unplayed videos
            val newCount = videos.count { video ->
              // Check if video was modified within threshold days
              val videoAge = currentTime - (video.dateModified * 1000)
              val isRecent = videoAge <= thresholdMillis

              // Check if video has been played
              // A video is considered "played" if it has any playback state
              val playbackState = playbackStateRepository.getVideoDataByTitle(video.displayName)
              val isUnplayed = playbackState == null

              isRecent && isUnplayed
            }

            FolderWithNewCount(folder, newCount)
          } catch (e: Exception) {
            Log.e(TAG, "Error counting new videos for folder ${folder.name}", e)
            FolderWithNewCount(folder, 0)
          }
        }

        _foldersWithNewCount.value = foldersWithCounts
      } catch (e: Exception) {
        Log.e(TAG, "Error calculating new video counts", e)
        _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
      }
    }
  }

  override fun refresh() {
    Log.d(TAG, "Hard refreshing folder list")

    // Set loading state
    _isLoading.value = true

    // Clear all caches to force fresh data from filesystem
    MediaFileRepository.clearCache()
    FolderViewScanner.clearCache()

    // Trigger media scan to ensure MediaStore is up-to-date
    triggerMediaScan()

    loadVideoFolders(forceFileSystemCheck = true)
  }
  
  /**
   * Trigger a comprehensive media scan to update MediaStore
   */
  private fun triggerMediaScan() {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()
      
      android.media.MediaScannerConnection.scanFile(
        getApplication(),
        arrayOf(externalStorage.absolutePath),
        null, // Let MediaScanner detect all media types
      ) { path, uri ->
        Log.d(TAG, "Media scan completed for: $path -> $uri")
      }
      
      Log.d(TAG, "Triggered comprehensive media scan")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to trigger media scan", e)
    }
  }

  /**
   * Recalculate new video counts without refreshing the entire folder list
   * Useful when returning to the screen after playing videos
   */
  fun recalculateNewVideoCounts() {
    calculateNewVideoCounts(_videoFolders.value)
  }

  suspend fun renameFolder(folder: VideoFolder, newName: String): Boolean {
    val src = java.io.File(folder.path)
    val dst = java.io.File(src.parent ?: return false, newName)
    if (dst.exists()) return false
    val ok = src.renameTo(dst)
    if (ok) {
      android.media.MediaScannerConnection.scanFile(getApplication(), arrayOf(dst.absolutePath), null, null)
      _foldersWereDeleted.value = true
    }
    return ok
  }

  /** Publishes MediaStore immediately, then merges indexed .nomedia folders in the background. */
  private fun loadVideoFolders(forceFileSystemCheck: Boolean = false) {
    currentScanJob?.cancel()

    currentScanJob = viewModelScope.launch(Dispatchers.IO) {
      try {
        val hasExistingData = _allVideoFolders.value.isNotEmpty()
        if (!hasExistingData) {
          _isLoading.value = true
          _scanStatus.value = "Reading media library..."
        }

        val previousFolders = _allVideoFolders.value.associateBy(::folderKey)
        val mediaStoreFolders = MediaFileRepository.getAllVideoFoldersFast(
            context = getApplication(),
            onProgress = { count ->
              if (!hasExistingData) _scanStatus.value = "Found $count folders"
            },
            forceFileSystemCheck = forceFileSystemCheck,
          )
        // This is the important latency boundary: never wait for a filesystem walk.
        _allVideoFolders.value = mediaStoreFolders
        _isLoading.value = false
        _hasCompletedInitialLoad.value = true

        val indexedFolders = MediaFileRepository.getIndexedNoMediaFolders()
        var visibleFolders = mergeFolders(mediaStoreFolders, indexedFolders)
        _allVideoFolders.value = visibleFolders
        Log.d(TAG, "Published ${mediaStoreFolders.size} MediaStore and ${indexedFolders.size} indexed folders")

        if (foldersPreferences.includeNoMediaFolders.get()) {
          _scanStatus.value = if (forceFileSystemCheck || indexedFolders.isEmpty()) {
            "Discovering hidden folders..."
          } else {
            "Checking hidden folders..."
          }
          MediaFileRepository.scanNoMediaFoldersIncrementally(
            context = getApplication(),
            forceDiscovery = forceFileSystemCheck,
          ).collect { batch ->
            visibleFolders = mergeFolders(visibleFolders, batch)
            _allVideoFolders.value = visibleFolders
            _scanStatus.value = "Found ${visibleFolders.size} folders"
          }

          // Replace the old indexed snapshot after the scan, removing deleted/stale folders.
          visibleFolders = mergeFolders(mediaStoreFolders, MediaFileRepository.getIndexedNoMediaFolders())
          _allVideoFolders.value = visibleFolders
        }

        if (visibleFolders.isEmpty()) return@launch

        var needsEnrichment = false
        val foldersForEnrichment = visibleFolders.map { folder ->
          val cached = previousFolders[folderKey(folder)]
          val cachedIsComplete = cached != null && (cached.videoCount == 0 || cached.totalDuration > 0)
          if (cached != null && cached.videoCount == folder.videoCount &&
            cached.lastModified == folder.lastModified && cachedIsComplete
          ) {
            cached
          } else {
            needsEnrichment = true
            folder
          }
        }
        _allVideoFolders.value = foldersForEnrichment

        val needsDurationEnrichment = needsEnrichment && MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)
        if (!needsDurationEnrichment) return@launch

        _isEnriching.value = true
        _scanStatus.value = "Processing metadata..."
        val enrichedFolders = MetadataRetrieval.enrichFoldersIfNeeded(
            context = getApplication(),
            folders = foldersForEnrichment,
            browserPreferences = browserPreferences,
            metadataCache = metadataCache,
            onProgress = { processed, total ->
               _scanStatus.value = "Processing metadata $processed/$total"
            }
          )

        _allVideoFolders.value = enrichedFolders
      } catch (e: kotlinx.coroutines.CancellationException) {
        Log.d(TAG, "Scan cancelled (new scan started)")
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "Error loading video folders", e)
        _hasCompletedInitialLoad.value = true
      } finally {
        _isLoading.value = false
        _isEnriching.value = false
        _scanStatus.value = null
      }
    }
  }

  private fun folderKey(folder: VideoFolder): String = folder.path.replace('\\', '/').trimEnd('/').lowercase(Locale.ROOT)

  private fun mergeFolders(vararg groups: List<VideoFolder>): List<VideoFolder> {
    val merged = linkedMapOf<String, VideoFolder>()
    groups.forEach { folders -> folders.forEach { folder -> merged[folderKey(folder)] = folder } }
    return merged.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
  }

}

