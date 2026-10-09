/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.panels

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.presentation.components.OutlinedNumericChooser
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.currentMpvConfigOverrideOptions
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun SubtitleDelayPanel(
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val preferences = koinInject<SubtitlesPreferences>()
  val configOwnedOptions = currentMpvConfigOverrideOptions()
  val delayEnabled = SUB_DELAY_PROPERTY !in configOwnedOptions
  val secondaryDelayEnabled = SECONDARY_SUB_DELAY_PROPERTY !in configOwnedOptions
  val speedEnabled = "sub-speed" !in configOwnedOptions

  /**
   * mpv keeps `sub-delay` and `secondary-sub-delay` as two independent properties, but
   * setupSubtitlesOptions initialises both from the same default. Writing only `sub-delay`
   * left the secondary track stuck on the startup value, so a secondary subtitle could never
   * be synced at all. Keep the two in lockstep, each one only while mpvRx still owns it.
   */
  fun setSubtitleDelay(
    seconds: Float,
  ) {
    if (delayEnabled) PlaybackSession.setPropertyDouble(SUB_DELAY_PROPERTY, seconds.toDouble())
    if (secondaryDelayEnabled) PlaybackSession.setPropertyDouble(SECONDARY_SUB_DELAY_PROPERTY, seconds.toDouble())
  }

  DraggablePanel(
    modifier = modifier,
    header = {
      SubtitleDelayTitle(onClose = onDismissRequest)
    },
  ) {
    val delay by PlaybackSession.propDouble[SUB_DELAY_PROPERTY].collectAsState()
    val delayFloat by remember { derivedStateOf { (delay ?: 0.0).toFloat() } }
    val speed by PlaybackSession.propDouble["sub-speed"].collectAsState()
    val speedFloat by remember { derivedStateOf { (speed ?: 1.0).toFloat() } }

    // We unwrap the card content here because DraggablePanel already provides the card
    SubtitleDelayCardContent(
      delay = delayFloat,
      onDelayChange = ::setSubtitleDelay,
      speed = speedFloat,
      onSpeedChange = { PlaybackSession.setPropertyDouble("sub-speed", it.toDouble()) },
      onApply = {
        if (delayEnabled) preferences.defaultSubDelay.set((delayFloat * 1000).roundToInt())
        val currentSpeed = speed ?: 1.0
        if (speedEnabled && currentSpeed in 0.1..10.0) preferences.defaultSubSpeed.set(currentSpeed.toFloat())
      },
      onReset = {
        setSubtitleDelay(preferences.defaultSubDelay.get() / 1000f)
        if (speedEnabled) PlaybackSession.setPropertyDouble("sub-speed", preferences.defaultSubSpeed.get().toDouble())
      },
      delayEnabled = delayEnabled,
      speedEnabled = speedEnabled,
    )
  }
}

private const val SUB_DELAY_PROPERTY = "sub-delay"
private const val SECONDARY_SUB_DELAY_PROPERTY = "secondary-sub-delay"

// Extracted content to avoid nested cards since DraggablePanel has a Card
@Composable
private fun SubtitleDelayCardContent(
  delay: Float,
  onDelayChange: (Float) -> Unit,
  speed: Float,
  onSpeedChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  delayEnabled: Boolean,
  speedEnabled: Boolean,
) {
  DelayCardContent(
    delay = delay,
    onDelayChange = onDelayChange,
    onApply = onApply,
    onReset = onReset,
    delayType = DelayType.Subtitle,
    delayControlEnabled = delayEnabled,
    actionsEnabled = delayEnabled || speedEnabled,
    extraSettings = {
      OutlinedNumericChooser(
        label = { Text(stringResource(R.string.player_sheets_sub_delay_card_speed)) },
        value = speed,
        onChange = onSpeedChange,
        max = 10f,
        step = 0.01f,
        min = 0.1f,
        increaseIcon = Icons.RoundedFilled.Add,
        decreaseIcon = Icons.RoundedFilled.Remove,
        valueFormatter = { "%.2f".format(it) },
        enabled = speedEnabled,
      )
    },
  )
}

