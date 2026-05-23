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
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import app.gyrolet.mpvrx.R

@OptIn(UnstableApi::class)
@Composable
fun PlayPauseButton(
    player: Player,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    label: String? = null,
    isInteractive: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val state = rememberPlayPauseButtonState(player)
    val icon = when (state.showPlay) {
        true -> NextIcons.Play
        false -> NextIcons.Pause
    }
    val contentDescription = when (state.showPlay) {
        true -> stringResource(R.string.play_pause)
        false -> stringResource(R.string.play_pause)
    }

    PlayerButton(
        modifier = modifier,
        buttonSize = 64.dp,
        isEnabled = state.isEnabled,
        isSelected = isSelected,
        label = label,
        isInteractive = isInteractive,
        onClick = onClick ?: state::onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(48.dp),
        )
    }
}
