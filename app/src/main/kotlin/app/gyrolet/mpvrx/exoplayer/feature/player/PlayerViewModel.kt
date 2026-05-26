package app.gyrolet.mpvrx.exoplayer.feature.player

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.round
import app.gyrolet.mpvrx.exoplayer.core.data.repository.ExternalSubtitleFontSource
import app.gyrolet.mpvrx.exoplayer.core.data.repository.MediaRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.SubtitleFontRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.buildRemotePlaybackStateKey
import app.gyrolet.mpvrx.exoplayer.core.domain.GetSortedPlaylistUseCase
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.LastPlayerScreenOrientation
import app.gyrolet.mpvrx.exoplayer.core.model.LoopMode
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControl
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsLayout
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.Video
import app.gyrolet.mpvrx.exoplayer.core.model.VideoContentScale
import app.gyrolet.mpvrx.exoplayer.core.model.withSubtitleStyleFrom
import app.gyrolet.mpvrx.exoplayer.core.model.withVideoFiltersFrom
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteFilePath
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteProtocol
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.remoteServerId
import app.gyrolet.mpvrx.exoplayer.feature.player.state.SubtitleOptionsEvent
import app.gyrolet.mpvrx.exoplayer.feature.player.state.VideoZoomEvent

private fun Float.normalizeVideoFilter(
    minimumValue: Float,
    maximumValue: Float,
    decimals: Int = 2,
): Float = coerceIn(minimumValue, maximumValue).round(decimals)

internal fun normalizeVideoSharpening(value: Float): Float = value
    .normalizeVideoFilter(PlayerPreferences.DEFAULT_VIDEO_SHARPENING, PlayerPreferences.MAX_VIDEO_SHARPENING)

