/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.videolist

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.gyrolet.mpvrx.database.entities.PlaybackStateEntity
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.playbackstate.repository.PlaybackStateRepository
import app.gyrolet.mpvrx.repository.MediaFileRepository
import app.gyrolet.mpvrx.ui.browser.base.BaseBrowserViewModel
import app.gyrolet.mpvrx.ui.player.PlaybackIdentity
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.FolderViewScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

@Immutable
data class VideoWithPlaybackInfo(
  val video: Video,
  val timeRemaining: Long? = null, // in seconds
  val progressPercentage: Float? = null, // 0.0 to 1.0
  val isOldAndUnplayed: Boolean = false, // true while the NEW badge is eligible
  val isWatched: Boolean = false, // true once the configured watched threshold is reached
)

internal fun videoPlaybackIdentifiers(video: Video): Set<String> =
  linkedSetOf(
    PlaybackIdentity.forLocalPath(video.path),
    PlaybackIdentity.forUri(video.uri.toString()),
    PlaybackIdentity.forUri(video.path),
    PlaybackIdentity.forUri("file://${video.path}"),
  )

internal fun buildVideoWithPlaybackInfo(
  video: Video,
  playbackState: PlaybackStateEntity?,
  currentTimeMillis: Long,
  newLabelDays: Int,
  watchedThreshold: Int,
): VideoWithPlaybackInfo {
  val durationSeconds = video.duration / 1000L
  val progressValue =
    if (playbackState != null && durationSeconds > 0L) {
      val watchedSeconds = durationSeconds - playbackState.timeRemaining.toLong()
      (watchedSeconds.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
    } else {
      null
    }
  val isWatched =
    playbackState?.hasBeenWatched == true ||
      (watchedThreshold > 0 && progressValue != null && progressValue >= watchedThreshold / 100f)
  val newLabelWindowMillis = newLabelDays.toLong() * 24L * 60L * 60L * 1000L
  val videoAgeMillis = currentTimeMillis - video.dateModified * 1000L
  val isWithinNewLabelWindow = newLabelDays == 0 || videoAgeMillis <= newLabelWindowMillis

  return VideoWithPlaybackInfo(
    video = video,
    timeRemaining = playbackState?.timeRemaining?.toLong(),
    progressPercentage = progressValue?.takeIf { it in 0.01f..0.99f },
    isOldAndUnplayed = !isWatched && (playbackState?.newLabelOverride ?: isWithinNewLabelWindow),
    isWatched = isWatched,
  )
}

class VideoListViewModel(
  application: Application,
  private val bucketId: String,
  private val includeAudio: Boolean = false,
) : BaseBrowserViewModel(application),
  KoinComponent {
  private val playbackStateRepository: PlaybackStateRepository by inject()
  private val appearancePreferences: app.gyrolet.mpvrx.preferences.AppearancePreferences by inject()
  private val browserPreferences: app.gyrolet.mpvrx.preferences.BrowserPreferences by inject()
  private val recentlyPlayedRepository: app.gyrolet.mpvrx.domain.recentlyplayed.repository.RecentlyPlayedRepository by inject()
  // Using MediaFileRepository singleton directly

  private val _videos = MutableStateFlow<List<Video>>(emptyList())
  val videos: StateFlow<List<Video>> = _videos.asStateFlow()

  private val _videosWithPlaybackInfo = MutableStateFlow<List<VideoWithPlaybackInfo>>(emptyList())
  val videosWithPlaybackInfo: StateFlow<List<VideoWithPlaybackInfo>> = _videosWithPlaybackInfo.asStateFlow()

  private val _isLoading = MutableStateFlow(true)
  val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

  // Track if items were deleted/moved leaving folder empty
  private val _videosWereDeletedOrMoved = MutableStateFlow(false)
  val videosWereDeletedOrMoved: StateFlow<Boolean> = _videosWereDeletedOrMoved.asStateFlow()

  val lastPlayedInFolderPath: StateFlow<String?> =
    recentlyPlayedRepository
      .observeRecentlyPlayed(limit = 100)
      .map { recentlyPlayedList ->
        val folderPath =
          _videos.value
            .firstOrNull()
            ?.path
            ?.let { File(it).parent }
        if (folderPath != null) {
          recentlyPlayedList
            .firstOrNull { entity ->
              try {
                File(entity.filePath).parent == folderPath
              } catch (_: Exception) {
                false
              }
            }?.filePath
        } else {
          null
        }
      }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  // Track previous video count to detect if folder became empty
  private var previousVideoCount = 0
  private var loadJob: kotlinx.coroutines.Job? = null
  private val loadGeneration = java.util.concurrent.atomic.AtomicLong()
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val tag = "VideoListViewModel"

  init {
    loadVideos()

    // Listen for global media library changes and refresh this list when they occur
    viewModelScope.launch {
      MediaLibraryEvents.changes.collectLatest {
        loadVideos()
      }
    }

    // Playback persistence emits this event whenever a position/watched state is saved. Re-read
    // the affected playback metadata so NEW/progress/watched UI updates without a hard refresh.
    viewModelScope.launch {
      PlaybackStateEvents.changes.collectLatest { mediaIdentifier ->
        if (_videos.value.isNotEmpty()) {
          updatePlaybackInfo(mediaIdentifier)
        }
      }
    }
  }

  override fun refresh() {
    Log.d(tag, "Hard refreshing video list for bucket: $bucketId")

    // Set loading state
    _isLoading.value = _videos.value.isEmpty()

    // Clear cache to force fresh data from filesystem
    MediaFileRepository.clearCache()
    FolderViewScanner.clearCache()

    loadVideos(forceFileSystemCheck = true)
  }

  private fun loadVideos(forceFileSystemCheck: Boolean = false) {
    val generation = loadGeneration.incrementAndGet()
    loadJob?.cancel()
    val previous = _videos.value
    loadJob = viewModelScope.launch {
      try {
        if (forceFileSystemCheck && !ZipArchiveMedia.isBrowserPath(bucketId)) {
          kotlinx.coroutines.withContext(Dispatchers.IO) { triggerMediaScan() }
        }
        val onSnapshot: suspend (List<Video>) -> Unit = { snapshot ->
          kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (generation == loadGeneration.get()) {
              val visible = if (includeAudio) snapshot.filter { it.isAudio } else snapshot
              if (visible.isNotEmpty()) {
                val paths = visible.mapTo(HashSet()) { it.path }
                publishVideos(visible + previous.filterNot { it.path in paths })
              }
            }
          }
        }
        val onMetadata: suspend (List<Video>) -> Unit = { snapshot ->
          kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (generation == loadGeneration.get()) publishVideos(snapshot)
          }
        }
        // First attempt to load videos (basic info from MediaStore)
        var videoList =
          MediaFileRepository.getVideosInFolder(
            getApplication(),
            bucketId,
            forceFileSystemCheck = forceFileSystemCheck,
            includeAudioOverride = if (includeAudio) true else null,
            onSnapshot = onSnapshot,
          )
        if (includeAudio) {
          videoList = videoList.filter { it.isAudio }
        }

        // ZIP entries need basic metadata even when optional metadata chips are disabled.
        val archiveFolder = ZipArchiveMedia.isBrowserPath(bucketId)
        if (generation != loadGeneration.get()) return@launch
        if (videoList.isNotEmpty()) publishVideos(videoList)
        if (archiveFolder || MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
          Log.d(tag, "Enriching ${videoList.size} videos with metadata")
          videoList =
            MetadataRetrieval.enrichVideosIfNeeded(
              context = getApplication(),
              videos = videoList,
              browserPreferences = browserPreferences,
              metadataCache = metadataCache,
              onSnapshot = onMetadata,
            )
        } else {
          Log.d(tag, "Metadata extraction not required")
        }

        // Check if folder became empty after having videos
        if (previousVideoCount > 0 && videoList.isEmpty()) {
          _videosWereDeletedOrMoved.value = true
          Log.d(tag, "Folder became empty (had $previousVideoCount videos before)")
        } else if (videoList.isNotEmpty()) {
          // Reset flag if folder now has videos
          _videosWereDeletedOrMoved.value = false
        }

        // Update previous count
        previousVideoCount = videoList.size

        if (videoList.isEmpty() && !archiveFolder) {
          Log.d(tag, "No videos found for bucket $bucketId - attempting media rescan")
          kotlinx.coroutines.withContext(Dispatchers.IO) { triggerMediaScan() }
          delay(1000)
          var retryVideoList =
            MediaFileRepository.getVideosInFolder(
              getApplication(),
              bucketId,
              forceFileSystemCheck = true,
              includeAudioOverride = if (includeAudio) true else null,
              onSnapshot = onSnapshot,
            )
          if (includeAudio) {
            retryVideoList = retryVideoList.filter { it.isAudio }
          }
          if (generation != loadGeneration.get()) return@launch
          if (retryVideoList.isNotEmpty()) publishVideos(retryVideoList)

          // Enrich retry list if needed
          if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
            retryVideoList =
              MetadataRetrieval.enrichVideosIfNeeded(
                context = getApplication(),
                videos = retryVideoList,
                browserPreferences = browserPreferences,
                metadataCache = metadataCache,
                onSnapshot = onMetadata,
              )
          }

          // Update count after retry
          if (previousVideoCount > 0 && retryVideoList.isEmpty()) {
            _videosWereDeletedOrMoved.value = true
          } else if (retryVideoList.isNotEmpty()) {
            _videosWereDeletedOrMoved.value = false
          }
          previousVideoCount = retryVideoList.size

          if (generation != loadGeneration.get()) return@launch
          publishVideos(retryVideoList)
          loadPlaybackInfo(retryVideoList)
        } else {
          if (generation != loadGeneration.get()) return@launch
          publishVideos(videoList)
          loadPlaybackInfo(videoList)
        }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e(tag, "Error loading videos for bucket $bucketId", e)
      } finally {
        if (generation == loadGeneration.get()) _isLoading.value = false
      }
    }
  }

  private fun publishVideos(videos: List<Video>) {
    val previous = _videosWithPlaybackInfo.value.associateBy { it.video.path }
    val now = System.currentTimeMillis()
    _videos.value = videos
    _videosWithPlaybackInfo.value = videos.map { video ->
      previous[video.path]?.copy(video = video) ?: buildVideoWithPlaybackInfo(
        video, null, now, appearancePreferences.unplayedOldVideoDays.get(), browserPreferences.watchedThreshold.get(),
      ).copy(isOldAndUnplayed = false)
    }
    if (videos.isNotEmpty()) _isLoading.value = false
  }

  /**
   * Set flag indicating videos were deleted or moved
   */
  fun setVideosWereDeletedOrMoved() {
    _videosWereDeletedOrMoved.value = true
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackByIdentifier = playbackStateRepository.getAllPlaybackStates().associateBy { it.mediaTitle }
    if (_videos.value != videos) return
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val newLabelDays = appearancePreferences.unplayedOldVideoDays.get()
    val now = System.currentTimeMillis()
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
          playbackState = videoPlaybackIdentifiers(video).firstNotNullOfOrNull(playbackByIdentifier::get),
          currentTimeMillis = now,
          newLabelDays = newLabelDays,
          watchedThreshold = watchedThreshold,
        )
      }
    if (_videos.value == videos) _videosWithPlaybackInfo.value = videosWithInfo
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
    if (_videos.value != videos || _videosWithPlaybackInfo.value !== currentItems) return

    _videosWithPlaybackInfo.value =
      currentItems.toMutableList().apply {
        this[index] = updatedItem
      }
  }

  fun setWatched(
    video: Video,
    watched: Boolean,
  ) {
    _videosWithPlaybackInfo.update { videos ->
      videos.map { item ->
        if (item.video.path == video.path) {
          item.copy(
            timeRemaining = if (watched) 0L else (video.duration / 1000L).coerceAtLeast(0L),
            progressPercentage = null,
            isOldAndUnplayed = item.isOldAndUnplayed && !watched,
            isWatched = watched,
          )
        } else {
          item
        }
      }
    }

    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
        PlaybackStateOps.setWatched(video, watched)
      }.onFailure { error ->
        Log.e(tag, "Failed to update watched state for ${video.displayName}", error)
        loadPlaybackInfo(_videos.value)
      }
    }
  }

  private fun triggerMediaScan() {
    if (ZipArchiveMedia.isBrowserPath(bucketId)) return
    try {
      // Trigger a targeted media scan for the specific folder
      val folder = File(bucketId)

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
          ) { path, uri ->
            Log.d(tag, "Media scan completed for: $path -> $uri")
          }

          Log.d(tag, "Triggered media scan for ${filePaths.size} files in: $bucketId")
        } else {
          Log.d(tag, "No video files found in folder: $bucketId")
        }
      } else {
        // Fallback to scanning external storage root
        val externalStorage = android.os.Environment.getExternalStorageDirectory()
        android.media.MediaScannerConnection.scanFile(
          getApplication(),
          arrayOf(externalStorage.absolutePath),
          arrayOf("video/*"),
        ) { path, uri ->
          Log.d(tag, "Media scan completed for: $path -> $uri")
        }
        Log.d(tag, "Triggered media scan for: ${externalStorage.absolutePath}")
      }
    } catch (e: Exception) {
      Log.e(tag, "Failed to trigger media scan", e)
    }
  }

  companion object {
    fun factory(
      application: Application,
      bucketId: String,
      includeAudio: Boolean = false,
    ) = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T =
        VideoListViewModel(application, bucketId, includeAudio) as T
    }
  }
}
