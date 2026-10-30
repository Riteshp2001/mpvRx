/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import android.view.ViewConfiguration as AndroidViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.Panels
import app.gyrolet.mpvrx.ui.player.PlayerUpdates
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.SingleActionGesture
import app.gyrolet.mpvrx.ui.player.getSubtitleGestureRegion
import app.gyrolet.mpvrx.ui.player.SubtitleGestureTarget
import app.gyrolet.mpvrx.ui.player.SubtitleGestureRegion
import app.gyrolet.mpvrx.ui.player.findSubtitleGestureTarget
import app.gyrolet.mpvrx.ui.player.draggedSubtitlePosition
import app.gyrolet.mpvrx.ui.player.getTrackSelectionId
import app.gyrolet.mpvrx.ui.player.isSecondarySubtitleActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val holdSpeedPresets = listOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f)
private const val SPEED_HOLD_INTENT_THRESHOLD_MS = 250L
private const val MULTI_TAP_SEEK_TIMEOUT_MS = 1_000L
private const val CENTER_SINGLE_TAP_VERTICAL_EDGE_FRACTION = 0.35f

private data class TapSeekFeedback(
  val region: String,
  val generation: Long,
  val lastSeekUptimeMs: Long,
)

private enum class GestureOwner {
  SPEED,
  VERTICAL,
  SUBTITLE_VERTICAL,
  PLAYLIST,
  PINCH,
  HORIZONTAL_SEEK,
  SUBTITLE_SEEK,
}

private fun nearestHoldSpeedPreset(speed: Float): Float =
  holdSpeedPresets.minByOrNull { abs(it - speed) } ?: holdSpeedPresets.first()

private const val SWIPE_SPEED_STEP = 0.25f
private const val SWIPE_SPEED_MIN = 0.5f
private const val SWIPE_SPEED_MAX = 4f
private const val SWIPE_SPEED_FEEDBACK_MS = 1_000L
private val SWIPE_SPEED_STEP_DISTANCE = 20.dp

/** Next 0.25x step; 1.1x lands on 1.25x or 1x, and a speed outside 0.5x-4x never moves further out. */
private fun swipeSpeedStep(
  speed: Float,
  up: Boolean,
): Float {
  val grid = speed / SWIPE_SPEED_STEP
  return if (up) {
    ((floor(grid + 0.001f) + 1f) * SWIPE_SPEED_STEP).coerceAtMost(maxOf(SWIPE_SPEED_MAX, speed))
  } else {
    ((ceil(grid - 0.001f) - 1f) * SWIPE_SPEED_STEP).coerceAtLeast(minOf(SWIPE_SPEED_MIN, speed))
  }
}

private fun swipeSpeedAfter(
  start: Float,
  steps: Int,
): Float {
  var speed = start
  repeat(abs(steps)) { speed = swipeSpeedStep(speed, up = steps > 0) }
  return speed
}

