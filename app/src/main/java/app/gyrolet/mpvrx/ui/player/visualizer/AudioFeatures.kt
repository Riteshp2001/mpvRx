package app.gyrolet.mpvrx.ui.player.visualizer

class AudioFeatures {
    @Volatile var energy: Float = 0f
    @Volatile var bass: Float = 0f
    @Volatile var mid: Float = 0f
    @Volatile var treble: Float = 0f
    @Volatile var beat: Float = 0f
    @Volatile var centroid: Float = 0.35f
    @Volatile var active: Boolean = false
    @Volatile var lastCaptureNanos: Long = 0L

    fun markCaptureReceived() {
        lastCaptureNanos = System.nanoTime()
        active = true
    }

    fun markCaptureStarted() {
        lastCaptureNanos = System.nanoTime()
        active = false
    }

    fun hasRecentCapture(maxAgeNanos: Long): Boolean {
        val capturedAt = lastCaptureNanos
        return capturedAt != 0L && System.nanoTime() - capturedAt <= maxAgeNanos
    }

    fun reset() {
        energy = 0f
        bass = 0f
        mid = 0f
        treble = 0f
        beat = 0f
        centroid = 0.35f
        active = false
        lastCaptureNanos = 0L
    }

    fun decay(factor: Float, beatFactor: Float = factor) {
        bass *= factor
        mid *= factor
        treble *= factor
        energy *= factor
        beat *= beatFactor
        active = false
    }
}
