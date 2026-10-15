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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
  private val scanMutex = Mutex()
  private val _videos = MutableStateFlow<List<Video>>(emptyList())
  val videos: StateFlow<List<Video>> =
    combine(
      _videos,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
    ) { items, hidden, videoBlacklist, audioBlacklist ->
      items.filter { isVisible(it, hidden, videoBlacklist, audioBlacklist) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> =
    combine(
      _videosWithPlaybackInfo,
      hiddenVideoPaths,
      foldersPreferences.blacklistedFolders.changes(),
      foldersPreferences.blacklistedAudioFolders.changes(),
    ) { items, hidden, videoBlacklist, audioBlacklist ->
      items.filter { isVisible(it.video, hidden, videoBlacklist, audioBlacklist) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val tag = "MediaLibraryViewModel"

  private fun isVisible(
    video: Video,
    hidden: Map<String, DeletionState>,
    videoBlacklist: Set<String>,
    audioBlacklist: Set<String>,
  ): Boolean {
    if (video.path in hidden) return false
    val blacklist = if (video.isAudio) audioBlacklist else videoBlacklist
    return blacklist.none { folder ->
      app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.bucketId, folder) ||
        app.gyrolet.mpvrx.utils.storage.isMediaPathInFolder(video.path, folder)
    }
  }

  init {
    // Try the persisted list first so an unchanged library needs no scan at all. Without this, every
    // cold boot queried MediaStore once per folder before the screen had anything to show.
    val restored = loadCachedVideos()
    if (!restored) {
      loadData()
    }
    viewModelScope.launch(Dispatchers.IO) {
      val hiddenPreferences = combine(
        foldersPreferences.includeNoMediaFolders.changes(),
        foldersPreferences.hiddenFolderMarkerNames.changes(),
        foldersPreferences.sharedScanMarkedFolders.changes(),
        foldersPreferences.sharedScanDotFolders.changes(),
        foldersPreferences.sharedIgnoredMarkers.changes(),
      ) { _ -> Unit }.drop(1)
      merge(app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.changes, hiddenPreferences).collectLatest {
        // A change in Library's hidden-folder policy must not reuse the old path list.
        MediaLibraryCache.clear(getApplication())
        MediaStoreGenerationGuard.invalidate(getApplication())
        loadData(force = true)
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        if (_videos.value.isNotEmpty()) updatePlaybackInfo(mediaIdentifier)
      }
    }
  }

  /**
 * Restores the persisted list without scanning, when it is still trustworthy.
 *
 * The cache stores no fps, dimensions or codec, so it is only complete enough to use when no
 * metadata chip is enabled. With a chip on, the list must be rebuilt so the chips have real values,
 * which also means the scan runs regardless — the cache then only avoids an empty first frame.
 */
  private fun loadCachedVideos(): Boolean {
    if (foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY)) return false
    if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) return false
    if (!MediaStoreGenerationGuard.isUnchangedSinceLastScan(getApplication())) return false
    val cached = MediaLibraryCache.load(getApplication())
    if (cached.isNullOrEmpty()) return false
    _videos.value = cached
    viewModelScope.launch(Dispatchers.IO) {
      loadPlaybackInfo(cached)
    }
    return true
  }

  private fun loadData(force: Boolean = false) {
    // Guards a re-entry that has nothing new to learn: a media event fired while the list on screen
    // still matches the current MediaStore, or refresh() was asked to invalidate and rebuild. force
    // comes from refresh() and always re-reads.
    if (!force &&
      !foldersPreferences.hiddenScanEnabled(HiddenScanScope.LIBRARY) &&
      _videos.value.isNotEmpty() &&
      MediaStoreGenerationGuard.isUnchangedSinceLastScan(getApplication())
    ) {
      Log.d(tag, "MediaStore unchanged, keeping current media library")
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      scanMutex.withLock {
        try {
          // Read before scanning: recording the generation afterwards would bless a MediaStore newer
          // than the data, and the next cold boot would keep a stale list.
          val observedGeneration = MediaStoreGenerationGuard.currentToken(getApplication())

          if (_videos.value.isEmpty()) {
            _isLoading.value = true
          }
          var videoList =
            MediaFileRepository.getAllVideos(
              context = getApplication(),
              includeAudioOverride = true,
            )

          if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
            videoList =
              MetadataRetrieval.enrichVideosIfNeeded(
                context = getApplication(),
                videos = videoList,
                browserPreferences = browserPreferences,
                metadataCache = metadataCache,
              )
          }

          _videos.value = videoList
          val scannedPaths = videoList.mapTo(hashSetOf()) { it.path }
          hiddenVideoPaths.update { states ->
            states.filter { (path, state) -> state == DeletionState.Pending || path in scannedPaths }
          }
          MediaLibraryCache.save(
            getApplication(),
            videoList.filterNot { hiddenVideoPaths.value[it.path] == DeletionState.Confirmed },
          )
          MediaStoreGenerationGuard.remember(getApplication(), observedGeneration)
          loadPlaybackInfo(videoList)
        } catch (e: Exception) {
          Log.e(tag, "Error loading media library videos", e)
        } finally {
          _isLoading.value = false
        }
      }
    }
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
    MediaStoreGenerationGuard.invalidate(getApplication())
    loadData(force = true)
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
