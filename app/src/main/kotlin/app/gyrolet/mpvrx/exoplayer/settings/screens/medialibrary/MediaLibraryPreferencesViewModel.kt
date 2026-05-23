package app.gyrolet.mpvrx.exoplayer.settings.screens.medialibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.ApplicationPreferences

class MediaLibraryPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(MediaLibraryPreferencesUiState())
    val uiState: StateFlow<MediaLibraryPreferencesUiState> = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect {
                uiStateInternal.update { currentState ->
                    currentState.copy(preferences = it)
                }
            }
        }
    }

    fun onEvent(event: MediaLibraryPreferencesUiEvent) {
        when (event) {
            is MediaLibraryPreferencesUiEvent.SetIgnoreNoMediaFiles -> setIgnoreNoMediaFiles(event.shouldIgnoreNoMediaFiles)
            MediaLibraryPreferencesUiEvent.ResetRestrictedFeatures -> resetRestrictedFeatures()
            MediaLibraryPreferencesUiEvent.ToggleMarkLastPlayedMedia -> toggleMarkLastPlayedMedia()
            MediaLibraryPreferencesUiEvent.ToggleRestoreLastPlayedMediaInFolders -> toggleRestoreLastPlayedMediaInFolders()
            MediaLibraryPreferencesUiEvent.ToggleRecycleBinEnabled -> toggleRecycleBinEnabled()
        }
    }

    private fun setIgnoreNoMediaFiles(shouldIgnoreNoMediaFiles: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                if (it.shouldIgnoreNoMediaFiles == shouldIgnoreNoMediaFiles) {
                    it
                } else {
                    it.copy(shouldIgnoreNoMediaFiles = shouldIgnoreNoMediaFiles)
                }
            }
        }
    }

    private fun toggleMarkLastPlayedMedia() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(shouldMarkLastPlayedMedia = !it.shouldMarkLastPlayedMedia)
            }
        }
    }

    private fun toggleRestoreLastPlayedMediaInFolders() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(shouldRestoreLastPlayedMediaInFolders = !it.shouldRestoreLastPlayedMediaInFolders)
            }
        }
    }

    private fun resetRestrictedFeatures() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                val shouldResetIgnoreNoMediaFiles = it.shouldIgnoreNoMediaFiles
                val shouldResetRecycleBin = it.isRecycleBinEnabled

                if (!shouldResetIgnoreNoMediaFiles && !shouldResetRecycleBin) {
                    it
                } else {
                    it.copy(
                        shouldIgnoreNoMediaFiles = false,
                        isRecycleBinEnabled = false,
                    )
                }
            }
        }
    }

    private fun toggleRecycleBinEnabled() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(isRecycleBinEnabled = !it.isRecycleBinEnabled)
            }
        }
    }
}

data class MediaLibraryPreferencesUiState(
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface MediaLibraryPreferencesUiEvent {
    data class SetIgnoreNoMediaFiles(val shouldIgnoreNoMediaFiles: Boolean) : MediaLibraryPreferencesUiEvent
    data object ResetRestrictedFeatures : MediaLibraryPreferencesUiEvent
    data object ToggleMarkLastPlayedMedia : MediaLibraryPreferencesUiEvent
    data object ToggleRestoreLastPlayedMediaInFolders : MediaLibraryPreferencesUiEvent
    data object ToggleRecycleBinEnabled : MediaLibraryPreferencesUiEvent
}
