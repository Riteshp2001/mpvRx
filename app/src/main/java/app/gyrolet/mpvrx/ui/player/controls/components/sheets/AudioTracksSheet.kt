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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioChannels
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.ReplayGainMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.runtime.collectAsState
import app.gyrolet.mpvrx.ui.player.AudioEngineKind
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetAction
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSectionHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.player.TrackNode
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import kotlinx.collections.immutable.ImmutableList
import org.koin.compose.koinInject
import kotlin.math.roundToInt

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
  val context = LocalContext.current
  val audioPreferences = koinInject<AudioPreferences>()
  val audioChannels by audioPreferences.audioChannels.collectAsState()
  val session by PlaybackSession.state.collectAsState()
  val audio by PlaybackSession.audioState.collectAsState()
  val exoPlayer = session.engine == AudioEngineKind.ExoPlayer
  val processingAvailable = !exoPlayer || !audio.ready || !audio.output.processingBypassed
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
          if (exoPlayer) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Spacer(modifier = Modifier.weight(1f))
              if (onOpenEqualizerSheet != null) {
                PlayerSheetAction(icon = Icons.RoundedFilled.Equalizer, label = stringResource(R.string.btn_label_equalizer),
                  onClick = onOpenEqualizerSheet, enabled = processingAvailable)
              }
            }
          } else AddTrackRow(
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
        if (exoPlayer) {
          item(key = "exo_output_controls") {
            val preferAtmos by audioPreferences.preferDolbyAtmos.collectAsState()
            val spatial by audioPreferences.spatialAudio.collectAsState()
            val skipSilence by audioPreferences.skipSilence.collectAsState()
            val replayGain by audioPreferences.replayGain.collectAsState()
            val crossfade by audioPreferences.crossfadeDurationMs.collectAsState()
            var fadeSeconds by remember(crossfade) { mutableFloatStateOf((crossfade / 1000f).coerceIn(0f, 12f)) }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (audio.output.dolbyAtmosSupported) {
                  FilterChip(
                    selected = preferAtmos,
                    onClick = { audioPreferences.preferDolbyAtmos.set(!preferAtmos) },
                    label = { Text(stringResource(R.string.pref_audio_dolby_atmos)) },
                    leadingIcon = { Icon(Icons.RoundedFilled.AutoAwesome, contentDescription = null) },
                  )
                }
                if (audio.output.spatializationAvailable) {
                  FilterChip(
                    selected = spatial && audio.output.spatializationEnabled,
                    onClick = {
                      if (!audio.output.spatializationEnabled) {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)) }
                      } else audioPreferences.spatialAudio.set(!spatial)
                    },
                    label = { Text(stringResource(R.string.pref_audio_spatial)) },
                    leadingIcon = { Icon(Icons.RoundedFilled.Headset, contentDescription = null) },
                  )
                }
              }
              if (audio.output.spatializationAvailable && !audio.output.spatializationEnabled) {
                Text(stringResource(R.string.audio_spatial_system_disabled), style = MaterialTheme.typography.bodySmall)
              }
              Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                  .toggleable(skipSilence, enabled = processingAvailable, role = Role.Switch,
                    onValueChange = audioPreferences.skipSilence::set),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(stringResource(R.string.pref_audio_skip_silence), modifier = Modifier.weight(1f))
                Switch(skipSilence, onCheckedChange = null, enabled = processingAvailable)
              }
              Text(stringResource(R.string.pref_audio_replay_gain), style = MaterialTheme.typography.titleSmall)
              FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayGainMode.entries.forEach { mode ->
                  FilterChip(
                    selected = replayGain == mode,
                    enabled = processingAvailable,
                    onClick = {
                      audioPreferences.replayGain.set(mode)
                      if (mode != ReplayGainMode.Off) audioPreferences.volumeNormalization.set(false)
                    },
                    label = { Text(stringResource(when (mode) {
                      ReplayGainMode.Off -> R.string.generic_disabled
                      ReplayGainMode.Track -> R.string.audio_replay_gain_track
                      ReplayGainMode.Album -> R.string.audio_replay_gain_album
                    })) },
                  )
                }
              }
              Text(stringResource(R.string.pref_audio_crossfade), style = MaterialTheme.typography.titleSmall)
              Text(if (fadeSeconds < 1f) stringResource(R.string.audio_gapless)
                else stringResource(R.string.pref_audio_crossfade_seconds, fadeSeconds.roundToInt()),
                style = MaterialTheme.typography.bodySmall)
              Slider(
                value = fadeSeconds,
                onValueChange = { fadeSeconds = it },
                onValueChangeFinished = { audioPreferences.crossfadeDurationMs.set(fadeSeconds.roundToInt() * 1000) },
                valueRange = 0f..12f, steps = 11,
                enabled = processingAvailable && session.currentItem?.audiobook == null,
              )
            }
          }
        }
        if (embeddedTracks.isNotEmpty()) {
          item(key = "embedded_audio_tracks_header") {
            PlayerSheetSectionHeader(stringResource(R.string.player_sheets_embedded_audio_tracks))
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
            PlayerSheetSectionHeader(stringResource(R.string.player_sheets_external_audio_tracks))
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
          Column(modifier = Modifier.fillMaxWidth()) {
            if (exoPlayer && !processingAvailable) {
              Text(stringResource(R.string.audio_output_preserved), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            PlayerSheetSectionHeader(stringResource(R.string.pref_audio_channels))
            FlowRow(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              AudioChannels.entries.forEach {
                FilterChip(
                  selected = audioChannels == it,
                  enabled = processingAvailable && (exoPlayer || if (it == AudioChannels.ReverseStereo) reverseStereoEnabled else audioChannelsEnabled),
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
                .toggleable(value = volumeNormalization, enabled = (exoPlayer || audioEffectsEnabled) && processingAvailable, role = Role.Switch,
                  onValueChange = { enabled ->
                    audioPreferences.volumeNormalization.set(enabled)
                    if (enabled && exoPlayer) audioPreferences.replayGain.set(ReplayGainMode.Off)
                  })
                .padding(horizontal = 20.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.pref_audio_volume_normalization_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
              Switch(checked = volumeNormalization, onCheckedChange = null, enabled = (exoPlayer || audioEffectsEnabled) && processingAvailable)
            }
            Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .toggleable(value = drcEnabled, enabled = (exoPlayer || audioEffectsEnabled) && processingAvailable, role = Role.Switch,
                  onValueChange = audioPreferences.drcEnabled::set)
                .padding(horizontal = 20.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(stringResource(R.string.pref_audio_drc_title), modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge)
              Switch(checked = drcEnabled, onCheckedChange = null, enabled = (exoPlayer || audioEffectsEnabled) && processingAvailable)
            }
          }
        }
      }
  }
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
  val haptics = rememberAppHaptics()
  val reducedMotion = AppMotion.playerReducedMotion()
  val containerColor by animateColorAsState(
    targetValue = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isSelected) 0.35f else 0f),
    animationSpec = if (reducedMotion) snap() else AppMotion.Effect.Color,
    label = "audioTrackSelection",
  )
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .padding(horizontal = 8.dp, vertical = 2.dp)
        .background(containerColor, MaterialTheme.shapes.medium)
        .tvFocusHighlight(MaterialTheme.shapes.medium, enabled = enabled)
        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
          onClick()
          if (!isSelected) haptics.selection(true)
        }
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    RadioButton(
      selected = isSelected,
      onClick = null,
      enabled = enabled,
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
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
