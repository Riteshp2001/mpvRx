/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.drawscope.scale
import app.gyrolet.mpvrx.ui.liquidglass.DampedDragAnimation
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.drawCapsuleTint
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.SkipSegment
import app.gyrolet.mpvrx.ui.player.SkipSegmentType
import app.gyrolet.mpvrx.ui.player.clip.ClipEditorUiState
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.player.visualizer.AudioFeatures
import app.gyrolet.mpvrx.ui.player.visualizer.VisualizerPalette
import app.gyrolet.mpvrx.ui.player.visualizer.WaveVisualizerOverlay
import app.gyrolet.mpvrx.ui.theme.AppConnectedControlShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.Segment
import dev.vivvvek.seeker.rememberSeekerState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Precomputed, allocation-free drawing data for a single skip segment overlay. */
private data class SkipSegmentOverlay(
  val startFraction: Float,
  val endFraction: Float,
  val fillColor: Color,
  val edgeColor: Color,
)

private const val READ_AHEAD_TRACK_ALPHA = 0.40f
private const val EMPTY_TRACK_ALPHA = 0.24f

private fun liquidSeekbarTrackHeight(paused: Boolean): Dp = if (paused) 8.dp else 6.dp

@Composable
private fun rememberSeekbarTrackAlphas(): Pair<Float, Float> {
  return READ_AHEAD_TRACK_ALPHA to EMPTY_TRACK_ALPHA
}

private fun bufferedEndPx(
  bufferPosition: Float?,
  duration: Float,
  trackWidth: Float,
  playedPx: Float,
): Float {
  if (duration <= 0f || trackWidth <= 0f) {
    return playedPx
  }
  val playedPosition = (playedPx / trackWidth * duration).coerceIn(0f, duration)
  val bufferedUntil = normalizedReadAheadValue(bufferPosition, playedPosition, duration)
  return (bufferedUntil / duration * trackWidth).coerceIn(playedPx, trackWidth)
}

/**
 * Normalizes mpv's absolute `demuxer-cache-time` endpoint for both the visible
 * buffered range and Seeker's read-ahead state. The indicator must never trail
 * the committed playback position or extend beyond the media duration.
 */
private fun normalizedReadAheadValue(
  bufferPosition: Float?,
  playedPosition: Float,
  duration: Float,
): Float {
  if (duration <= 0f) return 0f
  val safePlayedPosition = playedPosition.takeIf { it.isFinite() }?.coerceIn(0f, duration) ?: 0f
  val safeBufferPosition =
    bufferPosition
      ?.takeIf { it.isFinite() && it > 0f }
      ?: safePlayedPosition
  return safeBufferPosition.coerceIn(safePlayedPosition, duration)
}

private val segmentDrawPath = ThreadLocal.withInitial { Path() }

/**
 * Draws the linear seekbar as stacked layers instead of assigning one color to
 * each sub-range. Keeping the unplayed track underneath is important: it lets
 * the cache/read-ahead layer terminate with a real rounded cap without leaving
 * transparent wedges at the cap corners. Slim, Standard, Thick and the flattened
 * Wavy style all share this renderer, so their end-cap geometry stays identical.
 */
private fun DrawScope.drawSeekbarTrackSegments(
  segments: List<SeekbarTrackSegment>,
  playedPx: Float,
  bufferedPx: Float,
  centerY: Float,
  trackHeight: Float,
  playedColor: Color,
  bufferedColor: Color,
  unplayedColor: Color,
) {
  if (trackHeight <= 0f || size.width <= 0f || segments.isEmpty()) return

  val outerRadius = trackHeight / 2f
  val innerRadius = minOf(2.dp.toPx(), outerRadius)
  val cornerOuter = CornerRadius(outerRadius)
  val cornerInner = CornerRadius(innerRadius)
  val cornerZero = CornerRadius.Zero
  val reusablePath = segmentDrawPath.get() ?: Path().also { segmentDrawPath.set(it) }
  val edgeEpsilon = 0.5f

  fun cornerFor(radius: Float): CornerRadius =
    when {
      radius == outerRadius -> cornerOuter
      radius == innerRadius -> cornerInner
      radius == 0f -> cornerZero
      else -> CornerRadius(radius)
    }

  fun drawPiece(
    startX: Float,
    endX: Float,
    color: Color,
    leftRadius: Float,
    rightRadius: Float,
  ) {
    if (endX - startX < edgeEpsilon) return
    val cLeft = cornerFor(leftRadius)
    val cRight = cornerFor(rightRadius)
    reusablePath.reset()
    reusablePath.addRoundRect(
      androidx.compose.ui.geometry.RoundRect(
        left = startX,
        top = centerY - outerRadius,
        right = endX,
        bottom = centerY + outerRadius,
        topLeftCornerRadius = cLeft,
        bottomLeftCornerRadius = cLeft,
        topRightCornerRadius = cRight,
        bottomRightCornerRadius = cRight,
      ),
    )
    drawPath(reusablePath, color)
  }

  fun segmentLeftRadius(segment: SeekbarTrackSegment): Float =
    if (segment.start <= edgeEpsilon) outerRadius else innerRadius

  fun segmentRightRadius(segment: SeekbarTrackSegment): Float =
    if (segment.end >= size.width - edgeEpsilon) outerRadius else innerRadius

  fun drawRange(
    rangeStart: Float,
    rangeEnd: Float,
    color: Color,
    roundRangeEnd: Boolean = false,
  ) {
    val safeStart = rangeStart.takeIf { it.isFinite() }?.coerceIn(0f, size.width) ?: 0f
    val safeEnd = rangeEnd.takeIf { it.isFinite() }?.coerceIn(safeStart, size.width) ?: safeStart
    if (safeEnd - safeStart < edgeEpsilon) return

    segments.forEach { segment ->
      val pieceStart = maxOf(segment.start, safeStart)
      val pieceEnd = minOf(segment.end, safeEnd)
      if (pieceEnd - pieceStart < edgeEpsilon) return@forEach

      val startsAtSegmentBoundary = pieceStart <= segment.start + edgeEpsilon
      val endsAtSegmentBoundary = pieceEnd >= segment.end - edgeEpsilon
      val leftRadius = if (startsAtSegmentBoundary) segmentLeftRadius(segment) else 0f
      val rightRadius =
        when {
          endsAtSegmentBoundary -> segmentRightRadius(segment)
          roundRangeEnd && pieceEnd >= safeEnd - edgeEpsilon -> outerRadius
          else -> 0f
        }

      drawPiece(pieceStart, pieceEnd, color, leftRadius, rightRadius)
    }
  }

  // Base pill always owns the true outer shape. Progress and cache are overlays.
  drawRange(0f, size.width, unplayedColor)

  // Cache/read-ahead gets a rounded terminal cap while the base remains visible
  // underneath its curved corners. This is the piece that was flat previously.
  if (bufferedPx > playedPx) {
    drawRange(playedPx, bufferedPx, bufferedColor, roundRangeEnd = true)
  }

  if (playedPx > 0f) {
    drawRange(0f, playedPx, playedColor)
  }
}

private fun normalizeSeekerSegments(
  chapters: List<Segment>,
  duration: Float,
): List<Segment> {
  if (duration <= 0f) return emptyList()

  val validChapters =
    chapters
      .asSequence()
      .filter { chapter -> chapter.start.isFinite() && chapter.start in 0f..duration }
      .sortedBy(Segment::start)
      .distinctBy(Segment::start)
      .toList()

  return when {
    validChapters.isEmpty() -> emptyList()
    validChapters.first().start == 0f -> validChapters
    else -> listOf(Segment.Unspecified) + validChapters
  }
}

