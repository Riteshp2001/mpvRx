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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import app.gyrolet.mpvrx.ui.player.controls.components.LocalPlayerButtonsOpacity
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
 * Shared press/drag feedback in both renderer modes. Without a sampleable backdrop (native HDR),
 * translucent surfaces reveal the underlying video without claiming to blur or refract it.
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
  backgroundAlpha: Float = 1f,
  content: @Composable RowScope.() -> Unit,
) {
  val glassSettings = rememberLiquidGlassSettings()
  val reducedMotion = AppMotion.shouldReduceMotion()
  val animatePress = enabled && !reducedMotion
  val interactionSource = remember { MutableInteractionSource() }
  val animationScope = rememberCoroutineScope()
  val interactiveHighlight =
    remember(animationScope) {
      InteractiveHighlight(animationScope = animationScope)
    }
  val rememberedHighlight = remember(glassSettings) { glassSettings.highlight(Highlight.Default) }
  val rememberedShadow = remember { Shadow(radius = 6.dp) }
  val rememberedInnerShadow = remember { InnerShadow(radius = 2.dp, color = Color.White.copy(alpha = 0.18f)) }
  // Read animated state in the layer phase, not composition. The same controller survives
  // SDR/HDR renderer changes, so pressing/dragging a button never changes its interaction model.
  //
  // Keep the elastic transform inside the control's measured bounds. drawBackdrop owns an
  // offscreen layer exactly as large as the composable; scaling that layer above 1f cuts the
  // capsule flat when a pill sits against a constrained player-control boundary. Compress first,
  // then let the dragged axis stretch back toward its resting size so the interaction remains
  // elastic without ever asking a parent for out-of-bounds pixels.
  val pressTransform: GraphicsLayerScope.() -> Unit = {
    if (animatePress && size.minDimension > 0f) {
      val width = size.width
      val heightPx = size.height
      val progress = interactiveHighlight.pressProgress
      val maxOffset = size.minDimension
      val offset = interactiveHighlight.offset
      val compressedScale = lerp(1f, 1f - (4.dp.toPx() / heightPx).coerceAtMost(0.10f), progress)
      val offsetAngle = atan2(offset.y, offset.x)
      val availableStretch = 1f - compressedScale
      scaleX = compressedScale + availableStretch *
        abs(cos(offsetAngle) * offset.x / size.maxDimension).coerceAtMost(1f) *
        (width / heightPx).fastCoerceAtMost(1f)
      scaleY = compressedScale + availableStretch *
        abs(sin(offsetAngle) * offset.y / size.maxDimension).coerceAtMost(1f) *
        (heightPx / width).fastCoerceAtMost(1f)

      val horizontalRoom = (width * (1f - scaleX) / 2f).coerceAtLeast(0f)
      val verticalRoom = (heightPx * (1f - scaleY) / 2f).coerceAtLeast(0f)
      translationX = horizontalRoom * tanh(0.12f * offset.x / maxOffset)
      translationY = verticalRoom * tanh(0.12f * offset.y / maxOffset)
    }
  }
  val glassModifier =
    if (backdrop != null) {
      Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { Capsule() },
        effects = {
          liquidGlassEffects(
            glassSettings,
            8.dp.toPx(),
            14.dp.toPx(),
            24.dp.toPx(),
            vibrant = true,
            refractionEnabled = !reducedMotion,
          )
        },
        highlight = { rememberedHighlight },
        shadow = { rememberedShadow },
        innerShadow = { rememberedInnerShadow },
        layerBlock = if (animatePress) pressTransform else null,
        onDrawSurface = {
          // The backdrop API samples a rectangular offscreen layer, but the visible
          // surface must be painted only inside its capsule. A full drawRect leaked a
          // thin rectangular box around LUA/speed/seek pills with Liquid Glass enabled.
          val capsuleCorner = CornerRadius(size.height / 2f)
          if (tint.isSpecified) {
            drawRoundRect(tint, cornerRadius = capsuleCorner, blendMode = BlendMode.Hue)
            drawRoundRect(glassSettings.surfaceColor(tint.copy(alpha = 0.75f)), cornerRadius = capsuleCorner)
          }
          if (surfaceColor.isSpecified) {
            drawRoundRect(glassSettings.surfaceColor(surfaceColor), cornerRadius = capsuleCorner)
          }
        },
      )
    } else {
      val baseColor = when {
        surfaceColor.isSpecified -> surfaceColor
        tint.isSpecified -> tint
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
      }
      // Thickens toward opaque above the default background level; fading below it is backgroundAlpha's job.
      val fillLevel = maxOf(LocalPlayerButtonsOpacity.current, PlayerButtonAlpha.DEFAULT_BACKGROUND_LEVEL)
      val fill = Brush.verticalGradient(
        0f to baseColor.copy(alpha = PlayerButtonAlpha.background(0.68f, fillLevel)),
        0.45f to baseColor.copy(alpha = PlayerButtonAlpha.background(0.42f, fillLevel)),
        1f to baseColor.copy(alpha = PlayerButtonAlpha.background(0.58f, fillLevel)),
      )
      val rim = Brush.linearGradient(
        listOf(
          Color.White.copy(alpha = (0.43f * glassSettings.highlightStrength).coerceIn(0f, 1f)),
          Color.White.copy(alpha = (0.10f * glassSettings.highlightStrength).coerceIn(0f, 1f)),
          baseColor.copy(alpha = (0.30f * glassSettings.highlightStrength).coerceIn(0f, 1f)),
        ),
      )
      Modifier
        .graphicsLayer(pressTransform)
        .shadow(elevation = 6.dp, shape = Capsule(), clip = false)
        .clip(Capsule())
        .background(fill, Capsule())
        .then(
          if (glassSettings.highlightWidth > 0f) Modifier.border(1.dp * glassSettings.highlightWidth, rim, Capsule())
          else Modifier,
        )
    }

  val interactionModifier =
    Modifier
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
      )

  if (backgroundAlpha >= 1f) {
    Row(
      modifier =
        modifier
          .then(glassModifier)
          .then(interactionModifier)
          .height(height)
          .padding(horizontal = horizontalPadding),
      horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  } else {
    // Only the glass fades: it gets its own layer behind the content, so icons stay fully opaque.
    // ModulateAlpha avoids an offscreen buffer that would cut the glass shadow off at the bounds.
    Box(
      modifier = modifier.then(interactionModifier).height(height),
      contentAlignment = Alignment.Center,
      propagateMinConstraints = true,
    ) {
      Spacer(
        Modifier
          .matchParentSize()
          .graphicsLayer {
            alpha = backgroundAlpha.coerceIn(0f, 1f)
            compositingStrategy = CompositingStrategy.ModulateAlpha
          }.then(glassModifier),
      )
      Row(
        modifier =
          (if (animatePress) Modifier.graphicsLayer(pressTransform) else Modifier)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
      )
    }
  }
}
