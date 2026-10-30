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
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.media.VisibleDirectoryWatcher
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import app.gyrolet.mpvrx.utils.media.PlaybackStateEvents
import app.gyrolet.mpvrx.utils.media.PlaybackStateOps
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

private const val SCOPED_PROBE_BATCH = 6

@Immutable
data class VideoWithPlaybackInfo(
  val video: Video,
  val timeRemaining: Long? = null, // in seconds
  val progressPercentage: Float? = null, // 0.0 to 1.0
  val isOldAndUnplayed: Boolean = false, // true while the NEW badge is eligible
  val isWatched: Boolean = false, // true once the configured watched threshold is reached
)

// Match PlayerActivity: MediaStore items with a real path use local identity; SAF documents
// retain their URI identity. Legacy alternatives remain readable after earlier queue launches.
internal fun videoPlaybackIdentifiers(video: Video): Set<String> =
  if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && video.uri.scheme == "content" &&
    (video.uri.authority != android.provider.MediaStore.AUTHORITY || !video.path.startsWith('/'))
  ) {
    linkedSetOf(
      PlaybackIdentity.forUri(video.uri.toString()),
      PlaybackIdentity.forUri(video.path),
      PlaybackIdentity.forLocalPath(video.path),
    )
  } else {
    linkedSetOf(
      PlaybackIdentity.forLocalPath(video.path),
      PlaybackIdentity.forUri(video.uri.toString()),
      PlaybackIdentity.forUri(video.path),
      PlaybackIdentity.forUri("file://${video.path}"),
    )
  }

