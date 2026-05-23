package app.gyrolet.mpvrx.exoplayer.settings.screens.subtitle

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.data.repository.SubtitleFontRepository
import app.gyrolet.mpvrx.exoplayer.core.model.Font
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.withSubtitleStyleFrom

class SubtitlePreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val subtitleFontRepository: SubtitleFontRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        SubtitlePreferencesUiState(
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
        viewModelScope.launch {
            subtitleFontRepository.state.collect { state ->
                uiStateInternal.update { currentState ->
                    currentState.copy(
                        externalFontName = state.displayName,
                        isExternalFontAvailable = state.isAvailable,
                    )
                }
            }
        }
    }

    fun onEvent(event: SubtitlePreferencesUiEvent) {
        when (event) {
            is SubtitlePreferencesUiEvent.ShowDialog -> showDialog(event.value)
            SubtitlePreferencesUiEvent.ToggleSubtitleAutoLoad -> toggleSubtitleAutoLoad()
            is SubtitlePreferencesUiEvent.UpdateSubtitleLanguage -> updateSubtitleLanguage(event.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleFont -> updateSubtitleFont(event.value)
            SubtitlePreferencesUiEvent.ToggleSubtitleTextBold -> toggleSubtitleTextBold()
            is SubtitlePreferencesUiEvent.UpdateSubtitleFontSize -> updateSubtitleFontSize(event.value)
            is SubtitlePreferencesUiEvent.UpdateSubtitleStyle -> updateSubtitleStyle(event.preferences)
            SubtitlePreferencesUiEvent.ToggleSubtitleBackground -> toggleSubtitleBackground()
            SubtitlePreferencesUiEvent.ToggleApplyEmbeddedStyles -> toggleApplyEmbeddedStyles()
            is SubtitlePreferencesUiEvent.UpdateSubtitleEncoding -> updateSubtitleEncoding(event.value)
            SubtitlePreferencesUiEvent.ToggleUseSystemCaptionStyle -> toggleUseSystemCaptionStyle()
            SubtitlePreferencesUiEvent.ImportExternalSubtitleFont -> importExternalSubtitleFont()
            is SubtitlePreferencesUiEvent.OnExternalSubtitleFontSelected -> onExternalSubtitleFontSelected(event.uri)
            SubtitlePreferencesUiEvent.ClearExternalSubtitleFont -> clearExternalSubtitleFont()
            SubtitlePreferencesUiEvent.ClearResultMessage -> clearResultMessage()
        }
    }

    private fun showDialog(value: SubtitlePreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun toggleSubtitleAutoLoad() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(isSubtitleAutoLoadEnabled = !it.isSubtitleAutoLoadEnabled)
            }
        }
    }

    private fun updateSubtitleLanguage(value: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(preferredSubtitleLanguage = value)
            }
        }
    }

    private fun updateSubtitleFont(value: Font) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleFont = value)
            }
        }
    }

    private fun toggleSubtitleTextBold() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldUseBoldSubtitleText = !it.shouldUseBoldSubtitleText)
            }
        }
    }

    private fun updateSubtitleFontSize(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(subtitleTextSize = value)
            }
        }
    }

    private fun toggleSubtitleBackground() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldShowSubtitleBackground = !it.shouldShowSubtitleBackground)
            }
        }
    }

    private fun updateSubtitleStyle(preferences: PlayerPreferences) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.withSubtitleStyleFrom(preferences)
            }
        }
    }

    private fun toggleApplyEmbeddedStyles() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(shouldApplyEmbeddedStyles = !it.shouldApplyEmbeddedStyles)
            }
        }
    }

    private fun updateSubtitleEncoding(value: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(subtitleTextEncoding = value) }
        }
    }

    private fun toggleUseSystemCaptionStyle() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(shouldUseSystemCaptionStyle = !it.shouldUseSystemCaptionStyle) }
        }
    }

    private fun importExternalSubtitleFont() {
        uiStateInternal.update {
            it.copy(pendingAction = SubtitlePreferencesPendingAction.OpenExternalSubtitleFontPicker)
        }
    }

    private fun onExternalSubtitleFontSelected(uri: Uri?) {
        uiStateInternal.update { it.copy(pendingAction = null) }
        if (uri == null) return

        viewModelScope.launch {
            runCatching {
                subtitleFontRepository.importFont(uri)
            }.onSuccess {
                uiStateInternal.update { it.copy(resultMessage = SubtitlePreferencesResultMessage.ImportSucceeded) }
            }.onFailure {
                uiStateInternal.update { it.copy(resultMessage = SubtitlePreferencesResultMessage.ImportFailed) }
            }
        }
    }

    private fun clearExternalSubtitleFont() {
        viewModelScope.launch {
            subtitleFontRepository.clearFont()
            uiStateInternal.update { it.copy(resultMessage = SubtitlePreferencesResultMessage.ClearSucceeded) }
        }
    }

    private fun clearResultMessage() {
        uiStateInternal.update { it.copy(resultMessage = null) }
    }
}

@Stable
data class SubtitlePreferencesUiState(
    val showDialog: SubtitlePreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
    val externalFontName: String = "",
    val isExternalFontAvailable: Boolean = false,
    val pendingAction: SubtitlePreferencesPendingAction? = null,
    val resultMessage: SubtitlePreferencesResultMessage? = null,
)

sealed interface SubtitlePreferenceDialog {
    data object SubtitleLanguageDialog : SubtitlePreferenceDialog
    data object SubtitleFontDialog : SubtitlePreferenceDialog
    data object SubtitleEncodingDialog : SubtitlePreferenceDialog
}

sealed interface SubtitlePreferencesPendingAction {
    data object OpenExternalSubtitleFontPicker : SubtitlePreferencesPendingAction
}

sealed interface SubtitlePreferencesResultMessage {
    data object ImportSucceeded : SubtitlePreferencesResultMessage
    data object ImportFailed : SubtitlePreferencesResultMessage
    data object ClearSucceeded : SubtitlePreferencesResultMessage
}

sealed interface SubtitlePreferencesUiEvent {
    data class ShowDialog(val value: SubtitlePreferenceDialog?) : SubtitlePreferencesUiEvent
    data object ToggleSubtitleAutoLoad : SubtitlePreferencesUiEvent
    data class UpdateSubtitleLanguage(val value: String) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleFont(val value: Font) : SubtitlePreferencesUiEvent
    data object ToggleSubtitleTextBold : SubtitlePreferencesUiEvent
    data class UpdateSubtitleFontSize(val value: Int) : SubtitlePreferencesUiEvent
    data class UpdateSubtitleStyle(val preferences: PlayerPreferences) : SubtitlePreferencesUiEvent
    data object ToggleSubtitleBackground : SubtitlePreferencesUiEvent
    data object ToggleApplyEmbeddedStyles : SubtitlePreferencesUiEvent
    data class UpdateSubtitleEncoding(val value: String) : SubtitlePreferencesUiEvent
    data object ToggleUseSystemCaptionStyle : SubtitlePreferencesUiEvent
    data object ImportExternalSubtitleFont : SubtitlePreferencesUiEvent
    data class OnExternalSubtitleFontSelected(val uri: Uri?) : SubtitlePreferencesUiEvent
    data object ClearExternalSubtitleFont : SubtitlePreferencesUiEvent
    data object ClearResultMessage : SubtitlePreferencesUiEvent
}