@Composable
internal fun SeekbarWithTimers(
  position: Float,
  duration: Float,
  remaining: Float,
  committedPosition: Float = position,
  externalScrubbing: Boolean = false,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: (Float) -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnCLick: () -> Unit,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Wavy,
  useWavySeekbar: Boolean = true,
  showWavyVisualizer: Boolean = false,
  waveFeatures: AudioFeatures? = null,
  wavePalette: VisualizerPalette? = null,
  waveSheetOpen: Boolean = false,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  isPortrait: Boolean = false,
  applyHorizontalPadding: Boolean = true,
  timerTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  thumbnailPreview: (@Composable (Float, Boolean) -> Unit)? = null,
  onValueChangeCancelled: () -> Unit = {},
  centerContent: @Composable () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  var isUserInteracting by remember { mutableStateOf(false) }
  var userPosition by remember { mutableFloatStateOf(position) }

  // Animated position for smooth transitions
  val animatedPosition = remember { Animatable(position) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(position, isUserInteracting, externalScrubbing) {
    if (!isUserInteracting && position != animatedPosition.value) {
      // Run the animation directly in this LaunchedEffect body (not via scope.launch).
      // When `position` changes on the next poll, the effect is cancelled and relaunched,
      // which retargets the same Animatable smoothly instead of stacking independent
      // spring coroutines that fight each other and leak work every ~50ms.
      if (externalScrubbing || position == 0f) {
        // Gesture preview already updates at pointer-event frequency; no delayed spring.
        animatedPosition.snapTo(position)
      } else {
        animatedPosition.animateTo(
          targetValue = position,
          animationSpec =
            spring(
              dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
              stiffness = AppMotion.Spatial.Standard.stiffness,
            ),
        )
      }
    }
  }

  if (isPortrait) {
    Column(
      modifier =
        modifier
          .fillMaxWidth()
          .then(if (applyHorizontalPadding) Modifier.padding(horizontal = MaterialTheme.spacing.large) else Modifier),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      SeekbarContent(
        positionProvider = {
          if (isUserInteracting) userPosition else if (externalScrubbing) position else animatedPosition.value
        },
        thumbnailPreview = thumbnailPreview,
        onValueChangeCancelled = onValueChangeCancelled,
        committedPosition = committedPosition,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        useWavySeekbar = useWavySeekbar,
        showWavyVisualizer = showWavyVisualizer,
        waveFeatures = waveFeatures,
        wavePalette = wavePalette,
        waveSheetOpen = waveSheetOpen,
        loopStart = loopStart,
        loopEnd = loopEnd,
        bufferDuration = bufferDuration,
        onUserInteractionChange = { isUserInteracting = it },
        onUserPositionChange = { userPosition = it },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        scope = scope,
        animatedPosition = animatedPosition,
        modifier = Modifier.fillMaxWidth().height(if (showWavyVisualizer) 64.dp else 44.dp),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VideoTimer(
          value = if (isUserInteracting) userPosition else position,
          isInverted = timersInverted.first,
          textColor = timerTextColor,
          onClick = {
            clickEvent()
            positionTimerOnClick()
          },
        )

        centerContent()

        VideoTimer(
          value = if (timersInverted.second) -remaining else duration,
          isInverted = timersInverted.second,
          textColor = timerTextColor,
          onClick = {
            clickEvent()
            durationTimerOnCLick()
          },
        )
      }
    }
  } else {
    Row(
      modifier = modifier.height(if (showWavyVisualizer) 64.dp else 48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      VideoTimer(
        value = if (isUserInteracting) userPosition else position,
        isInverted = timersInverted.first,
        textColor = timerTextColor,
        onClick = {
          clickEvent()
          positionTimerOnClick()
        },
        modifier = Modifier.width(60.dp),
      )

      SeekbarContent(
        positionProvider = {
          if (isUserInteracting) userPosition else if (externalScrubbing) position else animatedPosition.value
        },
        thumbnailPreview = thumbnailPreview,
        onValueChangeCancelled = onValueChangeCancelled,
        committedPosition = committedPosition,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        useWavySeekbar = useWavySeekbar,
        showWavyVisualizer = showWavyVisualizer,
        waveFeatures = waveFeatures,
        wavePalette = wavePalette,
        waveSheetOpen = waveSheetOpen,
        loopStart = loopStart,
        loopEnd = loopEnd,
        bufferDuration = bufferDuration,
        onUserInteractionChange = { isUserInteracting = it },
        onUserPositionChange = { userPosition = it },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        scope = scope,
        animatedPosition = animatedPosition,
        modifier = Modifier.weight(1f).height(if (showWavyVisualizer) 64.dp else 48.dp),
      )

      VideoTimer(
        value = if (timersInverted.second) -remaining else duration,
        isInverted = timersInverted.second,
        textColor = timerTextColor,
        onClick = {
          clickEvent()
          durationTimerOnCLick()
        },
        modifier = Modifier.width(60.dp),
      )
    }
  }
}

@Composable
private fun SeekbarContent(
  positionProvider: () -> Float,
  committedPosition: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  isPortrait: Boolean,
  isUserInteracting: Boolean,
  seekbarStyle: SeekbarStyle,
  useWavySeekbar: Boolean,
  showWavyVisualizer: Boolean = false,
  waveFeatures: AudioFeatures? = null,
  wavePalette: VisualizerPalette? = null,
  waveSheetOpen: Boolean = false,
  loopStart: Float?,
  loopEnd: Float?,
  bufferDuration: Float?,
  onUserInteractionChange: (Boolean) -> Unit,
  onUserPositionChange: (Float) -> Unit,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: (Float) -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
  animatedPosition: Animatable<Float, *>,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  showClipRange: Boolean = true,
  thumbnailPreview: (@Composable (Float, Boolean) -> Unit)? = null,
  onValueChangeCancelled: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val touchAreaHeight = if (isPortrait) 64.dp else 52.dp
  val seekerState = rememberSeekerState()
  val seekerInteractionSource = interactionSource
  val isSeekerPressed by seekerInteractionSource.collectIsPressedAsState()
  val isSeekerDragged by seekerInteractionSource.collectIsDraggedAsState()
  val isVisuallyInteracting = isUserInteracting || isSeekerPressed || isSeekerDragged
  val clipRange by ClipEditorUiState.state.collectAsState()
  val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
  val seekerRange = 0f..safeDuration.coerceAtLeast(0.1f)
  val safeCommittedPosition =
    committedPosition
      .takeIf { it.isFinite() }
      ?.coerceIn(seekerRange)
      ?: seekerRange.start
  val currentPos = positionProvider()
  val safeThumbPosition =
    currentPos
      .takeIf { it.isFinite() }
      ?.coerceIn(seekerRange)
      ?: safeCommittedPosition
  val seekerSegments =
    remember(chapters, safeDuration) {
      normalizeSeekerSegments(chapters, safeDuration)
    }
  val overlayTrackHeight by animateDpAsState(
    targetValue = when (seekbarStyle) {
      SeekbarStyle.Normal -> if (isVisuallyInteracting) 6.dp else 4.dp
      SeekbarStyle.Slim ->
        when {
          isVisuallyInteracting -> 15.dp
          paused -> 6.dp
          else -> 8.dp
        }
      SeekbarStyle.Thick -> if (paused || isVisuallyInteracting) 11.2.dp else 16.dp
      SeekbarStyle.Standard -> if (paused || isVisuallyInteracting) 5.6.dp else 8.dp
      SeekbarStyle.Wavy -> 8.dp
      SeekbarStyle.Liquid -> liquidSeekbarTrackHeight(paused)
    },
    animationSpec = spring(
      dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
      stiffness = AppMotion.Spatial.Expressive.stiffness,
    ),
    label = "seekbar_marker_height",
  )
  var latestInteractionPosition by remember { mutableFloatStateOf(currentPos) }

  LaunchedEffect(currentPos, isUserInteracting) {
    if (!isUserInteracting) {
      latestInteractionPosition = currentPos
    }
  }

  // Precompute skip-segment geometry fractions and colors once per (segments, duration)
  // change instead of allocating Color objects and recomputing positions on every Canvas
  // redraw (the overlay redraws on every position tick, ~20x/sec while scrubbing).
  val skipSegmentOverlays =
    remember(skipSegments, duration) {
      if (duration <= 0f || skipSegments.isEmpty()) {
        emptyList()
      } else {
        skipSegments.map { segment ->
          val color = segment.type.accentColor
          SkipSegmentOverlay(
            startFraction = (segment.startSeconds / duration).toFloat().coerceIn(0f, 1f),
            endFraction = (segment.endSeconds / duration).toFloat().coerceIn(0f, 1f),
            fillColor =
              Color(
                red = color.red * 0.74f,
                green = color.green * 0.74f,
                blue = color.blue * 0.74f,
                alpha = 0.42f,
              ),
            edgeColor =
              Color(
                red = color.red * 0.58f,
                green = color.green * 0.58f,
                blue = color.blue * 0.58f,
                alpha = 1f,
              ),
          )
        }
      }
    }

  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  // TV remote scrubbing: the underlying Seeker is a touch-only slider (rendered at alpha 0),
  // so the seekbar is not reachable or operable by the D-pad. When focused on Android TV,
  // LEFT/RIGHT preview + commit a precise position so the remote can land on the bar and scrub.
  var dpadSeekPosition by remember { mutableFloatStateOf(currentPos) }
  val dpadStep = (safeDuration * 0.02f).coerceIn(1f, 30f)
  val dpadBigStep = (safeDuration * 0.10f).coerceIn(5f, 120f)
  var isDpadScrubbing by remember { mutableStateOf(false) }
  LaunchedEffect(currentPos, isDpadScrubbing) {
    if (!isDpadScrubbing) dpadSeekPosition = currentPos
  }
  val seekKeyModifier =
    if (isTelevision) {
      Modifier
        .focusable()
        .tvFocusHighlight(shape = RoundedCornerShape(percent = 50))
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) {
            return@onKeyEvent false
          }
          when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> {
              if (event.type == KeyEventType.KeyDown) {
                val repeats = event.nativeKeyEvent.repeatCount
                val step = if (repeats > 0) dpadBigStep else dpadStep
                val dir = if (event.key == Key.DirectionLeft) -1f else 1f
                isDpadScrubbing = true
                dpadSeekPosition = (dpadSeekPosition + dir * step).coerceIn(0f, safeDuration)
                onUserInteractionChange(true)
                onUserPositionChange(dpadSeekPosition)
                onValueChange(dpadSeekPosition)
              } else {
                onValueChangeFinished(dpadSeekPosition)
                onUserInteractionChange(false)
                isDpadScrubbing = false
              }
              true
            }
            else -> false
          }
        }
    } else {
      Modifier
    }

  var expandedPreview by remember { mutableStateOf(false) }
  // Pointer coroutines retain updated callbacks without restarting on each position tick.
  val updatePosition by rememberUpdatedState<(Float) -> Unit> { target ->
    onUserInteractionChange(true)
    latestInteractionPosition = target
    onUserPositionChange(target)
    onValueChange(target)
  }
  val finishPosition by rememberUpdatedState<(Float) -> Unit> { target ->
    onValueChangeFinished(target)
    onUserInteractionChange(false)
    scope.launch { animatedPosition.snapTo(target) }
  }
  val cancelPosition by rememberUpdatedState {
    onUserInteractionChange(false)
    onValueChangeCancelled()
  }

  val activeInteraction by rememberUpdatedState(isUserInteracting)
  DisposableEffect(thumbnailPreview != null) {
    onDispose {
      if (thumbnailPreview != null && activeInteraction) cancelPosition()
    }
  }

  Box(
    modifier = modifier.then(seekKeyModifier),
    contentAlignment = Alignment.Center,
  ) {
    val waveSeekbarActive = showWavyVisualizer && waveFeatures != null && wavePalette != null
    if (waveSeekbarActive) {
      val waveTrackHeight = if (seekbarStyle == SeekbarStyle.Wavy) 5.dp else overlayTrackHeight
      WaveVisualizerOverlay(
        palette = wavePalette,
        isSheetOpen = waveSheetOpen,
        features = waveFeatures,
        isPlaying = !paused && !isVisuallyInteracting,
        progressProvider = {
          if (safeDuration > 0f) (positionProvider() / safeDuration).coerceIn(0f, 1f) else 0f
        },
        trackHeight = waveTrackHeight,
        trackColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().matchParentSize(),
      )
    }
    // Visual seekbar (smaller, centered) - always drawn on top of the wave
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(32.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (seekbarStyle) {
        SeekbarStyle.Normal -> {
          NormalSeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
        SeekbarStyle.Standard -> {
          StandardSeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            seekbarStyle = SeekbarStyle.Standard,
            interactionSource = seekerInteractionSource,
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
        SeekbarStyle.Wavy -> {
          SquigglySeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            useWavySeekbar = useWavySeekbar && !waveSeekbarActive,
            seekbarStyle = SeekbarStyle.Wavy,
            onSeek = { }, // Touch handled by parent
            onSeekFinished = { }, // Touch handled by parent
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
        SeekbarStyle.Thick -> {
          StandardSeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            seekbarStyle = SeekbarStyle.Thick,
            interactionSource = seekerInteractionSource,
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
        SeekbarStyle.Slim -> {
          SlimSeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
        SeekbarStyle.Liquid -> {
          LiquidSeekbar(
            positionProvider = positionProvider,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isVisuallyInteracting,
            interactionSource = seekerInteractionSource,
            loopStart = loopStart,
            loopEnd = loopEnd,
            bufferDuration = bufferDuration,
          )
        }
      }
    }

    Canvas(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(overlayTrackHeight)
          .clip(RoundedCornerShape(percent = 50))
          .align(Alignment.Center),
    ) {
      if (skipSegmentOverlays.isNotEmpty()) {
        val trackHeight = size.height
        val edgeStroke = 2.dp.toPx()
        val minimumMarkerWidth = 2.dp.toPx().coerceAtMost(size.width)
        skipSegmentOverlays.forEach { overlay ->
          val requestedStartX = overlay.startFraction * size.width
          val requestedEndX = overlay.endFraction * size.width
          val markerWidth = maxOf(requestedEndX - requestedStartX, minimumMarkerWidth)
          val startX = requestedStartX.coerceIn(0f, (size.width - markerWidth).coerceAtLeast(0f))
          val endX = (startX + markerWidth).coerceAtMost(size.width)
          drawRect(
            color = overlay.fillColor,
            topLeft = Offset(startX, 0f),
            size = Size(endX - startX, trackHeight),
          )
          drawLine(
            color = overlay.edgeColor,
            start = Offset(startX, 0f),
            end = Offset(startX, trackHeight),
            strokeWidth = edgeStroke,
          )
          drawLine(
            color = overlay.edgeColor,
            start = Offset(endX, 0f),
            end = Offset(endX, trackHeight),
            strokeWidth = edgeStroke,
          )
        }
      }

    }

    val activeClip = clipRange.takeIf { showClipRange }
    val clipEnd = activeClip?.endSeconds
    if (activeClip != null && clipEnd != null && safeDuration > 0f && clipEnd > activeClip.startSeconds) {
      ClipRangeSelection(
        startSeconds = activeClip.startSeconds,
        endSeconds = clipEnd,
        duration = safeDuration,
        color = MaterialTheme.colorScheme.tertiary,
        trackHeight = overlayTrackHeight,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(touchAreaHeight)
            .align(Alignment.Center),
      )
    }

    if (thumbnailPreview != null && !isTelevision) {
      Box(
        Modifier.fillMaxWidth().height(touchAreaHeight).thumbnailScrubInput(
          duration = safeDuration,
          position = positionProvider,
          onPosition = { updatePosition(it) },
          onExpanded = { expandedPreview = it },
          onFinished = { finishPosition(it) },
          onCancelled = { cancelPosition() },
        ),
      )
    } else Seeker(
      state = seekerState,
      value = safeCommittedPosition,
      thumbValue = safeThumbPosition,
      range = seekerRange,
      progressStartPosition = (safeCommittedPosition / seekerRange.endInclusive).coerceIn(0f, 1f),
      readAheadValue =
        normalizedReadAheadValue(
          bufferPosition = bufferDuration,
          playedPosition = safeCommittedPosition,
          duration = safeDuration,
        ).coerceIn(seekerRange),
      segments = seekerSegments,
      enabled = safeDuration > 0f,
      interactionSource = seekerInteractionSource,
      colors =
        SeekerDefaults.seekerColors(
          progressColor = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.primary,
          disabledProgressColor = MaterialTheme.colorScheme.primary,
          disabledTrackColor = MaterialTheme.colorScheme.primary,
          thumbColor = MaterialTheme.colorScheme.primary,
          disabledThumbColor = MaterialTheme.colorScheme.primary,
          readAheadColor = MaterialTheme.colorScheme.primary,
        ),
      dimensions =
        SeekerDefaults.seekerDimensions(
          trackHeight = 0.dp,
          progressHeight = 0.dp,
          thumbRadius = 0.dp,
          gap = 0.dp,
        ),
      onValueChange = { newPosition ->
        val targetPosition = newPosition.coerceIn(0f, safeDuration)
        onUserInteractionChange(true)
        latestInteractionPosition = targetPosition
        onUserPositionChange(targetPosition)
        onValueChange(targetPosition)
      },
      onValueChangeFinished = {
        val targetPosition = latestInteractionPosition.coerceIn(0f, safeDuration)
        scope.launch {
          animatedPosition.snapTo(targetPosition)
          onUserPositionChange(targetPosition)
          onValueChangeFinished(targetPosition)
          onUserInteractionChange(false)
        }
      },
      modifier =
        Modifier
          .fillMaxWidth()
          .height(touchAreaHeight)
          .graphicsLayer(alpha = 0f),
    )
    if (thumbnailPreview != null && isUserInteracting && safeDuration > 0f) {
      SeekThumbnailPopup(safeThumbPosition / safeDuration) {
        thumbnailPreview(safeThumbPosition, expandedPreview)
      }
    }
  }
}

@Composable
private fun ClipRangeSelection(
  startSeconds: Float,
  endSeconds: Float,
  duration: Float,
  color: Color,
  trackHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val targetStart = (startSeconds / duration).coerceIn(0f, 1f)
  val targetEnd = (endSeconds / duration).coerceIn(targetStart, 1f)
  val boundaryMotion = AppMotion.noBounce<Float>(Spring.StiffnessMedium)
  val animatedStart by animateFloatAsState(
    targetValue = targetStart,
    animationSpec = boundaryMotion,
    label = "ClipRangeStart",
  )
  val animatedEnd by animateFloatAsState(
    targetValue = targetEnd,
    animationSpec = boundaryMotion,
    label = "ClipRangeEnd",
  )

  Canvas(
    modifier = modifier,
  ) {
    val startX = animatedStart.coerceIn(0f, 1f) * size.width
    val endX = animatedEnd.coerceIn(animatedStart.coerceIn(0f, 1f), 1f) * size.width
    val selectionWidth = endX - startX
    if (selectionWidth <= 0f || size.height <= 0f) return@Canvas

    val trackHeightPx = trackHeight.toPx().coerceAtMost(size.height)
    val trackTop = (size.height - trackHeightPx) / 2f
    val cornerRadius = CornerRadius(trackHeightPx / 2f)
    val outlineWidth = 1.dp.toPx().coerceAtMost(trackHeightPx / 3f)
    drawRoundRect(
      color = color.copy(alpha = 0.32f),
      topLeft = Offset(startX, trackTop),
      size = Size(selectionWidth, trackHeightPx),
      cornerRadius = cornerRadius,
    )
    drawRoundRect(
      color = color.copy(alpha = 0.86f),
      topLeft = Offset(startX, trackTop),
      size = Size(selectionWidth, trackHeightPx),
      cornerRadius = cornerRadius,
      style = Stroke(width = outlineWidth),
    )

    val guideInset = 2.dp.toPx().coerceAtMost(size.height / 4f)
    val dashLength = 4.dp.toPx()
    val dashGap = 3.dp.toPx()
    val guideStroke = 2.dp.toPx()
    val guideEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, dashGap))
    fun drawBoundary(markerX: Float) {
      drawLine(
        color = color.copy(alpha = 0.96f),
        start = Offset(markerX, guideInset),
        end = Offset(markerX, size.height - guideInset),
        strokeWidth = guideStroke,
        pathEffect = guideEffect,
        cap = StrokeCap.Round,
      )
    }
    drawBoundary(startX)
    drawBoundary(endX)
  }
}

/**
 * A conventional media timeline with a circular thumb, buffered range, and chapter segments.
 * Renders full width (0..size.width) matching other styles and skip overlay geometry.
 */
@Composable
private fun NormalSeekbar(
  positionProvider: () -> Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean,
  isScrubbing: Boolean,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()
  val trackHeight by animateDpAsState(
    targetValue = if (isScrubbing) 6.dp else 4.dp,
    animationSpec = spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness),
    label = "normal_seekbar_height",
  )
  val thumbRadiusDp by animateDpAsState(
    targetValue = if (isScrubbing) 9.dp else 6.5.dp,
    animationSpec = spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness),
    label = "normal_seekbar_thumb",
  )

  val chapterFractions =
    remember(chapters, duration) {
      if (duration <= 0f) {
        FloatArray(0)
      } else {
        chapters
          .mapNotNull {
            val f = it.start / duration
            if (f.isFinite() && f in 0.005f..0.995f) f else null
          }.sorted().toFloatArray()
      }
    }

  Canvas(
    modifier = modifier.fillMaxWidth().height(32.dp),
  ) {
    val totalWidth = size.width
    val centerY = size.height / 2f
    val currentPosition = positionProvider()
    val progress = if (duration > 0f && currentPosition.isFinite()) (currentPosition / duration).coerceIn(0f, 1f) else 0f
    val playedPx = (totalWidth * progress).coerceIn(0f, totalWidth)
    val height = trackHeight.toPx()
    val radius = height / 2f
    val thumbR = thumbRadiusDp.toPx()
    val gapHalf = 1.dp.toPx()

    fun drawSegmentedTrack(startX: Float, endX: Float, color: Color) {
      if (endX <= startX) return
      if (chapterFractions.isEmpty()) {
        drawRoundRect(
          color = color,
          topLeft = Offset(startX, centerY - radius),
          size = Size(endX - startX, height),
          cornerRadius = CornerRadius(radius),
        )
        return
      }

      var currentSegmentStart = startX
      for (i in chapterFractions.indices) {
        val gapCenter = chapterFractions[i] * totalWidth
        if (gapCenter <= startX) continue
        if (gapCenter >= endX) break
        val segEnd = (gapCenter - gapHalf).coerceAtLeast(currentSegmentStart)
        if (segEnd > currentSegmentStart) {
          drawRoundRect(
            color = color,
            topLeft = Offset(currentSegmentStart, centerY - radius),
            size = Size(segEnd - currentSegmentStart, height),
            cornerRadius = CornerRadius(radius),
          )
        }
        currentSegmentStart = (gapCenter + gapHalf).coerceAtMost(endX)
      }
      if (currentSegmentStart < endX) {
        drawRoundRect(
          color = color,
          topLeft = Offset(currentSegmentStart, centerY - radius),
          size = Size(endX - currentSegmentStart, height),
          cornerRadius = CornerRadius(radius),
        )
      }
    }

    // 1. Background unplayed track
    drawSegmentedTrack(0f, totalWidth, primaryColor.copy(alpha = emptyAlpha))

    // 2. Buffer readahead track. Normal already uses a real round-rect overlay,
    // so its cache endpoint naturally follows the same pill geometry.
    if (bufferDuration != null && bufferDuration > 0f && duration > 0f) {
      val bufferPx = bufferedEndPx(bufferDuration, duration, totalWidth, playedPx)
      if (bufferPx > playedPx) {
        drawSegmentedTrack(playedPx, bufferPx, primaryColor.copy(alpha = readAheadAlpha))
      }
    }

    // 3. Played track
    if (playedPx > 0f) {
      drawSegmentedTrack(0f, playedPx, primaryColor)
    }

    // 4. Circular thumb. Keep the whole circle inside the canvas at 0%/100%; a
    // thumb centred directly on the canvas edge is clipped into a flat semicircle.
    val thumbCenterX =
      if (totalWidth > thumbR * 2f) {
        playedPx.coerceIn(thumbR, totalWidth - thumbR)
      } else {
        totalWidth / 2f
      }
    drawCircle(
      color = primaryColor,
      radius = thumbR,
      center = Offset(thumbCenterX, centerY),
    )

    // 5. A-B Loop indicators
    if (loopStart != null || loopEnd != null) {
      val loopColor = Color(0xFFFFB300)
      val markerW = 2.dp.toPx()
      if (loopStart != null && duration > 0f) {
        val px = (loopStart / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(px, centerY - thumbR), Offset(px, centerY + thumbR), markerW)
      }
      if (loopEnd != null && duration > 0f) {
        val px = (loopEnd / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(px, centerY - thumbR), Offset(px, centerY + thumbR), markerW)
      }
      if (loopStart != null && loopEnd != null && duration > 0f) {
        val minPx = (minOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        val maxPx = (maxOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        drawRect(
          color = loopColor.copy(alpha = 0.2f),
          topLeft = Offset(minPx, centerY - thumbR),
          size = Size(maxPx - minPx, thumbR * 2),
        )
      }
    }
  }
}

@Composable
private fun SquigglySeekbar(
  positionProvider: () -> Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean,
  isScrubbing: Boolean,
  useWavySeekbar: Boolean,
  seekbarStyle: SeekbarStyle,
  onSeek: (Float) -> Unit,
  onSeekFinished: () -> Unit,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()
  val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

  val isInteracting = isScrubbing
  val thumbVisibilityState = animateFloatAsState(
    targetValue = if (isInteracting) 0f else 1f,
    animationSpec =
      spring(
        dampingRatio = AppMotion.Effect.Alpha.dampingRatio,
        stiffness = AppMotion.Effect.Alpha.stiffness,
      ),
    label = "wavy_seekbar_thumb_visibility",
  )

  // Animation state
  var phaseOffset by remember { mutableFloatStateOf(0f) }
  var heightFraction by remember { mutableFloatStateOf(1f) }

  // Wave parameters
  val waveLength = 80f
  val lineAmplitude = if (useWavySeekbar) 6f else 0f
  val phaseSpeed = 10f // px per second
  val transitionPeriods = 1.5f
  val minWaveEndpoint = 0f
  val matchedWaveEndpoint = 1f
  val transitionEnabled = true

  val wavyPath = remember { Path() }
  val chapterFractions =
    remember(chapters, duration) {
      if (duration <= 0f) {
        FloatArray(0)
      } else {
        chapters
          .mapNotNull {
            val f = it.start / duration
            if (f.isFinite() && f in 0f..1f) f else null
          }.toFloatArray()
      }
    }
  val chapterStarts = remember(chapters) { chapters.map(Segment::start) }

  // Animate height fraction based on paused state and scrubbing state
  LaunchedEffect(isPaused, isScrubbing, useWavySeekbar) {
    if (!useWavySeekbar) {
      heightFraction = 0f
      return@LaunchedEffect
    }

    val shouldFlatten = isPaused || isScrubbing
    val targetHeight = if (shouldFlatten) 0f else 1f
    val startDelay = if (shouldFlatten) 0L else 60L

    if (startDelay > 0L) {
      kotlinx.coroutines.delay(startDelay)
    }

    val animator = Animatable(heightFraction)
    animator.animateTo(
      targetValue = targetHeight,
      animationSpec =
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        ),
    ) {
      heightFraction = value
    }
  }

  // Animate wave movement only when not paused
  LaunchedEffect(isPaused, useWavySeekbar) {
    if (isPaused || !useWavySeekbar) return@LaunchedEffect

    var lastFrameTime = withFrameMillis { it }
    while (isActive) {
      withFrameMillis { frameTimeMillis ->
        val deltaTime = (frameTimeMillis - lastFrameTime) / 1000f
        phaseOffset += deltaTime * phaseSpeed
        phaseOffset %= waveLength
        lastFrameTime = frameTimeMillis
      }
    }
  }

  Canvas(
    modifier =
      modifier
        .fillMaxWidth()
        .height(48.dp),
  ) {
    val currentPosition = positionProvider()
    val strokeWidth = 5.dp.toPx()
    val progress = if (duration > 0f && currentPosition.isFinite()) (currentPosition / duration).coerceIn(0f, 1f) else 0f
    val totalWidth = size.width
    val totalProgressPx = totalWidth * progress
    val centerY = size.height / 2f

    // Calculate wave progress
    val waveProgressPx =
      if (!transitionEnabled || progress > matchedWaveEndpoint) {
        totalWidth * progress
      } else {
        val t = (progress / matchedWaveEndpoint).coerceIn(0f, 1f)
        totalWidth * (minWaveEndpoint + (matchedWaveEndpoint - minWaveEndpoint) * t)
      }

    // Helper function to compute amplitude
    fun computeAmplitude(
      x: Float,
      sign: Float,
    ): Float =
      if (transitionEnabled) {
        val length = transitionPeriods * waveLength
        val coeff = ((waveProgressPx + length / 2f - x) / length).coerceIn(0f, 1f)
        sign * heightFraction * lineAmplitude * coeff
      } else {
        sign * heightFraction * lineAmplitude
      }

    // Build wavy path for played portion
    wavyPath.reset()
    val waveStart = -phaseOffset - waveLength / 2f
    val waveEnd = if (transitionEnabled) totalWidth else waveProgressPx

    wavyPath.moveTo(waveStart, centerY)

    var currentX = waveStart
    var waveSign = 1f
    var currentAmp = computeAmplitude(currentX, waveSign)
    val dist = waveLength / 2f

    while (currentX < waveEnd) {
      waveSign = -waveSign
      val nextX = currentX + dist
      val midX = currentX + dist / 2f
      val nextAmp = computeAmplitude(nextX, waveSign)

      wavyPath.cubicTo(
        midX,
        centerY + currentAmp,
        midX,
        centerY + nextAmp,
        nextX,
        centerY + nextAmp,
      )

      currentAmp = nextAmp
      currentX = nextX
    }

    // Draw path up to progress position using clipping
    val clipTop = lineAmplitude + strokeWidth
    val gapHalf = 1.dp.toPx()
    val bufferColor = primaryColor.copy(alpha = readAheadAlpha)
    val unplayedColor = primaryColor.copy(alpha = emptyAlpha)
    val bufferPx =
      if (bufferDuration != null && bufferDuration > 0f && duration > 0f) {
        bufferedEndPx(bufferDuration, duration, totalWidth, totalProgressPx)
      } else {
        totalProgressPx
      }

    fun drawPathWithGaps(
      startX: Float,
      endX: Float,
      color: Color,
    ) {
      if (endX <= startX) return
      if (duration <= 0f || chapterFractions.isEmpty()) {
        clipRect(
          left = startX,
          top = centerY - clipTop,
          right = endX,
          bottom = centerY + clipTop,
        ) {
          drawPath(
            path = wavyPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
          )
        }
        return
      }

      var segmentStart = startX
      for (i in chapterFractions.indices) {
        val gapCenter = chapterFractions[i] * totalWidth
        if (gapCenter < startX) continue
        if (gapCenter > endX) break
        val gapStart = (gapCenter - gapHalf).coerceAtLeast(startX)
        val gapEnd = (gapCenter + gapHalf).coerceAtMost(endX)
        if (gapStart > segmentStart) {
          clipRect(
            left = segmentStart,
            top = centerY - clipTop,
            right = gapStart,
            bottom = centerY + clipTop,
          ) {
            drawPath(
              path = wavyPath,
              color = color,
              style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
          }
        }
        segmentStart = gapEnd
      }
      if (segmentStart < endX) {
        clipRect(
          left = segmentStart,
          top = centerY - clipTop,
          right = endX,
          bottom = centerY + clipTop,
        ) {
          drawPath(
            path = wavyPath,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
          )
        }
      }
    }

    fun waveYAt(x: Float): Float {
      val envelope =
        if (transitionEnabled) {
          val length = transitionPeriods * waveLength
          ((waveProgressPx + length / 2f - x) / length).coerceIn(0f, 1f)
        } else {
          1f
        }
      val phase = (x - waveStart) / waveLength * (2f * kotlin.math.PI.toFloat())
      return centerY + kotlin.math.cos(phase) * heightFraction * lineAmplitude * envelope
    }

    if (heightFraction <= 0.05f) {
      // Flattened Wavy shares the same layered renderer as Slim/Standard/Thick,
      // so the cache endpoint and the physical track ends keep the same radius.
      val segments =
        seekbarTrackSegments(
          chapterStarts = chapterStarts,
          duration = duration,
          trackWidth = totalWidth,
          chapterGapHalf = gapHalf,
        )
      drawSeekbarTrackSegments(
        segments = segments,
        playedPx = totalProgressPx,
        bufferedPx = bufferPx,
        centerY = centerY,
        trackHeight = strokeWidth,
        playedColor = primaryColor,
        bufferedColor = bufferColor,
        unplayedColor = unplayedColor,
      )
    } else {
      // The unplayed path is the base layer, then cache and played are painted on
      // top. Keeping this order allows the rounded cache terminal to reveal the
      // correct unplayed color beneath its curved corners.
      if (transitionEnabled) {
        drawPathWithGaps(0f, totalWidth, unplayedColor)
      } else {
        drawLine(
          color = surfaceVariant.copy(alpha = 0.4f),
          start = Offset(0f, centerY),
          end = Offset(totalWidth, centerY),
          strokeWidth = strokeWidth,
          cap = StrokeCap.Round,
        )
      }

      if (bufferPx > totalProgressPx) {
        drawPathWithGaps(totalProgressPx, bufferPx, bufferColor)

        // clipRect intentionally cuts the path at bufferPx, which makes the cache
        // endpoint flat. Restore a pill cap entirely *inside* the cached range so
        // it remains accurate and does not over-report the buffered position.
        val availableBufferWidth = bufferPx - totalProgressPx
        val capRadius = minOf(strokeWidth / 2f, availableBufferWidth / 2f)
        val nearChapterGap =
          chapterFractions.any { fraction ->
            kotlin.math.abs(fraction * totalWidth - bufferPx) <= gapHalf + capRadius
          }
        if (capRadius > 0.5f && bufferPx < totalWidth - 0.5f && !nearChapterGap) {
          val capCenterX = bufferPx - capRadius
          drawCircle(
            color = bufferColor,
            radius = capRadius,
            center = Offset(capCenterX, waveYAt(capCenterX)),
          )
        }
      }

      drawPathWithGaps(0f, totalProgressPx, primaryColor)

      // A stroked path whose cap is centred on x=0/width loses half of the cap to
      // canvas clipping. Draw the terminal caps *inside* the canvas instead.
      val outerCapRadius = strokeWidth / 2f
      if (totalWidth > outerCapRadius * 2f) {
        val leftCapColor =
          when {
            totalProgressPx > 0.5f -> primaryColor
            bufferPx > 0.5f -> bufferColor
            else -> unplayedColor
          }
        val leftCapX = outerCapRadius
        drawCircle(
          color = leftCapColor,
          radius = outerCapRadius,
          center = Offset(leftCapX, waveYAt(leftCapX)),
        )

        val rightCapColor =
          when {
            totalProgressPx >= totalWidth - 0.5f -> primaryColor
            bufferPx >= totalWidth - 0.5f -> bufferColor
            else -> unplayedColor
          }
        val rightCapX = totalWidth - outerCapRadius
        drawCircle(
          color = rightCapColor,
          radius = outerCapRadius,
          center = Offset(rightCapX, waveYAt(rightCapX)),
        )
      }
    }

    // Vertical Bar Thumb
    val thumbVisibility = thumbVisibilityState.value
    val barHalfHeight = (lineAmplitude + strokeWidth) * thumbVisibility
    val barWidth = 5.dp.toPx()

    if (barHalfHeight > 0.5f && thumbVisibility > 0.05f) {
      val halfBarWidth = barWidth * thumbVisibility / 2f
      val thumbX =
        if (totalWidth > halfBarWidth * 2f) {
          totalProgressPx.coerceIn(halfBarWidth, totalWidth - halfBarWidth)
        } else {
          totalWidth / 2f
        }
      drawLine(
        color = primaryColor.copy(alpha = thumbVisibility),
        start = Offset(thumbX, centerY - barHalfHeight),
        end = Offset(thumbX, centerY + barHalfHeight),
        strokeWidth = barWidth * thumbVisibility,
        cap = StrokeCap.Round,
      )
    }

    // A-B Loop Indicators for SquigglySeekbar
    if (loopStart != null || loopEnd != null) {
      val loopColor = Color(0xFFFFB300)
      val markerWidth = 2.dp.toPx()

      if (loopStart != null && duration > 0f) {
        val startPx = (loopStart / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(
          color = loopColor,
          start = Offset(startPx, centerY - maxOf(lineAmplitude, strokeWidth)),
          end = Offset(startPx, centerY + maxOf(lineAmplitude, strokeWidth)),
          strokeWidth = markerWidth,
        )
      }

      if (loopEnd != null && duration > 0f) {
        val endPx = (loopEnd / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(
          color = loopColor,
          start = Offset(endPx, centerY - maxOf(lineAmplitude, strokeWidth)),
          end = Offset(endPx, centerY + maxOf(lineAmplitude, strokeWidth)),
          strokeWidth = markerWidth,
        )
      }

      if (loopStart != null && loopEnd != null && duration > 0f) {
        val minPx = (minOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        val maxPx = (maxOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        drawRect(
          color = loopColor.copy(alpha = 0.2f),
          topLeft = Offset(minPx, centerY - maxOf(lineAmplitude, strokeWidth)),
          size = Size(maxPx - minPx, maxOf(lineAmplitude, strokeWidth) * 2),
        )
      }
    }
  }
}

@Composable
private fun SlimSeekbar(
  positionProvider: () -> Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean,
  isScrubbing: Boolean,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()

  // Height breathes like other seekbars:
  //   paused  → 7dp  (relaxed/thin)
  //   playing → 10dp (normal)
  //   pressed → 20dp (expanded)
  val trackHeight by animateDpAsState(
    targetValue =
      when {
        isScrubbing -> 15.dp
        isPaused -> 6.dp
        else -> 8.dp
      },
    animationSpec =
      when {
        isScrubbing -> spring(stiffness = 500f, dampingRatio = 0.75f)
        else ->
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
          )
      },
    label = "slim_seekbar_height",
  )

  // Chapter gap widens when pressed so segments look clearly distinct
  val chapterGapHalfDp by animateDpAsState(
    targetValue = if (isScrubbing) 2.dp else 1.5.dp,
    animationSpec =
      spring(
        dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
        stiffness = AppMotion.Spatial.Standard.stiffness,
      ),
    label = "slim_chapter_gap",
  )

  // Colors stay constant — only height changes on press
  val playedColor = primaryColor
  val unplayedColor = primaryColor.copy(alpha = emptyAlpha)

  val chapterStarts = remember(chapters) { chapters.map(Segment::start) }

  Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
    val currentPosition = positionProvider()
    val progress = if (duration > 0f && currentPosition.isFinite()) (currentPosition / duration).coerceIn(0f, 1f) else 0f
    val totalWidth = size.width
    val playedPx = totalWidth * progress
    val centerY = size.height / 2f
    val height = trackHeight.toPx()
    val outerRadius = height / 2f // full pill for track ends
    val gapHalf = chapterGapHalfDp.toPx()

    val segments =
      seekbarTrackSegments(
        chapterStarts = chapterStarts,
        duration = duration,
        trackWidth = totalWidth,
        chapterGapHalf = gapHalf,
      )

    val bufferPx =
      bufferedEndPx(bufferDuration, duration, totalWidth, playedPx)

    drawSeekbarTrackSegments(
      segments = segments,
      playedPx = playedPx,
      bufferedPx = bufferPx,
      centerY = centerY,
      trackHeight = height,
      playedColor = playedColor,
      bufferedColor = primaryColor.copy(alpha = readAheadAlpha),
      unplayedColor = unplayedColor,
    )

    // A-B loop markers
    if (loopStart != null || loopEnd != null) {
      val loopColor = Color(0xFFFFB300)
      val markerW = 2.dp.toPx()
      if (loopStart != null && duration > 0f) {
        val px = (loopStart / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(px, centerY - outerRadius), Offset(px, centerY + outerRadius), markerW)
      }
      if (loopEnd != null && duration > 0f) {
        val px = (loopEnd / duration).coerceIn(0f, 1f) * totalWidth
        drawLine(loopColor, Offset(px, centerY - outerRadius), Offset(px, centerY + outerRadius), markerW)
      }
      if (loopStart != null && loopEnd != null && duration > 0f) {
        val minPx = (minOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        val maxPx = (maxOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * totalWidth
        drawRect(
          color = loopColor.copy(alpha = 0.2f),
          topLeft = Offset(minPx, centerY - outerRadius),
          size = Size(maxPx - minPx, height),
        )
      }
    }
  }
}

@Composable
private fun LiquidSeekbar(
  positionProvider: () -> Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean,
  isScrubbing: Boolean,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val glassSettings = rememberLiquidGlassSettings()
  val accentColor = MaterialTheme.colorScheme.primary
  // Scoped only to the playback Liquid seekbar; preference sliders keep their own colors.
  val liquidThumbFilm = androidx.compose.ui.graphics.lerp(
    MaterialTheme.colorScheme.surfaceContainerHigh,
    accentColor,
    0.18f,
  )
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()
  val isPressed by interactionSource.collectIsPressedAsState()
  val isDragged by interactionSource.collectIsDraggedAsState()
  val isThumbInteracting = isPressed || isDragged || isScrubbing
  val trackHeight by animateDpAsState(
    targetValue = liquidSeekbarTrackHeight(isPaused),
    animationSpec = spring(
      dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
      stiffness = AppMotion.Spatial.Expressive.stiffness,
    ),
    label = "liquid_seekbar_height",
  )
  val chapterGapHalfDp by animateDpAsState(
    targetValue = if (isThumbInteracting) 2.dp else 1.5.dp,
    animationSpec = spring(
      dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
      stiffness = AppMotion.Spatial.Standard.stiffness,
    ),
    label = "liquid_seekbar_chapter_gap",
  )

  val chapterStarts = remember(chapters) { chapters.map(Segment::start) }
  val playerBackdrop = LocalKyantPlayerBackdrop.current
  val trackBackdrop = rememberLayerBackdrop()
  val density = LocalDensity.current
  val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
  val animationScope = rememberCoroutineScope()
  val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
  val animationRange = 0f..safeDuration.coerceAtLeast(0.1f)
  val initialPosition =
    positionProvider()
      .takeIf { it.isFinite() }
      ?.coerceIn(animationRange)
      ?: animationRange.start
  val dampedDragAnimation =
    remember(animationScope, animationRange.endInclusive) {
      DampedDragAnimation(
        animationScope = animationScope,
        initialValue = initialPosition,
        valueRange = animationRange,
        visibilityThreshold = 0.01f,
        initialScale = 1f,
        pressedScale = 1.5f,
        onDragStarted = {},
        onDragStopped = {},
        onDrag = { _, _ -> },
      )
    }
  val currentPosition = positionProvider()
  LaunchedEffect(currentPosition) {
    if (currentPosition.isFinite()) {
      dampedDragAnimation.updateValue(currentPosition)
    }
  }
  LaunchedEffect(isThumbInteracting) {
    if (isThumbInteracting) {
      dampedDragAnimation.press()
    } else {
      dampedDragAnimation.release()
    }
  }

  BoxWithConstraints(
    modifier = modifier.fillMaxWidth().height(48.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    val trackWidthPx = constraints.maxWidth.toFloat()
    val playedFraction =
      if (safeDuration > 0f) {
        dampedDragAnimation.progress.coerceIn(0f, 1f)
      } else {
        0f
      }

    // 1. The Track Layer with its own LayerBackdrop for refraction sampling
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .layerBackdrop(trackBackdrop),
      contentAlignment = Alignment.Center,
    ) {
      Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        val playedPx = size.width * playedFraction
        val bufferPx = bufferedEndPx(bufferDuration, safeDuration, size.width, playedPx)
        val centerY = size.height / 2f
        val heightPx = trackHeight.toPx()
        val radiusPx = heightPx / 2f

        val segments = seekbarTrackSegments(
          chapterStarts = chapterStarts,
          duration = safeDuration,
          trackWidth = size.width,
          chapterGapHalf = chapterGapHalfDp.toPx(),
        )

        drawSeekbarTrackSegments(
          segments = segments,
          playedPx = playedPx,
          bufferedPx = bufferPx,
          centerY = centerY,
          trackHeight = heightPx,
          playedColor = accentColor,
          bufferedColor = accentColor.copy(alpha = readAheadAlpha),
          unplayedColor = accentColor.copy(alpha = emptyAlpha),
        )

        // Loop markers
        if ((loopStart != null || loopEnd != null) && safeDuration > 0f) {
          val loopColor = Color(0xFFFFB300)
          val markerWidth = 2.dp.toPx()
          val trackTop = centerY - radiusPx
          val trackBottom = centerY + radiusPx

          loopStart?.let { start ->
            val startPx = (start / safeDuration).coerceIn(0f, 1f) * size.width
            drawLine(loopColor, Offset(startPx, trackTop), Offset(startPx, trackBottom), markerWidth)
          }
          loopEnd?.let { end ->
            val endPx = (end / safeDuration).coerceIn(0f, 1f) * size.width
            drawLine(loopColor, Offset(endPx, trackTop), Offset(endPx, trackBottom), markerWidth)
          }
          if (loopStart != null && loopEnd != null) {
            val minPx = (minOf(loopStart, loopEnd) / safeDuration).coerceIn(0f, 1f) * size.width
            val maxPx = (maxOf(loopStart, loopEnd) / safeDuration).coerceIn(0f, 1f) * size.width
            drawRect(
              color = loopColor.copy(alpha = 0.2f),
              topLeft = Offset(minPx, trackTop),
              size = Size(maxPx - minPx, heightPx),
            )
          }
        }
      }
    }

    // Kyant's liquid thumb, driven by the existing Seeker interaction layer.
    val thumbWidthDp = 40.dp
    // The pressed thumb magnifies the track. It refracts the video as well once the player exposes
    // a sampled video layer; until then (and in the settings preview) it refracts the track alone.
    val trackLensBackdrop =
      rememberBackdrop(trackBackdrop) { drawBackdrop ->
        val progress = dampedDragAnimation.pressProgress
        val scaleX = lerp(2f / 3f, 1f, progress)
        val scaleY = lerp(0f, 1f, progress)
        scale(scaleX, scaleY) {
          drawBackdrop()
        }
      }
    val thumbBackdrop =
      if (playerBackdrop != null) rememberCombinedBackdrop(playerBackdrop, trackLensBackdrop) else trackLensBackdrop
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .graphicsLayer {
          val thumbWidthPx = with(density) { thumbWidthDp.toPx() }
          val minCenterPx = (thumbWidthPx / 2f).coerceAtMost(trackWidthPx / 2f)
          val safeCenterPx = (trackWidthPx * playedFraction)
            .coerceIn(minCenterPx, trackWidthPx - minCenterPx)
          translationX = (safeCenterPx - thumbWidthPx / 2f) * if (isLtr) 1f else -1f
        }
        .drawBackdrop(
          backdrop = thumbBackdrop,
          shape = { Capsule() },
          effects = {
            val progress = dampedDragAnimation.pressProgress
            liquidGlassEffects(
              glassSettings,
              with(density) { 8.dp.toPx() * (1f - progress) },
              with(density) { 10.dp.toPx() * progress },
              with(density) { 14.dp.toPx() * progress },
            )
          },
          highlight = {
            val progress = dampedDragAnimation.pressProgress
            glassSettings.highlight(Highlight.Ambient.copy(
              width = Highlight.Ambient.width / 1.5f,
              blurRadius = Highlight.Ambient.blurRadius / 1.5f,
              alpha = progress,
            ))
          },
          shadow = {
            Shadow(
              radius = 4.dp,
              color = Color.Black.copy(alpha = 0.05f),
            )
          },
          innerShadow = {
            val progress = dampedDragAnimation.pressProgress
            InnerShadow(
              radius = 4.dp * progress,
              alpha = progress,
            )
          },
          layerBlock = {
            scaleX = dampedDragAnimation.scaleX
            scaleY = dampedDragAnimation.scaleY
            val velocity = dampedDragAnimation.velocity / 10f
            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
          },
          onDrawSurface = {
            val progress = dampedDragAnimation.pressProgress
            val film = androidx.compose.ui.graphics.lerp(
              Color.White,
              liquidThumbFilm.copy(alpha = 0.72f - 0.28f * progress),
              progress.coerceIn(0f, 1f),
            )
            drawCapsuleTint(glassSettings.surfaceColor(film))
          },
        )
        .size(thumbWidthDp, 24.dp),
    )
  }
}

@Composable
fun SeekbarStylePreview(
  style: SeekbarStyle,
  progress: Float = 0.38f,
  useWavySeekbar: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()
  val previewProgress = progress

  val slimPath = remember { Path() }

  if (style == SeekbarStyle.Wavy) {
    SquigglySeekbar(
      positionProvider = { previewProgress * 100f },
      duration = 100f,
      chapters = persistentListOf(),
      isPaused = false,
      isScrubbing = false,
      useWavySeekbar = useWavySeekbar,
      seekbarStyle = SeekbarStyle.Wavy,
      onSeek = {},
      onSeekFinished = {},
      modifier = modifier,
    )
  } else if (style == SeekbarStyle.Liquid) {
    LiquidSeekbar(
      positionProvider = { previewProgress * 100f },
      duration = 100f,
      chapters = persistentListOf(),
      isPaused = false,
      isScrubbing = false,
      modifier = modifier,
    )
  } else {
    Canvas(modifier = modifier.fillMaxWidth().height(36.dp)) {
      val playedPx = size.width * previewProgress
      val centerY = size.height / 2f

      when (style) {
        SeekbarStyle.Normal -> {
          val trackHeight = 4.dp.toPx()
          val thumbRadius = 7.dp.toPx()
          drawRoundRect(
            color = primaryColor.copy(alpha = emptyAlpha),
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
          )
          drawRoundRect(
            color = primaryColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(playedPx, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
          )
          val thumbCenterX =
            if (size.width > thumbRadius * 2f) {
              playedPx.coerceIn(thumbRadius, size.width - thumbRadius)
            } else {
              size.width / 2f
            }
          drawCircle(primaryColor, radius = thumbRadius, center = Offset(thumbCenterX, centerY))
        }
        SeekbarStyle.Slim -> {
          val height = 10.dp.toPx()
          val radius = height / 2f
          drawRoundRect(
            color = primaryColor.copy(alpha = emptyAlpha),
            topLeft = Offset(0f, centerY - radius),
            size = Size(size.width, height),
            cornerRadius = CornerRadius(radius),
          )
          if (playedPx > 0f) {
            slimPath.reset()
            slimPath.addRoundRect(
              androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = centerY - radius,
                right = playedPx,
                bottom = centerY + radius,
                topLeftCornerRadius = CornerRadius(radius),
                bottomLeftCornerRadius = CornerRadius(radius),
                topRightCornerRadius =
                  CornerRadius(
                    if (playedPx >=
                      size.width - 0.5f
                    ) {
                      radius
                    } else {
                      0f
                    },
                  ),
                bottomRightCornerRadius =
                  CornerRadius(
                    if (playedPx >=
                      size.width - 0.5f
                    ) {
                      radius
                    } else {
                      0f
                    },
                  ),
              ),
            )
            drawPath(slimPath, primaryColor)
          }
        }
        SeekbarStyle.Standard -> {
          val height = 8.dp.toPx()
          val radius = height / 2f
          val thumbW = 3.dp.toPx()
          val gapHalf = (thumbW + 10.dp.toPx()) / 2f
          val thumbStart = (playedPx - gapHalf).coerceIn(0f, size.width)
          val thumbEnd = (playedPx + gapHalf).coerceIn(0f, size.width)
          drawRoundRect(
            color = primaryColor.copy(alpha = emptyAlpha),
            topLeft = Offset(thumbEnd, centerY - radius),
            size = Size((size.width - thumbEnd).coerceAtLeast(0f), height),
            cornerRadius = CornerRadius(radius),
          )
          if (thumbStart > 0f) {
            drawRoundRect(
              color = primaryColor,
              topLeft = Offset(0f, centerY - radius),
              size = Size(thumbStart, height),
              cornerRadius = CornerRadius(radius),
            )
          }
          val thumbHalfH = 12.dp.toPx()
          val thumbLeft =
            (playedPx - thumbW / 2f)
              .coerceIn(0f, (size.width - thumbW).coerceAtLeast(0f))
          drawRoundRect(
            color = primaryColor,
            topLeft = Offset(thumbLeft, centerY - thumbHalfH),
            size = Size(thumbW, thumbHalfH * 2),
            cornerRadius = CornerRadius(thumbW / 2f),
          )
        }
        SeekbarStyle.Thick -> {
          val height = 16.dp.toPx()
          val radius = height / 2f
          val thumbW = 4.dp.toPx()
          val gapHalf = (thumbW + 18.dp.toPx()) / 2f
          val thumbStart = (playedPx - gapHalf).coerceIn(0f, size.width)
          val thumbEnd = (playedPx + gapHalf).coerceIn(0f, size.width)
          drawRoundRect(
            color = primaryColor.copy(alpha = emptyAlpha),
            topLeft = Offset(thumbEnd, centerY - radius),
            size = Size((size.width - thumbEnd).coerceAtLeast(0f), height),
            cornerRadius = CornerRadius(radius),
          )
          if (thumbStart > 0f) {
            drawRoundRect(
              color = primaryColor,
              topLeft = Offset(0f, centerY - radius),
              size = Size(thumbStart, height),
              cornerRadius = CornerRadius(radius),
            )
          }
          val thumbLeft =
            (playedPx - thumbW / 2f)
              .coerceIn(0f, (size.width - thumbW).coerceAtLeast(0f))
          drawRoundRect(
            color = primaryColor,
            topLeft = Offset(thumbLeft, centerY - radius),
            size = Size(thumbW, height),
            cornerRadius = CornerRadius(thumbW / 2f),
          )
        }
        SeekbarStyle.Wavy,
        SeekbarStyle.Liquid -> {
          // Handled in parent branch
        }
      }
    }
  }
}

@Composable
fun SeekbarStyleLivePreview(
  style: SeekbarStyle,
  useWavySeekbar: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val duration = 120f
  val reducedMotion = AppMotion.shouldReduceMotion()
  var position by rememberSaveable { mutableFloatStateOf(38f) }
  var paused by rememberSaveable { mutableStateOf(reducedMotion) }
  var showChapters by rememberSaveable { mutableStateOf(true) }
  var showSkipMarkers by rememberSaveable { mutableStateOf(true) }
  var showLoop by rememberSaveable { mutableStateOf(false) }
  var isUserInteracting by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val isDragged by interactionSource.collectIsDraggedAsState()
  val isInteracting = isUserInteracting || isPressed || isDragged
  val scope = rememberCoroutineScope()
  val animatedPosition = remember { Animatable(position) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val chapterTitles = (1..4).map { stringResource(R.string.audiobook_chapter_number, it) }
  val chapters = remember(chapterTitles) {
    persistentListOf(
      Segment(chapterTitles[0], 0f),
      Segment(chapterTitles[1], 30f),
      Segment(chapterTitles[2], 60f),
      Segment(chapterTitles[3], 90f),
    )
  }
  val skipMarkers = remember {
    persistentListOf(
      SkipSegment(SkipSegmentType.INTRO, 4.0, 14.0, "preview"),
      SkipSegment(SkipSegmentType.RECAP, 24.0, 29.0, "preview"),
      SkipSegment(SkipSegmentType.OUTRO, 92.0, 101.0, "preview"),
      SkipSegment(SkipSegmentType.CREDITS, 106.0, 113.0, "preview"),
      SkipSegment(SkipSegmentType.PREVIEW, 116.0, 120.0, "preview"),
    )
  }
  LaunchedEffect(paused, isInteracting, lifecycleOwner, reducedMotion) {
    if (paused || isInteracting || reducedMotion) return@LaunchedEffect
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      var previousFrame = withFrameMillis { it }
      while (isActive) {
        val frame = withFrameMillis { it }
        position = (position + (frame - previousFrame).coerceAtMost(100L) / 1000f * 4f) % duration
        previousFrame = frame
      }
    }
  }

  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = style.name,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      FilledTonalIconButton(onClick = { position = 0f }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.RoundedFilled.Refresh, contentDescription = stringResource(R.string.player_restart_action))
      }
      FilledIconButton(onClick = { paused = !paused }, modifier = Modifier.size(48.dp)) {
        Icon(
          if (paused) Icons.RoundedFilled.PlayArrow else Icons.RoundedFilled.Pause,
          contentDescription = stringResource(if (paused) R.string.ui_play else R.string.audiobook_pause),
          modifier = Modifier.size(24.dp),
        )
      }
    }
    SeekbarContent(
      positionProvider = { position },
      committedPosition = position,
      duration = duration,
      chapters = if (showChapters) chapters else persistentListOf(),
      skipSegments = if (showSkipMarkers) skipMarkers else persistentListOf(),
      paused = paused,
      isPortrait = false,
      isUserInteracting = isUserInteracting,
      seekbarStyle = style,
      useWavySeekbar = useWavySeekbar,
      loopStart = if (showLoop) 44f else null,
      loopEnd = if (showLoop) 76f else null,
      bufferDuration = (position + 28f).coerceAtMost(duration),
      onUserInteractionChange = { isUserInteracting = it },
      onUserPositionChange = { position = it },
      onValueChange = { position = it },
      onValueChangeFinished = { position = it },
      scope = scope,
      animatedPosition = animatedPosition,
      interactionSource = interactionSource,
      showClipRange = false,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(56.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = Utils.prettyTime(position.toInt(), false),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.width(48.dp),
      )
      Text(
        text = if (showChapters) chapters.lastOrNull { it.start <= position }?.name.orEmpty() else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        minLines = 1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = Utils.prettyTime(duration.toInt(), false),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(48.dp),
      )
    }
    // The three preview features are a single Material Expressive connected group.
    // Keep their independent preview state: toggling a feature only affects this sample.
    val featureLabels = listOf(
      stringResource(R.string.media_info_tab_chapters),
      stringResource(R.string.pref_seekbar_preview_skip_markers),
      stringResource(R.string.btn_label_ab_loop),
    )
    val enabledFeatures = listOf(showChapters, showSkipMarkers, showLoop)
    val setFeature: (Int, Boolean) -> Unit = { index, checked ->
      when (index) {
        0 -> showChapters = checked
        1 -> showSkipMarkers = checked
        2 -> showLoop = checked
      }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      // At narrow widths or large accessibility font sizes, stack connected options
      // rather than clipping "Skip markers" or forcing a horizontal scrolling strip.
      val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f
      if (stacked) {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
        ) {
          featureLabels.forEachIndexed { index, label ->
            SeekbarPreviewFeatureToggle(
              label = label,
              checked = enabledFeatures[index],
              index = index,
              itemCount = featureLabels.size,
              layout = ConnectedLayout.Vertical,
              compact = false,
              modifier = Modifier.fillMaxWidth(),
              onCheckedChange = { setFeature(index, it) },
            )
          }
        }
      } else {
        val compact = maxWidth < 420.dp
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedControlShapeTokens.spacing),
        ) {
          featureLabels.forEachIndexed { index, label ->
            SeekbarPreviewFeatureToggle(
              label = label,
              checked = enabledFeatures[index],
              index = index,
              itemCount = featureLabels.size,
              layout = ConnectedLayout.Horizontal,
              compact = compact,
              modifier = Modifier.weight(1f),
              onCheckedChange = { setFeature(index, it) },
            )
          }
        }
      }
    }
  }
}


