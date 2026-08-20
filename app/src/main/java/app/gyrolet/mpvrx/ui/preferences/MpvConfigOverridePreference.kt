/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import me.zhanghai.compose.preference.Preference

@Composable
internal fun MpvConfigOverridePreference(
  preferences: AdvancedPreferences,
  modifier: Modifier = Modifier,
) {
  val storedValues by preferences.mpvConfOverrides.collectAsState()
  val selectedOptions = remember(storedValues) { MpvConfigOverride.resolveOptionNames(storedValues) }
  val selectedGroups = remember(selectedOptions) { MpvConfigOverride.groupsContaining(selectedOptions) }
  var showDialog by remember { mutableStateOf(false) }

  Preference(
    modifier = modifier,
    title = { Text(stringResource(R.string.pref_mpv_conf_overrides_title)) },
    summary = {
      Text(
        text =
          if (selectedOptions.isEmpty()) {
            stringResource(R.string.pref_mpv_conf_overrides_summary_app_owned)
          } else {
            stringResource(
              R.string.pref_mpv_conf_overrides_summary_config_owned,
              selectedGroups.size,
              selectedOptions.size,
            )
          },
        color = MaterialTheme.colorScheme.outline,
      )
    },
    onClick = { showDialog = true },
  )

  if (showDialog) {
    MpvConfigOverrideDialog(
      initialSelection = selectedOptions,
      hasMpvConfig = hasMeaningfulMpvConfig(preferences.mpvConf.get()),
      onDismiss = { showDialog = false },
      onSave = { selection ->
        preferences.mpvConfOverrides.set(selection)
        showDialog = false
      },
    )
  }
}

@Composable
private fun MpvConfigOverrideDialog(
  initialSelection: Set<String>,
  hasMpvConfig: Boolean,
  onDismiss: () -> Unit,
  onSave: (Set<String>) -> Unit,
) {
  var draftSelection by remember(initialSelection) { mutableStateOf(initialSelection) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.pref_mpv_conf_overrides_dialog_title)) },
    text = {
      Column {
        Text(
          text = stringResource(R.string.pref_mpv_conf_overrides_dialog_message),
          style = MaterialTheme.typography.bodyMedium,
        )
        if (!hasMpvConfig) {
          Text(
            text = stringResource(R.string.pref_mpv_conf_overrides_empty_config_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp),
          )
        }
        Text(
          text = stringResource(R.string.pref_mpv_conf_overrides_protected_message),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
          MpvConfigOverride.entries.forEach { override ->
            item(key = "group:${override.preferenceKey}") {
              val selectedCount = override.optionNames.count(draftSelection::contains)
              val toggleState =
                when (selectedCount) {
                  0 -> ToggleableState.Off
                  override.optionNames.size -> ToggleableState.On
                  else -> ToggleableState.Indeterminate
                }
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      draftSelection =
                        if (toggleState == ToggleableState.On) {
                          draftSelection - override.optionNames
                        } else {
                          draftSelection + override.optionNames
                        }
                    }.padding(top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.Top,
              ) {
                TriStateCheckbox(state = toggleState, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(override.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                  )
                  Text(
                    text = stringResource(override.summaryRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = stringResource(R.string.pref_mpv_conf_overrides_selected_count, selectedCount, override.optionNames.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp),
                  )
                }
              }
            }
            items(
              items = override.optionNames.sorted(),
              key = { optionName -> "${override.preferenceKey}:$optionName" },
            ) { optionName ->
              val checked = optionName in draftSelection
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable {
                      draftSelection =
                        if (checked) draftSelection - optionName else draftSelection + optionName
                    }.padding(start = 32.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = optionName,
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
            item(key = "divider:${override.preferenceKey}") {
              if (override != MpvConfigOverride.entries.last()) HorizontalDivider()
            }
          }
          if (draftSelection.isNotEmpty()) {
            item {
              TextButton(
                onClick = { draftSelection = emptySet() },
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(stringResource(R.string.pref_mpv_conf_overrides_reset))
              }
            }
          }
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(draftSelection) }) {
        Text(stringResource(R.string.ui_save))
      }
    },
  )
}

private fun hasMeaningfulMpvConfig(content: String): Boolean =
  content.lineSequence().any { line ->
    val value = line.trim()
    value.isNotEmpty() && !value.startsWith("#") && !value.startsWith(";")
  }

@StringRes
private fun MpvConfigOverride.titleRes(): Int =
  when (this) {
    MpvConfigOverride.RENDERER -> R.string.pref_mpv_conf_override_renderer
    MpvConfigOverride.DECODER -> R.string.pref_mpv_conf_override_decoder
    MpvConfigOverride.HDR_AND_SHADERS -> R.string.pref_mpv_conf_override_hdr_shaders
    MpvConfigOverride.VIDEO_FILTERS -> R.string.pref_mpv_conf_override_video_filters
    MpvConfigOverride.VIDEO_GEOMETRY -> R.string.pref_mpv_conf_override_video_geometry
    MpvConfigOverride.AUDIO_OUTPUT -> R.string.pref_mpv_conf_override_audio_output
    MpvConfigOverride.AUDIO_FILTERS -> R.string.pref_mpv_conf_override_audio_filters
    MpvConfigOverride.SUBTITLE_LOADING -> R.string.pref_mpv_conf_override_subtitle_loading
    MpvConfigOverride.SUBTITLE_STYLE -> R.string.pref_mpv_conf_override_subtitle_style
    MpvConfigOverride.PLAYBACK_TIMING -> R.string.pref_mpv_conf_override_playback_timing
    MpvConfigOverride.NETWORK_BUFFERING -> R.string.pref_mpv_conf_override_network_buffering
    MpvConfigOverride.YTDLP -> R.string.pref_mpv_conf_override_ytdlp
    MpvConfigOverride.OSD -> R.string.pref_mpv_conf_override_osd
  }

@StringRes
private fun MpvConfigOverride.summaryRes(): Int =
  when (this) {
    MpvConfigOverride.RENDERER -> R.string.pref_mpv_conf_override_renderer_summary
    MpvConfigOverride.DECODER -> R.string.pref_mpv_conf_override_decoder_summary
    MpvConfigOverride.HDR_AND_SHADERS -> R.string.pref_mpv_conf_override_hdr_shaders_summary
    MpvConfigOverride.VIDEO_FILTERS -> R.string.pref_mpv_conf_override_video_filters_summary
    MpvConfigOverride.VIDEO_GEOMETRY -> R.string.pref_mpv_conf_override_video_geometry_summary
    MpvConfigOverride.AUDIO_OUTPUT -> R.string.pref_mpv_conf_override_audio_output_summary
    MpvConfigOverride.AUDIO_FILTERS -> R.string.pref_mpv_conf_override_audio_filters_summary
    MpvConfigOverride.SUBTITLE_LOADING -> R.string.pref_mpv_conf_override_subtitle_loading_summary
    MpvConfigOverride.SUBTITLE_STYLE -> R.string.pref_mpv_conf_override_subtitle_style_summary
    MpvConfigOverride.PLAYBACK_TIMING -> R.string.pref_mpv_conf_override_playback_timing_summary
    MpvConfigOverride.NETWORK_BUFFERING -> R.string.pref_mpv_conf_override_network_buffering_summary
    MpvConfigOverride.YTDLP -> R.string.pref_mpv_conf_override_ytdlp_summary
    MpvConfigOverride.OSD -> R.string.pref_mpv_conf_override_osd_summary
  }
