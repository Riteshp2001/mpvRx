/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import app.gyrolet.mpvrx.ui.player.controls.components.LocalHidePlayerButtonsBackground
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonBorderColor
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContainerColor
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContentColor
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.spacing
import com.kyant.backdrop.Backdrop
import org.koin.compose.koinInject

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AdaptiveControlsButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: AppIcon? = null,
  onLongClick: () -> Unit = {},
  text: String? = null,
  title: String? = null,
  color: Color? = null,
  surfaceColor: Color = Color.Unspecified,
  buttonSize: Dp = 40.dp,
  useGlass: Boolean = true,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
  enabled: Boolean = true,
  onLongClickLabel: String? = null,
  hideBackground: Boolean? = null,
) {
  AdaptiveControlsContainer(
    onClick = onClick,
    modifier = modifier.widthIn(min = buttonSize),
    onLongClick = onLongClick,
    color = color ?: Color.Unspecified,
    surfaceColor = surfaceColor,
    useGlass = useGlass,
    hideBackground = hideBackground ?: LocalHidePlayerButtonsBackground.current,
    buttonSize = buttonSize,
    spacing = 4.dp,
    horizontalPadding = if (text != null) 8.dp else 0.dp,
    backdrop = backdrop,
    enabled = enabled,
    onLongClickLabel = onLongClickLabel,
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = title ?: text,
        tint = LocalContentColor.current,
        modifier = Modifier.size(PlayerLiquidTokens.IconSize),
      )
    }
    if (text != null) {
      Text(
        text = text,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = LocalContentColor.current,
        maxLines = 1,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AdaptiveControlsContainer(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  color: Color = Color.Unspecified,
  surfaceColor: Color = Color.Unspecified,
  isInteractive: Boolean = true,
  useGlass: Boolean = true,
  hideBackground: Boolean = LocalHidePlayerButtonsBackground.current,
  buttonSize: Dp = 40.dp,
  spacing: Dp = 8.dp,
  horizontalPadding: Dp? = null,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
  enabled: Boolean = true,
  onLongClickLabel: String? = null,
  content: @Composable RowScope.() -> Unit,
) {
  val preferences = koinInject<AppearancePreferences>()
  val liquidGlassEnabled by preferences.liquidGlassEnabled.collectAsState()
  val clickEvent = LocalPlayerButtonsClickEvent.current
  val resolvedTint =
    color.takeUnless { it == Color.Unspecified }
      ?: if (liquidGlassEnabled) PlayerLiquidTokens.contentColor else playerButtonContentColor()
  val resolvedSurface =
    surfaceColor.takeUnless { it == Color.Unspecified }
      ?: if (liquidGlassEnabled) PlayerLiquidTokens.surfaceColor else playerButtonContainerColor()
  val contentTint =
    if (enabled) resolvedTint else resolvedTint.copy(alpha = resolvedTint.alpha * PlayerButtonAlpha.DISABLED_CONTENT)
  val controlModifier =
    modifier
      .tvFocusHighlight(
        CircleShape,
        isInteractive && enabled,
        clipContent = !(liquidGlassEnabled && useGlass),
      )
      .then(if (isInteractive) Modifier.minimumInteractiveComponentSize() else Modifier)

  if (liquidGlassEnabled && useGlass) {
    CompositionLocalProvider(LocalContentColor provides contentTint) {
      LiquidButton(
        onClick = {
          clickEvent()
          onClick()
        },
        onLongClick = {
          clickEvent()
          onLongClick()
        },
        onLongClickLabel = onLongClickLabel,
        modifier = controlModifier,
        enabled = isInteractive && enabled,
        surfaceColor = resolvedSurface,
        height = buttonSize,
        spacing = spacing,
        horizontalPadding = horizontalPadding ?: 8.dp,
        backdrop = backdrop,
        content = content,
      )
    }
  } else {
    Surface(
      shape = CircleShape,
      color = if (hideBackground || !useGlass) Color.Transparent else resolvedSurface,
      contentColor = contentTint,
      tonalElevation = 0.dp,
      shadowElevation = 0.dp,
      border =
        if (hideBackground || !useGlass) {
          null
        } else {
          BorderStroke(1.dp, playerButtonBorderColor())
        },
      modifier =
        controlModifier
          .heightIn(min = buttonSize)
          .clip(CircleShape)
          .then(
            if (isInteractive) {
              Modifier.combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = {
                  clickEvent()
                  onClick()
                },
                onLongClick = {
                  clickEvent()
                  onLongClick()
                },
                onLongClickLabel = onLongClickLabel,
              )
            } else {
              Modifier
            },
          ),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.padding(horizontal = horizontalPadding ?: MaterialTheme.spacing.small),
          horizontalArrangement = Arrangement.spacedBy(spacing),
          content = content,
        )
      }
    }
  }
}
