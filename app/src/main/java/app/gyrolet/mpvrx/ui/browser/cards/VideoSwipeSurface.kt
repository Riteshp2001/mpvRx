package app.gyrolet.mpvrx.ui.browser.cards

import androidx.annotation.StringRes
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
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
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt

@StringRes
internal fun VideoSwipeAction.labelRes(isWatched: Boolean? = false): Int =
  when (this) {
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

internal fun VideoSwipeAction.icon(isWatched: Boolean? = false): AppIcon =
  when (this) {
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
internal fun VideoSwipeAction.containerColor(): Color =
  when (this) {
    VideoSwipeAction.Delete -> MaterialTheme.colorScheme.errorContainer
    VideoSwipeAction.ToggleWatched, VideoSwipeAction.Finished -> MaterialTheme.colorScheme.tertiaryContainer
    VideoSwipeAction.AddToQueue, VideoSwipeAction.PlayNext, VideoSwipeAction.LastPlayed ->
      MaterialTheme.colorScheme.secondaryContainer
    VideoSwipeAction.AddToPlaylist, VideoSwipeAction.MarkNew -> MaterialTheme.colorScheme.primaryContainer
    VideoSwipeAction.None, VideoSwipeAction.ClearHistory -> MaterialTheme.colorScheme.surfaceContainerHighest
  }

@Composable
internal fun VideoSwipeAction.contentColor(): Color =
  when (this) {
    VideoSwipeAction.Delete -> MaterialTheme.colorScheme.onErrorContainer
    VideoSwipeAction.ToggleWatched, VideoSwipeAction.Finished -> MaterialTheme.colorScheme.onTertiaryContainer
    VideoSwipeAction.AddToQueue, VideoSwipeAction.PlayNext, VideoSwipeAction.LastPlayed ->
      MaterialTheme.colorScheme.onSecondaryContainer
    VideoSwipeAction.AddToPlaylist, VideoSwipeAction.MarkNew -> MaterialTheme.colorScheme.onPrimaryContainer
    VideoSwipeAction.None, VideoSwipeAction.ClearHistory -> MaterialTheme.colorScheme.onSurfaceVariant
  }

/** Returns (left, right); audio rows use their own actions once the user separates them from video. */
@Composable
internal fun rememberSwipeActions(
  isAudio: Boolean,
  videoLeft: VideoSwipeAction,
  videoRight: VideoSwipeAction,
): Pair<VideoSwipeAction, VideoSwipeAction> {
  if (!isAudio) return videoLeft to videoRight
  val preferences = koinInject<BrowserPreferences>()
  val separateAudio by preferences.separateAudioSwipeActions.collectAsState()
  val audioLeft by preferences.audioSwipeLeft.collectAsState()
  val audioRight by preferences.audioSwipeRight.collectAsState()
  return if (separateAudio) audioLeft to audioRight else videoLeft to videoRight
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
  isAudio: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  // Keep the same composition subtree while selection temporarily disables swiping. Switching
  // between a plain Card and the swipe wrapper disposes thumbnail state and causes visible list
  // flicker (album art briefly falls back to the placeholder icon) on long-press selection.
  if (leftAction == VideoSwipeAction.None && rightAction == VideoSwipeAction.None) {
    Card(modifier = modifier, shape = shape, colors = colors, content = content)
    return
  }
  val preferences = koinInject<BrowserPreferences>()
  val separateAudio by preferences.separateAudioSwipeActions.collectAsState()
  val videoRightZonePercent by preferences.videoSwipeRightZonePercent.collectAsState()
  val videoLeftZonePercent by preferences.videoSwipeLeftZonePercent.collectAsState()
  val audioRightZonePercent by preferences.audioSwipeRightZonePercent.collectAsState()
  val audioLeftZonePercent by preferences.audioSwipeLeftZonePercent.collectAsState()
  val useAudioZones = isAudio && separateAudio
  val rightZonePercent = if (useAudioZones) audioRightZonePercent else videoRightZonePercent
  val leftZonePercent = if (useAudioZones) audioLeftZonePercent else videoLeftZonePercent
  val rightSwipeZoneFraction = rightZonePercent.coerceIn(BrowserPreferences.VIDEO_SWIPE_ZONE_RANGE) / 100f
  val leftSwipeZoneFraction = leftZonePercent.coerceIn(BrowserPreferences.VIDEO_SWIPE_ZONE_RANGE) / 100f
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  var dragOffset by remember(identity) { mutableFloatStateOf(0f) }
  var dragging by remember(identity) { mutableStateOf(false) }
  var thresholdReached by remember(identity) { mutableStateOf(false) }
  var thresholdFeedbackSent by remember(identity) { mutableStateOf(false) }
  val canSwipe =
    enabled &&
      onAction != null &&
      (leftAction != VideoSwipeAction.None || rightAction != VideoSwipeAction.None)
  val currentAction by rememberUpdatedState(onAction)
  val currentLeft by rememberUpdatedState(leftAction)
  val currentRight by rememberUpdatedState(rightAction)
  val animationScope = rememberCoroutineScope()
  val displayOffset by animateFloatWhenChanged(
    targetValue = if (dragging) dragOffset else 0f,
    animationSpec = if (dragging || reducedMotion) snap() else AppMotion.Spatial.ExpressiveFast,
    scope = animationScope,
  )

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

  Box(
    modifier =
      modifier
        .semantics { customActions = accessibilityActions }
        .pointerInput(
          identity,
          canSwipe,
          isTelevision,
          leftAction,
          rightAction,
          leftSwipeZoneFraction,
          rightSwipeZoneFraction,
        ) {
          if (!canSwipe || isTelevision) return@pointerInput
          val touchSlop = viewConfiguration.touchSlop

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Geometry comes from this node's own size, so measuring a row never recomposes it. Read
            // after the down event: returning before any suspension would spin awaitEachGesture.
            val rowWidth = size.width
            if (rowWidth <= 0) return@awaitEachGesture
            val travel = minOf(rowWidth * SWIPE_TRAVEL_FRACTION, SWIPE_MAX_TRAVEL.toPx())
            val threshold = minOf(travel * 0.7f, 56.dp.toPx())
            val leftEdge = rowWidth * rightSwipeZoneFraction
            val rightEdge = rowWidth * (1f - leftSwipeZoneFraction)
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
            var committedAction: VideoSwipeAction? = null

            try {
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
                if (threshold > 0f && abs(committedOffset) >= threshold && action != VideoSwipeAction.None) {
                  committedAction = action
                }
              }
            } finally {
              // Also runs when a preference change restarts this block mid-swipe.
              if (dragStarted) {
                dragging = false
                dragOffset = 0f
                thresholdReached = false
                thresholdFeedbackSent = false
              }
            }
            committedAction?.let { action -> currentAction?.invoke(action) }
          }
        },
  ) {
    // The revealed action is only composed while a swipe shows it; idle rows skip its text layout.
    if (dragging || displayOffset != 0f) {
      val revealedAction = if (displayOffset >= 0f) rightAction else leftAction
      val background by animateColorWhenChanged(
        targetValue = revealedAction.containerColor(),
        animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
        scope = animationScope,
      )
      val iconScale by animateFloatWhenChanged(
        targetValue = if (thresholdReached && !reducedMotion) 1.12f else 1f,
        animationSpec = AppMotion.spatial(AppMotion.Spatial.ExpressiveFast, snap()),
        scope = animationScope,
      )
      Box(
        modifier =
          Modifier
            .matchParentSize()
            .clearAndSetSemantics { }
            .drawWithContent {
              val left = if (displayOffset < 0f) size.width + displayOffset else 0f
              val right = if (displayOffset > 0f) displayOffset else size.width
              if (abs(displayOffset) > 0f) clipRect(left = left, right = right) { this@drawWithContent.drawContent() }
            }.background(background, shape),
      ) {
        Column(
          modifier =
            Modifier
              .align(if (displayOffset >= 0f) AbsoluteAlignment.CenterLeft else AbsoluteAlignment.CenterRight)
              .layout { measurable, constraints ->
                val travelWidth =
                  minOf(constraints.maxWidth * SWIPE_TRAVEL_FRACTION, SWIPE_MAX_TRAVEL.toPx())
                    .roundToInt()
                    .coerceIn(constraints.minWidth, constraints.maxWidth)
                val placeable = measurable.measure(constraints.copy(minWidth = travelWidth, maxWidth = travelWidth))
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
              }.padding(horizontal = 6.dp, vertical = 4.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = revealedAction.icon(isWatched),
            contentDescription = null,
            tint = revealedAction.contentColor(),
            modifier =
              Modifier.size(24.dp).graphicsLayer {
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
    }
    Card(
      modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = displayOffset },
      shape = shape,
      colors = colors,
      content = content,
    )
  }
}

private const val SWIPE_TRAVEL_FRACTION = 0.4f
private val SWIPE_MAX_TRAVEL = 112.dp
