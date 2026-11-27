package app.gyrolet.mpvrx.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Weather and nature family: Snow, Rain, Sakura, Fireflies, Bubbles.

/**
 * A tiny six-arm snowflake with two branches per arm. Built only once and reused for every flake.
 * Visual reference: Telegram / NagramX SnowflakesEffect (GPL-2.0-or-later).
 *
 * Keep the geometry small and delicate: Telegram's snow is made of points and small flakes,
 * not the large glowing, rotating crystals previously used here.
 */
private val TelegramStyleSnowflake: Path by lazy {
  Path().apply {
    for (arm in 0 until 6) {
      val angle = arm * TAU / 6f - TAU / 4f
      val dx = cos(angle)
      val dy = sin(angle)
      moveTo(0f, 0f)
      lineTo(dx, dy)
      val jointX = dx * 0.64f
      val jointY = dy * 0.64f
      for (side in -1..1 step 2) {
        val branchAngle = angle + side * TAU / 6f
        moveTo(jointX, jointY)
        lineTo(jointX + cos(branchAngle) * 0.26f, jointY + sin(branchAngle) * 0.26f)
      }
    }
  }
}

/**
 * Telegram-style snowfall, adapted to mpvRx's analytic Compose renderer.
 *
 * Individual particles appear throughout the visible area, descend gently at slightly different
 * angles, and fade in and out over a short lifetime. Seeded time cycles mean there are no
 * allocations, per-frame random calls, particle lists or extra animation clocks. This also lets
 * the picker draw the same effect at a fixed instant without starting an animation.
 */
internal fun DrawScope.drawSnow(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  intensity: Float = 1f,
) {
  val w = size.width
  val h = size.height
  if (w <= 0f || h <= 0f || intensity <= 0f) return

  val spacing = 50.dp.toPx()
  val count =
    ((w * h / (spacing * spacing)).toInt().coerceIn(24, 140) * intensity)
      .toInt()
      .coerceIn(0, 210)
  val ice =
    if (p.isDark) {
      lerp(Color.White, p.primary, 0.06f)
    } else {
      lerp(p.ink, p.primary, 0.14f)
    }
  val drift = sin(t * 0.22f) * 4.dp.toPx()
  val margin = 8.dp.toPx()

  for (i in 0 until count) {
    // Different lifetimes keep each reappearance desynchronised. A cycle-based seed supplies
    // a new starting location on the next lifetime, hidden by the opacity envelope.
    val lifetime = 3.4f + 2.6f * hash01(i, 21)
    val progress = t / lifetime + hash01(i, 22) * 9f
    val phase = fract(progress)
    val cycle = kotlin.math.floor(progress).toInt()
    val id = i + cycle * 197
    val depth = 0.35f + 0.65f * hash01(i, 23)

    val x0 = hash01(id, 24) * (w + 2f * margin) - margin
    val y0 = hash01(id, 25) * h
    val fall = (38f + 48f * depth).dp.toPx() * phase
    val sideways = (hash01(id, 26) - 0.5f) * 36.dp.toPx() * phase
    val sway = sin(t * (0.55f + depth * 0.34f) + i * 1.31f) * 5.dp.toPx() * depth
    val x = x0 + sideways + sway + drift + tilt.x * 14.dp.toPx() * depth
    val y = y0 + fall + tilt.y * 9.dp.toPx() * depth
    if (x < -margin || x > w + margin || y < -margin || y > h + margin) continue

    // Telegram-inspired gentle entrance and longer departure, with depth-based transparency.
    val opacity =
      smoothstep(0f, 0.07f, phase) *
        smoothstep(1f, 0.56f, phase) * (0.32f + 0.54f * depth)
    if (opacity < 0.005f) continue

    val center = Offset(x, y)
    if (hash01(i, 27) < 0.54f) {
      // Many tiny rounded points between the recognizable flakes.
      drawCircle(
        color = ice.copy(alpha = opacity),
        radius = (0.65f + depth * 0.75f).dp.toPx(),
        center = center,
      )
    } else {
      val radius = (2.7f + 2.2f * hash01(i, 28) + depth).dp.toPx()
      val line = (0.46f + depth * 0.16f).dp.toPx()
      translate(left = x, top = y) {
        scale(radius, radius, pivot = Offset.Zero) {
          drawPath(
            path = TelegramStyleSnowflake,
            color = ice.copy(alpha = opacity),
            style = Stroke(width = line / radius, cap = StrokeCap.Round),
          )
        }
      }
    }
  }
}

