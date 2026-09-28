/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

private val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun rememberLiquidGlassBackdrop(): LayerBackdrop = rememberLayerBackdrop()

fun Modifier.captureLiquidGlassBackdrop(
  backdrop: LayerBackdrop,
  enabled: Boolean = true,
): Modifier =
  if (enabled && isRenderEffectSupported()) layerBackdrop(backdrop) else this

@Composable
fun ProvideLiquidGlassBackdrop(
  backdrop: LayerBackdrop,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(
    LocalLiquidGlassBackdrop provides backdrop.takeIf { enabled },
    content = content,
  )
}

enum class LiquidGlassStyle {
  MiniPlayer,
  Navigation,
}

/** A Compose-native glass panel with a readable opaque fallback below Android 12. */
@Composable
fun LiquidGlassSurface(
  shape: Shape,
  modifier: Modifier = Modifier,
  style: LiquidGlassStyle = LiquidGlassStyle.Navigation,
  glassColor: Color,
  fallbackColor: Color,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  content: @Composable BoxScope.() -> Unit,
) {
  val backdrop = LocalLiquidGlassBackdrop.current
  val reducedMotion = AppMotion.shouldReduceMotion()
  val blurRadius = if (style == LiquidGlassStyle.MiniPlayer) 12.dp else 8.dp
  val refractionHeight = if (style == LiquidGlassStyle.MiniPlayer) 14.dp else 12.dp
  val refractionAmount = if (style == LiquidGlassStyle.MiniPlayer) 26.dp else 22.dp
  val shadowElevation: Dp = if (style == LiquidGlassStyle.MiniPlayer) 10.dp else 8.dp

  val surfaceModifier =
    if (backdrop != null && isRenderEffectSupported()) {
      modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
          vibrancy()
          blur(blurRadius.toPx())
          if (!reducedMotion) {
            lens(
              refractionHeight = refractionHeight.toPx(),
              refractionAmount = refractionAmount.toPx(),
              depthEffect = true,
              chromaticAberration = true,
            )
          }
        },
        highlight = {
          if (reducedMotion) Highlight.Plain.copy(alpha = 0.35f) else Highlight.Default
        },
        shadow = {
          Shadow(
            radius = shadowElevation,
            color = Color.Black.copy(alpha = 0.16f),
          )
        },
        onDrawSurface = { drawRect(glassColor) },
      )
    } else {
      modifier
        .shadow(shadowElevation, shape)
        .clip(shape)
        .background(fallbackColor)
    }

  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Box(modifier = surfaceModifier, content = content)
  }
}
