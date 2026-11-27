/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.gyrolet.mpvrx.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Wallpapers that are drawn in code, so no image assets are needed.
 *
 * A preset is stored in preferences as `preset:<id>` (see [WallpaperPreset.uri]) and turned into a
 * bitmap on demand by [createWallpaperPresetBitmap]. The same [drawWallpaperPreset] function is used
 * for the small picker cards, so the card always matches the real wallpaper.
 */
enum class WallpaperPreset(
  val id: String,
  @StringRes val labelRes: Int,
) {
  Aurora("aurora", R.string.wallpaper_preset_aurora),
  Sunset("sunset", R.string.wallpaper_preset_sunset),
  Ocean("ocean", R.string.wallpaper_preset_ocean),
  Midnight("midnight", R.string.wallpaper_preset_midnight),
  Mist("mist", R.string.wallpaper_preset_mist),
  Blossom("blossom", R.string.wallpaper_preset_blossom),
  ;

  val uri: String get() = PREFIX + id

  companion object {
    const val PREFIX = "preset:"

    fun isPresetUri(uri: String): Boolean = uri.startsWith(PREFIX, ignoreCase = true)

    fun fromUri(uri: String): WallpaperPreset? = entries.firstOrNull { uri.equals(it.uri, ignoreCase = true) }
  }
}

private const val PRESET_WIDTH_PX = 1080
private const val PRESET_HEIGHT_PX = 2340

fun createWallpaperPresetBitmap(
  preset: WallpaperPreset,
  width: Int = PRESET_WIDTH_PX,
  height: Int = PRESET_HEIGHT_PX,
): Bitmap {
  val image = ImageBitmap(width, height)
  CanvasDrawScope().draw(
    density = Density(1f),
    layoutDirection = LayoutDirection.Ltr,
    canvas = ComposeCanvas(image),
    size = Size(width.toFloat(), height.toFloat()),
  ) {
    drawWallpaperPreset(preset)
  }
  return image.asAndroidBitmap()
}

fun DrawScope.drawWallpaperPreset(preset: WallpaperPreset) {
  when (preset) {
    WallpaperPreset.Aurora -> drawAurora()
    WallpaperPreset.Sunset -> drawSunset()
    WallpaperPreset.Ocean -> drawOcean()
    WallpaperPreset.Midnight -> drawMidnight()
    WallpaperPreset.Mist -> drawMist()
    WallpaperPreset.Blossom -> drawBlossom()
  }
}

private fun DrawScope.glow(
  color: Color,
  cx: Float,
  cy: Float,
  radius: Float,
  alpha: Float,
) {
  drawCircle(
    brush =
      Brush.radialGradient(
        colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
        center = Offset(cx, cy),
        radius = radius,
      ),
    radius = radius,
    center = Offset(cx, cy),
  )
}

private fun waveAt(
  t: Float,
  freqA: Float,
  phaseA: Float,
  freqB: Float,
  phaseB: Float,
): Float =
  0.65f * sin((t * freqA * 2f * PI + phaseA).toFloat()) +
    0.35f * sin((t * freqB * 2f * PI + phaseB).toFloat())

/** Filled silhouette with a soft rolling ridge line. */
private fun DrawScope.ridge(
  baseY: Float,
  amplitude: Float,
  freqA: Float,
  phaseA: Float,
  freqB: Float,
  phaseB: Float,
  brush: Brush,
) {
  val path = Path()
  val steps = 60
  path.moveTo(0f, size.height)
  for (i in 0..steps) {
    val t = i / steps.toFloat()
    val wave = waveAt(t, freqA, phaseA, freqB, phaseB)
    path.lineTo(t * size.width, baseY - amplitude * wave)
  }
  path.lineTo(size.width, size.height)
  path.close()
  drawPath(path, brush)
}

/** Small round stars, thinning out towards [maxY] so they fade into the horizon. */
private fun DrawScope.stars(
  seed: Int,
  count: Int,
  maxY: Float,
  minAlpha: Float = 0.3f,
  tint: Color = Color.White,
) {
  val unit = size.width / 1080f
  val random = Random(seed)
  repeat(count) {
    val x = random.nextFloat() * size.width
    val y = random.nextFloat() * maxY
    val r = (0.7f + random.nextFloat() * 2.4f) * unit
    val a = minAlpha + random.nextFloat() * (1f - minAlpha)
    val fade = 1f - (y / maxY) * 0.6f
    drawCircle(tint.copy(alpha = (a * fade).coerceIn(0f, 1f)), radius = r, center = Offset(x, y))
  }
}

/** Three stacked triangles, base at [baseY], used as tree silhouettes on the ridges. */
private fun DrawScope.pine(
  cx: Float,
  baseY: Float,
  height: Float,
  color: Color,
) {
  for (tier in 0 until 3) {
    val top = baseY - height + height * 0.26f * tier
    val bottom = top + height * 0.46f
    val halfWidth = height * (0.11f + 0.04f * tier)
    val path = Path()
    path.moveTo(cx, top)
    path.lineTo(cx + halfWidth, bottom)
    path.lineTo(cx - halfWidth, bottom)
    path.close()
    drawPath(path, color)
  }
}

