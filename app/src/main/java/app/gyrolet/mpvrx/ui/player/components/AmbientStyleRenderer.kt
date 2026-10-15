/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.gyrolet.mpvrx.ui.player.AmbientStyle
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Rect as ComposeRect

@Immutable
data class AmbientRenderParams(
  val style: AmbientStyle,
  val spread: Float,
  val intensity: Float,
  val saturation: Float,
  val vignette: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
  val edgeBlend: Float,
)

fun ambientBlurRadiusFor(
  style: AmbientStyle,
  blurSamples: Int,
): Int =
  when (style) {
    AmbientStyle.Glow -> (blurSamples / 6).coerceIn(1, 4)
    AmbientStyle.Cinema -> (blurSamples / 8).coerceIn(0, 3)
    AmbientStyle.Echo -> (blurSamples / 6).coerceIn(0, 2)
    AmbientStyle.Mirror -> (blurSamples / 4).coerceIn(0, 2)
    AmbientStyle.YouTube -> AMBIENT_DEFAULT_BLUR_RADIUS
  }

private enum class AmbientBarSide { Left, Right, Top, Bottom }

private class AmbientBar(
  val side: AmbientBarSide,
  val rect: ComposeRect,
) {
  val horizontal: Boolean get() = side == AmbientBarSide.Left || side == AmbientBarSide.Right
  val thickness: Float get() = if (horizontal) rect.width else rect.height
}

private const val MIN_BAR_PX = 1f
private const val FADE_STOPS = 9

private fun ambientBars(
  canvas: Size,
  video: ComposeRect,
): List<AmbientBar> =
  buildList {
    val hasSides = video.left >= MIN_BAR_PX || canvas.width - video.right >= MIN_BAR_PX
    if (video.left >= MIN_BAR_PX) {
      add(AmbientBar(AmbientBarSide.Left, ComposeRect(0f, 0f, video.left, canvas.height)))
    }
    if (canvas.width - video.right >= MIN_BAR_PX) {
      add(AmbientBar(AmbientBarSide.Right, ComposeRect(video.right, 0f, canvas.width, canvas.height)))
    }
    val spanLeft = if (hasSides) video.left else 0f
    val spanRight = if (hasSides) video.right else canvas.width
    if (video.top >= MIN_BAR_PX) {
      add(AmbientBar(AmbientBarSide.Top, ComposeRect(spanLeft, 0f, spanRight, video.top)))
    }
    if (canvas.height - video.bottom >= MIN_BAR_PX) {
      add(AmbientBar(AmbientBarSide.Bottom, ComposeRect(spanLeft, video.bottom, spanRight, canvas.height)))
    }
  }

private fun styleBaseIntensity(style: AmbientStyle): Float =
  when (style) {
    AmbientStyle.Glow -> 0.75f
    AmbientStyle.Cinema -> 0.72f
    AmbientStyle.Echo -> 0.85f
    AmbientStyle.Mirror -> 0.55f
    AmbientStyle.YouTube -> 1f
  }

private fun styleBaseSaturation(style: AmbientStyle): Float =
  when (style) {
    AmbientStyle.Cinema -> 1.08f
    AmbientStyle.Echo -> 1.15f
    AmbientStyle.Mirror -> 0.92f
    else -> 1f
  }

private fun styleReach(params: AmbientRenderParams): Float {
  val reach =
    when (params.style) {
      AmbientStyle.Glow -> 0.6f + params.spread * 2.5f
      AmbientStyle.Cinema -> 1.35f * params.spread / 0.10f
      AmbientStyle.Echo -> 1.20f * params.spread / 0.10f
      AmbientStyle.Mirror -> 1.15f * params.spread / 0.06f
      AmbientStyle.YouTube -> 4f
    }
  return reach.takeIf { it.isFinite() }?.coerceIn(0.2f, 4f) ?: 1f
}

