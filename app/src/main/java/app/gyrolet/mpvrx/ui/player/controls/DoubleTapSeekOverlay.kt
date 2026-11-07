/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.LeftSideOvalShape
import app.gyrolet.mpvrx.presentation.components.RightSideOvalShape
import app.gyrolet.mpvrx.ui.player.controls.components.stableNumericWidth
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Each accepted tap is a distinct event, even when seeking is clamped at the media boundary.
// Position is normalized within the physical left/right seek region, not the whole player.
@Immutable
class SeekRippleRequest(val position: Offset, val isForward: Boolean)

private const val MAX_SEEK_RIPPLES = 4
private const val MAX_SEEK_CHEVRONS = 8
private const val SEEK_SLIDE_DURATION_MS = 400
private const val SEEK_MORPH_DURATION_MS = 333f
private const val SEEK_COUNT_COMPRESS_DURATION_MS = 118
private const val SEEK_COUNT_RELEASE_DURATION_MS = 66
private val seekSlideEasing = CubicBezierEasing(0.05f, 0f, 0f, 1f)
private val seekFeedbackEasing = CubicBezierEasing(0.2f, 0f, 0.6f, 1f)

private class SeekChevronWave {
  val elapsedMillis = Animatable(0f)
}

@Composable
fun DoubleTapToSeekOvals(
  amount: Int,
  showOvals: Boolean,
  showSeekIcon: Boolean,
  showSeekTime: Boolean,
  rippleRequest: SeekRippleRequest?,
  seekAreaFraction: Float,
  reducedMotion: Boolean = AppMotion.playerReducedMotion(),
  modifier: Modifier = Modifier,
) {
  val areaFraction = seekAreaFraction.coerceIn(0.01f, 0.5f)
  // Reset active waves when feedback/motion settings change, without replaying the last tap.
  val waves = remember(showOvals, reducedMotion, areaFraction) { mutableStateListOf<SeekRippleRequest>() }
  LaunchedEffect(rippleRequest) {
    if (showOvals && amount != 0 && rippleRequest != null) {
      // Bound overdraw and coroutine count during rapid multi-tap seeking.
      while (waves.size >= MAX_SEEK_RIPPLES) waves.removeAt(0)
      waves.add(rippleRequest)
    }
  }

  LaunchedEffect(amount == 0) {
    if (amount == 0) waves.clear()
  }

  Box(modifier.fillMaxSize()) {
    // Wave lifetime is independent of the accumulated seek amount and its reset timer.
    // Switching sides leaves each existing wave on its original side until it fades out.
    waves.forEach { wave ->
      key(wave) {
        SeekRippleWave(
          request = wave,
          reducedMotion = reducedMotion,
          onFinished = { waves.remove(wave) },
          modifier = Modifier
            .align(if (wave.isForward) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
            .fillMaxHeight()
            .fillMaxWidth(areaFraction),
        )
      }
    }

    listOf(false, true).forEach { isForward ->
      SeekSideFeedback(
        amount = if ((amount > 0) == isForward) amount else 0,
        isForward = isForward,
        showSeekIcon = showSeekIcon,
        showSeekTime = showSeekTime,
        rippleRequest = rippleRequest?.takeIf { it.isForward == isForward },
        reducedMotion = reducedMotion,
        modifier = Modifier
          .align(if (isForward) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
          .fillMaxHeight()
          .fillMaxWidth(areaFraction),
      )
    }
  }
}

@Composable
private fun SeekSideFeedback(
  amount: Int,
  isForward: Boolean,
  showSeekIcon: Boolean,
  showSeekTime: Boolean,
  rippleRequest: SeekRippleRequest?,
  reducedMotion: Boolean,
  modifier: Modifier,
) {
  var lastAmount by remember { mutableIntStateOf(amount) }
  LaunchedEffect(amount) {
    if (amount != 0) lastAmount = amount
  }
  val labelScale = remember { Animatable(1f) }
  LaunchedEffect(amount, rippleRequest, reducedMotion, showSeekTime) {
    if (reducedMotion || !showSeekTime) {
      labelScale.snapTo(1f)
    } else {
      // Retarget from the current scale so another tap never jumps back to full size.
      if (amount != 0) {
        labelScale.animateTo(0.9f, tween(SEEK_COUNT_COMPRESS_DURATION_MS, easing = seekFeedbackEasing))
      }
      labelScale.animateTo(
        1f,
        tween(SEEK_COUNT_RELEASE_DURATION_MS, easing = seekFeedbackEasing),
      )
    }
  }
  val displayedSeconds = abs(if (amount != 0) amount else lastAmount)
  val seekSign = if (isForward) "+" else "−"
  val seekText = "$seekSign $displayedSeconds"
  val seekTextStyle = MaterialTheme.typography.headlineSmall.copy(
    fontSize = 24.sp,
    lineHeight = 24.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 6f),
  )
  val textMeasurer = rememberTextMeasurer()
  // Only the layout slot is two digits wide; the actual number can grow inward without a limit.
  val counterSlotWidth = with(LocalDensity.current) {
    textMeasurer.measure(
      text = "$seekSign 99",
      style = seekTextStyle,
      maxLines = 1,
      softWrap = false,
      layoutDirection = LayoutDirection.Ltr,
    ).size.width.toDp()
  }
  val durationText = pluralStringResource(R.plurals.seconds, displayedSeconds, displayedSeconds)
  val seekDescription = stringResource(
    if (isForward) R.string.player_seek_forward else R.string.player_seek_backward,
    durationText,
  )

  AnimatedVisibility(
    visible = amount != 0 && (showSeekIcon || showSeekTime),
    modifier = modifier,
    enter = fadeIn(tween(if (reducedMotion) 0 else 200, easing = seekFeedbackEasing)),
    exit = fadeOut(tween(if (reducedMotion) 0 else 200, easing = seekFeedbackEasing)),
  ) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
          modifier = Modifier
            .wrapContentWidth(unbounded = true)
            .semantics(mergeDescendants = true) { contentDescription = seekDescription },
        ) {
          if (showSeekIcon && !isForward) SeekMorphingChevrons(isForward, amount, rippleRequest, reducedMotion)
          if (showSeekTime) {
            Text(
              text = seekText,
              color = Color.White,
              maxLines = 1,
              softWrap = false,
              style = seekTextStyle,
              textAlign = when {
                !showSeekIcon -> TextAlign.Center
                isForward -> TextAlign.End
                else -> TextAlign.Start
              },
              modifier = Modifier
                .width(counterSlotWidth)
                .wrapContentWidth(
                  align = when {
                    !showSeekIcon -> Alignment.CenterHorizontally
                    isForward -> Alignment.End
                    else -> Alignment.Start
                  },
                  unbounded = true,
                )
                .stableNumericWidth(seekText)
                .graphicsLayer {
                  transformOrigin = TransformOrigin(
                    pivotFractionX = when {
                      !showSeekIcon -> 0.5f
                      isForward -> 1f
                      else -> 0f
                    },
                    pivotFractionY = 0.5f,
                  )
                  scaleX = if (reducedMotion) 1f else labelScale.value
                  scaleY = scaleX
                },
            )
          }
          if (showSeekIcon && isForward) SeekMorphingChevrons(isForward, amount, rippleRequest, reducedMotion)
        }
      }
    }
  }
}

