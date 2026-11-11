/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.preferences.LocalInsidePreferenceCard
import app.gyrolet.mpvrx.ui.preferences.PreferenceTileVisualState
import app.gyrolet.mpvrx.ui.preferences.playerSettingSummary
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import app.gyrolet.mpvrx.ui.theme.animatedConnectedContainerColor
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.rememberLowEndDevice
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
  val availability = playerSettingAvailability(modifier)
  if (!availability.visible) return
  val canChange = enabled && availability.enabled
  val effectiveSummary = playerSettingSummary(availability, enabled, summary)
  require((groupIndex == null) == (groupSize == null)) {
    "groupIndex and groupSize must either both be set or both be null"
  }
  if (groupIndex != null && groupSize != null) {
    require(groupSize > 0 && groupIndex in 0 until groupSize) {
      "groupIndex ($groupIndex) must be within groupSize ($groupSize)"
    }
  }
  val lowEnd = rememberLowEndDevice()
  val haptics = rememberAppHaptics()
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val updateValue: (Boolean) -> Unit = { checked ->
    if (canChange && checked != value) {
      onValueChange(checked)
      haptics.selection(checked)
    }
  }
  val insideConnectedCard = LocalInsidePreferenceCard.current
  val shape = when {
    insideConnectedCard -> MaterialTheme.shapes.small
    groupIndex != null && groupSize != null -> rememberConnectedShape(
      index = groupIndex,
      itemCount = groupSize,
      layout = ConnectedLayout.Vertical,
      pressed = pressed && canChange,
      selected = value,
      reduceMotion = lowEnd || AppMotion.shouldReduceMotion(),
    )
    else -> MaterialTheme.shapes.large
  }
  // Inside a connected PreferenceCard the parent owns all tile fills, including selection.
  // Standalone switches still draw their own animated tonal surfaces.
  val containerColor = animatedConnectedContainerColor(
    selected = value,
    pressed = pressed && !insideConnectedCard && groupIndex != null,
    enabled = canChange,
    containerColor = when {
      insideConnectedCard -> Color.Transparent
      groupIndex != null -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
      else -> MaterialTheme.colorScheme.surfaceContainerLow
    },
    selectedContainerColor = when {
      insideConnectedCard -> Color.Transparent
      groupIndex != null -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.90f)
      else -> MaterialTheme.colorScheme.secondaryContainer
    },
    reduceMotion = lowEnd || AppMotion.shouldReduceMotion(),
  )
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .then(
          if (insideConnectedCard) {
            Modifier.layoutId(PreferenceTileVisualState(selected = value, pressed = pressed && canChange))
          } else {
            Modifier
          },
        )
        .tvFocusHighlight(shape, enabled = canChange, focusedScale = 1.01f),
    shape = shape,
    color = containerColor,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .then(if (lowEnd) Modifier else Modifier.appPressFeedback(interactionSource, enabled = canChange, pressedScale = 0.99f))
          .clip(shape)
          .toggleable(
            value = value,
            enabled = canChange,
            role = Role.Switch,
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            onValueChange = updateValue,
          )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        ProvideTextStyle(value = titleStyle) {
          title()
        }
        if (effectiveSummary != null) {
          ProvideTextStyle(value = summaryStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
            effectiveSummary()
          }
        }
      }

      IconSwitch(
        checked = value,
        onCheckedChange = null,
        enabled = canChange,
        modifier = switchModifier,
      )
    }
  }
}
