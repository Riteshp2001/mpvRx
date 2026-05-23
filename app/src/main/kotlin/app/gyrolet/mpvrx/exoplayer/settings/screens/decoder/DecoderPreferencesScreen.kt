package app.gyrolet.mpvrx.exoplayer.settings.screens.decoder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ClickablePreferenceItem
import app.gyrolet.mpvrx.exoplayer.core.ui.components.ListSectionTitle
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextSwitch
import app.gyrolet.mpvrx.exoplayer.core.ui.components.NextTopAppBar
import app.gyrolet.mpvrx.exoplayer.core.ui.components.OptionsDialog
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSlider
import app.gyrolet.mpvrx.exoplayer.core.ui.components.PreferenceSwitch
import app.gyrolet.mpvrx.exoplayer.core.ui.components.RadioTextButton
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.core.ui.extensions.withBottomFallback
import app.gyrolet.mpvrx.exoplayer.settings.extensions.name
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

@Serializable
object ExoDecoderPreferencesScreen : Screen {
    @Composable
    override fun Content() {
        val backstack = LocalBackStack.current
        DecoderPreferencesScreen(
            onNavigateUp = { backstack.popSafely() }
        )
    }
}

@Composable
fun DecoderPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: DecoderPreferencesViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DecoderPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DecoderPreferencesContent(
    uiState: DecoderPreferencesUiState,
    onEvent: (DecoderPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
) {
    val preferences = uiState.preferences

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.exo_decoder),
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
            ListSectionTitle(text = stringResource(id = R.string.exo_decoder))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    modifier = Modifier.testTag("item_settings_decoder_priority"),
                    title = stringResource(R.string.decoder_priority),
                    description = preferences.decoderPriority.name(),
                    icon = NextIcons.Priority,
                    onClick = { onEvent(DecoderPreferencesUiEvent.ShowDialog(DecoderPreferenceDialog.DecoderPriorityDialog)) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.video_filters))
            VideoFiltersSettings(
                preferences = preferences,
                onEvent = onEvent,
            )
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                DecoderPreferenceDialog.DecoderPriorityDialog -> {
                    OptionsDialog(
                        title = stringResource(id = R.string.decoder_priority),
                        onDismissClick = { onEvent(DecoderPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(DecoderPriority.entries.toTypedArray()) {
                            RadioTextButton(
                                modifier = Modifier.testTag("option_settings_decoder_priority_${it.name.lowercase()}"),
                                text = it.name(),
                                isSelected = it == preferences.decoderPriority,
                                onClick = {
                                    onEvent(DecoderPreferencesUiEvent.UpdateDecoderPriority(it))
                                    onEvent(DecoderPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoFiltersSettings(
    preferences: PlayerPreferences,
    onEvent: (DecoderPreferencesUiEvent) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        PreferenceSwitch(
            modifier = Modifier.testTag("switch_settings_video_filters"),
            title = stringResource(R.string.video_filters),
            description = stringResource(R.string.enable_video_filters_description),
            icon = NextIcons.Sensitivity,
            isChecked = preferences.shouldApplyVideoFilters,
            onClick = { onEvent(DecoderPreferencesUiEvent.ToggleVideoFilters) },
            isFirstItem = true,
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_brightness"),
            sliderModifier = Modifier.testTag("slider_settings_video_brightness"),
            title = stringResource(R.string.video_brightness),
            description = signedPercent(preferences.videoBrightness),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoBrightnessFilterEnabled,
            value = preferences.videoBrightness,
            valueRange = PlayerPreferences.MIN_VIDEO_BRIGHTNESS..PlayerPreferences.MAX_VIDEO_BRIGHTNESS,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoBrightness(it)) },
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_brightness",
                    resetTestTag = "btn_reset_settings_video_brightness",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoBrightnessFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoBrightnessFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoBrightness(PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS)) },
                )
            },
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_contrast"),
            sliderModifier = Modifier.testTag("slider_settings_video_contrast"),
            title = stringResource(R.string.video_contrast),
            description = signedPercent(preferences.videoContrast),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoContrastFilterEnabled,
            value = preferences.videoContrast,
            valueRange = PlayerPreferences.MIN_VIDEO_CONTRAST..PlayerPreferences.MAX_VIDEO_CONTRAST,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoContrast(it)) },
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_contrast",
                    resetTestTag = "btn_reset_settings_video_contrast",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoContrastFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoContrastFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoContrast(PlayerPreferences.DEFAULT_VIDEO_CONTRAST)) },
                )
            },
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_saturation"),
            sliderModifier = Modifier.testTag("slider_settings_video_saturation"),
            title = stringResource(R.string.video_saturation),
            description = signedInteger(preferences.videoSaturation),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoSaturationFilterEnabled,
            value = preferences.videoSaturation,
            valueRange = PlayerPreferences.MIN_VIDEO_SATURATION..PlayerPreferences.MAX_VIDEO_SATURATION,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoSaturation(it)) },
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_saturation",
                    resetTestTag = "btn_reset_settings_video_saturation",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoSaturationFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoSaturationFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoSaturation(PlayerPreferences.DEFAULT_VIDEO_SATURATION)) },
                )
            },
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_hue"),
            sliderModifier = Modifier.testTag("slider_settings_video_hue"),
            title = stringResource(R.string.video_hue),
            description = stringResource(R.string.exo_degrees, preferences.videoHue.toInt()),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoHueFilterEnabled,
            value = preferences.videoHue,
            valueRange = PlayerPreferences.MIN_VIDEO_HUE..PlayerPreferences.MAX_VIDEO_HUE,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoHue(it)) },
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_hue",
                    resetTestTag = "btn_reset_settings_video_hue",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoHueFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoHueFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoHue(PlayerPreferences.DEFAULT_VIDEO_HUE)) },
                )
            },
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_gamma"),
            sliderModifier = Modifier.testTag("slider_settings_video_gamma"),
            title = stringResource(R.string.video_gamma),
            description = String.format("%.2f", preferences.videoGamma),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoGammaFilterEnabled,
            value = preferences.videoGamma,
            valueRange = PlayerPreferences.MIN_VIDEO_GAMMA..PlayerPreferences.MAX_VIDEO_GAMMA,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoGamma(it)) },
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_gamma",
                    resetTestTag = "btn_reset_settings_video_gamma",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoGammaFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoGammaFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoGamma(PlayerPreferences.DEFAULT_VIDEO_GAMMA)) },
                )
            },
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_video_sharpening"),
            sliderModifier = Modifier.testTag("slider_settings_video_sharpening"),
            title = stringResource(R.string.video_sharpening),
            description = stringResource(R.string.exo_percent, (preferences.videoSharpening * 100).toInt()),
            icon = NextIcons.Sensitivity,
            isEnabled = preferences.shouldApplyVideoFilters,
            isSliderEnabled = preferences.shouldApplyVideoFilters && preferences.isVideoSharpeningFilterEnabled,
            value = preferences.videoSharpening,
            valueRange = PlayerPreferences.DEFAULT_VIDEO_SHARPENING..PlayerPreferences.MAX_VIDEO_SHARPENING,
            onValueChange = { onEvent(DecoderPreferencesUiEvent.UpdateVideoSharpening(it)) },
            isLastItem = true,
            trailingContent = {
                VideoFilterActions(
                    switchTestTag = "switch_settings_video_sharpening",
                    resetTestTag = "btn_reset_settings_video_sharpening",
                    resetContentDescription = stringResource(id = R.string.generic_reset),
                    isEnabled = preferences.shouldApplyVideoFilters,
                    isChecked = preferences.isVideoSharpeningFilterEnabled,
                    onToggle = { onEvent(DecoderPreferencesUiEvent.ToggleVideoSharpeningFilter) },
                    onReset = { onEvent(DecoderPreferencesUiEvent.UpdateVideoSharpening(PlayerPreferences.DEFAULT_VIDEO_SHARPENING)) },
                )
            },
        )
    }
}

@Composable
private fun VideoFilterActions(
    switchTestTag: String,
    resetTestTag: String,
    resetContentDescription: String,
    isEnabled: Boolean,
    isChecked: Boolean,
    onToggle: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NextSwitch(
            modifier = Modifier.testTag(switchTestTag),
            isChecked = isChecked,
            onCheckedChange = { onToggle() },
            isEnabled = isEnabled,
        )
        FilledIconButton(
            modifier = Modifier.testTag(resetTestTag),
            enabled = isEnabled,
            onClick = onReset,
        ) {
            Icon(
                imageVector = NextIcons.History,
                contentDescription = resetContentDescription,
            )
        }
    }
}

private fun signedPercent(value: Float): String {
    val percent = (value * 100).toInt()
    return if (percent > 0) "+$percent%" else "$percent%"
}

private fun signedInteger(value: Float): String {
    val rounded = value.toInt()
    return if (rounded > 0) "+$rounded" else "$rounded"
}
