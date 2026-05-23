package app.gyrolet.mpvrx.exoplayer.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerPreferences
import app.gyrolet.mpvrx.exoplayer.core.model.SubtitleColor
import app.gyrolet.mpvrx.exoplayer.core.model.SubtitleEdgeStyle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons

import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubtitleStylePanel(
    preferences: PlayerPreferences,
    onPreferencesChange: (PlayerPreferences) -> Unit,
) {
    val isEnabled = preferences.shouldUseSystemCaptionStyle.not()
    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        PreferenceSwitch(
            modifier = Modifier.testTag("switch_settings_subtitle_bold"),
            title = stringResource(id = R.string.subtitle_text_bold),
            description = stringResource(id = R.string.subtitle_text_bold_desc),
            icon = NextIcons.Bold,
            isEnabled = isEnabled,
            isChecked = preferences.shouldUseBoldSubtitleText,
            onClick = { onPreferencesChange(preferences.copy(shouldUseBoldSubtitleText = !preferences.shouldUseBoldSubtitleText)) },
            isFirstItem = true,
        )
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_subtitle_size"),
            sliderModifier = Modifier.testTag("slider_settings_subtitle_size"),
            title = stringResource(id = R.string.subtitle_text_size),
            description = preferences.subtitleTextSize.toString(),
            icon = NextIcons.FontSize,
            isEnabled = isEnabled,
            value = preferences.subtitleTextSize.toFloat(),
            valueRange = SUBTITLE_TEXT_SIZE_RANGE,
            onValueChange = { onPreferencesChange(preferences.copy(subtitleTextSize = it.toInt())) },
            trailingContent = {
                FilledIconButton(
                    modifier = Modifier.testTag("btn_reset_settings_subtitle_size"),
                    enabled = isEnabled,
                    onClick = {
                        onPreferencesChange(
                            preferences.copy(subtitleTextSize = PlayerPreferences.DEFAULT_SUBTITLE_TEXT_SIZE),
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
        PreferenceSlider(
            modifier = Modifier.testTag("item_settings_subtitle_bottom_padding"),
            sliderModifier = Modifier.testTag("slider_settings_subtitle_bottom_padding"),
            title = stringResource(id = R.string.subtitle_position),
            description = "${(preferences.subtitleBottomPaddingFraction * 100).roundToInt()}%",
            icon = NextIcons.Length,
            isEnabled = isEnabled,
            value = preferences.subtitleBottomPaddingFraction,
            valueRange = SUBTITLE_POSITION_RANGE,
            onValueChange = { onPreferencesChange(preferences.copy(subtitleBottomPaddingFraction = it)) },
            trailingContent = {
                FilledIconButton(
                    modifier = Modifier.testTag("btn_reset_settings_subtitle_bottom_padding"),
                    enabled = isEnabled,
                    onClick = {
                        onPreferencesChange(
                            preferences.copy(
                                subtitleBottomPaddingFraction = PlayerPreferences.DEFAULT_SUBTITLE_BOTTOM_PADDING_FRACTION,
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
        PreferenceSwitch(
            modifier = Modifier.testTag("switch_settings_subtitle_background"),
            title = stringResource(id = R.string.subtitle_background),
            description = stringResource(id = R.string.subtitle_background_desc),
            icon = NextIcons.Background,
            isEnabled = isEnabled,
            isChecked = preferences.shouldShowSubtitleBackground,
            onClick = { onPreferencesChange(preferences.copy(shouldShowSubtitleBackground = !preferences.shouldShowSubtitleBackground)) },
        )
        ClickablePreferenceItem(
            modifier = Modifier.testTag("item_settings_subtitle_color"),
            title = stringResource(id = R.string.subtitle_text_color),
            description = preferences.subtitleColor.displayName(),
            icon = NextIcons.Appearance,
            isEnabled = isEnabled,
            onClick = { onPreferencesChange(preferences.copy(subtitleColor = preferences.subtitleColor.next())) },
        )
        ClickablePreferenceItem(
            modifier = Modifier.testTag("item_settings_subtitle_edge_style"),
            title = stringResource(id = R.string.subtitle_edge_style),
            description = preferences.subtitleEdgeStyle.displayName(),
            icon = NextIcons.Style,
            isEnabled = isEnabled,
            onClick = { onPreferencesChange(preferences.copy(subtitleEdgeStyle = preferences.subtitleEdgeStyle.next())) },
            isLastItem = true,
        )
    }
}

@Composable
private fun SubtitleColor.displayName(): String = when (this) {
    SubtitleColor.WHITE -> stringResource(R.string.subtitle_color_white)
    SubtitleColor.YELLOW -> stringResource(R.string.subtitle_color_yellow)
    SubtitleColor.CYAN -> stringResource(R.string.subtitle_color_cyan)
    SubtitleColor.GREEN -> stringResource(R.string.subtitle_color_green)
}

private fun SubtitleColor.next(): SubtitleColor = when (this) {
    SubtitleColor.WHITE -> SubtitleColor.YELLOW
    SubtitleColor.YELLOW -> SubtitleColor.CYAN
    SubtitleColor.CYAN -> SubtitleColor.GREEN
    SubtitleColor.GREEN -> SubtitleColor.WHITE
}

@Composable
private fun SubtitleEdgeStyle.displayName(): String = when (this) {
    SubtitleEdgeStyle.NONE -> stringResource(R.string.subtitle_edge_none)
    SubtitleEdgeStyle.OUTLINE -> stringResource(R.string.subtitle_edge_outline)
    SubtitleEdgeStyle.DROP_SHADOW -> stringResource(R.string.subtitle_edge_shadow)
}

private fun SubtitleEdgeStyle.next(): SubtitleEdgeStyle = when (this) {
    SubtitleEdgeStyle.NONE -> SubtitleEdgeStyle.OUTLINE
    SubtitleEdgeStyle.OUTLINE -> SubtitleEdgeStyle.DROP_SHADOW
    SubtitleEdgeStyle.DROP_SHADOW -> SubtitleEdgeStyle.NONE
}

private val SUBTITLE_TEXT_SIZE_RANGE = 10f..60f
private val SUBTITLE_POSITION_RANGE = PlayerPreferences.MIN_SUBTITLE_BOTTOM_PADDING_FRACTION..PlayerPreferences.MAX_SUBTITLE_BOTTOM_PADDING_FRACTION
