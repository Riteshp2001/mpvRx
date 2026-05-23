package app.gyrolet.mpvrx.exoplayer.core.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons

@Composable
fun VideoFiltersPanel(
    preferences: PlayerPreferences,
    onDismissRequest: () -> Unit,
    onPreviewPreferences: (PlayerPreferences) -> Unit,
    onConfirmPreferences: (PlayerPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    VideoFiltersEditor(
        preferences = preferences,
        onDismissRequest = onDismissRequest,
        onPreviewPreferences = onPreviewPreferences,
        onConfirmPreferences = onConfirmPreferences,
    ) { draftPreferences, updateDraft, resetFilters, restoreAndDismiss, confirmAndDismiss ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isLandscape) {
                LandscapeVideoFiltersContent(
                    modifier = Modifier.weight(1f),
                    preferences = draftPreferences,
                    onUpdatePreferences = updateDraft,
                )
            } else {
                PortraitVideoFiltersContent(
                    modifier = Modifier.weight(1f),
                    preferences = draftPreferences,
                    onUpdatePreferences = updateDraft,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                CancelButton(
                    modifier = Modifier.testTag("btn_cancel_video_filters"),
                    onClick = restoreAndDismiss,
                )
                DoneButton(
                    modifier = Modifier.testTag("btn_done_video_filters"),
                    onClick = confirmAndDismiss,
                )
            }
        }
    }
}

@Composable
private fun VideoFiltersEditor(
    preferences: PlayerPreferences,
    onDismissRequest: () -> Unit,
    onPreviewPreferences: (PlayerPreferences) -> Unit,
    onConfirmPreferences: (PlayerPreferences) -> Unit,
    content: @Composable (
        draftPreferences: PlayerPreferences,
        updateDraft: (((PlayerPreferences) -> PlayerPreferences) -> Unit),
        resetFilters: () -> Unit,
        restoreAndDismiss: () -> Unit,
        confirmAndDismiss: () -> Unit,
    ) -> Unit,
) {
    val initialPreferences = remember(preferences) { preferences }
    var draftPreferences by remember(preferences) { mutableStateOf(preferences) }
    val updateDraft = { transform: (PlayerPreferences) -> PlayerPreferences ->
        val updatedPreferences = transform(draftPreferences)
        draftPreferences = updatedPreferences
        onPreviewPreferences(updatedPreferences)
    }
    val restoreAndDismiss = {
        onPreviewPreferences(initialPreferences)
        onDismissRequest()
    }
    val confirmAndDismiss = {
        onConfirmPreferences(draftPreferences)
        onDismissRequest()
    }
    val resetFilters = {
        updateDraft {
            it.copy(
                shouldApplyVideoFilters = false,
                isVideoBrightnessFilterEnabled = false,
                videoBrightness = PlayerPreferences.DEFAULT_VIDEO_BRIGHTNESS,
                isVideoContrastFilterEnabled = false,
                videoContrast = PlayerPreferences.DEFAULT_VIDEO_CONTRAST,
                isVideoSaturationFilterEnabled = false,
                videoSaturation = PlayerPreferences.DEFAULT_VIDEO_SATURATION,
                isVideoHueFilterEnabled = false,
                videoHue = PlayerPreferences.DEFAULT_VIDEO_HUE,
                isVideoGammaFilterEnabled = false,
                videoGamma = PlayerPreferences.DEFAULT_VIDEO_GAMMA,
                isVideoSharpeningFilterEnabled = false,
                videoSharpening = PlayerPreferences.DEFAULT_VIDEO_SHARPENING,
            )
        }
    }

    content(
        draftPreferences,
        updateDraft,
        resetFilters,
        restoreAndDismiss,
        confirmAndDismiss,
    )
}

@Composable
private fun PortraitVideoFiltersContent(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderSpecs = videoFilterSliderSpecs(preferences, onUpdatePreferences)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VideoFiltersSwitch(
            preferences = preferences,
            onUpdatePreferences = onUpdatePreferences,
        )
        sliderSpecs.forEach { spec ->
            VideoFilterSlider(spec)
        }
    }
}

