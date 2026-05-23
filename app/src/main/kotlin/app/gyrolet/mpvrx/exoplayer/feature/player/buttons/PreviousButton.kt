package app.gyrolet.mpvrx.exoplayer.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import app.gyrolet.mpvrx.exoplayer.core.ui.designsystem.NextIcons
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.rememberPreviousButtonState
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.LocalControlsVisibilityState

@OptIn(UnstableApi::class)
@Composable
internal fun PreviousButton(
    player: Player,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    label: String? = null,
    isInteractive: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val state = rememberPreviousButtonState(player)
    val controlsVisibilityState = LocalControlsVisibilityState.current

    PlayerButton(
        modifier = modifier,
        buttonSize = 48.dp,
        isEnabled = state.isEnabled,
        isSelected = isSelected,
        label = label,
        isInteractive = isInteractive,
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
            imageVector = NextIcons.SkipPrevious,
            contentDescription = stringResource(R.string.player_controls_previous),
            modifier = Modifier.size(28.dp),
        )
    }
}
