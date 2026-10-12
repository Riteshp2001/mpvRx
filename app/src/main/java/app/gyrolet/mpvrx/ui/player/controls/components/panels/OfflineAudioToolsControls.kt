package app.gyrolet.mpvrx.ui.player.controls.components.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.player.OfflinePlaybackAudioTools
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import kotlin.math.roundToLong

@Composable
internal fun OfflineSilenceSkippingControls(viewModel: PlayerViewModel, showSettings: Boolean = true) {
  val state by OfflinePlaybackAudioTools.state.collectAsState()
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().toggleable(
        value = state.skipping,
        role = Role.Switch,
        onValueChange = viewModel::setOfflineSilenceSkipping,
      ).padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(stringResource(R.string.offline_silence_skipping), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      Switch(checked = state.skipping, onCheckedChange = null)
    }
    if (showSettings || state.skipping) {
      OfflineAudioSlider(
        label = stringResource(R.string.offline_silence_threshold),
        displayValue = stringResource(R.string.offline_silence_db, state.thresholdDb),
        value = state.thresholdDb,
        range = -70f..-20f,
        steps = 49,
        onChange = { OfflinePlaybackAudioTools.configureSilence(it, state.minimumSilenceMs, state.speechPaddingMs) },
      )
      OfflineAudioSlider(
        label = stringResource(R.string.offline_silence_minimum),
        displayValue = stringResource(R.string.offline_silence_seconds, state.minimumSilenceMs / 1000f),
        value = state.minimumSilenceMs.toFloat(),
        range = 500f..5000f,
        steps = 8,
        onChange = { OfflinePlaybackAudioTools.configureSilence(state.thresholdDb, it.roundToLong(), state.speechPaddingMs) },
      )
      OfflineAudioSlider(
        label = stringResource(R.string.offline_silence_padding),
        displayValue = stringResource(R.string.offline_silence_seconds, state.speechPaddingMs / 1000f),
        value = state.speechPaddingMs.toFloat(),
        range = 100f..500f,
        steps = 3,
        onChange = { OfflinePlaybackAudioTools.configureSilence(state.thresholdDb, state.minimumSilenceMs, it.roundToLong()) },
      )
    }
    if (state.analyzingSilence) {
      Text(stringResource(R.string.offline_audio_analyzing), style = MaterialTheme.typography.bodySmall)
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (state.skippedMs > 0) Text(stringResource(R.string.offline_silence_skipped, state.skippedMs / 1000.0), style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
  }
}

@Composable
private fun OfflineAudioSlider(
  label: String,
  displayValue: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  steps: Int,
  onChange: (Float) -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(displayValue, style = MaterialTheme.typography.labelLarge)
  }
  Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps,
    modifier = Modifier.fillMaxWidth().semantics {
      contentDescription = label
      stateDescription = displayValue
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OfflineSubtitleAlignmentControls(viewModel: PlayerViewModel, enabled: Boolean) {
  val state by OfflinePlaybackAudioTools.state.collectAsState()
  Column(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(stringResource(R.string.offline_subtitle_alignment), style = MaterialTheme.typography.titleMedium)
    if (state.aligning) {
      Text(stringResource(R.string.offline_audio_analyzing), style = MaterialTheme.typography.bodySmall)
      LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.alignment?.let {
      Text(stringResource(R.string.offline_alignment_result, it.delaySeconds, it.speed), style = MaterialTheme.typography.bodyMedium)
    }
    if (state.alignmentApplied) Text(stringResource(R.string.offline_alignment_applied), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.aligning) {
        TextButton(onClick = OfflinePlaybackAudioTools::cancelAlignment) { Text(stringResource(R.string.generic_cancel)) }
      } else {
        Button(onClick = viewModel::analyzeOfflineSubtitleAlignment) { Text(stringResource(R.string.offline_alignment_analyze)) }
      }
      if (state.alignment != null && !state.aligning) {
        if (state.alignmentApplied) {
          TextButton(onClick = OfflinePlaybackAudioTools::undoAlignment, enabled = enabled) { Text(stringResource(R.string.offline_alignment_undo)) }
        } else {
          TextButton(onClick = OfflinePlaybackAudioTools::applyAlignment, enabled = enabled) { Text(stringResource(R.string.offline_alignment_apply)) }
        }
      }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    HorizontalDivider()
  }
}