/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.CustomFilterPreset
import app.gyrolet.mpvrx.preferences.DecoderPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.presentation.components.ExpandableCard
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.FilterPreset
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.controls.CARDS_MAX_WIDTH
import app.gyrolet.mpvrx.ui.player.controls.panelCardsColors
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import org.koin.compose.koinInject

/** The six adjustable filter values a preset stores. */
private data class FilterValues(
  val brightness: Int,
  val saturation: Int,
  val contrast: Int,
  val gamma: Int,
  val hue: Int,
  val sharpness: Int,
)

private val FilterPreset.filterValues: FilterValues
  get() =
    FilterValues(
      brightness = brightness,
      saturation = saturation,
      contrast = contrast,
      gamma = gamma,
      hue = hue,
      sharpness = sharpness,
    )

private val CustomFilterPreset.filterValues: FilterValues
  get() =
    FilterValues(
      brightness = brightness,
      saturation = saturation,
      contrast = contrast,
      gamma = gamma,
      hue = hue,
      sharpness = sharpness,
    )

/**
 * Writes the values to both the persisted preferences and the live mpv properties, so the
 * sliders, the preset chips and the next playback can never disagree.
 */
private fun applyFilterValues(
  decoderPreferences: DecoderPreferences,
  values: FilterValues,
) {
  decoderPreferences.brightnessFilter.set(values.brightness)
  decoderPreferences.saturationFilter.set(values.saturation)
  decoderPreferences.contrastFilter.set(values.contrast)
  decoderPreferences.gammaFilter.set(values.gamma)
  decoderPreferences.hueFilter.set(values.hue)
  decoderPreferences.sharpnessFilter.set(values.sharpness)

  PlaybackSession.setPropertyInt("brightness", values.brightness)
  PlaybackSession.setPropertyInt("saturation", values.saturation)
  PlaybackSession.setPropertyInt("contrast", values.contrast)
  PlaybackSession.setPropertyInt("gamma", values.gamma)
  PlaybackSession.setPropertyInt("hue", values.hue)
  PlaybackSession.setPropertyInt("sharpen", values.sharpness)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoSettingsFilterPresetsCard(modifier: Modifier = Modifier) {
  val decoderPreferences = koinInject<DecoderPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val presetOptions = setOf("brightness", "saturation", "contrast", "gamma", "hue", "sharpen")
  val presetsEnabled = presetOptions.none(configOwnedOptions::contains)
  var isExpanded by remember { mutableStateOf(true) }

  // Collect current filter values
  val brightness by decoderPreferences.brightnessFilter.collectAsState()
  val saturation by decoderPreferences.saturationFilter.collectAsState()
  val contrast by decoderPreferences.contrastFilter.collectAsState()
  val gamma by decoderPreferences.gammaFilter.collectAsState()
  val hue by decoderPreferences.hueFilter.collectAsState()
  val sharpness by decoderPreferences.sharpnessFilter.collectAsState()
  val customPresets by decoderPreferences.customFilterPresets.collectAsState()

  val currentValues =
    FilterValues(
      brightness = brightness,
      saturation = saturation,
      contrast = contrast,
      gamma = gamma,
      hue = hue,
      sharpness = sharpness,
    )

  // Match on the current filter values. A user preset is reported on its own because it is
  // the deliberate choice; the built ins only light up when nothing of the user's matches.
  // FilterPreset.NONE already covers the all-zero case.
  val activeCustomPreset = customPresets.firstOrNull { it.filterValues == currentValues }
  val activeBuiltInPreset =
    if (activeCustomPreset != null) {
      null
    } else {
      FilterPreset.entries.firstOrNull { it.filterValues == currentValues }
    }

  // The preset being renamed, or null while a brand new preset is being named.
  var presetBeingNamed by remember { mutableStateOf<CustomFilterPreset?>(null) }
  var isNamingPreset by remember { mutableStateOf(false) }
  var presetPendingDeletion by remember { mutableStateOf<CustomFilterPreset?>(null) }
  var nameDraft by remember { mutableStateOf("") }
  var nameErrorRes by remember { mutableStateOf<Int?>(null) }
  var presetMenuOwner by remember { mutableStateOf<CustomFilterPreset?>(null) }

  fun commitName(
    rawName: String,
  ) {
    val cleanName = CustomFilterPreset.normalizeName(rawName)
    if (cleanName.isEmpty()) {
      nameErrorRes = R.string.filter_preset_name_empty
      return
    }
    val renamed = presetBeingNamed
    // Renaming keeps its own slot and its own values; a new preset must not silently
    // overwrite an existing one that happens to share the name.
    val clash = customPresets.firstOrNull { it.hasName(cleanName) && it.name != renamed?.name }
    if (clash != null) {
      nameErrorRes = R.string.filter_preset_name_taken
      return
    }

    val updated =
      if (renamed != null) {
        customPresets.map { if (it.name == renamed.name) renamed.copy(name = cleanName) else it }
      } else {
        customPresets +
          CustomFilterPreset(
            name = cleanName,
            brightness = brightness,
            saturation = saturation,
            contrast = contrast,
            gamma = gamma,
            hue = hue,
            sharpness = sharpness,
          )
      }
    decoderPreferences.customFilterPresets.set(updated)

    isNamingPreset = false
    presetBeingNamed = null
    nameDraft = ""
    nameErrorRes = null
  }

  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = { isExpanded = !isExpanded },
    title = {
      Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.medium),
      ) {
        Icon(Icons.RoundedFilled.AutoAwesome, null)
        Text(stringResource(R.string.ui_filter_presets))
      }
    },
    colors = panelCardsColors(),
    modifier = modifier.widthIn(max = CARDS_MAX_WIDTH),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilterPreset.entries.forEach { preset ->
          FilterChip(
            selected = activeBuiltInPreset == preset,
            enabled = presetsEnabled,
            onClick = { applyFilterValues(decoderPreferences, preset.filterValues) },
            label = { Text(stringResource(preset.displayNameRes)) },
            leadingIcon = null,
          )
        }
      }

      // Show description for selected preset
      activeBuiltInPreset?.let { preset ->
        if (preset.descriptionRes != 0) {
          Text(
            text = stringResource(preset.descriptionRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.filter_preset_custom_section),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
          onClick = {
            nameDraft = ""
            nameErrorRes = null
            presetBeingNamed = null
            isNamingPreset = true
          },
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Text(
            text = stringResource(R.string.filter_preset_save_current),
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }

      if (customPresets.isEmpty()) {
        Text(
          text = stringResource(R.string.filter_preset_custom_empty),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          customPresets.forEach { preset ->
            val menuExpanded = presetMenuOwner?.name == preset.name
            InputChip(
              selected = activeCustomPreset?.name == preset.name,
              enabled = presetsEnabled,
              onClick = { applyFilterValues(decoderPreferences, preset.filterValues) },
              label = {
                Text(
                  text = preset.name,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              trailingIcon = {
                Box {
                  IconButton(
                    onClick = { presetMenuOwner = if (menuExpanded) null else preset },
                    modifier = Modifier.size(24.dp),
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.MoreVert,
                      contentDescription = stringResource(R.string.filter_preset_menu_description),
                      modifier = Modifier.size(16.dp),
                    )
                  }
                  DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { presetMenuOwner = null },
                  ) {
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.filter_preset_rename)) },
                      leadingIcon = { Icon(Icons.RoundedFilled.Edit, null) },
                      onClick = {
                        presetMenuOwner = null
                        nameDraft = preset.name
                        nameErrorRes = null
                        presetBeingNamed = preset
                        isNamingPreset = true
                      },
                    )
                    DropdownMenuItem(
                      text = { Text(stringResource(R.string.filter_preset_delete)) },
                      leadingIcon = { Icon(Icons.RoundedFilled.Delete, null) },
                      onClick = {
                        presetMenuOwner = null
                        presetPendingDeletion = preset
                      },
                    )
                  }
                }
              },
            )
          }
        }
      }
    }
  }

  if (isNamingPreset) {
    FilterPresetNameDialog(
      title =
        stringResource(
          if (presetBeingNamed == null) R.string.filter_preset_save_title else R.string.filter_preset_rename_title,
        ),
      initialName = nameDraft,
      errorText = nameErrorRes?.let { stringResource(it) },
      onNameChange = {
        nameDraft = it
        nameErrorRes = null
      },
      onConfirm = ::commitName,
      onDismiss = {
        isNamingPreset = false
        presetBeingNamed = null
        nameDraft = ""
        nameErrorRes = null
      },
    )
  }

  presetPendingDeletion?.let { preset ->
    ConfirmDialog(
      title = stringResource(R.string.filter_preset_delete_title),
      subtitle = stringResource(R.string.filter_preset_delete_message, preset.name),
      onConfirm = {
        decoderPreferences.customFilterPresets.set(customPresets.filterNot { it.name == preset.name })
        presetPendingDeletion = null
      },
      onCancel = { presetPendingDeletion = null },
    )
  }
}

@Composable
private fun FilterPresetNameDialog(
  title: String,
  initialName: String,
  errorText: String?,
  onNameChange: (String) -> Unit,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = initialName,
        onValueChange = { onNameChange(it.take(CustomFilterPreset.MAX_NAME_LENGTH)) },
        singleLine = true,
        isError = errorText != null,
        supportingText = { errorText?.let { message -> Text(message) } },
        label = { Text(stringResource(R.string.filter_preset_name_label)) },
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(initialName) }) { Text(stringResource(R.string.filter_preset_save)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}