/** Scatters [pine]s along the same wave a [ridge] with these parameters follows. */
private fun DrawScope.pinesAlongRidge(
  baseY: Float,
  amplitude: Float,
  freqA: Float,
  phaseA: Float,
  freqB: Float,
  phaseB: Float,
  seed: Int,
  count: Int,
  minHeight: Float,
  maxHeight: Float,
  color: Color,
) {
  val random = Random(seed)
  repeat(count) {
    val t = random.nextFloat()
    val height = minHeight + random.nextFloat() * (maxHeight - minHeight)
    val ridgeY = baseY - amplitude * waveAt(t, freqA, phaseA, freqB, phaseB)
    pine(t * size.width, ridgeY + height * 0.06f, height, color)
  }
}

/** Soft cones of light fanning out from ([cx], [cy]); [angles] are degrees from straight down. */
private fun DrawScope.lightBeams(
  cx: Float,
  cy: Float,
  length: Float,
  angles: List<Float>,
  spread: Float,
  color: Color,
  alpha: Float,
) {
  angles.forEach { degrees ->
    val radians = degrees * (PI / 180.0)
    val dirX = sin(radians).toFloat()
    val dirY = cos(radians).toFloat()
    val endX = cx + dirX * length
    val endY = cy + dirY * length
    val path = Path()
    path.moveTo(cx, cy)
    path.lineTo(endX + dirY * spread, endY - dirX * spread)
    path.lineTo(endX - dirY * spread, endY + dirX * spread)
    path.close()
    drawPath(
      path,
      Brush.linearGradient(
        listOf(color.copy(alpha = alpha), Color.Transparent),
        start = Offset(cx, cy),
        end = Offset(endX, endY),
      ),
    )
  }
}

/** Horizontal band of haze that fades in and out, sitting between two ridges. */
private fun DrawScope.fogBand(
  y: Float,
  thickness: Float,
  alpha: Float,
) {
  drawRect(
    brush =
      Brush.verticalGradient(
        listOf(Color.Transparent, Color(0xFFD5F2E4).copy(alpha = alpha), Color.Transparent),
        startY = y,
        endY = y + thickness,
      ),
    topLeft = Offset(0f, y),
    size = Size(size.width, thickness),
  )
}

/** Smooth pseudo-random value in 0..1 that varies along [t] (0..1); a/b/c pick the pattern. */
private fun noise01(
  t: Float,
  a: Float,
  b: Float,
  c: Float,
): Float =
  0.5f +
    0.5f *
    (
      0.5f * sin(t * a * 6.2832f + b) +
        0.3f * sin(t * (a * 2.3f + 1f) * 6.2832f + c) +
        0.2f * sin(t * (a * 4.1f + 2f) * 6.2832f + b * 2f + c)
    )

/** A few larger stars with a soft halo. Each entry is (x fraction, y fraction, radius in 1080p px). */
private fun DrawScope.brightStars(stars: List<Triple<Float, Float, Float>>) {
  val unit = size.width / 1080f
  stars.forEach { (fx, fy, radius) ->
    val x = size.width * fx
    val y = size.height * fy
    glow(Color(0xFFCFE8FF), x, y, radius * unit * 7f, 0.30f)
    drawCircle(Color.White, radius = radius * unit, center = Offset(x, y))
  }
}

/**
 * One aurora curtain, drawn as many thin vertical strips that share a single gradient (bright and
 * sharp at the bottom, fading upwards). Each strip is scaled to its own height and alpha, which
 * gives the streaky, folded look of a real aurora.
 */
private fun DrawScope.auroraCurtain(
  baseY: Float,
  amplitude: Float,
  maxHeight: Float,
  freqA: Float,
  phaseA: Float,
  freqB: Float,
  phaseB: Float,
  top: Color,
  mid: Color,
  low: Color,
  alpha: Float,
  seed: Float,
  strips: Int = 240,
) {
  val brush =
    Brush.verticalGradient(
      0f to top.copy(alpha = 0f),
      0.30f to top.copy(alpha = 0.16f),
      0.62f to mid.copy(alpha = 0.42f),
      0.90f to low.copy(alpha = 0.85f),
      1f to low.copy(alpha = 0.95f),
      startY = -maxHeight,
      endY = 0f,
    )
  val stripWidth = size.width / strips
  for (i in 0 until strips) {
    val t = (i + 0.5f) / strips
    val baseline = baseY - amplitude * waveAt(t, freqA, phaseA, freqB, phaseB)
    val stripHeight = maxHeight * (0.30f + 0.70f * noise01(t, 2.2f, seed, seed * 1.7f))
    val stripAlpha = alpha * (0.55f + 0.45f * noise01(t, 6.0f, seed * 2.1f, seed * 0.3f))
    withTransform({
      translate(t * size.width - stripWidth / 2f, baseline)
      scale(1f, stripHeight / maxHeight, pivot = Offset.Zero)
    }) {
      drawRect(
        brush = brush,
        topLeft = Offset(0f, -maxHeight),
        size = Size(stripWidth + 1.2f, maxHeight + 2f),
        alpha = stripAlpha,
      )
    }
  }
}

