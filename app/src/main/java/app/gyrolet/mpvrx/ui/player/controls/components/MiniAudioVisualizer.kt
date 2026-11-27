package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A sleek mini audio visualizer bar indicator that animates when playing
 * and pauses smoothly at resting height when playback is paused.
 */
@Composable
fun MiniAudioVisualizer(
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
  barCount: Int = 3,
  barWidth: Dp = 2.5.dp,
  barSpacing: Dp = 2.dp,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "mini_visualizer_bars")

  val bar1Target by if (isPlaying) {
    infiniteTransition.animateFloat(
      initialValue = 0.2f,
      targetValue = 0.95f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 480, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "bar_1",
    )
  } else {
    remember { mutableFloatStateOf(0.3f) }
  }

  val bar2Target by if (isPlaying) {
    infiniteTransition.animateFloat(
      initialValue = 0.4f,
      targetValue = 1.0f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 620, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "bar_2",
    )
  } else {
    remember { mutableFloatStateOf(0.65f) }
  }

  val bar3Target by if (isPlaying) {
    infiniteTransition.animateFloat(
      initialValue = 0.2f,
      targetValue = 0.85f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 400, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "bar_3",
    )
  } else {
    remember { mutableFloatStateOf(0.25f) }
  }

  val bar4Target by if (isPlaying) {
    infiniteTransition.animateFloat(
      initialValue = 0.3f,
      targetValue = 0.75f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 540, easing = LinearEasing),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "bar_4",
    )
  } else {
    remember { mutableFloatStateOf(0.45f) }
  }

  val bar1Height by animateFloatAsState(targetValue = bar1Target, label = "bar1_smooth")
  val bar2Height by animateFloatAsState(targetValue = bar2Target, label = "bar2_smooth")
  val bar3Height by animateFloatAsState(targetValue = bar3Target, label = "bar3_smooth")
  val bar4Height by animateFloatAsState(targetValue = bar4Target, label = "bar4_smooth")

  val anims = listOf(bar1Height, bar2Height, bar3Height, bar4Height)

  Canvas(modifier = modifier) {
    val count = barCount.coerceIn(2, 4)
    val widthPx = barWidth.toPx()
    val spacingPx = barSpacing.toPx()
    val totalWidth = count * widthPx + (count - 1) * spacingPx
    val startX = (size.width - totalWidth) / 2f
    val cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)

    for (i in 0 until count) {
      val barHeightFraction = anims[i % anims.size].coerceIn(0.15f, 1f)
      val barHeight = size.height * barHeightFraction
      val x = startX + i * (widthPx + spacingPx)
      val y = size.height - barHeight

      drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(widthPx, barHeight),
        cornerRadius = cornerRadius,
      )
    }
  }
}
