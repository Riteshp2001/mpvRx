package app.gyrolet.mpvrx.ui.browser.cards

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlinx.coroutines.flow.collect
import org.koin.compose.koinInject
import kotlin.math.abs

@StringRes
internal fun VideoSwipeAction.labelRes(isWatched: Boolean? = false): Int = when (this) {
  VideoSwipeAction.None -> R.string.pref_none
  VideoSwipeAction.ToggleWatched ->
    when (isWatched) {
      true -> R.string.video_action_mark_unwatched
      false -> R.string.video_action_mark_watched
      null -> R.string.pref_video_swipe_toggle_watched
    }
  VideoSwipeAction.AddToPlaylist -> R.string.ui_add_to_playlist
  VideoSwipeAction.PlayNext -> R.string.video_swipe_play_next
  VideoSwipeAction.AddToQueue -> R.string.video_swipe_add_queue
  VideoSwipeAction.Delete -> R.string.delete
  VideoSwipeAction.MarkNew -> R.string.video_swipe_mark_new
  VideoSwipeAction.LastPlayed -> R.string.video_swipe_last_played
  VideoSwipeAction.Finished -> R.string.video_swipe_finished
  VideoSwipeAction.ClearHistory -> R.string.video_swipe_clear_history
}

internal fun VideoSwipeAction.icon(isWatched: Boolean? = false): AppIcon = when (this) {
  VideoSwipeAction.None -> Icons.RoundedFilled.Block
  VideoSwipeAction.ToggleWatched ->
    if (isWatched == true) Icons.RoundedFilled.VisibilityOff else Icons.RoundedFilled.Visibility
  VideoSwipeAction.AddToPlaylist -> Icons.RoundedFilled.PlaylistAdd
  VideoSwipeAction.PlayNext -> Icons.RoundedFilled.SkipNext
  VideoSwipeAction.AddToQueue -> Icons.RoundedFilled.QueueMusic
  VideoSwipeAction.Delete -> Icons.RoundedFilled.Delete
  VideoSwipeAction.MarkNew -> Icons.RoundedFilled.NewReleases
  VideoSwipeAction.LastPlayed -> Icons.RoundedFilled.History
  VideoSwipeAction.Finished -> Icons.RoundedFilled.CheckCircle
  VideoSwipeAction.ClearHistory -> Icons.RoundedFilled.Close
}

