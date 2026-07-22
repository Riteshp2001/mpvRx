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
import app.gyrolet.mpvrx.utils.media.MetadataRetrieval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import androidx.compose.runtime.Immutable

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

  private val tag = "MediaLibraryViewModel"

  init {
    loadData()
  }

  private fun loadData() {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        _isLoading.value = true
        var videoList = MediaFileRepository.getAllVideos(
          context = getApplication(),
          includeAudioOverride = true,
        )

        if (MetadataRetrieval.isVideoMetadataNeeded(browserPreferences)) {
          videoList = MetadataRetrieval.enrichVideosIfNeeded(
            context = getApplication(),
            videos = videoList,
            browserPreferences = browserPreferences,
            metadataCache = metadataCache
          )
        }

        _videos.value = videoList
        loadPlaybackInfo(videoList)
      } catch (e: Exception) {
        Log.e(tag, "Error loading media library videos", e)
      } finally {
        _isLoading.value = false
      }
    }
  }

  override fun refresh() {
    loadData()
  }

  private suspend fun loadPlaybackInfo(videos: List<Video>) {
    val playbackStates = playbackStateRepository.getAllPlaybackStates()
    val currentTime = System.currentTimeMillis()
    val thresholdDays = appearancePreferences.unplayedOldVideoDays.get()
    val thresholdMillis = thresholdDays * 24 * 60 * 60 * 1000L
    val watchedThreshold = browserPreferences.watchedThreshold.get()
    val playbackByTitle = playbackStates.associateBy { it.mediaTitle }

    val videosWithInfo =
      videos.map { video ->
        val playbackState = playbackByTitle[video.displayName]

        val progress = if (playbackState != null && video.duration > 0) {
          val durationSeconds = video.duration / 1000
          val watched = durationSeconds - playbackState.timeRemaining.toLong()
          val progressValue = (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
          if (progressValue in 0.01f..0.99f) progressValue else null
        } else {
          null
        }

        val videoAge = currentTime - (video.dateModified * 1000)
        val isOldAndUnplayed = playbackState == null && videoAge <= thresholdMillis

        val isWatched = if (playbackState != null && video.duration > 0) {
           val durationSeconds = video.duration / 1000
           val watched = durationSeconds - playbackState.timeRemaining.toLong()
           val progressValue = (watched.toFloat() / durationSeconds.toFloat()).coerceIn(0f, 1f)
           val calculatedWatched = progressValue >= (watchedThreshold / 100f)
           playbackState.hasBeenWatched || calculatedWatched
        } else {
           false
        }

        VideoWithPlaybackInfo(
          video = video,
          timeRemaining = playbackState?.timeRemaining?.toLong(),
          progressPercentage = progress,
          isOldAndUnplayed = isOldAndUnplayed,
          isWatched = isWatched,
        )
      }
    _videosWithPlaybackInfo.value = videosWithInfo
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return MediaLibraryViewModel(application) as T
        }
      }
  }
}
