/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/** Surface treatment is independent of whether the renderer exposes a sampleable video layer. */
internal val LocalPlayerGlassSurfaceActive = staticCompositionLocalOf { false }

/** Shared material for player sheets and movable panels; one optical layer per container. */
@Composable
internal fun Modifier.playerGlassSurface(
  backdrop: Backdrop?,
  shape: Shape,
  containerColor: Color,
  enabled: Boolean = true,
): Modifier {
  if (!enabled) return this
  val settings = rememberLiquidGlassSettings()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val tint = remember(containerColor) {
    // Preserve the player's original 80% frosted backing. Rows inside it provide lighter
    // 35% / 65% resting / selected surfaces, rather than opaque cards hiding the video.
    Brush.verticalGradient(
      0f to containerColor.copy(alpha = 0.86f),
      0.25f to containerColor.copy(alpha = 0.80f),
      1f to containerColor.copy(alpha = 0.80f),
    )
  }
  // SurfaceView (HDR without the "liquid glass in HDR" opt-in) is composed by Android outside the
  // Compose scene. Keep HDR untouched and show it through the same translucent backing; do not
  // sample stale frames or fake a blur.
  if (backdrop == null) {
    return background(tint, shape).then(
      if (settings.highlightWidth > 0f && settings.highlightStrength > 0f) {
        Modifier.border(
          1.dp * settings.highlightWidth,
          Brush.verticalGradient(
            listOf(
              Color.White.copy(alpha = (0.18f * settings.highlightStrength).coerceIn(0f, 1f)),
              Color.White.copy(alpha = (0.04f * settings.highlightStrength).coerceIn(0f, 1f)),
            ),
          ),
          shape,
        )
      } else Modifier,
    )
  }
  val rememberedHighlight = remember(settings, reducedMotion) {
    settings.highlight(Highlight.Ambient.copy(alpha = if (reducedMotion) 0.15f else 0.30f))
  }
  val rememberedShadow = remember {
    Shadow(radius = 16.dp, color = Color.Black.copy(alpha = 0.25f))
  }
  val rememberedInnerShadow = remember {
    InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.12f))
  }
  return drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
      liquidGlassEffects(
        settings,
        blurRadius = 12.dp.toPx(),
        refractionHeight = 12.dp.toPx(),
        refractionAmount = 20.dp.toPx(),
        vibrant = true,
        refractionEnabled = !reducedMotion,
      )
    },
    highlight = { rememberedHighlight },
    shadow = { rememberedShadow },
    innerShadow = { rememberedInnerShadow },
    onDrawSurface = { drawShapeTint(shape, tint) },
  )
}

/**
 * Glass fill for controls that keep their own expressive layout and shape (animated corners,
 * connected bars, pills). The glass is drawn behind the caller's content, so clickables, ripples,
 * focus highlights and press feedback stay exactly as they are without Liquid Glass.
 */
@Composable
internal fun Modifier.liquidGlassControlSurface(
  backdrop: Backdrop?,
  shape: Shape,
  surfaceColor: Color,
): Modifier {
  if (backdrop == null) return background(surfaceColor, shape)
  val settings = rememberLiquidGlassSettings()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val rememberedHighlight = remember(settings) { settings.highlight(Highlight.Default) }
  val rememberedShadow = remember { Shadow(radius = 6.dp) }
  val rememberedInnerShadow = remember { InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.18f)) }
  return drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
      liquidGlassEffects(
        settings,
        blurRadius = 8.dp.toPx(),
        refractionHeight = 14.dp.toPx(),
        refractionAmount = 24.dp.toPx(),
        vibrant = true,
        refractionEnabled = !reducedMotion,
      )
    },
    highlight = { rememberedHighlight },
    shadow = { rememberedShadow },
    innerShadow = { rememberedInnerShadow },
    onDrawSurface = { drawShapeTint(shape, settings.surfaceColor(surfaceColor)) },
  )
}
