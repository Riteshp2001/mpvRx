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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Event backdrops. They wear fixed festive colours rather than the theme's, since the colours
 * are the point (orange for Halloween, gulal for Holi), and are kept soft enough to sit under
 * text on both light and dark pages.
 */

// ---------------------------------------------------------------- Halloween --------------

private val HalloweenOrange = Color(0xFFFF7A18)
private val HalloweenPurple = Color(0xFF8A4DFF)
private val MoonTone = Color(0xFFFFE3A3)

/** Purple night with a low orange glow, a big moon, drifting fog, flapping bats and rising embers. */
internal fun DrawScope.drawHalloween(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  path: Path,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)

  drawRect(
    brush =
      Brush.radialGradient(
        listOf(HalloweenPurple.copy(alpha = 0.16f), Color.Transparent),
        center =
          Offset(
            w * 0.5f,
            0f,
          ),
        radius = h * 0.75f,
      ),
    size = size,
  )
  drawRect(
    brush =
      Brush.radialGradient(
        listOf(HalloweenOrange.copy(alpha = 0.20f), Color.Transparent),
        center =
          Offset(
            w * 0.5f,
            h * 1.02f,
          ),
        radius = h * 0.6f,
      ),
    size = size,
  )

  // Moon
  val moon = Offset(w * 0.76f + tilt.x * 8.dp.toPx(), h * 0.15f + tilt.y * 8.dp.toPx())
  val mr = base * 0.10f
  drawCircle(
    Brush.radialGradient(
      listOf(MoonTone.copy(alpha = 0.28f), Color.Transparent),
      center = moon,
      radius =
        mr * 3.2f,
    ),
    radius = mr * 3.2f,
    center = moon,
  )
  drawCircle(MoonTone.copy(alpha = 0.70f), radius = mr, center = moon)
  drawCircle(
    Color(0xFFB98F3A).copy(alpha = 0.18f),
    radius = mr * 0.22f,
    center = moon + Offset(-mr * 0.35f, -mr * 0.2f),
  )
  drawCircle(Color(0xFFB98F3A).copy(alpha = 0.14f), radius = mr * 0.15f, center = moon + Offset(mr * 0.3f, mr * 0.35f))

  // Fog banks sliding across at different heights
  for (k in 0 until 3) {
    val phase = fract(t * (0.006f + 0.003f * k) + k * 0.33f)
    val fade = smoothstep(0f, 0.18f, phase) * smoothstep(1f, 0.82f, phase)
    val x = w * (phase * 1.8f - 0.4f)
    val c = Offset(x + tilt.x * 6.dp.toPx(), h * (0.62f + 0.12f * k) + sin(t * 0.12f + k) * 12.dp.toPx())
    val rr = w * 0.65f
    drawCircle(
      Brush.radialGradient(
        listOf(HalloweenPurple.copy(alpha = 0.10f * fade), Color.Transparent),
        center = c,
        radius = rr,
      ),
      radius = rr,
      center = c,
    )
  }

  // Bats
  for (i in 0 until backdropCount(5, minimum = 3, maximum = 7)) {
    val u = fract(hash01(i, 1) + t * (0.022f + 0.016f * hash01(i, 2)))
    val s = (7f + 6f * hash01(i, 3)).dp.toPx()
    val dir = if (i % 2 == 0) -1f else 1f
    val x = if (dir < 0f) w * (1.12f - 1.24f * u) else w * (-0.12f + 1.24f * u)
    val y = h * (0.08f + 0.42f * hash01(i, 4)) + sin(u * TAU * 3f + i) * 26.dp.toPx() + tilt.y * 12.dp.toPx()
    val flap = sin(t * 9f + i * 1.7f)
    val bat = path.apply { rewind() }
    val fade = smoothstep(0f, 0.08f, u) * smoothstep(1f, 0.92f, u)
    for (sg in -1..1 step 2) {
      val f = sg.toFloat()
      bat.moveTo(0f, -s * 0.1f)
      bat.quadraticTo(f * s * 0.75f, -s * (0.65f + 0.55f * flap), f * s * 1.55f, s * 0.12f * (1f - flap))
      bat.quadraticTo(f * s * 1.05f, s * 0.12f, f * s * 0.75f, s * 0.38f)
      bat.quadraticTo(f * s * 0.42f, s * 0.12f, 0f, s * 0.42f)
      bat.close()
    }
    translate(left = x, top = y) {
      drawPath(bat, HalloweenPurple.copy(alpha = 0.50f * fade))
      drawCircle(HalloweenPurple.copy(alpha = 0.65f * fade), radius = s * 0.22f, center = Offset(0f, s * 0.05f))
    }
  }

  // Embers
  for (i in 0 until backdropCount(26, minimum = 15, maximum = 38)) {
    val u = fract(hash01(i, 11) + t * (0.02f + 0.03f * hash01(i, 12)))
    val x = w * hash01(i, 13) + sin(u * TAU * 2f + i) * 20.dp.toPx()
    val y = h * (1.04f - 0.8f * u)
    val a = smoothstep(0f, 0.12f, u) * smoothstep(1f, 0.6f, u)
    val flicker = 0.8f + 0.2f * sin(t * 2.5f + i * 2f)
    drawCircle(HalloweenOrange.copy(alpha = 0.14f * a), radius = 7.dp.toPx(), center = Offset(x, y))
    drawCircle(
      HalloweenOrange.copy(alpha = 0.8f * a * flicker),
      radius = (1f + 1.2f * hash01(i, 14)).dp.toPx(),
      center = Offset(x, y),
    )
  }
}

