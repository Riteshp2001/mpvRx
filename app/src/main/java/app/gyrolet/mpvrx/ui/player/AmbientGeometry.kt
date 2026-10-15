/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player

import kotlin.math.pow

/**
 * The picture rectangle, before clipping to the viewport. Ambient samples the same frame only
 * outside this rectangle; black pixels inside the movie are never mistaken for letterboxing.
 * Native scale opens a full-size OUTPUT canvas. The shader restores the picture, including its
 * zoom and pan, while mpv composites subtitles afterwards in ordinary screen coordinates.
 */
internal data class AmbientGeometry(
  val canvasScaleX: Double,
  val canvasScaleY: Double,
  val picture: AmbientRenderContext,
  val hasBars: Boolean,
) {
  companion object {
    fun calculate(
      width: Int,
      height: Int,
      aspect: Double,
      panscan: Double = 0.0,
      zoom: Double = 0.0,
      panX: Double = 0.0,
      panY: Double = 0.0,
      alignX: Double = 0.0,
      alignY: Double = 0.0,
      recenter: Boolean = false,
    ): AmbientGeometry? {
      if (width <= 0 || height <= 0 || !aspect.isFinite() || aspect <= 0.0 ||
        listOf(panscan, zoom, panX, panY, alignX, alignY).any { !it.isFinite() }
      ) return null
      val w = width.toDouble()
      val h = height.toDouble()
      val fitWidth = minOf(w, h * aspect)
      val fitHeight = fitWidth / aspect
      val fill = maxOf(w / fitWidth, h / fitHeight)
      val scan = 1.0 + (fill - 1.0) * panscan.coerceIn(0.0, 1.0)
      val baseWidth = fitWidth * scan
      val baseHeight = fitHeight * scan
      val multiplier = 2.0.pow(zoom)
      val pictureWidth = baseWidth * multiplier
      val pictureHeight = baseHeight * multiplier
      if (!pictureWidth.isFinite() || !pictureHeight.isFinite() ||
        pictureWidth < 1.0 || pictureHeight < 1.0
      ) return null
      val effectiveAlignX = if (recenter && pictureWidth <= w) 0.0 else alignX
      val effectiveAlignY = if (recenter && pictureHeight <= h) 0.0 else alignY
      val left = (w - pictureWidth) * (effectiveAlignX + 1.0) / 2.0 + panX * pictureWidth
      val top = (h - pictureHeight) * (effectiveAlignY + 1.0) / 2.0 + panY * pictureHeight
      // Ignore subpixel rounding at the fitted edges; an exact-fit video needs no Ambient pass.
      val hasBars = left >= 1.0 || top >= 1.0 ||
        left + pictureWidth <= w - 1.0 || top + pictureHeight <= h - 1.0
      return AmbientGeometry(
        canvasScaleX = w / baseWidth,
        canvasScaleY = h / baseHeight,
        picture = AmbientRenderContext(
          scaleX = w / pictureWidth,
          scaleY = h / pictureHeight,
          centerX = (left + pictureWidth / 2.0) / w,
          centerY = (top + pictureHeight / 2.0) / h,
        ),
        hasBars = hasBars,
      )
    }
  }
}
