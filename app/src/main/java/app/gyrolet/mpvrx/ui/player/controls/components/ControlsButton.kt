/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.LocalPlayerButtonsClickEvent
import app.gyrolet.mpvrx.ui.theme.LocalDarkAppColorScheme
import app.gyrolet.mpvrx.ui.theme.spacing

@Suppress("CompositionLocalAllowlist")
internal val LocalForceDarkPlayerButtonsBackground = staticCompositionLocalOf { false }

@Suppress("CompositionLocalAllowlist")
internal val LocalHidePlayerButtonsBackground = staticCompositionLocalOf { false }

@Composable
private fun playerButtonColorScheme(
  forceDark: Boolean =
    LocalForceDarkPlayerButtonsBackground.current && !LocalHidePlayerButtonsBackground.current,
): ColorScheme =
  if (forceDark) LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme else MaterialTheme.colorScheme

@Composable
internal fun playerButtonContainerColor(): Color =
  playerButtonColorScheme().surfaceContainer.copy(alpha = 0.55f)

@Composable
internal fun playerButtonContentColor(): Color = playerButtonColorScheme().onSurface

@Composable
internal fun playerButtonBorderColor(): Color =
  playerButtonColorScheme().outlineVariant.copy(alpha = 0.4f)

@Suppress("ModifierClickableOrder")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlsButton(
  icon: AppIcon,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: () -> Unit = {},
  title: String? = null,
  color: Color? = null,
  enabled: Boolean = true,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val hideBackground = LocalHidePlayerButtonsBackground.current
  val resolvedColor = color ?: playerButtonContentColor()

  val clickEvent = LocalPlayerButtonsClickEvent.current
  Surface(
    modifier =
      modifier
        .tvFocusHighlight(CircleShape, enabled)
        .clip(CircleShape)
        .combinedClickable(
          enabled = enabled,
          onClick = {
            clickEvent()
            onClick()
          },
          onLongClick = {
            clickEvent()
            onLongClick()
          },
          interactionSource = interactionSource,
          indication = ripple(),
        ),
    shape = CircleShape,
    color = if (hideBackground) Color.Transparent else playerButtonContainerColor(),
    contentColor = resolvedColor,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
    border =
      if (hideBackground) {
        null
      } else {
        BorderStroke(
          1.dp,
          playerButtonBorderColor(),
        )
      },
  ) {
    Icon(
      imageVector = icon,
      contentDescription = title,
      tint = if (enabled) resolvedColor else resolvedColor.copy(alpha = 0.38f),
      modifier =
        Modifier
          .padding(MaterialTheme.spacing.small)
          .size(20.dp),
    )
  }
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
        .spacedBy(spacing.extraSmall),
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
