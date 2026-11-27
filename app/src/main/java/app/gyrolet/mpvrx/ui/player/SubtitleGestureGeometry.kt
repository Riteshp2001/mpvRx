// SPDX-License-Identifier: AGPL-3.0-or-later

package app.gyrolet.mpvrx.ui.player

import kotlin.math.abs
import kotlin.math.ceil

enum class SubtitleGestureTarget(
  val prefix: String,
  val selectionProperty: String,
) {
  Primary("sub", "sid"),
  Secondary("secondary-sub", "secondary-sid"),
}

/** An estimate: libmpv does not expose the rendered libass glyph bounds. */
data class SubtitleGestureRegion(
  val target: SubtitleGestureTarget,
  val trackId: Int,
  val position: Float,
  val scale: Float,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val positionHeight: Float,
) {
  /** Maps surface coordinates through a centre-pivot SurfaceView zoom and pan into screen coordinates. */
  fun zoomedAroundCenter(
    scale: Float,
    panX: Float,
    panY: Float,
    width: Float,
    height: Float,
  ): SubtitleGestureRegion {
    fun x(v: Float) = width / 2f + (v - width / 2f) * scale + panX

    fun y(v: Float) = height / 2f + (v - height / 2f) * scale + panY
    return copy(
      left = x(left),
      right = x(right),
      top = y(top),
      bottom = y(bottom),
      positionHeight =
        positionHeight * scale,
    )
  }

  fun contains(
    x: Float,
    y: Float,
    padding: Float = 0f,
  ): Boolean = x >= left - padding && x <= right + padding && y >= top - padding && y <= bottom + padding
}

/** Prefer a glyph area over a padded hit, then the nearest track when dual subtitles overlap. */
fun findSubtitleGestureTarget(
  regions: List<SubtitleGestureRegion>,
  x: Float,
  y: Float,
  touchPadding: Float,
): SubtitleGestureRegion? =
  regions
    .filter { it.contains(x, y, touchPadding) }
    .minWithOrNull(
      compareBy<SubtitleGestureRegion> { !it.contains(x, y) }
        .thenBy { abs(y - (it.top + it.bottom) / 2f) },
    )

// Android's ICU regex rejects an unescaped '}', so both braces must be escaped.
private val SUBTITLE_MARKUP_REGEX = Regex("<[^>]*>|\\{[^}]*\\}")

/** Use the rendered height, not a device-dependent number of pixels per percentage point. */
fun draggedSubtitlePosition(
  start: Float,
  deltaY: Float,
  positionHeight: Float,
): Float =
  if (positionHeight > 0f && deltaY.isFinite()) {
    (start + deltaY * 100f / positionHeight).coerceIn(0f, 150f)
  } else {
    start
  }

fun estimateSubtitleGestureRegion(
  target: SubtitleGestureTarget,
  trackId: Int,
  text: String,
  position: Float,
  scale: Float,
  screenWidth: Float,
  screenHeight: Float,
  renderTop: Float = 0f,
  renderHeight: Float = screenHeight,
  fontSize: Float = 55f,
  marginX: Float = 19f,
  marginY: Float = 34f,
  fontReferenceHeight: Float = screenHeight,
): SubtitleGestureRegion? {
  if (trackId <= 0 || text.isBlank() || screenWidth <= 0f || screenHeight <= 0f || renderHeight <= 0f) return null
  val plainText = text.replace(SUBTITLE_MARKUP_REGEX, "").replace("\\N", "\n")
  if (plainText.isBlank()) return null
  val pixelScale = fontReferenceHeight / 720f
  val charWidth = (fontSize * scale * pixelScale * 0.55f).coerceAtLeast(1f)
  val availableWidth = (screenWidth - 2f * marginX * pixelScale).coerceAtLeast(1f)
  val charsPerLine = (availableWidth / charWidth).coerceAtLeast(1f)
  val lines = plainText.lines()
  val lineCount = lines.sumOf { ceil(it.length / charsPerLine).toInt().coerceAtLeast(1) }
  val width = ((lines.maxOfOrNull { it.length } ?: 1) * charWidth).coerceIn(1f, availableWidth)
  val height = (lineCount * fontSize * scale * pixelScale * 1.3f).coerceIn(1f, screenHeight)
  val positionHeight = (renderHeight - marginY * pixelScale).coerceAtLeast(1f)
  // libass keeps raised subtitles below the top edge, even at sub-pos=0.
  val bottom = (renderTop + positionHeight * position / 100f).coerceAtLeast(renderTop + height)
  val top = (bottom - height).coerceAtLeast(0f)
  if (top >= screenHeight || bottom <= 0f) return null
  return SubtitleGestureRegion(
    target,
    trackId,
    position,
    scale,
    (screenWidth - width) / 2f,
    top,
    (screenWidth + width) / 2f,
    bottom.coerceAtMost(screenHeight),
    positionHeight,
  )
}
