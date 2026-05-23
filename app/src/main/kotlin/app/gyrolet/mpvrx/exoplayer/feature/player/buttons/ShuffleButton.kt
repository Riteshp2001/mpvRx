package app.gyrolet.mpvrx.exoplayer.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberShuffleButtonState
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.exoplayer.feature.player.LocalControlsVisibilityState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon

@OptIn(UnstableApi::class)
@Composable
fun ShuffleButton(
    player: Player,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    label: String? = null,
    shouldDimWhenUnselected: Boolean = false,
    shouldShowCustomizeFrame: Boolean = false,
    isOutlineOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val state = rememberShuffleButtonState(player)
    val controlsVisibilityState = LocalControlsVisibilityState.current

    PlayerButton(
        modifier = modifier,
        isEnabled = onClick != null || state.isEnabled,
        isSelected = isSelected,
        label = label,
        shouldDimWhenUnselected = shouldDimWhenUnselected,
        shouldShowCustomizeFrame = shouldShowCustomizeFrame,
        isOutlineOnly = isOutlineOnly,
        onClick = {
            if (onClick != null) {
                onClick()
            } else {
                state.onClick()
                controlsVisibilityState?.showControls()
            }
        },
    ) {
        Icon(
            imageVector = if (state.shuffleOn) NextIcons.ShuffleOn else NextIcons.Shuffle,
            contentDescription = stringResource(R.string.shuffle),
        )
    }
}
