package app.gyrolet.mpvrx.exoplayer.settings.screens.player

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.isPipFeatureSupported
import app.gyrolet.mpvrx.exoplayer.core.common.extensions.round
import app.gyrolet.mpvrx.exoplayer.core.model.ControlButtonsPosition
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.ScreenOrientation
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ClickablePreferenceItem
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ListSectionTitle
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextTopAppBar
import app.gyrolet.mpvrx.exoplayer.core.ui.components.OptionsDialog
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSlider
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSwitch
import app.gyrolet.mpvrx.exoplayer.core.ui.components.RadioTextButton
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.withBottomFallback
import app.gyrolet.mpvrx.exoplayer.settings.extensions.isEnabled
import app.gyrolet.mpvrx.exoplayer.settings.extensions.name
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
object ExoPlayerPreferencesScreen : Screen {
    @Composable
    override fun Content() {
        val backstack = LocalBackStack.current
        PlayerPreferencesScreen(
            onNavigateUp = { backstack.popSafely() }
        )
    }
}

@Composable
fun PlayerPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: PlayerPreferencesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlayerPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerPreferencesContent(
    uiState: PlayerPreferencesUiState,
    onEvent: (PlayerPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit = {},
) {
    val isPipFeatureSupported = LocalContext.current.isPipFeatureSupported

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.exo_player),
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
            ListSectionTitle(text = stringResource(id = R.string.pref_appearance_title))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSlider(
                    modifier = Modifier.testTag("item_settings_player_controller_timeout"),
                    sliderModifier = Modifier.testTag("slider_settings_player_controller_timeout"),
                    title = stringResource(R.string.controller_timeout),
                    description = stringResource(R.string.exo_seconds, uiState.preferences.controllerAutoHideTimeout),
                    icon = NextIcons.Timer,
                    value = uiState.preferences.controllerAutoHideTimeout.toFloat(),
                    valueRange = 1.0f..60.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(it.toInt())) },
                    isFirstItem = true,
                    trailingContent = {
                        FilledIconButton(
                            modifier = Modifier.testTag("btn_reset_settings_player_controller_timeout"),
                            onClick = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(PlayerPreferences.DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT)) },
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.generic_reset),
                            )
                        }
                    },
                )
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_player_screen_orientation"),
                    title = stringResource(id = R.string.player_screen_orientation),
                    description = uiState.preferences.playerScreenOrientation.name(),
                    icon = NextIcons.Rotation,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.PlayerScreenOrientationDialog))
                    },
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_remember_orientation"),
                    title = stringResource(id = R.string.remember_player_screen_orientation),
                    description = stringResource(id = R.string.remember_player_screen_orientation_description),
                    icon = NextIcons.History,
                    isChecked = uiState.preferences.shouldRememberPlayerScreenOrientation,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberPlayerScreenOrientation) },
                )
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_player_controls_style"),
                    title = stringResource(id = R.string.player_controls_style),
                    description = uiState.preferences.controlsStyle.name(),
                    icon = NextIcons.Player,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ControlsStyleDialog)) },
                    isLastItem = uiState.preferences.controlsStyle != PlayerControlsStyle.LEGACY,
                )
                if (uiState.preferences.controlsStyle == PlayerControlsStyle.LEGACY) {
                    ClickablePreferenceItem(
                        modifier = Modifier.testTag("item_settings_player_icon_style"),
                        title = stringResource(id = R.string.player_icon_style),
                        description = uiState.preferences.playerIconStyle.name(),
                        icon = NextIcons.Style,
                        onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.PlayerIconStyleDialog)) },
                        isLastItem = true,
                    )
                }
            }

            ListSectionTitle(text = stringResource(id = R.string.playback_behavior))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_resume"),
                    title = stringResource(id = R.string.resume),
                    description = stringResource(id = R.string.resume_description),
                    icon = NextIcons.Resume,
                    isChecked = uiState.preferences.resume.isEnabled,
                    onClick = { onEvent(PlayerPreferencesUiEvent.TogglePlaybackResume) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    modifier = Modifier.testTag("item_settings_player_default_speed"),
                    sliderModifier = Modifier.testTag("slider_settings_player_default_speed"),
                    title = stringResource(id = R.string.default_playback_speed),
                    description = uiState.preferences.defaultPlaybackSpeed.toString(),
                    icon = NextIcons.Speed,
                    value = uiState.preferences.defaultPlaybackSpeed,
                    valueRange = 0.2f..4.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(it.round(2))) },
                    trailingContent = {
                        FilledIconButton(
                            modifier = Modifier.testTag("btn_reset_settings_player_default_speed"),
                            onClick = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(1f)) },
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.generic_reset),
                            )
                        }
                    },
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_autoplay"),
                    title = stringResource(id = R.string.autoplay_settings),
                    description = stringResource(
                        id = R.string.autoplay_settings_description,
                    ),
                    icon = NextIcons.Player,
                    isChecked = uiState.preferences.shouldAutoPlay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoplay) },
                )
                if (isPipFeatureSupported) {
                    PreferenceSwitch(
                        modifier = Modifier.testTag("switch_settings_player_auto_pip"),
                        title = stringResource(id = R.string.pip_settings),
                        description = stringResource(
                            id = R.string.pip_settings_description,
                        ),
                        icon = NextIcons.Pip,
                        isChecked = uiState.preferences.shouldAutoEnterPip,
                        onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoPip) },
                    )
                }
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_background_play"),
                    title = stringResource(id = R.string.background_play),
                    description = stringResource(
                        id = R.string.background_play_description,
                    ),
                    icon = NextIcons.Headset,
                    isChecked = uiState.preferences.shouldAutoPlayInBackground,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay) },
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_remember_brightness"),
                    title = stringResource(id = R.string.remember_brightness_level),
                    description = stringResource(
                        id = R.string.remember_brightness_level_description,
                    ),
                    icon = NextIcons.Brightness,
                    isChecked = uiState.preferences.shouldRememberPlayerBrightness,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel) },
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.player_controls))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_player_control_buttons_position"),
                    title = stringResource(id = R.string.control_buttons_alignment),
                    description = uiState.preferences.controlButtonsPosition.name(),
                    icon = NextIcons.ButtonsPosition,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ControlButtonsDialog)) },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    modifier = Modifier.testTag("switch_settings_player_control_labels"),
                    title = stringResource(id = R.string.player_control_labels),
                    description = stringResource(id = R.string.player_control_labels_description),
                    icon = NextIcons.Title,
                    isChecked = uiState.preferences.shouldHidePlayerControlLabels,
                    onClick = { onEvent(PlayerPreferencesUiEvent.TogglePlayerControlLabels) },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                PlayerPreferenceDialog.PlayerScreenOrientationDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.player_screen_orientation),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ScreenOrientation.entries.toTypedArray()) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_player_screen_orientation_${it.name.lowercase()}"),
                                text = it.name(),
                                isSelected = it == uiState.preferences.playerScreenOrientation,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredPlayerOrientation(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.ControlButtonsDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.control_buttons_alignment),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ControlButtonsPosition.entries.toTypedArray()) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_player_control_buttons_position_${it.name.lowercase()}"),
                                text = it.name(),
                                isSelected = it == uiState.preferences.controlButtonsPosition,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredControlButtonsPosition(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.PlayerIconStyleDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.player_icon_style),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(PlayerIconStyle.entries.toTypedArray()) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_player_icon_style_${it.name.lowercase()}"),
                                text = it.name(),
                                isSelected = it == uiState.preferences.playerIconStyle,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePlayerIconStyle(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.ControlsStyleDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.player_controls_style),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(PlayerControlsStyle.entries.toTypedArray()) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_player_controls_style_${it.name.lowercase()}"),
                                text = it.name(),
                                isSelected = it == uiState.preferences.controlsStyle,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdateControlsStyle(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
