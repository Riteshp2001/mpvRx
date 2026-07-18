package app.gyrolet.mpvrx.ui.player.controls.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.preferences.SeekbarStyle
import app.gyrolet.mpvrx.ui.player.SkipSegment
import app.gyrolet.mpvrx.ui.player.SkipSegmentType
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.spacing
import dev.vivvvek.seeker.Segment
import dev.vivvvek.seeker.Seeker
import dev.vivvvek.seeker.SeekerDefaults
import dev.vivvvek.seeker.rememberSeekerState
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
  val safeBufferPosition = bufferPosition
    ?.takeIf { it.isFinite() && it > 0f }
    ?: safePlayedPosition
  return safeBufferPosition.coerceIn(safePlayedPosition, duration)
}

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
  val outerRadius = trackHeight / 2f
  val innerRadius = 2.dp.toPx()

  fun drawPiece(
    startX: Float,
    endX: Float,
    color: Color,
    leftRadius: Float,
    rightRadius: Float,
  ) {
    if (endX - startX < 0.5f) return
    val path = Path().apply {
      addRoundRect(
        androidx.compose.ui.geometry.RoundRect(
          left = startX,
          top = centerY - outerRadius,
          right = endX,
          bottom = centerY + outerRadius,
          topLeftCornerRadius = CornerRadius(leftRadius),
          bottomLeftCornerRadius = CornerRadius(leftRadius),
          topRightCornerRadius = CornerRadius(rightRadius),
          bottomRightCornerRadius = CornerRadius(rightRadius),
        )
      )
    }
    drawPath(path, color)
  }

  segments.forEach { segment ->
    val leftRadius = if (segment.start <= 0.5f) outerRadius else innerRadius
    val rightRadius = if (segment.end >= size.width - 0.5f) outerRadius else innerRadius

    when {
      segment.end <= playedPx ->
        drawPiece(segment.start, segment.end, playedColor, leftRadius, rightRadius)
      segment.start >= bufferedPx ->
        drawPiece(segment.start, segment.end, unplayedColor, leftRadius, rightRadius)
      segment.end <= bufferedPx -> {
        if (segment.start >= playedPx) {
          drawPiece(segment.start, segment.end, bufferedColor, leftRadius, rightRadius)
        } else {
          drawPiece(segment.start, playedPx, playedColor, leftRadius, 0f)
          drawPiece(playedPx, segment.end, bufferedColor, 0f, rightRadius)
        }
      }
      segment.start < playedPx && segment.end > bufferedPx -> {
        drawPiece(segment.start, playedPx, playedColor, leftRadius, 0f)
        drawPiece(playedPx, bufferedPx, bufferedColor, 0f, 0f)
        drawPiece(bufferedPx, segment.end, unplayedColor, 0f, rightRadius)
      }
      segment.start < bufferedPx && segment.end > bufferedPx -> {
        drawPiece(segment.start, bufferedPx, bufferedColor, leftRadius, 0f)
        drawPiece(bufferedPx, segment.end, unplayedColor, 0f, rightRadius)
      }
      else -> drawPiece(segment.start, segment.end, unplayedColor, leftRadius, rightRadius)
    }
  }
}

