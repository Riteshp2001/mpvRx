package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DoubleTapGesture {
    PLAY_PAUSE,
    FAST_FORWARD_AND_REWIND,
    BOTH,
    NONE,
}

