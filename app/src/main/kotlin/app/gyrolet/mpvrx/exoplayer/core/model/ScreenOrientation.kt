package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ScreenOrientation {
    AUTOMATIC,
    LANDSCAPE,
    LANDSCAPE_REVERSE,
    LANDSCAPE_AUTO,
    PORTRAIT,
    VIDEO_ORIENTATION,
}

@Serializable
enum class LastPlayerScreenOrientation {
    PORTRAIT,
    LANDSCAPE,
}

