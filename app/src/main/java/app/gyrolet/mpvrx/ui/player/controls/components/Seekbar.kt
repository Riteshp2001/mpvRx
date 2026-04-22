package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.ui.player.SkipSegment
import app.gyrolet.mpvrx.ui.player.SkipSegmentType
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SeekbarWithTimers(
  position: Float,
  duration: Float,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnCLick: () -> Unit,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Wavy,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  isPortrait: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val clickEvent = LocalPlayerButtonsClickEvent.current
  var isUserInteracting by remember { mutableStateOf(false) }
  var userPosition by remember { mutableFloatStateOf(position) }

  // Animated position for smooth transitions
  val animatedPosition = remember { Animatable(position) }
  val scope = rememberCoroutineScope()

  LaunchedEffect(position, isUserInteracting) {
    if (!isUserInteracting && position != animatedPosition.value) {
      scope.launch {
        animatedPosition.animateTo(
          targetValue = position,
          animationSpec =
            tween(
              durationMillis = 200,
              easing = LinearEasing,
            ),
        )
      }
    }
  }

  if (isPortrait) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.large),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      SeekbarContent(
        position = if (isUserInteracting) userPosition else animatedPosition.value,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        loopStart = loopStart,
        loopEnd = loopEnd,
        onUserInteractionChange = { isUserInteracting = it },
        onUserPositionChange = { userPosition = it },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        scope = scope,
        animatedPosition = animatedPosition,
        modifier = Modifier.fillMaxWidth().height(44.dp) // Taller for visibility
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        VideoTimer(
          value = if (isUserInteracting) userPosition else position,
          isInverted = timersInverted.first,
          onClick = {
            clickEvent()
            positionTimerOnClick()
          }
        )

        VideoTimer(
          value = if (timersInverted.second) position - duration else duration,
          isInverted = timersInverted.second,
          onClick = {
            clickEvent()
            durationTimerOnCLick()
          }
        )
      }
    }
  } else {
    Row(
      modifier = modifier.height(48.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      VideoTimer(
        value = if (isUserInteracting) userPosition else position,
        isInverted = timersInverted.first,
        onClick = {
          clickEvent()
          positionTimerOnClick()
        },
        modifier = Modifier.width(60.dp),
      )

      SeekbarContent(
        position = if (isUserInteracting) userPosition else animatedPosition.value,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        loopStart = loopStart,
        loopEnd = loopEnd,
        onUserInteractionChange = { isUserInteracting = it },
        onUserPositionChange = { userPosition = it },
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        scope = scope,
        animatedPosition = animatedPosition,
        modifier = Modifier.weight(1f).height(48.dp)
      )

      VideoTimer(
        value = if (timersInverted.second) position - duration else duration,
        isInverted = timersInverted.second,
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
  position: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  isPortrait: Boolean,
  isUserInteracting: Boolean,
  seekbarStyle: SeekbarStyle,
  loopStart: Float?,
  loopEnd: Float?,
  onUserInteractionChange: (Boolean) -> Unit,
  onUserPositionChange: (Float) -> Unit,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: () -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
  animatedPosition: Animatable<Float, *>,
  modifier: Modifier = Modifier
) {
  val touchAreaHeight = if (isPortrait) 64.dp else 52.dp
  val overlayTrackHeight =
    when (seekbarStyle) {
      SeekbarStyle.Slim ->
        when {
          isUserInteracting -> 15.dp
          paused -> 6.dp
          else -> 8.dp
        }
      SeekbarStyle.Thick -> 16.dp
      SeekbarStyle.Standard -> 8.dp
      SeekbarStyle.Wavy -> 8.dp
    }

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    // Invisible expanded touch area
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(touchAreaHeight)
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = { offset ->
              val newPosition = (offset.x / size.width) * duration
              onUserInteractionChange(true)
              val targetPos = newPosition.coerceIn(0f, duration)
              onUserPositionChange(targetPos)
              onValueChange(targetPos)
              scope.launch {
                animatedPosition.snapTo(targetPos)
                onUserInteractionChange(false)
                onValueChangeFinished()
              }
            }
          )
        }
        .pointerInput(Unit) {
          detectDragGestures(
            onDragStart = {
              onUserInteractionChange(true)
            },
            onDragEnd = {
              scope.launch {
                delay(50)
                onUserInteractionChange(false)
                onValueChangeFinished()
              }
            },
            onDragCancel = {
              scope.launch {
                delay(50)
                onUserInteractionChange(false)
                onValueChangeFinished()
              }
            },
          ) { change, _ ->
            change.consume()
            val newPosition = (change.position.x / size.width) * duration
            val targetPos = newPosition.coerceIn(0f, duration)
            onUserPositionChange(targetPos)
            onValueChange(targetPos)
          }
        }
    )

    // Visual seekbar (smaller, centered)
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(32.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (seekbarStyle) {
        SeekbarStyle.Standard -> {
          StandardSeekbar(
            position = position,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isUserInteracting,
            seekbarStyle = SeekbarStyle.Standard,
            onSeek = { newPosition ->
              onUserInteractionChange(true)
              onUserPositionChange(newPosition)
              onValueChange(newPosition)
            },
            onSeekFinished = {
              scope.launch { animatedPosition.snapTo(position) }
              onUserInteractionChange(false)
              onValueChangeFinished()
            },
            loopStart = loopStart,
            loopEnd = loopEnd,
          )
        }
        SeekbarStyle.Wavy -> {
          SquigglySeekbar(
            position = position,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isUserInteracting,
            useWavySeekbar = true,
            seekbarStyle = SeekbarStyle.Wavy,
            onSeek = { }, // Touch handled by parent
            onSeekFinished = { }, // Touch handled by parent
            loopStart = loopStart,
            loopEnd = loopEnd,
          )
        }
        SeekbarStyle.Thick -> {
          StandardSeekbar(
            position = position,
            duration = duration,
            chapters = chapters,
            isPaused = paused,
            isScrubbing = isUserInteracting,
            seekbarStyle = SeekbarStyle.Thick,
            onSeek = { newPosition ->
              onUserInteractionChange(true)
              onUserPositionChange(newPosition)
              onValueChange(newPosition)
            },
            onSeekFinished = {
              scope.launch { animatedPosition.snapTo(position) }
              onUserInteractionChange(false)
              onValueChangeFinished()
            },
            loopStart = loopStart,
            loopEnd = loopEnd,
          )
        }
        SeekbarStyle.Slim -> {
          SlimSeekbar(
            position    = position,
            duration    = duration,
            chapters    = chapters,
            isPaused    = paused,
            isScrubbing = isUserInteracting,
            loopStart   = loopStart,
            loopEnd     = loopEnd,
          )
        }
      }
    }

    Canvas(
      modifier =
        Modifier
          .fillMaxWidth()
          .height(overlayTrackHeight)
          .align(Alignment.Center),
    ) {
      if (duration > 0f && skipSegments.isNotEmpty()) {
        val trackHeight = size.height
        skipSegments.forEach { segment ->
          val startX = ((segment.startSeconds / duration) * size.width).toFloat().coerceIn(0f, size.width)
          val endX = ((segment.endSeconds / duration) * size.width).toFloat().coerceIn(0f, size.width)
          if (endX - startX < 1f) return@forEach
          val color = segment.type.accentColor
          val fillColor =
            Color(
              red = color.red * 0.74f,
              green = color.green * 0.74f,
              blue = color.blue * 0.74f,
              alpha = 0.42f,
            )
          val edgeColor =
            Color(
              red = color.red * 0.58f,
              green = color.green * 0.58f,
              blue = color.blue * 0.58f,
              alpha = 1f,
            )
          drawRect(
            color = fillColor,
            topLeft = Offset(startX, 0f),
            size = Size(endX - startX, trackHeight),
          )
          drawLine(
            color = edgeColor,
            start = Offset(startX, 0f),
            end = Offset(startX, trackHeight),
            strokeWidth = 2.dp.toPx(),
          )
          drawLine(
            color = edgeColor,
            start = Offset(endX, 0f),
            end = Offset(endX, trackHeight),
            strokeWidth = 2.dp.toPx(),
          )
        }
      }
    }
  }
}