/** Jagged mountain range: [count] peaks along the width, each with a sharp summit. */
private fun DrawScope.jaggedRange(
  baseY: Float,
  amplitude: Float,
  seed: Int,
  count: Int,
  brush: Brush,
) {
  val random = Random(seed)
  val xs = FloatArray(count + 1) { it / count.toFloat() }
  val ys = FloatArray(count + 1) { baseY - amplitude * (0.25f + 0.75f * random.nextFloat()) }
  val path = Path()
  path.moveTo(0f, size.height)
  path.lineTo(0f, ys[0])
  for (i in 1..count) {
    val summitX = (xs[i - 1] + xs[i]) / 2f + (random.nextFloat() - 0.5f) / count * 0.5f
    val summitY = minOf(ys[i - 1], ys[i]) - amplitude * (0.15f + 0.45f * random.nextFloat())
    path.lineTo(summitX * size.width, summitY)
    path.lineTo(xs[i] * size.width, ys[i])
  }
  path.lineTo(size.width, size.height)
  path.close()
  drawPath(path, brush)
}

private fun DrawScope.drawAurora() {
  val w = size.width
  val h = size.height
  drawRect(
    Brush.verticalGradient(
      0f to Color(0xFF02040F),
      0.45f to Color(0xFF061336),
      0.78f to Color(0xFF0A2E4A),
      1f to Color(0xFF0B3B48),
    ),
  )
  stars(seed = 11, count = 170, maxY = h * 0.62f)
  brightStars(
    listOf(
      Triple(0.14f, 0.070f, 3.4f),
      Triple(0.83f, 0.120f, 3.0f),
      Triple(0.55f, 0.045f, 2.6f),
      Triple(0.32f, 0.200f, 2.8f),
      Triple(0.90f, 0.290f, 2.4f),
      Triple(0.08f, 0.310f, 2.6f),
    ),
  )
  // Light pooling behind the curtains.
  glow(Color(0xFF2AF5B0), w * 0.40f, h * 0.50f, w * 0.95f, 0.28f)
  glow(Color(0xFF7B5CFF), w * 0.85f, h * 0.40f, w * 0.70f, 0.22f)
  auroraCurtain(
    baseY = h * 0.60f,
    amplitude = h * 0.050f,
    maxHeight = h * 0.36f,
    freqA = 0.8f,
    phaseA = 0.7f,
    freqB = 1.9f,
    phaseB = 2.1f,
    top = Color(0xFF8A5CFF),
    mid = Color(0xFF2AF5B0),
    low = Color(0xFF7CFFC8),
    alpha = 0.70f,
    seed = 1.3f,
  )
  auroraCurtain(
    baseY = h * 0.66f,
    amplitude = h * 0.045f,
    maxHeight = h * 0.30f,
    freqA = 1.2f,
    phaseA = 3.9f,
    freqB = 2.6f,
    phaseB = 0.6f,
    top = Color(0xFFFF6BD6),
    mid = Color(0xFF6C7CFF),
    low = Color(0xFF3BE0FF),
    alpha = 0.50f,
    seed = 4.1f,
  )
  auroraCurtain(
    baseY = h * 0.55f,
    amplitude = h * 0.040f,
    maxHeight = h * 0.20f,
    freqA = 0.7f,
    phaseA = 5.0f,
    freqB = 2.4f,
    phaseB = 1.4f,
    top = Color(0xFF6B7CFF),
    mid = Color(0xFF29D6F6),
    low = Color(0xFF6BFFD0),
    alpha = 0.42f,
    seed = 7.7f,
  )
  // Faint reflection of the aurora on a lake.
  val lakeGlow = Color(0xFF2AF5B0)
  drawRect(
    brush =
      Brush.verticalGradient(
        0f to lakeGlow.copy(alpha = 0f),
        0.5f to lakeGlow.copy(alpha = 0.10f),
        1f to lakeGlow.copy(alpha = 0f),
        startY = h * 0.80f,
        endY = h * 0.92f,
      ),
    topLeft = Offset(0f, h * 0.80f),
    size = Size(w, h * 0.12f),
  )
  // Far range, near range, then the treeline.
  jaggedRange(
    baseY = h * 0.79f,
    amplitude = h * 0.075f,
    seed = 5,
    count = 9,
    brush = Brush.verticalGradient(listOf(Color(0xFF123048), Color(0xFF08182A)), startY = h * 0.70f, endY = h * 0.86f),
  )
  jaggedRange(
    baseY = h * 0.865f,
    amplitude = h * 0.060f,
    seed = 8,
    count = 7,
    brush = Brush.verticalGradient(listOf(Color(0xFF06141D), Color(0xFF030A10)), startY = h * 0.80f, endY = h * 0.93f),
  )
  ridge(
    baseY = h * 0.925f,
    amplitude = h * 0.018f,
    freqA = 1.3f,
    phaseA = 0.4f,
    freqB = 3.0f,
    phaseB = 1.1f,
    brush = Brush.verticalGradient(listOf(Color(0xFF02070C), Color(0xFF010306)), startY = h * 0.90f, endY = h),
  )
  pinesAlongRidge(
    baseY = h * 0.925f,
    amplitude = h * 0.018f,
    freqA = 1.3f,
    phaseA = 0.4f,
    freqB = 3.0f,
    phaseB = 1.1f,
    seed = 32,
    count = 22,
    minHeight = h * 0.028f,
    maxHeight = h * 0.060f,
    color = Color(0xFF02070C),
  )
}

