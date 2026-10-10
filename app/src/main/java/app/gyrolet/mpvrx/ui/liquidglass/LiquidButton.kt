/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import app.gyrolet.mpvrx.ui.theme.AppMotion
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * App adapter for Kyant's catalog LiquidButton.
 *
 * Shared optics and press feedback, with a static fallback when no backdrop is available.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidButton(
  onClick: () -> Unit,
  backdrop: Backdrop?,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  enabled: Boolean = true,
  tint: Color = Color.Unspecified,
  surfaceColor: Color = Color.Unspecified,
  height: Dp = 48.dp,
  horizontalPadding: Dp = 16.dp,
  spacing: Dp = 8.dp,
  onLongClickLabel: String? = null,
  content: @Composable RowScope.() -> Unit,
) {
  val glassSettings = rememberLiquidGlassSettings()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val animatePress = enabled && !reducedMotion && backdrop != null
  val interactionSource = remember { MutableInteractionSource() }
  val animationScope = rememberCoroutineScope()
  val interactiveHighlight =
    remember(animationScope) {
      InteractiveHighlight(animationScope = animationScope)
    }
  val glassModifier =
    if (backdrop != null) {
      Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { Capsule() },
        effects = {
          liquidGlassEffects(
            glassSettings,
            5.dp.toPx(),
            12.dp.toPx(),
            20.dp.toPx(),
            vibrant = false,
            refractionEnabled = !reducedMotion,
          )
        },
        highlight = { glassSettings.highlight(Highlight.Default.copy(alpha = 0.65f)) },
        shadow = { glassSettings.shadow(Shadow(radius = 6.dp)) },
        innerShadow = { glassSettings.innerShadow(InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.18f))) },
        layerBlock =
          if (animatePress) {
            {
              val width = size.width
              val heightPx = size.height
              val progress = interactiveHighlight.pressProgress
              val scale = lerp(1f, 1f + 4.dp.toPx() / heightPx, progress)
              val maxOffset = size.minDimension
              val offset = interactiveHighlight.offset
              translationX = maxOffset * tanh(0.05f * offset.x / maxOffset)
              translationY = maxOffset * tanh(0.05f * offset.y / maxOffset)

              val maxDragScale = 4.dp.toPx() / heightPx
              val offsetAngle = atan2(offset.y, offset.x)
              scaleX =
                scale +
                  maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
                  (width / heightPx).fastCoerceAtMost(1f)
              scaleY =
                scale +
                  maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
                  (heightPx / width).fastCoerceAtMost(1f)
            }
          } else {
            null
          },
        onDrawSurface = {
          if (tint.isSpecified) {
            drawRect(tint, blendMode = BlendMode.Hue)
            drawRect(glassSettings.surfaceColor(tint.copy(alpha = 0.75f)))
          }
          if (surfaceColor.isSpecified) drawRect(glassSettings.surfaceColor(surfaceColor))
        },
      )
    } else {
      val fallbackColor =
        if (surfaceColor.isSpecified) surfaceColor else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.32f)
      Modifier
        .shadow(6.dp, Capsule())
        .clip(Capsule())
        .background(fallbackColor)
        .border(1.dp, Color.White.copy(alpha = 0.2f), Capsule())
    }

  Row(
    modifier =
      modifier
        .then(glassModifier)
        .combinedClickable(
          enabled = enabled,
          interactionSource = interactionSource,
          indication = if (animatePress) null else ripple(),
          role = Role.Button,
          onClick = onClick,
          onLongClick = onLongClick,
          onLongClickLabel = onLongClickLabel,
        ).then(
          if (animatePress) {
            Modifier
              .then(interactiveHighlight.modifier)
              .then(interactiveHighlight.gestureModifier)
          } else {
            Modifier
          },
        ).height(height)
        .padding(horizontal = horizontalPadding),
    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
    content = content,
  )
}
