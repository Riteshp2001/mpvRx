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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.liquidglass.drawShapeTint
import app.gyrolet.mpvrx.ui.liquidglass.connectedGlassRim
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.runtime.getValue
import app.gyrolet.mpvrx.ui.theme.LocalAppBackgroundBackdrop
import org.koin.compose.koinInject
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

typealias LiquidGlassBackdrop = LayerBackdrop

internal val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberLiquidGlassBackdrop(): LiquidGlassBackdrop = rememberLayerBackdrop()

fun Modifier.captureLiquidGlassBackdrop(
  backdrop: LiquidGlassBackdrop?,
  enabled: Boolean = true,
): Modifier = if (enabled && backdrop != null) layerBackdrop(backdrop) else this

/**
 * What a floating glass bar refracts: the app background captured at the top level (wallpaper and
 * animated background) with the screen layer [content] drawn over it. Screens turn transparent over
 * a wallpaper, so [content] alone leaves holes where the sharp content under the bar shows through
 * the glass. A bar can't sample one window-wide layer instead: it would be recording itself.
 * Returns null when [content] is null, which is how callers turn glass off.
 */
@Composable
fun rememberSceneGlassBackdrop(content: Backdrop?): Backdrop? {
  val background = LocalAppBackgroundBackdrop.current
  return when {
    content == null -> null
    background == null -> content
    else -> rememberCombinedBackdrop(background, content)
  }
}

@Composable
fun ProvideLiquidGlassBackdrop(
  backdrop: Backdrop,
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

/**
 * Kyant-backed liquid glass surface shared by mini players and floating action bars. It refracts
 * [backdrop] over the app background (see [rememberSceneGlassBackdrop]).
 */
@Composable
fun LiquidGlassSurface(
  shape: Shape,
  modifier: Modifier = Modifier,
  style: LiquidGlassStyle = LiquidGlassStyle.Navigation,
  glassColor: Color,
  fallbackColor: Color,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
  glowStrength: Float = 1f,
  cornerRadius: Dp? = null,
  connectionProgress: Float = 0f,
  content: @Composable BoxScope.() -> Unit,
) {
  val glassSettings = rememberLiquidGlassSettings()
  val preferences = koinInject<AppearancePreferences>()
  val glassEnabled by preferences.liquidGlassEnabled.collectAsState()
  val sceneBackdrop = rememberSceneGlassBackdrop(backdrop)
  val renderGlass = glassEnabled && sceneBackdrop != null
  val reducedMotion = AppMotion.shouldReduceMotion()
  val connection = connectionProgress.coerceIn(0f, 1f)
  val surfaceTint = lerp(glassColor, MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.34f), connection)
  val blurRadius = when (style) {
    LiquidGlassStyle.MiniPlayer -> 12.dp * (1f - connection) + 8.dp * connection
    LiquidGlassStyle.Navigation -> 8.dp
  }
  val refractionHeight = when (style) {
    LiquidGlassStyle.MiniPlayer -> 18.dp
    LiquidGlassStyle.Navigation -> 14.dp
  }
  val refractionAmount = when (style) {
    LiquidGlassStyle.MiniPlayer -> 26.dp
    LiquidGlassStyle.Navigation -> 22.dp
  }
  val shadowElevation: Dp = if (style == LiquidGlassStyle.MiniPlayer) 10.dp else 8.dp
  val glassShape =
    when {
      cornerRadius != null -> RoundedRectangle(cornerRadius)
      style == LiquidGlassStyle.MiniPlayer -> shape
      else -> Capsule()
    }

  val surfaceModifier =
    if (renderGlass) {
      modifier
        .drawBackdrop(
          backdrop = checkNotNull(sceneBackdrop),
          shape = { glassShape },
          effects = {
            liquidGlassEffects(
              glassSettings, blurRadius.toPx(), refractionHeight.toPx(), refractionAmount.toPx(),
              vibrant = true,
            )
          },
          highlight = {
            glassSettings.highlight(Highlight.Ambient.copy(alpha = (if (reducedMotion) 0.28f else 0.52f) * glowStrength * (1f - connection)))
          },
          shadow = {
            Shadow(
              radius = shadowElevation,
              color = Color.Black.copy(alpha = 0.16f * glowStrength * (1f - connection)),
            )
          },
          innerShadow = {
            InnerShadow(
              radius = 2.dp,
              color = Color.White.copy(alpha = 0.14f * glowStrength * (1f - connection)),
            )
          },
          onDrawSurface = {
            drawShapeTint(glassShape, glassSettings.surfaceColor(surfaceTint))
          },
        )
        .then(
          if (connection > 0f) Modifier.connectedGlassRim(
            glassShape,
            joinedAtTop = false,
            strength = connection * glowStrength * glassSettings.highlightStrength.coerceAtMost(1f),
            connectionProgress = connection,
          ) else Modifier,
        )
    } else {
      modifier
        .outsideShadow(shape, radius = shadowElevation)
        .clip(shape)
        .background(fallbackColor)
    }

  CompositionLocalProvider(LocalContentColor provides contentColor) {
    Box(modifier = surfaceModifier, content = content)
  }
}
