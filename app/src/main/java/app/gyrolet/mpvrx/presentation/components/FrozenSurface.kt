/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

private val LocalFrozenBackdrop = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun rememberFrozenBackdrop(): HazeState = rememberHazeState()

fun Modifier.captureFrozenBackdrop(
  backdrop: HazeState,
  enabled: Boolean = true,
): Modifier = if (enabled) hazeSource(state = backdrop) else this

@Composable
fun ProvideFrozenBackdrop(
  backdrop: HazeState,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalFrozenBackdrop provides backdrop.takeIf { enabled },
    content = content,
  )
}

enum class FrozenSurfaceStyle {
  MiniPlayer,
  Navigation,
  TopBar,
  FloatingControl,
}

@Composable
fun Modifier.frozenSurface(
  shape: Shape,
  style: FrozenSurfaceStyle = FrozenSurfaceStyle.Navigation,
  tintColor: Color = MaterialTheme.colorScheme.primary,
  fallbackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
): Modifier {
  val backdrop = LocalFrozenBackdrop.current
  val frozen = backdrop != null
  val shadowElevation: Dp =
    when (style) {
      FrozenSurfaceStyle.MiniPlayer -> 10.dp
      FrozenSurfaceStyle.Navigation -> 8.dp
      FrozenSurfaceStyle.TopBar -> 0.dp
      FrozenSurfaceStyle.FloatingControl -> 12.dp
    }
  val fillAlpha =
    if (frozen) {
      when (style) {
        FrozenSurfaceStyle.MiniPlayer -> 0.62f
        FrozenSurfaceStyle.Navigation -> 0.55f
        FrozenSurfaceStyle.TopBar -> 0.68f
        FrozenSurfaceStyle.FloatingControl -> 0.64f
      }
    } else {
      1f
    }
  val edgeBrush =
    Brush.verticalGradient(
      0f to tintColor.copy(alpha = if (frozen) 0.46f else 0f),
      0.35f to MaterialTheme.colorScheme.onSurface.copy(alpha = if (frozen) 0.14f else 0f),
      1f to Color.Transparent,
    )

  return this
    .shadow(shadowElevation, shape)
      .clip(shape)
      .then(
        if (backdrop != null) {
          Modifier.hazeEffect(state = backdrop) {
            blurRadius = 24.dp
            backgroundColor = fallbackColor
            tints = listOf(HazeTint(tintColor.copy(alpha = 0.10f)))
            noiseFactor = 0f
          }
        } else {
          Modifier
        },
      ).background(fallbackColor.copy(alpha = fillAlpha), shape)
      .border(BorderStroke(0.75.dp, edgeBrush), shape)
}

@Composable
fun FrozenSurface(
  shape: Shape,
  modifier: Modifier = Modifier,
  style: FrozenSurfaceStyle = FrozenSurfaceStyle.Navigation,
  tintColor: Color = MaterialTheme.colorScheme.primary,
  fallbackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  content: @Composable BoxScope.() -> Unit,
) {
  val surfaceModifier =
    modifier.frozenSurface(
      shape = shape,
      style = style,
      tintColor = tintColor,
      fallbackColor = fallbackColor,
    )

  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Box(modifier = surfaceModifier, content = content)
  }
}
