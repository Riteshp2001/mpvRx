/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.liquidglass

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import app.gyrolet.mpvrx.ui.player.controls.components.playerButtonContentColor
import com.kyant.backdrop.Backdrop

object PlayerLiquidTokens {
  val ButtonSize: Dp = 44.dp
  val CenterButtonSize: Dp = 72.dp
  val IconSize: Dp = 24.dp
  val CenterIconSize: Dp = 34.dp
  val PillHeight: Dp = 40.dp

  val contentColor: Color
    @Composable get() = playerButtonContentColor()

  val disabledContentColor: Color
    @Composable get() = contentColor.copy(alpha = PlayerButtonAlpha.DISABLED_CONTENT)

  val selectedContentColor: Color
    @Composable get() = LiquidControlColors.accent

  val surfaceColor: Color
    @Composable get() = LiquidControlColors.surface

  val selectedSurfaceColor: Color
    @Composable get() = LiquidControlColors.selectedSurface
}

@Composable
fun LiquidIconButton(
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String? = null,
  tint: Color = Color.Unspecified,
  surfaceColor: Color = Color.Unspecified,
  size: Dp = PlayerLiquidTokens.ButtonSize,
  iconSize: Dp = PlayerLiquidTokens.IconSize,
  spacing: Dp = 8.dp,
  useGlass: Boolean = true,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
) {
  LiquidPillButton(
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier.requiredSize(size),
    tint = tint,
    surfaceColor = surfaceColor,
    height = size,
    spacing = spacing,
    horizontalPadding = 0.dp,
    useGlass = useGlass,
    backdrop = backdrop,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = LocalContentColor.current,
      modifier = Modifier.size(iconSize),
    )
  }
}

@Composable
fun LiquidPillButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  isInteractive: Boolean = true,
  tint: Color = Color.Unspecified,
  surfaceColor: Color = Color.Unspecified,
  height: Dp = PlayerLiquidTokens.PillHeight,
  spacing: Dp = 8.dp,
  horizontalPadding: Dp = 16.dp,
  useGlass: Boolean = true,
  backdrop: Backdrop? = LocalKyantPlayerBackdrop.current,
  onLongClickLabel: String? = null,
  content: @Composable RowScope.() -> Unit,
) {
  AdaptiveControlsContainer(
    onClick = onClick,
    modifier = modifier.height(height),
    onLongClick = onLongClick,
    color = tint,
    surfaceColor = surfaceColor,
    isInteractive = isInteractive,
    useGlass = useGlass,
    buttonSize = height,
    spacing = spacing,
    horizontalPadding = horizontalPadding,
    backdrop = backdrop,
    onLongClickLabel = onLongClickLabel,
    content = content,
  )
}
