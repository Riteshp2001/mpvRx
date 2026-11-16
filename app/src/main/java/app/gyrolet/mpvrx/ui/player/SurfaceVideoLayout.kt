/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.roundToInt

/**
 * The video Surface of FongMi's host-owned layout: a [width]x[height] rectangle centred in the
 * player, fitted to the video aspect and panscan, then scaled by [scale] and moved by the pan.
 */
internal data class SurfaceVideoLayout(
  val width: Int,
  val height: Int,
  val scale: Float,
  val translationX: Float,
  val translationY: Float,
) {
  /** `android-video-geometry` for a [viewWidth]x[viewHeight] subtitle overlay. */
  fun geometryCommand(
    viewWidth: Int,
    viewHeight: Int,
  ): Array<String> {
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    val left = (viewWidth / 2f + translationX - scaledWidth / 2f).roundToInt()
    val top = (viewHeight / 2f + translationY - scaledHeight / 2f).roundToInt()
    return arrayOf(
      "android-video-geometry",
      viewWidth.toString(),
      viewHeight.toString(),
      left.toString(),
      top.toString(),
      (left + scaledWidth).toString(),
      (top + scaledHeight).toString(),
    )
  }

  companion object {
    fun calculate(
      viewWidth: Int,
      viewHeight: Int,
      videoAspect: Double,
      panscan: Double,
      zoomMultiplier: Float,
      panX: Float,
      panY: Float,
    ): SurfaceVideoLayout? {
      if (viewWidth <= 0 || viewHeight <= 0 || !videoAspect.isFinite() || videoAspect <= 0.0) return null
      val wider = videoAspect > viewWidth.toDouble() / viewHeight
      val fitWidth = if (wider) viewWidth.toDouble() else viewHeight * videoAspect
      val fitHeight = if (wider) viewWidth / videoAspect else viewHeight.toDouble()
      val fillWidth = if (wider) viewHeight * videoAspect else viewWidth.toDouble()
      val fillHeight = if (wider) viewHeight.toDouble() else viewWidth / videoAspect
      // mpv's panscan moves between fitting inside the player (0) and filling it (1).
      val crop = panscan.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
      return SurfaceVideoLayout(
        width = (fitWidth + (fillWidth - fitWidth) * crop).roundToInt().coerceAtLeast(1),
        height = (fitHeight + (fillHeight - fitHeight) * crop).roundToInt().coerceAtLeast(1),
        scale = zoomMultiplier,
        translationX = panX,
        translationY = panY,
      )
    }
  }
}
