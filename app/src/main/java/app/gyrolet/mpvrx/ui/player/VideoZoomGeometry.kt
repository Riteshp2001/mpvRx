/* SPDX-License-Identifier: AGPL-3.0-or-later */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.log2

fun videoZoomMultiplier(zoom: Float): Float = 2f.pow(zoom.coerceIn(-1f, 3f))

private const val ZOOM_SNAP_ENTER = 0.045f
private const val ZOOM_SNAP_RELEASE = 0.075f

/** Fit and screen-fill stops calculated from geometry alone, without capturing video frames. */
internal fun videoZoomStops(
  viewportAspect: Double,
  videoAspect: Double,
  panscan: Double,
): List<Float> {
  if (!viewportAspect.isFinite() || viewportAspect <= 0 || !videoAspect.isFinite() || videoAspect <= 0) return emptyList()
  val widthScale = maxOf(1.0, viewportAspect / videoAspect)
  val heightScale = maxOf(1.0, videoAspect / viewportAspect)
  val fillScale = maxOf(widthScale, heightScale)
  val baseScale = 1 + (fillScale - 1) * panscan.coerceIn(0.0, 1.0)
  val scales = listOf(1.0, fillScale)
  return scales.map { log2(it / baseScale).toFloat() }.filter { it in -1f..3f }.distinct()
}

internal fun nearestVideoZoomStop(zoom: Float, stops: List<Float>, heldStop: Float? = null): Float? {
  if (heldStop != null && abs(zoom - heldStop) <= ZOOM_SNAP_RELEASE) return heldStop
  return stops.minByOrNull { abs(zoom - it) }?.takeIf { abs(zoom - it) <= ZOOM_SNAP_ENTER }
}

/** mpv pan values are fractions of the scaled video, while touch gestures use pixels. */
fun videoPanFractions(
  width: Float,
  height: Float,
  aspect: Float,
  zoom: Float,
  panX: Float,
  panY: Float,
): Pair<Float, Float> {
  if (width <= 0f || height <= 0f || aspect <= 0f) return 0f to 0f
  val fittedWidth = minOf(width, height * aspect)
  val fittedHeight = minOf(height, width / aspect)
  val scale = videoZoomMultiplier(zoom)
  return panX / (fittedWidth * scale) to panY / (fittedHeight * scale)
}