@Composable
private fun SquigglySeekbar(
  position: Float,
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
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

  val isInteracting = isScrubbing
  val thumbVisibility by animateFloatAsState(
    targetValue = if (isInteracting) 0f else 1f,
    animationSpec = tween(durationMillis = 180, easing = LinearEasing),
    label = "wavy_seekbar_thumb_visibility",
  )

  // Animation state
  var phaseOffset by remember { mutableFloatStateOf(0f) }
  var heightFraction by remember { mutableFloatStateOf(1f) }

  val scope = rememberCoroutineScope()

  // Wave parameters
  val waveLength = 80f
  val lineAmplitude = if (useWavySeekbar) 6f else 0f
  val phaseSpeed = 10f // px per second
  val transitionPeriods = 1.5f
  val minWaveEndpoint = 0f
  val matchedWaveEndpoint = 1f
  val transitionEnabled = true

  // Animate height fraction based on paused state and scrubbing state
  LaunchedEffect(isPaused, isScrubbing, useWavySeekbar) {
    if (!useWavySeekbar) {
      heightFraction = 0f
      return@LaunchedEffect
    }

    scope.launch {
      val shouldFlatten = isPaused || isScrubbing
      val targetHeight = if (shouldFlatten) 0f else 1f
      val duration = if (shouldFlatten) 550 else 800
      val startDelay = if (shouldFlatten) 0L else 60L

      kotlinx.coroutines.delay(startDelay)

      val animator = Animatable(heightFraction)
      animator.animateTo(
        targetValue = targetHeight,
        animationSpec =
          tween(
            durationMillis = duration,
            easing = LinearEasing,
          ),
      ) {
        heightFraction = value
      }
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
    val strokeWidth = 5.dp.toPx()
    val progress = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
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
    val path = Path()
    val waveStart = -phaseOffset - waveLength / 2f
    val waveEnd = if (transitionEnabled) totalWidth else waveProgressPx

    path.moveTo(waveStart, centerY)

    var currentX = waveStart
    var waveSign = 1f
    var currentAmp = computeAmplitude(currentX, waveSign)
    val dist = waveLength / 2f

    while (currentX < waveEnd) {
      waveSign = -waveSign
      val nextX = currentX + dist
      val midX = currentX + dist / 2f
      val nextAmp = computeAmplitude(nextX, waveSign)

      path.cubicTo(
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

    fun drawPathWithGaps(
      startX: Float,
      endX: Float,
      color: Color,
    ) {
      if (endX <= startX) return
      if (duration <= 0f) {
        clipRect(
          left = startX,
          top = centerY - clipTop,
          right = endX,
          bottom = centerY + clipTop,
        ) {
          drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
          )
        }
        return
      }
      val gaps =
        chapters
          .map { (it.start / duration).coerceIn(0f, 1f) * totalWidth }
          .filter { it in startX..endX }
          .sorted()
          .map { x -> (x - gapHalf).coerceAtLeast(startX) to (x + gapHalf).coerceAtMost(endX) }

      var segmentStart = startX
      for ((gapStart, gapEnd) in gaps) {
        if (gapStart > segmentStart) {
          clipRect(
            left = segmentStart,
            top = centerY - clipTop,
            right = gapStart,
            bottom = centerY + clipTop,
          ) {
            drawPath(
              path = path,
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
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
          )
        }
      }
    }

    // Played segment
    drawPathWithGaps(0f, totalProgressPx, primaryColor)

    if (transitionEnabled) {
      val disabledAlpha = 77f / 255f
      drawPathWithGaps(totalProgressPx, totalWidth, primaryColor.copy(alpha = disabledAlpha))
    } else {
      drawLine(
        color = surfaceVariant.copy(alpha = 0.4f),
        start = Offset(totalProgressPx, centerY),
        end = Offset(totalWidth, centerY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
      )
    }

    // Draw round cap
    val startAmp = kotlin.math.cos(kotlin.math.abs(waveStart) / waveLength * (2f * kotlin.math.PI.toFloat()))
    drawCircle(
      color = primaryColor,
      radius = strokeWidth / 2f,
      center = Offset(0f, centerY + startAmp * lineAmplitude * heightFraction),
    )

    // Vertical Bar Thumb
    val barHalfHeight = (lineAmplitude + strokeWidth) * thumbVisibility
    val barWidth = 5.dp.toPx()

    if (barHalfHeight > 0.5f && thumbVisibility > 0.05f) {
        drawLine(
          color = primaryColor.copy(alpha = thumbVisibility),
          start = Offset(totalProgressPx, centerY - barHalfHeight),
          end = Offset(totalProgressPx, centerY + barHalfHeight),
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
    position: Float,
    duration: Float,
    chapters: ImmutableList<Segment>,
    isPaused: Boolean,
    isScrubbing: Boolean,
    loopStart: Float? = null,
    loopEnd: Float? = null,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    // Height breathes like other seekbars:
    //   paused  → 7dp  (relaxed/thin)
    //   playing → 10dp (normal)
    //   pressed → 20dp (expanded)
    val trackHeight by animateDpAsState(
        targetValue = when {
            isScrubbing -> 15.dp
            isPaused    -> 6.dp
            else        -> 8.dp
        },
        animationSpec = when {
            isScrubbing -> spring(stiffness = 500f, dampingRatio = 0.75f)
            else        -> tween(durationMillis = 600, easing = LinearEasing)
        },
        label = "slim_seekbar_height",
    )

    // Chapter gap widens when pressed so segments look clearly distinct
    val chapterGapHalfDp by animateDpAsState(
        targetValue = if (isScrubbing) 2.dp else 1.5.dp,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "slim_chapter_gap",
    )

    // Colors stay constant — only height changes on press
    val playedColor   = primaryColor
    val unplayedColor = primaryColor.copy(alpha = 0.3f)

    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        val progress      = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
        val totalWidth    = size.width
        val playedPx      = totalWidth * progress
        val centerY       = size.height / 2f
        val height        = trackHeight.toPx()
        val outerRadius   = height / 2f      // full pill for track ends
        val innerRadius   = 2.dp.toPx()      // slight rounding for inner chapter edges
        val gapHalf       = chapterGapHalfDp.toPx()

        // Chapter split positions
        val chapterXs = if (duration > 0f) {
            chapters
                .map { (it.start / duration).coerceIn(0f, 1f) * totalWidth }
                .filter { it > gapHalf && it < totalWidth - gapHalf }
                .sorted()
        } else emptyList()

        // Build segment list from chapter gaps
        val segments = mutableListOf<Pair<Float, Float>>()
        var segCursor = 0f
        for (chX in chapterXs) {
            val gS = chX - gapHalf
            val gE = chX + gapHalf
            if (gS > segCursor) segments.add(segCursor to gS)
            segCursor = gE
        }
        if (segCursor < totalWidth) segments.add(segCursor to totalWidth)

        // Draw a rect segment with independent left/right corner radii
        fun seg(startX: Float, endX: Float, color: Color, leftR: Float, rightR: Float) {
            if (endX - startX < 0.5f) return
            val path = Path()
            path.addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left   = startX,
                    top    = centerY - outerRadius,
                    right  = endX,
                    bottom = centerY + outerRadius,
                    topLeftCornerRadius     = CornerRadius(leftR),
                    bottomLeftCornerRadius  = CornerRadius(leftR),
                    topRightCornerRadius    = CornerRadius(rightR),
                    bottomRightCornerRadius = CornerRadius(rightR),
                )
            )
            drawPath(path, color)
        }

        for ((sS, sE) in segments) {
            // Outer track ends = full pill, inner chapter edges = slight rounding
            val lR = if (sS <= 0.5f)              outerRadius else innerRadius
            val rR = if (sE >= totalWidth - 0.5f) outerRadius else innerRadius

            when {
                sE <= playedPx -> seg(sS, sE, playedColor,   lR, rR)
                sS >= playedPx -> seg(sS, sE, unplayedColor, lR, rR)
                else -> {
                    // Progress boundary is inside this segment — seamless colour join (0 inner radius)
                    seg(sS,      playedPx, playedColor,   lR, 0f)
                    seg(playedPx, sE,      unplayedColor, 0f, rR)
                }
            }
        }

        // A-B loop markers
        if (loopStart != null || loopEnd != null) {
            val loopColor = Color(0xFFFFB300)
            val markerW   = 2.dp.toPx()
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
                    color   = loopColor.copy(alpha = 0.2f),
                    topLeft = Offset(minPx, centerY - outerRadius),
                    size    = Size(maxPx - minPx, height),
                )
            }
        }
    }
}

@Composable
fun SeekbarStylePreview(
    style: SeekbarStyle,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val previewProgress = 0.38f

    Canvas(modifier = modifier.fillMaxWidth().height(36.dp)) {
        val playedPx = size.width * previewProgress
        val centerY = size.height / 2f

        when (style) {
            SeekbarStyle.Slim -> {
                // Preview at normal (playing) height: 10dp pill bar
                val height = 10.dp.toPx()
                val radius = height / 2f
                val innerR  = 2.dp.toPx()
                // Unplayed background
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.3f),
                    topLeft = Offset(0f, centerY - radius),
                    size = Size(size.width, height),
                    cornerRadius = CornerRadius(radius),
                )
                // Played — right end is 0 radius (seamless join with unplayed area)
                if (playedPx > 0f) {
                    val path = Path()
                    path.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 0f, top = centerY - radius,
                            right = playedPx, bottom = centerY + radius,
                            topLeftCornerRadius     = CornerRadius(radius),
                            bottomLeftCornerRadius  = CornerRadius(radius),
                            topRightCornerRadius    = CornerRadius(if (playedPx >= size.width - 0.5f) radius else 0f),
                            bottomRightCornerRadius = CornerRadius(if (playedPx >= size.width - 0.5f) radius else 0f),
                        )
                    )
                    drawPath(path, primaryColor)
                }
            }
            SeekbarStyle.Standard -> {
                val height = 8.dp.toPx()
                val radius = height / 2f
                val thumbW = 3.dp.toPx()
                val gapHalf = (thumbW + 10.dp.toPx()) / 2f
                val thumbStart = (playedPx - gapHalf).coerceIn(0f, size.width)
                val thumbEnd = (playedPx + gapHalf).coerceIn(0f, size.width)
                // Unplayed
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.3f),
                    topLeft = Offset(thumbEnd, centerY - radius),
                    size = Size((size.width - thumbEnd).coerceAtLeast(0f), height),
                    cornerRadius = CornerRadius(radius),
                )
                // Played
                if (thumbStart > 0f) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(0f, centerY - radius),
                        size = Size(thumbStart, height),
                        cornerRadius = CornerRadius(radius),
                    )
                }
                // Thumb (vertical bar)
                val thumbHalfH = 12.dp.toPx()
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(playedPx - thumbW / 2f, centerY - thumbHalfH),
                    size = Size(thumbW, thumbHalfH * 2),
                    cornerRadius = CornerRadius(thumbW / 2f),
                )
            }
            SeekbarStyle.Wavy -> {
                val strokeWidth = 5.dp.toPx()
                drawLine(
                    color = primaryColor,
                    start = Offset(0f, centerY),
                    end = Offset(playedPx, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = primaryColor.copy(alpha = 0.3f),
                    start = Offset(playedPx, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                // Thumb bar
                val barHalfH = strokeWidth / 2f + 3.dp.toPx()
                drawLine(
                    color = primaryColor,
                    start = Offset(playedPx, centerY - barHalfH),
                    end = Offset(playedPx, centerY + barHalfH),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            SeekbarStyle.Thick -> {
                val height = 16.dp.toPx()
                val radius = height / 2f
                val thumbW = 4.dp.toPx()
                val gapHalf = (thumbW + 18.dp.toPx()) / 2f
                val thumbStart = (playedPx - gapHalf).coerceIn(0f, size.width)
                val thumbEnd = (playedPx + gapHalf).coerceIn(0f, size.width)
                // Unplayed
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.3f),
                    topLeft = Offset(thumbEnd, centerY - radius),
                    size = Size((size.width - thumbEnd).coerceAtLeast(0f), height),
                    cornerRadius = CornerRadius(radius),
                )
                // Played
                if (thumbStart > 0f) {
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(0f, centerY - radius),
                        size = Size(thumbStart, height),
                        cornerRadius = CornerRadius(radius),
                    )
                }
                // Thumb block
                drawRoundRect(
                    color = primaryColor,
                    topLeft = Offset(playedPx - thumbW / 2f, centerY - radius),
                    size = Size(thumbW, height),
                    cornerRadius = CornerRadius(thumbW / 2f),
                )
            }
        }
    }
}

