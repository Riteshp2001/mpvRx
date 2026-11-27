package app.gyrolet.mpvrx.ui.background

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Layered, flowing curtains. All movement is continuous, including the glow and star pulses. */
internal fun DrawScope.drawAurora(
  t: Float,
  tilt: Offset,
  p: BackdropPalette,
  path: Path,
) {
  val w = size.width
  val h = size.height
  val shift = Offset(tilt.x * 18.dp.toPx(), tilt.y * 12.dp.toPx())
  val glow = Offset(w * (0.66f + 0.12f * sin(t * 0.08f)), h * 0.22f) + shift * 0.4f
  drawCircle(
    Brush.radialGradient(listOf(p.primary.copy(alpha = 0.14f), Color.Transparent), center = glow, radius = w * 0.85f),
    radius = w * 0.85f,
    center = glow,
  )
  for (ribbon in 0 until 4) {
    val phase = t * (0.10f + ribbon * 0.018f) + ribbon * 1.7f
    val centerY = h * (0.24f + ribbon * 0.13f) + shift.y
    val amplitude = h * (0.07f + 0.025f * sin(phase * 0.7f))
    path.rewind()
    val steps = 32
    for (step in 0..steps) {
      val x = w * (-0.12f + 1.24f * step / steps) + shift.x
      val u = step.toFloat() / steps
      val y = centerY + sin(u * TAU * 0.8f + phase) * amplitude + cos(u * TAU * 1.5f - phase * 0.8f) * amplitude * 0.3f
      if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    val color = p.accent(ribbon)
    val breath = 0.75f + 0.25f * sin(t * 0.24f + ribbon)
    val brush =
      Brush.horizontalGradient(
        0f to Color.Transparent,
        0.25f to color.copy(alpha = 0.28f * breath),
        0.65f to p.accent(ribbon + 1).copy(alpha = 0.24f * breath),
        1f to Color.Transparent,
        startX = 0f,
        endX = w,
      )
    // Soft translucent curtains with a readable, narrow core; still no blur pass.
    drawPath(path, brush, style = Stroke(width = (32f + ribbon * 7f).dp.toPx(), cap = StrokeCap.Round), alpha = 0.32f)
    drawPath(path, brush, style = Stroke(width = (12f + ribbon * 3f).dp.toPx(), cap = StrokeCap.Round), alpha = 0.62f)
    drawPath(path, brush, style = Stroke(width = (2.5f + ribbon).dp.toPx(), cap = StrokeCap.Round))
  }
  for (i in 0 until backdropCount(26, minimum = 15, maximum = 34)) {
    val center = Offset(w * hash01(i, 31), h * hash01(i, 32)) + shift * (0.25f + 0.35f * hash01(i, 33))
    val pulse = wave01(t * (0.4f + hash01(i, 34) * 0.5f) + hash01(i, 35) * TAU)
    drawCircle(p.ink.copy(alpha = 0.12f + 0.34f * pulse), radius = (0.6f + hash01(i, 36)).dp.toPx(), center = center)
  }
}
