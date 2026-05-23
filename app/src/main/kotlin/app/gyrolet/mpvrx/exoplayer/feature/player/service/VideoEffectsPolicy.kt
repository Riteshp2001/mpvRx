package app.gyrolet.mpvrx.exoplayer.feature.player.service

import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority

internal fun shouldApplyVideoEffects(decoderPriority: DecoderPriority): Boolean = decoderPriority != DecoderPriority.PREFER_APP