@Composable
fun VideoTimer(
  value: Float,
  isInverted: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  val interactionSource = remember { MutableInteractionSource() }
  Text(
    modifier =
      modifier
        .clickable(
          interactionSource = interactionSource,
          indication = ripple(),
          onClick = onClick,
        )
        .padding(horizontal = 4.dp)
        .wrapContentHeight(Alignment.CenterVertically),
    text = Utils.prettyTime(value.toInt(), isInverted),
    color = Color.White,
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.labelSmall
  )
}

@Composable
fun StandardSeekbar(
    position: Float,
    duration: Float,
    chapters: ImmutableList<Segment>,
    isPaused: Boolean = false,
    isScrubbing: Boolean = false,
    useWavySeekbar: Boolean = false,
    seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    loopStart: Float? = null,
    loopEnd: Float? = null,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isThumbInteracting = isPressed || isDragged || isScrubbing
    
    // Animation state (same as SquigglySeekbar)
    var heightFraction by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    
    // Animate height fraction based on paused state and scrubbing state (same as SquigglySeekbar)
    LaunchedEffect(isPaused, isScrubbing) {
        scope.launch {
            val shouldFlatten = isPaused || isScrubbing
            val targetHeight = if (shouldFlatten) 0.7f else 1f // Slightly less dramatic for standard seekbar
            val animationDuration = if (shouldFlatten) 550 else 800
            val startDelay = if (shouldFlatten) 0L else 60L

            kotlinx.coroutines.delay(startDelay)

            val animator = Animatable(heightFraction)
            animator.animateTo(
                targetValue = targetHeight,
                animationSpec = tween(
                    durationMillis = animationDuration,
                    easing = LinearEasing,
                ),
            ) {
                heightFraction = value
            }
        }
    }
    
    val isThick = seekbarStyle == SeekbarStyle.Thick
    val baseTrackHeight = if (isThick) 16.dp else 8.dp
    val trackHeightDp = baseTrackHeight * heightFraction // Apply animation to track height
    val thumbWidth by animateDpAsState(
        targetValue = when {
            isThick && isThumbInteracting -> 4.dp
            isThumbInteracting -> 3.dp
            else -> 6.dp
        },
        animationSpec = spring(stiffness = 900f, dampingRatio = 0.9f),
        label = "standard_seekbar_thumb_width"
    )
    val thumbHeight = if (isThick) 16.dp else 24.dp
    val thumbShape = if (isThick) RoundedCornerShape(thumbWidth / 2) else CircleShape

    Slider(
        value = position,
        onValueChange = onSeek,
        onValueChangeFinished = onSeekFinished,
        valueRange = 0f..duration.coerceAtLeast(0.1f),
        modifier = modifier.fillMaxWidth(),
        interactionSource = interactionSource,
        track = { sliderState ->
            val disabledAlpha = 0.3f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeightDp),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f

                val playedFraction = ((sliderState.value - min) / range).coerceIn(0f, 1f)

                val playedPx = size.width * playedFraction
                val trackHeight = size.height
                
                // Radius for the outer ends of the seekbar
                val outerRadius = trackHeight / 2f
                
                // MODIFIED: For Thick style, inner corners now match the outer rounding
                val innerRadius = if (isThick) outerRadius else 2.dp.toPx()
                
                val thumbTrackGapSize = when {
                    else -> thumbWidth.toPx() + if (isThick) 8.dp.toPx() else 10.dp.toPx()
                }
                val gapHalf = thumbTrackGapSize / 2f
                val chapterGapHalf = 1.dp.toPx()
                
                val thumbGapStart = (playedPx - gapHalf).coerceIn(0f, size.width)
                val thumbGapEnd = (playedPx + gapHalf).coerceIn(0f, size.width)
                
                val chapterGaps = chapters
                    .map { (it.start / duration).coerceIn(0f, 1f) * size.width }
                    .filter { it > 0f && it < size.width }
                    .map { x -> (x - chapterGapHalf) to (x + chapterGapHalf) }
                
                fun drawSegment(startX: Float, endX: Float, color: Color) {
                    if (endX - startX < 0.5f) return
                    
                    val path = Path()
                    val isOuterLeft = startX <= 0.5f
                    val isInnerLeft = kotlin.math.abs(startX - thumbGapEnd) < 0.5f
                    
                    val cornerRadiusLeft = when {
                        isOuterLeft -> androidx.compose.ui.geometry.CornerRadius(outerRadius)
                        isInnerLeft -> androidx.compose.ui.geometry.CornerRadius(innerRadius)
                        else -> androidx.compose.ui.geometry.CornerRadius.Zero
                    }

                    val isOuterRight = endX >= size.width - 0.5f
                    val isInnerRight = kotlin.math.abs(endX - thumbGapStart) < 0.5f

                    val cornerRadiusRight = when {
                        isOuterRight -> androidx.compose.ui.geometry.CornerRadius(outerRadius)
                        isInnerRight -> androidx.compose.ui.geometry.CornerRadius(innerRadius)
                        else -> androidx.compose.ui.geometry.CornerRadius.Zero
                    }
                    
                    path.addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = startX,
                            top = 0f,
                            right = endX,
                            bottom = trackHeight,
                            topLeftCornerRadius = cornerRadiusLeft,
                            bottomLeftCornerRadius = cornerRadiusLeft,
                            topRightCornerRadius = cornerRadiusRight,
                            bottomRightCornerRadius = cornerRadiusRight
                        )
                    )
                    drawPath(path, color)
                }
                
                fun drawRangeWithGaps(
                    rangeStart: Float, 
                    rangeEnd: Float, 
                    gaps: List<Pair<Float, Float>>, 
                    color: Color
                ) {
                    if (rangeEnd <= rangeStart) return
                    val relevantGaps = gaps
                        .filter { (gStart, gEnd) -> gEnd > rangeStart && gStart < rangeEnd }
                        .sortedBy { it.first }
                    
                    var currentPos = rangeStart
                    for ((gStart, gEnd) in relevantGaps) {
                        val segmentEnd = gStart.coerceAtMost(rangeEnd)
                        if (segmentEnd > currentPos) {
                            drawSegment(currentPos, segmentEnd, color)
                        }
                        currentPos = gEnd.coerceAtLeast(currentPos)
                    }
                    if (currentPos < rangeEnd) {
                        drawSegment(currentPos, rangeEnd, color)
                    }
                }
                
                // 1. Unplayed Background
                drawRangeWithGaps(thumbGapEnd, size.width, chapterGaps, primaryColor.copy(alpha = disabledAlpha))
                
                // 2. Played
                if (thumbGapStart > 0) {
                    drawRangeWithGaps(0f, thumbGapStart, chapterGaps, primaryColor)
                }

                // 3. A-B Loop Indicators
                if (loopStart != null || loopEnd != null) {
                    val loopColor = Color(0xFFFFB300) // Amber/Gold color for loop
                    val markerWidth = 2.dp.toPx()
                    
                    // Draw loop start marker
                    if (loopStart != null) {
                        val startPx = (loopStart / duration).coerceIn(0f, 1f) * size.width
                        drawLine(
                            color = loopColor,
                            start = Offset(startPx, 0f),
                            end = Offset(startPx, size.height),
                            strokeWidth = markerWidth
                        )
                    }

                    // Draw loop end marker
                    if (loopEnd != null) {
                        val endPx = (loopEnd / duration).coerceIn(0f, 1f) * size.width
                        drawLine(
                            color = loopColor,
                            start = Offset(endPx, 0f),
                            end = Offset(endPx, size.height),
                            strokeWidth = markerWidth
                        )
                    }

                    // Draw connected segment if both are set
                    if (loopStart != null && loopEnd != null) {
                        val minPx = (minOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * size.width
                        val maxPx = (maxOf(loopStart, loopEnd) / duration).coerceIn(0f, 1f) * size.width
                        
                        // Draw a semi-transparent overlay between A and B
                        drawRect(
                            color = loopColor.copy(alpha = 0.3f),
                            topLeft = Offset(minPx, 0f),
                            size = Size(maxPx - minPx, size.height)
                        )
                    }
                }
            }
        },
            thumb = {
                Box(
                    modifier = Modifier
                        .width(thumbWidth)
                        .height(thumbHeight)
                        .background(primaryColor, thumbShape)
                )
            }
        )
    }

@Preview(name = "Seekbar - Wavy (default)")
@Composable
private fun PreviewSeekBarWavy() {
  SeekbarWithTimers(
    position = 30f,
    duration = 180f,
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
