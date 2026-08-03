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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@Composable
fun SettingsClickableItem(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  icon: AppIcon? = null,
  enabled: Boolean = true,
  onClick: () -> Unit = {},
  isFirstItem: Boolean = false,
  isLastItem: Boolean = false,
  trailing: @Composable (() -> Unit)? = null,
) {
  val shape =
    RoundedCornerShape(
      topStart = if (isFirstItem) 16.dp else 0.dp,
      topEnd = if (isFirstItem) 16.dp else 0.dp,
      bottomStart = if (isLastItem) 16.dp else 0.dp,
      bottomEnd = if (isLastItem) 16.dp else 0.dp,
    )

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(
          enabled = enabled,
          onClick = onClick,
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
        ),
    shape = shape,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (description != null) {
          Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (trailing != null) {
        Spacer(modifier = Modifier.width(8.dp))
        trailing()
      }
    }
  }
}

@Composable
fun SettingsSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = title,
    modifier = modifier.padding(start = 12.dp, top = 20.dp, bottom = 10.dp),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelLarge,
  )
}

@Composable
fun SettingsSwitchItem(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  icon: AppIcon? = null,
  isChecked: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
  isFirstItem: Boolean = false,
  isLastItem: Boolean = false,
) {
  SettingsClickableItem(
    title = title,
    description = description,
    icon = icon,
    enabled = enabled,
    onClick = onClick,
    isFirstItem = isFirstItem,
    isLastItem = isLastItem,
    modifier = modifier,
    trailing = {
      IconSwitch(
        checked = isChecked,
        onCheckedChange = null,
        enabled = enabled,
      )
    },
  )
}

@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
  HorizontalDivider(
    modifier = modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
  )
}
