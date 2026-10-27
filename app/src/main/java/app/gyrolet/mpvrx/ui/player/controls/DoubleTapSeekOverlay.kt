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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.LeftSideOvalShape
import app.gyrolet.mpvrx.presentation.components.RightSideOvalShape
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.stableNumericWidth
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Each accepted tap is a distinct event, even when seeking is clamped at the media boundary.
// Position is normalized within the physical left/right seek region, not the whole player.
@Immutable
class SeekRippleRequest(val position: Offset, val isForward: Boolean)

private const val MAX_SEEK_RIPPLES = 4

@Composable
fun DoubleTapToSeekOvals(
  amount: Int,
  showOvals: Boolean,
  showSeekIcon: Boolean,
  showSeekTime: Boolean,
  rippleRequest: SeekRippleRequest?,
  modifier: Modifier = Modifier,
) {
  val gesturePreferences = koinInject<GesturePreferences>()
  val doubleTapSeekAreaWidth by gesturePreferences.doubleTapSeekAreaWidth.collectAsState()
  val reducedMotion = AppMotion.playerReducedMotion()
  val seekAreaFraction = (doubleTapSeekAreaWidth / 100f).coerceIn(0.01f, 0.5f)
  // Reset active waves when feedback/motion settings change, without replaying the last tap.
  val waves = remember(showOvals, reducedMotion) { mutableStateListOf<SeekRippleRequest>() }
  LaunchedEffect(rippleRequest) {
    if (showOvals && rippleRequest != null) {
      // Bound overdraw and coroutine count during rapid multi-tap seeking.
      while (waves.size >= MAX_SEEK_RIPPLES) waves.removeAt(0)
      waves.add(rippleRequest)
    }
  }

  var lastNonZeroAmount by remember { mutableIntStateOf(amount) }
  val labelScale = remember { Animatable(1f) }
  LaunchedEffect(amount, rippleRequest, reducedMotion) {
    if (amount != 0) lastNonZeroAmount = amount
    if (amount == 0 || reducedMotion) {
      labelScale.snapTo(1f)
    } else {
      labelScale.snapTo(1.06f)
      labelScale.animateTo(1f, tween(220, easing = LinearOutSlowInEasing))
    }
  }
  val displayedAmount = if (amount != 0) amount else lastNonZeroAmount
  val isForward = displayedAmount > 0

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
            .fillMaxWidth(seekAreaFraction),
        )
      }
    }

    AnimatedVisibility(
      visible = amount != 0 && (showSeekIcon || showSeekTime),
      modifier = Modifier
        .align(if (isForward) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
        .fillMaxHeight()
        .fillMaxWidth(seekAreaFraction),
      enter = fadeIn(tween(if (reducedMotion) 0 else 70)),
      exit = fadeOut(tween(if (reducedMotion) 0 else 160)),
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier.graphicsLayer {
              scaleX = labelScale.value
              scaleY = labelScale.value
            },
          ) {
            if (showSeekIcon && !isForward) {
              SeekChevron(false, amount, rippleRequest, reducedMotion)
            }
            if (showSeekTime) {
              val seekText = "${if (isForward) "+" else "−"} ${abs(displayedAmount)}"
              Text(
                text = seekText,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall.copy(
                  fontSize = 22.sp,
                  fontWeight = FontWeight.Bold,
                  fontFeatureSettings = "tnum",
                  shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 6f),
                ),
                modifier = Modifier.stableNumericWidth(seekText),
              )
            }
            if (showSeekIcon && isForward) {
              SeekChevron(true, amount, rippleRequest, reducedMotion)
            }
          }
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
      // A short, stationary touch cue; no expanding halo, bouncing label or moving chevrons.
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
        // Unit-radius brushes are cached and scaled in the draw phase. No per-frame shaders,
        // bitmap capture or backdrop sampling is needed for this translucent touch feedback.
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
private fun SeekChevron(
  isForward: Boolean,
  amount: Int,
  request: SeekRippleRequest?,
  reducedMotion: Boolean,
) {
  val progress = remember { Animatable(1f) }
  LaunchedEffect(amount, request, reducedMotion) {
    if (reducedMotion || amount == 0) {
      progress.snapTo(1f)
    } else {
      progress.snapTo(0f)
      progress.animateTo(1f, tween(240, easing = LinearOutSlowInEasing))
    }
  }
  Box(Modifier.size(48.dp).graphicsLayer { scaleX = if (isForward) 1f else -1f }) {
    Icon(
      imageVector = Icons.RoundedFilled.KeyboardArrowRight,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.fillMaxSize(),
    )
    if (!reducedMotion) {
      Icon(
        imageVector = Icons.RoundedFilled.KeyboardArrowRight,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.fillMaxSize().graphicsLayer {
          alpha = (1f - progress.value) * 0.65f
          translationX = -14.dp.toPx() * (1f - progress.value)
        },
      )
    }
  }
}