internal fun buildVideoWithPlaybackInfo(
  video: Video,
  playbackState: PlaybackStateEntity?,
  currentTimeMillis: Long,
  newLabelDays: Int,
  watchedThreshold: Int,
): VideoWithPlaybackInfo {
  // MediaStore cannot see .nomedia files, and a SAF provider may not report their
  // duration. The player can still supply total seconds through saved position + remaining.
  val recordedDuration =
    if (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY && video.uri.scheme == "content") {
      playbackState?.let { (it.lastPosition.toLong() + it.timeRemaining.toLong()).takeIf { total -> total > 0L } }
    } else null
  val durationSeconds = (video.duration / 1000L).takeIf { it > 0L } ?: recordedDuration ?: 0L
  // Until the background probe finishes, the duration the player recorded is the best label.
  val displayVideo =
    if (video.duration <= 0L && durationSeconds > 0L) {
      video.copy(
        duration = durationSeconds * 1000L,
        durationFormatted = VideoScanUtils.formatDuration(durationSeconds * 1000L),
      )
    } else {
      video
    }
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
    video = displayVideo,
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
  private var playbackIndexByIdentifier: Map<String, Int> = emptyMap()

  private val tag = "VideoListViewModel"
  private var scopedMetadataJob: Job? = null
  @Volatile private var loadVideosJob: Job? = null
  private val liveFolderWatcher = VisibleDirectoryWatcher { MediaLibraryEvents.notifyChanged() }

  fun startWatching() {
    if (!ZipArchiveMedia.isBrowserPath(bucketId) && !ScopedHiddenFolderScanner.isScopedFolder(bucketId)) {
      liveFolderWatcher.watch(bucketId)
    }
  }

  fun stopWatching() = liveFolderWatcher.stop()

  override fun onCleared() {
    liveFolderWatcher.stop()
    super.onCleared()
  }

  private val cacheKey = "$bucketId|audio=$includeAudio"

  init {
    // Show the last known contents instantly; loadVideos() revalidates in the background.
    viewModelScope.launch(Dispatchers.IO) {
      val cached = VideoListCache.load(getApplication(), cacheKey)
      if (!cached.isNullOrEmpty() && _videos.value.isEmpty()) {
        previousVideoCount = cached.size
        _videos.value = cached
        loadPlaybackInfo(cached)
        _isLoading.value = false
      }
      loadVideos()
    }

    // Listen for global media library changes and refresh this list when they occur
    viewModelScope.launch(Dispatchers.IO) {
      MediaLibraryEvents.changes.collectLatest {
        // A newly copied file may not be indexed in MediaStore yet.
        loadVideos(forceFileSystemCheck = true)
      }
    }

    // Playback persistence emits this event whenever a position/watched state is saved. Re-read
    // the affected playback metadata so NEW/progress/watched UI updates without a hard refresh.
    viewModelScope.launch(Dispatchers.IO) {
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
    _isLoading.value = true

    // Clear cache to force fresh data from filesystem
    MediaFileRepository.clearCache()

    // Trigger media scan before loading to ensure MediaStore is up-to-date
    if (!ZipArchiveMedia.isBrowserPath(bucketId) && !ScopedHiddenFolderScanner.isScopedFolder(bucketId)) triggerMediaScan()

    loadVideos(forceFileSystemCheck = true)
  }

  private fun loadVideos(forceFileSystemCheck: Boolean = false) {
    // Provider probing must never compete with listing; it restarts once the new list is shown.
    scopedMetadataJob?.cancel()
    // A newer request supersedes an unfinished one: bursts of media events used to stack full
    // MediaStore and filesystem passes for the same folder.
    loadVideosJob?.cancel()
    loadVideosJob = viewModelScope.launch(Dispatchers.IO) {
      try {
        // First attempt to load videos (basic info from MediaStore)
        var videoList =
          MediaFileRepository.getVideosInFolder(
            getApplication(),
            bucketId,
            forceFileSystemCheck = forceFileSystemCheck,
            includeAudioOverride = if (includeAudio) true else null,
          )
        if (includeAudio) {
          videoList = videoList.filter { it.isAudio }
        }
        val scopedFolder = ScopedHiddenFolderScanner.isScopedFolder(bucketId)
        // Play Store and indexed hidden folders are shown as listed, then probed once the list is visible.
        val deferredMetadata = scopedFolder || MediaFileRepository.isIndexedHiddenFolder(bucketId)
        if (deferredMetadata) {
          videoList = withKnownScopedMetadata(videoList, _videos.value)
        }
        if (scopedFolder) {
          videoList = MetadataRetrieval.restoreCachedContentMetadata(videoList, metadataCache)
        }

        // ZIP entries need basic metadata even when optional metadata chips are disabled.
        val archiveFolder = ZipArchiveMedia.isBrowserPath(bucketId)
        if (!deferredMetadata &&
          (archiveFolder || MetadataRetrieval.isVideoMetadataNeeded(browserPreferences))) {
          Log.d(tag, "Enriching ${videoList.size} videos with metadata")
          videoList =
            MetadataRetrieval.enrichVideosIfNeeded(
              context = getApplication(),
              videos = videoList,
              browserPreferences = browserPreferences,
              metadataCache = metadataCache,
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

        if (videoList.isEmpty() && !archiveFolder && !ScopedHiddenFolderScanner.isScopedFolder(bucketId)) {
          Log.d(tag, "No videos found for bucket $bucketId - attempting media rescan")
          triggerMediaScan()
          delay(1000)
          var retryVideoList =
            MediaFileRepository.getVideosInFolder(
              getApplication(),
              bucketId,
              forceFileSystemCheck = true,
              includeAudioOverride = if (includeAudio) true else null,
            )
          if (includeAudio) {
            retryVideoList = retryVideoList.filter { it.isAudio }
          }

          // Enrich retry list if needed
          if (!deferredMetadata && MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
            retryVideoList =
              MetadataRetrieval.enrichVideosIfNeeded(
                context = getApplication(),
                videos = retryVideoList,
                browserPreferences = browserPreferences,
                metadataCache = metadataCache,
              )
          }

          // Update count after retry
          if (previousVideoCount > 0 && retryVideoList.isEmpty()) {
            _videosWereDeletedOrMoved.value = true
          } else if (retryVideoList.isNotEmpty()) {
            _videosWereDeletedOrMoved.value = false
          }
          previousVideoCount = retryVideoList.size

          _videos.value = retryVideoList
          loadPlaybackInfo(retryVideoList)
          VideoListCache.save(getApplication(), cacheKey, retryVideoList)
          if (deferredMetadata) probeDeferredMetadataInBackground(retryVideoList)
        } else {
          if (_videos.value != videoList) {
            _videos.value = videoList
            loadPlaybackInfo(videoList)
          }
          VideoListCache.save(getApplication(), cacheKey, videoList)
          if (deferredMetadata) probeDeferredMetadataInBackground(videoList)
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        // Superseded by a newer load (or the screen closed): keep the visible list.
        throw e
      } catch (e: Exception) {
        Log.e(tag, "Error loading videos for bucket $bucketId", e)
        _videos.value = emptyList()
        _videosWithPlaybackInfo.value = emptyList()
      } finally {
        // A superseded load leaves the flag to the load that replaced it.
        if (isActive) _isLoading.value = false
      }
    }
  }

  /**
   * Set flag indicating videos were deleted or moved
   */
  fun setVideosWereDeletedOrMoved() {
    _videosWereDeletedOrMoved.value = true
  }

  /** Keeps metadata already probed for unchanged SAF documents, so a rescan does not blank durations. */
  private fun withKnownScopedMetadata(fresh: List<Video>, known: List<Video>): List<Video> {
    val knownByPath = known.filter { it.duration > 0L }.associateBy { it.path }
    if (knownByPath.isEmpty()) return fresh
    return fresh.map { video ->
      val previous = knownByPath[video.path]
      if (video.duration <= 0L && previous != null && isSameDocumentVersion(previous, video)) {
        video.copy(
          duration = previous.duration,
          durationFormatted = VideoScanUtils.formatDuration(previous.duration),
          width = previous.width,
          height = previous.height,
          fps = previous.fps,
          resolution = previous.resolution,
          hasEmbeddedSubtitles = previous.hasEmbeddedSubtitles,
          subtitleCodec = previous.subtitleCodec,
          videoCodec = previous.videoCodec,
          videoCodecMimeType = previous.videoCodecMimeType,
        )
      } else {
        video
      }
    }
  }

  private fun isSameDocumentVersion(first: Video, second: Video): Boolean =
    first.size == second.size && first.dateModified == second.dateModified

  /**
   * Play Store documents and standard hidden files carry no metadata in the listing, so each file is
   * probed here. This runs only after the list is already visible, one file at a time, publishing in
   * small batches.
   */
  private fun probeDeferredMetadataInBackground(videos: List<Video>) {
    scopedMetadataJob?.cancel()
    if (!MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
      return
    }
    val pending = videos.filter { MetadataRetrieval.needsVideoMetadata(it, browserPreferences) }
    if (pending.isEmpty()) return
    scopedMetadataJob =
      viewModelScope.launch(Dispatchers.IO) {
        val probed = HashMap<String, Video>()
        pending.forEachIndexed { index, video ->
          ensureActive()
          val includeCodec =
            browserPreferences.showCodecSupportIndicator.get() && !video.isAudio && video.videoCodec.isBlank()
          val metadata =
            if (video.uri.scheme == "content") {
              metadataCache.getOrExtractContentMetadata(video, includeCodec)
            } else {
              metadataCache.getOrExtractMetadata(File(video.path), video.uri, video.displayName, includeCodec)
            }
          metadata?.let { probed[video.path] = MetadataRetrieval.withMetadata(video, it) }
          if (probed.isNotEmpty() && ((index + 1) % SCOPED_PROBE_BATCH == 0 || index == pending.lastIndex)) {
            val current = _videos.value
            val updated =
              current.map { item ->
                probed[item.path]?.takeIf { isSameDocumentVersion(it, item) } ?: item
              }
            probed.clear()
            if (updated != current) {
              _videos.value = updated
              loadPlaybackInfo(updated)
            }
          }
        }
        VideoListCache.save(getApplication(), cacheKey, _videos.value)
      }
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackByIdentifier = playbackStateRepository.getAllPlaybackStates().associateBy { it.mediaTitle }
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
    if (ZipArchiveMedia.isBrowserPath(bucketId) || ScopedHiddenFolderScanner.isScopedFolder(bucketId)) return
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
