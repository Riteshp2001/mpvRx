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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import app.gyrolet.mpvrx.ui.preferences.SelectedPreferenceTile
import app.gyrolet.mpvrx.ui.preferences.playerSettingSummary
import app.gyrolet.mpvrx.ui.preferences.playerSettingAvailability
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
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
  val updateValue: (Boolean) -> Unit = { checked ->
    if (canChange && checked != value) {
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
  val insideConnectedCard = LocalInsidePreferenceCard.current
  val shape = when {
    insideConnectedCard -> MaterialTheme.shapes.small
    groupIndex != null && groupSize != null -> connectedShape
    else -> MaterialTheme.shapes.large
  }
  // Inside a connected PreferenceCard the parent owns all tile fills, including selection.
  // Standalone switches still draw their own animated tonal surfaces.
  val containerColor by animateColorAsState(
    targetValue = when {
      insideConnectedCard -> androidx.compose.ui.graphics.Color.Transparent
      // Connected rows in draggable panels need visible fills even when switched off.
      groupIndex != null && groupSize != null ->
        if (value) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.90f)
        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)
      value -> MaterialTheme.colorScheme.secondaryContainer
      else -> MaterialTheme.colorScheme.surfaceContainerLow
    },
    animationSpec = if (lowEnd) snap() else AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "settingsOptionSelectionColor",
  )
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (insideConnectedCard && value) Modifier.layoutId(SelectedPreferenceTile) else Modifier)
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
