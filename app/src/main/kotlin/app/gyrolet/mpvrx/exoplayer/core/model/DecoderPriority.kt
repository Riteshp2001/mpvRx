package app.gyrolet.mpvrx.exoplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class DecoderPriority {
    AUTOMATIC,
    AUTOMATIC_PREFER_DEVICE,
    DEVICE_ONLY,
    PREFER_DEVICE,
    PREFER_APP,
}

