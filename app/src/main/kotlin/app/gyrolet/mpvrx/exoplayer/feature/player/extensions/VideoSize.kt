package app.gyrolet.mpvrx.exoplayer.feature.player.extensions

import androidx.media3.common.VideoSize


val VideoSize.isPortrait: Boolean
    get() = height >= width