@Composable
private fun SeekRippleWave(
  request: SeekRippleRequest,
  reducedMotion: Boolean,
  onFinished: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val progress = remember { Animatable(0f) }
  val currentOnFinished by rememberUpdatedState(onFinished)
  LaunchedEffect(request) {
    if (reducedMotion) {
      // Keep the touch cue stationary when spatial motion is disabled.
      delay(160)
    } else {
      progress.animateTo(1f, tween(460, easing = LinearEasing))
    }
    currentOnFinished()
  }

  Box(
    modifier
      .clip(if (request.isForward) RightSideOvalShape else LeftSideOvalShape)
      .drawWithCache {
        val origin = Offset(size.width * request.position.x, size.height * request.position.y)
        // Reach the farthest corner even for touches near the edge of a tall portrait region.
        val maxRadius = hypot(max(origin.x, size.width - origin.x), max(origin.y, size.height - origin.y))
          .coerceAtLeast(1f)
        val startRadius = min(20.dp.toPx(), maxRadius * 0.12f)
        val staticRadius = min(56.dp.toPx(), maxRadius)
        val brush =
          Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.20f),
            0.65f to Color.White.copy(alpha = 0.18f),
            0.86f to Color.White.copy(alpha = 0.10f),
            0.96f to Color.Black.copy(alpha = 0.035f),
            1f to Color.Transparent,
            center = Offset.Zero,
            radius = 1f,
          )
        onDrawBehind {
          val p = progress.value
          val expansion = LinearOutSlowInEasing.transform(p)
          val radius =
            if (reducedMotion) staticRadius else startRadius + (maxRadius - startRadius) * expansion
          val fadeIn = (p / 0.09f).coerceIn(0f, 1f)
          val fadeOut = 1f - FastOutSlowInEasing.transform(((p - 0.28f) / 0.72f).coerceIn(0f, 1f))
          val opacity = if (reducedMotion) 0.7f else fadeIn * fadeOut
          withTransform({
            translate(origin.x, origin.y)
            scale(radius, radius, pivot = Offset.Zero)
          }) {
            drawCircle(brush, radius = 1f, center = Offset.Zero, alpha = opacity)
          }
        }
      },
  )
}