@Composable
internal fun VideoSwipeAction.containerColor(): Color = when (this) {
  VideoSwipeAction.Delete -> MaterialTheme.colorScheme.errorContainer
  VideoSwipeAction.ToggleWatched, VideoSwipeAction.Finished -> MaterialTheme.colorScheme.tertiaryContainer
  VideoSwipeAction.AddToQueue, VideoSwipeAction.PlayNext, VideoSwipeAction.LastPlayed ->
    MaterialTheme.colorScheme.secondaryContainer
  VideoSwipeAction.AddToPlaylist, VideoSwipeAction.MarkNew -> MaterialTheme.colorScheme.primaryContainer
  VideoSwipeAction.None, VideoSwipeAction.ClearHistory -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
internal fun VideoSwipeAction.contentColor(): Color = when (this) {
  VideoSwipeAction.Delete -> MaterialTheme.colorScheme.onErrorContainer
  VideoSwipeAction.ToggleWatched, VideoSwipeAction.Finished -> MaterialTheme.colorScheme.onTertiaryContainer
  VideoSwipeAction.AddToQueue, VideoSwipeAction.PlayNext, VideoSwipeAction.LastPlayed ->
    MaterialTheme.colorScheme.onSecondaryContainer
  VideoSwipeAction.AddToPlaylist, VideoSwipeAction.MarkNew -> MaterialTheme.colorScheme.onPrimaryContainer
  VideoSwipeAction.None, VideoSwipeAction.ClearHistory -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun VideoSwipeSurface(
  identity: String,
  leftAction: VideoSwipeAction,
  rightAction: VideoSwipeAction,
  isWatched: Boolean?,
  enabled: Boolean,
  onAction: ((VideoSwipeAction) -> Unit)?,
  modifier: Modifier,
  shape: Shape,
  colors: CardColors,
  content: @Composable ColumnScope.() -> Unit,
) {
  val preferences = koinInject<BrowserPreferences>()
  val rightZonePercent by preferences.videoSwipeRightZonePercent.collectAsState()
  val leftZonePercent by preferences.videoSwipeLeftZonePercent.collectAsState()
  val rightSwipeZoneFraction = rightZonePercent.coerceIn(BrowserPreferences.VIDEO_SWIPE_ZONE_RANGE) / 100f
  val leftSwipeZoneFraction = leftZonePercent.coerceIn(BrowserPreferences.VIDEO_SWIPE_ZONE_RANGE) / 100f
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  val density = LocalDensity.current
  var rowWidth by remember(identity) { mutableIntStateOf(0) }
  var dragOffset by remember(identity) { mutableFloatStateOf(0f) }
  var dragging by remember(identity) { mutableStateOf(false) }
  var thresholdReached by remember(identity) { mutableStateOf(false) }
  var thresholdFeedbackSent by remember(identity) { mutableStateOf(false) }
  val travel = minOf(rowWidth * 0.4f, with(density) { 112.dp.toPx() })
  val threshold = minOf(travel * 0.7f, with(density) { 56.dp.toPx() })
  val canSwipe = enabled && onAction != null &&
    (leftAction != VideoSwipeAction.None || rightAction != VideoSwipeAction.None)
  val currentAction by rememberUpdatedState(onAction)
  val currentLeft by rememberUpdatedState(leftAction)
  val currentRight by rememberUpdatedState(rightAction)
  val currentThreshold by rememberUpdatedState(threshold)
  val displayOffset by animateFloatAsState(
    targetValue = if (canSwipe && dragging) dragOffset else 0f,
    animationSpec = if (dragging || reducedMotion) snap() else AppMotion.Spatial.ExpressiveFast,
    label = "videoSwipeOffset",
  )

  LaunchedEffect(canSwipe, leftAction, rightAction, identity, rowWidth, leftSwipeZoneFraction, rightSwipeZoneFraction) {
    dragOffset = 0f
    dragging = false
    thresholdReached = false
    thresholdFeedbackSent = false
  }
  val accessibilityActions =
    if (canSwipe) {
      listOf(rightAction, leftAction).filter { it != VideoSwipeAction.None }.distinct().map { action ->
        CustomAccessibilityAction(stringResource(action.labelRes(isWatched))) {
          currentAction?.invoke(action)
          true
        }
      }
    } else {
      emptyList()
    }
  val revealedAction = if (displayOffset >= 0f) rightAction else leftAction
  val background by animateColorAsState(
    targetValue = revealedAction.containerColor(),
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "videoSwipeColor",
  )
  val iconScale by animateFloatAsState(
    targetValue = if (thresholdReached && !reducedMotion) 1.12f else 1f,
    animationSpec = AppMotion.spatial(AppMotion.Spatial.ExpressiveFast, snap()),
    label = "videoSwipeIconScale",
  )

  Box(
    propagateMinConstraints = true,
    modifier = modifier
      .onSizeChanged { rowWidth = it.width }
      .semantics { customActions = accessibilityActions }
      .pointerInput(
        identity, canSwipe, isTelevision, rowWidth, leftAction, rightAction,
        travel, threshold, leftSwipeZoneFraction, rightSwipeZoneFraction,
      ) {
        if (!canSwipe || isTelevision || rowWidth <= 0) return@pointerInput
        val leftEdge = rowWidth * rightSwipeZoneFraction
        val rightEdge = rowWidth * (1f - leftSwipeZoneFraction)
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startX = down.position.x
          val canStartRightSwipe = rightAction != VideoSwipeAction.None && startX < leftEdge
          val canStartLeftSwipe = leftAction != VideoSwipeAction.None && startX >= rightEdge
          if (!canStartRightSwipe && !canStartLeftSwipe) {
            return@awaitEachGesture
          }

          val pointerId = down.id
          var totalDeltaX = 0f
          var totalDeltaY = 0f
          var dragStarted = false

          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
            if (!change.pressed) {
              break
            }

            val currentPos = change.position
            val prevPos = change.previousPosition
            val deltaX = currentPos.x - prevPos.x
            val deltaY = currentPos.y - prevPos.y

            totalDeltaX += deltaX
            totalDeltaY += deltaY

            if (!dragStarted) {
              if (change.isConsumed) break
              if (abs(totalDeltaX) > touchSlop && abs(totalDeltaX) > abs(totalDeltaY)) {
                if ((totalDeltaX > 0f && !canStartRightSwipe) || (totalDeltaX < 0f && !canStartLeftSwipe)) {
                  break
                }
                dragStarted = true
                dragging = true
                thresholdReached = false
                thresholdFeedbackSent = false
                dragOffset = 0f
                change.consume()
              } else if (abs(totalDeltaY) > touchSlop) {
                // Vertical scrolling took precedence
                break
              }
            } else {
              change.consume()
              val minimum = if (canStartLeftSwipe) -travel else 0f
              val maximum = if (canStartRightSwipe) travel else 0f
              dragOffset = (dragOffset + deltaX).coerceIn(minimum, maximum)

              if (!thresholdReached && threshold > 0f && abs(dragOffset) >= threshold) {
                thresholdReached = true
                if (!thresholdFeedbackSent) {
                  thresholdFeedbackSent = true
                  haptics.tick()
                }
              } else if (abs(dragOffset) < threshold * 0.8f) {
                thresholdReached = false
              }
            }
          }

          if (dragStarted) {
            val committedOffset = dragOffset
            val action = if (committedOffset > 0f) currentRight else currentLeft
            dragging = false
            dragOffset = 0f
            thresholdReached = false
            thresholdFeedbackSent = false
            if (currentThreshold > 0f && abs(committedOffset) >= currentThreshold && action != VideoSwipeAction.None) {
              currentAction?.invoke(action)
            }
          }
        }
      },
  ) {
    Box(
      modifier = Modifier.matchParentSize().clearAndSetSemantics { }
        .drawWithContent {
          val left = if (displayOffset < 0f) size.width + displayOffset else 0f
          val right = if (displayOffset > 0f) displayOffset else size.width
          if (abs(displayOffset) > 0f) clipRect(left = left, right = right) { this@drawWithContent.drawContent() }
        }.background(background, shape),
    ) {
      Column(
        modifier = Modifier
          .align(if (displayOffset >= 0f) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
          .width(with(density) { travel.toDp() })
          .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Icon(
          imageVector = revealedAction.icon(isWatched),
          contentDescription = null,
          tint = revealedAction.contentColor(),
          modifier = Modifier.size(24.dp).graphicsLayer {
            scaleX = iconScale
            scaleY = iconScale
          },
        )
        Text(
          text = stringResource(revealedAction.labelRes(isWatched)),
          style = MaterialTheme.typography.labelSmall,
          color = revealedAction.contentColor(),
          textAlign = TextAlign.Center,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Card(
      modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = displayOffset },
      shape = shape,
      colors = colors,
      content = content,
    )
  }
}