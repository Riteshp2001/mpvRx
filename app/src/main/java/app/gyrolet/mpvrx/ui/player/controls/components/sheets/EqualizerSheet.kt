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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

enum class EqualizerPreset(val displayName: String, val gains: List<Int>) {
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
  CUSTOM("Custom", listOf(0, 0, 0, 0, 0));

  companion object {
    val MUSIC = listOf(
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
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    dragHandle = null,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 48.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "EQUALIZER",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Switch(
          checked = state.isEnabled,
          onCheckedChange = onEnabledChanged,
          colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
          ),
          modifier = Modifier.scale(0.8f),
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      val presetsToShow = if (state.currentPreset == EqualizerPreset.CUSTOM) {
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
            onClick = if (preset != EqualizerPreset.CUSTOM) {
              { onPresetSelected(preset) }
            } else null,
          )
        }
      }

      Spacer(modifier = Modifier.height(32.dp))

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          letterSpacing = 2.sp,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = if (state.volumeBoostDb > 0) "+${state.volumeBoostDb} dB" else "Off",
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
          color = if (state.volumeBoostDb > 0) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Slider(
        value = state.volumeBoostDb.toFloat(),
        onValueChange = { onVolumeBoostChanged(it.roundToInt()) },
        valueRange = 0f..10f,
        steps = 9,
        modifier = Modifier.fillMaxWidth(),
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "0 dB",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "+10 dB",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun PresetChip(
  preset: EqualizerPreset,
  isSelected: Boolean,
  onClick: (() -> Unit)?,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .clip(RoundedCornerShape(50))
      .background(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent
      )
      .border(
        BorderStroke(
          1.dp,
          if (isSelected) Color.Transparent
          else MaterialTheme.colorScheme.outlineVariant,
        ),
        RoundedCornerShape(50),
      )
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Text(
      text = preset.displayName,
      style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
      color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
      else MaterialTheme.colorScheme.onSurface,
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
  Column(
    modifier = modifier.fillMaxHeight(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val gainText = if (gainDb > 0) "+$gainDb" else "$gainDb"
    Text(
      text = gainText,
      style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
      color = if (isEnabled) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )

    Slider(
      value = gainDb.toFloat(),
      onValueChange = { onGainChanged(it.roundToInt()) },
      valueRange = EQ_MIN_DB.toFloat()..EQ_MAX_DB.toFloat(),
      enabled = isEnabled,
      modifier = Modifier
        .weight(1f)
        .padding(vertical = 12.dp)
        .layout { measurable, constraints ->
          val placeable = measurable.measure(
            Constraints(
              minWidth = constraints.minHeight,
              maxWidth = constraints.maxHeight,
              minHeight = constraints.minWidth,
              maxHeight = constraints.maxWidth,
            )
          )
          layout(placeable.height, placeable.width) {
            placeable.place(
              x = -(placeable.width / 2 - placeable.height / 2),
              y = -(placeable.height / 2 - placeable.width / 2),
            )
          }
        }
        .graphicsLayer {
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
