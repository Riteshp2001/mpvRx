/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.weight
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheetDragHandle
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.rememberTvInitialFocusRequester
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.player.controls.components.tvInitialFocus
import kotlin.math.abs
import kotlin.math.roundToInt

enum class EqualizerMode(@StringRes val titleRes: Int) {
  DYNAMIC(R.string.equalizer_mode_dynamic),
  MANUAL(R.string.equalizer_mode_manual),
}

enum class EqualizerPreset(
  @StringRes val titleRes: Int,
  vararg val gainsDb: Float,
) {
  FLAT(R.string.equalizer_preset_flat, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
  ACOUSTIC(R.string.equalizer_preset_acoustic, 3f, 1.5f, 0f, 1.5f, 2.5f, 2f, 1f),
  BASS_BOOST(R.string.equalizer_preset_bass_boost, 6f, 4f, 1.5f, 0f, 0f, 0f, 0f),
  BASS_CUT(R.string.equalizer_preset_bass_cut, -6f, -4f, -1.5f, 0f, 0f, 0f, 0f),
  VOCAL(R.string.equalizer_preset_vocal, -3f, -1.5f, 1f, 3.5f, 3f, 1f, -1f),
  TREBLE_BOOST(R.string.equalizer_preset_treble_boost, 0f, 0f, 0f, 0f, 1.5f, 3.5f, 5f),
  TREBLE_CUT(R.string.equalizer_preset_treble_cut, 0f, 0f, 0f, 0f, -1.5f, -3.5f, -5f),
  LOUDNESS(R.string.equalizer_preset_loudness, 6f, 3.5f, 0f, -1.5f, -1f, 2f, 5f),
  SPOKEN_WORD(R.string.equalizer_preset_spoken_word, -5f, -2.5f, 1.5f, 4f, 3.5f, 1.5f, -2f),
  ELECTRONIC(R.string.equalizer_preset_electronic, 5f, 3f, -1f, 0f, 1f, 3f, 4f),
  ROCK(R.string.equalizer_preset_rock, 4f, 2.5f, -1f, -1.5f, 1f, 3f, 3.5f),
  HIP_HOP(R.string.equalizer_preset_hip_hop, 6f, 4f, 0.5f, -1f, 0.5f, 2f, 2.5f),
  JAZZ(R.string.equalizer_preset_jazz, 3f, 1.5f, 0f, 1f, 1.5f, 2f, 2.5f),
  CLASSICAL(R.string.equalizer_preset_classical, 3f, 2f, 0f, 0f, 1f, 2.5f, 3f),
  SMALL_SPEAKERS(R.string.equalizer_preset_small_speakers, 5f, 4f, 2f, 0.5f, 0f, -1f, -2f),
  LATE_NIGHT(R.string.equalizer_preset_late_night, 3f, 1f, 0f, 1.5f, 1f, -1f, -3f),
  CUSTOM(R.string.equalizer_preset_custom),
  ;

  val gains: List<Float>
    get() = gainsDb.toList()

  companion object {
    val MUSIC = entries.filterNot { it == CUSTOM }

    fun matching(gains: List<Float>): EqualizerPreset =
      entries.firstOrNull { preset ->
        preset != CUSTOM &&
          preset.gainsDb.size == gains.size &&
          preset.gainsDb.indices.all { index -> abs(preset.gainsDb[index] - gains[index]) < 0.05f }
      } ?: CUSTOM
  }
}

val EQ_BAND_FREQUENCIES = listOf(60, 150, 400, 1_000, 2_500, 6_000, 14_000)
val EQ_BAND_LABELS = listOf("60", "150", "400", "1k", "2.5k", "6k", "14k")
const val EQ_MIN_DB = -12f
const val EQ_MAX_DB = 12f
const val EQ_TONE_STEPS = 5
const val EQ_TONE_DB_PER_STEP = 1.2f

data class EqualizerState(
  val isEnabled: Boolean = false,
  val mode: EqualizerMode = EqualizerMode.DYNAMIC,
  val currentPreset: EqualizerPreset = EqualizerPreset.FLAT,
  val bandGains: List<Float> = List(EQ_BAND_FREQUENCIES.size) { 0f },
  val toneX: Int = 0,
  val toneY: Int = 0,
  val toneFocused: Boolean = false,
  val volumeBoostDb: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
  state: EqualizerState,
  onEnabledChanged: (Boolean) -> Unit,
  onModeChanged: (EqualizerMode) -> Unit,
  onPresetSelected: (EqualizerPreset) -> Unit,
  onBandChanged: (bandIndex: Int, gainDb: Float) -> Unit,
  onToneChanged: (x: Int, y: Int) -> Unit,
  onToneFocusChanged: (Boolean) -> Unit,
  onVolumeBoostChanged: (Int) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
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
    scrimColor = BottomSheetDefaults.ScrimColor,
    sheetMaxWidth = 640.dp,
    dragHandle = { PlayerSheetDragHandle() },
    modifier = modifier.tvFocusGroup(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.btn_label_equalizer),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(1f),
      )
      IconSwitch(
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
      SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
      ) {
        EqualizerMode.entries.forEachIndexed { index, mode ->
          SegmentedButton(
            selected = state.mode == mode,
            onClick = { onModeChanged(mode) },
            enabled = state.isEnabled,
            shape = SegmentedButtonDefaults.itemShape(index, EqualizerMode.entries.size),
            colors = themedSegmentedButtonColors(),
          ) {
            Text(stringResource(mode.titleRes))
          }
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      when (state.mode) {
        EqualizerMode.DYNAMIC -> {
          DynamicEqualizerContent(
            state = state,
            onToneChanged = onToneChanged,
            onToneFocusChanged = onToneFocusChanged,
          )
        }
        EqualizerMode.MANUAL -> {
          ManualEqualizerContent(
            state = state,
            onPresetSelected = onPresetSelected,
            onBandChanged = onBandChanged,
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
          text = stringResource(R.string.equalizer_volume_boost).uppercase(),
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
          text = if (state.volumeBoostDb > 0) "+${state.volumeBoostDb} dB" else stringResource(R.string.player_sheets_off),
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
        enabled = state.isEnabled,
        modifier =
          Modifier
            .fillMaxWidth()
            .tvFocusHighlight(MaterialTheme.shapes.small, enabled = state.isEnabled),
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
private fun ManualEqualizerContent(
  state: EqualizerState,
  onPresetSelected: (EqualizerPreset) -> Unit,
  onBandChanged: (bandIndex: Int, gainDb: Float) -> Unit,
) {
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
        isEnabled = state.isEnabled,
        onClick = if (preset != EqualizerPreset.CUSTOM) ({ onPresetSelected(preset) }) else null,
      )
    }
  }

  Spacer(modifier = Modifier.height(20.dp))

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(220.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    state.bandGains.forEachIndexed { index, gain ->
      BandColumn(
        label = EQ_BAND_LABELS.getOrElse(index) { "" },
        gainDb = gain,
        isEnabled = state.isEnabled,
        onGainChanged = { db -> onBandChanged(index, db) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun DynamicEqualizerContent(
  state: EqualizerState,
  onToneChanged: (x: Int, y: Int) -> Unit,
  onToneFocusChanged: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = stringResource(R.string.equalizer_tone_warm),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = stringResource(R.string.equalizer_tone_forward),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = stringResource(R.string.equalizer_tone_bright),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  TonePad(
    x = state.toneX,
    y = state.toneY,
    enabled = state.isEnabled,
    onChange = onToneChanged,
  )
  Text(
    text =
      "${stringResource(R.string.equalizer_tilt_format, signed(state.toneX))}  •  " +
        stringResource(R.string.equalizer_contour_format, signed(state.toneY)),
    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
    color = if (state.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
  )
  Spacer(modifier = Modifier.height(16.dp))
  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    listOf(false, true).forEachIndexed { index, focused ->
      SegmentedButton(
        selected = state.toneFocused == focused,
        onClick = { onToneFocusChanged(focused) },
        enabled = state.isEnabled,
        shape = SegmentedButtonDefaults.itemShape(index, 2),
        colors = themedSegmentedButtonColors(),
      ) {
        Text(stringResource(if (focused) R.string.equalizer_bandwidth_focused else R.string.equalizer_bandwidth_broad))
      }
    }
  }
}

@Composable
private fun TonePad(
  x: Int,
  y: Int,
  enabled: Boolean,
  onChange: (Int, Int) -> Unit,
) {
  val latestX by rememberUpdatedState(x)
  val latestY by rememberUpdatedState(y)
  val animatedX by animateFloatAsState(
    targetValue = x.toFloat(),
    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
    label = "equalizer_tone_x",
  )
  val animatedY by animateFloatAsState(
    targetValue = y.toFloat(),
    animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessHigh),
    label = "equalizer_tone_y",
  )
  val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
  val puckColor = MaterialTheme.colorScheme.primary
  val puckMarkColor = MaterialTheme.colorScheme.onPrimary
  val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest

  Canvas(
    modifier =
      Modifier
        .fillMaxWidth()
        .height(190.dp)
        .padding(horizontal = 12.dp, vertical = 14.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(surfaceColor.copy(alpha = if (enabled) 0.55f else 0.25f))
        .pointerInput(enabled) {
          if (!enabled) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            var lastX = latestX
            var lastY = latestY
            fun report(position: Offset) {
              val inset = 18.dp.toPx()
              val width = (size.width - inset * 2f).coerceAtLeast(1f)
              val height = (size.height - inset * 2f).coerceAtLeast(1f)
              val nextX = ((((position.x - inset) / width).coerceIn(0f, 1f) * EQ_TONE_STEPS * 2f).roundToInt() - EQ_TONE_STEPS)
              val nextY = EQ_TONE_STEPS - (((position.y - inset) / height).coerceIn(0f, 1f) * EQ_TONE_STEPS * 2f).roundToInt()
              if (nextX != lastX || nextY != lastY) {
                lastX = nextX
                lastY = nextY
                onChange(nextX, nextY)
              }
            }
            report(down.position)
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              if (!change.pressed) {
                change.consume()
                break
              }
              if (change.positionChanged()) {
                change.consume()
                report(change.position)
              }
            }
          }
        },
  ) {
    val inset = 18.dp.toPx()
    val usableWidth = size.width - inset * 2f
    val usableHeight = size.height - inset * 2f
    val divisions = EQ_TONE_STEPS * 2
    for (column in 0..divisions) {
      for (row in 0..divisions) {
        val onAxis = column == EQ_TONE_STEPS || row == EQ_TONE_STEPS
        drawCircle(
          color = dotColor.copy(alpha = if (onAxis) 0.55f else 0.26f),
          radius = if (onAxis) 2.dp.toPx() else 1.5.dp.toPx(),
          center = Offset(
            x = inset + usableWidth * column / divisions,
            y = inset + usableHeight * row / divisions,
          ),
        )
      }
    }
    val puckCenter =
      Offset(
        x = inset + usableWidth * (animatedX + EQ_TONE_STEPS) / divisions,
        y = inset + usableHeight * (EQ_TONE_STEPS - animatedY) / divisions,
      )
    drawCircle(color = Color.Black.copy(alpha = 0.18f), radius = 15.dp.toPx(), center = puckCenter.copy(y = puckCenter.y + 2.dp.toPx()))
    drawCircle(color = puckColor.copy(alpha = if (enabled) 1f else 0.38f), radius = 13.dp.toPx(), center = puckCenter)
    drawCircle(color = puckMarkColor, radius = 4.dp.toPx(), center = puckCenter)
  }
}

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

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
      text = stringResource(preset.titleRes),
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
  gainDb: Float,
  isEnabled: Boolean,
  onGainChanged: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  var sliderValue by remember(gainDb) { mutableFloatStateOf(gainDb.toFloat()) }
  val gainHaptics = app.gyrolet.mpvrx.ui.utils.rememberAdjustmentHaptics(EQ_MIN_DB, EQ_MAX_DB)

  Column(
    modifier = modifier.fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val displayGain = (sliderValue * 2f).roundToInt() / 2f
    val gainText = formatGain(displayGain)
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
        val snapped = (newValue * 2f).roundToInt() / 2f
        gainHaptics.move(sliderValue, snapped)
        sliderValue = snapped
        onGainChanged(snapped)
      },
      valueRange = EQ_MIN_DB..EQ_MAX_DB,
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

private fun formatGain(value: Float): String =
  when {
    abs(value) < 0.05f -> "0"
    value == value.roundToInt().toFloat() -> if (value > 0f) "+${value.roundToInt()}" else value.roundToInt().toString()
    value > 0f -> "+$value"
    else -> value.toString()
  }