private fun DrawScope.drawSunset() {
  val w = size.width
  val h = size.height
  val horizon = h * 0.62f
  drawRect(
    Brush.verticalGradient(
      0f to Color(0xFF1B1140),
      0.35f to Color(0xFF6A1B9A),
      0.55f to Color(0xFFE8546B),
      0.68f to Color(0xFFFFB35C),
      0.68f to Color(0xFF2A1240),
      1f to Color(0xFF120824),
    ),
  )
  glow(Color(0xFFFFE29A), w * 0.5f, horizon, w * 0.75f, 0.75f)
  drawCircle(Color(0xFFFFF3C4), radius = w * 0.13f, center = Offset(w * 0.5f, horizon - w * 0.02f))
  ridge(
    baseY = horizon + h * 0.03f,
    amplitude = h * 0.04f,
    freqA = 1.5f,
    phaseA = 0.2f,
    freqB = 3.4f,
    phaseB = 2.0f,
    brush = Brush.verticalGradient(listOf(Color(0xFF3A1650), Color(0xFF1A0B2C)), startY = horizon, endY = h),
  )
  ridge(
    baseY = h * 0.80f,
    amplitude = h * 0.05f,
    freqA = 1.1f,
    phaseA = 2.4f,
    freqB = 2.3f,
    phaseB = 0.6f,
    brush = Brush.verticalGradient(listOf(Color(0xFF241038), Color(0xFF0B0416)), startY = h * 0.75f, endY = h),
  )
}

/** Underwater scene: light from the surface, drifting bubbles and kelp on a dark seabed. */
private fun DrawScope.drawOcean() {
  val w = size.width
  val h = size.height
  val unit = w / 1080f
  drawRect(
    Brush.verticalGradient(
      0f to Color(0xFF46D2DC),
      0.16f to Color(0xFF1AA3C6),
      0.42f to Color(0xFF0B66A6),
      0.72f to Color(0xFF073A78),
      1f to Color(0xFF021535),
    ),
  )
  val lightX = w * 0.74f
  val lightY = -h * 0.05f
  glow(Color(0xFFD6FBFF), lightX, -h * 0.03f, w * 1.0f, 0.60f)
  val rayColor = Color(0xFFE6FDFF)
  lightBeams(lightX, lightY, h * 0.95f, listOf(-52f, -36f, -22f, -9f, 4f, 16f, 28f), w * 0.14f, rayColor, 0.10f)
  lightBeams(lightX, lightY, h * 0.80f, listOf(-44f, -28f, -15f, -2f, 10f, 22f), w * 0.05f, rayColor, 0.12f)
  lightBeams(lightX, lightY, h * 0.60f, listOf(-31f, -6f, 14f), w * 0.02f, rayColor, 0.14f)

  // Floating plankton.
  val plankton = Random(3)
  repeat(70) {
    val x = plankton.nextFloat() * w
    val y = plankton.nextFloat() * h * 0.85f
    val a = 0.12f + 0.25f * plankton.nextFloat()
    val r = (0.8f + 1.8f * plankton.nextFloat()) * unit
    drawCircle(rayColor.copy(alpha = a), radius = r, center = Offset(x, y))
  }

  // Bubbles: a soft ring with a small highlight.
  val bubbles = Random(14)
  repeat(30) {
    val cx = (0.12f + 0.80f * bubbles.nextFloat()) * w
    val cy = h * (0.10f + 0.78f * bubbles.nextFloat().pow(1.2f))
    val squared = bubbles.nextFloat()
    val r = (5f + 20f * squared * squared) * unit
    val ring =
      Brush.radialGradient(
        0f to Color.White.copy(alpha = 0f),
        0.62f to Color.White.copy(alpha = 0.06f),
        0.90f to Color.White.copy(alpha = 0.55f),
        1f to Color.White.copy(alpha = 0f),
        center = Offset(cx, cy),
        radius = r,
      )
    drawCircle(ring, radius = r, center = Offset(cx, cy))
    drawCircle(Color.White.copy(alpha = 0.7f), radius = r * 0.16f, center = Offset(cx - r * 0.35f, cy - r * 0.38f))
  }

  // Seabed dunes with kelp swaying in front of the far one.
  ridge(
    baseY = h * 0.86f,
    amplitude = h * 0.030f,
    freqA = 1.0f,
    phaseA = 2.0f,
    freqB = 2.4f,
    phaseB = 0.5f,
    brush =
      Brush.verticalGradient(
        listOf(Color(0xFF0B5E8C).copy(alpha = 0.85f), Color(0xFF04264B).copy(alpha = 0.95f)),
        startY = h * 0.82f,
        endY = h,
      ),
  )
  val kelp = Random(21)
  for (i in 0 until 9) {
    val baseX = (0.04f + 0.92f * (i + kelp.nextFloat() * 0.6f) / 9f) * w
    val baseY = h * (0.90f + 0.03f * kelp.nextFloat())
    val height = h * (0.10f + 0.16f * kelp.nextFloat())
    val sway = (30f + 50f * kelp.nextFloat()) * unit * (if (i % 2 == 1) 1f else -1f)
    val color = lerp(Color(0xFF0E6B62), Color(0xFF2BA37A), kelp.nextFloat()).copy(alpha = 0.9f)
    val p0 = Offset(baseX, baseY)
    val p1 = Offset(baseX + sway * 0.9f, baseY - height * 0.35f)
    val p2 = Offset(baseX - sway * 0.7f, baseY - height * 0.70f)
    val p3 = Offset(baseX + sway * 0.4f, baseY - height)
    drawTaperedCurve(color, 14f * unit, 4f * unit, 26) { bezierPoint(p0, p1, p2, p3, it) }
  }
  ridge(
    baseY = h * 0.93f,
    amplitude = h * 0.022f,
    freqA = 1.4f,
    phaseA = 0.6f,
    freqB = 3.1f,
    phaseB = 1.7f,
    brush = Brush.verticalGradient(listOf(Color(0xFF031B3A), Color(0xFF010A1E)), startY = h * 0.90f, endY = h),
  )
}

