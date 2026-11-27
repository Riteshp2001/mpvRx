/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics
import kotlin.math.roundToInt
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider

@Composable
fun SliderItem(
  label: String,
  value: Int,
  valueText: String,
  onChange: (Int) -> Unit,
  max: Int,
  modifier: Modifier = Modifier,
  min: Int = 0,
  enabled: Boolean = true,
  hapticLandmarks: List<Int> = emptyList(),
  icon: @Composable () -> Unit = {},
) {
  val haptics =
    rememberAdjustmentHaptics(
      min.toFloat(),
      max.toFloat(),
      (max - min - 1).coerceAtLeast(0),
      hapticLandmarks.map(Int::toFloat),
    )

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = MaterialTheme.spacing.smaller,
        ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
  ) {
    icon()
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
      SliderItemHeader(label = label, valueText = valueText)

      Slider(
        value = value.toFloat(),
        onValueChange = {
          val newValue = it.roundToInt()
          if (newValue != value) {
            onChange(newValue)
            haptics.move(value.toFloat(), newValue.toFloat())
          }
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .semantics {
              contentDescription = label
              stateDescription = valueText
            }.tvFocusHighlight(MaterialTheme.shapes.medium, enabled = enabled, focusedScale = 1.01f),
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min - 1).coerceAtLeast(0),
        enabled = enabled,
      )
    }
  }
}

@Composable
fun SliderItem(
  label: String,
  value: Float,
  valueText: String,
  onChange: (Float) -> Unit,
  max: Float,
  modifier: Modifier = Modifier,
  steps: Int = 0,
  min: Float = 0f,
  enabled: Boolean = true,
  hapticLandmarks: List<Float> = emptyList(),
  icon: @Composable () -> Unit = {},
) {
  val haptics = rememberAdjustmentHaptics(min, max, steps, hapticLandmarks)

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(
          horizontal = MaterialTheme.spacing.medium,
          vertical = MaterialTheme.spacing.smaller,
        ),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.large),
  ) {
    icon()
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
      SliderItemHeader(label = label, valueText = valueText)

      Slider(
        value = value,
        onValueChange = {
          val newValue = it
          if (newValue != value) {
            onChange(newValue)
            haptics.move(value, newValue)
          }
        },
        modifier =
          Modifier
            .fillMaxWidth()
            .semantics {
              contentDescription = label
              stateDescription = valueText
            }.tvFocusHighlight(MaterialTheme.shapes.medium, enabled = enabled, focusedScale = 1.01f),
        valueRange = min..max,
        steps = steps,
        enabled = enabled,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SliderItemHeader(
  label: String,
  valueText: String,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    Text(
      text = label,
      modifier = Modifier.align(Alignment.CenterVertically),
      style = MaterialTheme.typography.bodyMedium,
    )
    Text(
      text = valueText,
      modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelLargeEmphasized,
      textAlign = TextAlign.End,
    )
  }
}
