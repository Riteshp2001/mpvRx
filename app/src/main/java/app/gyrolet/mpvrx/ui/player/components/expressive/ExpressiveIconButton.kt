/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.components.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.theme.AppMotion

/**
 * Expressive icon button with bouncy scale on press.
 */
@Composable
fun ExpressiveIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: @Composable () -> Unit,
) {
  var isPressed by remember { mutableStateOf(false) }

  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.85f else 1f,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "ExpressiveIconButtonScale",
  )

  IconButton(
    onClick = onClick,
    modifier = modifier.scale(scale),
    enabled = enabled,
    interactionSource = remember { MutableInteractionSource() },
  ) {
    icon()
  }
}

/**
 * Compact expressive icon button — smaller visual size with proper touch target.
 */
@Composable
fun CompactExpressiveIconButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  imageVector: AppIcon,
  contentDescription: String? = null,
) {
  var isPressed by remember { mutableStateOf(false) }

  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.85f else 1f,
    animationSpec = AppMotion.Spatial.Expressive,
    label = "CompactExpressiveIconButtonScale",
  )

  IconButton(
    onClick = onClick,
    modifier = modifier.scale(scale),
    enabled = enabled,
    colors = IconButtonDefaults.iconButtonColors(),
  ) {
    Icon(
      imageVector = imageVector,
      contentDescription = contentDescription,
      modifier = Modifier.size(20.dp),
      tint = LocalContentColor.current,
    )
  }
}
