/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.PlayerSheetDragHandle
import app.gyrolet.mpvrx.presentation.components.PlayerSheetHeader
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import org.koin.compose.koinInject
import androidx.compose.runtime.collectAsState
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.AudioEngineKind
import kotlin.math.roundToInt

enum class EqualizerPreset(
  val displayName: String,
  val gains: List<Int>,
) {
  FLAT("Flat", listOf(0, 0, 0, 0, 0)),
  ROCK("Rock", listOf(4, 2, -1, 2, 4)),
  POP("Pop", listOf(-1, 2, 4, 2, -1)),
  JAZZ("Jazz", listOf(3, 2, -1, 2, 3)),
  CLASSICAL("Classical", listOf(3, 1, -1, 2, 3)),
  ELECTRONIC("Electronic", listOf(5, 3, 0, 2, 4)),
  BASS_BOOST("Bass Boost", listOf(5, 3, 0, -1, -2)),
  TREBLE_BOOST("Treble Boost", listOf(-2, -1, 0, 3, 5)),
  VOICE_BOOST("Voice Boost", listOf(2, 4, 5, 3, 1)),
  LOUDNESS("Loudness", listOf(4, 2, 0, 2, 4)),
  CUSTOM("Custom", listOf(0, 0, 0, 0, 0)),
  ;

  companion object {
    val MUSIC =
      listOf(
        FLAT,
        ROCK,
        POP,
        JAZZ,
        CLASSICAL,
        ELECTRONIC,
        BASS_BOOST,
        TREBLE_BOOST,
        VOICE_BOOST,
        LOUDNESS,
      )
  }
}

val EQ_BAND_LABELS = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
const val EQ_MIN_DB = -15
const val EQ_MAX_DB = 15

