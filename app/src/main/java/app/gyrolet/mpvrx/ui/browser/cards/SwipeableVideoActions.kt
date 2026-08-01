/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.LocalMotionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SwipeableVideoActions(
  itemKey: String,
  enabled: Boolean,
  isWatched: Boolean,
  onWatchedChange: (Boolean) -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  content: @Composable () -> Unit,
) {
  val actionWidth = 88.dp
  val density = LocalDensity.current
  val leftRevealPx = with(density) { actionWidth.toPx() }
  val rightRevealPx = with(density) { actionWidth.toPx() }
  val thresholdPx = with(density) { 56.dp.toPx() }
  val scope = rememberCoroutineScope()
  val reduceMotion = LocalMotionPolicy.current.reduceMotion
  val currentIsWatched by rememberUpdatedState(isWatched)
  val currentOnWatchedChange by rememberUpdatedState(onWatchedChange)
  var offsetX by remember(itemKey) { mutableFloatStateOf(0f) }
  var settleJob by remember(itemKey) { androidx.compose.runtime.mutableStateOf<Job?>(null) }

  fun settle(
    target: Float,
    action: (() -> Unit)? = null,
  ) {
    settleJob?.cancel()
    action?.invoke()
    if (reduceMotion) {
      offsetX = target
      return
    }
    settleJob =
      scope.launch {
        animate(
          initialValue = offsetX,
          targetValue = target,
          animationSpec = AppMotion.Spatial.StandardDefault,
        ) { value, _ -> offsetX = value }
      }
  }

  LaunchedEffect(enabled) {
    if (!enabled) {
      settleJob?.cancel()
      offsetX = 0f
    }
  }

  val shape = AppShapeScale.large
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surface),
  ) {
    // Swipe right → reveal left action (Watched/Unwatch)
    if (offsetX > 0f) {
      val progress = (offsetX / leftRevealPx).coerceIn(0f, 1f)
      Box(
        modifier = Modifier.matchParentSize(),
        contentAlignment = Alignment.CenterStart,
      ) {
        SwipePillAction(
          label = if (isWatched) "Unwatch" else "Watched",
          icon = if (isWatched) Icons.RoundedFilled.RemoveCircle else Icons.RoundedFilled.CheckCircle,
          background = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          width = actionWidth,
          progress = progress,
          onClick = null,
        )
      }
    }

    // Swipe left → reveal right actions (Rename + Delete)
    if (offsetX < 0f) {
      val progress = (abs(offsetX) / rightRevealPx).coerceIn(0f, 1f)
      Row(
        modifier = Modifier.matchParentSize(),
        horizontalArrangement = Arrangement.End,
      ) {
        SwipePillAction(
          label = "Rename",
          icon = Icons.RoundedFilled.Edit,
          background = MaterialTheme.colorScheme.secondaryContainer,
          contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          width = actionWidth,
          progress = progress,
          onClick = { settle(0f, onRename) },
        )
        SwipePillAction(
          label = "Delete",
          icon = Icons.RoundedFilled.Delete,
          background = MaterialTheme.colorScheme.errorContainer,
          contentColor = MaterialTheme.colorScheme.onErrorContainer,
          width = actionWidth,
          progress = progress,
          onClick = { settle(0f, onDelete) },
        )
      }
    }

    // Draggable content
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .offset { IntOffset(offsetX.roundToInt(), 0) }
          .background(MaterialTheme.colorScheme.surface)
          .then(
            if (enabled) {
              Modifier.pointerInput(itemKey, leftRevealPx, rightRevealPx) {
                detectHorizontalDragGestures(
                  onDragStart = { settleJob?.cancel() },
                  onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceIn(-leftRevealPx, rightRevealPx)
                  },
                  onDragEnd = {
                    when {
                      offsetX >= thresholdPx -> {
                        val targetWatched = !currentIsWatched
                        settle(0f) { currentOnWatchedChange(targetWatched) }
                      }
                      offsetX <= -thresholdPx -> settle(-rightRevealPx)
                      else -> settle(0f)
                    }
                  },
                  onDragCancel = { settle(0f) },
                )
              }
            } else {
              Modifier
            },
          ),
    ) {
      content()
    }
  }
}

@Composable
private fun SwipePillAction(
  label: String,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  background: Color,
  contentColor: Color,
  width: androidx.compose.ui.unit.Dp,
  progress: Float,
  onClick: (() -> Unit)?,
) {
  val iconScale by animateFloatAsState(
    targetValue = if (progress > 0.5f) 1f else 0.6f,
    label = "iconScale",
  )
  val alpha by animateFloatAsState(
    targetValue = progress.coerceIn(0.3f, 1f),
    label = "alpha",
  )

  Box(
    modifier =
      Modifier
        .width(width)
        .fillMaxHeight()
        .graphicsLayer {
          scaleX = iconScale
          scaleY = iconScale
          this.alpha = alpha
        }
        .padding(8.dp)
        .clip(RoundedCornerShape(28.dp))
        .background(background)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      icon,
      contentDescription = label,
      tint = contentColor,
      modifier = Modifier.size(28.dp),
    )
  }
}
