package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon

@Composable
fun MenuRootContent(
    isLockEnabled: Boolean,
    isPipSupported: Boolean,
    isTakingScreenshot: Boolean,
    isVideoFiltersEnabled: Boolean,
    isStatsEnabled: Boolean,
    isAmbienceModeEnabled: Boolean,
    isShuffleEnabled: Boolean,
    onNavigate: (MenuRoute) -> Unit,
    onLockClick: () -> Unit,
    onAmbienceClick: () -> Unit,
    onPictureInPictureClick: () -> Unit,
    onScreenshotClick: () -> Unit,
    onPlayInBackgroundClick: () -> Unit,
    onLoopClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onStatsClick: () -> Unit,
    onVideoFiltersToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Stats toggle
        MenuToggleItemRow(
            icon = NextIcons.BugReport,
            text = stringResource(R.string.player_sheets_stats_page_title),
            testTag = "menu_item_stats",
            isChecked = isStatsEnabled,
            onClick = onStatsClick,
        )
        MenuItemRow(
            icon = NextIcons.Subtitle,
            text = stringResource(R.string.select_subtitle_track),
            testTag = "menu_item_subtitle",
            onClick = { onNavigate(MenuRoute.Subtitle) },
        )
        MenuItemRow(
            icon = NextIcons.Audio,
            text = stringResource(R.string.select_audio_track),
            testTag = "menu_item_audio",
            onClick = { onNavigate(MenuRoute.Audio) },
        )
        MenuItemRow(
            icon = NextIcons.Frame,
            text = stringResource(R.string.video_zoom),
            testTag = "menu_item_video_scale",
            onClick = { onNavigate(MenuRoute.VideoContentScale) },
        )
        MenuItemRow(
            icon = NextIcons.Decoder,
            text = stringResource(R.string.decoder_priority),
            testTag = "menu_item_decoder",
            onClick = { onNavigate(MenuRoute.Decoder) },
        )
        // Video Filters toggle
        MenuToggleItemRow(
            icon = NextIcons.Sensitivity,
            text = stringResource(R.string.video_filters),
            testTag = "menu_item_video_filters",
            isChecked = isVideoFiltersEnabled,
            onClick = { onNavigate(MenuRoute.VideoFilters) },
            onToggle = onVideoFiltersToggle,
        )
        MenuItemRow(
            icon = NextIcons.Timer,
            text = stringResource(R.string.exo_sleep_timer),
            testTag = "menu_item_sleep_timer",
            onClick = { onNavigate(MenuRoute.SleepTimer) },
        )
        MenuItemRow(
            icon = NextIcons.Lock,
            text = stringResource(if (isLockEnabled) R.string.controls_unlock else R.string.controls_lock),
            testTag = "menu_item_lock",
            onClick = onLockClick,
        )
        // Ambience Mode toggle
        MenuToggleItemRow(
            icon = NextIcons.Style,
            text = stringResource(R.string.ambience_mode),
            testTag = "menu_item_ambience",
            isChecked = isAmbienceModeEnabled,
            onClick = onAmbienceClick,
        )
        if (isPipSupported) {
            MenuItemRow(
                icon = NextIcons.Pip,
                text = stringResource(R.string.pip_settings),
                testTag = "menu_item_pip",
                onClick = onPictureInPictureClick,
            )
        }
        MenuItemRow(
            icon = NextIcons.Screenshot,
            text = stringResource(R.string.take_screenshot),
            testTag = "menu_item_screenshot",
            onClick = onScreenshotClick,
            isEnabled = !isTakingScreenshot,
        )
        MenuItemRow(
            icon = NextIcons.Headset,
            text = stringResource(R.string.background_play),
            testTag = "menu_item_background",
            onClick = onPlayInBackgroundClick,
        )
        MenuItemRow(
            icon = NextIcons.Loop,
            text = stringResource(R.string.loop_mode),
            testTag = "menu_item_loop",
            onClick = onLoopClick,
        )
        // Shuffle toggle
        MenuToggleItemRow(
            icon = NextIcons.Shuffle,
            text = stringResource(R.string.shuffle),
            testTag = "menu_item_shuffle",
            isChecked = isShuffleEnabled,
            onClick = onShuffleClick,
        )
    }
}

@Composable
private fun MenuItemRow(
    icon: AppIcon,
    text: String,
    testTag: String,
    onClick: () -> Unit,
    isEnabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag(testTag),
        onClick = onClick,
        enabled = isEnabled,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Menu item row with a toggle switch that dynamically adapts to the theme colors.
 * The switch uses Material theme primary color when ON, and surfaceVariant when OFF.
 * Clicking the row text/icon triggers [onClick], clicking the switch triggers [onToggle]
 * (or [onClick] if [onToggle] is null).
 */
@Composable
private fun MenuToggleItemRow(
    icon: AppIcon,
    text: String,
    testTag: String,
    isChecked: Boolean,
    onClick: () -> Unit,
    onToggle: (() -> Unit)? = null,
    isEnabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .testTag(testTag),
        onClick = onClick,
        enabled = isEnabled,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isChecked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isChecked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = isChecked,
                onCheckedChange = { onToggle?.invoke() ?: onClick() },
                enabled = isEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                thumbContent = if (isChecked) {
                    {
                        Icon(
                            imageVector = NextIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(SwitchDefaults.IconSize),
                        )
                    }
                } else null,
            )
        }
    }
}
