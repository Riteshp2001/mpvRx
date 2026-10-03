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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

  @Volatile private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val loadJob = AtomicReference<Job?>(null)
  private val loadGeneration = AtomicInteger(0)
  private val loadLock = Any()

  private val tag = "MediaLibraryViewModel"

  init {
    loadData()
    viewModelScope.launch(Dispatchers.IO) {
      app.gyrolet.mpvrx.utils.media.MediaLibraryEvents.changes.collectLatest {
        loadData()
      }
    }
    viewModelScope.launch(Dispatchers.IO) {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        // Mid-scan the raw list and the decorated list are from different generations, so the
        // size guard below would fail and rebuild the whole index on every single event.
        if (loadJob.get()?.isActive != true && _videos.value.isNotEmpty()) updatePlaybackInfo(mediaIdentifier)
      }
    }
  }

  private fun loadData() {
    var lastPublishAt = 0L
    // Media events arrive in bursts, and each load lists every folder, so only the newest run is
    // wanted. Reached from both the main thread and an IO collector, so retiring the previous
    // job and publishing the new one has to be one step; otherwise a run started mid-swap is
    // never cancelled and two full library scans end up racing.
    synchronized(loadLock) {
      loadJob.getAndSet(null)?.cancel()
      val generation = loadGeneration.incrementAndGet()
      loadJob.set(
        viewModelScope.launch(Dispatchers.IO) {
          try {
            _isLoading.value = true
            val videoList =
              MediaFileRepository.getAllVideos(
                context = getApplication(),
                includeAudioOverride = true,
                // Republish as results stream in so the list appears instead of one long
                // spinner. Throttled, and always with matching playback info, because every
                // publish re-sorts the list and restarts the thumbnail pipeline.
                onPartial = publish@{ partial ->
                  if (partial.isEmpty()) return@publish
                  val now = System.currentTimeMillis()
                  if (now - lastPublishAt < PARTIAL_PUBLISH_INTERVAL_MS) return@publish
                  lastPublishAt = now
                  _videos.value = partial
                  loadPlaybackInfo(partial)
                },
              )

            val enriched =
              if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
                MetadataRetrieval.enrichVideosIfNeeded(
                  context = getApplication(),
                  videos = videoList,
                  browserPreferences = browserPreferences,
                  metadataCache = metadataCache,
                )
              } else {
                videoList
              }

            // A superseded run must not overwrite the newer one it raced with.
            if (generation == loadGeneration.get()) {
              _videos.value = enriched
              loadPlaybackInfo(enriched)
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(tag, "Error loading media library videos", e)
          } finally {
            // A superseded run must not clear the flag its replacement already raised.
            if (generation == loadGeneration.get()) _isLoading.value = false
          }
        },
      )
    }
  }

  override fun refresh() {
    loadData()
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
    /**
     * Floor between two mid-scan publishes. Each one re-sorts the library and restarts the
     * thumbnail pipeline, so unthrottled streaming would cost more than it saves.
     */
    private const val PARTIAL_PUBLISH_INTERVAL_MS = 700L

    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaLibraryViewModel(application) as T
      }
  }
}