@Composable
private fun SeekMorphingChevrons(
  isForward: Boolean,
  amount: Int,
  rippleRequest: SeekRippleRequest?,
  reducedMotion: Boolean,
) {
  val entranceMillis = remember { Animatable(if (reducedMotion) SEEK_SLIDE_DURATION_MS.toFloat() else 0f) }
  val incoming = remember { mutableStateListOf<SeekChevronWave>() }
  var lastRequest by remember { mutableStateOf(rippleRequest) }
  var lastAmount by remember { mutableIntStateOf(amount) }

  // The settled arrow's entrance belongs to the visible session, never to later taps.
  LaunchedEffect(reducedMotion) {
    if (reducedMotion) {
      entranceMillis.snapTo(SEEK_SLIDE_DURATION_MS.toFloat())
    } else {
      entranceMillis.animateTo(
        SEEK_SLIDE_DURATION_MS.toFloat(),
        tween(SEEK_SLIDE_DURATION_MS, easing = LinearEasing),
      )
    }
  }

  LaunchedEffect(amount, rippleRequest, reducedMotion) {
    val newTap = if (rippleRequest != null) rippleRequest !== lastRequest else amount != lastAmount
    if (reducedMotion) {
      incoming.clear()
    } else if (amount != 0 && newTap) {
      while (incoming.size >= MAX_SEEK_CHEVRONS) incoming.removeAt(0)
      incoming.add(SeekChevronWave())
    }
    lastRequest = rippleRequest
    lastAmount = amount
  }

  incoming.forEach { wave ->
    key(wave) {
      LaunchedEffect(wave) {
        wave.elapsedMillis.animateTo(
          SEEK_SLIDE_DURATION_MS.toFloat(),
          tween(SEEK_SLIDE_DURATION_MS, easing = LinearEasing),
        )
        incoming.remove(wave)
      }
    }
  }

  Box(
    Modifier.size(width = 22.dp, height = 24.dp)
      .graphicsLayer { scaleX = if (isForward) 1f else -1f }
      .drawWithCache {
        // Preserve the vector's aspect ratio and fixed upper/lower anchors as the tip unfolds.
        val unit = min(size.width / 22f, size.height / 32f)
        val left = (size.width - 22f * unit) / 2f + 4f * unit
        val top = (size.height - 32f * unit) / 2f + 4f * unit
        val bottom = top + 24f * unit
        val path = Path()
        val stroke = Stroke(width = 4f * unit, cap = StrokeCap.Round, join = StrokeJoin.Miter)
        val travel = 20.dp.toPx()
        val mergeDistance = (stroke.width * 0.5f).coerceAtLeast(0.1f)
        onDrawBehind {
          val entrance = if (reducedMotion) SEEK_SLIDE_DURATION_MS.toFloat() else entranceMillis.value
          val baseMorph = seekSlideEasing.transform((entrance / SEEK_MORPH_DURATION_MS).coerceIn(0f, 1f))
          val baseSlide = seekSlideEasing.transform((entrance / SEEK_SLIDE_DURATION_MS).coerceIn(0f, 1f))

          fun drawChevron(morph: Float, slide: Float, alpha: Float) {
            path.reset()
            path.moveTo(left, top)
            path.lineTo(left + 12f * unit * morph, size.height / 2f)
            path.lineTo(left, bottom)
            withTransform({ translate(left = -travel * (1f - slide)) }) {
              drawPath(path, Color.White, alpha = alpha, style = stroke)
            }
          }

          if (!reducedMotion) {
            incoming.forEach { wave ->
              val elapsed = wave.elapsedMillis.value
              val morph = seekSlideEasing.transform((elapsed / SEEK_MORPH_DURATION_MS).coerceIn(0f, 1f))
              val slide = seekSlideEasing.transform((elapsed / SEEK_SLIDE_DURATION_MS).coerceIn(0f, 1f))
              val separation = travel * abs(baseSlide - slide) + 12f * unit * abs(baseMorph - morph)
              // Crossfade only once both paths coincide within half a stroke; removal cannot pop.
              val merge = seekFeedbackEasing.transform((1f - separation / mergeDistance).coerceIn(0f, 1f))
              drawChevron(morph, slide, (slide * 2f).coerceIn(0f, 1f) * (1f - merge))
            }
          }
          drawChevron(baseMorph, baseSlide, 1f)
        }
      },
  )
}
