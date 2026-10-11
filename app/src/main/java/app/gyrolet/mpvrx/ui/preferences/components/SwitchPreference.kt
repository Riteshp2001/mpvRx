/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitchPreference(
  value: Boolean,
  onValueChange: (Boolean) -> Unit,
  title: @Composable () -> Unit,
  summary: @Composable (() -> Unit)? = null,
  icon: @Composable (() -> Unit)? = null,
  enabled: Boolean = true,
  titleStyle: TextStyle = MaterialTheme.typography.bodyLargeEmphasized,
  summaryStyle: TextStyle = MaterialTheme.typography.bodyMedium,
  switchModifier: Modifier = Modifier,
  modifier: Modifier = Modifier,
  groupIndex: Int? = null,
  groupSize: Int? = null,
) {
  require((groupIndex == null) == (groupSize == null)) {
    "groupIndex and groupSize must either both be set or both be null"
  }
  if (groupIndex != null && groupSize != null) {
    require(groupSize > 0 && groupIndex in 0 until groupSize) {
      "groupIndex ($groupIndex) must be within groupSize ($groupSize)"
    }
  }
  val haptics = rememberAppHaptics()
  val updateValue: (Boolean) -> Unit = { checked ->
    if (checked != value) {
      onValueChange(checked)
      haptics.selection(checked)
    }
  }
  val connectedShape =
    rememberConnectedShape(
      index = groupIndex ?: 0,
      itemCount = groupSize ?: 1,
      layout = ConnectedLayout.Vertical,
    )
  val shape = if (groupIndex != null && groupSize != null) connectedShape else MaterialTheme.shapes.large
  val containerColor by
    animateColorAsState(
      targetValue =
        if (value) {
          MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
          MaterialTheme.colorScheme.surfaceContainerLow
        },
      animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
      label = "settings switch container",
    )
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(shape, enabled = enabled, focusedScale = 1.01f),
    shape = shape,
    color = containerColor,
    tonalElevation = if (value) 1.dp else 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(shape)
          .toggleable(value = value, enabled = enabled, role = Role.Switch, onValueChange = updateValue)
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
          ProvideTextStyle(value = summaryStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            summary()
          }
        }
      }

      IconSwitch(
        checked = value,
        onCheckedChange = updateValue,
        enabled = enabled,
        modifier = switchModifier,
      )
    }
  }
}
