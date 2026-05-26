package app.gyrolet.mpvrx.exoplayer.feature.player.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ExoPlayerStatsTracker {
    private val _videoFps = MutableStateFlow(0f)
    val videoFps: StateFlow<Float> = _videoFps.asStateFlow()

    private val _videoDecoderName = MutableStateFlow<String?>(null)
    val videoDecoderName: StateFlow<String?> = _videoDecoderName.asStateFlow()

    fun updateFps(fps: Float) {
        if (fps > 0f) _videoFps.value = fps
    }

    fun updateDecoderName(name: String?) {
        _videoDecoderName.value = name
    }

    fun reset() {
        _videoFps.value = 0f
        _videoDecoderName.value = null
    }
}
