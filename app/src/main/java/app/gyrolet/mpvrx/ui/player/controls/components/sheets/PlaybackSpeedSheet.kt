/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.ui.components.AppSlider as Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.presentation.components.RepeatingIconButton
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import org.koin.compose.koinInject
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun PlaybackSpeedSheet(
  speed: Float,
  speedPresets: List<Float>,
  onSpeedChange: (Float) -> Unit,
  onAddSpeedPreset: (Float) -> Unit,
  onRemoveSpeedPreset: (Float) -> Unit,
  onResetPresets: () -> Unit,
  onMakeDefault: (Float) -> Unit,
  onResetDefault: () -> Unit,
  onDismissRequest: () -> Unit,
  speedControlEnabled: Boolean = true,
  pitchCorrectionEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val speedHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(0.05f, 4f, landmarks = listOf(1f))
  val actionHaptics = app.gyrolet.mpvrx.ui.utils.rememberAppHaptics()
  val adjustSpeed: (Float) -> Unit = { target ->
    if (kotlin.math.abs(target - speed) > 0.001f) {
      onSpeedChange(target)
      speedHaptics.move(speed, target)
    }
  }
  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.btn_label_speed),
    leadingIcon = Icons.RoundedFilled.Speed,
    customMaxWidth = 520.dp,
    showDragHandle = false,
    actions = {
      PlayerSheetAction(
        icon = Icons.RoundedFilled.Bookmark,
        label = stringResource(R.string.player_sheets_speed_make_default),
        onClick = { onMakeDefault(speed) },
        enabled = speedControlEnabled,
      )
      PlayerSheetAction(
        icon = Icons.RoundedFilled.Restore,
        label = stringResource(R.string.generic_reset),
        onClick = {
          onResetDefault()
          onResetPresets()
          onSpeedChange(1f)
        },
        enabled = speedControlEnabled,
      )
    },
  ) {
    Column(
      modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackValue = maxWidth < 240.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val valueLabel: @Composable () -> Unit = {
          Text(
            text = "${speed.toFixed(2)}x",
            modifier = Modifier.widthIn(min = 48.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
        }
        Column {
          if (stackValue) valueLabel()
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (!stackValue) valueLabel()
            RepeatingIconButton(
              onClick = { adjustSpeed((speed - 0.05f).coerceAtLeast(0.05f)) },
              enabled = speedControlEnabled,
              modifier = Modifier.size(48.dp),
            ) {
              Icon(Icons.RoundedFilled.Remove, stringResource(R.string.player_speed_decrease), modifier = Modifier.size(22.dp))
            }
            Slider(
              value = speed,
              onValueChange = { adjustSpeed((it * 20).roundToInt() / 20f) },
              valueRange = 0.1f..4f,
              enabled = speedControlEnabled,
              modifier = Modifier.weight(1f)
                .tvFocusHighlight(MaterialTheme.shapes.small, enabled = speedControlEnabled),
            )
            RepeatingIconButton(
              onClick = { adjustSpeed((speed + 0.05f).coerceAtMost(4f)) },
              enabled = speedControlEnabled,
              modifier = Modifier.size(48.dp),
            ) {
              Icon(Icons.RoundedFilled.Add, stringResource(R.string.player_speed_increase), modifier = Modifier.size(22.dp))
            }
          }
        }
      }

      // Chips and Add/Remove Button
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val defaultPresets =
          remember {
            listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)
          }

        LazyRow(
          modifier = Modifier.weight(1f),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(speedPresets, key = { it }) { presetSpeed ->
            val isDefault = defaultPresets.any { kotlin.math.abs(it - presetSpeed) < 0.001f }

            FilterChip(
              selected = kotlin.math.abs(presetSpeed - speed) < 0.01f,
              onClick = {
                if (kotlin.math.abs(presetSpeed - speed) > 0.001f) {
                  onSpeedChange(presetSpeed)
                  actionHaptics.selection(true)
                }
              },
              label = { Text("${presetSpeed.toFixed(2)}") },
              leadingIcon = null,
              enabled = speedControlEnabled,
              colors =
                androidx.compose.material3.FilterChipDefaults.filterChipColors(
                  containerColor = if (isDefault) playerSheetSelectionColor(false)
                    else MaterialTheme.colorScheme.tertiaryContainer,
                  labelColor = if (isDefault) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onTertiaryContainer,
                  selectedContainerColor = playerSheetSelectionColor(true),
                  selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
          }
        }

        // Add / Remove Preset Buttons
        val isCurrentSpeedSaved = speedPresets.any { kotlin.math.abs(it - speed) < 0.001f }
        val isDefaultPreset = defaultPresets.any { kotlin.math.abs(it - speed) < 0.001f }

        if (isCurrentSpeedSaved && !isDefaultPreset) {
          PlayerSheetAction(
            icon = Icons.RoundedFilled.Remove,
            label = stringResource(R.string.ui_remove),
            onClick = { onRemoveSpeedPreset(speed) },
            enabled = speedControlEnabled,
          )
        } else if (!isCurrentSpeedSaved) {
          PlayerSheetAction(
            icon = Icons.RoundedFilled.Add,
            label = stringResource(R.string.ui_add),
            onClick = { onAddSpeedPreset(speed) },
            enabled = speedControlEnabled,
          )
        }
      }

      val audioPreferences = koinInject<AudioPreferences>()
      val pitchCorrection by audioPreferences.audioPitchCorrection.collectAsState()
      SwitchPreference(
        value = pitchCorrection,
        enabled = pitchCorrectionEnabled,
        onValueChange = { newValue ->
          audioPreferences.audioPitchCorrection.set(newValue)
          PlaybackSession.setPropertyBoolean("audio-pitch-correction", newValue)
        },
        title = { Text(stringResource(R.string.pref_audio_pitch_correction_title)) },
        summary = { Text(stringResource(R.string.pref_audio_pitch_correction_summary)) },
        titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        summaryStyle = MaterialTheme.typography.bodySmall,
        compact = true,
      )
    }
  }
}

fun Float.toFixed(precision: Int = 1): Float {
  val factor = 10.0f.pow(precision)
  return (this * factor).roundToInt() / factor
}
