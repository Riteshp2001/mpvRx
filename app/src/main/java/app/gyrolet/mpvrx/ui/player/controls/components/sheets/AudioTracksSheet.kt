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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.ui.liquidglass.LocalKyantPlayerBackdrop
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSectionHeader
import app.gyrolet.mpvrx.ui.components.IconSwitch
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.collections.immutable.ImmutableList
import org.koin.compose.koinInject
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioTracksSheet(
  tracks: ImmutableList<TrackNode>,
  onSelect: (TrackNode) -> Unit,
  onAddAudioTrack: () -> Unit,
  onOpenDelayPanel: () -> Unit,
  onOpenEqualizerSheet: (() -> Unit)? = null,
  onDismissRequest: () -> Unit,
  delayControlEnabled: Boolean = true,
  equalizerControlEnabled: Boolean = true,
  audioChannelsEnabled: Boolean = true,
  reverseStereoEnabled: Boolean = true,
  audioEffectsEnabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val audioPreferences = koinInject<AudioPreferences>()
  val audioChannels by audioPreferences.audioChannels.collectAsState()
  val initialFocusRequester = rememberTvInitialFocusRequester(tracks.isNotEmpty())
  val initialTrackId = remember(tracks) { tracks.firstOrNull { it.isSelected }?.id ?: tracks.firstOrNull()?.id }
  val (embeddedTracks, externalTracks) =
    remember(tracks) {
      tracks.partition { track -> track.external != true }
    }

  PlayerSheet(
    onDismissRequest = onDismissRequest,
    title = stringResource(R.string.ui_audio_tab),
  ) {
      LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
      ) {
        item(key = "add_audio_track") {
          AddTrackRow(
            title = stringResource(R.string.player_sheets_add_ext_audio),
            onClick = onAddAudioTrack,
            actions = {
              if (onOpenEqualizerSheet != null) {
                PlayerSheetAction(
                  icon = Icons.RoundedFilled.Equalizer,
                  label = stringResource(R.string.btn_label_equalizer),
                  onClick = onOpenEqualizerSheet,
                  enabled = equalizerControlEnabled,
                )
              }
              PlayerSheetAction(
                icon = Icons.RoundedFilled.AvTimer,
                label = stringResource(R.string.player_sheets_audio_delay_card_title),
                onClick = onOpenDelayPanel,
                enabled = delayControlEnabled,
              )
            },
          )
        }
        if (embeddedTracks.isNotEmpty()) {
          item(key = "embedded_audio_tracks_header") {
            PlayerSheetSectionHeader(stringResource(R.string.player_sheets_embedded_audio_tracks))
          }
        }
        itemsIndexed(embeddedTracks, key = { _, track -> track.id }) { index, track ->
          AudioTrackCard(
            track = track,
            isSelected = track.isSelected,
            onClick = { onSelect(track) },
            groupIndex = index,
            groupSize = embeddedTracks.size,
            modifier = if (track.id == initialTrackId) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
          )
        }
        if (externalTracks.isNotEmpty()) {
          item(key = "external_audio_tracks_header") {
            PlayerSheetSectionHeader(stringResource(R.string.player_sheets_external_audio_tracks))
          }
        }
        itemsIndexed(externalTracks, key = { _, track -> track.id }) { index, track ->
          AudioTrackCard(
            track = track,
            isSelected = track.isSelected,
            onClick = { onSelect(track) },
            groupIndex = index,
            groupSize = externalTracks.size,
            modifier = if (track.id == initialTrackId) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
          )
        }
        item {
          Column(modifier = Modifier.fillMaxWidth()) {
            PlayerSheetSectionHeader(stringResource(R.string.pref_audio_channels))
            FlowRow(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              AudioChannels.entries.forEach {
                FilterChip(
                  selected = audioChannels == it,
                  enabled = if (it == AudioChannels.ReverseStereo) reverseStereoEnabled else audioChannelsEnabled,
                  onClick = {
                    audioPreferences.audioChannels.set(it)
                    if (it == AudioChannels.ReverseStereo) {
                      PlaybackSession.setPropertyString(AudioChannels.AutoSafe.property, AudioChannels.AutoSafe.value)
                    } else {
                      PlaybackSession.setPropertyString(it.property, it.value)
                    }
                  },
                  label = { Text(text = stringResource(id = it.title)) },
                  leadingIcon = null,
                )
              }
            }

            val volumeNormalization by audioPreferences.volumeNormalization.collectAsState()
            val drcEnabled by audioPreferences.drcEnabled.collectAsState()

            PlayerSheetSectionHeader(stringResource(R.string.pref_audio_effects))
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = volumeNormalization, enabled = audioEffectsEnabled, role = Role.Switch,
                  onValueChange = audioPreferences.volumeNormalization::set)
                .padding(horizontal = 20.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.pref_audio_volume_normalization_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
              IconSwitch(checked = volumeNormalization, onCheckedChange = null, enabled = audioEffectsEnabled)
            }
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = drcEnabled, enabled = audioEffectsEnabled, role = Role.Switch,
                  onValueChange = audioPreferences.drcEnabled::set)
                .padding(horizontal = 20.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.pref_audio_drc_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
              IconSwitch(checked = drcEnabled, onCheckedChange = null, enabled = audioEffectsEnabled)
            }
          }
        }
      }
  }
}

