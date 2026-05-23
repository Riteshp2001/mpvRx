package app.gyrolet.mpvrx.exoplayer.settings.screens.decoder

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.round
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences

class DecoderPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private companion object {
        const val TAG = "DecoderPreferencesViewModel"
    }

    private val uiStateInternal = MutableStateFlow(
        DecoderPreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                uiStateInternal.update { currentState ->
                    currentState.copy(preferences = preferences)
                }
            }
        }
    }

    fun onEvent(event: DecoderPreferencesUiEvent) {
        when (event) {
            is DecoderPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is DecoderPreferencesUiEvent.UpdateDecoderPriority -> updateDecoderPriority(event.value)
            DecoderPreferencesUiEvent.ToggleVideoFilters -> toggleVideoFilters()
            DecoderPreferencesUiEvent.ToggleVideoBrightnessFilter -> toggleVideoBrightnessFilter()
            is DecoderPreferencesUiEvent.UpdateVideoBrightness -> updateVideoBrightness(event.value)
            DecoderPreferencesUiEvent.ToggleVideoContrastFilter -> toggleVideoContrastFilter()
            is DecoderPreferencesUiEvent.UpdateVideoContrast -> updateVideoContrast(event.value)
            DecoderPreferencesUiEvent.ToggleVideoSaturationFilter -> toggleVideoSaturationFilter()
            is DecoderPreferencesUiEvent.UpdateVideoSaturation -> updateVideoSaturation(event.value)
            DecoderPreferencesUiEvent.ToggleVideoHueFilter -> toggleVideoHueFilter()
            is DecoderPreferencesUiEvent.UpdateVideoHue -> updateVideoHue(event.value)
            DecoderPreferencesUiEvent.ToggleVideoGammaFilter -> toggleVideoGammaFilter()
            is DecoderPreferencesUiEvent.UpdateVideoGamma -> updateVideoGamma(event.value)
            DecoderPreferencesUiEvent.ToggleVideoSharpeningFilter -> toggleVideoSharpeningFilter()
            is DecoderPreferencesUiEvent.UpdateVideoSharpening -> updateVideoSharpening(event.value)
        }
    }

    private fun showDialog(value: DecoderPreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateDecoderPriority(value: DecoderPriority) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(decoderPriority = value)
            }
        }
    }

    private fun toggleVideoFilters() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldApplyVideoFilters = !it.shouldApplyVideoFilters)
            }
        }
    }

    private fun toggleVideoBrightnessFilter() {
        updateVideoFilter("brightness enabled toggled") { preferences ->
            preferences.copy(isVideoBrightnessFilterEnabled = !preferences.isVideoBrightnessFilterEnabled)
        }
    }

    private fun toggleVideoContrastFilter() {
        updateVideoFilter("contrast enabled toggled") { preferences ->
            preferences.copy(isVideoContrastFilterEnabled = !preferences.isVideoContrastFilterEnabled)
        }
    }

    private fun toggleVideoSaturationFilter() {
        updateVideoFilter("saturation enabled toggled") { preferences ->
            preferences.copy(isVideoSaturationFilterEnabled = !preferences.isVideoSaturationFilterEnabled)
        }
    }

    private fun toggleVideoHueFilter() {
        updateVideoFilter("hue enabled toggled") { preferences ->
            preferences.copy(isVideoHueFilterEnabled = !preferences.isVideoHueFilterEnabled)
        }
    }

    private fun toggleVideoGammaFilter() {
        updateVideoFilter("gamma enabled toggled") { preferences ->
            preferences.copy(isVideoGammaFilterEnabled = !preferences.isVideoGammaFilterEnabled)
        }
    }

    private fun toggleVideoSharpeningFilter() {
        updateVideoFilter("sharpening enabled toggled") { preferences ->
            preferences.copy(isVideoSharpeningFilterEnabled = !preferences.isVideoSharpeningFilterEnabled)
        }
    }

    private fun updateVideoBrightness(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.MIN_VIDEO_BRIGHTNESS, PlayerPreferences.MAX_VIDEO_BRIGHTNESS).round(2)
        updateVideoFilter("brightness=$normalizedValue") { it.copy(videoBrightness = normalizedValue) }
    }

    private fun updateVideoContrast(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.MIN_VIDEO_CONTRAST, PlayerPreferences.MAX_VIDEO_CONTRAST).round(2)
        updateVideoFilter("contrast=$normalizedValue") { it.copy(videoContrast = normalizedValue) }
    }

    private fun updateVideoSaturation(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.MIN_VIDEO_SATURATION, PlayerPreferences.MAX_VIDEO_SATURATION).round(0)
        updateVideoFilter("saturation=$normalizedValue") { it.copy(videoSaturation = normalizedValue) }
    }

    private fun updateVideoHue(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.MIN_VIDEO_HUE, PlayerPreferences.MAX_VIDEO_HUE).round(0)
        updateVideoFilter("hue=$normalizedValue") { it.copy(videoHue = normalizedValue) }
    }

    private fun updateVideoGamma(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.MIN_VIDEO_GAMMA, PlayerPreferences.MAX_VIDEO_GAMMA).round(2)
        updateVideoFilter("gamma=$normalizedValue") { it.copy(videoGamma = normalizedValue) }
    }

    private fun updateVideoSharpening(value: Float) {
        val normalizedValue = value.coerceIn(PlayerPreferences.DEFAULT_VIDEO_SHARPENING, PlayerPreferences.MAX_VIDEO_SHARPENING).round(2)
        updateVideoFilter("sharpening=$normalizedValue") { it.copy(videoSharpening = normalizedValue) }
    }

    private fun updateVideoFilter(
        debugValue: String,
        transform: (PlayerPreferences) -> PlayerPreferences,
    ) {
        Logger.debug(TAG, "Update video filter from settings: $debugValue")
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences(transform)
        }
    }
}

@Stable
data class DecoderPreferencesUiState(
    val showDialog: DecoderPreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface DecoderPreferenceDialog {
    data object DecoderPriorityDialog : DecoderPreferenceDialog
}

sealed interface DecoderPreferencesUiEvent {
    data class ShowDialog(val value: DecoderPreferenceDialog?) : DecoderPreferencesUiEvent
    data class UpdateDecoderPriority(val value: DecoderPriority) : DecoderPreferencesUiEvent
    data object ToggleVideoFilters : DecoderPreferencesUiEvent
    data object ToggleVideoBrightnessFilter : DecoderPreferencesUiEvent
    data class UpdateVideoBrightness(val value: Float) : DecoderPreferencesUiEvent
    data object ToggleVideoContrastFilter : DecoderPreferencesUiEvent
    data class UpdateVideoContrast(val value: Float) : DecoderPreferencesUiEvent
    data object ToggleVideoSaturationFilter : DecoderPreferencesUiEvent
    data class UpdateVideoSaturation(val value: Float) : DecoderPreferencesUiEvent
    data object ToggleVideoHueFilter : DecoderPreferencesUiEvent
    data class UpdateVideoHue(val value: Float) : DecoderPreferencesUiEvent
    data object ToggleVideoGammaFilter : DecoderPreferencesUiEvent
    data class UpdateVideoGamma(val value: Float) : DecoderPreferencesUiEvent
    data object ToggleVideoSharpeningFilter : DecoderPreferencesUiEvent
    data class UpdateVideoSharpening(val value: Float) : DecoderPreferencesUiEvent
}
