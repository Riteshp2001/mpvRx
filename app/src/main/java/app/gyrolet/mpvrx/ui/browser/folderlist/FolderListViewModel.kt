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
import app.gyrolet.mpvrx.preferences.HiddenScanScope
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.permission.PermissionUtils.StorageOps
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale
import java.io.File

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
  @Volatile private var currentListingReady: CompletableDeferred<Unit>? = null
  private var cacheWriteJob: Job? = null
  private var lastHiddenMarkerSnapshot: Map<String, Boolean>? = null
  private var lastExternalFolderRecheckAt = android.os.SystemClock.elapsedRealtime()
  private val folderContentRevision = MutableStateFlow(0L)

  /**
   * MediaStore generation observed by the scan that produced the list currently on screen, or null
   * when the on-screen list was not re-validated against MediaStore (a cold-boot reuse pass, or no
   * token because the device is below API 30). Written to the cache alongside the list so the next
   * cold boot knows whether that list is still trustworthy.
   */
  private var observedMediaStoreGeneration: String? = null

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

    // Token checks query MediaStore, so they run off the main thread.
    viewModelScope.launch(Dispatchers.IO) {
      if (!hasCachedData) {
        loadVideoFolders(silent = false)
        return@launch
      }
      // MediaStore has not changed since this cache was written, so re-querying it could only
      // rebuild an identical list. Hidden .nomedia folders are still walked: MediaStore cannot see
      // those files, so its generation says nothing about them.
      val unchanged = MediaStoreGenerationGuard.isUnchangedSinceLastScan(getApplication())
      if (unchanged) Log.d(TAG, "MediaStore unchanged, reusing cached folder list")
      loadVideoFolders(silent = true, skipMediaStoreScan = unchanged)
    }

    // Refresh on media events and every preference that changes scan/index semantics. Settings UI
    // may emit both. Conflate bursts without restarting the delay for every scanner row.
    viewModelScope.launch(Dispatchers.IO) {
      val scanPreferenceChanges =
        combine(
          foldersPreferences.includeNoMediaFolders.changes(),
          foldersPreferences.foldersHiddenScanEnabled.changes(),
          foldersPreferences.hiddenFolderMarkerNames.changes(),
          foldersPreferences.sharedScanMarkedFolders.changes(),
          foldersPreferences.sharedScanDotFolders.changes(),
          foldersPreferences.sharedIgnoredMarkers.changes(),
          browserPreferences.includeAudioBrowser.changes(),
          browserPreferences.minimumAudioDurationSeconds.changes(),
        ) { _ -> Unit }
          .drop(1)

      merge(MediaLibraryEvents.changes.map { true }, scanPreferenceChanges.map { false }).conflate().collect { mediaChanged ->
        delay(MEDIA_LIBRARY_REFRESH_DEBOUNCE_MS)
        // Let an in-flight basic listing publish even during a continuous scanner burst.
        // This does not wait for hidden discovery or optional metadata enrichment.
        currentListingReady?.await()
        // A media event affects the MediaStore snapshot, not the tree cache or persisted
        // .nomedia fingerprints. Known hidden roots will be checked incrementally below.
        MediaFileRepository.invalidateFolderCache()
        // Whatever prompted this, the library has to be re-read; make sure the cold-boot
        // fast path cannot reuse the cache afterwards.
        if (mediaChanged) {
          MediaStoreGenerationGuard.invalidate(getApplication())
        } else {
          MediaStoreGenerationGuard.forgetLastScan(getApplication())
        }
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
      combine(
        _allVideoFolders,
        blacklistFlow,
        foldersPreferences.whitelistChanges(),
        pendingFolderDeletionKeys,
      ) { folders, blacklist, (whitelistEnabled, whitelist), pendingDeletions ->
        folders.filter { folder ->
          folderKey(folder) !in pendingDeletions &&
            app.gyrolet.mpvrx.utils.storage.isWhitelistedMediaPath(folder.path, whitelistEnabled, whitelist) &&
            blacklist.none { blacklisted ->
              app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(folder.path, blacklisted)
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
        if (_foldersWithNewCount.value.isEmpty() && filteredFolders.isNotEmpty()) {
          _foldersWithNewCount.value = filteredFolders.map { FolderWithNewCount(it, 0) }
        }

        // Pending deletions must not leak back into the next launch through the cache.
        val pendingDeletions = pendingFolderDeletionKeys.value
        saveFoldersToCache(_allVideoFolders.value.filterNot { folderKey(it) in pendingDeletions })
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      val folderVideos = mutableMapOf<VideoFolder, List<Video>>()
      var cachedRevision = -1L
      // Built by each complete count. A playback save then recounts only the folders holding that
      // item: the full pass hashed every video's identities and ran every few seconds in playback.
      var identityIndex: FolderIdentityIndex? = null
      val badgeInputs =
        combine(
          _videoFolders,
          appearancePreferences.showUnplayedOldVideoLabel.changes(),
          appearancePreferences.unplayedOldVideoDays.changes(),
          browserPreferences.watchedThreshold.changes(),
          folderContentRevision,
        ) { _, _, _, _, revision -> Pair<Long, String?>(revision, null) }
      val playbackChanges =
        PlaybackStateEvents.changes.map { identifier ->
          Pair<Long, String?>(folderContentRevision.value, identifier.takeIf(String::isNotBlank))
        }

      merge(badgeInputs, playbackChanges).collectLatest { (revision, mediaIdentifier) ->
        if (cachedRevision != revision) {
          folderVideos.clear()
          cachedRevision = revision
          identityIndex = null
        }
        val index = identityIndex
        if (mediaIdentifier != null && index != null) {
          // Saves for anything outside the library (network streams, music) change no badge.
          val affectedFolders = index.foldersFor(mediaIdentifier)
          if (affectedFolders.isEmpty()) return@collectLatest
          delay(400)
          recountNewVideos(affectedFolders, folderVideos)
          return@collectLatest
        }
        // A cancelled pass leaves no index, so the next request counts everything again.
        identityIndex = null
        delay(400)
        identityIndex = calculateNewVideoCounts(_videoFolders.value, folderVideos)
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
        val folders = parseFoldersFromJson(cachedJson).filterNot {
          ScopedHiddenFolderScanner.isScopedFolder(it.bucketId) && it.videoCount == 0
        }
        if (folders.isNotEmpty()) {
          Log.d(TAG, "Loaded ${folders.size} folders from cache instantly")
          hasCachedData = true
          _allVideoFolders.value = folders
          _hasCompletedInitialLoad.value = true
          _isLoading.value = false
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error loading cached folders", e)
      }
    }

    return hasCachedData
  }

  private fun saveFoldersToCache(folders: List<VideoFolder>) {
    cacheWriteJob?.cancel()
    cacheWriteJob =
      viewModelScope.launch(Dispatchers.IO) {
        delay(750)
        try {
          val prefs =
            getApplication<Application>().getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
          val json = serializeFoldersToJson(folders)
          prefs.edit().putString(currentFolderCacheKey(), json).apply()
          // Only now, with a list a real scan produced, is it safe to record the MediaStore state
          // that scan observed. Remembering after the fact would bless a newer MediaStore than the
          // data was built from, and the next cold boot would keep a stale list.
          MediaStoreGenerationGuard.remember(getApplication(), observedMediaStoreGeneration)
          Log.d(TAG, "Saved ${folders.size} folders to cache")
        } catch (e: Exception) {
          Log.e(TAG, "Error saving folders to cache", e)
        }
      }
  }

  private fun currentFolderCacheKey(): String =
    "folders_v3_${if (audioOnly) "audioOnly" else "video"}" +
      "_${if (foldersPreferences.includeNoMediaFolders.get()) "with_nomedia" else "exclude_nomedia"}" +
      "_enabled${foldersPreferences.foldersHiddenScanEnabled.get()}" +
      "_m${foldersPreferences.sharedScanMarkedFolders.get()}_d${foldersPreferences.sharedScanDotFolders.get()}" +
      "_markers${foldersPreferences.hiddenFolderMarkerNames.get().sorted().joinToString(",")}" +
      "_ignore${foldersPreferences.sharedIgnoredMarkers.get().sorted().joinToString(",")}" +
      "_audio_${browserPreferences.includeAudioBrowser.get()}_${browserPreferences.minimumAudioDurationSeconds.get()}" +
      "_${MediaFileRepository.currentScanOptions().mediaFilterKey}"

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

  /** Counts every folder and returns the index that lets playback saves recount just one. */
  private suspend fun calculateNewVideoCounts(
    folders: List<VideoFolder>,
    folderVideos: MutableMap<VideoFolder, List<Video>>,
  ): FolderIdentityIndex? {
    folderVideos.keys.retainAll(folders.toSet())
    try {
      val parameters = newBadgeParameters()
      if (parameters == null) {
        folderVideos.clear()
        _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
        // Badges are off, so playback saves have nothing to update.
        return FolderIdentityIndex.EMPTY
      }
      // One snapshot per pass: per-video lookups issued up to four Room queries for every video in
      // every folder, which mostly miss because most videos were never played.
      val playbackByIdentifier = playbackStateRepository.getAllPlaybackStates().associateBy { it.mediaTitle }
      val indexBuilder = FolderIdentityIndex.Builder(folders)

      val foldersWithCounts =
        folders.mapIndexed { folderIndex, folder ->
          try {
            val videos = folderVideos[folder] ?: MediaFileRepository.getVideosInFolder(
              context = getApplication(),
              bucketId = folder.bucketId,
              forceFileSystemCheck = false,
            ).also {
              currentCoroutineContext().ensureActive()
              folderVideos[folder] = it
            }

            val newCount =
              countNewVideos(videos, parameters) { identifiers ->
                indexBuilder.add(identifiers, folderIndex)
                identifiers.firstNotNullOfOrNull(playbackByIdentifier::get)
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
      return indexBuilder.build()
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      Log.e(TAG, "Error calculating new video counts", e)
      _foldersWithNewCount.value = folders.map { FolderWithNewCount(it, 0) }
      return null
    }
  }

  /** Recounts the given folders from their cached video lists, leaving every other badge as is. */
  private suspend fun recountNewVideos(
    folders: List<VideoFolder>,
    folderVideos: Map<VideoFolder, List<Video>>,
  ) {
    val parameters = newBadgeParameters() ?: return
    val recounted =
      folders.mapNotNull { folder ->
        val videos = folderVideos[folder] ?: return@mapNotNull null
        folder to
          countNewVideos(videos, parameters) { identifiers ->
            identifiers.firstNotNullOfOrNull { playbackStateRepository.getVideoDataByTitle(it) }
          }
      }.toMap()
    if (recounted.isEmpty()) return
    currentCoroutineContext().ensureActive()
    _foldersWithNewCount.update { current ->
      current.map { entry -> recounted[entry.folder]?.let { entry.copy(newVideoCount = it) } ?: entry }
    }
  }

  private class NewBadgeParameters(
    val currentTime: Long,
    val thresholdMillis: Long,
    val newLabelDays: Int,
    val watchedThreshold: Int,
  )

  /** Null while the NEW label is turned off. */
  private fun newBadgeParameters(): NewBadgeParameters? {
    if (!appearancePreferences.showUnplayedOldVideoLabel.get()) return null
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    return NewBadgeParameters(
      currentTime = System.currentTimeMillis(),
      thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L,
      newLabelDays = thresholdDays,
      watchedThreshold = browserPreferences.watchedThreshold.get(),
    )
  }

  private suspend fun countNewVideos(
    videos: List<Video>,
    parameters: NewBadgeParameters,
    playbackStateFor: suspend (identifiers: Set<String>) -> PlaybackStateEntity?,
  ): Int =
    videos.count { video ->
      val videoAge = parameters.currentTime - (video.dateModified * 1000)
      val isRecent = videoAge <= parameters.thresholdMillis
      val playbackState =
        playbackStateFor(app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers(video))

      if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY) {
        return@count app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo(
          video, playbackState, parameters.currentTime, parameters.newLabelDays, parameters.watchedThreshold,
        ).isOldAndUnplayed
      }
      val isUnplayed =
        if (playbackState != null && video.duration > 0) {
          val durationSeconds = video.duration / 1000
          val watched = durationSeconds - playbackState.timeRemaining.toLong()
          val progressValue =
            (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
          parameters.watchedThreshold <= 0 || progressValue < (parameters.watchedThreshold / 100f)
        } else {
          playbackState == null
        }

      playbackState?.hasBeenWatched != true &&
        (playbackState?.newLabelOverride ?: (isRecent && isUnplayed))
    }

  /**
   * Which folders hold a video with a given playback identity. Stores identity hashes, since the
   * identity strings would cost megabytes for a large library; a rare collision only recounts an
   * extra folder.
   */
  private class FolderIdentityIndex private constructor(
    private val folders: List<VideoFolder>,
    // Sorted pairs packed as (identity hash shl 32) or folder index.
    private val entries: LongArray,
  ) {
    fun foldersFor(identifier: String): List<VideoFolder> {
      val hash = identifier.hashCode()
      val lowestKey = hash.toLong() shl 32
      var low = 0
      var high = entries.size
      while (low < high) {
        val middle = (low + high) ushr 1
        if (entries[middle] < lowestKey) low = middle + 1 else high = middle
      }
      val result = ArrayList<VideoFolder>(1)
      while (low < entries.size && (entries[low] shr 32).toInt() == hash) {
        folders.getOrNull(entries[low].toInt())?.let { if (it !in result) result.add(it) }
        low++
      }
      return result
    }

    class Builder(private val folders: List<VideoFolder>) {
      private var entries = LongArray(256)
      private var size = 0

      fun add(identifiers: Collection<String>, folderIndex: Int) {
        for (identifier in identifiers) {
          if (size == entries.size) entries = entries.copyOf(size * 2)
          entries[size++] = (identifier.hashCode().toLong() shl 32) or (folderIndex.toLong() and 0xffffffffL)
        }
      }

      fun build(): FolderIdentityIndex = FolderIdentityIndex(folders, entries.copyOf(size).apply { sort() })
    }

    companion object {
      val EMPTY = FolderIdentityIndex(emptyList(), LongArray(0))
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
    Log.d(TAG, "Refreshing folder list and MediaStore snapshot")

    // Keep the last published folders visible while MediaStore is refreshed. A full recursive
    // filesystem walk and MediaScanner.scanFile(storageRoot) do not belong on the interactive
    // refresh path: both can take tens of seconds and overlap the folder query.
    if (_allVideoFolders.value.isEmpty()) _isLoading.value = true
    MediaFileRepository.invalidateFolderCache()
    MediaStoreGenerationGuard.invalidate(getApplication())
    viewModelScope.launch(Dispatchers.IO) {
      loadVideoFolders()
      app.gyrolet.mpvrx.utils.storage.UnindexedVideoDiscovery.refresh(getApplication(), force = true)
    }
  }

  /**
   * Folder/Tree Home must notice externally added/removed .nomedia markers without first
   * visiting the Library tab. Check only indexed roots and visible folders, on IO, while this
   * page is resumed. Perform the expensive forced filesystem pass only on a change or when
   * an old device is revisited after a while (Android 10 has no MediaStore generation token).
   */
  suspend fun checkExternalFolderChanges(onResume: Boolean) {
    if (audioOnly || currentScanJob?.isActive == true ||
      !foldersPreferences.hiddenScanEnabled(HiddenScanScope.FOLDERS)) return

    val markerNames =
      (foldersPreferences.hiddenFolderMarkerNames.get() - foldersPreferences.sharedIgnoredMarkers.get())
        .map { it.trim() }
        .filter { it.isNotEmpty() && '/' !in it && '\\' !in it }
        .map { if (it.startsWith(".")) it else ".$it" }
    val roots = MediaFileRepository.getIndexedNoMediaRootPaths()
    val directories = (roots + _allVideoFolders.value.map { it.path }).distinct()
    val markerSnapshot = directories.associateWith { path ->
      val directory = File(path)
      (foldersPreferences.scanDotFolders(HiddenScanScope.FOLDERS) && directory.name.startsWith(".")) ||
        (foldersPreferences.scanMarkedFolders(HiddenScanScope.FOLDERS) && markerNames.any { File(directory, it).isFile })
    }
    val previous = lastHiddenMarkerSnapshot
    lastHiddenMarkerSnapshot = markerSnapshot

    val staleHiddenRoot = roots.any { root ->
      val directory = File(root)
      !directory.isDirectory || !directory.name.startsWith(".") &&
        markerNames.none { File(directory, it).isFile }
    }
    // Only marker-state transitions matter; scanning new ordinary folders must not
    // bounce us into a second expensive filesystem pass.
    val markersChanged = previous?.any { (path, wasHidden) ->
      val isHidden = markerSnapshot[path]
      isHidden != null && isHidden != wasHidden
    } == true
    val now = android.os.SystemClock.elapsedRealtime()
    val foregroundRefreshInterval = if (android.os.Build.VERSION.SDK_INT < 30) 20_000L else 60_000L
    val needsForegroundCheck = onResume && now - lastExternalFolderRecheckAt >= foregroundRefreshInterval
    if (!staleHiddenRoot && !markersChanged && !needsForegroundCheck) return

    lastExternalFolderRecheckAt = now
    Log.d(TAG, "Folder mode checking external folder markers; refreshing independently of Library")
    MediaFileRepository.invalidateFolderCache()
    if (staleHiddenRoot || markersChanged) {
      // A confirmed hidden-root change also invalidates Tree's snapshot.
      MediaStoreGenerationGuard.invalidate(getApplication())
    } else {
      // Periodic health checks must not delete an otherwise valid Tree snapshot.
      MediaStoreGenerationGuard.forgetLastScan(getApplication())
    }
    // A periodic foreground verification is incremental; only changed hidden markers need
    // the forced filesystem/discovery fallback.
    viewModelScope.launch(Dispatchers.IO) {
      loadVideoFolders(forceFileSystemCheck = staleHiddenRoot || markersChanged, silent = true)
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

  /** Publishes MediaStore immediately if empty, then silently merges indexed .nomedia folders in the background. */
  private fun loadVideoFolders(
    forceFileSystemCheck: Boolean = false,
    silent: Boolean = false,
    skipMediaStoreScan: Boolean = false,
  ) {
    currentScanJob?.cancel()
    val listingReady = CompletableDeferred<Unit>()
    currentListingReady = listingReady
    folderContentRevision.update { it + 1 }
    // Reassigned per pass, not set once: a later real scan must be able to record the generation
    // again, otherwise the cold-boot fast path would stay off for the rest of the session.
    // Read *before* the scan — reading it afterwards would record a MediaStore state newer than the
    // one the scan observed, which is exactly how a stale cache gets blessed. A reuse pass has no
    // fresh observation to record, so it clears the value.
    observedMediaStoreGeneration =
      if (skipMediaStoreScan) null else MediaStoreGenerationGuard.currentToken(getApplication())

    if (audioOnly) {
      currentScanJob =
        viewModelScope.launch(Dispatchers.IO) {
          try {
            val hasExistingData = _allVideoFolders.value.isNotEmpty()
            if (!hasExistingData && !silent) {
              _isLoading.value = true
              _scanStatus.value = "Reading music library..."
            }
            val folders =
              MediaFileRepository.getAllAudioFolders(
                context = getApplication(),
                minimumAudioDurationSeconds = browserPreferences.minimumAudioDurationSeconds.get(),
              )
            ensureActive()
            if (_allVideoFolders.value != folders) {
              publishFinalFolders(folders)
            }
            _isLoading.value = false
            _hasCompletedInitialLoad.value = true
            listingReady.complete(Unit)
            if (MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)) {
              enrichDeferredFolderDurations(folders, emptySet())
            }
          } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
          } catch (e: Exception) {
            ensureActive()
            Log.e(TAG, "Error loading audio folders", e)
            _hasCompletedInitialLoad.value = true
          } finally {
            listingReady.complete(Unit)
            if (isActive) {
              _isLoading.value = false
              _isEnriching.value = false
              _scanStatus.value = null
            }
          }
        }
      return
    }

    currentScanJob =
      viewModelScope.launch(Dispatchers.IO) {
        try {
          val hasExistingData = _allVideoFolders.value.isNotEmpty()
          if (!hasExistingData && !silent) {
            _isLoading.value = true
            _scanStatus.value = "Reading media library..."
          }

          val previousFolders = _allVideoFolders.value.associateBy(::folderKey)
          val indexedFolders = MediaFileRepository.getIndexedNoMediaFolders()
          ensureActive()

          // A cold boot with an unchanged MediaStore reuses the cached list instead of re-querying
          // it. The cached list is the *published* one, so it also contains .nomedia folders; those
          // must be excluded here or a folder the index has since dropped would survive as a
          // phantom, since indexedFolders is the only authority on them and merging cannot remove.
          val indexedKeys = indexedFolders.mapTo(hashSetOf(), ::folderKey)
          val mediaStoreFolders =
            if (skipMediaStoreScan && hasExistingData) {
              Log.d(TAG, "Reusing cached MediaStore folders, generation unchanged")
              previousFolders.values
                .filterNot { folderKey(it) in indexedKeys || ScopedHiddenFolderScanner.isScopedFolder(it.bucketId) }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
            } else {
              MediaFileRepository.getAllVideoFoldersFast(
                context = getApplication(),
                onProgress = { count ->
                  if (!hasExistingData && !silent) _scanStatus.value = "Found $count folders"
                },
                forceFileSystemCheck = forceFileSystemCheck,
                includeAudioOverride = browserPreferences.includeAudioBrowser.get(),
              )
            }
          ensureActive()

          // If there is no existing data, publish immediately so the screen isn't empty.
          // If cached data is already visible, do NOT overwrite with partial mediaStore results!
          if (!hasExistingData) {
            _allVideoFolders.value = mediaStoreFolders
            _isLoading.value = false
            _hasCompletedInitialLoad.value = true
          }

          val scopedFolders = if (skipMediaStoreScan) {
            ScopedHiddenFolderScanner.withoutLocalDuplicates(
              mediaStoreFolders + indexedFolders,
              MediaFileRepository.getScopedHiddenVideoFolders(getApplication(), includeAudioOverride = if (audioOnly) true else null),
            )
          } else {
            emptyList()
          }
          var visibleFolders = mergeFolders(mediaStoreFolders, indexedFolders, scopedFolders)
          // This already includes the last hidden-folder index. Publish new ordinary folders now,
          // rather than holding them behind hidden discovery and optional metadata enrichment.
          if (_allVideoFolders.value != visibleFolders) {
            _allVideoFolders.value = visibleFolders
          }
          listingReady.complete(Unit)
          Log.d(TAG, "Scanned ${mediaStoreFolders.size} MediaStore and ${indexedFolders.size} indexed folders")

          var hiddenFolderKeys: Set<String> = indexedKeys
          if (foldersPreferences.hiddenScanEnabled(HiddenScanScope.FOLDERS)) {
            if (!hasExistingData && !silent) {
              _scanStatus.value =
                if (forceFileSystemCheck || indexedFolders.isEmpty()) {
                  "Discovering hidden folders..."
                } else {
                  "Checking hidden folders..."
                }
            }
            MediaFileRepository
              .scanNoMediaFoldersIncrementally(
                context = getApplication(),
                forceDiscovery = forceFileSystemCheck,
              ).collect { batch ->
                ensureActive()
                visibleFolders = mergeFolders(visibleFolders, batch)
                if (!hasExistingData) {
                  _allVideoFolders.value = visibleFolders
                  if (!silent) _scanStatus.value = "Found ${visibleFolders.size} folders"
                }
              }

            // Replace the old indexed snapshot after the scan, removing deleted/stale folders.
            val indexedAfterScan = MediaFileRepository.getIndexedNoMediaFolders()
            hiddenFolderKeys = indexedAfterScan.mapTo(hashSetOf(), ::folderKey)
            visibleFolders = mergeFolders(mediaStoreFolders, indexedAfterScan, scopedFolders)
            ensureActive()
            if (!hasExistingData) {
              _allVideoFolders.value = visibleFolders
            }
          }

          if (visibleFolders.isEmpty()) {
            publishFinalFolders(emptyList())
            return@launch
          }

          var needsEnrichment = false
          val foldersForEnrichment =
            visibleFolders.map { folder ->
              val cached = previousFolders[folderKey(folder)]
              val cachedIsComplete = cached != null && (cached.videoCount == 0 || cached.totalDuration > 0)
              if (cached != null &&
                cached.videoCount == folder.videoCount &&
                cached.totalSize == folder.totalSize &&
                cached.lastModified == folder.lastModified &&
                cachedIsComplete
              ) {
                cached
              } else {
                needsEnrichment = true
                folder
              }
            }

          // Render the fresh folder list before potentially slow file-by-file metadata retrieval.
          // A running enrichment job can be cancelled by the next refresh; it must never hold
          // back visible Library updates. Cache and file metadata are reused above when unchanged.
          if (_allVideoFolders.value != foldersForEnrichment) {
            _allVideoFolders.value = foldersForEnrichment
          }
          _isLoading.value = false
          _hasCompletedInitialLoad.value = true

          val needsDurationEnrichment = needsEnrichment && foldersForEnrichment.isNotEmpty() &&
            MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)

          val finalFolders = if (needsDurationEnrichment) {
            if (!silent) {
              _isEnriching.value = true
              _scanStatus.value = "Processing metadata..."
            }
            val enrichedFolders =
              MetadataRetrieval.enrichFoldersIfNeeded(
                context = getApplication(),
                folders = foldersForEnrichment.filterNot { isDeferredDurationFolder(it, hiddenFolderKeys) },
                browserPreferences = browserPreferences,
                metadataCache = metadataCache,
                onProgress = { processed, total ->
                  if (!silent) _scanStatus.value = "Processing metadata $processed/$total"
                },
              )
            ensureActive()
            val enrichedByKey = enrichedFolders.associateBy(::folderKey)
            foldersForEnrichment.map { enrichedByKey[folderKey(it)] ?: it }
          } else {
            foldersForEnrichment
          }

          // Only publish if the list has actually changed from what is currently displayed
          if (_allVideoFolders.value != finalFolders) {
            publishFinalFolders(finalFolders)
          }

          if (MetadataRetrieval.isFolderMetadataNeeded(browserPreferences)) {
            _isEnriching.value = false
            _scanStatus.value = null
            enrichDeferredFolderDurations(finalFolders, hiddenFolderKeys)
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          Log.d(TAG, "Scan cancelled (new scan started)")
          throw e
        } catch (e: Exception) {
          ensureActive()
          Log.e(TAG, "Error loading video folders", e)
          _hasCompletedInitialLoad.value = true
        } finally {
          listingReady.complete(Unit)
          if (isActive) {
            _isLoading.value = false
            _isEnriching.value = false
            _scanStatus.value = null
          }
        }
      }
  }

  /**
   * Play Store and hidden folders are listed at once, so their totals are filled in here, one folder
   * at a time, after the list is visible. Play Store totals reuse the shared per-document cache, and
   * hidden folders reuse the metadata cache. A positive total can still be partial, so it must not
   * prevent missing durations being filled.
   */
  private suspend fun enrichDeferredFolderDurations(folders: List<VideoFolder>, hiddenKeys: Set<String>) {
    val pending = folders.filter {
      isDeferredDurationFolder(it, hiddenKeys) && it.videoCount > 0
    }
    for (folder in pending) {
      currentCoroutineContext().ensureActive()
      val totalDuration =
        MetadataRetrieval
          .enrichFolderIfNeeded(getApplication(), folder, browserPreferences, metadataCache, audioOnly = audioOnly)
          .totalDuration
      val key = folderKey(folder)
      _allVideoFolders.update { current ->
        current.map { if (folderKey(it) == key) it.copy(totalDuration = totalDuration) else it }
      }
    }
  }

  /** Folders listed without a blocking metadata pass: Play Store grants and indexed hidden folders. */
  private fun isDeferredDurationFolder(folder: VideoFolder, hiddenKeys: Set<String>): Boolean =
    ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId) || folderKey(folder) in hiddenKeys

  private fun folderKey(folder: VideoFolder): String =
    if (ScopedHiddenFolderScanner.isScopedFolder(folder.path)) {
      folder.path
    } else {
      folder.path
        .replace('\\', '/')
        .trimEnd('/')
        .lowercase(Locale.ROOT)
    }

  private fun publishFinalFolders(folders: List<VideoFolder>) {
    val incomingKeys = folders.mapTo(mutableSetOf(), ::folderKey)
    val confirmedDeletionKeys = completedFolderDeletionKeys.value - incomingKeys
    if (confirmedDeletionKeys.isNotEmpty()) {
      pendingFolderDeletionKeys.update { it - confirmedDeletionKeys }
      completedFolderDeletionKeys.update { it - confirmedDeletionKeys }
    }
    _allVideoFolders.value = folders
  }

  private fun mergeFolders(vararg groups: List<VideoFolder>): List<VideoFolder> {
    val merged = linkedMapOf<String, VideoFolder>()
    groups.forEach { folders -> folders.forEach { folder -> merged[folderKey(folder)] = folder } }
    return merged.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
  }
}