/**
 * Shared geometry for preview feature toggles: exposed outer corners are generously rounded,
 * while adjacent edges stay nearly square like Material 3 Expressive segmented controls.
 */
@Composable
private fun SeekbarPreviewFeatureToggle(
  label: String,
  checked: Boolean,
  index: Int,
  itemCount: Int,
  layout: ConnectedLayout,
  compact: Boolean,
  modifier: Modifier = Modifier,
  onCheckedChange: (Boolean) -> Unit,
) {
  val shape = rememberConnectedShape(
    index = index,
    itemCount = itemCount,
    layout = layout,
    tokens = AppConnectedControlShapeTokens,
  )
  val colors = MaterialTheme.colorScheme
  val isSelected = checked
  Surface(
    modifier = modifier.clip(shape).toggleable(
      value = isSelected,
      role = Role.Checkbox,
      onValueChange = onCheckedChange,
    ),
    shape = shape,
    color = if (isSelected) colors.secondaryContainer else colors.surfaceContainerLow,
    contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurface,
    border = BorderStroke(
      width = 1.dp,
      color = if (isSelected) colors.secondaryContainer else colors.outlineVariant,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = if (compact) 52.dp else 56.dp)
        .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isSelected) {
        Icon(
          imageVector = Icons.RoundedFilled.Check,
          contentDescription = null,
          modifier = Modifier.size(if (compact) 16.dp else 18.dp),
        )
        androidx.compose.foundation.layout.Spacer(
          modifier = Modifier.width(if (compact) 4.dp else 8.dp),
        )
      }
      Text(
        text = label,
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun VideoTimer(
  value: Float,
  isInverted: Boolean,
  modifier: Modifier = Modifier,
  textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  onClick: () -> Unit = {},
) {
  val seconds = value.toInt()
  val timeText = remember(seconds, isInverted) { Utils.prettyTime(seconds, isInverted) }
  val interactionSource = remember { MutableInteractionSource() }
  Text(
    modifier =
      modifier
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(),
          onClick = onClick,
        ).focusProperties { canFocus = false }
        .padding(horizontal = 4.dp)
        .stableNumericWidth(timeText)
        .wrapContentHeight(Alignment.CenterVertically),
    text = timeText,
    color = textColor,
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.labelSmall.withTabularDigits(),
  )
}

@Composable
fun StandardSeekbar(
  position: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean = false,
  isScrubbing: Boolean = false,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) = StandardSeekbar(
  positionProvider = { position },
  duration = duration,
  chapters = chapters,
  isPaused = isPaused,
  isScrubbing = isScrubbing,
  seekbarStyle = seekbarStyle,
  interactionSource = interactionSource,
  loopStart = loopStart,
  loopEnd = loopEnd,
  bufferDuration = bufferDuration,
  modifier = modifier,
)

@Composable
fun StandardSeekbar(
  positionProvider: () -> Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  isPaused: Boolean = false,
  isScrubbing: Boolean = false,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val (readAheadAlpha, emptyAlpha) = rememberSeekbarTrackAlphas()
  val isPressed by interactionSource.collectIsPressedAsState()
  val isDragged by interactionSource.collectIsDraggedAsState()
  val isThumbInteracting = isPressed || isDragged || isScrubbing

  // Animation state (same as SquigglySeekbar)
  var heightFraction by remember { mutableFloatStateOf(1f) }

  // Animate height fraction based on paused state and scrubbing state (same as SquigglySeekbar)
  LaunchedEffect(isPaused, isScrubbing) {
    val shouldFlatten = isPaused || isScrubbing
    val targetHeight = if (shouldFlatten) 0.7f else 1f // Slightly less dramatic for standard seekbar
    val startDelay = if (shouldFlatten) 0L else 60L

    if (startDelay > 0L) {
      kotlinx.coroutines.delay(startDelay)
    }

    val animator = Animatable(heightFraction)
    animator.animateTo(
      targetValue = targetHeight,
      animationSpec =
        spring(
          dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
          stiffness = AppMotion.Spatial.Expressive.stiffness,
        ),
    ) {
      heightFraction = value
    }
  }

  val isThick = seekbarStyle == SeekbarStyle.Thick
  val baseTrackHeight = if (isThick) 16.dp else 8.dp
  val chapterStarts = remember(chapters) { chapters.map(Segment::start) }
  val thumbWidth by animateDpAsState(
    targetValue =
      when {
        isThick && isThumbInteracting -> 4.dp
        isThumbInteracting -> 3.dp
        else -> 6.dp
      },
    animationSpec = spring(stiffness = 900f, dampingRatio = 0.9f),
    label = "standard_seekbar_thumb_width",
  )
  val thumbHeight = if (isThick) 16.dp else 24.dp
  val chapterGapHalfDp by animateDpAsState(
    targetValue = if (isThumbInteracting) 2.dp else 1.5.dp,
    animationSpec =
      spring(
        dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
        stiffness = AppMotion.Spatial.Standard.stiffness,
      ),
    label = "standard_chapter_gap",
  )

  Canvas(modifier = modifier.fillMaxWidth().height(thumbHeight)) {
    val currentPosition = positionProvider()
    val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
    val playedFraction =
      if (safeDuration > 0f) {
        currentPosition.takeIf { it.isFinite() }?.div(safeDuration)?.coerceIn(0f, 1f) ?: 0f
      } else {
        0f
      }
    val playedPx = size.width * playedFraction
    val bufferPx = bufferedEndPx(bufferDuration, safeDuration, size.width, playedPx)
    val trackHeight = baseTrackHeight.toPx() * heightFraction
    val centerY = size.height / 2f
    val thumbWidthPx = thumbWidth.toPx()
    val thumbHeightPx = thumbHeight.toPx()
    val thumbGapHalf = (thumbWidthPx + if (isThick) 8.dp.toPx() else 10.dp.toPx()) / 2f
    val thumbGapStart = (playedPx - thumbGapHalf).coerceIn(0f, size.width)
    val thumbGapEnd = (playedPx + thumbGapHalf).coerceIn(0f, size.width)
    val segments =
      seekbarTrackSegments(
        chapterStarts = chapterStarts,
        duration = safeDuration,
        trackWidth = size.width,
        chapterGapHalf = chapterGapHalfDp.toPx(),
        extraGaps = listOf(thumbGapStart to thumbGapEnd),
      )

    drawSeekbarTrackSegments(
      segments = segments,
      playedPx = playedPx,
      bufferedPx = bufferPx,
      centerY = centerY,
      trackHeight = trackHeight,
      playedColor = primaryColor,
      bufferedColor = primaryColor.copy(alpha = readAheadAlpha),
      unplayedColor = primaryColor.copy(alpha = emptyAlpha),
    )

    if ((loopStart != null || loopEnd != null) && safeDuration > 0f) {
      val loopColor = Color(0xFFFFB300)
      val markerWidth = 2.dp.toPx()
      val trackTop = centerY - trackHeight / 2f
      val trackBottom = centerY + trackHeight / 2f

      loopStart?.let { start ->
        val startPx = (start / safeDuration).coerceIn(0f, 1f) * size.width
        drawLine(loopColor, Offset(startPx, trackTop), Offset(startPx, trackBottom), markerWidth)
      }
      loopEnd?.let { end ->
        val endPx = (end / safeDuration).coerceIn(0f, 1f) * size.width
        drawLine(loopColor, Offset(endPx, trackTop), Offset(endPx, trackBottom), markerWidth)
      }
      if (loopStart != null && loopEnd != null) {
        val minPx = (minOf(loopStart, loopEnd) / safeDuration).coerceIn(0f, 1f) * size.width
        val maxPx = (maxOf(loopStart, loopEnd) / safeDuration).coerceIn(0f, 1f) * size.width
        drawRect(
          color = loopColor.copy(alpha = 0.3f),
          topLeft = Offset(minPx, trackTop),
          size = Size(maxPx - minPx, trackHeight),
        )
      }
    }

    val thumbLeft =
      (playedPx - thumbWidthPx / 2f)
        .coerceIn(0f, (size.width - thumbWidthPx).coerceAtLeast(0f))
    drawRoundRect(
      color = primaryColor,
      topLeft = Offset(thumbLeft, centerY - thumbHeightPx / 2f),
      size = Size(thumbWidthPx, thumbHeightPx),
      cornerRadius = CornerRadius(thumbWidthPx / 2f),
    )
  }
}

@Preview(name = "Seekbar - Wavy (default)")
@Composable
private fun PreviewSeekBarWavy() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
    remaining= 150f,
    onValueChange = {},
    onValueChangeFinished = {},
    timersInverted = Pair(false, true),
    positionTimerOnClick = {},
    durationTimerOnCLick = {},
    chapters = persistentListOf(),
    skipSegments = persistentListOf(),
    paused = false,
    seekbarStyle = SeekbarStyle.Wavy,
  )
}

