/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Renders an animated spectrum bar chart with gradient colors and rounded caps.
 */
@Composable
fun BarsVisualizerCanvas(
  data: VisualizationData,
  modifier: Modifier = Modifier,
  primaryColor: Color = Color(0xFF6200EE),
  secondaryColor: Color = Color(0xFF03DAC6),
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val bars = data.magnitudes
    if (bars.isEmpty()) return@Canvas

    val width = size.width
    val height = size.height
    val barCount = bars.size
    val totalSpacing = width * 0.15f
    val barWidth = (width - totalSpacing) / barCount
    val spacing = totalSpacing / (barCount + 1)

    val gradient = Brush.verticalGradient(
      colors = listOf(secondaryColor, primaryColor),
      startY = 0f,
      endY = height,
    )

    for (i in 0 until barCount) {
      val barHeight = (bars[i] * height * 0.85f).coerceAtLeast(4f)
      val left = spacing + i * (barWidth + spacing)
      val top = height - barHeight

      drawRoundRect(
        brush = gradient,
        topLeft = Offset(left, top),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
      )
    }
  }
}

/**
 * Renders a smooth time-domain oscilloscope wave line based on PCM samples.
 */
@Composable
fun WaveVisualizerCanvas(
  data: VisualizationData,
  modifier: Modifier = Modifier,
  lineColor: Color = Color(0xFF03DAC6),
  strokeWidth: Float = 4f,
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val samples = data.waveform
    if (samples.isEmpty()) return@Canvas

    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val stepX = width / (samples.size - 1)

    val path = Path()
    for (i in samples.indices) {
      val x = i * stepX
      val y = centerY + samples[i] * (height * 0.35f)

      if (i == 0) {
        path.moveTo(x, y)
      } else {
        path.lineTo(x, y)
      }
    }

    drawPath(
      path = path,
      color = lineColor,
      style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
  }
}

/**
 * Renders a circular pulsing spectrum ring reacting to bass/mid/treble audio features.
 */
@Composable
fun CircleVisualizerCanvas(
  data: VisualizationData,
  modifier: Modifier = Modifier,
  primaryColor: Color = Color(0xFFBB86FC),
  secondaryColor: Color = Color(0xFF03DAC6),
) {
  Canvas(modifier = modifier.fillMaxSize()) {
    val bars = data.magnitudes
    if (bars.isEmpty()) return@Canvas

    val center = Offset(size.width / 2f, size.height / 2f)
    val baseRadius = (minOf(size.width, size.height) / 4f) * (1f + data.bass * 0.25f)
    val count = bars.size

    val gradient = Brush.radialGradient(
      colors = listOf(primaryColor, secondaryColor),
      center = center,
      radius = baseRadius * 1.8f,
    )

    // Inner pulsing ring
    drawCircle(
      brush = gradient,
      radius = baseRadius * 0.85f,
      center = center,
      alpha = 0.35f + data.energy * 0.45f,
    )

    // Radial spectrum spokes
    for (i in 0 until count) {
      val angle = (2.0 * PI * i / count).toFloat()
      val barLen = (bars[i] * baseRadius * 0.8f).coerceAtLeast(6f)

      val startX = center.x + baseRadius * cos(angle)
      val startY = center.y + baseRadius * sin(angle)
      val endX = center.x + (baseRadius + barLen) * cos(angle)
      val endY = center.y + (baseRadius + barLen) * sin(angle)

      drawLine(
        color = secondaryColor,
        start = Offset(startX, startY),
        end = Offset(endX, endY),
        strokeWidth = 3f + bars[i] * 2f,
        cap = StrokeCap.Round,
      )
    }
  }
}
