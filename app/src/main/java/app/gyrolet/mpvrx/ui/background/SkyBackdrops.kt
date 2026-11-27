package app.gyrolet.mpvrx.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Night-sky family: Space, Particles, Constellation.

/** Three layers of stars sliding at different speeds, two faint nebulae, and a comet every few seconds. */
internal fun DrawScope.drawSpace(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  val base = min(w, h)
  val starColor = lerp(p.ink, p.primary, 0.25f)

  // Nebulae sit behind the stars and barely move
  val n1 = Offset(w * (0.25f + 0.05f * sin(t * 0.03f)), h * 0.30f) + Offset(tilt.x * 8.dp.toPx(), tilt.y * 8.dp.toPx())
  val n2 = Offset(w * (0.80f + 0.05f * cos(t * 0.025f)), h * 0.72f) + Offset(tilt.x * 8.dp.toPx(), tilt.y * 8.dp.toPx())
  drawCircle(
    brush =
      Brush.radialGradient(
        listOf(p.primary.copy(alpha = 0.16f), Color.Transparent),
        center = n1,
        radius =
          base * 0.75f,
      ),
    radius = base * 0.75f,
    center = n1,
  )
  drawCircle(
    brush =
      Brush.radialGradient(
        listOf(p.tertiary.copy(alpha = 0.13f), Color.Transparent),
        center = n2,
        radius =
          base * 0.7f,
      ),
    radius = base * 0.7f,
    center = n2,
  )

  for (i in 0 until backdropCount(112, minimum = 58, maximum = 152)) {
    val layer = i % 3 // 0 far, 2 near
    val depth = (layer + 1) / 3f
    val fx = fract(hash01(i, 1) + t * 0.0016f * (layer + 1))
    val edge = smoothstep(0f, 0.025f, fx) * smoothstep(1f, 0.975f, fx)
    val x = w * fx + tilt.x * 20.dp.toPx() * depth
    val y = h * hash01(i, 2) + tilt.y * 20.dp.toPx() * depth
    val tw = (0.65f + 0.35f * sin(t * (0.4f + 0.9f * hash01(i, 3)) + i * 3.1f)) * edge
    val r = (0.55f + 0.75f * depth + 0.5f * hash01(i, 4)).dp.toPx()
    drawCircle(starColor.copy(alpha = (0.25f + 0.55f * depth) * tw), radius = r, center = Offset(x, y))
    if (layer == 2 && hash01(i, 5) > 0.8f) {
      // A few near stars get a soft halo
      drawCircle(starColor.copy(alpha = 0.06f * tw), radius = r * 4f, center = Offset(x, y))
      val sparkle = starColor.copy(alpha = 0.20f * tw)
      drawLine(sparkle, Offset(x - r * 2.5f, y), Offset(x + r * 2.5f, y), strokeWidth = 0.7.dp.toPx())
      drawLine(sparkle, Offset(x, y - r * 2.5f), Offset(x, y + r * 2.5f), strokeWidth = 0.7.dp.toPx())
    }
  }

  // Comet: one pass per 7 s slot, most of the slot is empty sky
  val slot = kotlin.math.floor(t / 7f).toInt()
  val u = fract(t / 7f) / 0.22f
  if (u in 0f..1f) {
    val sx = w * (0.15f + 0.7f * hash01(slot, 21))
    val sy = h * (0.05f + 0.35f * hash01(slot, 22))
    val dir = Offset(0.82f, 0.57f)
    val travel = base * 0.9f * u
    val head = Offset(sx + dir.x * travel, sy + dir.y * travel)
    val tail = Offset(head.x - dir.x * base * 0.22f, head.y - dir.y * base * 0.22f)
    val fade = smoothstep(0f, 0.15f, u) * smoothstep(1f, 0.7f, u)
    drawLine(
      brush =
        Brush.linearGradient(
          listOf(Color.Transparent, starColor.copy(alpha = 0.9f * fade)),
          start = tail,
          end = head,
        ),
      start = tail,
      end = head,
      strokeWidth = 1.8.dp.toPx(),
      cap = StrokeCap.Round,
    )
    drawCircle(starColor.copy(alpha = 0.9f * fade), radius = 2.2.dp.toPx(), center = head)
  }
}

private fun mote(
  i: Int,
  u: Float,
  w: Float,
  h: Float,
): Offset {
  val sway = 0.12f * sin(u * TAU * 1.4f + i * 1.9f)
  return Offset(w * (0.04f + 0.92f * hash01(i, 3) + sway), h * (1.06f - 1.12f * u))
}

