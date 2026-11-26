/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.medialibrary

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.HiddenScanScope
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.browser.videolist.VideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers
import app.gyrolet.mpvrx.utils.logging.Log
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import app.gyrolet.mpvrx.utils.storage.UnindexedVideoDiscovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MediaLibraryViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val foldersPreferences: FoldersPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private enum class DeletionState { Pending, Confirmed }

  private val hiddenVideoPaths = MutableStateFlow<Map<String, DeletionState>>(emptyMap())
  private val whitelistFlow = foldersPreferences.whitelistChanges()
  private val loadRequests = Channel<Boolean>(Channel.CONFLATED)
  private var enrichmentJob: Job? = null
  // Library's own hidden-folder index refresh; see loadData.
  private var hiddenIndexJob: Job? = null
  private val _videos = MutableStateFlow<List<Video>>(emptyList())
  val videos: StateFlow<List<Video>> =
    combine(
      _videos,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
      whitelistFlow,
    ) { items, hidden, videoBlacklist, audioBlacklist, allowed ->
      items.filter { isVisible(it, hidden, videoBlacklist, audioBlacklist, allowed) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> =
    combine(
      _videosWithPlaybackInfo,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
      whitelistFlow,
    ) { items, hidden, videoBlacklist, audioBlacklist, allowed ->
      items.filter { isVisible(it.video, hidden, videoBlacklist, audioBlacklist, allowed) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val tag = "MediaLibraryViewModel"

  private fun isVisible(
    video: Video,
    hidden: Map<String, DeletionState>,
    videoBlacklist: Set<String>,
    audioBlacklist: Set<String>,
    whitelistState: Pair<Boolean, Set<String>>,
  ): Boolean {
    if (video.path in hidden) return false
    val (whitelistEnabled, whitelist) = whitelistState
    if (!app.gyrolet.mpvrx.utils.storage.isWhitelistedMediaPath(video.bucketId, whitelistEnabled, whitelist) &&
      !app.gyrolet.mpvrx.utils.storage.isWhitelistedMediaPath(video.path, whitelistEnabled, whitelist)
    ) return false
    val blacklist = if (video.isAudio) audioBlacklist else videoBlacklist
    return blacklist.none { folder ->
      app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.bucketId, folder) ||
        app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.path, folder)
    }
  }

  init {
    viewModelScope.launch(Dispatchers.IO) {
      // Restore before consuming refreshes so a slow cache read cannot overwrite a newer list.
      val restored = loadCachedVideos()
      if (!restored || foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY) ||
        UnindexedVideoDiscovery.hasVideos || _videos.value.any { it.uri.scheme == "file" }
      ) {
        loadData(force = true)
      }
      // Finish the lightweight listing, retaining only the newest queued request. Cancelling
      // every read during a long extraction can starve publication until copying stops.
      loadRequests.receiveAsFlow().collect { force -> loadData(force) }
    }
    viewModelScope.launch(Dispatchers.IO) {
      val hiddenPreferences = combine(
        foldersPreferences.includeNoMediaFolders.changes(),
        foldersPreferences.libraryHiddenScanEnabled.changes(),
        foldersPreferences.hiddenFolderMarkerNames.changes(),
        foldersPreferences.sharedScanMarkedFolders.changes(),
        foldersPreferences.sharedScanDotFolders.changes(),
        foldersPreferences.sharedIgnoredMarkers.changes(),
      ) { _ -> Unit }.drop(1)
      merge(
        app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.changes.map { true },
        hiddenPreferences.map { false },
      ).collectLatest { mediaChanged ->
        // Changing Library's scan policy must not destroy the saved Tree snapshot.
        hiddenIndexJob?.cancel()
        MediaLibraryCache.clear(getApplication())
        MediaFileRepository.invalidateFolderCache()
        if (mediaChanged) MediaStoreGenerationGuard.invalidate(getApplication())
        loadRequests.trySend(true)
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        if (_videos.value.isNotEmpty()) updatePlaybackInfo(mediaIdentifier)
      }
    }
  }

  /**
   * A mode-specific key prevents a snapshot created with one hidden-folder policy from being
   * presented under another. Full video metadata is persisted separately from this key.
   */
  private fun currentLibraryCacheKey(): String =
    "library_v3|scoped=${BuildConfig.SCOPED_STORAGE_ONLY}" +
      "|hidden=${foldersPreferences.libraryHiddenScanEnabled.get()}" +
      "|nomedia=${foldersPreferences.includeNoMediaFolders.get()}" +
      "|marked=${foldersPreferences.sharedScanMarkedFolders.get()}" +
      "|dot=${foldersPreferences.sharedScanDotFolders.get()}" +
      "|markers=${foldersPreferences.hiddenFolderMarkerNames.get().sorted().joinToString(",")}" +
      "|ignored=${foldersPreferences.sharedIgnoredMarkers.get().sorted().joinToString(",")}" +
      "|uris=${foldersPreferences.scopedHiddenFolderUris.get().sorted().joinToString(",")}" +
      "|audioMin=${browserPreferences.minimumAudioDurationSeconds.get()}" +
      "|${MediaFileRepository.currentScanOptions().mediaFilterKey}"

  /** Show a validated saved list immediately; background work must never blank it out. */
  private fun loadCachedVideos(): Boolean {
    val cached =
      MediaLibraryCache.load(
        getApplication(),
        scanOptionsKey = currentLibraryCacheKey(),
        requireMetadata = MetadataRetrieval.isVideoMetadataNeeded(browserPreferences),
      ) ?: return false
    _videos.value = cached
    _isLoading.value = false
    viewModelScope.launch(Dispatchers.IO) {
      loadPlaybackInfo(cached)
    }
    return true
  }

  private suspend fun loadData(force: Boolean = false) {
    // Guards a re-entry that has nothing new to learn: a media event fired while the list on screen
    // still matches the current MediaStore, or refresh() was asked to invalidate and rebuild. force
    // comes from refresh() and always re-reads. The MediaStore check queries providers, so it runs
    // off the main thread.
    withContext(Dispatchers.IO) {
      enrichmentJob?.cancel()
      if (!force &&
        !foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY) &&
        _videos.value.isNotEmpty() &&
        MediaStoreGenerationGuard.isUnchangedSinceLastScan(getApplication())
      ) {
        Log.d(tag, "MediaStore unchanged, keeping current media library")
        return@withContext
      }
      try {
        // Read before scanning: recording the generation afterwards would bless a MediaStore newer
        // than the data, and the next cold boot would keep a stale list.
        val observedGeneration = MediaStoreGenerationGuard.currentToken(getApplication())
        val scanCacheKey = currentLibraryCacheKey()
        val metadataEnriched = MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)

        if (_videos.value.isEmpty()) {
          _isLoading.value = true
        }
        val scannedVideos =
          MediaFileRepository.getAllVideos(
            context = getApplication(),
            includeAudioOverride = true,
          )

        // Reuse durations learned in any browser mode before showing the fresh listing.
        // Only genuinely missing metadata may open documents, after the list is visible.
        val videoList = MetadataRetrieval.restoreCachedContentMetadata(scannedVideos, metadataCache)
        publishVideos(videoList, scanCacheKey, observedGeneration, metadataEnriched = false)

        if (metadataEnriched) {
          // Metadata must never hold the refresh queue. The next listing cancels this work.
          enrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
              val enriched = MetadataRetrieval.enrichVideosIfNeeded(
                context = getApplication(),
                videos = videoList,
                browserPreferences = browserPreferences,
                metadataCache = metadataCache,
              )
              publishVideos(enriched, scanCacheKey, observedGeneration, metadataEnriched = true)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              Log.w(tag, "Could not enrich media library", error)
            }
          }
        }

        // .nomedia folders come from Library's own index. Keep it current off the load queue and
        // list again only when hidden folders were added, changed or removed; the next pass then
        // finds nothing stale, so this settles.
        if (foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY) && hiddenIndexJob?.isActive != true) {
          hiddenIndexJob = viewModelScope.launch(Dispatchers.IO) {
            try {
              if (MediaFileRepository.refreshLibraryHiddenFolders(getApplication(), includeAudioOverride = true)) {
                loadRequests.trySend(false)
              }
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              Log.w(tag, "Could not refresh hidden Library folders", error)
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(tag, "Error loading media library videos", e)
      } finally {
        _isLoading.value = false
      }
    }
  }

  private suspend fun publishVideos(
    videos: List<Video>,
    scanCacheKey: String,
    observedGeneration: String?,
    metadataEnriched: Boolean,
  ) {
    currentCoroutineContext().ensureActive()
    // MediaStore can report an unknown duration until cached metadata is restored or enriched.
    // Reapply thresholds after enrichment so newly discovered short videos are excluded too.
    val scanOptions = MediaFileRepository.currentScanOptions(includeAudioOverride = true)
    val eligible = videos.filter { scanOptions.includesMedia(it.size, it.duration, it.isAudio) }
    _videos.value = eligible
    val scannedPaths = eligible.mapTo(hashSetOf()) { it.path }
    hiddenVideoPaths.update { states ->
      states.filter { (path, state) -> state == DeletionState.Pending || path in scannedPaths }
    }
    MediaLibraryCache.save(
      getApplication(),
      eligible.filterNot { hiddenVideoPaths.value[it.path] == DeletionState.Confirmed },
      scanOptionsKey = scanCacheKey,
      observedGeneration = observedGeneration,
      metadataEnriched = metadataEnriched,
    )
    MediaStoreGenerationGuard.remember(getApplication(), observedGeneration)
    loadPlaybackInfo(eligible)
    _isLoading.value = false
  }

  override suspend fun deleteVideos(videos: List<Video>): Pair<Int, Int> {
    val targets = videos.distinctBy { it.path }.filterNot { it.path in hiddenVideoPaths.value }
    if (targets.isEmpty()) return 0 to 0
    val paths = targets.mapTo(hashSetOf()) { it.path }
    hiddenVideoPaths.update { it + paths.associateWith { DeletionState.Pending } }
    return try {
      super.deleteVideos(targets) { video ->
        hiddenVideoPaths.update { it + (video.path to DeletionState.Confirmed) }
      }
    } finally {
      hiddenVideoPaths.update { states ->
        states.filterNot { (path, state) -> path in paths && state == DeletionState.Pending }
      }
    }
  }

  override fun refresh() {
    // A user-driven refresh must never be satisfied by the persisted list.
    MediaLibraryCache.clear(getApplication())
    MediaFileRepository.invalidateFolderCache()
    MediaStoreGenerationGuard.invalidate(getApplication())
    loadRequests.trySend(true)
    viewModelScope.launch(Dispatchers.IO) {
      UnindexedVideoDiscovery.refresh(getApplication(), force = true)
      loadRequests.trySend(true)
    }
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackStates = playbackStateRepository.getAllPlaybackStates()
    val currentTime = System.currentTimeMillis()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val playbackByTitle = playbackStates.associateBy { it.mediaTitle }
    playbackIndexByIdentifier =
      buildMap(videos.size * 4) {
        videos.forEachIndexed { index, video ->
          videoPlaybackIdentifiers(video).forEach { identifier -> put(identifier, index) }
        }
      }

    val videosWithInfo =
      videos.map { video ->
        buildVideoWithPlaybackInfo(
          video = video,
          playbackState = videoPlaybackIdentifiers(video).firstNotNullOfOrNull(playbackByTitle::get),
          currentTimeMillis = currentTime,
          newLabelDays = thresholdDays,
          watchedThreshold = watchedThreshold,
        )
      }
    _videosWithPlaybackInfo.value = videosWithInfo
  }

  private suspend fun updatePlaybackInfo(mediaIdentifier: String) {
    if (mediaIdentifier.isBlank()) {
      loadPlaybackInfo(_videos.value)
      return
    }

    val index = playbackIndexByIdentifier[mediaIdentifier] ?: return
    val videos = _videos.value
    val video = videos.getOrNull(index) ?: return
    val currentItems = _videosWithPlaybackInfo.value
    if (currentItems.size != videos.size || currentItems.getOrNull(index)?.video?.path != video.path) {
      loadPlaybackInfo(videos)
      return
    }

    val updatedItem =
      buildVideoWithPlaybackInfo(
        video = video,
        playbackState = playbackStateRepository.getVideoDataByTitle(mediaIdentifier),
        currentTimeMillis = System.currentTimeMillis(),
        newLabelDays = appearancePreferences.unplayedOldVideoDays.get(),
        watchedThreshold = browserPreferences.watchedThreshold.get(),
      )
    if (currentItems[index] == updatedItem) return

    _videosWithPlaybackInfo.value =
      currentItems.toMutableList().apply {
        this[index] = updatedItem
      }
  }

  fun setWatched(video: Video, watched: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateOps.setWatched(video, watched)
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaLibraryViewModel(application) as T
      }
  }
}
