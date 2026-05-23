package app.gyrolet.mpvrx.exoplayer.settings.screens.gesture

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.round
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.DoubleTapGesture
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences

class GesturePreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        GesturePreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                uiStateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    fun onEvent(event: GesturePreferencesUiEvent) {
        when (event) {
            is GesturePreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is GesturePreferencesUiEvent.UpdateDoubleTapGesture -> updateDoubleTapGesture(event.gesture)
            GesturePreferencesUiEvent.ToggleUseLongPressControls -> toggleUseLongPressControls()
            GesturePreferencesUiEvent.ToggleUseLongPressVariableSpeed -> toggleUseLongPressVariableSpeed()
            GesturePreferencesUiEvent.ToggleDoubleTapGesture -> toggleDoubleTapGesture()
            GesturePreferencesUiEvent.ToggleEnableBrightnessSwipeGesture -> toggleEnableBrightnessSwipeGesture()
            GesturePreferencesUiEvent.ToggleEnableVolumeSwipeGesture -> toggleEnableVolumeSwipeGesture()
            GesturePreferencesUiEvent.ToggleUseSeekControls -> toggleUseSeekControls()
            GesturePreferencesUiEvent.ToggleUseZoomControls -> toggleUseZoomControls()
            GesturePreferencesUiEvent.ToggleEnablePanGesture -> toggleEnablePanGesture()
            is GesturePreferencesUiEvent.UpdateLongPressControlsSpeed -> updateLongPressControlsSpeed(event.value)
            is GesturePreferencesUiEvent.UpdateSeekIncrement -> updateSeekIncrement(event.value)
            is GesturePreferencesUiEvent.UpdateSeekSensitivity -> updateSeekSensitivity(event.value)
            is GesturePreferencesUiEvent.UpdateVolumeGestureSensitivity -> updateVolumeGestureSensitivity(event.value)
            is GesturePreferencesUiEvent.UpdateBrightnessGestureSensitivity -> updateBrightnessGestureSensitivity(event.value)
        }
    }

    private fun showDialog(value: GesturePreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateDoubleTapGesture(gesture: DoubleTapGesture) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(doubleTapGesture = gesture)
            }
        }
    }

    private fun toggleUseLongPressControls() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                val shouldUseLongPressControls = !it.shouldUseLongPressControls
                it.copy(
                    shouldUseLongPressControls = shouldUseLongPressControls,
                    shouldUseLongPressVariableSpeed = it.shouldUseLongPressVariableSpeed && shouldUseLongPressControls,
                )
            }
        }
    }

    private fun toggleUseLongPressVariableSpeed() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                if (!it.shouldUseLongPressControls) {
                    return@updatePlayerPreferences it.copy(shouldUseLongPressVariableSpeed = false)
                }
                it.copy(shouldUseLongPressVariableSpeed = !it.shouldUseLongPressVariableSpeed)
            }
        }
    }

    private fun toggleDoubleTapGesture() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(
                    doubleTapGesture = if (it.doubleTapGesture == DoubleTapGesture.NONE) {
                        DoubleTapGesture.FAST_FORWARD_AND_REWIND
                    } else {
                        DoubleTapGesture.NONE
                    },
                )
            }
        }
    }

    private fun toggleEnableBrightnessSwipeGesture() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isBrightnessSwipeGestureEnabled = !it.isBrightnessSwipeGestureEnabled)
            }
        }
    }

    private fun toggleEnableVolumeSwipeGesture() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isVolumeSwipeGestureEnabled = !it.isVolumeSwipeGestureEnabled)
            }
        }
    }

    private fun toggleUseSeekControls() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldUseSeekControls = !it.shouldUseSeekControls)
            }
        }
    }

    private fun toggleUseZoomControls() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldUseZoomControls = !it.shouldUseZoomControls)
            }
        }
    }

    private fun toggleEnablePanGesture() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isPanGestureEnabled = !it.isPanGestureEnabled)
            }
        }
    }

    private fun updateLongPressControlsSpeed(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(longPressControlsSpeed = value) }
        }
    }

    private fun updateSeekIncrement(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(seekIncrement = value.coerceIn(1, PlayerPreferences.MAX_SEEK_INCREMENT))
            }
        }
    }

    private fun updateSeekSensitivity(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(seekSensitivity = value.round(2))
            }
        }
    }

    private fun updateVolumeGestureSensitivity(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(volumeGestureSensitivity = value.round(2))
            }
        }
    }

    private fun updateBrightnessGestureSensitivity(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(brightnessGestureSensitivity = value.round(2))
            }
        }
    }
}

@Stable
data class GesturePreferencesUiState(
    val showDialog: GesturePreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface GesturePreferenceDialog {
    data object DoubleTapDialog : GesturePreferenceDialog
    data object LongPressControlsSpeedDialog : GesturePreferenceDialog
}

sealed interface GesturePreferencesUiEvent {
    data class ShowDialog(val value: GesturePreferenceDialog?) : GesturePreferencesUiEvent
    data class UpdateDoubleTapGesture(val gesture: DoubleTapGesture) : GesturePreferencesUiEvent
    data object ToggleUseLongPressControls : GesturePreferencesUiEvent
    data object ToggleUseLongPressVariableSpeed : GesturePreferencesUiEvent
    data object ToggleDoubleTapGesture : GesturePreferencesUiEvent
    data object ToggleEnableBrightnessSwipeGesture : GesturePreferencesUiEvent
    data object ToggleEnableVolumeSwipeGesture : GesturePreferencesUiEvent
    data object ToggleUseSeekControls : GesturePreferencesUiEvent
    data object ToggleUseZoomControls : GesturePreferencesUiEvent
    data object ToggleEnablePanGesture : GesturePreferencesUiEvent
    data class UpdateLongPressControlsSpeed(val value: Float) : GesturePreferencesUiEvent
    data class UpdateSeekIncrement(val value: Int) : GesturePreferencesUiEvent
    data class UpdateSeekSensitivity(val value: Float) : GesturePreferencesUiEvent
    data class UpdateVolumeGestureSensitivity(val value: Float) : GesturePreferencesUiEvent
    data class UpdateBrightnessGestureSensitivity(val value: Float) : GesturePreferencesUiEvent
}
