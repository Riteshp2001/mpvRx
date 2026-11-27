/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.icons.AnimatedIcon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.liquidglass.LiquidControlColors
import app.gyrolet.mpvrx.ui.liquidglass.LiquidToggle
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.ui.liquidglass.liquidGlassActive
import app.gyrolet.mpvrx.ui.theme.rememberLowEndDevice
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop

@Composable
fun IconSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val haptics = rememberAppHaptics()
  val liquidGlassEnabled = liquidGlassActive()

  // Avoid a separate sampled glass layer for each settings switch on budget devices.
  val lowEndDevice = rememberLowEndDevice()
  if (liquidGlassEnabled && !lowEndDevice) {
    val switchBackdropColor = MaterialTheme.colorScheme.surfaceContainer
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(switchBackdropColor) }
    val backdrop = LocalKyantPlayerBackdrop.current ?: canvasBackdrop
    LiquidToggle(
      selected = { checked },
      onSelect = { newValue ->
        if (enabled && newValue != checked) {
          onCheckedChange?.invoke(newValue)
          haptics.selection(newValue)
        }
      },
      backdrop = backdrop,
      modifier = modifier,
      accentColor = LiquidControlColors.accent,
      enabled = enabled,
      isInteractive = onCheckedChange != null,
    )
    return
  }

  Switch(
    checked = checked,
    onCheckedChange =
      onCheckedChange?.let { callback ->
        { value ->
          if (value != checked) {
            callback(value)
            haptics.selection(value)
          }
        }
      },
    modifier = modifier,
    enabled = enabled,
    thumbContent = {
      AnimatedIcon(
        imageVector = if (checked) Icons.RoundedFilled.Check else Icons.RoundedFilled.Close,
        contentDescription = null,
        modifier = Modifier.size(SwitchDefaults.IconSize),
      )
    },
  )
}
