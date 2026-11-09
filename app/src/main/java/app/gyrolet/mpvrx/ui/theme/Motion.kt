/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import org.koin.compose.koinInject

/**
 * mpvRx motion policy — respects system reduce-motion accessibility setting.
 * Spatial feedback is removed when Android animations are disabled.
 */
@Stable
data class MotionPolicy(
  val reduceMotion: Boolean = false,
)

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }

@Composable
fun rememberMotionPolicy(): MotionPolicy {
  val reducedByPreference by koinInject<PlayerPreferences>().reduceMotion.collectAsState()
  var animationsDisabled by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    animationsDisabled = !ValueAnimator.areAnimatorsEnabled()
  }
  return MotionPolicy(reduceMotion = animationsDisabled || reducedByPreference)
}

/**
 * Centralized motion specification object with spring-based animations.
 * Spring animations feel more natural than duration-based tweens.
 */
object AppMotion {
  @Composable
  fun policy(): MotionPolicy = LocalMotionPolicy.current

  @Composable
  fun <T> spatial(
    spec: FiniteAnimationSpec<T>,
    reduced: FiniteAnimationSpec<T>,
  ): FiniteAnimationSpec<T> = if (policy().reduceMotion) reduced else spec

  @Composable
  fun shouldReduceMotion(): Boolean = policy().reduceMotion

  @Composable
  fun playerReducedMotion(): Boolean {
    val reducedByPlayer by koinInject<PlayerPreferences>().reduceMotion.collectAsState()
    return shouldReduceMotion() || reducedByPlayer
  }

  fun <T> noBounce(stiffness: Float): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

  val EnterEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

  /** A small, interruptible hand-off for changing actions; never changes their touch target. */
  fun iconChange(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
      EnterTransition.None togetherWith ExitTransition.None
    } else {
      (fadeIn(tween(160)) + scaleIn(noBounce(700f), initialScale = 0.78f)) togetherWith
        (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.92f))
    }

  val ReducedAlpha: SpringSpec<Float> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedOffset: SpringSpec<IntOffset> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedIntSize: SpringSpec<IntSize> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedDp: SpringSpec<Dp> = noBounce(stiffness = Spring.StiffnessMedium)

  /** IntSize-specific spring for expandVertically/shrinkVertically animations. */
  val IntSizeSpring: SpringSpec<IntSize> =
    spring(
      dampingRatio = 0.8f,
      stiffness = 380f,
    )

  /** Spatial animations — can overshoot for expressive feel. */
  object Spatial {
    val Press = tween<Float>(durationMillis = 90, easing = AppMotion.EnterEasing)
    val Release: SpringSpec<Float> = AppMotion.noBounce(stiffness = 700f)
    val ExpressiveDefault: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)
    val ExpressiveFast: SpringSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f)
    val ExpressiveSlow: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f)
    val ExpressiveOffset: SpringSpec<IntOffset> = spring(dampingRatio = 0.8f, stiffness = 380f)
    val ExpressiveSize: SpringSpec<IntSize> = spring(dampingRatio = 0.8f, stiffness = 380f)
    val StandardDefault: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val StandardOffset: SpringSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val StandardSize: SpringSpec<IntSize> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val Expressive: SpringSpec<Float> = ExpressiveDefault
    val Standard: SpringSpec<Float> = StandardDefault
    val Snappy: SpringSpec<Float> = ExpressiveFast
    val SnappyDp: SpringSpec<Dp> = spring(dampingRatio = 0.6f, stiffness = 800f)

    /** Dp-specific variants for animateDpAsState. */
    val ExpressiveDp: SpringSpec<Dp> = spring(dampingRatio = 0.8f, stiffness = 380f)
    val StandardDp: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 700f)
  }

  /** Effect animations — color and alpha transitions (no overshoot). */
  object Effect {
    val Color: SpringSpec<Color> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1600f,
      )
    val Alpha: SpringSpec<Float> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 1600f,
      )
  }
}

/**
 * Shares the control's existing interaction source, so clicks, long presses, ripple and D-pad
 * behavior stay with the original control. Only drawing scales; layout and hit bounds stay put.
 */
@Composable
fun Modifier.appPressFeedback(
  interactionSource: InteractionSource,
  enabled: Boolean = true,
  pressedScale: Float = 0.97f,
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Modifier {
  if (!enabled || reduceMotion) return this
  val pressed by interactionSource.collectIsPressedAsState()
  val scale = animateFloatAsState(
    targetValue = if (pressed) pressedScale else 1f,
    animationSpec = if (pressed) AppMotion.Spatial.Press else AppMotion.Spatial.Release,
    label = "appPressFeedback",
  )
  return graphicsLayer {
    scaleX = scale.value
    scaleY = scale.value
  }
}

/** Dialogs enter once per opening, not on recomposition or when their fields change. */
@Composable
fun Modifier.appDialogEnter(
  reduceMotion: Boolean = AppMotion.shouldReduceMotion(),
): Modifier {
  if (reduceMotion) return this
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) {
    progress.animateTo(1f, tween(durationMillis = 220, easing = AppMotion.EnterEasing))
  }
  return graphicsLayer {
    alpha = progress.value
    scaleX = 0.94f + 0.06f * progress.value
    scaleY = scaleX
  }
}
