/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.player.visualizer.AudioFeatures
import kotlinx.coroutines.isActive

/**
 * A quiet, audio-reactive timeline. Dragging only previews the target; mpv receives one seek
 * after release so scrubbing cannot emit decoded audio fragments between rapid native seeks.
 */
@Composable
internal fun AudioReactiveSeekbar(
  position: Float,
  duration: Float,
  features: AudioFeatures,
  isPlaying: Boolean,
  primary: Color,
  secondary: Color,
  inactive: Color,
  onSeekFinished: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 1f
  var widthPx by remember { mutableFloatStateOf(1f) }
  var dragging by remember { mutableStateOf(false) }
  var previewPosition by remember { mutableFloatStateOf(position.coerceIn(0f, safeDuration)) }
  var animationFrame by remember { mutableLongStateOf(0L) }

  LaunchedEffect(position, safeDuration, dragging) {
    if (!dragging) previewPosition = position.coerceIn(0f, safeDuration)
  }
  LaunchedEffect(isPlaying) {
    while (isActive && isPlaying) {
      withFrameNanos { animationFrame = it }
    }
  }

  val displayedPosition = if (dragging) previewPosition else position.coerceIn(0f, safeDuration)
  val progress = (displayedPosition / safeDuration).coerceIn(0f, 1f)
  val redrawFrame = animationFrame

  Column(modifier = modifier.fillMaxWidth()) {
    Canvas(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(44.dp)
          .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
          .pointerInput(safeDuration, widthPx) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              dragging = true
              previewPosition = (down.position.x / widthPx).coerceIn(0f, 1f) * safeDuration
              val completed =
                drag(down.id) { change ->
                  change.consume()
                  previewPosition = (change.position.x / widthPx).coerceIn(0f, 1f) * safeDuration
                }
              if (completed) onSeekFinished(previewPosition)
              dragging = false
            }
          },
    ) {
      @Suppress("UNUSED_VARIABLE")
      val frameInvalidation = redrawFrame
      val centerY = size.height / 2f
      val spectrum = features.logSpectrum
      val barCount = 56
      val gap = 2.dp.toPx()
      val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
      val playedX = size.width * progress

      drawLine(
        color = inactive.copy(alpha = 0.30f),
        start = Offset(0f, centerY),
        end = Offset(size.width, centerY),
        strokeWidth = 3.dp.toPx(),
        cap = StrokeCap.Round,
      )

      repeat(barCount) { index ->
        val sourceIndex = (index * spectrum.size / barCount).coerceIn(0, spectrum.lastIndex)
        val liveMagnitude = spectrum[sourceIndex].coerceIn(0f, 1f) * features.volumeScale
        val shaped = 0.16f + liveMagnitude * 0.84f
        val height = (4.dp.toPx() + shaped * 13.dp.toPx()).coerceAtMost(size.height * 0.78f)
        val x = index * (barWidth + gap) + barWidth / 2f
        drawLine(
          color = if (x <= playedX) primary else inactive.copy(alpha = 0.52f),
          start = Offset(x, centerY - height / 2f),
          end = Offset(x, centerY + height / 2f),
          strokeWidth = barWidth,
          cap = StrokeCap.Round,
        )
      }

      drawCircle(
        brush = Brush.radialGradient(listOf(secondary.copy(alpha = 0.25f), Color.Transparent)),
        radius = 13.dp.toPx(),
        center = Offset(playedX, centerY),
      )
      drawCircle(color = primary, radius = 7.dp.toPx(), center = Offset(playedX, centerY))
      drawCircle(color = Color.White.copy(alpha = 0.92f), radius = 3.dp.toPx(), center = Offset(playedX, centerY))
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      androidx.compose.material3.Text(
        text = formatAudioTime(displayedPosition),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = inactive,
      )
      androidx.compose.material3.Text(
        text = "-${formatAudioTime((safeDuration - displayedPosition).coerceAtLeast(0f))}",
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = inactive,
      )
    }
  }
}

private fun formatAudioTime(seconds: Float): String {
  val total = seconds.takeIf { it.isFinite() }?.toLong()?.coerceAtLeast(0L) ?: 0L
  val hours = total / 3600L
  val minutes = (total % 3600L) / 60L
  val remainingSeconds = total % 60L
  return if (hours > 0L) {
    "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
  } else {
    "%d:%02d".format(minutes, remainingSeconds)
  }
}
