package app.gyrolet.mpvrx.domain.thumbnail

import app.gyrolet.mpvrx.preferences.ThumbnailMode
import kotlin.math.roundToInt

fun ThumbnailMode.toThumbnailStrategy(framePositionPercent: Float): ThumbnailStrategy =
  when (this) {
    ThumbnailMode.Smart -> ThumbnailStrategy.Hybrid(0.33f)
    ThumbnailMode.FirstFrame -> ThumbnailStrategy.FirstFrame
    ThumbnailMode.FrameAtPosition ->
      ThumbnailStrategy.FrameAtPercentage((framePositionPercent / 100f).coerceIn(0f, 1f))
    ThumbnailMode.EmbeddedThumbnail -> ThumbnailStrategy.EmbeddedOrFirstFrame
    ThumbnailMode.OneThird -> ThumbnailStrategy.FrameAtPercentage(0.33f)
    ThumbnailMode.Halfway -> ThumbnailStrategy.FrameAtPercentage(0.5f)
  }

internal fun ThumbnailStrategy.prefersEmbeddedPicture(): Boolean = this is ThumbnailStrategy.EmbeddedOrFirstFrame

fun ThumbnailMode.thumbnailModeCacheKey(framePositionPercent: Float): String =
  when (this) {
    ThumbnailMode.FrameAtPosition ->
      "FrameAtPosition_${framePositionPercent.coerceIn(0f, 100f).roundToInt()}"
    ThumbnailMode.OneThird -> "FrameAtPosition_33"
    ThumbnailMode.Halfway -> "FrameAtPosition_50"
    else -> name
  }

