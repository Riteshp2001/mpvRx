package app.gyrolet.mpvrx.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlin.math.abs
import kotlin.math.sign

/** Physical left/right gestures stay in that direction, including in RTL layouts. */
@Composable
internal fun SwipeDismissibleToast(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  onInteracting: (Boolean) -> Unit = {},
  content: @Composable () -> Unit,
) {
  var width by remember { mutableIntStateOf(0) }
  var offset by remember { mutableFloatStateOf(0f) }
  val dismissToast by rememberUpdatedState(onDismiss)
  val interacting by rememberUpdatedState(onInteracting)
  val density = LocalDensity.current
  val flingThreshold = with(density) { 900.dp.toPx() }
  val minimumFlingDistance = with(density) { 8.dp.toPx() }
  val reducedMotion = AppMotion.shouldReduceMotion()
  DisposableEffect(Unit) {
    onDispose { interacting(false) }
  }
  Box(
    modifier
      .onSizeChanged { width = it.width }
      .graphicsLayer {
        translationX = offset
        alpha = (1f - abs(offset) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
      }.semantics {
        dismiss {
          dismissToast()
          true
        }
      }.draggable(
        state = rememberDraggableState { offset += it },
        orientation = Orientation.Horizontal,
        reverseDirection = false,
        onDragStarted = { interacting(true) },
        onDragStopped = { velocity ->
          val fling =
            abs(velocity) >= flingThreshold && abs(offset) >= minimumFlingDistance && velocity.sign == offset.sign
          val shouldDismiss = width > 0 && (abs(offset) >= width * 0.3f || fling)
          val target = if (shouldDismiss) offset.sign * width.coerceAtLeast(1) else 0f
          try {
            animate(
              initialValue = offset,
              targetValue = target,
              initialVelocity = if (reducedMotion) 0f else velocity,
              animationSpec =
                when {
                  reducedMotion -> snap()
                  shouldDismiss -> tween(160, easing = FastOutSlowInEasing)
                  else -> AppMotion.Spatial.Standard
                },
            ) { value, _ -> offset = value }
            if (shouldDismiss) dismissToast()
          } finally {
            interacting(false)
          }
        },
      ),
  ) { content() }
}
