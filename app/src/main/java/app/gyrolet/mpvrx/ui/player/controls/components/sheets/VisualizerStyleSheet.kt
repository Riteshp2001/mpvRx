/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioVisualizerStyle
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.theme.spacing

@Composable
fun VisualizerStyleSheet(
  selectedStyle: AudioVisualizerStyle,
  onSelectStyle: (AudioVisualizerStyle) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  PlayerSheet(onDismissRequest) {
    Column(modifier = modifier) {
      Text(
        text = stringResource(R.string.pref_audio_visualizer_style_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
          Modifier.padding(
            horizontal = MaterialTheme.spacing.medium,
            vertical = MaterialTheme.spacing.small,
          ),
      )
      LazyColumn {
        items(AudioVisualizerStyle.entries, key = { it.name }) { style ->
          AudioTrackRow(
            title = stringResource(style.title),
            isSelected = selectedStyle == style,
            onClick = {
              onSelectStyle(style)
              onDismissRequest()
            },
          )
        }
      }
    }
  }
}