private fun normalizeSeekerSegments(
  chapters: List<Segment>,
  duration: Float,
): List<Segment> {
  if (duration <= 0f) return emptyList()

  val validChapters = chapters
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
fun SeekbarWithTimers(
  position: Float,
  duration: Float,
  committedPosition: Float = position,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: (Float) -> Unit,
  timersInverted: Pair<Boolean, Boolean>,
  positionTimerOnClick: () -> Unit,
  durationTimerOnCLick: () -> Unit,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  seekbarStyle: SeekbarStyle = SeekbarStyle.Wavy,
  loopStart: Float? = null,
  loopEnd: Float? = null,
  bufferDuration: Float? = null,
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
      // Run the animation directly in this LaunchedEffect body (not via scope.launch).
      // When `position` changes on the next poll, the effect is cancelled and relaunched,
      // which retargets the same Animatable smoothly instead of stacking independent
      // spring coroutines that fight each other and leak work every ~50ms.
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

  if (isPortrait) {
    Column(
      modifier = modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.large),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      SeekbarContent(
        position = if (isUserInteracting) userPosition else animatedPosition.value,
        committedPosition = committedPosition,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        loopStart = loopStart,
        loopEnd = loopEnd,
        bufferDuration = bufferDuration,
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
        committedPosition = committedPosition,
        duration = duration,
        chapters = chapters,
        skipSegments = skipSegments,
        paused = paused,
        isPortrait = isPortrait,
        isUserInteracting = isUserInteracting,
        seekbarStyle = seekbarStyle,
        loopStart = loopStart,
        loopEnd = loopEnd,
        bufferDuration = bufferDuration,
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
  committedPosition: Float,
  duration: Float,
  chapters: ImmutableList<Segment>,
  skipSegments: ImmutableList<SkipSegment>,
  paused: Boolean,
  isPortrait: Boolean,
  isUserInteracting: Boolean,
  seekbarStyle: SeekbarStyle,
  loopStart: Float?,
  loopEnd: Float?,
  bufferDuration: Float?,
  onUserInteractionChange: (Boolean) -> Unit,
  onUserPositionChange: (Float) -> Unit,
  onValueChange: (Float) -> Unit,
  onValueChangeFinished: (Float) -> Unit,
  scope: kotlinx.coroutines.CoroutineScope,
  animatedPosition: Animatable<Float, *>,
  modifier: Modifier = Modifier
) {
  val touchAreaHeight = if (isPortrait) 64.dp else 52.dp
  val seekerState = rememberSeekerState()
  val seekerInteractionSource = remember { MutableInteractionSource() }
  val isSeekerPressed by seekerInteractionSource.collectIsPressedAsState()
  val isSeekerDragged by seekerInteractionSource.collectIsDraggedAsState()
  val isVisuallyInteracting = isUserInteracting || isSeekerPressed || isSeekerDragged
  val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
  val seekerRange = 0f..safeDuration.coerceAtLeast(0.1f)
  val safeCommittedPosition = committedPosition
    .takeIf { it.isFinite() }
    ?.coerceIn(seekerRange)
    ?: seekerRange.start
  val safeThumbPosition = position
    .takeIf { it.isFinite() }
    ?.coerceIn(seekerRange)
    ?: safeCommittedPosition
  val seekerSegments = remember(chapters, safeDuration) {
    normalizeSeekerSegments(chapters, safeDuration)
  }
  val overlayTrackHeight =
    when (seekbarStyle) {
      SeekbarStyle.Slim ->
        when {
          isVisuallyInteracting -> 15.dp
          paused -> 6.dp
          else -> 8.dp
        }
      SeekbarStyle.Thick -> 16.dp
      SeekbarStyle.Standard -> 8.dp
      SeekbarStyle.Wavy -> 8.dp
    }
  var latestInteractionPosition by remember { mutableFloatStateOf(position) }

  LaunchedEffect(position, isUserInteracting) {
    if (!isUserInteracting) {
      latestInteractionPosition = position
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

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
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
              position = position,
              duration = duration,
              chapters = chapters,
              isPaused = paused,
              isScrubbing = isVisuallyInteracting,
              useWavySeekbar = true,
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
              position = position,
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
              position    = position,
              duration    = duration,
              chapters    = chapters,
              isPaused    = paused,
              isScrubbing = isVisuallyInteracting,
              loopStart   = loopStart,
              loopEnd     = loopEnd,
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
          skipSegmentOverlays.forEach { overlay ->
            val startX = overlay.startFraction * size.width
            val endX = overlay.endFraction * size.width
            if (endX - startX < 1f) return@forEach
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

      Seeker(
        state = seekerState,
        value = safeCommittedPosition,
        thumbValue = safeThumbPosition,
        range = seekerRange,
        progressStartPosition = (safeCommittedPosition / seekerRange.endInclusive).coerceIn(0f, 1f),
        readAheadValue = normalizedReadAheadValue(
          bufferPosition = bufferDuration,
          playedPosition = safeCommittedPosition,
          duration = safeDuration,
        ).coerceIn(seekerRange),
        segments = seekerSegments,
        enabled = safeDuration > 0f,
        interactionSource = seekerInteractionSource,
        colors = SeekerDefaults.seekerColors(
          progressColor = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.primary,
          disabledProgressColor = MaterialTheme.colorScheme.primary,
          disabledTrackColor = MaterialTheme.colorScheme.primary,
          thumbColor = MaterialTheme.colorScheme.primary,
          disabledThumbColor = MaterialTheme.colorScheme.primary,
          readAheadColor = MaterialTheme.colorScheme.primary,
        ),
        dimensions = SeekerDefaults.seekerDimensions(
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
        modifier = Modifier
          .fillMaxWidth()
          .height(touchAreaHeight)
          .graphicsLayer(alpha = 0f),
      )
  }
}

@Composable
fun SeekThumbnailPreviewBubble(
  position: Float,
  duration: Float,
  visible: Boolean,
  bitmap: Bitmap?,
  isLoading: Boolean,
  isPortrait: Boolean,
  chapterTitle: String? = null,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible && duration > 0f,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier.fillMaxWidth(),
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
      val previewWidth = if (isPortrait) 152.dp else 132.dp
      val previewHeight = previewWidth * 9f / 16f
      val progress = (position / duration).coerceIn(0f, 1f)
      val maxOffset = (maxWidth - previewWidth).coerceAtLeast(0.dp)
      val xOffset = maxOffset * progress
      val previewShape = RoundedCornerShape(12.dp)

      Column(
        modifier =
          Modifier
            .offset(x = xOffset)
            .width(previewWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        chapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
          Surface(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(bottom = 5.dp),
            shape = RoundedCornerShape(999.dp),
            color = Color.Black.copy(alpha = 0.82f),
            contentColor = Color.White,
            tonalElevation = 0.dp,
          ) {
            Text(
              text = title,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
            )
          }
        }

        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .clip(previewShape),
          shape = previewShape,
          color = Color.Black.copy(alpha = 0.72f),
          contentColor = Color.White,
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
          tonalElevation = 0.dp,
          shadowElevation = 12.dp,
        ) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null && !bitmap.isRecycled) {
              Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Box(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)),
              )
            }

            if (isLoading) {
              Box(
                modifier =
                  Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  color = Color.White,
                  strokeWidth = 2.dp,
                )
              }
            }
          }
        }

        Surface(
          modifier = Modifier.padding(top = 6.dp),
          shape = RoundedCornerShape(999.dp),
          color = Color.Black.copy(alpha = 0.78f),
          contentColor = Color.White,
          tonalElevation = 0.dp,
        ) {
          Text(
            text = Utils.prettyTime(position.toInt(), false),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
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
  bufferDuration: Float? = null,
  modifier: Modifier = Modifier,
) {
  val primaryColor = MaterialTheme.colorScheme.primary
  val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

  val isInteracting = isScrubbing
  val thumbVisibility by animateFloatAsState(
    targetValue = if (isInteracting) 0f else 1f,
    animationSpec = spring(dampingRatio = AppMotion.Effect.Alpha.dampingRatio, stiffness = AppMotion.Effect.Alpha.stiffness),
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
          spring(
            dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
            stiffness = AppMotion.Spatial.Expressive.stiffness,
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

    if (bufferDuration != null && bufferDuration > 0f && duration > 0f) {
      val bufferPx = bufferedEndPx(bufferDuration, duration, totalWidth, totalProgressPx)
      if (bufferPx > totalProgressPx) {
        drawPathWithGaps(totalProgressPx, bufferPx, primaryColor.copy(alpha = 0.55f))
      }
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
    bufferDuration: Float? = null,
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
            else        -> spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness)
        },
        label = "slim_seekbar_height",
    )

    // Chapter gap widens when pressed so segments look clearly distinct
    val chapterGapHalfDp by animateDpAsState(
        targetValue = if (isScrubbing) 2.dp else 1.5.dp,
        animationSpec = spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness),
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
        val gapHalf       = chapterGapHalfDp.toPx()

        val segments = seekbarTrackSegments(
            chapterStarts = chapters.map(Segment::start),
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
            bufferedColor = primaryColor.copy(alpha = 0.55f),
            unplayedColor = unplayedColor,
        )

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
    progress: Float = 0.38f,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val previewProgress = progress

    if (style == SeekbarStyle.Wavy) {
        SquigglySeekbar(
            position = previewProgress * 100f,
            duration = 100f,
            chapters = persistentListOf(),
            isPaused = false,
            isScrubbing = false,
            useWavySeekbar = true,
            seekbarStyle = SeekbarStyle.Wavy,
            onSeek = {},
            onSeekFinished = {},
            modifier = modifier,
        )
    } else {
        Canvas(modifier = modifier.fillMaxWidth().height(36.dp)) {
            val playedPx = size.width * previewProgress
            val centerY = size.height / 2f

            when (style) {
                SeekbarStyle.Slim -> {
                    val height = 10.dp.toPx()
                    val radius = height / 2f
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.3f),
                        topLeft = Offset(0f, centerY - radius),
                        size = Size(size.width, height),
                        cornerRadius = CornerRadius(radius),
                    )
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
                    drawRoundRect(
                        color = primaryColor.copy(alpha = 0.3f),
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
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(playedPx - thumbW / 2f, centerY - thumbHalfH),
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
                        color = primaryColor.copy(alpha = 0.3f),
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
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(playedPx - thumbW / 2f, centerY - radius),
                        size = Size(thumbW, height),
                        cornerRadius = CornerRadius(thumbW / 2f),
                    )
                }
                SeekbarStyle.Wavy -> {
                    // Handled in parent branch
                }
            }
        }
    }
}

