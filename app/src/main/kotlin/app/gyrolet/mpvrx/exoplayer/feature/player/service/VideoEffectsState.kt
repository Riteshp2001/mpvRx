package app.gyrolet.mpvrx.exoplayer.feature.player.service

import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority

internal data class VideoEffectsState(
    val filters: VideoFilterPreferences,
    val decoderPriority: DecoderPriority,
    val isPipelineInitialized: Boolean = false,
)
