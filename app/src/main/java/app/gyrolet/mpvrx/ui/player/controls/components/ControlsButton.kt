/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.AdaptiveControlsButton
import app.gyrolet.mpvrx.ui.liquidglass.PlayerLiquidTokens
import app.gyrolet.mpvrx.ui.player.controls.PlayerButtonAlpha
import app.gyrolet.mpvrx.ui.theme.LocalDarkAppColorScheme
import app.gyrolet.mpvrx.ui.theme.spacing

@Suppress("CompositionLocalAllowlist")
internal val LocalForceDarkPlayerButtonsBackground = staticCompositionLocalOf { false }

/** Drawer tiles supply their own surface; their embedded controls remain borderless. */
@Suppress("CompositionLocalAllowlist")
internal val LocalBorderlessPlayerControls = staticCompositionLocalOf { false }

@Suppress("CompositionLocalAllowlist")
internal val LocalPlayerButtonsOpacity = staticCompositionLocalOf { PlayerButtonAlpha.DEFAULT_BACKGROUND_LEVEL }

/** This color with its alpha mapped through the player button background-opacity setting. */
@Composable
internal fun Color.asPlayerButtonBackground(): Color =
  copy(alpha = PlayerButtonAlpha.background(alpha, LocalPlayerButtonsOpacity.current))

@Composable
private fun playerButtonColorScheme(
  forceDark: Boolean =
    LocalForceDarkPlayerButtonsBackground.current && !LocalBorderlessPlayerControls.current,
): ColorScheme =
  if (forceDark) LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme else MaterialTheme.colorScheme

@Composable
internal fun playerButtonContainerColor(): Color =
  playerButtonColorScheme().surfaceContainerHigh.copy(alpha = PlayerButtonAlpha.CONTAINER).asPlayerButtonBackground()

@Composable
internal fun playerButtonContentColor(): Color = playerButtonColorScheme().onSurface

@Composable
internal fun playerButtonBorderColor(): Color =
  playerButtonColorScheme().outlineVariant.copy(alpha = PlayerButtonAlpha.BORDER).asPlayerButtonBackground()

@Composable
fun ControlsButton(
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String? = null,
  color: Color? = null,
  enabled: Boolean = true,
  onLongClickLabel: String? = null,
) {
  AdaptiveControlsButton(
    onClick = onClick,
    modifier = modifier,
    icon = icon,
    onLongClick = onLongClick,
    title = title,
    color = color ?: if (LocalBorderlessPlayerControls.current) LocalContentColor.current else playerButtonContentColor(),
    enabled = enabled,
    onLongClickLabel = onLongClickLabel,
    borderless = LocalBorderlessPlayerControls.current,
    useGlass = true,
    buttonSize = PlayerLiquidTokens.ButtonSize,
  )
}

@Composable
fun ControlsGroup(
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val spacing = MaterialTheme.spacing

  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement =
      androidx.compose.foundation.layout.Arrangement
        .spacedBy(if (LocalBorderlessPlayerControls.current) 0.dp else spacing.extraSmall),
    content = content,
  )
}

@Preview
@Composable
private fun PreviewControlsButton() {
  ControlsButton(
    Icons.RoundedFilled.CatchingPokemon,
    onClick = {},
  )
}