class PlayerViewModel(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val subtitleFontRepository: SubtitleFontRepository,
    private val getSortedPlaylistUseCase: GetSortedPlaylistUseCase,
) : ViewModel() {

    private companion object {
        const val TAG = "PlayerViewModel"
    }

    var shouldPlayWhenReady: Boolean = true

    private val internalUiState = MutableStateFlow(
        PlayerUiState(
            playerPreferences = preferencesRepository.playerPreferences.value,
            applicationPreferences = preferencesRepository.applicationPreferences.value,
            shouldPreventScreenshots = preferencesRepository.applicationPreferences.value.shouldPreventScreenshots,
            shouldHideInRecents = preferencesRepository.applicationPreferences.value.shouldHideInRecents,
        ),
    )
    val uiState = internalUiState.asStateFlow()

    init {
        android.util.Log.d("PlayerViewModel", "init starting")
        viewModelScope.launch {
            try {
                preferencesRepository.playerPreferences.collect { prefs ->
                    internalUiState.update { it.copy(playerPreferences = prefs) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Failed to collect player preferences", e)
            }
        }
        viewModelScope.launch {
            try {
                preferencesRepository.applicationPreferences.collect { prefs ->
                    internalUiState.update {
                        it.copy(
                            applicationPreferences = prefs,
                            shouldPreventScreenshots = prefs.shouldPreventScreenshots,
                            shouldHideInRecents = prefs.shouldHideInRecents,
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Failed to collect app preferences", e)
            }
        }
        viewModelScope.launch {
            try {
                subtitleFontRepository.source.collect { source ->
                    internalUiState.update { it.copy(externalSubtitleFontSource = source) }
                }
            } catch (e: Exception) {
                android.util.Log.e("PlayerViewModel", "Failed to collect font source", e)
            }
        }
        android.util.Log.d("PlayerViewModel", "init finished")
    }

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> = getSortedPlaylistUseCase.invoke(uri)

    suspend fun getVideoByUri(uri: String): Video? = mediaRepository.getVideoByUri(uri)

    fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    fun updatePlayerVolume(percentage: Int) {
        viewModelScope.launch {
            val clampedPercentage = percentage.coerceIn(
                minimumValue = 0,
                maximumValue = PlayerPreferences.MAX_PLAYER_VOLUME_PERCENTAGE,
            )
            Logger.debug(TAG, "Remember player volume: percentage=$clampedPercentage")
            preferencesRepository.updatePlayerPreferences {
                it.copy(playerVolumePercentage = clampedPercentage)
            }
        }
    }

    fun updateLastPlayerScreenOrientation(value: LastPlayerScreenOrientation) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { preferences ->
                if (!preferences.shouldRememberPlayerScreenOrientation) return@updatePlayerPreferences preferences
                preferences.copy(lastPlayerScreenOrientation = value)
            }
        }
    }

    fun updateVideoContentScale(contentScale: VideoContentScale) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerVideoZoom = contentScale) }
        }
    }

    fun updateDecoderPriority(decoderPriority: DecoderPriority) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(decoderPriority = decoderPriority)
            }
        }
    }

    fun updateStatisticsPage(page: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(statisticsPage = page)
            }
        }
    }

    fun toggleVideoFilters() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldApplyVideoFilters = !it.shouldApplyVideoFilters)
            }
        }
    }

    fun updateAmbienceMode(isEnabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isAmbienceModeEnabled = isEnabled)
            }
        }
    }

    fun updateVideoBrightness(value: Float) {
        val normalizedValue = value.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_BRIGHTNESS, PlayerPreferences.MAX_VIDEO_BRIGHTNESS)
        updateVideoFilter("brightness=$normalizedValue") { it.copy(videoBrightness = normalizedValue) }
    }

    fun updateVideoFilters(preferences: PlayerPreferences) {
        val normalizedPreferences = preferences.normalizedVideoFilters()
        updateVideoFilter("confirmed=$normalizedPreferences") {
            it.withVideoFiltersFrom(normalizedPreferences)
        }
    }

    fun updateVideoContrast(value: Float) {
        val normalizedValue = value.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_CONTRAST, PlayerPreferences.MAX_VIDEO_CONTRAST)
        updateVideoFilter("contrast=$normalizedValue") { it.copy(videoContrast = normalizedValue) }
    }

    fun updateVideoSaturation(value: Float) {
        val normalizedValue = value.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_SATURATION, PlayerPreferences.MAX_VIDEO_SATURATION, decimals = 0)
        updateVideoFilter("saturation=$normalizedValue") { it.copy(videoSaturation = normalizedValue) }
    }

    fun updateVideoHue(value: Float) {
        val normalizedValue = value.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_HUE, PlayerPreferences.MAX_VIDEO_HUE, decimals = 0)
        updateVideoFilter("hue=$normalizedValue") { it.copy(videoHue = normalizedValue) }
    }

    fun updateVideoGamma(value: Float) {
        val normalizedValue = value.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_GAMMA, PlayerPreferences.MAX_VIDEO_GAMMA)
        updateVideoFilter("gamma=$normalizedValue") { it.copy(videoGamma = normalizedValue) }
    }

    fun updateVideoSharpening(value: Float) {
        val normalizedValue = normalizeVideoSharpening(value)
        updateVideoFilter("sharpening=$normalizedValue") { it.copy(videoSharpening = normalizedValue) }
    }

    private fun PlayerPreferences.normalizedVideoFilters(): PlayerPreferences = copy(
        videoBrightness = videoBrightness.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_BRIGHTNESS, PlayerPreferences.MAX_VIDEO_BRIGHTNESS),
        videoContrast = videoContrast.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_CONTRAST, PlayerPreferences.MAX_VIDEO_CONTRAST),
        videoSaturation = videoSaturation.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_SATURATION, PlayerPreferences.MAX_VIDEO_SATURATION, decimals = 0),
        videoHue = videoHue.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_HUE, PlayerPreferences.MAX_VIDEO_HUE, decimals = 0),
        videoGamma = videoGamma.normalizeVideoFilter(PlayerPreferences.MIN_VIDEO_GAMMA, PlayerPreferences.MAX_VIDEO_GAMMA),
        videoSharpening = normalizeVideoSharpening(videoSharpening),
    )

    private fun updateVideoFilter(
        debugValue: String,
        transform: (PlayerPreferences) -> PlayerPreferences,
    ) {
        Logger.debug(TAG, "Update video filter from player: $debugValue")
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences(transform)
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(loopMode = loopMode) }
        }
    }

    fun updateSubtitleStyle(preferences: PlayerPreferences) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.withSubtitleStyleFrom(preferences)
            }
        }
    }

    fun updatePlayerControlsCustomization(
        hiddenControls: Set<PlayerControl>,
        layout: PlayerControlsLayout,
    ) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(
                    hiddenPlayerControls = hiddenControls,
                    playerControlsLayout = layout,
                )
            }
        }
    }

    fun onVideoZoomEvent(event: VideoZoomEvent) {
        when (event) {
            is VideoZoomEvent.ContentScaleChanged -> {
                updateVideoContentScale(event.contentScale)
            }
            is VideoZoomEvent.ZoomChanged -> {
                updateVideoZoom(event.mediaItem.resolvePlaybackStateUri(), event.zoom)
            }
        }
    }

    fun onSubtitleOptionEvent(event: SubtitleOptionsEvent) {
        when (event) {
            is SubtitleOptionsEvent.DelayChanged -> {
                updateSubtitleDelay(event.mediaItem.resolvePlaybackStateUri(), event.delay)
            }
            is SubtitleOptionsEvent.SpeedChanged -> {
                updateSubtitleSpeed(event.mediaItem.resolvePlaybackStateUri(), event.speed)
            }
        }
    }

    private fun updateSubtitleDelay(uri: String, delay: Long) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleDelay(uri, delay)
        }
    }

    private fun updateSubtitleSpeed(uri: String, speed: Float) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleSpeed(uri, speed)
        }
    }

    private fun MediaItem.resolvePlaybackStateUri(): String = buildRemotePlaybackStateKey(
        remoteProtocol = mediaMetadata.remoteProtocol,
        remoteServerId = mediaMetadata.remoteServerId,
        remoteFilePath = mediaMetadata.remoteFilePath,
    ) ?: mediaId
}

@Stable
data class PlayerUiState(
    val playerPreferences: PlayerPreferences? = null,
    val applicationPreferences: ApplicationPreferences = ApplicationPreferences(),
    val shouldPreventScreenshots: Boolean = false,
    val shouldHideInRecents: Boolean = false,
    val externalSubtitleFontSource: ExternalSubtitleFontSource? = null,
)

sealed interface PlayerEvent