@Suppress("CyclomaticComplexMethod", "MultipleEmitters")
@Composable
fun GestureHandler(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
  externalPanelShown: Boolean = false,
  onDismissExternalPanel: () -> Unit = {},
  onLockedTouchSideChanged: (isLeft: Boolean) -> Unit = {},
  onSeekRipple: (SeekRippleRequest) -> Unit = {},
) {
  val playerPreferences = koinInject<PlayerPreferences>()
  val audioPreferences = koinInject<AudioPreferences>()
  val gesturePreferences = koinInject<GesturePreferences>()
  val subtitlesPreferences = koinInject<SubtitlesPreferences>()
  val context = LocalContext.current
  val mediaGeneration by remember {
    PlaybackSession.state.map { it.generation }.distinctUntilChanged()
  }.collectAsState(initial = PlaybackSession.state.value.generation)
  val currentOnLockedTouchSideChanged by rememberUpdatedState(onLockedTouchSideChanged)
  val currentOnSeekRipple by rememberUpdatedState(onSeekRipple)
  val subtitleTracks by viewModel.subtitleTracks.collectAsState(emptyList())
  // Pointer handlers outlive recomposition; always resolve against the current tracks.
  val subtitleRegions by rememberUpdatedState<(Float, Float) -> List<SubtitleGestureRegion>> { width, height ->
    SubtitleGestureTarget.entries.mapNotNull { target ->
      val track = subtitleTracks.find { it.id == getTrackSelectionId(target.selectionProperty) }
      val isAss = track?.codec?.let { it.contains("ass", true) || it.contains("ssa", true) } == true
      val preserveAssLayout = isAss &&
        PlaybackSession.getPropertyString("${target.prefix}-ass-override") != "force"
      getSubtitleGestureRegion(
        target, width, height,
        preserveAssLayout || PlaybackSession.getPropertyString("sub-use-margins") == "no",
      )
    }
  }

  val primarySubtitleDragRegion by rememberUpdatedState<(Float, Float) -> SubtitleGestureRegion?> { width, height ->
    val target = SubtitleGestureTarget.Primary
    val track = subtitleTracks.find { it.id == getTrackSelectionId(target.selectionProperty) }
    val isAss = track?.codec?.let { it.contains("ass", true) || it.contains("ssa", true) } == true
    val preserveAssLayout = isAss && PlaybackSession.getPropertyString("sub-ass-override") != "force"
    getSubtitleGestureRegion(
      target, width, height,
      preserveAssLayout || PlaybackSession.getPropertyString("sub-use-margins") == "no",
      includeWhenTextEmpty = true,
    )?.takeUnless { MpvConfigOverridePolicy.isOwnedByMpvConf("sub-pos") }
  }

  val panelShown by viewModel.panelShown.collectAsState()
  val anyPanelShown = panelShown != Panels.None || externalPanelShown
  val allowPanelGesturesPreference by playerPreferences.allowGesturesInPanels.collectAsState()
  val settingsPanelShown = panelShown == Panels.PlayerSettings
  val allowGesturesInPanels = allowPanelGesturesPreference && !settingsPanelShown
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val duration by PlaybackSession.propInt["duration"].collectAsState()
  val position by PlaybackSession.propInt["time-pos"].collectAsState()
  val playbackSpeed by PlaybackSession.propFloat["speed"].collectAsState()
  val areControlsLocked by viewModel.areControlsLocked.collectAsState()
  val seekState by viewModel.seekState.collectAsState()
  val seekAmount = seekState.amount
  val useSingleTapForLeft by gesturePreferences.useSingleTapForLeft.collectAsState()
  val useSingleTapForCenter by gesturePreferences.useSingleTapForCenter.collectAsState()
  val useSingleTapForRight by gesturePreferences.useSingleTapForRight.collectAsState()
  val doubleTapSeekAreaWidth by gesturePreferences.doubleTapSeekAreaWidth.collectAsState()
  val centerVerticalSubtitlePositionGesture by gesturePreferences.centerVerticalSubtitlePositionGesture.collectAsState()
  val enableCenterSwipeUpGesture by gesturePreferences.enableCenterSwipeUpGesture.collectAsState()
  val enableVideoMiniPlayer by playerPreferences.enableVideoMiniPlayer.collectAsState()
  val pinchToZoomSubtitles by gesturePreferences.pinchToZoomSubtitles.collectAsState()
  val secondarySubPinchZoomEnabled by subtitlesPreferences.secondarySubPinchZoom.collectAsState()
  val swipeSubtitlesToSeekDialog by gesturePreferences.swipeSubtitlesToSeekDialog.collectAsState()
  val isSwipeSubtitlesInverted by gesturePreferences.swipeSubtitlesInvertDirection.collectAsState()
  val twoFingerSpeedSwipe by gesturePreferences.twoFingerSpeedSwipe.collectAsState()
  var speedSwipeFeedbackJob by remember { mutableStateOf<Job?>(null) }
  var isTapSeeking by remember { mutableStateOf(false) }
  var seekFeedbackBurst by remember { mutableStateOf<TapSeekFeedback?>(null) }
  var seekFeedbackRevision by remember { mutableStateOf(0L) }
  var tapInProgress by remember { mutableStateOf(false) }
  val multipleSpeedGesture by playerPreferences.holdForMultipleSpeed.collectAsState()
  val brightnessGesture by playerPreferences.brightnessGesture.collectAsState()
  val volumeGesture by playerPreferences.volumeGesture.collectAsState()
  val swapVolumeAndBrightness by playerPreferences.swapVolumeAndBrightness.collectAsState()
  val pinchToZoomGesture by playerPreferences.pinchToZoomGesture.collectAsState()
  val panAndZoomEnabled by playerPreferences.panAndZoomEnabled.collectAsState()
  val horizontalSwipeToSeek by playerPreferences.horizontalSwipeToSeek.collectAsState()
  val horizontalSwipeSensitivity by playerPreferences.horizontalSwipeSensitivity.collectAsState()
  var isLongPressing by remember { mutableStateOf(false) }
  var isDynamicSpeedControlActive by remember { mutableStateOf(false) }
  var dynamicSpeedStartX by remember { mutableStateOf(0f) }
  var dynamicSpeedStartValue by remember { mutableStateOf(2f) }
  var lastAppliedSpeed by remember { mutableStateOf(2f) }
  var hasSwipedEnough by remember { mutableStateOf(false) }
  var isSpeedLocked by remember { mutableStateOf(false) }
  var longPressTriggeredDuringTouch by remember { mutableStateOf(false) }
  var isVerticalGestureActive by remember { mutableStateOf(false) }
  var gestureOwner by remember { mutableStateOf<GestureOwner?>(null) }
  var gesturePointerId by remember { mutableStateOf<Long?>(null) }
  var gestureDownTimeMillis by remember { mutableStateOf<Long?>(null) }
  var gestureClaimedForPointer by remember { mutableStateOf(false) }
  var speedHoldPending by remember { mutableStateOf(false) }
  var suppressHorizontalSeekForPointer by remember { mutableStateOf(false) }

  fun beginGesture(
    pointerId: Long,
    downTimeMillis: Long,
  ) {
    if (gesturePointerId == pointerId && gestureDownTimeMillis == downTimeMillis) return
    gesturePointerId = pointerId
    gestureDownTimeMillis = downTimeMillis
    gestureOwner = null
    gestureClaimedForPointer = false
    speedHoldPending = false
    suppressHorizontalSeekForPointer = false
  }

  fun claimGesture(owner: GestureOwner): Boolean {
    if (gestureOwner != null && gestureOwner != owner) return false
    gestureOwner = owner
    gestureClaimedForPointer = true
    return true
  }

  fun releaseGesture(owner: GestureOwner) {
    if (gestureOwner == owner) gestureOwner = null
  }

  val currentVolumePercent by viewModel.currentVolumePercent.collectAsState()
  val currentMPVVolume by PlaybackSession.propInt["volume"].collectAsState()
  val currentBrightness by viewModel.currentBrightness.collectAsState()
  val volumeBoostingCap = audioPreferences.volumeBoostCap.get()
  val actionHaptics = app.gyrolet.mpvrx.ui.utils.rememberAppHaptics()
  val volumeHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(
    0f, 100f + volumeBoostingCap, landmarks = listOf(100f),
  )
  val brightnessHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(0f, 1f)
  val coroutineScope = rememberCoroutineScope()
  val density = LocalDensity.current
  val topStatusBarInsetPx = WindowInsets.statusBars.getTop(density).toFloat()
  val bottomNavigationInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
  val topGestureDeadZonePx =
    remember(density, topStatusBarInsetPx) {
      max(
        topStatusBarInsetPx + with(density) { 12.dp.toPx() },
        with(density) { 44.dp.toPx() },
      )
    }
  val bottomGestureDeadZonePx =
    remember(density, bottomNavigationInsetPx) {
      max(
        bottomNavigationInsetPx + with(density) { 12.dp.toPx() },
        with(density) { 36.dp.toPx() },
      )
    }

  // Isolated tap state tracking
  var tapCount by remember { mutableStateOf(0) }
  var lastTapTime by remember { mutableStateOf(0L) }
  var lastTapGeneration by remember { mutableStateOf(-1L) }
  var lastTapPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
  var lastTapRegion by remember { mutableStateOf<String?>(null) }
  var pendingSingleTapRegion by remember { mutableStateOf<String?>(null) }
  val doubleTapTimeout = AndroidViewConfiguration.getDoubleTapTimeout().toLong()
  val doubleTapSlop = remember(context) {
    AndroidViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
  }

  fun toggleControls() {
    if (anyPanelShown && !allowGesturesInPanels) {
      if (viewModel.panelShown.value != Panels.None) viewModel.panelShown.update { Panels.None }
      if (externalPanelShown) onDismissExternalPanel()
    }
    if (viewModel.areControlsLocked.value || !viewModel.controlsShown.value) {
      viewModel.showControls()
    } else {
      viewModel.hideControls()
    }
  }

  LaunchedEffect(
    mediaGeneration,
    areControlsLocked,
    doubleTapSeekAreaWidth,
    externalPanelShown,
    panelShown,
    useSingleTapForLeft,
    useSingleTapForCenter,
    useSingleTapForRight,
  ) {
    tapCount = 0
    lastTapRegion = null
    pendingSingleTapRegion = null
    isTapSeeking = false
    seekFeedbackBurst = null
    viewModel.updateSeekText(null)
    viewModel.hideSeekBar()
    viewModel.updateSeekAmount(0)
  }

  // Regions in double-tap mode wait before treating a lone tap as a controls toggle.
  LaunchedEffect(tapCount, lastTapTime, longPressTriggeredDuringTouch, tapInProgress) {
    if (tapInProgress) return@LaunchedEffect
    if (tapCount == 1 && pendingSingleTapRegion != null) {
      delay(doubleTapTimeout)
      if (tapCount == 1 && pendingSingleTapRegion != null && !isTapSeeking && !longPressTriggeredDuringTouch) {
        toggleControls()
        pendingSingleTapRegion = null
      }
      tapCount = 0
      lastTapRegion = null
      if (!isTapSeeking) {
        seekFeedbackBurst = null
        viewModel.updateSeekAmount(0)
      }
    }
  }

  // Each accepted seek restarts feedback, even if the amount is unchanged at a media boundary.
  // Never expire the tap sequence while a finger or another gesture is still active.
  LaunchedEffect(seekAmount, seekFeedbackRevision, tapInProgress, gestureOwner) {
    if ((seekAmount == 0 && !isTapSeeking) || tapInProgress || gestureOwner != null) {
      return@LaunchedEffect
    }
    delay(MULTI_TAP_SEEK_TIMEOUT_MS)
    isTapSeeking = false
    seekFeedbackBurst = null
    viewModel.updateSeekText(null)
    viewModel.hideSeekBar()
    // This changes the effect's key, so clear it last instead of cancelling pending cleanup.
    viewModel.updateSeekAmount(0)
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .pointerInput(
          areControlsLocked,
          doubleTapSeekAreaWidth,
          useSingleTapForLeft,
          useSingleTapForCenter,
          useSingleTapForRight,
          gesturePreferences,
          isVerticalGestureActive,
          mediaGeneration,
          externalPanelShown,
          anyPanelShown,
          allowGesturesInPanels,
          settingsPanelShown,
        ) {
          // Tap detection is isolated from drags and multi-finger gestures.
          if (isVerticalGestureActive || settingsPanelShown) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            beginGesture(down.id.value, down.uptimeMillis)
            val downPosition = down.position
            val downTime = down.uptimeMillis
            val generation = PlaybackSession.state.value.generation
            tapInProgress = true
            var completedTap = false

            try {
              if (areControlsLocked) {
                currentOnLockedTouchSideChanged(downPosition.x < size.width / 2f)
              }

              val seekAreaFraction = (doubleTapSeekAreaWidth / 100f).coerceIn(0.01f, 0.5f)
              val leftBoundary = size.width * seekAreaFraction
              val rightBoundary = size.width * (1f - seekAreaFraction)
              val region = when {
                downPosition.x > rightBoundary -> "right"
                downPosition.x < leftBoundary -> "left"
                else -> "center"
              }
              val singleTapEnabled = when (region) {
                "left" -> useSingleTapForLeft
                "right" -> useSingleTapForRight
                else -> useSingleTapForCenter
              }
              // Only center single taps use the middle 30% of the player's height.
              // The existing horizontal regions give the center 30% width by default.
              val isSingleTapActionArea = region != "center" ||
                (downPosition.y >= size.height * CENTER_SINGLE_TAP_VERTICAL_EDGE_FRACTION &&
                  downPosition.y < size.height * (1f - CENTER_SINGLE_TAP_VERTICAL_EDGE_FRACTION))
              val seekRegionWidth = (size.width * seekAreaFraction).coerceAtLeast(1f)
              val seekRegionStart = if (region == "right") rightBoundary else 0f
              val ripplePosition = Offset(
                x = ((downPosition.x - seekRegionStart) / seekRegionWidth).coerceIn(0f, 1f),
                y = (downPosition.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
              )
              var isDrag = false
              var wasOwnedByAnotherGesture = gestureOwner != null

              do {
                val event = awaitPointerEvent()
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (gestureOwner != null || event.changes.count { it.pressed } > 1) {
                  wasOwnedByAnotherGesture = true
                }
                val distance = (pointer.position - downPosition).getDistance()
                if (distance > viewConfiguration.touchSlop) isDrag = true

                if (!pointer.pressed) {
                  completedTap = true
                  if (!isDrag && !wasOwnedByAnotherGesture && !gestureClaimedForPointer) {
                    // A tap after a volume/brightness swipe should restore access to controls
                    // immediately, without waiting for the overlay timer or firing a tap action.
                    if (viewModel.dismissAdjustmentOverlays()) {
                      tapCount = 0
                      isTapSeeking = false
                      seekFeedbackBurst = null
                      lastTapRegion = null
                      pendingSingleTapRegion = null
                      viewModel.showControls()
                      pointer.consume()
                      break
                    }
                    // Platform double-tap timing measures first UP to second DOWN. Input uptime
                    // remains accurate when a busy frame delays delivery of a pointer event.
                    val gap = downTime - lastTapTime
                    val sameMediaAndRegion = lastTapGeneration == generation && lastTapRegion == region
                    val gesture = when (region) {
                      "left" -> gesturePreferences.leftSingleActionGesture.get()
                      "right" -> gesturePreferences.rightSingleActionGesture.get()
                      else -> gesturePreferences.centerSingleActionGesture.get()
                    }
                    val isSeekRegion = region != "center" && gesture == SingleActionGesture.Seek
                    val tapActionsAllowed = !areControlsLocked && (!anyPanelShown || allowGesturesInPanels)
                    val isSingleTap = tapActionsAllowed && singleTapEnabled && isSingleTapActionArea
                    val isDoubleTap = tapActionsAllowed && !singleTapEnabled && tapCount == 1 && sameMediaAndRegion &&
                      gap in 0L..doubleTapTimeout &&
                      (downPosition - lastTapPosition).getDistance() <= doubleTapSlop

                    if (singleTapEnabled && (!isSingleTapActionArea || !tapActionsAllowed)) {
                      tapCount = 0
                      pendingSingleTapRegion = null
                      isTapSeeking = false
                      seekFeedbackBurst = null
                      viewModel.updateSeekAmount(0)
                      toggleControls()
                    } else if (isSingleTap || isDoubleTap) {
                      tapCount = 0
                      pendingSingleTapRegion = null
                      isTapSeeking = isSeekRegion
                      if (isSeekRegion) {
                        val previousFeedback = seekFeedbackBurst
                        val continuesFeedback = previousFeedback != null && previousFeedback.region == region &&
                          previousFeedback.generation == generation &&
                          downTime - previousFeedback.lastSeekUptimeMs in 0L..MULTI_TAP_SEEK_TIMEOUT_MS
                        if (!continuesFeedback) viewModel.updateSeekAmount(0)
                        seekFeedbackBurst = TapSeekFeedback(region, generation, pointer.uptimeMillis)
                        seekFeedbackRevision++
                        currentOnSeekRipple(SeekRippleRequest(ripplePosition, isForward = region == "right"))
                      } else {
                        seekFeedbackBurst = null
                        viewModel.updateSeekAmount(0)
                      }
                      when (region) {
                        "right" -> viewModel.handleRightTap()
                        "left" -> viewModel.handleLeftTap()
                        else -> viewModel.handleCenterTap()
                      }
                    } else {
                      // A moved, opposite-side or expired tap starts a new candidate. Never drop
                      // a rapid tap just because it failed the previous sequence's conditions.
                      tapCount = 1
                      isTapSeeking = false
                      pendingSingleTapRegion = region
                    }
                    lastTapTime = pointer.uptimeMillis
                    lastTapPosition = downPosition
                    lastTapRegion = region
                    lastTapGeneration = generation
                    pointer.consume()
                  } else {
                    tapCount = 0
                    isTapSeeking = false
                    seekFeedbackBurst = null
                    lastTapRegion = null
                    pendingSingleTapRegion = null
                  }
                  break
                }
              } while (event.changes.any { it.pressed })
            } finally {
              if (!completedTap) {
                tapCount = 0
                isTapSeeking = false
                seekFeedbackBurst = null
                lastTapRegion = null
                pendingSingleTapRegion = null
              }
              tapInProgress = false
            }
          }
        }.pointerInput(
          areControlsLocked,
          multipleSpeedGesture,
          brightnessGesture,
          volumeGesture,
          centerVerticalSubtitlePositionGesture,
          enableCenterSwipeUpGesture,
          enableVideoMiniPlayer,
          externalPanelShown,
          anyPanelShown,
          allowGesturesInPanels,
        ) {
          if (
            (
              !brightnessGesture &&
                !volumeGesture &&
                !centerVerticalSubtitlePositionGesture &&
                !enableCenterSwipeUpGesture &&
                !enableVideoMiniPlayer &&
                multipleSpeedGesture <= 0f
            ) ||
            areControlsLocked || (anyPanelShown && !allowGesturesInPanels)
          ) {
            return@pointerInput
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            beginGesture(down.id.value, down.uptimeMillis)
            val startPosition = down.position
            val isVerticalGestureDeadZone =
              startPosition.y <= topGestureDeadZonePx ||
                startPosition.y >= size.height - bottomGestureDeadZonePx
            val subtitleHit = if (centerVerticalSubtitlePositionGesture) {
              findSubtitleGestureTarget(
                subtitleRegions(size.width.toFloat(), size.height.toFloat()),
                startPosition.x, startPosition.y, 12.dp.toPx(),
              )
            } else null
            val touchedSubtitle = subtitleHit?.takeUnless {
              MpvConfigOverridePolicy.isOwnedByMpvConf("${it.target.prefix}-pos")
            }
            // Direct subtitle hits select that track; a center hold elsewhere selects primary.
            // This is independent of the center swipe-up toggle, so ordinary swipes keep working.
            val centerSubtitle = if (subtitleHit == null && centerVerticalSubtitlePositionGesture &&
              !isVerticalGestureDeadZone &&
              startPosition.x in (size.width * 0.35f)..(size.width * 0.65f)
            ) {
              primarySubtitleDragRegion(size.width.toFloat(), size.height.toFloat())
            } else null
            val selectedSubtitle = touchedSubtitle ?: centerSubtitle
            val subtitleGestureGeneration = PlaybackSession.state.value.generation
            val isStartOnSubtitleText = subtitleHit != null
            speedHoldPending = paused == false && multipleSpeedGesture > 0f && selectedSubtitle == null

            // Reset long press tracking at the start of each gesture
            longPressTriggeredDuringTouch = false

            // State for vertical gestures (volume/brightness)
            var startingY = 0f
            var mpvVolumeStartingY = 0f
            var originalVolumePercent = currentVolumePercent
            var originalMPVVolume = currentMPVVolume
            var originalBrightness = currentBrightness
            var lastVolumePercentValue = currentVolumePercent
            var lastMPVVolumeValue = currentMPVVolume ?: 100
            var lastBrightnessValue = currentBrightness
            val brightnessGestureSens = 0.001f
            // Match the anime4k gesture feel, but snap to whole-number volume steps.
            val volumeGestureSens = 0.1f
            val mpvVolumeGestureSens = 0.1f
            var subtitleDragStartY = startPosition.y
            var subtitleDragPosition = selectedSubtitle?.position ?: 0f
            var lastDraggedPosition: Float? = null
            // A moving finger is a swipe, not a pending subtitle long-press.
            var subtitleHoldEligible = selectedSubtitle != null
            var subtitleHighlightShown = false

            // Original speed for long press
            var originalSpeed = playbackSpeed ?: 1f

            // Track long press separately
            var longPressTriggered = false
            var isSubtitleHoldActive = false
            val longPressDelay = viewConfiguration.longPressTimeoutMillis
            var longPressJob =
              coroutineScope.launch {
                delay(longPressDelay)
                if (!longPressTriggered) {
                  // Gesture movement cancels the subtitle hold before this callback runs.
                  if (subtitleHoldEligible || !isStartOnSubtitleText) {
                    if (subtitleHoldEligible && claimGesture(GestureOwner.SUBTITLE_VERTICAL)) {
                      longPressTriggered = true
                      isSubtitleHoldActive = true
                      speedHoldPending = false
                      suppressHorizontalSeekForPointer = true
                      viewModel.setSubtitleGestureHighlight(true, selectedSubtitle?.target)
                      subtitleHighlightShown = true
                      longPressTriggeredDuringTouch = true
                      actionHaptics.pickup()
                      viewModel.playerUpdate.update {
                        PlayerUpdates.ShowText(
                          context.getString(R.string.player_move_subtitles_hint),
                        )
                      }
                    } else if (
                      selectedSubtitle == null &&
                      paused == false &&
                      multipleSpeedGesture > 0f &&
                      claimGesture(GestureOwner.SPEED)
                    ) {
                      speedHoldPending = false
                      longPressTriggered = true
                      isLongPressing = true
                      longPressTriggeredDuringTouch = true
                      actionHaptics.pickup()
                      originalSpeed = playbackSpeed ?: 1f
                      // Ramp speed up incrementally to avoid audio filter stutter
                      val startSpeed = originalSpeed
                      val targetSpeed = nearestHoldSpeedPreset(multipleSpeedGesture)
                      val steps = 5
                      val stepDelay = 16L // ~one frame per step
                      for (i in 1..steps) {
                        val t = i.toFloat() / steps
                        val intermediateSpeed = startSpeed + (targetSpeed - startSpeed) * t
                        PlaybackSession.setPropertyFloat("speed", intermediateSpeed)
                        if (i < steps) delay(stepDelay)
                      }

                      isDynamicSpeedControlActive = true
                      hasSwipedEnough = false
                      dynamicSpeedStartX = startPosition.x
                      dynamicSpeedStartValue = targetSpeed
                      lastAppliedSpeed = targetSpeed
                      viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(targetSpeed) }
                    }
                  }
                }
              }

            var gestureType: String? = null
            var minimizeDistance = 0f

            try {
              do {
                val event = awaitPointerEvent()
                val pointerCount = event.changes.count { it.pressed }

                if (pointerCount == 1) {
                  event.changes.forEach { change ->
                    if (change.pressed) {
                      val currentPosition = change.position
                      val deltaX = currentPosition.x - startPosition.x
                      val deltaY = currentPosition.y - startPosition.y
                      if (subtitleHoldEligible && sqrt(deltaX * deltaX + deltaY * deltaY) > viewConfiguration.touchSlop) {
                        subtitleHoldEligible = false
                        longPressJob.cancel()
                      }

                      // Determine gesture type based on initial drag direction
                      if (gestureType == null && (abs(deltaX) > 20f || abs(deltaY) > 20f)) {
                        val isHorizontalDrag = abs(deltaX) > abs(deltaY) * 1.5f
                        val isVerticalDrag = abs(deltaY) > abs(deltaX) * 1.5f

                        if (isSubtitleHoldActive) {
                          if (isVerticalDrag && (isStartOnSubtitleText || !isVerticalGestureDeadZone)) {
                            gestureType = "subtitle_vertical"
                          } else {
                            return@forEach
                          }
                        } else {
                          if (
                            speedHoldPending &&
                            isHorizontalDrag &&
                            change.uptimeMillis - down.uptimeMillis >= SPEED_HOLD_INTENT_THRESHOLD_MS
                          ) {
                            suppressHorizontalSeekForPointer = true
                          }
                          speedHoldPending = false
                          val isCenterTouch =
                            enableCenterSwipeUpGesture && startPosition.x in (size.width * 0.35f)..(size.width * 0.65f)
                          val isMinimizeTouch = enableVideoMiniPlayer && !externalPanelShown &&
                            !isVerticalGestureDeadZone && startPosition.x in (size.width / 3f)..(size.width * 2f / 3f)
                          if (
                            isLongPressing &&
                              isDynamicSpeedControlActive &&
                              gestureOwner == GestureOwner.SPEED &&
                              (abs(deltaX) > 10f || abs(deltaY) > 10f)
                          ) {
                            longPressJob.cancel()
                            gestureType = "speed_control"
                          } else if (isMinimizeTouch && isVerticalDrag && deltaY > 20f && claimGesture(GestureOwner.VERTICAL)) {
                            longPressJob.cancel()
                            gestureType = "minimize"
                          } else if (isCenterTouch && isVerticalDrag) {
                            longPressJob.cancel()
                            if (
                              deltaY < -20f &&
                              viewModel.hasPlaylistSupport() &&
                              claimGesture(GestureOwner.PLAYLIST)
                            ) {
                              gestureType = "playlist_swipe"
                              actionHaptics.pickup()
                              viewModel.isPlaylistSwipeActive.value = true
                              viewModel.playlistSwipeOffset.value = deltaY
                              viewModel.sheetShown.update { Sheets.Playlist }
                              viewModel.hideControls()
                              viewModel.panelShown.update { Panels.None }
                              if (externalPanelShown) onDismissExternalPanel()
                            } else {
                              return@forEach
                            }
                          } else {
                            // Cancel long press if drag started
                            longPressJob.cancel()

                            // Check if we're in long press mode with dynamic speed control
                            if (
                              isLongPressing &&
                              isDynamicSpeedControlActive &&
                              gestureOwner == GestureOwner.SPEED &&
                              abs(deltaX) > 10f
                            ) {
                              gestureType = "speed_control"
                            } else {
                              gestureType =
                                if (isHorizontalDrag) {
                                  "horizontal"
                                } else if (
                                  isVerticalDrag &&
                                  !isVerticalGestureDeadZone &&
                                  claimGesture(GestureOwner.VERTICAL)
                                ) {
                                  "vertical"
                                } else {
                                  null
                                }
                            }
                          }
                        }

                        // Initialize gesture-specific state
                        when (gestureType) {
                          "speed_control" -> {
                            dynamicSpeedStartX = currentPosition.x
                            dynamicSpeedStartValue = PlaybackSession.getPropertyFloat("speed") ?: multipleSpeedGesture
                          }
                          "vertical" -> {
                            if ((brightnessGesture || volumeGesture) && !isLongPressing) {
                              isVerticalGestureActive = true
                              startingY = 0f
                              mpvVolumeStartingY = 0f
                              originalVolumePercent = currentVolumePercent
                              originalMPVVolume = currentMPVVolume
                              originalBrightness = currentBrightness
                              lastVolumePercentValue = currentVolumePercent
                              lastMPVVolumeValue = currentMPVVolume ?: 100
                              lastBrightnessValue = currentBrightness
                            }
                          }
                          "subtitle_vertical" -> {
                            isVerticalGestureActive = true
                            subtitleDragStartY = currentPosition.y
                            subtitleDragPosition = selectedSubtitle?.position ?: subtitleDragPosition
                            if (!subtitleHighlightShown) {
                              viewModel.setSubtitleGestureHighlight(true, selectedSubtitle?.target)
                              subtitleHighlightShown = true
                            }
                            startingY = 0f
                          }
                        }
                      }

                      // Handle the appropriate gesture
                      when (gestureType) {
                        "minimize" -> {
                          minimizeDistance = deltaY.coerceAtLeast(0f)
                          change.consume()
                        }
                        "playlist_swipe" -> {
                          viewModel.playlistSwipeOffset.value = deltaY
                          change.consume()
                        }
                        "speed_control" -> {
                          if (isLongPressing && isDynamicSpeedControlActive && paused == false) {
                            change.consume()

                            val speedPresets = holdSpeedPresets
                            val screenWidth = size.width.toFloat()

                            val deltaX = currentPosition.x - dynamicSpeedStartX
                            val deltaY = currentPosition.y - startPosition.y
                            val swipeDetectionThreshold = 10.dp.toPx()
                            val speedLockThreshold = 60.dp.toPx()

                            if (deltaY < -speedLockThreshold && !isSpeedLocked) {
                              isSpeedLocked = true
                              actionHaptics.pickup()
                              viewModel.playerUpdate.update {
                                PlayerUpdates.ShowText(context.getString(R.string.player_speed_gesture_locked))
                              }
                            } else if (deltaY > speedLockThreshold && isSpeedLocked) {
                              isSpeedLocked = false
                              isDynamicSpeedControlActive = false
                              originalSpeed = playerPreferences.defaultSpeed.get()
                              PlaybackSession.setPropertyFloat("speed", originalSpeed)
                              actionHaptics.pickup()
                              viewModel.playerUpdate.update {
                                PlayerUpdates.ShowText(context.getString(R.string.player_speed_gesture_restored))
                              }
                              return@forEach
                            }

                            if (!hasSwipedEnough && abs(deltaX) >= swipeDetectionThreshold) {
                              hasSwipedEnough = true
                              viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(lastAppliedSpeed) }
                            }

                            if (hasSwipedEnough) {
                              val presetsRange = speedPresets.size - 1
                              val indexDelta = (deltaX / screenWidth) * presetsRange * 3.5f

                              val startIndex =
                                speedPresets
                                  .indexOfFirst {
                                    abs(it - dynamicSpeedStartValue) < 0.01f
                                  }.takeIf { it >= 0 } ?: speedPresets
                                  .indexOfFirst {
                                    it >= dynamicSpeedStartValue
                                  }.takeIf { it >= 0 } ?: speedPresets.lastIndex

                              val newIndex = (startIndex + indexDelta.toInt()).coerceIn(0, speedPresets.size - 1)
                              val newSpeed = speedPresets[newIndex]

                              if (abs(lastAppliedSpeed - newSpeed) > 0.01f) {
                                actionHaptics.tick()
                                lastAppliedSpeed = newSpeed
                                PlaybackSession.setPropertyFloat("speed", newSpeed)
                                viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(newSpeed) }
                              }
                            }
                          }
                        }
                        "subtitle_vertical" -> {
                          if (isSubtitleHoldActive && gestureOwner == GestureOwner.SUBTITLE_VERTICAL) {
                            val selected = selectedSubtitle
                            if (selected != null && PlaybackSession.state.value.generation == subtitleGestureGeneration &&
                              getTrackSelectionId(selected.target.selectionProperty) == selected.trackId
                            ) {
                              // Lock the target for this entire touch, even if the tracks cross.
                              val newPosition = draggedSubtitlePosition(
                                subtitleDragPosition, currentPosition.y - subtitleDragStartY, selected.positionHeight,
                              )
                              viewModel.previewSubtitlePosition(newPosition, selected.target)
                              lastDraggedPosition = newPosition
                            }
                            change.consume()
                          }
                        }
                        "vertical" -> {
                          if ((brightnessGesture || volumeGesture) && !isLongPressing) {
                            val amount = currentPosition.y - startPosition.y

                            val changeVolume: () -> Unit = {
                              val isIncreasingVolumeBoost: (Float) -> Boolean = {
                                volumeBoostingCap > 0 &&
                                  currentVolumePercent == 100 &&
                                  (currentMPVVolume ?: 100) - 100 < volumeBoostingCap &&
                                  amount < 0
                              }
                              val isDecreasingVolumeBoost: (Float) -> Boolean = {
                                volumeBoostingCap > 0 &&
                                  currentVolumePercent == 100 &&
                                  (currentMPVVolume ?: 100) - 100 in 1..volumeBoostingCap &&
                                  amount > 0
                              }

                              if (isIncreasingVolumeBoost(amount) || isDecreasingVolumeBoost(amount)) {
                                if (mpvVolumeStartingY == 0f) {
                                  startingY = 0f
                                  originalVolumePercent = currentVolumePercent
                                  mpvVolumeStartingY = currentPosition.y
                                }
                                val newMPVVolume =
                                  calculateNewVerticalGestureValue(
                                    originalMPVVolume ?: 100,
                                    mpvVolumeStartingY,
                                    currentPosition.y,
                                    mpvVolumeGestureSens,
                                  ).coerceIn(100..volumeBoostingCap + 100)

                                if (newMPVVolume != lastMPVVolumeValue) {
                                  viewModel.changeMPVVolumeTo(newMPVVolume)
                                  volumeHaptics.move(lastMPVVolumeValue.toFloat(), newMPVVolume.toFloat())
                                  lastMPVVolumeValue = newMPVVolume
                                }
                              } else {
                                if (startingY == 0f) {
                                  mpvVolumeStartingY = 0f
                                  originalMPVVolume = currentMPVVolume
                                  startingY = currentPosition.y
                                }
                                val newVolumePercent =
                                  calculateNewVerticalGestureValue(
                                    originalVolumePercent,
                                    startingY,
                                    currentPosition.y,
                                    volumeGestureSens,
                                  ).coerceIn(0, 100)

                                if (newVolumePercent != lastVolumePercentValue) {
                                  viewModel.changeVolumePercentTo(newVolumePercent)
                                  volumeHaptics.move(lastVolumePercentValue.toFloat(), newVolumePercent.toFloat())
                                  lastVolumePercentValue = newVolumePercent
                                }
                              }

                              viewModel.displayVolumeSlider()
                            }
                            val changeBrightness: () -> Unit = {
                              if (startingY == 0f) startingY = currentPosition.y
                              val newBrightness =
                                calculateNewVerticalGestureValue(
                                  originalBrightness,
                                  startingY,
                                  currentPosition.y,
                                  brightnessGestureSens,
                                )

                              if (abs(newBrightness - lastBrightnessValue) > 0.001f) {
                                viewModel.changeBrightnessTo(newBrightness)
                                brightnessHaptics.move(
                                  lastBrightnessValue.coerceIn(0f, 1f),
                                  newBrightness.coerceIn(0f, 1f),
                                )
                                lastBrightnessValue = newBrightness
                              }

                              viewModel.displayBrightnessSlider()
                            }
                            when {
                              volumeGesture && brightnessGesture -> {
                                if (swapVolumeAndBrightness) {
                                  if (currentPosition.x > size.width / 2) changeBrightness() else changeVolume()
                                } else {
                                  if (currentPosition.x < size.width / 2) changeBrightness() else changeVolume()
                                }
                              }
                              brightnessGesture -> changeBrightness()
                              volumeGesture -> changeVolume()
                              else -> {}
                            }

                            change.consume()
                          }
                        }
                      }
                    }
                  }
                } else if (pointerCount > 1) {
                  // Multi-finger gesture detected
                  speedHoldPending = false
                  longPressJob.cancel()
                  if (gestureType != null) {
                    when (gestureType) {
                      "playlist_swipe" -> {
                        viewModel.isPlaylistSwipeActive.value = false
                      }
                      "vertical",
                      "subtitle_vertical",
                      -> {
                        if (brightnessGesture || volumeGesture || isSubtitleHoldActive) {
                          isVerticalGestureActive = false
                          startingY = 0f
                          lastVolumePercentValue = currentVolumePercent
                          lastMPVVolumeValue = currentMPVVolume ?: 100
                          lastBrightnessValue = currentBrightness
                        }
                      }
                    }
                    gestureType = null
                  }
                  when (gestureOwner) {
                    GestureOwner.SPEED,
                    GestureOwner.VERTICAL,
                    GestureOwner.SUBTITLE_VERTICAL,
                    GestureOwner.PLAYLIST,
                    -> gestureOwner = null
                    else -> Unit
                  }
                  break
                }
              } while (event.changes.any { it.pressed })

              // Handle gesture end
              speedHoldPending = false
              longPressJob.cancel()

              if (isLongPressing) {
                isLongPressing = false
                isDynamicSpeedControlActive = false
                hasSwipedEnough = false
                if (!isSpeedLocked) {
                  // Ramp speed back down incrementally to avoid audio filter stutter
                  val currentSpeed = PlaybackSession.getPropertyFloat("speed") ?: multipleSpeedGesture
                  val targetSpeed = originalSpeed
                  val steps = 5
                  val stepDelay = 16L
                  coroutineScope.launch {
                    for (i in 1..steps) {
                      val t = i.toFloat() / steps
                      val intermediateSpeed = currentSpeed + (targetSpeed - currentSpeed) * t
                      PlaybackSession.setPropertyFloat("speed", intermediateSpeed)
                      if (i < steps) delay(stepDelay)
                    }
                  }
                }
                viewModel.playerUpdate.update { PlayerUpdates.None }
              }

              if (isSubtitleHoldActive) {
                isSubtitleHoldActive = false
                viewModel.playerUpdate.update { PlayerUpdates.None }
              }

              if (gestureType == "playlist_swipe") {
                viewModel.isPlaylistSwipeActive.value = false
              }
              if (gestureType == "minimize" && minimizeDistance >= 80.dp.toPx()) {
                if (viewModel.minimizeToMiniPlayer()) actionHaptics.pickup()
              }

              when (gestureType) {
                "vertical",
                "subtitle_vertical",
                -> {
                  if (brightnessGesture || volumeGesture || isVerticalGestureActive) {
                    isVerticalGestureActive = false
                    startingY = 0f
                    lastVolumePercentValue = currentVolumePercent
                    lastMPVVolumeValue = currentMPVVolume ?: 100
                    lastBrightnessValue = currentBrightness
                  }
                }
              }

              when (gestureOwner) {
                GestureOwner.SPEED,
                GestureOwner.VERTICAL,
                GestureOwner.SUBTITLE_VERTICAL,
                GestureOwner.PLAYLIST,
                -> gestureOwner = null
                else -> Unit
              }
            } finally {
              longPressJob.cancel()
              if (subtitleHighlightShown) viewModel.setSubtitleGestureHighlight(false)
              if (PlaybackSession.state.value.generation == subtitleGestureGeneration) {
                val selected = selectedSubtitle
                lastDraggedPosition?.let { position ->
                  if (selected != null && getTrackSelectionId(selected.target.selectionProperty) == selected.trackId) {
                    viewModel.persistSubtitleGesturePosition(selected.target, position)
                  }
                }
              }
              isVerticalGestureActive = false
              releaseGesture(GestureOwner.SUBTITLE_VERTICAL)
              if (isLongPressing) {
                if (!isSpeedLocked) PlaybackSession.setPropertyFloat("speed", originalSpeed)
                isLongPressing = false
                isDynamicSpeedControlActive = false
                viewModel.playerUpdate.update { PlayerUpdates.None }
              }
              releaseGesture(GestureOwner.SPEED)
            }
          }
        }.pointerInput(
          pinchToZoomGesture,
          pinchToZoomSubtitles,
          secondarySubPinchZoomEnabled,
          panAndZoomEnabled,
          areControlsLocked,
          centerVerticalSubtitlePositionGesture,
          anyPanelShown,
          allowGesturesInPanels,
          twoFingerSpeedSwipe,
        ) {
          if ((!pinchToZoomGesture && !pinchToZoomSubtitles && !panAndZoomEnabled && !twoFingerSpeedSwipe) ||
            (anyPanelShown && !allowGesturesInPanels) || areControlsLocked
          ) {
            return@pointerInput
          }

          awaitEachGesture {
            var zoom = 0f
            var gestureStarted = false
            var prevDist = 0f
            var prevMidX = 0f
            var prevMidY = 0f
            var startDist = 0f
            var startMidX = 0f
            var startMidY = 0f
            var speedSwipeRuledOut = true
            var speedSwipeMode = false
            var swipedSpeed = 1f
            var swipeStartSpeed = 1f
            var swipeFirstStep = 0
            var swipeExtraSteps = 0
            var swipeAnchorY = 0f

            var selectedSubtitle: SubtitleGestureRegion? = null
            var isSubZoomMode = false
            var initialSubScale = 1f
            var initialDist = 1f
            var lastCalculatedSubScale = 1f
            var initialSecondarySubScale = 1f
            var lastCalculatedSecondarySubScale = 1f
            var scaleChanged = false
            var linkedScaleChanged = false
            var linkSecondaryScale = false
            var pinchHighlightShown = false

            var currentPanX = 0f
            var currentPanY = 0f
            var sw = 0f
            var sh = 0f

            val down = awaitFirstDown(requireUnconsumed = false)
            beginGesture(down.id.value, down.uptimeMillis)
            val subtitleGestureGeneration = PlaybackSession.state.value.generation
            val firstTouchedSubtitle = if (pinchToZoomSubtitles) {
              findSubtitleGestureTarget(
                subtitleRegions(size.width.toFloat(), size.height.toFloat()),
                down.position.x, down.position.y, 12.dp.toPx(),
              )
            } else null

            try {
              do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }

                if (pressed.size == 2) {
                  releaseGesture(GestureOwner.SUBTITLE_VERTICAL)
                  if (!claimGesture(GestureOwner.PINCH)) continue
                  val p1 = pressed[0].position
                  val p2 = pressed[1].position
                  val dx = p2.x - p1.x
                  val dy = p2.y - p1.y
                  val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                  val midX = (p1.x + p2.x) / 2f
                  val midY = (p1.y + p2.y) / 2f

                  if (prevDist == 0f) {
                    // First frame — capture baseline
                    prevDist = dist
                    prevMidX = midX
                    prevMidY = midY

                    sw = size.width.toFloat()
                    sh = size.height.toFloat()

                    currentPanX = viewModel.videoPanX.value
                    currentPanY = viewModel.videoPanY.value

                    selectedSubtitle = if (pinchToZoomSubtitles) {
                      val regions = subtitleRegions(sw, sh)
                      regions.find { it.target == firstTouchedSubtitle?.target && it.trackId == firstTouchedSubtitle.trackId }
                        ?: findSubtitleGestureTarget(regions, midX, midY, 12.dp.toPx())
                    } else null

                    val selected = selectedSubtitle
                    if (selected != null) {
                      isSubZoomMode = true
                      initialSubScale = selected.scale
                      initialDist = dist.coerceAtLeast(1f)
                      lastCalculatedSubScale = initialSubScale
                      initialSecondarySubScale =
                        PlaybackSession.getPropertyFloat("secondary-sub-scale") ?: subtitlesPreferences.secondarySubScale.get()
                      lastCalculatedSecondarySubScale = initialSecondarySubScale
                      linkSecondaryScale = selected.target == SubtitleGestureTarget.Primary && secondarySubPinchZoomEnabled &&
                        isSecondarySubtitleActive() && !MpvConfigOverridePolicy.isOwnedByMpvConf("secondary-sub-scale")
                      // A linked pinch resizes both tracks, so keep both visible.
                      viewModel.setSubtitleGestureHighlight(true, selected.target.takeUnless { linkSecondaryScale })
                      pinchHighlightShown = true
                    } else if (pinchToZoomGesture || panAndZoomEnabled) {
                      isSubZoomMode = false
                      zoom = viewModel.videoZoom.value
                    } else {
                      isSubZoomMode = false
                    }
                    startDist = dist
                    startMidX = midX
                    startMidY = midY
                    // Pinch and pan wait until this cannot be a speed swipe; zoomed-in video keeps two-finger panning.
                    speedSwipeRuledOut =
                      !twoFingerSpeedSwipe || isSubZoomMode || (panAndZoomEnabled && viewModel.videoZoom.value > 0f)
                  } else {
                    if (!speedSwipeRuledOut && !speedSwipeMode) {
                      val travelX = midX - startMidX
                      val travelY = midY - startMidY
                      val spread = abs(dist - startDist)
                      if ((spread > 12.dp.toPx() && spread >= abs(travelY) / 2f) ||
                        (abs(travelX) > 24.dp.toPx() && abs(travelX) > abs(travelY))
                      ) {
                        speedSwipeRuledOut = true
                      } else if (abs(travelY) > 40.dp.toPx() && spread < abs(travelY) / 2f) {
                        speedSwipeMode = true
                        speedSwipeFeedbackJob?.cancel()
                        swipeStartSpeed = PlaybackSession.getPropertyFloat("speed") ?: 1f
                        swipeFirstStep = if (travelY < 0f) 1 else -1
                        swipeExtraSteps = 0
                        swipeAnchorY = midY
                        swipedSpeed = swipeSpeedAfter(swipeStartSpeed, swipeFirstStep)
                        if (abs(swipedSpeed - swipeStartSpeed) > 0.001f) {
                          PlaybackSession.setPropertyFloat("speed", swipedSpeed)
                          actionHaptics.tick()
                        } else {
                          // Already at 0.5x or 4x: a step that changed nothing must not count when reversing.
                          swipeFirstStep = 0
                        }
                        viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(swipedSpeed) }
                      }
                    } else if (speedSwipeMode) {
                      val stepPx = SWIPE_SPEED_STEP_DISTANCE.toPx()
                      val travelledSteps = (swipeAnchorY - midY) / stepPx
                      // The extra 0.2 step of travel keeps a finger resting on a boundary from flickering.
                      if (abs(travelledSteps - swipeExtraSteps) > 0.7f) {
                        val nextExtraSteps = travelledSteps.roundToInt()
                        val nextSpeed = swipeSpeedAfter(swipeStartSpeed, swipeFirstStep + nextExtraSteps)
                        if (abs(nextSpeed - swipedSpeed) > 0.001f) {
                          swipeExtraSteps = nextExtraSteps
                          swipedSpeed = nextSpeed
                          PlaybackSession.setPropertyFloat("speed", nextSpeed)
                          actionHaptics.tick()
                          viewModel.playerUpdate.update { PlayerUpdates.DynamicSpeedControl(nextSpeed) }
                        } else {
                          // Pinned at 0.5x or 4x: move the anchor with the fingers so reversing responds at once.
                          swipeAnchorY = midY + swipeExtraSteps * stepPx
                        }
                      }
                    }
                    if (!speedSwipeRuledOut) {
                      pressed.forEach { it.consume() }
                      continue
                    }

                    if (isSubZoomMode) {
                      // Pinch is zoom-only: vertical midpoint drift must not move the
                      // subtitle position. Position changes only via press-and-hold
                      // single-finger vertical drag (subtitle_vertical path above).
                      if (!gestureStarted && abs(dist - initialDist) > 5f) {
                        gestureStarted = true
                      }

                      val selected = selectedSubtitle
                      if (gestureStarted && selected != null &&
                        PlaybackSession.state.value.generation == subtitleGestureGeneration
                      ) {
                        val scaleProperty = "${selected.target.prefix}-scale"
                        if (!MpvConfigOverridePolicy.isOwnedByMpvConf(scaleProperty)) {
                          val currentSubScale = (initialSubScale * (dist / initialDist)).coerceIn(0.1f, 5f)
                          scaleChanged = scaleChanged || abs(currentSubScale - initialSubScale) > 0.001f
                          lastCalculatedSubScale = currentSubScale
                          viewModel.previewSubtitleScale(currentSubScale, selected.target)
                          if (linkSecondaryScale) {
                            lastCalculatedSecondarySubScale = (initialSecondarySubScale * (dist / initialDist)).coerceIn(0.1f, 5f)
                            viewModel.previewSubtitleScale(lastCalculatedSecondarySubScale, SubtitleGestureTarget.Secondary)
                            linkedScaleChanged = true
                          }
                          viewModel.playerUpdate.update {
                            PlayerUpdates.SubtitleZoom(currentSubScale, selected.target == SubtitleGestureTarget.Secondary)
                          }
                        }
                      }
                    } else if (pinchToZoomGesture || panAndZoomEnabled) {
                      // Activate on significant pinch movement OR mid movement (pan)
                      val distDelta = abs(dist - prevDist)
                      val midDeltaX = abs(midX - prevMidX)
                      val midDeltaY = abs(midY - prevMidY)

                      if (!gestureStarted && (distDelta > 5f || (panAndZoomEnabled && (midDeltaX > 3f || midDeltaY > 3f)))) {
                        gestureStarted = true
                        if (pinchToZoomGesture && distDelta > 5f) {
                          viewModel.playerUpdate.update { PlayerUpdates.VideoZoom }
                        }
                      }

                      if (gestureStarted) {
                        val previousScale = 2f.pow(viewModel.videoZoom.value)
                        if (pinchToZoomGesture && prevDist > 0f && distDelta > 0.5f) {
                          // Per-frame zoom: ratio of current distance to previous distance
                          val zoomRatio = dist / prevDist
                          val zoomDelta = ln(zoomRatio.toDouble()).toFloat() * 1.2f
                          zoom = (zoom + zoomDelta).coerceIn(-1f, 3f)
                          viewModel.setVideoZoom(zoom)
                        }

                        // Simultaneous pan while pinching or moving two fingers
                        if (panAndZoomEnabled && sw > 0f && sh > 0f) {
                          val currentZoom = viewModel.videoZoom.value
                          val scale = 2f.pow(currentZoom)
                          val extraWidth = (scale - 1f) * sw
                          val extraHeight = (scale - 1f) * sh
                          val maxX = (extraWidth / 2f).coerceAtLeast(0f)
                          val maxY = (extraHeight / 2f).coerceAtLeast(0f)
                          val scaleRatio = scale / previousScale
                          val centerX = sw / 2f
                          val centerY = sh / 2f

                          currentPanX =
                            (
                              midX - centerX -
                                (prevMidX - centerX - currentPanX) * scaleRatio
                            ).coerceIn(-maxX, maxX)
                          currentPanY =
                            (
                              midY - centerY -
                                (prevMidY - centerY - currentPanY) * scaleRatio
                            ).coerceIn(-maxY, maxY)
                          viewModel.setVideoPan(currentPanX, currentPanY)
                        }
                      }
                    }

                    prevDist = dist
                    prevMidX = midX
                    prevMidY = midY
                  }

                  pressed.forEach { it.consume() }
                } else if (pressed.size < 2 && prevDist != 0f) {
                  break
                }
              } while (event.changes.any { it.pressed })

            } finally {
              val selected = selectedSubtitle
              val stillSameTrack = selected != null && PlaybackSession.state.value.generation == subtitleGestureGeneration &&
                getTrackSelectionId(selected.target.selectionProperty) == selected.trackId
              if (scaleChanged && stillSameTrack) {
                if (selectedSubtitle.target == SubtitleGestureTarget.Secondary) {
                  subtitlesPreferences.secondarySubScale.set(lastCalculatedSubScale)
                } else {
                  subtitlesPreferences.subScale.set(lastCalculatedSubScale)
                }
              }
              if (linkedScaleChanged && stillSameTrack) subtitlesPreferences.secondarySubScale.set(lastCalculatedSecondarySubScale)
              if (pinchHighlightShown) viewModel.setSubtitleGestureHighlight(false)
              releaseGesture(GestureOwner.PINCH)
              if (speedSwipeMode) {
                val shownSpeed = PlayerUpdates.DynamicSpeedControl(swipedSpeed)
                speedSwipeFeedbackJob?.cancel()
                speedSwipeFeedbackJob =
                  coroutineScope.launch {
                    delay(SWIPE_SPEED_FEEDBACK_MS)
                    viewModel.playerUpdate.update { if (it == shownSpeed) PlayerUpdates.None else it }
                  }
              } else {
                viewModel.playerUpdate.update { PlayerUpdates.None }
              }
            }
          }
        }.pointerInput(
          horizontalSwipeToSeek,
          areControlsLocked,
          gesturePreferences,
          isVerticalGestureActive,
          swipeSubtitlesToSeekDialog,
          isSwipeSubtitlesInverted,
          anyPanelShown,
        ) {
          if ((!horizontalSwipeToSeek && !swipeSubtitlesToSeekDialog) ||
            areControlsLocked ||
            isVerticalGestureActive
          ) {
            return@pointerInput
          }

          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            beginGesture(down.id.value, down.uptimeMillis)
            val startPosition = down.position
            val startTime = System.currentTimeMillis()

            val primaryRegion = subtitleRegions(size.width.toFloat(), size.height.toFloat())
              .find { it.target == SubtitleGestureTarget.Primary }
            val isSubtitleTouch = swipeSubtitlesToSeekDialog &&
              primaryRegion?.contains(startPosition.x, startPosition.y, 12.dp.toPx()) == true

            var gestureType: String? = null
            var hasStartedSeeking = false
            var initialVideoPosition = 0f
            var pendingSeekPosition: Float? = null
            var wasPlayerAlreadyPaused = false
            var windowedPreviewStarted = false
            var seekGestureCompleted = false
            val seekGestureGeneration = PlaybackSession.state.value.generation
            // Use the sensitivity preference instead of hardcoded value
            val seekSensitivity = horizontalSwipeSensitivity

            try {
              do {
                val event = awaitPointerEvent()
                val pointerCount = event.changes.count { it.pressed }
                if (PlaybackSession.state.value.generation != seekGestureGeneration ||
                  (windowedPreviewStarted && viewModel.seekThumbnailFrame.value.targetMs == null)
                ) {
                  hasStartedSeeking = false
                  break
                }

                if (pointerCount == 1) {
                  event.changes.forEach { change ->
                    if (change.pressed) {
                      val currentPosition = change.position
                      val deltaX = currentPosition.x - startPosition.x
                      val deltaY = currentPosition.y - startPosition.y
                      val timeSinceStart = System.currentTimeMillis() - startTime

                      if (
                        gestureType == null &&
                        isSubtitleTouch &&
                        abs(deltaX) > 40f &&
                        abs(deltaX) > abs(deltaY) * 2f &&
                        claimGesture(GestureOwner.SUBTITLE_SEEK)
                      ) {
                        gestureType = "subtitle_dialog_seek"
                        hasStartedSeeking = true
                        viewModel.setSubtitleGestureHighlight(true, SubtitleGestureTarget.Primary)
                        val isForward = if (isSwipeSubtitlesInverted) deltaX < 0 else deltaX > 0
                        val direction = if (isForward) "1" else "-1"
                        PlaybackSession.command("sub-seek", direction)
                        viewModel.playerUpdate.update {
                          PlayerUpdates.ShowText(
                            context.getString(
                              if (isForward) R.string.player_next_dialog else R.string.player_previous_dialog,
                            ),
                          )
                        }
                        change.consume()
                      }

                      if (gestureType == "subtitle_dialog_seek") {
                        change.consume()
                      }

                      // Only activate if this is clearly a horizontal gesture
                      // and not conflicting with other gestures
                      if (gestureType == null &&
                        gestureOwner == null &&
                        !speedHoldPending &&
                        !suppressHorizontalSeekForPointer &&
                        horizontalSwipeToSeek &&
                        !isSubtitleTouch &&
                        abs(deltaX) > 30f &&
                        abs(deltaX) > abs(deltaY) * 2f &&
                        // Must be strongly horizontal
                        timeSinceStart > 100L &&
                        // Avoid conflicts with double-tap
                        !isLongPressing &&
                        // Don't conflict with long press
                        !isDynamicSpeedControlActive &&
                        // Don't conflict with speed control
                        !anyPanelShown
                      ) { // Only when no panels are shown
                        if (claimGesture(GestureOwner.HORIZONTAL_SEEK)) {
                          gestureType = "horizontal_seek"
                          hasStartedSeeking = true
                          // Follow the same high precision clock that drives the seekbar.
                          initialVideoPosition = viewModel.precisePosition.value
                            .takeIf { it.isFinite() && it >= 0f }
                            ?: position?.toFloat()
                            ?: 0f
                          pendingSeekPosition = initialVideoPosition

                          // Pause before seeking to prevent decoder stalls
                          wasPlayerAlreadyPaused = paused ?: false
                          if (!wasPlayerAlreadyPaused) {
                            viewModel.pause()
                          }

                          change.consume()
                        }
                      }

                      if (gestureType == "horizontal_seek" && hasStartedSeeking) {
                        // Calculate seek amount based on horizontal movement
                        val seekAmount = deltaX * seekSensitivity
                        val targetPosition = (initialVideoPosition + seekAmount).coerceAtLeast(0f)
                        val maxDuration = duration?.toFloat() ?: 0f
                        val clampedPosition = targetPosition.coerceAtMost(maxDuration)
                        pendingSeekPosition = clampedPosition

                        windowedPreviewStarted = viewModel.previewWindowedSeekTo(clampedPosition, wasPlayerAlreadyPaused)
                        if (!windowedPreviewStarted) {
                          viewModel.seekPreviewTo(clampedPosition)
                        }

                        // Format and display time position updates
                        val currentPos = clampedPosition.toInt()
                        val seekDelta = (clampedPosition - initialVideoPosition).toInt()

                        val currentTimeStr = formatSeekTime(currentPos)

                        // Format seek delta with +/- prefix
                        val deltaStr =
                          if (seekDelta >= 0) {
                            "+${formatSeekTime(seekDelta)}"
                          } else {
                            "-${formatSeekTime(-seekDelta)}"
                          }

                        // Use PlayerUpdates system like zoom updates
                        viewModel.playerUpdate.update {
                          PlayerUpdates.HorizontalSeek(currentTimeStr, deltaStr)
                        }

                        change.consume()
                      }
                    }
                  }
                } else if (pointerCount > 1) {
                  // Multi-finger detected, cancel horizontal seek
                  if (hasStartedSeeking) {
                    hasStartedSeeking = false
                    viewModel.cancelWindowedSeekPreview(resumePlayback = false)
                    // Clean up seeking state without showing controls
                    if (gestureType == "horizontal_seek" && !wasPlayerAlreadyPaused) {
                      viewModel.unpause()
                    }
                    viewModel.playerUpdate.update { PlayerUpdates.None }
                  }
                  releaseGesture(GestureOwner.HORIZONTAL_SEEK)
                  releaseGesture(GestureOwner.SUBTITLE_SEEK)
                  break
                }
              } while (event.changes.any { it.pressed })
              seekGestureCompleted = true
            } finally {
              if (gestureType == "subtitle_dialog_seek") viewModel.setSubtitleGestureHighlight(false)
              if (windowedPreviewStarted && (!seekGestureCompleted || !hasStartedSeeking)) {
                viewModel.cancelWindowedSeekPreview(
                  resumePlayback = PlaybackSession.state.value.generation == seekGestureGeneration,
                )
              }
            }

            // Apply the final seek when gesture ends
            if (hasStartedSeeking && PlaybackSession.state.value.generation == seekGestureGeneration &&
              (!windowedPreviewStarted || viewModel.seekThumbnailFrame.value.targetMs != null)
            ) {
              pendingSeekPosition?.let {
                if (gestureType == "horizontal_seek") {
                  viewModel.seekFromScrub(it, resumePlayback = !wasPlayerAlreadyPaused)
                } else {
                  viewModel.seekTo(it.toInt())
                }
              }

              // Keep the chip visible briefly after the gesture settles.
              coroutineScope.launch {
                delay(300)
                viewModel.playerUpdate.update { PlayerUpdates.None }
              }
            }
            releaseGesture(GestureOwner.HORIZONTAL_SEEK)
            releaseGesture(GestureOwner.SUBTITLE_SEEK)
          }
        },
  )
}

fun calculateNewVerticalGestureValue(
  originalValue: Int,
  startingY: Float,
  newY: Float,
  sensitivity: Float,
): Int = originalValue + ((startingY - newY) * sensitivity).roundToInt()

fun calculateNewVerticalGestureValue(
  originalValue: Float,
  startingY: Float,
  newY: Float,
  sensitivity: Float,
): Float = originalValue + ((startingY - newY) * sensitivity)

private fun formatSeekTime(seconds: Int): String {
  val absSeconds = kotlin.math.abs(seconds)
  val hours = absSeconds / 3600
  val minutes = (absSeconds % 3600) / 60
  val secs = absSeconds % 60
  return if (hours > 0) {
    String.format("%d:%02d:%02d", hours, minutes, secs)
  } else {
    String.format("%02d:%02d", minutes, secs)
  }
}
