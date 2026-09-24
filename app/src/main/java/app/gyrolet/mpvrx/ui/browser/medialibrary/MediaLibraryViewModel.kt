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
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.browser.videolist.VideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.buildVideoWithPlaybackInfo
import app.gyrolet.mpvrx.ui.browser.videolist.videoPlaybackIdentifiers
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MediaLibraryViewModel(
  application: Application,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val appearancePreferences: AppearancePreferences by inject()
  private val browserPreferences: BrowserPreferences by inject()
  private val playbackStateRepository: PlaybackStateRepository by inject()

  private val _videos = MutableStateFlow<List<Video>>(emptyList())
  val videos: StateFlow<List<Video>> = _videos.asStateFlow()

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> = _videosWithPlaybackInfo.asStateFlow()

  private val _isLoading = MutableStateFlow(false)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()
  private var loadJob: kotlinx.coroutines.Job? = null
  private val loadGeneration = java.util.concurrent.atomic.AtomicLong()

  private val tag = "MediaLibraryViewModel"

  init {
    loadData()
    viewModelScope.launch {
      app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.changes.collectLatest {
        loadData()
      }
    }
    viewModelScope.launch {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        if (_videos.value.isNotEmpty()) updatePlaybackInfo(mediaIdentifier)
      }
    }
  }

  private fun loadData() {
    val generation = loadGeneration.incrementAndGet()
    loadJob?.cancel()
    val previous = _videos.value
    loadJob = viewModelScope.launch {
      try {
        _isLoading.value = previous.isEmpty()
        var videoList =
          MediaFileRepository.getAllVideos(
            context = getApplication(),
            includeAudioOverride = true,
            onSnapshot = { partial ->
              kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                ensureActive()
                if (generation == loadGeneration.get() && partial.isNotEmpty()) {
                  val paths = partial.mapTo(HashSet()) { it.path }
                  publishVideos(partial + previous.filterNot { it.path in paths })
                }
              }
            },
          )
        if (generation != loadGeneration.get()) return@launch
        publishVideos(videoList)

        if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
          videoList =
            MetadataRetrieval.enrichVideosIfNeeded(
              context = getApplication(),
              videos = videoList,
              browserPreferences = browserPreferences,
              metadataCache = metadataCache,
              onSnapshot = { partial ->
                kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                  ensureActive()
                  if (generation == loadGeneration.get()) publishVideos(partial)
                }
              },
            )
        }

        if (generation != loadGeneration.get()) return@launch
        publishVideos(videoList)
        loadPlaybackInfo(videoList)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(tag, "Error loading media library videos", e)
      } finally {
        if (generation == loadGeneration.get()) _isLoading.value = false
      }
    }
  }

  override fun refresh() {
    loadData()
  }

  private fun publishVideos(videos: List<Video>) {
    val previous = _videosWithPlaybackInfo.value.associateBy { it.video.path }
    _videos.value = videos
    _videosWithPlaybackInfo.value = videos.map { video ->
      previous[video.path]?.copy(video = video) ?: VideoWithPlaybackInfo(video)
    }
    if (videos.isNotEmpty()) _isLoading.value = false
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackStates = playbackStateRepository.getAllPlaybackStates()
    if (_videos.value != videos) return
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
