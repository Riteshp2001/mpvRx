package app.gyrolet.mpvrx.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * mpvRx expressive shape scale following Material 3 Expressive guidelines.
 * 8-level scale from 4dp (chips) to 48dp (high-emphasis expressive containers).
 */
val AppShapes =
  Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    largeIncreased = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    extraExtraLarge = RoundedCornerShape(48.dp),
  )

/**
 * Expressive shape tokens for directional/asymmetric corner treatments.
 * These create visual directionality — a hallmark of M3 Expressive design.
 */
object AppShapeScale {
  val none = RoundedCornerShape(0.dp)
  val extraSmall = RoundedCornerShape(4.dp)
  val small = RoundedCornerShape(8.dp)
  val medium = RoundedCornerShape(12.dp)
  val large = RoundedCornerShape(16.dp)
  val largeIncreased = RoundedCornerShape(20.dp)
  val extraLarge = RoundedCornerShape(28.dp)
  val extraLargeIncreased = RoundedCornerShape(32.dp)
  val extraExtraLarge = RoundedCornerShape(48.dp)
  val full = RoundedCornerShape(50)

  /** Asymmetric shapes — larger corners on the start side create visual flow. */
  val expressiveStart =
    RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 12.dp, bottomEnd = 12.dp)

  /** Asymmetric shapes — larger corners on the end side. */
  val expressiveEnd =
    RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp, topEnd = 28.dp, bottomEnd = 28.dp)

  /** Asymmetric shapes — larger corners on top, grounded at bottom. */
  val expressiveTop =
    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
}
