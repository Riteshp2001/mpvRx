package app.gyrolet.mpvrx.exoplayer.feature.player.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.exoplayer.feature.player.extensions.getName
import app.gyrolet.mpvrx.exoplayer.feature.player.state.rememberTracksState

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.AudioTrackSelectorView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    player: Player,
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        shouldShow = shouldShow,
        title = stringResource(R.string.select_audio_track),
    ) {
        AudioTrackSelectorContent(
            player = player,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AudioTrackSelectorContent(
    player: Player,
    onDismiss: () -> Unit,
) {
    val audioTracksState = rememberTracksState(player, C.TRACK_TYPE_AUDIO)
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 24.dp)
            .selectableGroup(),
    ) {
        audioTracksState.tracks.forEachIndexed { index, track ->
            RadioButtonRow(
                isSelected = track.isSelected,
                text = track.mediaTrackGroup.getName(C.TRACK_TYPE_AUDIO, index),
                onClick = {
                    audioTracksState.switchTrack(index)
                    onDismiss()
                },
            )
        }
        RadioButtonRow(
            isSelected = audioTracksState.tracks.none { it.isSelected },
            text = stringResource(R.string.disable),
            onClick = {
                audioTracksState.switchTrack(-1)
                onDismiss()
            },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.SubtitleTrackSelectorView(
    modifier: Modifier = Modifier,
    shouldShow: Boolean,
    player: Player,
    onDismiss: () -> Unit,
) {
    OverlayView(
        modifier = modifier,
        shouldShow = shouldShow,
        title = stringResource(R.string.select_subtitle_track),
    ) {
        SubtitleTrackSelectorContent(
            player = player,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun SubtitleTrackSelectorContent(
    player: Player,
    onDismiss: () -> Unit,
) {
    val subtitleTracksState = rememberTracksState(player, C.TRACK_TYPE_TEXT)
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .padding(horizontal = 24.dp)
            .selectableGroup(),
    ) {
        subtitleTracksState.tracks.forEachIndexed { index, track ->
            RadioButtonRow(
                isSelected = track.isSelected,
                text = track.mediaTrackGroup.getName(C.TRACK_TYPE_TEXT, index),
                onClick = {
                    subtitleTracksState.switchTrack(index)
                    onDismiss()
                },
            )
        }
        RadioButtonRow(
            isSelected = subtitleTracksState.tracks.none { it.isSelected },
            text = stringResource(R.string.disable),
            onClick = {
                subtitleTracksState.switchTrack(-1)
                onDismiss()
            },
        )
    }
}
