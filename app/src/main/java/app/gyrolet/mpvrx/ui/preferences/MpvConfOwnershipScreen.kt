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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.MpvConfigOverride
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ExpandableCard
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * Dedicated page for choosing which mpv options are owned by the user's mpv.conf
 * instead of the in-app settings. Groups collapse to a checkbox + count so the
 * page stays scannable; expanding reveals the individual options.
 */
@Serializable
object MpvConfOwnershipScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backStack = LocalBackStack.current
    val preferences = koinInject<AdvancedPreferences>()
    val storedValues by preferences.mpvConfOverrides.collectAsState()
    val selectedOptions = remember(storedValues) { MpvConfigOverride.resolveOptionNames(storedValues) }
    val hasMpvConfig = remember { hasMeaningfulMpvConfig(preferences.mpvConf.get()) }
    var expandedGroup by rememberSaveable { mutableStateOf<String?>(null) }

    fun apply(selection: Set<String>) = preferences.mpvConfOverrides.set(selection)

    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.pref_mpv_conf_overrides_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          },
          navigationIcon = {
            IconButton(onClick = { backStack.popSafely() }) {
              Icon(Icons.RoundedFilled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        item(key = "header") {
          OwnershipSummaryCard(
            selectedCount = selectedOptions.size,
            hasMpvConfig = hasMpvConfig,
            onResetAll = { apply(emptySet()) },
          )
        }

        items(
          count = MpvConfigOverride.entries.size,
          key = { index -> MpvConfigOverride.entries[index].preferenceKey },
        ) { index ->
          val override = MpvConfigOverride.entries[index]
          OwnershipGroupCard(
            override = override,
            selectedOptions = selectedOptions,
            isExpanded = expandedGroup == override.preferenceKey,
            onExpand = { expanded ->
              expandedGroup = if (expanded) override.preferenceKey else null
            },
            onSelectionChange = ::apply,
          )
        }
      }
    }
  }
}

@Composable
private fun OwnershipSummaryCard(
  selectedCount: Int,
  hasMpvConfig: Boolean,
  onResetAll: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = MaterialTheme.shapes.large,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color =
          if (selectedCount > 0) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
        modifier = Modifier.size(52.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.RoundedFilled.Tune,
            contentDescription = null,
            tint =
              if (selectedCount > 0) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
            modifier = Modifier.size(26.dp),
          )
        }
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text =
            if (selectedCount > 0) {
              stringResource(R.string.pref_mpv_conf_ownership_active, selectedCount)
            } else {
              stringResource(R.string.pref_mpv_conf_ownership_inactive)
            },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )
        Text(
          text =
            if (hasMpvConfig) {
              stringResource(R.string.pref_mpv_conf_ownership_hint)
            } else {
              stringResource(R.string.pref_mpv_conf_overrides_empty_config_warning)
            },
          style = MaterialTheme.typography.bodySmall,
          color =
            if (hasMpvConfig) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.error
            },
        )
        if (selectedCount > 0) {
          TextButton(
            onClick = onResetAll,
            contentPadding = PaddingValues(0.dp),
          ) {
            Text(stringResource(R.string.pref_mpv_conf_overrides_reset))
          }
        }
      }
    }
  }
}

@Composable
private fun OwnershipGroupCard(
  override: MpvConfigOverride,
  selectedOptions: Set<String>,
  isExpanded: Boolean,
  onExpand: (Boolean) -> Unit,
  onSelectionChange: (Set<String>) -> Unit,
) {
  val selectedCount = override.optionNames.count(selectedOptions::contains)
  val toggleState =
    when (selectedCount) {
      0 -> ToggleableState.Off
      override.optionNames.size -> ToggleableState.On
      else -> ToggleableState.Indeterminate
    }

  ExpandableCard(
    isExpanded = isExpanded,
    onExpand = onExpand,
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (selectedCount > 0) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
          } else {
            MaterialTheme.colorScheme.surfaceContainerLow
          },
      ),
    title = { _ ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(
          state = toggleState,
          onClick = {
            onSelectionChange(
              if (toggleState == ToggleableState.On) {
                selectedOptions - override.optionNames
              } else {
                selectedOptions + override.optionNames
              },
            )
          },
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
          Text(
            text = stringResource(override.titleRes()),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = "$selectedCount/${override.optionNames.size}",
            style = MaterialTheme.typography.labelSmall,
            color =
              if (selectedCount > 0) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.outline
              },
          )
        }
      }
    },
    content = {
      Column {
        Text(
          text = stringResource(override.summaryRes()),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 6.dp),
        )
        override.optionNames.sorted().forEach { optionName ->
          val checked = optionName in selectedOptions
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onSelectionChange(
                  if (checked) selectedOptions - optionName else selectedOptions + optionName,
                )
              }
              .padding(vertical = 2.dp),
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
      }
    },
  )
}

internal fun hasMeaningfulMpvConfig(content: String): Boolean =
  content.lineSequence().any { line ->
    val value = line.trim()
    value.isNotEmpty() && !value.startsWith("#") && !value.startsWith(";")
  }

@StringRes
internal fun MpvConfigOverride.titleRes(): Int =
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
internal fun MpvConfigOverride.summaryRes(): Int =
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