private fun ambientColorFilter(params: AmbientRenderParams): ColorFilter {
  val saturation = (params.saturation * styleBaseSaturation(params.style)).coerceIn(0f, 4f)
  val intensity = (params.intensity * styleBaseIntensity(params.style)).coerceIn(0f, 4f)
  val warmth = params.warmth.coerceIn(-1f, 1f)
  val matrix = ColorMatrix().apply { setToSaturation(saturation) }
  matrix.timesAssign(
    ColorMatrix(
      floatArrayOf(
        intensity * (1f + warmth * 0.12f), 0f, 0f, 0f, 0f,
        0f, intensity * (1f + warmth * 0.05f), 0f, 0f, 0f,
        0f, 0f, intensity * (1f - warmth * 0.15f), 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
      ),
    ),
  )
  return ColorFilter.colorMatrix(matrix)
}

@Composable
fun AmbientStyleBackground(
  frame: ImageBitmap?,
  params: AmbientRenderParams,
  videoBounds: ComposeRect,
  modifier: Modifier = Modifier,
) {
  val colorFilter =
    remember(params.style, params.saturation, params.intensity, params.warmth) { ambientColorFilter(params) }
  val layerPaint = remember { Paint() }

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .videoAmbientMask(videoBounds, params.edgeBlend),
  ) {
    drawRect(Color.Black)
    if (frame == null || videoBounds.isEmpty || frame.width <= 0 || frame.height <= 0) return@Canvas

    val reachScale = styleReach(params)
    val opacity = params.opacity.coerceIn(0f, 1f)
    for (bar in ambientBars(size, videoBounds)) {
      val reach = bar.thickness * reachScale
      if (reach < MIN_BAR_PX) continue
      layerPaint.alpha =
        if (params.style == AmbientStyle.Mirror && bar.side == AmbientBarSide.Top) opacity * 0.7f else opacity
      clipRect(bar.rect.left, bar.rect.top, bar.rect.right, bar.rect.bottom) {
        drawIntoCanvas { it.saveLayer(bar.rect, layerPaint) }
        drawStyleProjection(frame, bar, videoBounds, params, colorFilter)
        drawRect(
          brush = fadeBrush(bar, videoBounds, reach, params.fadeCurve),
          topLeft = bar.rect.topLeft,
          size = bar.rect.size,
          blendMode = BlendMode.DstIn,
        )
        drawIntoCanvas { it.restore() }
      }
    }

    val vignette = params.vignette.coerceIn(0f, 1f)
    if (vignette > 0.001f) {
      drawRect(
        Brush.radialGradient(
          0.35f to Color.Transparent,
          1f to Color.Black.copy(alpha = vignette),
          center = center,
          radius = max(size.width, size.height) * 0.75f,
        ),
      )
    }
  }
}

private fun fadeBrush(
  bar: AmbientBar,
  video: ComposeRect,
  reach: Float,
  fadeCurve: Float,
): Brush {
  val curve = fadeCurve.takeIf { it.isFinite() }?.coerceIn(0.2f, 4f) ?: 1f
  val stops =
    Array(FADE_STOPS) { index ->
      val t = index / (FADE_STOPS - 1f)
      t to Color.Black.copy(alpha = (1f - t).pow(curve))
    }
  val (start, end) =
    when (bar.side) {
      AmbientBarSide.Left -> Offset(video.left, 0f) to Offset(video.left - reach, 0f)
      AmbientBarSide.Right -> Offset(video.right, 0f) to Offset(video.right + reach, 0f)
      AmbientBarSide.Top -> Offset(0f, video.top) to Offset(0f, video.top - reach)
      AmbientBarSide.Bottom -> Offset(0f, video.bottom) to Offset(0f, video.bottom + reach)
    }
  return Brush.linearGradient(*stops, start = start, end = end)
}

private fun DrawScope.drawFrame(
  frame: ImageBitmap,
  srcX: Int,
  srcY: Int,
  srcW: Int,
  srcH: Int,
  dst: ComposeRect,
  colorFilter: ColorFilter,
) {
  val dstW = dst.width.roundToInt()
  val dstH = dst.height.roundToInt()
  if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return
  drawImage(
    image = frame,
    srcOffset = IntOffset(srcX, srcY),
    srcSize = IntSize(srcW, srcH),
    dstOffset = IntOffset(dst.left.roundToInt(), dst.top.roundToInt()),
    dstSize = IntSize(dstW, dstH),
    colorFilter = colorFilter,
    filterQuality = FilterQuality.Low,
  )
}