@Suppress("LambdaParameterInRestartableEffect")
@Composable
fun DelayCardContent( // Renamed from DelayCard and removed the Card wrapper
  delay: Float,
  onDelayChange: (Float) -> Unit,
  onApply: () -> Unit,
  onReset: () -> Unit,
  delayType: DelayType,
  delayControlEnabled: Boolean = true,
  actionsEnabled: Boolean = true,
  extraSettings: @Composable ColumnScope.() -> Unit = {},
) {
  // Note: verticalScroll is now handled by DraggablePanel
  Column(
    Modifier.padding(MaterialTheme.spacing.medium),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    OutlinedNumericChooser(
      label = { Text(stringResource(R.string.player_sheets_sub_delay_card_delay)) },
      value = delay,
      onChange = onDelayChange,
      step = 0.1f,
      min = Float.NEGATIVE_INFINITY,
      max = Float.POSITIVE_INFINITY,
      suffix = {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_s),
        )
      },
      increaseIcon = Icons.RoundedFilled.Add,
      decreaseIcon = Icons.RoundedFilled.Remove,
      valueFormatter = { "%.1f".format(it) },
      enabled = delayControlEnabled,
    )
    Text(
      text = stringResource(R.string.player_sheets_delay_quick_sync_summary),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    QuickSyncOffsets(
      delay = delay,
      enabled = delayControlEnabled,
      onSelect = onDelayChange,
    )
    Column(
      modifier = Modifier.animateContentSize(),
    ) { extraSettings() }
    // true (heard -> spotted), false (spotted -> heard)
    var isDirectionPositive by remember { mutableStateOf<Boolean?>(null) }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      var timerStart by remember { mutableStateOf<Long?>(null) }
      var finalDelay by remember { mutableStateOf(delay) }
      LaunchedEffect(isDirectionPositive) {
        if (isDirectionPositive == null) {
          onDelayChange(finalDelay)
          return@LaunchedEffect
        }
        finalDelay = delay
        val startTime = System.currentTimeMillis()
        timerStart = startTime
        val startingDelay: Float = finalDelay
        while (isDirectionPositive != null && timerStart != null) {
          val elapsed = System.currentTimeMillis() - startTime
          val direction = isDirectionPositive ?: break
          finalDelay = startingDelay + (if (direction) elapsed / 1000f else -elapsed / 1000f)
          // Arbitrary delay of 20ms
          delay(20)
        }
      }
      Button(
        onClick = {
          isDirectionPositive = if (isDirectionPositive == null) delayType == DelayType.Audio else null
        },
        modifier = Modifier.weight(1f),
        enabled = delayControlEnabled && isDirectionPositive != (delayType == DelayType.Audio),
      ) {
        Text(
          stringResource(
            if (delayType == DelayType.Audio) {
              R.string.player_sheets_sub_delay_audio_sound_heard
            } else {
              R.string.player_sheets_sub_delay_subtitle_voice_heard
            },
          ),
        )
      }
      Button(
        onClick = {
          isDirectionPositive = if (isDirectionPositive == null) delayType != DelayType.Audio else null
        },
        modifier = Modifier.weight(1f),
        enabled = delayControlEnabled && isDirectionPositive != (delayType == DelayType.Subtitle),
      ) {
        Text(
          stringResource(
            if (delayType == DelayType.Audio) {
              R.string.player_sheets_sub_delay_sound_sound_spotted
            } else {
              R.string.player_sheets_sub_delay_subtitle_text_seen
            },
          ),
        )
      }
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    ) {
      Button(
        onClick = onApply,
        modifier = Modifier.weight(1f),
        enabled = actionsEnabled && isDirectionPositive == null,
      ) {
        Text(stringResource(R.string.player_sheets_delay_set_as_default))
      }
      FilledIconButton(
        onClick = onReset,
        enabled = actionsEnabled && isDirectionPositive == null,
      ) {
        Icon(Icons.RoundedFilled.Refresh, null)
      }
    }
  }
}

/**
 * One tap offsets covering the delays that actually show up in practice.
 *
 * Each chip selects an absolute offset rather than nudging the current one, so repeated taps
 * are idempotent and the value the user sees is the value they picked. It also means a file
 * that was synced in a previous session only needs the same chip again, and the value is
 * stored per media so it is reapplied on the next playback.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickSyncOffsets(
  delay: Float,
  enabled: Boolean,
  onSelect: (Float) -> Unit,
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.smaller),
  ) {
    QUICK_SYNC_OFFSETS.forEach { offset ->
      FilterChip(
        selected = delay == offset,
        enabled = enabled,
        onClick = { onSelect(offset) },
        label = {
          // The signed format would render the zero chip as "+0 s", so it gets its own string.
          if (offset == 0f) {
            Text(stringResource(R.string.player_sheets_delay_offset_zero_seconds))
          } else {
            Text(stringResource(R.string.player_sheets_delay_offset_seconds, offset.toInt()))
          }
        },
      )
    }
  }
}

@Composable
fun SubtitleDelayTitle(
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MaterialTheme.spacing.medium)
        .padding(top = MaterialTheme.spacing.small),
  ) {
    Text(
      stringResource(R.string.player_sheets_sub_delay_card_title),
      style = MaterialTheme.typography.titleLarge,
    )
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onClose) {
      Icon(Icons.RoundedFilled.Close, null, modifier = Modifier.size(32.dp))
    }
  }
}

enum class DelayType {
  Audio,
  Subtitle,
}

/** Offsets offered by [QuickSyncOffsets], in seconds. */
private val QUICK_SYNC_OFFSETS = listOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f)
