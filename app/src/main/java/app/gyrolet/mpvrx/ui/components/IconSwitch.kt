/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@Composable
fun IconSwitch(
  checked: Boolean,
  onCheckedChange: ((Boolean) -> Unit)?,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  colors: SwitchColors = SwitchDefaults.colors(),
) {
  Switch(
    checked = checked,
    onCheckedChange = onCheckedChange,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    thumbContent = {
      Crossfade(
        targetState = checked,
        animationSpec = tween(durationMillis = 200),
        label = "SwitchIconAnimation",
      ) { isChecked ->
        if (isChecked) {
          Icon(
            Icons.RoundedFilled.Check,
            contentDescription = null,
            modifier = Modifier.size(SwitchDefaults.IconSize),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
          )
        } else {
          Icon(
            Icons.RoundedFilled.Close,
            contentDescription = null,
            modifier = Modifier.size(SwitchDefaults.IconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
  )
}