@Composable
fun SeekbarStyleLivePreview(
    style: SeekbarStyle,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "seekbar_live_preview")
    val animatedProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live_preview_progress",
    )

    SeekbarStylePreview(
        style = style,
        progress = animatedProgress,
        modifier = modifier,
    )
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
    seekbarStyle: SeekbarStyle = SeekbarStyle.Standard,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    loopStart: Float? = null,
    loopEnd: Float? = null,
    bufferDuration: Float? = null,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
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
            val startDelay = if (shouldFlatten) 0L else 60L

            kotlinx.coroutines.delay(startDelay)

            val animator = Animatable(heightFraction)
            animator.animateTo(
                targetValue = targetHeight,
                animationSpec = spring(
                    dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                    stiffness = AppMotion.Spatial.Expressive.stiffness,
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
    val chapterGapHalfDp by animateDpAsState(
        targetValue = if (isThumbInteracting) 2.dp else 1.5.dp,
        animationSpec = spring(
            dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
            stiffness = AppMotion.Spatial.Standard.stiffness,
        ),
        label = "standard_chapter_gap",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(thumbHeight)) {
        val safeDuration = duration.takeIf { it.isFinite() && it > 0f } ?: 0f
        val playedFraction = if (safeDuration > 0f) {
            position.takeIf { it.isFinite() }?.div(safeDuration)?.coerceIn(0f, 1f) ?: 0f
        } else {
            0f
        }
        val playedPx = size.width * playedFraction
        val bufferPx = bufferedEndPx(bufferDuration, safeDuration, size.width, playedPx)
        val trackHeight = trackHeightDp.toPx()
        val centerY = size.height / 2f
        val thumbWidthPx = thumbWidth.toPx()
        val thumbHeightPx = thumbHeight.toPx()
        val thumbGapHalf = (thumbWidthPx + if (isThick) 8.dp.toPx() else 10.dp.toPx()) / 2f
        val thumbGapStart = (playedPx - thumbGapHalf).coerceIn(0f, size.width)
        val thumbGapEnd = (playedPx + thumbGapHalf).coerceIn(0f, size.width)
        val segments = seekbarTrackSegments(
            chapterStarts = chapters.map(Segment::start),
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
            bufferedColor = primaryColor.copy(alpha = 0.55f),
            unplayedColor = primaryColor.copy(alpha = 0.3f),
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

        val thumbLeft = (playedPx - thumbWidthPx / 2f)
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