@Composable
private fun LandscapeVideoFiltersContent(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderSpecs = videoFilterSliderSpecs(preferences, onUpdatePreferences)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VideoFiltersSwitch(
            preferences = preferences,
            onUpdatePreferences = onUpdatePreferences,
        )
        sliderSpecs.chunked(2).forEach { rowSpecs ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rowSpecs.forEach { spec ->
                    CompactVideoFilterSlider(
                        modifier = Modifier.weight(1f),
                        spec = spec,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoFiltersSwitch(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
) {
    PreferenceSwitch(
        modifier = Modifier.testTag("switch_video_filters"),
        title = stringResource(R.string.enable_video_filters),
        description = stringResource(R.string.enable_video_filters_description),
        icon = NextIcons.Sensitivity,
        isChecked = preferences.shouldApplyVideoFilters,
        onClick = {
            onUpdatePreferences { it.copy(shouldApplyVideoFilters = !it.shouldApplyVideoFilters) }
        },
    )
}

private data class VideoFilterSliderSpec(
    val title: String,
    val value: Float,
    val valueRange: ClosedFloatingPointRange<Float>,
    val valueText: String,
    val testTag: String,
    val switchTestTag: String,
    val isEnabled: Boolean,
    val isChecked: Boolean,
    val onCheckedChange: () -> Unit,
    val onValueChange: (Float) -> Unit,
)

@Composable
private fun videoFilterSliderSpecs(
    preferences: PlayerPreferences,
    onUpdatePreferences: ((PlayerPreferences) -> PlayerPreferences) -> Unit,
): List<VideoFilterSliderSpec> = listOf(
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_brightness),
        value = preferences.videoBrightness,
        valueRange = PlayerPreferences.MIN_VIDEO_BRIGHTNESS..PlayerPreferences.MAX_VIDEO_BRIGHTNESS,
        valueText = signedPercent(preferences.videoBrightness),
        testTag = "slider_video_brightness",
        switchTestTag = "switch_video_brightness",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoBrightnessFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoBrightnessFilterEnabled = !preferences.isVideoBrightnessFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoBrightness = it) } },
    ),
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_contrast),
        value = preferences.videoContrast,
        valueRange = PlayerPreferences.MIN_VIDEO_CONTRAST..PlayerPreferences.MAX_VIDEO_CONTRAST,
        valueText = signedPercent(preferences.videoContrast),
        testTag = "slider_video_contrast",
        switchTestTag = "switch_video_contrast",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoContrastFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoContrastFilterEnabled = !preferences.isVideoContrastFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoContrast = it) } },
    ),
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_saturation),
        value = preferences.videoSaturation,
        valueRange = PlayerPreferences.MIN_VIDEO_SATURATION..PlayerPreferences.MAX_VIDEO_SATURATION,
        valueText = signedInteger(preferences.videoSaturation),
        testTag = "slider_video_saturation",
        switchTestTag = "switch_video_saturation",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoSaturationFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoSaturationFilterEnabled = !preferences.isVideoSaturationFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSaturation = it) } },
    ),
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_hue),
        value = preferences.videoHue,
        valueRange = PlayerPreferences.MIN_VIDEO_HUE..PlayerPreferences.MAX_VIDEO_HUE,
        valueText = stringResource(R.string.exo_degrees, preferences.videoHue.toInt()),
        testTag = "slider_video_hue",
        switchTestTag = "switch_video_hue",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoHueFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoHueFilterEnabled = !preferences.isVideoHueFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoHue = it) } },
    ),
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_gamma),
        value = preferences.videoGamma,
        valueRange = PlayerPreferences.MIN_VIDEO_GAMMA..PlayerPreferences.MAX_VIDEO_GAMMA,
        valueText = String.format("%.2f", preferences.videoGamma),
        testTag = "slider_video_gamma",
        switchTestTag = "switch_video_gamma",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoGammaFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoGammaFilterEnabled = !preferences.isVideoGammaFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoGamma = it) } },
    ),
    VideoFilterSliderSpec(
        title = stringResource(R.string.video_sharpening),
        value = preferences.videoSharpening,
        valueRange = PlayerPreferences.DEFAULT_VIDEO_SHARPENING..PlayerPreferences.MAX_VIDEO_SHARPENING,
        valueText = stringResource(R.string.exo_percent, (preferences.videoSharpening * 100).toInt()),
        testTag = "slider_video_sharpening",
        switchTestTag = "switch_video_sharpening",
        isEnabled = preferences.shouldApplyVideoFilters,
        isChecked = preferences.isVideoSharpeningFilterEnabled,
        onCheckedChange = {
            onUpdatePreferences { preferences ->
                preferences.copy(isVideoSharpeningFilterEnabled = !preferences.isVideoSharpeningFilterEnabled)
            }
        },
        onValueChange = { onUpdatePreferences { preferences -> preferences.copy(videoSharpening = it) } },
    ),
)

@Composable
private fun VideoFilterSlider(spec: VideoFilterSliderSpec) {
    PreferenceSlider(
        modifier = Modifier.testTag(spec.testTag),
        title = spec.title,
        description = spec.valueText,
        isEnabled = spec.isEnabled,
        isSliderEnabled = spec.isEnabled && spec.isChecked,
        value = spec.value,
        valueRange = spec.valueRange,
        onValueChange = spec.onValueChange,
        trailingContent = {
            NextSwitch(
                modifier = Modifier.testTag(spec.switchTestTag),
                isChecked = spec.isChecked,
                onCheckedChange = { spec.onCheckedChange() },
                isEnabled = spec.isEnabled,
            )
        },
    )
}

@Composable
private fun CompactVideoFilterSlider(
    spec: VideoFilterSliderSpec,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(spec.testTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = spec.title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                modifier = Modifier.widthIn(min = 40.dp).padding(end = 8.dp),
                text = spec.valueText,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NextSwitch(
                modifier = Modifier.testTag(spec.switchTestTag),
                isChecked = spec.isChecked,
                onCheckedChange = { spec.onCheckedChange() },
                isEnabled = spec.isEnabled,
            )
        }
        Slider(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 28.dp),
            enabled = spec.isEnabled && spec.isChecked,
            value = spec.value,
            valueRange = spec.valueRange,
            onValueChange = spec.onValueChange,
        )
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
