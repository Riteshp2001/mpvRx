package app.gyrolet.mpvrx.ui.player.controls.components

internal data class SeekbarSegmentCornerRadii(
  val left: Float,
  val right: Float,
)

/**
 * Resolves the independent radii for a track segment.
 *
 * Thick seekbars use pill-shaped chapter segments, while standard seekbars keep
 * chapter boundaries square and only round the track and thumb-gap boundaries.
 */
internal fun seekbarSegmentCornerRadii(
  startX: Float,
  endX: Float,
  trackWidth: Float,
  outerRadius: Float,
  innerRadius: Float,
  thumbGapStart: Float,
  thumbGapEnd: Float,
  roundEverySegmentEdge: Boolean,
): SeekbarSegmentCornerRadii {
  val tolerance = 0.5f
  val isOuterLeft = startX <= tolerance
  val isOuterRight = endX >= trackWidth - tolerance
  val isThumbGapLeft = kotlin.math.abs(startX - thumbGapEnd) < tolerance
  val isThumbGapRight = kotlin.math.abs(endX - thumbGapStart) < tolerance

  return SeekbarSegmentCornerRadii(
    left = when {
      roundEverySegmentEdge || isOuterLeft -> outerRadius
      isThumbGapLeft -> innerRadius
      else -> 0f
    },
    right = when {
      roundEverySegmentEdge || isOuterRight -> outerRadius
      isThumbGapRight -> innerRadius
      else -> 0f
    },
  )
}