private fun DrawScope.drawMidnight() {
  val w = size.width
  val h = size.height
  drawRect(
    Brush.verticalGradient(listOf(Color(0xFF02030A), Color(0xFF0B1030), Color(0xFF1B1F4B))),
  )
  val unit = w / 1080f
  val random = Random(7)
  repeat(170) {
    val x = random.nextFloat() * w
    val y = random.nextFloat() * h * 0.85f
    val r = (0.7f + random.nextFloat() * 2.6f) * unit
    val a = 0.35f + random.nextFloat() * 0.65f
    drawCircle(Color.White.copy(alpha = a), radius = r, center = Offset(x, y))
  }
  glow(Color(0xFFFFF6D5), w * 0.72f, h * 0.19f, w * 0.45f, 0.35f)
  drawCircle(Color(0xFFFFF6E0), radius = w * 0.06f, center = Offset(w * 0.72f, h * 0.19f))
  drawCircle(Color(0xFF0B1030).copy(alpha = 0.55f), radius = w * 0.05f, center = Offset(w * 0.745f, h * 0.185f))
  ridge(
    baseY = h * 0.93f,
    amplitude = h * 0.025f,
    freqA = 1.0f,
    phaseA = 1.0f,
    freqB = 2.2f,
    phaseB = 0.3f,
    brush = Brush.verticalGradient(listOf(Color(0xFF0A0D24), Color(0xFF02030A)), startY = h * 0.9f, endY = h),
  )
}

private class MistLayer(
  val y: Float,
  val color: Color,
  val alpha: Float,
)

private fun DrawScope.drawMist() {
  val w = size.width
  val h = size.height
  drawRect(
    Brush.verticalGradient(
      0f to Color(0xFF061A1E),
      0.30f to Color(0xFF12423F),
      0.52f to Color(0xFF3E8C7C),
      0.74f to Color(0xFF9ED4BE),
      1f to Color(0xFFB8E6D2),
    ),
  )
  stars(seed = 5, count = 90, maxY = h * 0.34f, minAlpha = 0.2f)

  // Pale sun low in the haze, with light fanning out of it.
  val sunX = w * 0.72f
  val sunY = h * 0.31f
  glow(Color(0xFFEFFFF6), sunX, sunY, w * 0.62f, 0.50f)
  lightBeams(
    cx = sunX,
    cy = sunY,
    length = h * 0.62f,
    angles = listOf(-38f, -24f, -12f, 0f, 11f, 23f, 36f),
    spread = w * 0.06f,
    color = Color(0xFFEFFFF6),
    alpha = 0.16f,
  )
  drawCircle(Color(0xFFF4FFF8).copy(alpha = 0.90f), radius = w * 0.05f, center = Offset(sunX, sunY))

  val layers =
    listOf(
      MistLayer(0.42f, Color(0xFF7FBFAA), 0.55f),
      MistLayer(0.50f, Color(0xFF5AA090), 0.65f),
      MistLayer(0.58f, Color(0xFF3F8677), 0.78f),
      MistLayer(0.67f, Color(0xFF2B6A5F), 0.88f),
      MistLayer(0.76f, Color(0xFF1B4F49), 0.95f),
      MistLayer(0.86f, Color(0xFF0E322F), 1.00f),
    )
  layers.forEachIndexed { index, layer ->
    val amplitude = h * (0.05f - 0.005f * index)
    val freqA = 1.1f + 0.35f * index
    val phaseA = index * 1.7f
    val freqB = 2.4f + 0.5f * index
    val phaseB = index * 0.9f + 0.5f
    ridge(
      baseY = h * layer.y,
      amplitude = amplitude,
      freqA = freqA,
      phaseA = phaseA,
      freqB = freqB,
      phaseB = phaseB,
      brush =
        Brush.verticalGradient(
          listOf(layer.color.copy(alpha = layer.alpha), Color(0xFF071B1A).copy(alpha = layer.alpha)),
          startY = h * (layer.y - 0.06f),
          endY = h,
        ),
    )
    // Trees only on the three nearest ridges, hazier the further back they are.
    if (index >= layers.size - 3) {
      pinesAlongRidge(
        baseY = h * layer.y,
        amplitude = amplitude,
        freqA = freqA,
        phaseA = phaseA,
        freqB = freqB,
        phaseB = phaseB,
        seed = 40 + index,
        count = 10 + 3 * (index - (layers.size - 3)),
        minHeight = h * (0.012f + 0.004f * (index - (layers.size - 3))),
        maxHeight = h * (0.022f + 0.008f * (index - (layers.size - 3))),
        color = lerp(layer.color, Color(0xFF071B1A), 0.55f).copy(alpha = layer.alpha),
      )
    }
    // Haze pooling between this ridge and the next one.
    if (index < layers.lastIndex) {
      fogBand(y = h * layer.y + h * 0.02f, thickness = h * 0.11f, alpha = 0.20f + 0.03f * index)
    }
  }
}

