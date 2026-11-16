/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** A zoom level pinch-zoom snaps to. [zoom] uses mpv's base-2 `video-zoom` scale. */
data class VideoZoomStop(
  val zoom: Float,
  val label: String,
  val major: Boolean,
)

/**
 * Zoom levels with a meaning: the picture as the aspect mode shows it, crops to common ratios,
 * Fill (no black bars left on screen) and, for a picture larger than the screen, Fit.
 */
internal object VideoZoomStops {
  // Pinch zoom is clamped to this range (50% to 800%).
  const val MIN_ZOOM = -1f
  const val MAX_ZOOM = 3f

  // About 3% either side of a stop pulls the zoom onto it.
  private const val SNAP_RANGE = 0.045f
  private const val MIN_GAP = SNAP_RANGE * 2f
  private const val EPSILON = 0.01

  private val ratios =
    listOf(
      9.0 / 16.0 to "9:16",
      1.0 to "1:1",
      4.0 / 3.0 to "4:3",
      16.0 / 9.0 to "16:9",
      18.0 / 9.0 to "18:9",
      20.0 / 9.0 to "20:9",
      21.0 / 9.0 to "21:9",
      2.39 to "2.39:1",
    )

  /**
   * Stops for a picture of [pictureWidth]x[pictureHeight] at zoom 0 that can grow up to
   * [boundsWidth]x[boundsHeight] before it is clipped; both share their centre.
   */
  fun calculate(
    pictureWidth: Double,
    pictureHeight: Double,
    boundsWidth: Double,
    boundsHeight: Double,
    baseLabel: String,
    fitLabel: String,
    fillLabel: String,
  ): List<VideoZoomStop> {
    val stops = mutableListOf(VideoZoomStop(0f, baseLabel, major = true))
    if (listOf(pictureWidth, pictureHeight, boundsWidth, boundsHeight).any { !it.isFinite() || it <= 0.0 }) {
      return stops
    }
    val pictureAspect = pictureWidth / pictureHeight
    val boundsAspect = boundsWidth / boundsHeight
    val fill = max(boundsWidth / pictureWidth, boundsHeight / pictureHeight)
    val fit = min(boundsWidth / pictureWidth, boundsHeight / pictureHeight)
    if (fit < 1.0 - EPSILON) stops += VideoZoomStop(log2(fit), fitLabel, major = true)
    if (fill > 1.0 + EPSILON) {
      // Past the first clipped side, the visible part of the picture widens (or narrows) from
      // the picture's own aspect to the screen's; each ratio in between is one zoom level.
      ratios.forEach { (ratio, label) ->
        val between =
          if (pictureAspect < boundsAspect) ratio > pictureAspect && ratio < boundsAspect
          else ratio < pictureAspect && ratio > boundsAspect
        if (!between) return@forEach
        val multiplier =
          if (pictureAspect < boundsAspect) ratio * boundsHeight / pictureWidth
          else boundsWidth / (ratio * pictureHeight)
        if (multiplier > 1.0 + EPSILON && multiplier < fill - EPSILON) {
          stops += VideoZoomStop(log2(multiplier), label, major = false)
        }
      }
      stops += VideoZoomStop(log2(fill), fillLabel, major = true)
    }
    return stops
      .filter { it.zoom in MIN_ZOOM..MAX_ZOOM }
      .sortedBy(VideoZoomStop::zoom)
      .fold(mutableListOf<VideoZoomStop>()) { kept, stop ->
        val previous = kept.lastOrNull()
        when {
          previous == null || stop.zoom - previous.zoom >= MIN_GAP -> kept += stop
          // Too close to tell apart: Fit and Fill win over a ratio.
          stop.major && !previous.major -> kept[kept.lastIndex] = stop
        }
        kept
      }
  }

  /** "16:9" for a common ratio, otherwise "1.85:1". */
  fun ratioLabel(ratio: Double): String =
    (ratios + listOf(16.0 / 10.0 to "16:10", 32.0 / 9.0 to "32:9", 2.35 to "2.35:1"))
      .firstOrNull { abs(it.first - ratio) < 0.01 }
      ?.second
      ?: String.format(java.util.Locale.ROOT, "%.2f:1", ratio)

  /** The stop a raw pinch zoom is pulled onto, if it is close enough to one. */
  fun snap(
    zoom: Float,
    stops: List<VideoZoomStop>,
  ): VideoZoomStop? =
    stops.minByOrNull { abs(it.zoom - zoom) }?.takeIf { abs(it.zoom - zoom) <= SNAP_RANGE }

  private fun log2(value: Double): Float = (ln(value) / ln(2.0)).toFloat()
}
