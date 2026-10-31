/* SPDX-License-Identifier: AGPL-3.0-or-later */
package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/** Captures both axes from the seekbar so lifting a scrub never turns into a player swipe. */
internal fun Modifier.thumbnailScrubInput(
  duration: Float,
  position: () -> Float,
  onPosition: (Float) -> Unit,
  onExpanded: (Boolean) -> Unit,
  onFinished: (Float) -> Unit,
  onCancelled: () -> Unit,
): Modifier = semantics {
  progressBarRangeInfo = ProgressBarRangeInfo(position().coerceIn(0f, duration), 0f..duration)
  setProgress { target ->
    if (!target.isFinite() || duration <= 0f) false else {
      onFinished(target.coerceIn(0f, duration))
      true
    }
  }
}.pointerInput(duration) {
  if (duration <= 0f) return@pointerInput
  awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    val width = size.width.coerceAtLeast(1).toFloat()
    var target = (down.position.x / width).coerceIn(0f, 1f) * duration
    var lastX = down.position.x
    var expanded = false
    var finished = false
    try {
      onPosition(target)
      while (true) {
        val event = awaitPointerEvent()
        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
        if (event.changes.any { it.id != down.id && it.pressed } || pointer.isConsumed) break
        if (!pointer.pressed) {
          pointer.consume()
          finished = true
          onFinished(target)
          break
        }
        val lifted = down.position.y - pointer.position.y
        val nextExpanded = if (expanded) lifted > 32.dp.toPx() else lifted > 64.dp.toPx()
        if (nextExpanded != expanded) {
          expanded = nextExpanded
          onExpanded(expanded)
          // Anchor at the current position when switching precision: no timestamp jump.
          lastX = pointer.position.x
        }
        val span = if (expanded) minOf(duration / 5f, 120f) else duration
        target = (target + (pointer.position.x - lastX) / width * span).coerceIn(0f, duration)
        lastX = pointer.position.x
        onPosition(target)
        pointer.consume()
      }
    } finally {
      onExpanded(false)
      if (!finished) onCancelled()
    }
  }
}
