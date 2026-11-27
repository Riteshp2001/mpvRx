package app.gyrolet.mpvrx.ui.utils

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.flow.collect

@Stable
internal class ReorderFeedback(
  private val haptics: AppHaptics,
) {
  val interactions = MutableInteractionSource()
  private var firstIndex: Int? = null
  private var lastIndex: Int? = null

  fun start() {
    firstIndex = null
    lastIndex = null
    haptics.pickup()
  }

  fun move(
    from: Int,
    to: Int,
  ) {
    if (from == to) return
    if (firstIndex == null) firstIndex = from
    lastIndex = to
    haptics.tick()
  }

  internal fun finish(cancelled: Boolean) {
    if (!cancelled && firstIndex != null && firstIndex != lastIndex) haptics.confirm()
    firstIndex = null
    lastIndex = null
  }
}

@Composable
internal fun rememberReorderFeedback(): ReorderFeedback {
  val haptics = rememberAppHaptics()
  val feedback = remember(haptics) { ReorderFeedback(haptics) }
  LaunchedEffect(feedback) {
    feedback.interactions.interactions.collect { interaction ->
      when (interaction) {
        is DragInteraction.Stop -> feedback.finish(cancelled = false)
        is DragInteraction.Cancel -> feedback.finish(cancelled = true)
        else -> Unit
      }
    }
  }
  return feedback
}

@Composable
internal fun dragElevation(
  dragging: Boolean,
  reducedMotion: Boolean = AppMotion.shouldReduceMotion(),
): Dp {
  val elevation by animateDpAsState(
    targetValue = if (dragging && !reducedMotion) 3.dp else 0.dp,
    animationSpec = if (reducedMotion) snap() else AppMotion.Spatial.ExpressiveDp,
    label = "dragElevation",
  )
  return elevation
}