// ---------------------------------------------------------------- New Year ---------------

private val FireworkPalette =
  listOf(
    Color(0xFFFF5E7E),
    Color(0xFFFFC857),
    Color(0xFF6BE0FF),
    Color(0xFFA98BFF),
    Color(0xFF7DFFB3),
    Color(0xFFFF8F4D),
  )

private const val FW_SLOT = 2.4f
private const val FW_RISE = 0.8f
private const val FW_BURST = 2.8f
private const val FW_SPARKS = 34

private fun sparkOffset(
  angle: Float,
  speed: Float,
  age: Float,
  gravity: Float,
): Offset {
  // Drag makes the spark decelerate; gravity pulls it down once it has slowed
  val travel = speed * (1f - exp(-2.4f * age)) / 2.4f * 2.6f
  return Offset(cos(angle) * travel, sin(angle) * travel + gravity * age * age)
}

/** A rocket climbs, bursts into a ring of sparks that droop and fade; a new one every 2.4 s. */
internal fun DrawScope.drawFireworks(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)
  val current = floor(t / FW_SLOT).toInt()
  for (k in (current - 2)..current) {
    val age = t - k * FW_SLOT
    if (age < 0f) continue
    val cx = w * (0.14f + 0.72f * hash01(k, 1)) + tilt.x * 14.dp.toPx()
    val cy = h * (0.14f + 0.36f * hash01(k, 2)) + tilt.y * 14.dp.toPx()
    val color = FireworkPalette[(hash01(k, 3) * FireworkPalette.size).toInt().coerceIn(0, FireworkPalette.size - 1)]

    if (age < FW_RISE) {
      // Rocket: a short bright streak climbing from the bottom to the burst point
      val u = age / FW_RISE
      val e = 1f - (1f - u) * (1f - u)
      val y = h + (cy - h) * e
      drawLine(
        Brush.verticalGradient(
          listOf(color.copy(alpha = 0.9f), Color.Transparent),
          startY = y,
          endY = y + 36.dp.toPx(),
        ),
        start = Offset(cx, y + 36.dp.toPx()),
        end = Offset(cx, y),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
      )
      continue
    }

    val a = age - FW_RISE
    if (a > FW_BURST) continue
    val life = a / FW_BURST
    if (a < 0.32f) {
      val flash = 1f - a / 0.32f
      drawCircle(
        Brush.radialGradient(
          listOf(color.copy(alpha = 0.20f * flash * flash), Color.Transparent),
          center = Offset(cx, cy),
          radius =
            base * 0.30f,
        ),
        radius = base * 0.30f,
        center = Offset(cx, cy),
      )
    }
    val gravity = base * 0.035f
    val sparks = backdropCount(FW_SPARKS, minimum = 22, maximum = 46)
    for (i in 0 until sparks) {
      val ang = i * TAU / sparks + hash01(i, k) * 0.12f
      val speed = base * (0.17f + 0.12f * hash01(i, k + 77))
      val head = Offset(cx, cy) + sparkOffset(ang, speed, a, gravity)
      val tail = Offset(cx, cy) + sparkOffset(ang, speed, (a - 0.07f).coerceAtLeast(0f), gravity)
      val fade = (1f - life).pow(1.4f) * (0.85f + 0.15f * sin(a * 7f + i))
      drawLine(color.copy(alpha = 0.55f * fade), tail, head, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
      drawCircle(color.copy(alpha = 0.95f * fade), radius = 1.5.dp.toPx(), center = head)
    }
  }
}