private fun DrawScope.drawBlossom() {
  val w = size.width
  val h = size.height
  val unit = w / 1080f
  drawRect(
    Brush.verticalGradient(
      0f to Color(0xFFF3B8D1),
      0.45f to Color(0xFFE087AC),
      1f to Color(0xFF6E5A96),
    ),
  )
  glow(Color(0xFFFF6FA0), w * 0.12f, h * 0.16f, w * 0.90f, 0.42f)
  glow(Color(0xFFFF9E5C), w * 0.95f, h * 0.52f, w * 0.85f, 0.34f)
  glow(Color(0xFF7C5FCF), w * 0.22f, h * 0.88f, w * 0.95f, 0.40f)

  // Out-of-focus light spots (kept subtle so they don't wash out foreground UI/text).
  val bokeh = Random(9)
  repeat(8) {
    val color = if (bokeh.nextBoolean()) Color(0xFFFFE3EF) else Color(0xFFFF8FB6)
    glow(
      color,
      bokeh.nextFloat() * w,
      h * (0.25f + 0.70f * bokeh.nextFloat()),
      (40f + bokeh.nextFloat() * 80f) * unit,
      0.07f + bokeh.nextFloat() * 0.06f,
    )
  }
  // A few soft, blurred blossoms in the far distance for depth.
  softBlossom(w * -0.02f, h * 0.30f, w * 0.22f, 20f, 0.30f)
  softBlossom(w * 1.02f, h * 0.70f, w * 0.26f, 200f, 0.26f)
  softBlossom(w * 0.12f, h * 0.93f, w * 0.20f, 75f, 0.24f)

  drawSakuraBranch()
  drawFallingPetals()
}

/** An unfocused, translucent blossom used only for background depth (no stem or stamens). */
private fun DrawScope.softBlossom(
  cx: Float,
  cy: Float,
  r: Float,
  rotation: Float,
  alpha: Float,
) {
  val center = Offset(cx, cy)
  val brush =
    Brush.radialGradient(
      colors =
        listOf(
          Color(0xFFFF7FA8).copy(alpha = alpha),
          Color(0xFFFFB3CB).copy(alpha = alpha * 0.8f),
          Color(0xFFFFEAF1).copy(alpha = alpha * 0.35f),
        ),
      center = center,
      radius = r * 1.05f,
    )
  for (i in 0 until 5) {
    rotate(rotation + i * 72f, center) {
      drawPath(petalPath(cx, cy, r), brush)
    }
  }
}

private fun bezierPoint(
  p0: Offset,
  p1: Offset,
  p2: Offset,
  p3: Offset,
  t: Float,
): Offset {
  val u = 1f - t
  val a = u * u * u
  val b = 3f * u * u * t
  val c = 3f * u * t * t
  val d = t * t * t
  return Offset(
    a * p0.x + b * p1.x + c * p2.x + d * p3.x,
    a * p0.y + b * p1.y + c * p2.y + d * p3.y,
  )
}

private fun quadPoint(
  p0: Offset,
  control: Offset,
  p1: Offset,
  t: Float,
): Offset {
  val u = 1f - t
  return Offset(
    u * u * p0.x + 2f * u * t * control.x + t * t * p1.x,
    u * u * p0.y + 2f * u * t * control.y + t * t * p1.y,
  )
}

/** Draws a curve as short round-capped segments whose width tapers from [startWidth] to [endWidth]. */
private fun DrawScope.drawTaperedCurve(
  color: Color,
  startWidth: Float,
  endWidth: Float,
  steps: Int = 22,
  point: (Float) -> Offset,
) {
  var previous = point(0f)
  for (i in 1..steps) {
    val s = i / steps.toFloat()
    val next = point(s)
    drawLine(color, previous, next, strokeWidth = startWidth + (endWidth - startWidth) * s, cap = StrokeCap.Round)
    previous = next
  }
}

