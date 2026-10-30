package app.gyrolet.mpvrx.domain.seekpreview

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal object SeekPreviewPolicy {
  const val FRAME_DIMENSION = 320
  const val EXACT_TOLERANCE_MS = 1_000L
  const val NEARBY_TOLERANCE_MS = 6_000L

  fun targetTimeMs(targetSeconds: Float, durationSeconds: Float): Long? {
    if (!targetSeconds.isFinite() || !durationSeconds.isFinite() || durationSeconds <= 0f) return null
    val durationMs = (durationSeconds.toDouble() * 1_000.0).toLong()
    return (floor(targetSeconds.toDouble().coerceAtLeast(0.0)) * 1_000.0).toLong().coerceAtMost(durationMs)
  }

  fun windowOffsetPx(targetMs: Long, durationMs: Long, trackWidthPx: Int, windowWidthPx: Int): Int {
    if (durationMs <= 0L || trackWidthPx <= windowWidthPx) return 0
    val fraction = targetMs.toDouble().div(durationMs).coerceIn(0.0, 1.0)
    return (trackWidthPx * fraction - windowWidthPx / 2.0)
      .roundToInt()
      .coerceIn(0, trackWidthPx - windowWidthPx)
  }

  fun nearestTimeMs(targetMs: Long, sortedTimes: List<Long>, maxDistanceMs: Long): Long? {
    if (targetMs < 0L || maxDistanceMs < 0L || sortedTimes.isEmpty()) return null
    val index = sortedTimes.binarySearch(targetMs)
    if (index >= 0) return sortedTimes[index]
    val insertion = -index - 1
    return listOfNotNull(sortedTimes.getOrNull(insertion - 1), sortedTimes.getOrNull(insertion))
      .minByOrNull { abs(it - targetMs) }
      ?.takeIf { abs(it - targetMs) <= maxDistanceMs }
  }
}