@Composable
fun AudioTrackCard(
  track: TrackNode,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  groupIndex: Int? = null,
  groupSize: Int? = null,
) {
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.playerReducedMotion()
  val shape =
    if (groupIndex != null && groupSize != null && groupSize > 0) {
      rememberConnectedShape(
        index = groupIndex,
        itemCount = groupSize,
        layout = ConnectedLayout.Vertical,
      )
    } else {
      MaterialTheme.shapes.medium
    }
  val containerColor by animateColorAsState(
    targetValue =
      if (isSelected) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
      } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
      },
    animationSpec = if (reducedMotion) snap() else AppMotion.Effect.Color,
    label = "audioTrackSelection",
  )
  val selectionBorder =
    BorderStroke(
      2.dp,
      Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
      ),
    )
  val badges = audioTrackBadges(track)
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .tvFocusHighlight(shape, enabled = enabled)
        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
          onClick()
          if (!isSelected) haptics.selection(true)
        },
    shape = shape,
    color = containerColor,
    border = if (isSelected) selectionBorder else null,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(
        selected = isSelected,
        onClick = null,
        enabled = enabled,
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
          text = getTrackTitle(track),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
          color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        TrackBadgeFlow(badges = badges, selected = isSelected)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioTrackRow(
  title: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  details: String? = null,
  trailing: (@Composable () -> Unit)? = null,
  groupIndex: Int? = null,
  groupSize: Int? = null,
) {
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.playerReducedMotion()
  val isGrouped = groupIndex != null && groupSize != null && groupSize > 0
  val shape =
    if (isGrouped) {
      rememberConnectedShape(
        index = requireNotNull(groupIndex),
        itemCount = requireNotNull(groupSize),
        layout = ConnectedLayout.Vertical,
      )
    } else {
      MaterialTheme.shapes.medium
    }
  val isFrosted = LocalKyantPlayerBackdrop.current != null
  val containerColor by animateColorAsState(
    targetValue =
      if (isSelected) {
        if (isFrosted) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.secondaryContainer
      } else {
        if (isFrosted) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh
      },
    animationSpec = if (reducedMotion) snap() else AppMotion.Effect.Color,
    label = "genericTrackSelection",
  )
  val selectionBorder =
    BorderStroke(
      2.dp,
      Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
      ),
    )
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (isGrouped) Modifier else Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        .tvFocusHighlight(shape, enabled = enabled)
        .clip(shape)
        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
          onClick()
          if (!isSelected) haptics.selection(true)
        },
    shape = shape,
    color = containerColor,
    border = if (isSelected) selectionBorder else null,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      RadioButton(selected = isSelected, onClick = null, enabled = enabled)
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = title,
          style = if (isSelected) MaterialTheme.typography.bodyLargeEmphasized else MaterialTheme.typography.bodyLarge,
          color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        )
        details?.let { value ->
          Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      trailing?.invoke()
    }
  }
}

@Composable
private fun audioTrackBadges(track: TrackNode): List<TrackBadge> {
  val defaultLabel = stringResource(R.string.theme_default)
  val forcedLabel = stringResource(R.string.track_badge_forced)
  val externalLabel = stringResource(R.string.track_badge_external)
  val descriptiveLabel = stringResource(R.string.track_badge_descriptive)
  val monoLabel = stringResource(R.string.pref_audio_channels_mono)
  val stereoLabel = stringResource(R.string.pref_audio_channels_stereo)
  return remember(track, defaultLabel, forcedLabel, externalLabel, descriptiveLabel, monoLabel, stereoLabel) {
    buildList {
      track.ytdlFormatId?.let { add(TrackBadge("#$it")) }
      track.effectiveLang?.takeIf(String::isNotBlank)?.let { add(TrackBadge(it.uppercase(Locale.ROOT))) }
      (track.codec?.takeIf(String::isNotBlank) ?: track.codecDesc?.takeIf(String::isNotBlank))
        ?.let { add(TrackBadge(it.uppercase(Locale.ROOT))) }
      track.demuxChannels?.takeIf(String::isNotBlank)?.let { add(TrackBadge(it)) }
        ?: track.audioChannels?.takeIf { it > 0 }?.let { channels ->
          val label =
            when (channels) {
              1L -> monoLabel
              2L -> stereoLabel
              6L -> "5.1"
              8L -> "7.1"
              else -> "${channels}ch"
            }
          add(TrackBadge(label))
        }
      track.effectiveBitrate
        ?.takeIf { it > 0L }
        ?.let { bitsPerSecond -> add(TrackBadge("${bitsPerSecond / 1_000L} kbps")) }
      if (track.default == true) add(TrackBadge(defaultLabel))
      if (track.forced == true) add(TrackBadge(forcedLabel))
      if (track.external == true) add(TrackBadge(externalLabel))
      if (track.visualImpaired == true) add(TrackBadge(descriptiveLabel))
    }.distinctBy(TrackBadge::text)
  }
}