// ---------------------------------------------------------------- Winter -----------------

private val BulbColors = listOf(Color(0xFFFF4D4D), Color(0xFFFFC83D), Color(0xFF4DD2FF), Color(0xFF7CFF6B))

/** Snowfall under a string of blinking festive lights, with a cold glow rising from the bottom. */
internal fun DrawScope.drawWinter(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  path: Path,
) {
  val w = size.width
  val h = size.height
  drawRect(
    brush =
      Brush.radialGradient(
        listOf(Color(0xFF6EC1FF).copy(alpha = 0.14f), Color.Transparent),
        center =
          Offset(
            w * 0.5f,
            h * 1.05f,
          ),
        radius = h * 0.55f,
      ),
    size = size,
  )
  drawSnow(t, tilt, p, intensity = 0.9f)

  // Two garlands, the second shifted so the swags interleave
  for (line in 0 until 2) {
    val swags = 4
    val sag = 22.dp.toPx()
    val topY = (10 + 26 * line).dp.toPx() + tilt.y * 6.dp.toPx()
    val wire = path.apply { rewind() }
    val steps = 40
    for (s in 0..steps) {
      val x = w * s / steps
      val phase = (x / w * swags + line * 0.5f)
      val y = topY + sag * abs(sin(phase * PI.toFloat()))
      if (s == 0) wire.moveTo(x, y) else wire.lineTo(x, y)
    }
    drawPath(wire, p.ink.copy(alpha = 0.20f), style = Stroke(width = 1.dp.toPx()))
    val bulbs = 14
    for (b in 0..bulbs) {
      val x = w * b / bulbs
      val phase = (x / w * swags + line * 0.5f)
      val y = topY + sag * abs(sin(phase * PI.toFloat())) + 5.dp.toPx()
      val blink = 0.55f + 0.45f * wave01(t * 0.8f + b * 0.65f + line * 2f)
      val c = BulbColors[(b + line) % BulbColors.size]
      drawCircle(c.copy(alpha = 0.22f * blink), radius = 11.dp.toPx(), center = Offset(x, y))
      drawCircle(c.copy(alpha = 0.55f + 0.4f * blink), radius = 2.6.dp.toPx(), center = Offset(x, y))
    }
  }
}

// ---------------------------------------------------------------- Diwali -----------------

private val Gold = Color(0xFFFFC857)
private val Flame = Color(0xFFFF8A1F)
private val ClayBowl = Color(0xFFC2570C)

