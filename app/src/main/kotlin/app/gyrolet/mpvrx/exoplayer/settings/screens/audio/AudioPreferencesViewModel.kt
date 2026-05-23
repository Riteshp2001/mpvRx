package app.gyrolet.mpvrx.exoplayer.settings.screens.audio

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences

class AudioPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        AudioPreferencesUiState(
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

    fun onEvent(event: AudioPreferencesUiEvent) {
        when (event) {
            is AudioPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is AudioPreferencesUiEvent.UpdateAudioLanguage -> updateAudioLanguage(event.value)
            is AudioPreferencesUiEvent.UpdateMaxInitialPlayerVolume -> updateMaxInitialPlayerVolume(event.value)
            AudioPreferencesUiEvent.TogglePauseOnHeadsetDisconnect -> togglePauseOnHeadsetDisconnect()
            AudioPreferencesUiEvent.ToggleShowSystemVolumePanel -> toggleShowSystemVolumePanel()
            AudioPreferencesUiEvent.ToggleRequireAudioFocus -> toggleRequireAudioFocus()
            AudioPreferencesUiEvent.ToggleRememberPlayerVolume -> toggleRememberPlayerVolume()
            AudioPreferencesUiEvent.ToggleVolumeNormalization -> toggleVolumeNormalization()
            AudioPreferencesUiEvent.ToggleVolumeBoost -> toggleVolumeBoost()
        }
    }

    private fun showDialog(value: AudioPreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateAudioLanguage(value: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(preferredAudioLanguage = value)
            }
        }
    }

    private fun togglePauseOnHeadsetDisconnect() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldPauseOnHeadsetDisconnect = !it.shouldPauseOnHeadsetDisconnect)
            }
        }
    }

    private fun toggleShowSystemVolumePanel() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldShowSystemVolumePanel = !it.shouldShowSystemVolumePanel)
            }
        }
    }

    private fun toggleRequireAudioFocus() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldRequireAudioFocus = !it.shouldRequireAudioFocus)
            }
        }
    }

    private fun toggleRememberPlayerVolume() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldRememberPlayerVolume = !it.shouldRememberPlayerVolume)
            }
        }
    }

    private fun updateMaxInitialPlayerVolume(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(
                    maxInitialPlayerVolumePercentage = value.coerceIn(
                        PlayerPreferences.MIN_INITIAL_PLAYER_VOLUME_PERCENTAGE,
                        PlayerPreferences.MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE,
                    ),
                )
            }
        }
    }

    private fun toggleVolumeNormalization() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isVolumeNormalizationEnabled = !it.isVolumeNormalizationEnabled)
            }
        }
    }

    private fun toggleVolumeBoost() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isVolumeBoostEnabled = !it.isVolumeBoostEnabled)
            }
        }
    }
}

@Stable
data class AudioPreferencesUiState(
    val showDialog: AudioPreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface AudioPreferenceDialog {
    data object AudioLanguageDialog : AudioPreferenceDialog
}

sealed interface AudioPreferencesUiEvent {
    data class ShowDialog(val value: AudioPreferenceDialog?) : AudioPreferencesUiEvent
    data class UpdateAudioLanguage(val value: String) : AudioPreferencesUiEvent
    data class UpdateMaxInitialPlayerVolume(val value: Int) : AudioPreferencesUiEvent
    data object TogglePauseOnHeadsetDisconnect : AudioPreferencesUiEvent
    data object ToggleShowSystemVolumePanel : AudioPreferencesUiEvent
    data object ToggleRequireAudioFocus : AudioPreferencesUiEvent
    data object ToggleRememberPlayerVolume : AudioPreferencesUiEvent
    data object ToggleVolumeNormalization : AudioPreferencesUiEvent
    data object ToggleVolumeBoost : AudioPreferencesUiEvent
}