/** A petal pointing up from ([cx], [cy]) with a small notch in its tip. */
private fun petalPath(
  cx: Float,
  cy: Float,
  r: Float,
): Path {
  val path = Path()
  path.moveTo(cx, cy)
  path.cubicTo(cx - r * 0.85f, cy - r * 0.30f, cx - r * 0.60f, cy - r * 1.05f, cx - r * 0.10f, cy - r * 0.98f)
  path.lineTo(cx, cy - r * 0.86f)
  path.lineTo(cx + r * 0.10f, cy - r * 0.98f)
  path.cubicTo(cx + r * 0.60f, cy - r * 1.05f, cx + r * 0.85f, cy - r * 0.30f, cx, cy)
  path.close()
  return path
}

private fun DrawScope.sakuraFlower(
  cx: Float,
  cy: Float,
  r: Float,
  rotation: Float,
) {
  val center = Offset(cx, cy)
  glow(Color(0xFFFF8FB1), cx, cy, r * 2.2f, 0.18f)
  val petalBrush =
    Brush.radialGradient(
      colors = listOf(Color(0xFFFF6F9C), Color(0xFFFFB3CB), Color(0xFFFFEAF1)),
      center = center,
      radius = r * 1.05f,
    )
  for (i in 0 until 5) {
    rotate(rotation + i * 72f, center) {
      val petal = petalPath(cx, cy, r)
      drawPath(petal, petalBrush)
      drawPath(petal, Color(0xFFFF8FB1).copy(alpha = 0.55f), style = Stroke(width = r * 0.03f))
    }
  }
  for (i in 0 until 6) {
    val angle = (rotation + i * 60f + 20f) * (PI / 180.0)
    val end = Offset(cx + (sin(angle) * r * 0.42f).toFloat(), cy - (cos(angle) * r * 0.42f).toFloat())
    drawLine(Color(0xFFE0457B).copy(alpha = 0.9f), center, end, strokeWidth = r * 0.035f)
    drawCircle(Color(0xFFFFD27A), radius = r * 0.06f, center = end)
  }
  drawCircle(Color(0xFFD6336C).copy(alpha = 0.85f), radius = r * 0.10f, center = center)
}

private fun DrawScope.sakuraBud(
  cx: Float,
  cy: Float,
  r: Float,
) {
  drawCircle(Color(0xFFFF6F9C), radius = r * 0.55f, center = Offset(cx, cy))
  drawCircle(Color(0xFFFFB3CB), radius = r * 0.32f, center = Offset(cx - r * 0.12f, cy - r * 0.14f))
}

/** One rigid segment of branch: a dark bark stroke plus a thinner highlight along its top edge. */
private fun DrawScope.limb(
  p0: Offset,
  p1: Offset,
  p2: Offset,
  p3: Offset,
  startWidth: Float,
  endWidth: Float,
  unit: Float,
) {
  drawTaperedCurve(BARK_COLOR, startWidth * unit, endWidth * unit, 30) { bezierPoint(p0, p1, p2, p3, it) }
  drawTaperedCurve(BARK_HIGHLIGHT, startWidth * 0.32f * unit, endWidth * 0.30f * unit, 30) {
    bezierPoint(p0, p1, p2, p3, it) + Offset(-3f * unit, -4f * unit)
  }
}

private val BARK_COLOR = Color(0xFF5B3445)
private val BARK_HIGHLIGHT = Color(0xFF8A566A).copy(alpha = 0.55f)