/** A turning gold mandala, a row of flickering diyas along the bottom and sparks drifting up. */
internal fun DrawScope.drawDiwali(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  path: Path,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)

  drawRect(
    brush =
      Brush.radialGradient(
        listOf(Flame.copy(alpha = 0.20f), Color.Transparent),
        center =
          Offset(
            w * 0.5f,
            h * 1.04f,
          ),
        radius = h * 0.70f,
      ),
    size = size,
  )

  // Mandala: two rings of petals turning in opposite directions
  val mc = Offset(w * 0.5f + tilt.x * 10.dp.toPx(), h * 0.36f + tilt.y * 10.dp.toPx())
  for (ring in 0 until 2) {
    val n = 12 + 6 * ring
    val radius = base * (0.20f + 0.15f * ring)
    val pw = base * (0.075f - 0.015f * ring)
    val ph = base * (0.19f - 0.03f * ring)
    val turn = t * (if (ring == 0) 0.045f else -0.03f)
    for (i in 0 until n) {
      val a = turn + i * TAU / n
      val pt = Offset(mc.x + cos(a) * radius, mc.y + sin(a) * radius)
      rotate(a * 57.29578f + 90f, pivot = pt) {
        drawOval(
          Gold.copy(alpha = 0.16f - 0.04f * ring),
          topLeft = Offset(pt.x - pw / 2f, pt.y - ph / 2f),
          size = Size(pw, ph),
          style = Stroke(width = 1.2.dp.toPx()),
        )
      }
    }
    drawCircle(Gold.copy(alpha = 0.10f), radius = radius, center = mc, style = Stroke(width = 1.dp.toPx()))
  }
  drawCircle(Gold.copy(alpha = 0.14f), radius = base * 0.045f, center = mc, style = Stroke(width = 1.2.dp.toPx()))

  // Sparks
  for (i in 0 until backdropCount(34, minimum = 20, maximum = 43)) {
    val u = fract(hash01(i, 1) + t * (0.02f + 0.035f * hash01(i, 2)))
    val x = w * hash01(i, 3) + sin(u * TAU * 2f + i) * 22.dp.toPx() + tilt.x * 14.dp.toPx()
    val y = h * (1.02f - 0.95f * u)
    val a = smoothstep(0f, 0.1f, u) * smoothstep(1f, 0.55f, u)
    val tw = 0.75f + 0.25f * sin(t * 2f + i * 2.3f)
    drawCircle(Gold.copy(alpha = 0.12f * a), radius = 6.dp.toPx(), center = Offset(x, y))
    drawCircle(
      Gold.copy(alpha = 0.85f * a * tw),
      radius = (0.9f + 1.3f * hash01(i, 4)).dp.toPx(),
      center = Offset(x, y),
    )
  }

  // Diyas
  val count = 5
  val s = min(w / (count * 2.3f), 26.dp.toPx())
  for (k in 0 until count) {
    val x = w * (0.5f + (k - (count - 1) / 2f) * 0.19f)
    val y = h - 38.dp.toPx()
    val flick = 0.9f + 0.10f * sin(t * 4f + k * 2.1f) + 0.04f * sin(t * 7f + k)
    val sway = sin(t * 3.2f + k) * s * 0.10f
    val fh = s * 1.05f * flick
    val fw = s * 0.30f
    drawCircle(
      Brush.radialGradient(
        listOf(Flame.copy(alpha = 0.32f * flick), Color.Transparent),
        center = Offset(x, y - s * 0.5f),
        radius =
          s * 3.4f,
      ),
      radius = s * 3.4f,
      center = Offset(x, y - s * 0.5f),
    )
    translate(left = x, top = y) {
      val bowl =
        path.apply {
          rewind()
          moveTo(-s, 0f)
          quadraticTo(0f, s * 1.1f, s, 0f)
          quadraticTo(0f, -s * 0.18f, -s, 0f)
          close()
        }
      drawPath(bowl, ClayBowl.copy(alpha = 0.85f))
      drawPath(bowl, Gold.copy(alpha = 0.30f), style = Stroke(width = 1.dp.toPx()))
      val flame =
        path.apply {
          rewind()
          moveTo(sway, -fh)
          cubicTo(fw + sway * 0.5f, -fh * 0.45f, fw * 0.9f, -s * 0.05f, 0f, -s * 0.05f)
          cubicTo(-fw * 0.9f, -s * 0.05f, -fw + sway * 0.5f, -fh * 0.45f, sway, -fh)
          close()
        }
      drawPath(flame, Brush.verticalGradient(listOf(Color(0xFFFFF2B0), Flame), startY = -fh, endY = 0f))
    }
  }
}

