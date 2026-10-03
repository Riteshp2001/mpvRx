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
import android.media.MediaScannerConnection
import android.os.Environment
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
import kotlinx.coroutines.delay
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
  private var snapshotWriteJob: Job? = null

  private val tag = "MediaLibraryViewModel"

  init {
    restoreSnapshotOrScan()
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

  /**
   * The persisted listing is the whole launch path for this mode: a warm start shows it and scans
   * nothing. Re-deriving the library on open is a MediaStore query per folder, which is what made
   * entering this mode as slow as a cold start, and it could only reproduce what the snapshot
   * already holds. Refreshing is explicit ([refresh]) or event-driven.
   *
   * Read off the main thread because this is one record per video: a large library is megabytes of
   * JSON, which is far too much to parse during composition.
   */
  private fun restoreSnapshotOrScan() {
    val generationAtStart = loadGeneration.get()
    // Raised for the duration of the read so the list is never briefly "found nothing" between an
    // empty state and the snapshot landing. The scan path below owns this flag from then on.
    _isLoading.value = true
    viewModelScope.launch(Dispatchers.IO) {
      val snapshot = MediaLibrarySnapshot.read(getApplication(), snapshotKey())
      when {
        // A scan started while the file was being read, from a media event or a manual refresh.
        // Its result is the newer one, so publishing the snapshot now would undo it.
        generationAtStart != loadGeneration.get() -> Unit

        // Nothing persisted yet, so a scan is the only way to have a list to show at all.
        snapshot.isEmpty() -> loadData()

        else -> {
          _videos.value = snapshot
          loadPlaybackInfo(snapshot)
          _isLoading.value = false
        }
      }
    }
  }

  /** Namespaces the listing by the scan options behind it, so stale options never read as current. */
  private fun snapshotKey(): String =
    MediaFileRepository.currentScanOptions(includeAudioOverride = true).cacheKey

  private fun saveSnapshot(videos: List<Video>) {
    snapshotWriteJob?.cancel()
    snapshotWriteJob =
      viewModelScope.launch(Dispatchers.IO) {
        // Same cadence the folder snapshot uses: an operation that ends in several quick scans
        // should not rewrite the file once per scan.
        delay(SNAPSHOT_WRITE_DEBOUNCE_MS)
        MediaLibrarySnapshot.write(getApplication(), snapshotKey(), videos)
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
                onSnapshot = publish@{ partial ->
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
              saveSnapshot(enriched)
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
    // This mode lists the whole library by asking for every video folder first, and that folder
    // list is the cached snapshot folder mode persists. Reading it again could only republish the
    // same buckets, so a file in a folder created since the last scan could never show up here.
    // Dropping the cache is what makes this a hard refresh, matching folder and tree mode.
    MediaFileRepository.clearCache()
    // Files copied by other apps are not in MediaStore until the platform indexes them, so ask it
    // to, exactly as the other two modes do. MediaScanReceiver turns the completion into a media
    // event, which reloads the list once the new rows exist.
    triggerMediaScan()
    loadData()
  }

  private fun triggerMediaScan() {
    try {
      val externalStorage = Environment.getExternalStorageDirectory()
      MediaScannerConnection.scanFile(
        getApplication(),
        arrayOf(externalStorage.absolutePath),
        null,
      ) { path, uri ->
        Log.d(tag, "Media scan completed for: $path -> $uri")
      }
    } catch (error: Exception) {
      Log.e(tag, "Failed to trigger media scan", error)
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
    /**
     * Floor between two mid-scan publishes. Each one re-sorts the library and restarts the
     * thumbnail pipeline, so unthrottled streaming would cost more than it saves.
     */
    private const val PARTIAL_PUBLISH_INTERVAL_MS = 700L

    /** Same reason as the publish floor: the file is rewritten in full, so batch the writes. */
    private const val SNAPSHOT_WRITE_DEBOUNCE_MS = 750L

    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MediaLibraryViewModel(application) as T
      }
  }
}
