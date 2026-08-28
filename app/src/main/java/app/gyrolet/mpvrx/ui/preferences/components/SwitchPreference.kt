/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.tv.tvFocusable

@Composable
fun SwitchPreference(
  value: Boolean,
  onValueChange: (Boolean) -> Unit,
  title: @Composable () -> Unit,
  summary: @Composable (() -> Unit)? = null,
  icon: @Composable (() -> Unit)? = null,
  enabled: Boolean = true,
  titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
  summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  switchModifier: Modifier = Modifier,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusable(shape = MaterialTheme.shapes.medium, enabled = enabled)
        .clickable(enabled = enabled) { onValueChange(!value) }
        .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Box(
        modifier = Modifier.padding(end = 16.dp),
        contentAlignment = Alignment.Center,
      ) {
        icon()
      }
    }

    Column(
      modifier =
        Modifier
          .weight(1f)
          .padding(end = 16.dp),
    ) {
      ProvideTextStyle(value = titleStyle) {
        title()
      }
      if (summary != null) {
        ProvideTextStyle(value = summaryStyle.copy(color = MaterialTheme.colorScheme.outline)) {
          summary()
        }
      }
    }

    IconSwitch(
      checked = value,
      onCheckedChange = onValueChange,
      enabled = enabled,
      modifier = switchModifier,
    )
  }
}
