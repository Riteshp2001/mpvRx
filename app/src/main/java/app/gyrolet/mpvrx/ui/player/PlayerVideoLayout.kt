/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class PlayerViewSize(
  val width: Int,
  val height: Int,
)

/** mpv's share of an aspect mode, applied to the player screen's own Surface only. */
internal data class SurfacePictureLayout(
  val panscan: Double = 0.0,
  /** mpv `video-aspect-override`, already corrected for crop and rotation; null keeps the picture's own. */
  val aspectOverride: Double? = null,
  val unscaled: Boolean = false,
)

/**
 * mpvRx's own aspect modes, measured on the real player area rather than the display. The Surface
 * stays the size of the player, so zoom can grow the picture into the black bars and subtitles
 * stay on screen; mpv is told exactly how to fit, cover, stretch or leave the picture unscaled.
 */
internal data class PlayerVideoLayout(
  /** mpv `panscan`: 1 makes the picture cover the player. */
  val panscan: Double = 0.0,
  /** Displayed width/height the picture is stretched to; null keeps its own aspect. */
  val stretchRatio: Double? = null,
  /** One video pixel per screen pixel (mpv `video-unscaled`). */
  val originalSize: Boolean = false,
) {
  companion object {
    /** A positive [forcedRatio], from a preset or a custom ratio, replaces [mode]. */
    fun calculate(
      mode: VideoAspect,
      forcedRatio: Double,
      containerWidth: Int,
      containerHeight: Int,
    ): PlayerVideoLayout {
      if (forcedRatio.isFinite() && forcedRatio > 0.0) return PlayerVideoLayout(stretchRatio = forcedRatio)
      return when (mode) {
        VideoAspect.Fit -> PlayerVideoLayout()
        VideoAspect.Crop -> PlayerVideoLayout(panscan = 1.0)
        VideoAspect.Stretch ->
          if (containerWidth > 0 && containerHeight > 0) {
            PlayerVideoLayout(stretchRatio = containerWidth.toDouble() / containerHeight)
          } else {
            PlayerVideoLayout()
          }
        VideoAspect.Original -> PlayerVideoLayout(originalSize = true)
      }
    }

    /** The largest [aspect] rectangle inside [width]x[height]. */
    fun fittedSize(
      aspect: Double,
      width: Double,
      height: Double,
    ): Pair<Double, Double> = if (width / height > aspect) height * aspect to height else width to width / aspect

    /** The smallest [aspect] rectangle covering [width]x[height]. */
    fun coveredSize(
      aspect: Double,
      width: Double,
      height: Double,
    ): Pair<Double, Double> = if (width / height > aspect) width to width / aspect else height * aspect to height

    /**
     * YouTube Ambient's player view for an unscaled picture of [pictureWidth]x[pictureHeight]:
     * its own size, within the container.
     */
    fun originalViewSize(
      pictureWidth: Double,
      pictureHeight: Double,
      containerWidth: Int,
      containerHeight: Int,
    ): PlayerViewSize =
      PlayerViewSize(
        centered(min(pictureWidth, containerWidth.toDouble()), containerWidth),
        centered(min(pictureHeight, containerHeight.toDouble()), containerHeight),
      )

    /** An odd difference to the container would put the extra pixel on one side only. */
    private fun centered(
      requested: Double,
      container: Int,
    ): Int {
      val size = requested.roundToInt().coerceIn(1, max(container, 1))
      return if (size > 1 && (container - size) % 2 != 0) size - 1 else size
    }
  }
}
