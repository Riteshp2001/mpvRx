/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.folderlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

data class FolderWithNewCount(
  val folder: VideoFolder,
  val newVideoCount: Int = 0,
)

class FolderListViewModel(
  application: Application,
  private val audioOnly: Boolean = false,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val foldersPreferences: FoldersPreferences by inject()
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: app.gyrolet.mpvrx.preferences.BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private val _allVideoFolders = MutableStateFlow<List<VideoFolder>>(emptyList())
  private val pendingFolderDeletionKeys = MutableStateFlow<Set<String>>(emptySet())
  private val completedFolderDeletionKeys = MutableStateFlow<Set<String>>(emptySet())
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
  private val scanGeneration = java.util.concurrent.atomic.AtomicLong()
  private var cacheWriteJob: Job? = null
  private var hasCompleteFolderSnapshot = false
  private val folderContentRevision = MutableStateFlow(0L)

    companion object {
    private const val TAG = "FolderListViewModel"
    private const val MEDIA_LIBRARY_REFRESH_DEBOUNCE_MS = 750L

    fun factory(
      application: Application,
      audioOnly: Boolean = false,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T = FolderListViewModel(application, audioOnly) as T
    }
  }

  init {
    // Load cached folders instantly for immediate display
    val hasCachedData = loadCachedFolders()
    hasCompleteFolderSnapshot = hasCachedData

    // If no cached data (first launch), scan immediately. Otherwise defer to not slow down app launch
    if (!hasCachedData) {
      loadVideoFolders()
    } else {
      viewModelScope.launch {
        kotlinx.coroutines.delay(2000) // Wait 2 seconds before refreshing
        loadVideoFolders()
      }
    }

    // Refresh on media events and every preference that changes scan/index semantics. Settings UI
    // may emit both; collectLatest plus the debounce collapses them into one refresh.
    viewModelScope.launch {
      val scanPreferenceChanges =
        combine(
          foldersPreferences.includeNoMediaFolders.changes(),
          foldersPreferences.hiddenFolderMarkerNames.changes(),
          browserPreferences.includeAudioBrowser.changes(),
          browserPreferences.minimumAudioDurationSeconds.changes(),
        ) { _, _, _, _ -> Unit }
          .drop(1)

      merge(MediaLibraryEvents.changes, scanPreferenceChanges).collectLatest {
        delay(MEDIA_LIBRARY_REFRESH_DEBOUNCE_MS)
        // A media event affects the MediaStore snapshot, not the tree cache or persisted
        // .nomedia fingerprints. Known hidden roots will be checked incrementally below.
        MediaFileRepository.invalidateFolderCache()
        loadVideoFolders()
      }
    }

    // Filter folders based on blacklist (video vs audio scope)
    val blacklistFlow = if (audioOnly) {
      foldersPreferences.blacklistedAudioFolders.changes()
    } else {
      foldersPreferences.blacklistedFolders.changes()
    }

    viewModelScope.launch {
      combine(_allVideoFolders, blacklistFlow, pendingFolderDeletionKeys) { folders, blacklist, pendingDeletions ->
        folders.filter { folder ->
          folderKey(folder) !in pendingDeletions &&
            blacklist.none { blacklisted ->
              folder.path.equals(blacklisted, ignoreCase = true) ||
                folder.path.startsWith(if (blacklisted.endsWith("/")) blacklisted else "$blacklisted/", ignoreCase = true)
            }
        }
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

        // Pending deletions must not leak back into the next launch through the cache.
        val pendingDeletions = pendingFolderDeletionKeys.value
        if (hasCompleteFolderSnapshot && currentScanJob?.isActive != true) {
          saveFoldersToCache(_allVideoFolders.value.filterNot { folderKey(it) in pendingDeletions })
        }
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      val folderVideos = mutableMapOf<VideoFolder, List<Video>>()
      var cachedRevision = -1L
      val badgeInputs =
        combine(
          _videoFolders,
          appearancePreferences.showUnplayedOldVideoLabel.changes(),
          appearancePreferences.unplayedOldVideoDays.changes(),
          browserPreferences.watchedThreshold.changes(),
          folderContentRevision,
        ) { _, _, _, _, revision -> revision }

      merge(badgeInputs, PlaybackStateEvents.changes.map { folderContentRevision.value }).collectLatest { revision ->
        if (cachedRevision != revision) {
          folderVideos.clear()
          cachedRevision = revision
        }
        delay(400)
        calculateNewVideoCounts(_videoFolders.value, folderVideos)
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
          _allVideoFolders.value = folders
          _hasCompletedInitialLoad.value = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached folders", e)
      }
    }

    return hasCachedData
  }

  private fun saveFoldersToCache(folders: List<VideoFolder>) {
    cacheWriteJob?.cancel()
    val cacheKey = currentFolderCacheKey()
    cacheWriteJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(750)
        try {
          val prefs =
            getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
          val json = serializeFoldersToJson(folders)
          ensureActive()
          prefs.edit().putString(cacheKey, json).apply()
          Log.d(TAG, "Saved ${folders.size} folders to cache")
        } catch (e: Exception) {
          Log.e(TAG, "Error saving folders to cache", e)
        }
      }
  }

  private fun currentFolderCacheKey(): String =
    "folders_${if (audioOnly) "audioOnly" else "video"}" +
      "_${if (foldersPreferences.includeNoMediaFolders.get()) "with_nomedia" else "exclude_nomedia"}" +
      "_audio_${browserPreferences.includeAudioBrowser.get()}_${browserPreferences.minimumAudioDurationSeconds.get()}"

  private fun serializeFoldersToJson(folders: List<VideoFolder>): String {
    // Simple JSON serialization
    return folders.joinToString(separator = "|") { folder ->
      "${folder.bucketId}::${folder.name}::${folder.path}::${folder.videoCount}::${folder.totalSize}::${folder.totalDuration}::${folder.lastModified}"
    }
  }

  private fun parseFoldersFromJson(json: String): List<VideoFolder> =
    try {
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
        } else {
          null
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing cached folders", e)
      emptyList()
    }

  private suspend fun calculateNewVideoCounts(
    folders: List<VideoFolder>,
    folderVideos: MutableMap<VideoFolder, List<Video>>,
  ) {
    folderVideos.keys.retainAll(folders.toSet())
    try {
      val showLabel = appearancePreferences.showUnplayedOldVideoLabel.get()
      if (!showLabel) {
        folderVideos.clear()
        _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
        return
      }

      val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
      val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
      val watchedThreshold = browserPreferences.watchedThreshold.get()
      val currentTime = System.currentTimeMillis()

      val foldersWithCounts =
        folders.map { folder ->
          try {
            val videos = folderVideos[folder] ?: MediaFileRepository.getVideosInFolder(
              context = getApplication(),
              bucketId = folder.bucketId,
              forceFileSystemCheck = true,
            ).also {
              currentCoroutineContext().ensureActive()
              folderVideos[folder] = it
            }

            val newCount =
              videos.count { video ->
                val videoAge = currentTime - (video.dateModified * 1000)
                val isRecent = videoAge <= thresholdMillis

                val playbackState = playbackStateRepository.getVideoDataByTitle(PlaybackIdentity.forLocalPath(video.path))
                  ?: playbackStateRepository.getVideoDataByTitle(PlaybackIdentity.forUri(video.uri.toString()))
                  ?: playbackStateRepository.getVideoDataByTitle(PlaybackIdentity.forUri(video.path))
                  ?: playbackStateRepository.getVideoDataByTitle(PlaybackIdentity.forUri("file://${video.path}"))
                val isUnplayed =
                  if (playbackState != null && video.duration > 0) {
                    val durationSeconds = video.duration / 1000
                    val watched = durationSeconds - playbackState.timeRemaining.toLong()
                    val progressValue =
                      (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
                    watchedThreshold <= 0 || progressValue < (watchedThreshold / 100f)
                  } else {
                    playbackState == null
                  }

                playbackState?.hasBeenWatched != true &&
                  (playbackState?.newLabelOverride ?: (isRecent && isUnplayed))
              }

            FolderWithNewCount(folder, newCount)
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (e: Exception) {
            Log.e(TAG, "Error counting new videos for folder ${folder.name}", e)
            FolderWithNewCount(folder, 0)
          }
        }

      currentCoroutineContext().ensureActive()
      _foldersWithNewCount.value = foldersWithCounts
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error calculating new video counts", e)
      _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
    }
  }

  fun beginFolderDeletion(folders: List<VideoFolder>) {
    val keys = folders.mapTo(mutableSetOf(), ::folderKey)
    if (keys.isEmpty()) return
    completedFolderDeletionKeys.update { it - keys }
    pendingFolderDeletionKeys.update { it + keys }
    _allVideoFolders.update { current -> current.filterNot { folderKey(it) in keys } }
    folderContentRevision.update { it + 1 }
  }

  fun finishFolderDeletion(
    requestedFolders: List<VideoFolder>,
    failedFolders: List<VideoFolder>,
  ) {
    val requestedKeys = requestedFolders.mapTo(mutableSetOf(), ::folderKey)
    val failedKeys = failedFolders.mapTo(mutableSetOf(), ::folderKey)
    val successfulKeys = requestedKeys - failedKeys

    if (failedFolders.isNotEmpty()) {
      _allVideoFolders.update { current -> mergeFolders(current, failedFolders) }
      pendingFolderDeletionKeys.update { it - failedKeys }
    }
    // Successful keys remain hidden until a complete scan confirms they disappeared.
    completedFolderDeletionKeys.update { (it - requestedKeys) + successfulKeys }
    folderContentRevision.update { it + 1 }
  }

  override fun refresh() {
    Log.d(TAG, "Hard refreshing folder list")

    // Set loading state
    _isLoading.value = _videoFolders.value.isEmpty()

    // Clear all caches to force fresh data from filesystem
    MediaFileRepository.clearCache()
    FolderViewScanner.clearCache()

    // Force the direct hidden index first; MediaScanner cannot see .nomedia trees.
    loadVideoFolders(forceFileSystemCheck = true)

    // Preserve full Refresh semantics for ordinary files copied by other apps. Completion emits a
    // debounced MediaLibraryEvents update, while this asynchronous pass never blocks hidden results.
    triggerMediaScan()
  }

  private fun triggerMediaScan() {
    try {
      val externalStorage = android.os.Environment.getExternalStorageDirectory()
      android.media.MediaScannerConnection.scanFile(
        getApplication(),
        arrayOf(externalStorage.absolutePath),
        null,
      ) { path, uri ->
        Log.d(TAG, "Media scan completed for: $path -> $uri")
      }
    } catch (error: Exception) {
      Log.e(TAG, "Failed to trigger media scan", error)
    }
  }

  suspend fun renameFolder(
    folder: VideoFolder,
    newName: String,
  ): Boolean {
    val ok = StorageOps.renameFolder(getApplication(), folder.path, newName)
    if (ok) {
      _foldersWereDeleted.value = true
    }
    return ok
  }

  /** Publishes MediaStore immediately, then merges indexed .nomedia folders in the background. */
  private fun loadVideoFolders(forceFileSystemCheck: Boolean = false) {
    val generation = scanGeneration.incrementAndGet()
    currentScanJob?.cancel()
    cacheWriteJob?.cancel()
    hasCompleteFolderSnapshot = false
    folderContentRevision.update { it + 1 }
    val previous = _allVideoFolders.value
    val onSnapshot: suspend (List<VideoFolder>) -> Unit = { snapshot ->
      kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
        ensureActive()
        if (generation == scanGeneration.get() && snapshot.isNotEmpty()) {
          _allVideoFolders.value = mergeFolders(previous, snapshot)
          _isLoading.value = false
          _hasCompletedInitialLoad.value = true
        }
      }
    }

    if (audioOnly) {
      currentScanJob =
        viewModelScope.launch {
          try {
            _isLoading.value = _allVideoFolders.value.isEmpty()
            _scanStatus.value = "Reading music library..."
            val folders =
              MediaFileRepository.getAllAudioFolders(
                context = getApplication(),
                minimumAudioDurationSeconds = browserPreferences.minimumAudioDurationSeconds.get(),
                onSnapshot = onSnapshot,
              )
            ensureActive()
            publishFinalFolders(folders)
            _isLoading.value = false
            _hasCompletedInitialLoad.value = true
          } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
          } catch (e: Exception) {
            ensureActive()
            Log.e(TAG, "Error loading audio folders", e)
            _hasCompletedInitialLoad.value = true
          } finally {
            if (isActive && generation == scanGeneration.get()) {
              _isLoading.value = false
              _isEnriching.value = false
              _scanStatus.value = null
            }
          }
        }
      return
    }

    currentScanJob =
      viewModelScope.launch {
        try {
          val hasExistingData = _allVideoFolders.value.isNotEmpty()
          if (!hasExistingData) {
            _isLoading.value = true
            _scanStatus.value = "Reading media library..."
          }

          val previousFolders = previous.associateBy(::folderKey)
          val mediaStoreFolders =
            MediaFileRepository.getAllVideoFoldersFast(
              context = getApplication(),
              onProgress = { count ->
                if (!hasExistingData) _scanStatus.value = "Found $count folders"
              },
              forceFileSystemCheck = forceFileSystemCheck,
              includeAudioOverride = browserPreferences.includeAudioBrowser.get(),
              onSnapshot = onSnapshot,
            )
          ensureActive()
          // This is the important latency boundary: never wait for a filesystem walk.
          _allVideoFolders.value = mergeFolders(previous, mediaStoreFolders)
          _isLoading.value = false
          _hasCompletedInitialLoad.value = true

          val indexedFolders = MediaFileRepository.getIndexedNoMediaFolders()
          ensureActive()
          var visibleFolders = mergeFolders(mediaStoreFolders, indexedFolders)
          _allVideoFolders.value = mergeFolders(previous, visibleFolders)
          Log.d(TAG, "Published ${mediaStoreFolders.size} MediaStore and ${indexedFolders.size} indexed folders")

          if (foldersPreferences.includeNoMediaFolders.get()) {
            _scanStatus.value =
              if (forceFileSystemCheck || indexedFolders.isEmpty()) {
                "Discovering hidden folders..."
              } else {
                "Checking hidden folders..."
              }
            MediaFileRepository
              .scanNoMediaFoldersIncrementally(
                context = getApplication(),
                forceDiscovery = forceFileSystemCheck,
              ).collect { batch ->
                ensureActive()
                visibleFolders = mergeFolders(visibleFolders, batch)
                _allVideoFolders.value = mergeFolders(previous, visibleFolders)
                _scanStatus.value = "Found ${visibleFolders.size} folders"
              }

            // Replace the old indexed snapshot after the scan, removing deleted/stale folders.
            visibleFolders = mergeFolders(mediaStoreFolders, MediaFileRepository.getIndexedNoMediaFolders())
            ensureActive()
            _allVideoFolders.value = visibleFolders
          }

          publishFinalFolders(visibleFolders)
          if (visibleFolders.isEmpty()) return@launch

          var needsEnrichment = false
          val foldersForEnrichment =
            visibleFolders.map { folder ->
              val cached = previousFolders[folderKey(folder)]
              val cachedIsComplete = cached != null && (cached.videoCount == 0 || cached.totalDuration > 0)
              if (cached != null &&
                cached.videoCount == folder.videoCount &&
                cached.lastModified == folder.lastModified &&
                cachedIsComplete
              ) {
                cached
              } else {
                needsEnrichment = true
                folder
              }
            }
          _allVideoFolders.value = foldersForEnrichment

          val needsDurationEnrichment = needsEnrichment && foldersForEnrichment.isNotEmpty() &&
            MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)
          if (!needsDurationEnrichment) return@launch

          _isEnriching.value = true
          _scanStatus.value = "Processing metadata..."
          val enrichedFolders =
            MetadataRetrieval.enrichFoldersIfNeeded(
              context = getApplication(),
              folders = foldersForEnrichment,
              browserPreferences = browserPreferences,
              metadataCache = metadataCache,
              onProgress = { processed, total ->
                _scanStatus.value = "Processing metadata $processed/$total"
              },
              onSnapshot = { snapshot ->
                kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                  ensureActive()
                  if (generation == scanGeneration.get()) _allVideoFolders.value = snapshot
                }
              },
            )

          ensureActive()
          val enrichedByKey = enrichedFolders.associateBy(::folderKey)
          publishFinalFolders(foldersForEnrichment.map { enrichedByKey[folderKey(it)] ?: it })
        } catch (e: kotlinx.coroutines.CancellationException) {
          Log.d(TAG, "Scan cancelled (new scan started)")
          throw e
        } catch (e: Exception) {
          ensureActive()
          Log.e(TAG, "Error loading video folders", e)
          _hasCompletedInitialLoad.value = true
        } finally {
          if (isActive && generation == scanGeneration.get()) {
            _isLoading.value = false
            _isEnriching.value = false
            _scanStatus.value = null
          }
        }
      }
  }

  private fun folderKey(folder: VideoFolder): String =
    folder.path
      .replace('\\', '/')
      .trimEnd('/')
      .lowercase(Locale.ROOT)

  private fun publishFinalFolders(folders: List<VideoFolder>) {
    hasCompleteFolderSnapshot = true
    val incomingKeys = folders.mapTo(mutableSetOf(), ::folderKey)
    val confirmedDeletionKeys = completedFolderDeletionKeys.value - incomingKeys
    if (confirmedDeletionKeys.isNotEmpty()) {
      pendingFolderDeletionKeys.update { it - confirmedDeletionKeys }
      completedFolderDeletionKeys.update { it - confirmedDeletionKeys }
    }
    _allVideoFolders.value = folders
    val pending = pendingFolderDeletionKeys.value
    saveFoldersToCache(folders.filterNot { folderKey(it) in pending })
  }

  private fun mergeFolders(vararg groups: List<VideoFolder>): List<VideoFolder> {
    val merged = linkedMapOf<String, VideoFolder>()
    groups.forEach { folders -> folders.forEach { folder -> merged[folderKey(folder)] = folder } }
    return merged.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
  }
}