/** Slanted rain in three layers with small elliptical ripples where it lands near the bottom. */
internal fun DrawScope.drawRain(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val tint = lerp(p.ink, p.secondary, 0.35f)
  val slant = 0.16f + 0.04f * sin(t * 0.16f) + tilt.x * 0.06f
  for (i in 0 until backdropCount(72, minimum = 34, maximum = 100)) {
    val depth = (i % 3 + 1) / 3f
    val len = (16f + 26f * depth).dp.toPx()
    val u = fract(hash01(i, 1) + t * (0.30f + 0.42f * depth))
    val y = h * (u * 1.2f - 0.1f)
    // Wrap across a padded horizontal span: diagonal motion must not empty the left side.
    val span = w + h * slant + len * 2f
    val x = fract(hash01(i, 2) + y * slant / span) * span - h * slant - len + tilt.x * 10.dp.toPx()
    val head = Offset(x, y)
    val tail = Offset(x - len * slant, y - len)
    val a = (0.08f + 0.20f * depth) * smoothstep(0f, 0.05f, u) * smoothstep(1f, 0.95f, u)
    // A soft, thin streak is cheaper than allocating a new linear-gradient shader for
    // each individual drop on every frame.
    drawLine(
      color = tint.copy(alpha = a),
      start = tail,
      end = head,
      strokeWidth = (0.65f + 0.75f * depth).dp.toPx(),
      cap = StrokeCap.Round,
    )
  }
  // Ripples: each slot has a fixed spot, re-rolled after every cycle
  for (i in 0 until backdropCount(12, minimum = 5, maximum = 15)) {
    val cyc = t * 0.45f + hash01(i, 7) * 3f
    val ph = fract(cyc)
    val seed = i + kotlin.math.floor(cyc).toInt() * 31
    val cx = w * hash01(seed, 8)
    val cy = h * (0.84f + 0.13f * hash01(seed, 9))
    val r = (4f + 24f * (1f - (1f - ph) * (1f - ph))).dp.toPx()
    drawOval(
      color = tint.copy(alpha = 0.26f * (1f - ph) * smoothstep(0f, 0.1f, ph)),
      topLeft = Offset(cx - r, cy - r * 0.32f),
      size = Size(r * 2f, r * 0.64f),
      style = Stroke(width = 1.dp.toPx()),
    )
  }
}

private fun Path.addPetal(s: Float) {
  moveTo(0f, -s)
  cubicTo(s * 0.95f, -s * 0.55f, s * 0.75f, s * 0.75f, 0f, s)
  cubicTo(-s * 0.75f, s * 0.75f, -s * 0.95f, -s * 0.55f, 0f, -s * 0.72f)
  close()
}

private val SakuraPetal: Path by lazy { Path().apply { addPetal(1f) } }

/** Cherry-blossom petals that flutter down, flipping edge-on as they turn. */
internal fun DrawScope.drawSakura(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val pink = lerp(Color(0xFFF6A5C0), p.tertiary, 0.2f)
  val deep = lerp(Color(0xFFE0668F), p.primary, 0.2f)
  for (i in 0 until backdropCount(28, minimum = 16, maximum = 40)) {
    val depth = 0.45f + 0.55f * hash01(i, 6)
    val u = fract(hash01(i, 1) + t * (0.017f + 0.022f * hash01(i, 2)))
    val x =
      w * (hash01(i, 3) * 0.9f - 0.1f) + u * w * 0.22f +
        (sin(u * TAU * 1.6f + i) * 26.dp.toPx() + sin(t * 0.16f) * 12.dp.toPx()) * depth +
        tilt.x * 24.dp.toPx() * depth
    val y = h * (u * 1.14f - 0.07f) + tilt.y * 14.dp.toPx() * depth
    val spin = (t * (0.5f + 0.7f * hash01(i, 4)) * (if (i % 2 == 0) 1f else -1f) + hash01(i, 5) * TAU) * 57.29578f
    val flip = cos(t * (0.8f + 0.5f * hash01(i, 7)) + i)
    val s = (5f + 6f * depth).dp.toPx()
    val a = 0.80f * smoothstep(0f, 0.06f, u) * smoothstep(1f, 0.94f, u)
    translate(left = x, top = y) {
      rotate(spin, pivot = Offset.Zero) {
        scale(scaleX = flip * s, scaleY = s, pivot = Offset.Zero) {
          drawPath(SakuraPetal, (if (flip >= 0f) pink else deep).copy(alpha = a))
          drawLine(deep.copy(alpha = a * 0.4f), Offset(0f, -0.5f), Offset(0f, 0.8f), strokeWidth = 0.7.dp.toPx() / s)
        }
      }
    }
  }
}

