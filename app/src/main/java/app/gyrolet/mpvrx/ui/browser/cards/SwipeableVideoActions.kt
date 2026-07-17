package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SwipeableVideoActions(
  itemKey: String,
  enabled: Boolean,
  isWatched: Boolean,
  onToggleWatched: () -> Unit,
  onMarkNew: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  content: @Composable () -> Unit,
) {
  val actionWidth = 76.dp
  val density = LocalDensity.current
  val leftRevealPx = with(density) { (actionWidth * 3).toPx() }
  val rightDragLimitPx = with(density) { (actionWidth * 2).toPx() }
  val thresholdPx = with(density) { 56.dp.toPx() }
  val scope = rememberCoroutineScope()
  var offsetX by remember(itemKey) { mutableFloatStateOf(0f) }
  var settleJob by remember(itemKey) { androidx.compose.runtime.mutableStateOf<Job?>(null) }

  fun settle(target: Float, action: (() -> Unit)? = null) {
    settleJob?.cancel()
    action?.invoke()
    settleJob = scope.launch {
      animate(
        initialValue = offsetX,
        targetValue = target,
        animationSpec =
          spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
          ),
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
        .clip(shape),
  ) {
    Box(
      modifier =
        Modifier
          .matchParentSize()
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.CenterStart,
    ) {
      SwipeVideoAction(
        label = if (isWatched) "Unwatch" else "Watched",
        icon = if (isWatched) Icons.Filled.History else Icons.Filled.CheckCircle,
        background = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        width = actionWidth,
        onClick = null,
      )
    }

    Row(
      modifier = Modifier.matchParentSize(),
      horizontalArrangement = Arrangement.End,
    ) {
      SwipeVideoAction(
        label = "New",
        icon = Icons.Default.NewReleases,
        background = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        width = actionWidth,
        onClick = { settle(0f, onMarkNew) },
      )
      SwipeVideoAction(
        label = "Rename",
        icon = Icons.Filled.Edit,
        background = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        width = actionWidth,
        onClick = { settle(0f, onRename) },
      )
      SwipeVideoAction(
        label = "Delete",
        icon = Icons.Filled.Delete,
        background = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        width = actionWidth,
        onClick = { settle(0f, onDelete) },
      )
    }

    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .offset { IntOffset(offsetX.roundToInt(), 0) }
          .background(MaterialTheme.colorScheme.surface)
          .then(
            if (enabled) {
              Modifier.pointerInput(itemKey, leftRevealPx, rightDragLimitPx) {
                detectHorizontalDragGestures(
                  onDragStart = { settleJob?.cancel() },
                  onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount).coerceIn(-leftRevealPx, rightDragLimitPx)
                  },
                  onDragEnd = {
                    when {
                      offsetX >= thresholdPx -> settle(0f, onToggleWatched)
                      offsetX <= -thresholdPx -> settle(-leftRevealPx)
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
private fun SwipeVideoAction(
  label: String,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  background: Color,
  contentColor: Color,
  width: androidx.compose.ui.unit.Dp,
  onClick: (() -> Unit)?,
) {
  Column(
    modifier =
      Modifier
        .width(width)
        .fillMaxHeight()
        .background(background)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(icon, contentDescription = label, tint = contentColor)
    Text(
      text = label,
      color = contentColor,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
    )
  }
}