data class EqualizerState(
  val isEnabled: Boolean = false,
  val currentPreset: EqualizerPreset = EqualizerPreset.FLAT,
  val bandGains: List<Int> = List(5) { 0 },
  val volumeBoostDb: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
  state: EqualizerState,
  onEnabledChanged: (Boolean) -> Unit,
  onPresetSelected: (EqualizerPreset) -> Unit,
  onBandChanged: (bandIndex: Int, gainDb: Int) -> Unit,
  onVolumeBoostChanged: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val dimBackground by koinInject<PlayerPreferences>().reduceMotion.collectAsState()
  val session by PlaybackSession.state.collectAsState()
  val audio by PlaybackSession.audioState.collectAsState()
  val processingAvailable = session.engine != AudioEngineKind.ExoPlayer || !audio.ready || !audio.output.processingBypassed
  val controlsEnabled = state.isEnabled && processingAvailable
  val initialFocusRequester =
    rememberTvInitialFocusRequester(requestKey = state.isEnabled)
  val sheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    scrimColor = if (dimBackground) BottomSheetDefaults.ScrimColor else Color.Transparent,
    sheetMaxWidth = 640.dp,
    dragHandle = { PlayerSheetDragHandle() },
    modifier = modifier.tvFocusGroup(),
  ) {
    PlayerSheetHeader(stringResource(R.string.btn_label_equalizer)) {
      if (processingAvailable) IconSwitch(
        checked = state.isEnabled,
        onCheckedChange = onEnabledChanged,
        modifier = Modifier.tvInitialFocus(initialFocusRequester)
          .tvFocusHighlight(RoundedCornerShape(12.dp), focusedScale = 1.04f),
      )
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .weight(1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
    ) {
      if (!processingAvailable) {
        Text(stringResource(R.string.audio_output_preserved), style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      val presetsToShow =
        if (state.currentPreset == EqualizerPreset.CUSTOM) {
          listOf(EqualizerPreset.CUSTOM) + EqualizerPreset.MUSIC
        } else {
          EqualizerPreset.MUSIC
        }

      LazyRow(
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        items(presetsToShow, key = { it.name }) { preset ->
          PresetChip(
            preset = preset,
            isSelected = preset == state.currentPreset,
            isEnabled = controlsEnabled,
            onClick =
              if (preset != EqualizerPreset.CUSTOM) {
                { onPresetSelected(preset) }
              } else {
                null
              },
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        state.bandGains.forEachIndexed { index, gain ->
          BandColumn(
            label = EQ_BAND_LABELS.getOrElse(index) { "" },
            gainDb = gain,
            isEnabled = controlsEnabled,
            onGainChanged = { db -> onBandChanged(index, db) },
            modifier = Modifier.weight(1f),
          )
        }
      }

      HorizontalDivider(
        modifier = Modifier.padding(vertical = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "VOLUME BOOST",
          style = MaterialTheme.typography.labelMedium,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
          letterSpacing = 0.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (state.volumeBoostDb > 0) "+${state.volumeBoostDb} dB" else "Off",
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color =
            if (state.isEnabled) {
              if (state.volumeBoostDb > 0) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              }
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      var volumeBoostValue by remember(state.volumeBoostDb) { mutableFloatStateOf(state.volumeBoostDb.toFloat()) }
      val boostHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(0f, 10f)
      Slider(
        value = volumeBoostValue,
        onValueChange = { newValue ->
          boostHaptics.move(volumeBoostValue, newValue)
          volumeBoostValue = newValue
          onVolumeBoostChanged(newValue.roundToInt())
        },
        valueRange = 0f..10f,
        enabled = controlsEnabled,
        modifier =
          Modifier
            .fillMaxWidth()
            .tvFocusHighlight(MaterialTheme.shapes.small, enabled = controlsEnabled),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "0 dB",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
        Text(
          text = "+10 dB",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (state.isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
      }
    }
  }
}

@Composable
private fun PresetChip(
  preset: EqualizerPreset,
  isSelected: Boolean,
  isEnabled: Boolean,
  onClick: (() -> Unit)?,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      Modifier
        .alpha(if (isEnabled) 1f else 0.38f)
        .tvFocusHighlight(RoundedCornerShape(50), enabled = isEnabled, focusedScale = 1.03f)
        .clip(RoundedCornerShape(50))
        .background(
          if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            Color.Transparent
          },
        ).border(
          BorderStroke(
            1.dp,
            if (isSelected) {
              Color.Transparent
            } else {
              MaterialTheme.colorScheme.outlineVariant
            },
          ),
          RoundedCornerShape(50),
        ).then(if (isEnabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(
      text = preset.displayName,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
      color =
        if (isSelected) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onSurface
        },
    )
  }
}

@Composable
private fun BandColumn(
  label: String,
  gainDb: Int,
  isEnabled: Boolean,
  onGainChanged: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sliderValue by remember(gainDb) { mutableFloatStateOf(gainDb.toFloat()) }
  val gainHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(EQ_MIN_DB.toFloat(), EQ_MAX_DB.toFloat())

  Column(
    modifier = modifier.fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val displayGain = sliderValue.roundToInt()
    val gainText = if (displayGain > 0) "+$displayGain" else "$displayGain"
    Text(
      text = gainText,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
      color =
        if (isEnabled) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      textAlign = TextAlign.Center,
    )

    Slider(
      value = sliderValue,
      onValueChange = { newValue ->
        gainHaptics.move(sliderValue, newValue)
        sliderValue = newValue
        onGainChanged(newValue.roundToInt())
      },
      valueRange = EQ_MIN_DB.toFloat()..EQ_MAX_DB.toFloat(),
      enabled = isEnabled,
      modifier =
        Modifier
          .weight(1f)
          .tvFocusHighlight(MaterialTheme.shapes.small, enabled = isEnabled)
          .padding(vertical = 12.dp)
          .layout { measurable, constraints ->
            val placeable =
              measurable.measure(
                Constraints(
                  minWidth = constraints.minHeight,
                  maxWidth = constraints.maxHeight,
                  minHeight = constraints.minWidth,
                  maxHeight = constraints.maxWidth,
                ),
              )
            layout(placeable.height, placeable.width) {
              placeable.place(
                x = -(placeable.width / 2 - placeable.height / 2),
                y = -(placeable.height / 2 - placeable.width / 2),
              )
            }
          }.graphicsLayer {
            rotationZ = -90f
            transformOrigin = TransformOrigin.Center
          },
    )

    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