/** Sakura branch reaching in from the top-right corner: two limbs, twigs, blossoms and buds. */
private fun DrawScope.drawSakuraBranch() {
  val w = size.width
  val h = size.height
  val unit = w / 1080f
  val flowers = ArrayList<Triple<Offset, Float, Float>>() // centre, radius factor, rotation
  val buds = ArrayList<Pair<Offset, Float>>() // centre, radius factor

  fun addTwigs(
    main: List<Offset>,
    specs: List<Triple<Float, Offset, Offset>>,
    widths: List<Float>,
  ) {
    specs.forEachIndexed { index, (t, control, end) ->
      val start = bezierPoint(main[0], main[1], main[2], main[3], t)
      val width = widths[index]
      drawTaperedCurve(BARK_COLOR, width * unit, width * 0.38f * unit, 20) { quadPoint(start, control, end, it) }
      flowers.add(Triple(end, 1.0f + 0.14f * (index % 3), index * 37f + 11f))
      flowers.add(Triple(quadPoint(start, control, end, 0.55f), 0.74f + 0.08f * (index % 3), index * 53f + 20f))
      val q = quadPoint(start, control, end, 0.78f)
      buds.add(q + Offset(12f * unit, 14f * unit) to 0.55f)
    }
  }

  // Main limb.
  val m1 =
    listOf(
      Offset(w * 1.10f, h * 0.045f),
      Offset(w * 0.95f, h * 0.000f),
      Offset(w * 0.56f, h * 0.170f),
      Offset(
        w * 0.10f,
        h * 0.125f,
      ),
    )
  limb(m1[0], m1[1], m1[2], m1[3], 30f, 8f, unit)
  addTwigs(
    m1,
    listOf(
      Triple(0.10f, Offset(w * 0.99f, h * 0.100f), Offset(w * 0.93f, h * 0.155f)),
      Triple(0.24f, Offset(w * 0.90f, h * 0.130f), Offset(w * 0.84f, h * 0.225f)),
      Triple(0.42f, Offset(w * 0.74f, h * 0.210f), Offset(w * 0.68f, h * 0.285f)),
      Triple(0.60f, Offset(w * 0.55f, h * 0.220f), Offset(w * 0.47f, h * 0.285f)),
      Triple(0.78f, Offset(w * 0.32f, h * 0.190f), Offset(w * 0.24f, h * 0.245f)),
      Triple(0.94f, Offset(w * 0.16f, h * 0.170f), Offset(w * 0.06f, h * 0.190f)),
    ),
    listOf(13f, 14f, 14f, 12f, 11f, 9f),
  )
  listOf(0.06f to 1.25f, 0.19f to 1.05f, 0.33f to 1.25f, 0.50f to 1.00f, 0.67f to 1.20f, 0.85f to 1.0f)
    .forEachIndexed { index, (t, factor) ->
      val onBranch = bezierPoint(m1[0], m1[1], m1[2], m1[3], t)
      flowers.add(Triple(onBranch + Offset(0f, -8f * unit), factor, index * 71f + 9f))
    }

  // Secondary, shorter limb branching lower down.
  val m2 =
    listOf(
      Offset(w * 1.08f, h * 0.30f),
      Offset(w * 0.96f, h * 0.26f),
      Offset(w * 0.80f, h * 0.34f),
      Offset(
        w * 0.60f,
        h * 0.36f,
      ),
    )
  limb(m2[0], m2[1], m2[2], m2[3], 20f, 6f, unit)
  addTwigs(
    m2,
    listOf(
      Triple(0.35f, Offset(w * 0.90f, h * 0.33f), Offset(w * 0.86f, h * 0.395f)),
      Triple(0.72f, Offset(w * 0.72f, h * 0.375f), Offset(w * 0.66f, h * 0.44f)),
    ),
    listOf(10f, 9f),
  )
  listOf(0.10f to 1.1f, 0.55f to 1.0f, 0.98f to 0.9f).forEachIndexed { index, (t, factor) ->
    val onBranch = bezierPoint(m2[0], m2[1], m2[2], m2[3], t)
    flowers.add(Triple(onBranch + Offset(0f, -6f * unit), factor, index * 61f + 5f))
  }

  val baseRadius = w * 0.050f
  buds.forEach { (center, factor) -> sakuraBud(center.x, center.y, baseRadius * factor) }
  flowers.forEach { (center, factor, rotation) -> sakuraFlower(center.x, center.y, baseRadius * factor, rotation) }
}

/** One petal drawn with a small gradient (base colour deepening towards a warmer pink) instead of a flat fill. */
private fun DrawScope.gradientPetal(
  x: Float,
  y: Float,
  r: Float,
  rotation: Float,
  color: Color,
  alpha: Float,
) {
  val tip = Offset(x, y + r * 0.5f)
  val brush =
    Brush.radialGradient(
      colors = listOf(lerp(color, Color(0xFFFF7FA8), 0.55f).copy(alpha = alpha), color.copy(alpha = alpha)),
      center = tip,
      radius = r * 0.95f,
    )
  rotate(rotation, Offset(x, y)) {
    drawPath(petalPath(x, y + r * 0.5f, r), brush)
  }
}

/** Petals drifting down: most stream diagonally away from the branch, the rest scattered loosely. */
private fun DrawScope.drawFallingPetals() {
  val w = size.width
  val h = size.height
  val unit = w / 1080f
  val palette = listOf(Color(0xFFFFB3C7), Color(0xFFFFC7D6), Color(0xFFFF9DB8), Color(0xFFFFDCE6), Color(0xFFFFFFFF))
  val random = Random(21)
  repeat(72) { i ->
    val depth = random.nextFloat()
    val fall = random.nextFloat()
    val y = h * (0.08f + 0.90f * fall.pow(1.15f))
    val x =
      if (i % 5 < 3) {
        w * (0.86f - 0.70f * (y / h)) + (random.nextFloat() - 0.5f) * w * 0.55f
      } else {
        random.nextFloat() * w
      }
    val r = (14f + 40f * depth * depth) * unit
    val alpha = 0.60f + 0.38f * random.nextFloat()
    gradientPetal(x, y, r, random.nextFloat() * 360f, palette[random.nextInt(palette.size)], alpha)
  }
  // A few big, faint petals close to the camera.
  repeat(5) {
    val x = random.nextFloat() * w
    val y = h * (0.15f + 0.8f * random.nextFloat())
    val r = (75f + random.nextFloat() * 50f) * unit
    gradientPetal(x, y, r, random.nextFloat() * 360f, Color(0xFFFFB3C7), 0.22f)
  }
}
