package app.gyrolet.mpvrx.exoplayer.settings.screens.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ClickablePreferenceItem
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ListSectionTitle
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextTopAppBar
import app.gyrolet.mpvrx.exoplayer.core.ui.components.OptionsDialog
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSlider
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSwitch
import app.gyrolet.mpvrx.exoplayer.core.ui.components.RadioTextButton
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.withBottomFallback
import app.gyrolet.mpvrx.exoplayer.settings.utils.LocalesHelper
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
object ExoAudioPreferencesScreen : Screen {
    @Composable
    override fun Content() {
        val backstack = LocalBackStack.current
        AudioPreferencesScreen(
            onNavigateUp = { backstack.popSafely() }
        )
    }
}

@Composable
fun AudioPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: AudioPreferencesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AudioPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudioPreferencesContent(
    uiState: AudioPreferencesUiState,
    onEvent: (AudioPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val languages = remember { listOf(Pair("None", "")) + LocalesHelper.getAvailableLocales() }
    val initialVolumeLimitRange = PlayerPreferences.MIN_INITIAL_PLAYER_VOLUME_PERCENTAGE.toFloat().rangeTo(
        PlayerPreferences.MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE.toFloat(),
    )

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.audio),
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
            ListSectionTitle(text = stringResource(id = R.string.audio_track_settings))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_audio_language"),
                    title = stringResource(id = R.string.preferred_audio_lang),
                    description = LocalesHelper.getLocaleDisplayLanguage(uiState.preferences.preferredAudioLanguage)
                        .takeIf { it.isNotBlank() } ?: stringResource(R.string.preferred_audio_lang_description),
                    icon = NextIcons.Language,
                    onClick = { onEvent(AudioPreferencesUiEvent.ShowDialog(AudioPreferenceDialog.AudioLanguageDialog)) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.audio_focus_and_devices))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_require_focus"),
                    title = stringResource(R.string.require_audio_focus),
                    description = stringResource(R.string.require_audio_focus_desc),
                    icon = NextIcons.Focus,
                    isChecked = uiState.preferences.shouldRequireAudioFocus,
                    onClick = { onEvent(AudioPreferencesUiEvent.ToggleRequireAudioFocus) },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_pause_on_headset_disconnect"),
                    title = stringResource(id = R.string.pause_on_headset_disconnect),
                    description = stringResource(id = R.string.pause_on_headset_disconnect_desc),
                    icon = NextIcons.HeadsetOff,
                    isChecked = uiState.preferences.shouldPauseOnHeadsetDisconnect,
                    onClick = { onEvent(AudioPreferencesUiEvent.TogglePauseOnHeadsetDisconnect) },
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_system_volume_panel"),
                    title = stringResource(id = R.string.system_volume_panel),
                    description = stringResource(id = R.string.system_volume_panel_desc),
                    icon = NextIcons.Headset,
                    isChecked = uiState.preferences.shouldShowSystemVolumePanel,
                    onClick = { onEvent(AudioPreferencesUiEvent.ToggleShowSystemVolumePanel) },
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.volume_memory))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_remember_volume"),
                    title = stringResource(id = R.string.remember_volume_level),
                    description = stringResource(id = R.string.remember_volume_level_description),
                    icon = NextIcons.VolumeUp,
                    isChecked = uiState.preferences.shouldRememberPlayerVolume,
                    onClick = { onEvent(AudioPreferencesUiEvent.ToggleRememberPlayerVolume) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    modifier = Modifier.testTag("item_settings_audio_initial_volume_limit"),
                    sliderModifier = Modifier.testTag("slider_settings_audio_initial_volume_limit"),
                    title = stringResource(id = R.string.initial_volume_limit),
                    description = stringResource(id = R.string.percent, uiState.preferences.maxInitialPlayerVolumePercentage),
                    icon = NextIcons.VolumeUp,
                    isEnabled = uiState.preferences.shouldRememberPlayerVolume,
                    value = uiState.preferences.maxInitialPlayerVolumePercentage.toFloat(),
                    valueRange = initialVolumeLimitRange,
                    onValueChange = { onEvent(AudioPreferencesUiEvent.UpdateMaxInitialPlayerVolume(it.toInt())) },
                    isLastItem = true,
                    trailingContent = {
                        FilledIconButton(
                            modifier = Modifier.testTag("btn_reset_settings_audio_initial_volume_limit"),
                            enabled = uiState.preferences.shouldRememberPlayerVolume,
                            onClick = {
                                onEvent(
                                    AudioPreferencesUiEvent.UpdateMaxInitialPlayerVolume(
                                        PlayerPreferences.DEFAULT_MAX_INITIAL_PLAYER_VOLUME_PERCENTAGE,
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.generic_reset),
                            )
                        }
                    },
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.volume_processing))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_normalization"),
                    title = stringResource(id = R.string.volume_normalization),
                    description = stringResource(id = R.string.volume_normalization_desc),
                    icon = NextIcons.VolumeUp,
                    isChecked = uiState.preferences.isVolumeNormalizationEnabled,
                    onClick = { onEvent(AudioPreferencesUiEvent.ToggleVolumeNormalization) },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_audio_boost"),
                    title = stringResource(id = R.string.volume_boost),
                    description = stringResource(id = R.string.volume_boost_desc),
                    icon = NextIcons.VolumeUp,
                    isChecked = uiState.preferences.isVolumeBoostEnabled,
                    onClick = { onEvent(AudioPreferencesUiEvent.ToggleVolumeBoost) },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                AudioPreferenceDialog.AudioLanguageDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.preferred_audio_lang),
                        onDismissClick = { onEvent(AudioPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(languages) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_audio_language_${it.second.ifBlank { "none" }}"),
                                text = it.first,
                                isSelected = it.second == uiState.preferences.preferredAudioLanguage,
                                onClick = {
                                    onEvent(AudioPreferencesUiEvent.UpdateAudioLanguage(it.second))
                                    onEvent(AudioPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
