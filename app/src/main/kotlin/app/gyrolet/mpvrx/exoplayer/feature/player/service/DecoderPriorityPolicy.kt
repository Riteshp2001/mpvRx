package app.gyrolet.mpvrx.exoplayer.feature.player.service

import androidx.media3.exoplayer.DefaultRenderersFactory
import app.gyrolet.mpvrx.exoplayer.core.model.DecoderPriority

internal fun DecoderPriority.extensionRendererMode(): Int = when (this) {
    DecoderPriority.AUTOMATIC -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    DecoderPriority.AUTOMATIC_PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
}

internal fun DecoderPriority.logName(): String = when (this) {
    DecoderPriority.AUTOMATIC -> "AUTO_HW"
    DecoderPriority.AUTOMATIC_PREFER_DEVICE -> "AUTO_HW_PLUS"
    DecoderPriority.DEVICE_ONLY -> "HW"
    DecoderPriority.PREFER_DEVICE -> "HW+"
    DecoderPriority.PREFER_APP -> "SW"
}

internal fun DecoderPriority.shouldEnableDecoderFallback(): Boolean = this != DecoderPriority.DEVICE_ONLY

internal fun DecoderPriority.shouldRetryWithSoftwareDecoder(): Boolean = when (this) {
    DecoderPriority.AUTOMATIC,
    DecoderPriority.AUTOMATIC_PREFER_DEVICE,
    DecoderPriority.PREFER_DEVICE,
    -> true
    DecoderPriority.DEVICE_ONLY,
    DecoderPriority.PREFER_APP,
    -> false
}

internal fun DecoderPriority.shouldUseAudioExtensionFallback(): Boolean = when (this) {
    DecoderPriority.AUTOMATIC,
    DecoderPriority.AUTOMATIC_PREFER_DEVICE,
    -> true
    DecoderPriority.DEVICE_ONLY,
    DecoderPriority.PREFER_DEVICE,
    DecoderPriority.PREFER_APP,
    -> false
}