/** Fireflies wandering on looping paths, each blinking on its own beat with a soft green-gold glow. */
internal fun DrawScope.drawFireflies(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val glow = lerp(Color(0xFFD8F36A), p.secondary, 0.15f)
  for (i in 0 until backdropCount(30, minimum = 18, maximum = 40)) {
    val depth = 0.4f + 0.6f * hash01(i, 8)
    val x =
      w * (0.06f + 0.88f * hash01(i, 1)) + sin(t * (0.17f + 0.2f * hash01(i, 2)) + i) * 44.dp.toPx() +
        sin(t * (0.41f + 0.2f * hash01(i, 3)) + i * 2f) * 20.dp.toPx() +
        tilt.x * 24.dp.toPx() * depth
    val y =
      h * (0.10f + 0.80f * hash01(i, 4)) + cos(t * (0.14f + 0.2f * hash01(i, 5)) + i * 1.4f) * 40.dp.toPx() +
        sin(t * 0.37f + i) * 16.dp.toPx() +
        tilt.y * 24.dp.toPx() * depth
    val b = wave01(t * (0.55f + 0.65f * hash01(i, 6)) + i * 2.1f)
    val blink = b * b
    val warmth = lerp(glow, p.tertiary, 0.18f * hash01(i, 9))
    val c = Offset(x, y)
    val r = (10f + 12f * depth).dp.toPx()
    // Three concentric glows approximate the soft firefly bloom without compiling a
    // distinct radial-gradient shader for every light on every frame.
    drawCircle(warmth.copy(alpha = 0.055f * blink), radius = r, center = c)
    drawCircle(warmth.copy(alpha = 0.14f * blink), radius = r * 0.36f, center = c)
    drawCircle(warmth.copy(alpha = 0.14f + 0.72f * blink), radius = (1.2f + 0.8f * depth).dp.toPx(), center = c)
  }
}

/** Soap bubbles: hollow, rim-lit, swelling as they rise and fading out near the top. */
internal fun DrawScope.drawBubbles(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)
  for (i in 0 until backdropCount(20, minimum = 12, maximum = 28)) {
    val depth = 0.35f + 0.65f * hash01(i, 6)
    val u = fract(hash01(i, 1) + t * (0.018f + 0.03f * hash01(i, 2)))
    val x =
      w * (0.04f + 0.92f * hash01(i, 3)) + sin(u * TAU * 3f + i) * 18.dp.toPx() * depth + tilt.x * 26.dp.toPx() * depth
    val y = h * (1.08f - 1.16f * u) + tilt.y * 14.dp.toPx() * depth
    val r = base * (0.022f + 0.055f * depth) * (0.85f + 0.3f * u) * (1f + 0.035f * sin(t * 0.7f + i))
    val a = smoothstep(0f, 0.08f, u) * smoothstep(1f, 0.90f, u)
    val color = p.accent(i)
    val c = Offset(x, y)
    drawCircle(
      brush =
        Brush.radialGradient(
          colorStops =
            arrayOf(
              0f to Color.Transparent,
              0.7f to color.copy(alpha = 0.05f * a),
              1f to color.copy(alpha = 0.22f * a),
            ),
          center = c,
          radius = r,
        ),
      radius = r,
      center = c,
    )
    drawCircle(color.copy(alpha = 0.38f * a), radius = r, center = c, style = Stroke(width = 1.2.dp.toPx()))
    // A second, tinted reflection makes the rim read as a translucent bubble.
    drawArc(
      color = p.accent(i + 1).copy(alpha = 0.28f * a),
      startAngle = 30f + 10f * sin(t * 0.35f + i),
      sweepAngle = 76f,
      useCenter = false,
      topLeft = c - Offset(r, r),
      size = Size(r * 2f, r * 2f),
      style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
    )
    // Highlight arc, upper left
    val hr = r * 0.72f
    drawArc(
      color = p.ink.copy(alpha = 0.45f * a),
      startAngle = 200f,
      sweepAngle = 48f,
      useCenter = false,
      topLeft = Offset(x - hr, y - hr),
      size = Size(hr * 2f, hr * 2f),
      style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
    )
  }
}