@Preview(name = "Seekbar - Slim (normal)")
@Composable
private fun PreviewSeekBarSlim() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
    remaining= 150f,
    onValueChange = {},
    onValueChangeFinished = {},
    timersInverted = Pair(false, true),
    positionTimerOnClick = {},
    durationTimerOnCLick = {},
    chapters = persistentListOf(),
    skipSegments = persistentListOf(),
    paused = false,
    seekbarStyle = SeekbarStyle.Slim,
  )
}

@Preview(name = "Seekbar - Slim (scrubbing)")
@Composable
private fun PreviewSeekBarSlimScrubbing() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
    remaining= 150f,
    onValueChange = {},
    onValueChangeFinished = {},
    timersInverted = Pair(false, true),
    positionTimerOnClick = {},
    durationTimerOnCLick = {},
    chapters = persistentListOf(),
    skipSegments = persistentListOf(),
    paused = false,
    seekbarStyle = SeekbarStyle.Slim,
  )
}

@Preview(name = "Seekbar Style Previews")
@Composable
private fun PreviewSeekbarStyles() {
  androidx.compose.foundation.layout.Column(
    modifier = Modifier.padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    SeekbarStyle.entries.forEach { style ->
      androidx.compose.material3.Text(style.name)
      SeekbarStylePreview(style = style)
    }
  }
}
