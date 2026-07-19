package app.gyrolet.mpvrx.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.ui.unit.Dp

import android.animation.ValueAnimator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * mpvRx motion policy — respects system reduce-motion accessibility setting.
 * When reduce-motion is true, all animations use non-bouncing standard specs.
 */
@Stable
data class MotionPolicy(val reduceMotion: Boolean = false)

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }

@Composable
fun rememberMotionPolicy(): MotionPolicy {
  LocalView.current
  return MotionPolicy(reduceMotion = !ValueAnimator.areAnimatorsEnabled())
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

  fun <T> noBounce(stiffness: Float): SpringSpec<T> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)

  val ReducedAlpha: SpringSpec<Float> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedOffset: SpringSpec<IntOffset> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedIntSize: SpringSpec<IntSize> = noBounce(stiffness = Spring.StiffnessMedium)
  val ReducedDp: SpringSpec<Dp> = noBounce(stiffness = Spring.StiffnessMedium)

  /** IntSize-specific spring for expandVertically/shrinkVertically animations. */
  val IntSizeSpring: SpringSpec<IntSize> = spring(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
  )

  /** Spatial animations — can overshoot for expressive feel. */
  object Spatial {
    val ExpressiveDefault: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val ExpressiveFast: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    val ExpressiveSlow: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 300f)
    val StandardDefault: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 380f)
    val Expressive: SpringSpec<Float> = ExpressiveDefault
    val Standard: SpringSpec<Float> = StandardDefault
    val Snappy: SpringSpec<Float> = ExpressiveFast
    val SnappyDp: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 1400f)

    /** Dp-specific variants for animateDpAsState. */
    val ExpressiveDp: SpringSpec<Dp> = spring(dampingRatio = 0.9f, stiffness = 700f)
    val StandardDp: SpringSpec<Dp> = spring(dampingRatio = 1f, stiffness = 380f)
  }

  /** Effect animations — color and alpha transitions (no overshoot). */
  object Effect {
    val Color: SpringSpec<Color> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
      )
    val Alpha: SpringSpec<Float> =
      spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
      )
  }
}

/**
 * Elevation tokens — consistent shadow/tonal elevation levels.
 */
object ElevationTokens {
  val Level0 = 0.dp
  val Level1 = 1.dp
  val Level2 = 3.dp
  val Level3 = 6.dp
  val Level4 = 8.dp
  val Level5 = 12.dp
}
