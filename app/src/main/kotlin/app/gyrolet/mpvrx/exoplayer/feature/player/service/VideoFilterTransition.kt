package app.gyrolet.mpvrx.exoplayer.feature.player.service

import android.os.SystemClock

internal data class VideoFilterTransition(
    val startFilters: VideoFilterPreferences,
    val targetFilters: VideoFilterPreferences,
    val startMs: Long,
) {
    fun currentFilters(
        currentMs: Long,
        durationMs: Long,
    ): VideoFilterPreferences = startFilters.interpolateTo(
        target = targetFilters,
        fraction = progress(currentMs, durationMs),
    )

    fun progress(
        currentMs: Long,
        durationMs: Long,
    ): Float {
        if (startFilters == targetFilters) return 1f
        return ((currentMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun to(
        targetFilters: VideoFilterPreferences,
        startMs: Long,
        durationMs: Long,
    ): VideoFilterTransition = VideoFilterTransition(
        startFilters = currentFilters(
            currentMs = startMs,
            durationMs = durationMs,
        ),
        targetFilters = targetFilters,
        startMs = startMs,
    )

    companion object {
        fun default(): VideoFilterTransition {
            val defaultFilters = VideoFilterPreferences.default()
            return VideoFilterTransition(
                startFilters = defaultFilters,
                targetFilters = defaultFilters,
                startMs = SystemClock.elapsedRealtime(),
            )
        }
    }
}
