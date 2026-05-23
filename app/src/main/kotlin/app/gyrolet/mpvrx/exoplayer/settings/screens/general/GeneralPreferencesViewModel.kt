package app.gyrolet.mpvrx.exoplayer.settings.screens.general

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.gyrolet.mpvrx.exoplayer.core.common.AppLanguageManager
import app.gyrolet.mpvrx.exoplayer.core.common.Logger
import app.gyrolet.mpvrx.exoplayer.core.data.repository.PreferencesRepository
import app.gyrolet.mpvrx.exoplayer.core.model.SettingsBackup

class GeneralPreferencesViewModel(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "GeneralPreferencesViewModel"
    }

    private val uiStateInternal = MutableStateFlow(GeneralPreferencesUiState())
    val uiState = uiStateInternal.asStateFlow()

    fun onEvent(event: GeneralPreferencesUiEvent) {
        when (event) {
            is GeneralPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            GeneralPreferencesUiEvent.ClearVideoCache -> clearVideoCache()
            GeneralPreferencesUiEvent.ResetSettings -> resetSettings()
            GeneralPreferencesUiEvent.BackupSettings -> backupSettings()
            GeneralPreferencesUiEvent.RestoreSettings -> restoreSettings()
            GeneralPreferencesUiEvent.ClearResultMessage -> clearResultMessage()
            is GeneralPreferencesUiEvent.OnBackupFileSelected -> onBackupFileSelected(event.context, event.uri)
            is GeneralPreferencesUiEvent.OnRestoreFileSelected -> onRestoreFileSelected(event.context, event.uri)
        }
    }

    private fun showDialog(value: GeneralPreferencesDialog?) {
        uiStateInternal.update { it.copy(showDialog = value) }
    }

    private fun clearVideoCache() {
        // TODO: Implement clear video cache in MpvRx context
    }

    private fun backupSettings() {
        uiStateInternal.update { it.copy(pendingAction = GeneralPreferencesPendingAction.BackupSettings) }
    }

    private fun restoreSettings() {
        uiStateInternal.update { it.copy(pendingAction = GeneralPreferencesPendingAction.RestoreSettings) }
    }

    private fun onBackupFileSelected(context: Context, uri: Uri?) {
        uiStateInternal.update { it.copy(pendingAction = null) }
        if (uri == null) return

        viewModelScope.launch {
            // TODO: Implement backup logic using Json if needed
            uiStateInternal.update { it.copy(resultMessage = GeneralPreferencesResultMessage.BackupFailed) }
        }
    }

    private fun onRestoreFileSelected(context: Context, uri: Uri?) {
        uiStateInternal.update { it.copy(pendingAction = null) }
        if (uri == null) return

        viewModelScope.launch {
            // TODO: Implement restore logic
            uiStateInternal.update { it.copy(resultMessage = GeneralPreferencesResultMessage.RestoreFailed) }
        }
    }

    private fun clearResultMessage() {
        uiStateInternal.update { it.copy(resultMessage = null) }
    }

    private fun resetSettings() {
        viewModelScope.launch {
            preferencesRepository.resetPreferences()
            AppLanguageManager.applyToCurrent("")
        }
    }
}

@Stable
data class GeneralPreferencesUiState(
    val showDialog: GeneralPreferencesDialog? = null,
    val pendingAction: GeneralPreferencesPendingAction? = null,
    val resultMessage: GeneralPreferencesResultMessage? = null,
)

sealed interface GeneralPreferencesPendingAction {
    data object BackupSettings : GeneralPreferencesPendingAction
    data object RestoreSettings : GeneralPreferencesPendingAction
}

sealed interface GeneralPreferencesResultMessage {
    data object BackupSucceeded : GeneralPreferencesResultMessage
    data object BackupFailed : GeneralPreferencesResultMessage
    data object RestoreSucceeded : GeneralPreferencesResultMessage
    data object RestoreFailed : GeneralPreferencesResultMessage
}

sealed interface GeneralPreferencesDialog {
    data object ClearVideoCacheDialog : GeneralPreferencesDialog
    data object ResetSettingsDialog : GeneralPreferencesDialog
}

sealed interface GeneralPreferencesUiEvent {
    data class ShowDialog(val value: GeneralPreferencesDialog?) : GeneralPreferencesUiEvent
    data class OnBackupFileSelected(val context: Context, val uri: Uri?) : GeneralPreferencesUiEvent
    data class OnRestoreFileSelected(val context: Context, val uri: Uri?) : GeneralPreferencesUiEvent
    data object ClearVideoCache : GeneralPreferencesUiEvent
    data object ResetSettings : GeneralPreferencesUiEvent
    data object BackupSettings : GeneralPreferencesUiEvent
    data object RestoreSettings : GeneralPreferencesUiEvent
    data object ClearResultMessage : GeneralPreferencesUiEvent
}
