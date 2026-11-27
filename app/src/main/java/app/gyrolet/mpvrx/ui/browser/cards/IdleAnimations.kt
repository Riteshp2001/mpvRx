// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private class RequestedTarget<T>(
  var value: T,
)

/**
 * Same result as animate*AsState for media-card decorations, without its idle cost: that API keeps
 * a coroutine and a channel per call and launches another coroutine on every recomposition. Rows
 * hold several of these and almost never animate, so a coroutine starts only when the target
 * changes. Pass one [scope] to share it between the animations of a component.
 */
@Composable
internal fun <T, V : AnimationVector> animateValueWhenChanged(
  targetValue: T,
  typeConverter: TwoWayConverter<T, V>,
  animationSpec: AnimationSpec<T>,
  visibilityThreshold: T? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
): State<T> {
  val animatable = remember { Animatable(targetValue, typeConverter, visibilityThreshold) }
  val requested = remember { RequestedTarget(targetValue) }
  SideEffect {
    if (requested.value != targetValue) {
      requested.value = targetValue
      scope.launch { animatable.animateTo(targetValue, animationSpec) }
    }
  }
  return animatable.asState()
}

@Composable
internal fun animateFloatWhenChanged(
  targetValue: Float,
  animationSpec: AnimationSpec<Float>,
  scope: CoroutineScope = rememberCoroutineScope(),
): State<Float> = animateValueWhenChanged(targetValue, Float.VectorConverter, animationSpec, 0.01f, scope)

@Composable
internal fun animateDpWhenChanged(
  targetValue: Dp,
  animationSpec: AnimationSpec<Dp>,
  scope: CoroutineScope = rememberCoroutineScope(),
): State<Dp> = animateValueWhenChanged(targetValue, Dp.VectorConverter, animationSpec, Dp.VisibilityThreshold, scope)

@Composable
internal fun animateColorWhenChanged(
  targetValue: Color,
  animationSpec: AnimationSpec<Color>,
  scope: CoroutineScope = rememberCoroutineScope(),
): State<Color> {
  val converter = remember(targetValue.colorSpace) { (Color.VectorConverter)(targetValue.colorSpace) }
  return animateValueWhenChanged(targetValue, converter, animationSpec, null, scope)
}
