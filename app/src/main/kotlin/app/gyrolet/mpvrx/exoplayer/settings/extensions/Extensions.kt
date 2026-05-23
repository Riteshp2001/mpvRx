package app.gyrolet.mpvrx.exoplayer.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.exoplayer.core.model.ControlButtonsPosition
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority
import app.gyrolet.mpvrx.exoplayer.core.model.DoubleTapGesture
import app.gyrolet.mpvrx.exoplayer.core.model.Font
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerControlsStyle
import app.gyrolet.mpvrx.exoplayer.core.model.PlayerIconStyle
import app.gyrolet.mpvrx.exoplayer.core.model.Resume
import app.gyrolet.mpvrx.exoplayer.core.model.ScreenOrientation
import app.gyrolet.mpvrx.R

@Composable
fun ScreenOrientation.name(): String {
    val stringRes = when (this) {
        ScreenOrientation.AUTOMATIC -> R.string.automatic
        ScreenOrientation.LANDSCAPE -> R.string.landscape
        ScreenOrientation.LANDSCAPE_REVERSE -> R.string.landscape_reverse
        ScreenOrientation.LANDSCAPE_AUTO -> R.string.landscape_auto
        ScreenOrientation.PORTRAIT -> R.string.portrait
        ScreenOrientation.VIDEO_ORIENTATION -> R.string.video_orientation
    }
    return stringResource(id = stringRes)
}

@Composable
fun PlayerControlsStyle.name(): String {
    val stringRes = when (this) {
        PlayerControlsStyle.LEGACY -> R.string.player_controls_style_legacy
        PlayerControlsStyle.MODERN -> R.string.player_controls_style_modern
    }
    return stringResource(stringRes)
}

@Composable
fun PlayerIconStyle.name(): String {
    val stringRes = when (this) {
        PlayerIconStyle.TONAL -> R.string.player_icon_style_tonal
        PlayerIconStyle.CLASSIC -> R.string.player_icon_style_classic
        PlayerIconStyle.TRANSLUCENT -> R.string.player_icon_style_translucent
    }
    return stringResource(stringRes)
}

@Composable
fun ControlButtonsPosition.name(): String {
    val stringRes = when (this) {
        ControlButtonsPosition.LEFT -> R.string.control_buttons_alignment_left
        ControlButtonsPosition.RIGHT -> R.string.control_buttons_alignment_right
    }
    return stringResource(stringRes)
}

@Composable
fun DoubleTapGesture.name(): String {
    val stringRes = when (this) {
        DoubleTapGesture.NONE -> R.string.off
        DoubleTapGesture.FAST_FORWARD_AND_REWIND -> R.string.ff_rewind
        DoubleTapGesture.PLAY_PAUSE -> R.string.play_pause
        DoubleTapGesture.BOTH -> R.string.play_pause_ff_rewind
    }
    return stringResource(stringRes)
}

@Composable
fun DecoderPriority.name(): String {
    val stringRes = when (this) {
        DecoderPriority.AUTOMATIC -> R.string.auto
        DecoderPriority.AUTOMATIC_PREFER_DEVICE -> R.string.auto_hw_decoder
        DecoderPriority.DEVICE_ONLY -> R.string.device_decoders_only
        DecoderPriority.PREFER_DEVICE -> R.string.prefer_device_decoders
        DecoderPriority.PREFER_APP -> R.string.prefer_app_decoders
    }
    return stringResource(stringRes)
}

@Composable
fun Font.name(): String {
    val stringRes = when (this) {
        Font.DEFAULT -> R.string.default_name
        Font.SANS_SERIF -> R.string.sans_serif
        Font.SERIF -> R.string.serif
        Font.MONOSPACE -> R.string.monospace
    }
    return stringResource(stringRes)
}

val Resume.isEnabled: Boolean
    get() = this == Resume.YES
