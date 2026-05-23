package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SubtitleColor {
    WHITE,
    YELLOW,
    CYAN,
    GREEN,
}

@Serializable
enum class SubtitleEdgeStyle {
    NONE,
    OUTLINE,
    DROP_SHADOW,
}