/** Glowing motes that rise on a wavering path, each leaving a short comet-like trail. */
internal fun DrawScope.drawParticles(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
) {
  val w = size.width
  val h = size.height
  for (i in 0 until backdropCount(38, minimum = 22, maximum = 52)) {
    val depth = 0.35f + 0.65f * hash01(i, 6)
    val u = fract(hash01(i, 1) + t * (0.012f + 0.024f * hash01(i, 2)))
    val head = mote(i, u, w, h) + Offset(tilt.x * 24.dp.toPx() * depth, tilt.y * 24.dp.toPx() * depth)
    val tail =
      mote(i, (u - 0.035f).coerceAtLeast(0f), w, h) +
        Offset(tilt.x * 24.dp.toPx() * depth, tilt.y * 24.dp.toPx() * depth)
    val life = smoothstep(0f, 0.15f, u) * smoothstep(1f, 0.82f, u)
    val color = p.accent(i)
    val r = (1.1f + 1.8f * depth).dp.toPx()
    // The tail grows from zero at birth; avoid a zero-length gradient at the wrap seam.
    if (u > 0.001f) {
      // Lightweight dual-stroke trail instead of a separate gradient shader per mote.
      drawLine(
        color = color.copy(alpha = 0.12f * life),
        start = tail,
        end = head,
        strokeWidth = r * 1.55f,
        cap = StrokeCap.Round,
      )
      drawLine(
        color = color.copy(alpha = 0.32f * life),
        start = tail + (head - tail) * 0.55f,
        end = head,
        strokeWidth = r * 0.78f,
        cap = StrokeCap.Round,
      )
    }
    drawCircle(color.copy(alpha = 0.10f * life), radius = r * 4.5f, center = head)
    drawCircle(color.copy(alpha = 0.75f * life), radius = r, center = head)
  }
}

/** Slowly wandering stars joined by lines while they are near each other, a living star-map. */
internal fun DrawScope.drawConstellation(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  drawing: BackdropDrawingState,
) {
  val w = size.width
  val h = size.height
  // The working buffers have a fixed maximum; previews only need a subset.
  val count = backdropCount(CONSTELLATION_STARS, minimum = 18, maximum = CONSTELLATION_STARS)
  val reach = min(w, h) * 0.28f
  val xs = drawing.starX
  val ys = drawing.starY
  val vis = drawing.starVisibility
  val shift = Offset(tilt.x * 14.dp.toPx(), tilt.y * 14.dp.toPx())
  for (i in 0 until count) {
    val vx = (hash01(i, 11) - 0.5f) * 0.012f
    val vy = (hash01(i, 12) - 0.5f) * 0.012f
    val fx = fract(hash01(i, 1) + t * vx)
    val fy = fract(hash01(i, 2) + t * vy)
    xs[i] = fx * w + shift.x
    ys[i] = fy * h + shift.y
    // fade at the wrap seam so a star never visibly jumps from one edge to the other
    vis[i] =
      smoothstep(0f, 0.06f, fx) * smoothstep(1f, 0.94f, fx) * smoothstep(0f, 0.06f, fy) * smoothstep(1f, 0.94f, fy)
  }
  for (i in 0 until count) {
    for (j in i + 1 until count) {
      val dx = xs[i] - xs[j]
      val dy = ys[i] - ys[j]
      val distanceSquared = dx * dx + dy * dy
      if (distanceSquared < reach * reach) {
        val proximity = 1f - distanceSquared / (reach * reach)
        val a = proximity * proximity * 0.30f * min(vis[i], vis[j])
        if (a > 0.01f) {
          drawLine(p.primary.copy(alpha = a), Offset(xs[i], ys[i]), Offset(xs[j], ys[j]), strokeWidth = 0.8.dp.toPx())
        }
      }
    }
  }
  for (i in 0 until count) {
    val tw = 0.6f + 0.4f * sin(t * (0.6f + hash01(i, 3)) + i)
    val r = (1.2f + 1.6f * hash01(i, 4)).dp.toPx()
    val c = Offset(xs[i], ys[i])
    drawCircle(p.ink.copy(alpha = 0.10f * vis[i] * tw), radius = r * 4f, center = c)
    drawCircle(lerp(p.ink, p.secondary, 0.3f).copy(alpha = 0.75f * vis[i] * tw), radius = r, center = c)
  }
}