private fun DrawScope.drawStyleProjection(
  frame: ImageBitmap,
  bar: AmbientBar,
  video: ComposeRect,
  params: AmbientRenderParams,
  colorFilter: ColorFilter,
) {
  val fw = frame.width
  val fh = frame.height
  when (params.style) {
    // A magnified, heavily blurred copy of the whole picture: the bars glow with nearby colours.
    AmbientStyle.Glow, AmbientStyle.YouTube -> {
      val zoom =
        max(size.width / video.width, size.height / video.height) *
          (1f + params.spread.coerceIn(0f, 1f) * 0.5f)
      val w = video.width * zoom
      val h = video.height * zoom
      val c = video.center
      drawFrame(frame, 0, 0, fw, fh, ComposeRect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f), colorFilter)
    }

    // The picture keeps projecting past its edges, like light spilling off a cinema screen.
    AmbientStyle.Cinema -> {
      val reach = bar.thickness * styleReach(params)
      val dst =
        if (bar.horizontal) {
          ComposeRect(video.left - reach, video.top, video.right + reach, video.bottom)
        } else {
          ComposeRect(video.left, video.top - reach, video.right, video.bottom + reach)
        }
      drawFrame(frame, 0, 0, fw, fh, dst, colorFilter)
    }

    // The outermost band of pixels is smeared straight across the bar.
    AmbientStyle.Echo -> {
      val spread = params.spread.coerceIn(0.01f, 1f)
      when (bar.side) {
        AmbientBarSide.Left, AmbientBarSide.Right -> {
          val band = (fw * spread * 0.5f).roundToInt().coerceIn(1, fw)
          val srcX = if (bar.side == AmbientBarSide.Left) 0 else fw - band
          drawFrame(frame, srcX, 0, band, fh, ComposeRect(bar.rect.left, video.top, bar.rect.right, video.bottom), colorFilter)
        }
        AmbientBarSide.Top, AmbientBarSide.Bottom -> {
          val band = (fh * spread * 0.5f).roundToInt().coerceIn(1, fh)
          val srcY = if (bar.side == AmbientBarSide.Top) 0 else fh - band
          drawFrame(frame, 0, srcY, fw, band, ComposeRect(video.left, bar.rect.top, video.right, bar.rect.bottom), colorFilter)
        }
      }
    }

    // The strip next to each edge, reflected across that edge at 1:1 scale.
    AmbientStyle.Mirror -> {
      val thickness = bar.thickness
      when (bar.side) {
        AmbientBarSide.Left, AmbientBarSide.Right -> {
          val band = (thickness * fw / video.width).roundToInt().coerceIn(1, fw)
          val left = bar.side == AmbientBarSide.Left
          val edge = if (left) video.left else video.right
          val inside =
            if (left) ComposeRect(edge, video.top, edge + thickness, video.bottom) else ComposeRect(edge - thickness, video.top, edge, video.bottom)
          scale(scaleX = -1f, scaleY = 1f, pivot = Offset(edge, video.center.y)) {
            drawFrame(frame, if (left) 0 else fw - band, 0, band, fh, inside, colorFilter)
          }
        }
        AmbientBarSide.Top, AmbientBarSide.Bottom -> {
          val band = (thickness * fh / video.height).roundToInt().coerceIn(1, fh)
          val top = bar.side == AmbientBarSide.Top
          val edge = if (top) video.top else video.bottom
          val inside =
            if (top) ComposeRect(video.left, edge, video.right, edge + thickness) else ComposeRect(video.left, edge - thickness, video.right, edge)
          scale(scaleX = 1f, scaleY = -1f, pivot = Offset(video.center.x, edge)) {
            drawFrame(frame, 0, if (top) 0 else fh - band, fw, band, inside, colorFilter)
          }
        }
      }
    }
  }
}