/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.roundToInt

internal data class BlackBarSource(
  val generation: Long,
  val trackId: Int,
  val width: Int,
  val height: Int,
  val rotation: Int,
)

/** Matte thickness on each edge, relative to the complete frame, in display orientation. */
internal data class VideoBlackBars(
  val top: Float,
  val bottom: Float,
  val left: Float,
  val right: Float,
) {
  val isEmpty: Boolean get() = top == 0f && bottom == 0f && left == 0f && right == 0f

  fun commonWith(other: VideoBlackBars): VideoBlackBars =
    VideoBlackBars(minOf(top, other.top), minOf(bottom, other.bottom), minOf(left, other.left), minOf(right, other.right))

  fun cropGeometry(source: BlackBarSource): String {
    // Screenshots honor rotation; video-crop takes coordinates before rotation and PAR correction.
    val unrotated = when ((source.rotation % 360 + 360) % 360) {
      90 -> VideoBlackBars(right, left, top, bottom)
      180 -> VideoBlackBars(bottom, top, right, left)
      270 -> VideoBlackBars(left, right, bottom, top)
      else -> this
    }
    val x = (source.width * unrotated.left).roundToInt()
    val y = (source.height * unrotated.top).roundToInt()
    val width = source.width - x - (source.width * unrotated.right).roundToInt()
    val height = source.height - y - (source.height * unrotated.bottom).roundToInt()
    return "${width}x${height}+$x+$y"
  }
}

/** A null result is an inconclusive frame, not a vote for or against a crop. */
internal fun measureBlackBars(pixels: IntArray, width: Int, height: Int): VideoBlackBars? {
  if (width < 8 || height < 8) return null
  val rows = IntArray(height)
  val columns = IntArray(width)
  var brightPixels = 0
  for (y in 0 until height) {
    for (x in 0 until width) {
      val pixel = pixels[y * width + x]
      val red = pixel ushr 16 and 255
      val green = pixel ushr 8 and 255
      val blue = pixel and 255
      val luma = (77 * red + 150 * green + 29 * blue) shr 8
      if (luma >= 40) brightPixels++
      // Treat saturated colors as picture even if their luma is close to black.
      val brightness = maxOf(luma, maxOf(red, green, blue) / 2)
      rows[y] = maxOf(rows[y], brightness)
      columns[x] = maxOf(columns[x], brightness)
    }
  }
  // Fades and black title cards with just a few bright pixels cannot establish a matte.
  if (brightPixels * 32 < width * height) return null

  fun edge(lines: IntArray, fromEnd: Boolean): Float {
    val indices = if (fromEnd) lines.indices.reversed() else lines.indices
    var darkLines = 0
    for (index in indices) {
      if (lines[index] > 12) break
      darkLines++
    }
    val fraction = darkLines.toFloat() / lines.size
    return fraction.takeIf { it in 0.015f..0.35f } ?: 0f
  }
  return VideoBlackBars(edge(rows, false), edge(rows, true), edge(columns, false), edge(columns, true))
}