// ---------------------------------------------------------------- Holi -------------------

private val GulalPalette =
  listOf(
    Color(0xFFFF1F8E),
    Color(0xFFFFD400),
    Color(0xFF00C2FF),
    Color(0xFF3DDC84),
    Color(0xFFFF7A00),
    Color(0xFF8E44FF),
  )

private const val HOLI_SLOT = 2f
private const val HOLI_LIFE = 5.6f

/** Clouds of coloured powder bloom at random spots, scatter grains and thin out; dust drifts between. */
internal fun DrawScope.drawHoli(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)
  val current = floor(t / HOLI_SLOT).toInt()
  val shift = Offset(tilt.x * 18.dp.toPx(), tilt.y * 18.dp.toPx())

  for (k in (current - 3)..current) {
    val age = t - k * HOLI_SLOT
    if (age < 0f || age > HOLI_LIFE) continue
    val x = age / HOLI_LIFE
    val grow = 1f - (1f - x).pow(3f)
    val c = Offset(w * (0.12f + 0.76f * hash01(k, 1)), h * (0.12f + 0.76f * hash01(k, 2)) - base * x * 0.08f) + shift
    val color = GulalPalette[(hash01(k, 3) * GulalPalette.size).toInt().coerceIn(0, GulalPalette.size - 1)]
    val radius = base * (0.10f + 0.34f * grow)
    val alpha = 0.40f * (1f - x).pow(1.2f) * smoothstep(0f, 0.06f, x)
    val rimColor = lerp(color, GulalPalette[Math.floorMod(k, GulalPalette.size)], 0.25f)
    drawCircle(
      Brush.radialGradient(
        listOf(color.copy(alpha = alpha), rimColor.copy(alpha = alpha * 0.4f), Color.Transparent),
        center = c,
        radius = radius,
      ),
      radius = radius,
      center = c,
    )
    // Grains thrown outwards
    for (i in 0 until backdropCount(26, minimum = 16, maximum = 34)) {
      val ang = hash01(i, k + 5) * TAU
      val dist = radius * (0.25f + 0.95f * hash01(i, k + 9)) * (0.4f + 0.6f * grow)
      val g = Offset(c.x + cos(ang) * dist, c.y + sin(ang) * dist)
      drawCircle(color.copy(alpha = alpha * 1.6f), radius = (1.2f + 2.2f * hash01(i, k + 13)).dp.toPx(), center = g)
    }
  }

  // Loose dust in the air
  for (i in 0 until backdropCount(30, minimum = 18, maximum = 42)) {
    val u = fract(hash01(i, 21) + t * (0.012f + 0.02f * hash01(i, 22)))
    val dx = w * fract(hash01(i, 23) + sin(t * 0.1f + i) * 0.02f)
    val dy = h * (1.03f - 1.06f * u)
    val a = smoothstep(0f, 0.1f, u) * smoothstep(1f, 0.85f, u)
    val color = GulalPalette[i % GulalPalette.size]
    drawCircle(
      color.copy(alpha = 0.55f * a),
      radius = (1.1f + 1.6f * hash01(i, 24)).dp.toPx(),
      center =
        Offset(dx, dy) + shift * 0.6f,
    )
  }
}
