package app.gyrolet.mpvrx.exoplayer.settings.screens.general

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.components.CancelButton
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ClickablePreferenceItem
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ListSectionTitle
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextDialog
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextTopAppBar
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.withBottomFallback
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
object ExoGeneralPreferencesScreen : Screen {
    @Composable
    override fun Content() {
        val backstack = LocalBackStack.current
        GeneralPreferencesScreen(
            onNavigateUp = { backstack.popSafely() }
        )
    }
}

@Composable
fun GeneralPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: GeneralPreferencesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GeneralPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneralPreferencesContent(
    uiState: GeneralPreferencesUiState,
    onEvent: (GeneralPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val context = LocalContext.current
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = CreateDocument("application/json"),
    ) { uri ->
        onEvent(GeneralPreferencesUiEvent.OnBackupFileSelected(context, uri))
    }
    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = OpenDocument(),
    ) { uri ->
        onEvent(GeneralPreferencesUiEvent.OnRestoreFileSelected(context, uri))
    }

    LaunchedEffect(uiState.pendingAction) {
        when (uiState.pendingAction) {
            GeneralPreferencesPendingAction.BackupSettings -> {
                // TODO: Use a standard backup file name string if available
                createBackupLauncher.launch("exoplayer-settings.json")
            }
            GeneralPreferencesPendingAction.RestoreSettings -> {
                restoreBackupLauncher.launch(arrayOf("application/json"))
            }
            null -> Unit
        }
    }

    val backupSuccessMsg = stringResource(R.string.backup_settings_success)
    val backupFailedMsg = stringResource(R.string.backup_settings_failed)
    val restoreSuccessMsg = stringResource(R.string.restore_settings_success)
    val restoreFailedMsg = stringResource(R.string.restore_settings_failed)

    LaunchedEffect(uiState.resultMessage) {
        val message = when (uiState.resultMessage) {
            GeneralPreferencesResultMessage.BackupSucceeded -> backupSuccessMsg
            GeneralPreferencesResultMessage.BackupFailed -> backupFailedMsg
            GeneralPreferencesResultMessage.RestoreSucceeded -> restoreSuccessMsg
            GeneralPreferencesResultMessage.RestoreFailed -> restoreFailedMsg
            null -> null
        } ?: return@LaunchedEffect

        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onEvent(GeneralPreferencesUiEvent.ClearResultMessage)
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.general_name),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.generic_cancel),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding.withBottomFallback())
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.user_data))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_general_backup_settings"),
                    title = stringResource(R.string.backup_settings),
                    description = stringResource(R.string.backup_settings_description),
                    icon = NextIcons.FileOpen,
                    onClick = { onEvent(GeneralPreferencesUiEvent.BackupSettings) },
                    isFirstItem = true,
                )
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_general_restore_settings"),
                    title = stringResource(R.string.restore_settings),
                    description = stringResource(R.string.restore_settings_description),
                    icon = NextIcons.History,
                    onClick = { onEvent(GeneralPreferencesUiEvent.RestoreSettings) },
                )
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_general_reset_settings"),
                    title = stringResource(R.string.reset_settings),
                    description = stringResource(R.string.reset_settings_description),
                    icon = NextIcons.Delete,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ResetSettingsDialog)) },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { dialog ->
            when (dialog) {
                GeneralPreferencesDialog.ClearVideoCacheDialog -> {
                    // Not implemented in this version
                }
                GeneralPreferencesDialog.ResetSettingsDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(R.string.reset_settings),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                modifier = Modifier.testTag("btn_confirm_settings_general_reset_settings"),
                                onClick = {
                                    onEvent(GeneralPreferencesUiEvent.ResetSettings)
                                    onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(R.string.generic_reset))
                            }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) }) },
                        content = {
                            Text(
                                text = stringResource(R.string.reset_settings_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
            }
        }
    }
}
