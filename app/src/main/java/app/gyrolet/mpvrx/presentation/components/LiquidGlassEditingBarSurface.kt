package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.liquidglass.drawCapsuleTint
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassEffects
import app.gyrolet.mpvrx.ui.liquidglass.outsideShadow
import app.gyrolet.mpvrx.ui.liquidglass.rememberLiquidGlassSettings
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import org.koin.compose.koinInject

/** The selection action bar's glass. It refracts [backdrop] over the app background. */
@Composable
fun LiquidGlassEditingBarSurface(
  backdrop: Backdrop?,
  content: @Composable BoxScope.() -> Unit,
) {
  val preferences = koinInject<AppearancePreferences>()
  val glassEnabled by preferences.liquidGlassEnabled.collectAsState()
  val sceneBackdrop = rememberSceneGlassBackdrop(backdrop)
  val settings = rememberLiquidGlassSettings()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val shape = CircleShape
  val glassColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.30f)
  val fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)

  val surfaceModifier =
    if (glassEnabled && sceneBackdrop != null) {
      Modifier.drawBackdrop(
        backdrop = sceneBackdrop,
        shape = { Capsule() },
        effects = {
          liquidGlassEffects(
            settings,
            blurRadius = 10.dp.toPx(),
            refractionHeight = 18.dp.toPx(),
            refractionAmount = 26.dp.toPx(),
            vibrant = true,
            refractionEnabled = !reducedMotion,
          )
        },
        highlight = {
          settings.highlight(Highlight.Ambient.copy(alpha = if (reducedMotion) 0.24f else 0.48f))
        },
        shadow = {
          Shadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.17f))
        },
        innerShadow = {
          InnerShadow(radius = 3.dp, color = Color.White.copy(alpha = 0.16f))
        },
        onDrawSurface = { drawCapsuleTint(settings.surfaceColor(glassColor)) },
      )
    } else {
      Modifier.outsideShadow(shape, radius = 8.dp).clip(shape).background(fallbackColor)
    }

  Box(modifier = surfaceModifier, content = content)
}
