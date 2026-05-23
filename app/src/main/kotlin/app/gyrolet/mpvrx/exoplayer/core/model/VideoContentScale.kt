package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class VideoContentScale {
    BEST_FIT,
    STRETCH,
    CROP,
    HUNDRED_PERCENT,
}

