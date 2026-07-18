package app.gyrolet.mpvrx.ui.player.controls.components

internal data class SeekbarTrackSegment(
  val start: Float,
  val end: Float,
)

/**
 * Splits a track around chapter boundaries and any extra visual gaps, merging
 * overlapping gaps so every seekbar style gets identical chapter geometry.
 */
internal fun seekbarTrackSegments(
  chapterStarts: List<Float>,
  duration: Float,
  trackWidth: Float,
  chapterGapHalf: Float,
  extraGaps: List<Pair<Float, Float>> = emptyList(),
): List<SeekbarTrackSegment> {
  if (trackWidth <= 0f) return emptyList()

  val gaps = buildList {
    if (duration > 0f) {
      chapterStarts.forEach { chapterStart ->
        if (!chapterStart.isFinite()) return@forEach
        val center = (chapterStart / duration).coerceIn(0f, 1f) * trackWidth
        if (center > chapterGapHalf && center < trackWidth - chapterGapHalf) {
          add(center - chapterGapHalf to center + chapterGapHalf)
        }
      }
    }
    extraGaps.forEach { (start, end) ->
      val safeStart = start.coerceIn(0f, trackWidth)
      val safeEnd = end.coerceIn(0f, trackWidth)
      if (safeEnd > safeStart) add(safeStart to safeEnd)
    }
  }.sortedBy(Pair<Float, Float>::first)

  val segments = mutableListOf<SeekbarTrackSegment>()
  var cursor = 0f
  gaps.forEach { (gapStart, gapEnd) ->
    if (gapStart > cursor) {
      segments += SeekbarTrackSegment(cursor, gapStart)
    }
    cursor = maxOf(cursor, gapEnd)
  }
  if (cursor < trackWidth) {
    segments += SeekbarTrackSegment(cursor, trackWidth)
  }

  return segments
}
