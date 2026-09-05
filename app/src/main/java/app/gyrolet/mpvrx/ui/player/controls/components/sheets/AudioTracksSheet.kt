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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlinx.collections.immutable.ImmutableList
import org.koin.compose.koinInject

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

  PlayerSheet(onDismissRequest) {
    Column(modifier) {
      AddTrackRow(
        stringResource(R.string.player_sheets_add_ext_audio),
        onAddAudioTrack,
        actions = {
          if (onOpenEqualizerSheet != null) {
            IconButton(onClick = onOpenEqualizerSheet, enabled = equalizerControlEnabled) {
              Icon(Icons.RoundedFilled.Equalizer, stringResource(R.string.btn_label_equalizer))
            }
          }
          IconButton(onClick = onOpenDelayPanel, enabled = delayControlEnabled) {
            Icon(Icons.RoundedFilled.AvTimer, null)
          }
        },
      )

      LazyColumn {
        if (embeddedTracks.isNotEmpty()) {
          item(key = "embedded_audio_tracks_header") {
            AudioTrackSectionHeader(stringResource(R.string.player_sheets_embedded_audio_tracks))
          }
        }
        items(embeddedTracks, key = { it.id }) {
          AudioTrackRow(
            title = getTrackTitle(it),
            details = audioTrackDetails(it),
            isSelected = it.isSelected,
            onClick = { onSelect(it) },
            modifier = if (it.id == initialTrackId) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
          )
        }
        if (externalTracks.isNotEmpty()) {
          item(key = "external_audio_tracks_header") {
            AudioTrackSectionHeader(stringResource(R.string.player_sheets_external_audio_tracks))
          }
        }
        items(externalTracks, key = { it.id }) {
          AudioTrackRow(
            title = getTrackTitle(it),
            details = audioTrackDetails(it),
            isSelected = it.isSelected,
            onClick = { onSelect(it) },
            modifier = if (it.id == initialTrackId) Modifier.tvInitialFocus(initialFocusRequester) else Modifier,
          )
        }
        item {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.medium),
          ) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
            Text(
              text = stringResource(id = R.string.pref_audio_channels),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.smaller))
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
            ) {
              items(AudioChannels.entries, key = { it.name }) {
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

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
            Text(
              text = stringResource(id = R.string.pref_audio_effects),
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.smaller))
            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
            ) {
              item {
                FilterChip(
                  selected = volumeNormalization,
                  enabled = audioEffectsEnabled,
                  onClick = { audioPreferences.volumeNormalization.set(!volumeNormalization) },
                  label = { Text(text = stringResource(id = R.string.pref_audio_volume_normalization_title)) },
                  leadingIcon = null,
                )
              }
              item {
                FilterChip(
                  selected = drcEnabled,
                  enabled = audioEffectsEnabled,
                  onClick = { audioPreferences.drcEnabled.set(!drcEnabled) },
                  label = { Text(text = stringResource(id = R.string.pref_audio_drc_title)) },
                  leadingIcon = null,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AudioTrackSectionHeader(title: String) {
  Text(
    text = title,
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold,
  )
}

@Composable
fun AudioTrackRow(
  title: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  details: String? = null,
) {
  val isTelevision = DeviceFormFactor.isTelevision(LocalContext.current)
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(enabled = enabled)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = MaterialTheme.spacing.medium, vertical = MaterialTheme.spacing.extraSmall),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    RadioButton(
      selected = isSelected,
      onClick = if (isTelevision) null else onClick,
      enabled = enabled,
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
        fontStyle = if (isSelected) FontStyle.Italic else FontStyle.Normal,
      )
      details?.let { value ->
        Text(
          text = value,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private fun audioTrackDetails(track: TrackNode): String? {
  val codec = track.codecDesc?.takeIf(String::isNotBlank) ?: track.codec?.takeIf(String::isNotBlank)
  val bitrate =
    track.effectiveBitrate
      ?.takeIf { it > 0L }
      ?.let { bitsPerSecond -> "${bitsPerSecond / 1_000L} kbps" }
  return listOfNotNull(track.ytdlFormatId?.let { "#$it" }, codec, bitrate)
    .distinct()
    .joinToString(" • ")
    .takeIf(String::isNotBlank)
}
