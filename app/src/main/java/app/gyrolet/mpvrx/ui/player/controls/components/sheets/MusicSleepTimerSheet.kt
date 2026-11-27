/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.PlaybackSession

private val musicSleepTimerPresets = listOf(5, 15, 30, 60)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicSleepTimerSheet(onDismissRequest: () -> Unit) {
  val remainingTime by PlaybackSession.sleepTimerRemaining.collectAsStateWithLifecycle()
  val endOfSong by PlaybackSession.sleepAtSongEnd.collectAsStateWithLifecycle()
  var showCustom by rememberSaveable { mutableStateOf(false) }

  fun selectDuration(seconds: Int) {
    PlaybackSession.startSleepTimer(seconds)
    onDismissRequest()
  }

  PlayerSheet(onDismissRequest = onDismissRequest, title = stringResource(R.string.timer_title)) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(
            rememberScrollState(),
          ).padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (remainingTime > 0) {
        Text(
          text = stringResource(R.string.timer_remaining, DateUtils.formatElapsedTime(remainingTime.toLong())),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        musicSleepTimerPresets.forEach { minutes ->
          FilterChip(
            selected = false,
            onClick = { selectDuration(minutes * 60) },
            label = {
              Text(
                if (minutes == 60) {
                  stringResource(R.string.timer_one_hour_short)
                } else {
                  stringResource(R.string.generic_minutes_short, minutes)
                },
              )
            },
          )
        }
        FilterChip(
          selected = false,
          onClick = { showCustom = true },
          label = { Text(stringResource(R.string.timer_custom)) },
        )
        FilterChip(
          selected = endOfSong,
          onClick = {
            PlaybackSession.startSleepTimerAtSongEnd()
            onDismissRequest()
          },
          label = { Text(stringResource(R.string.timer_end_of_song)) },
        )
      }
      if (remainingTime > 0 || endOfSong) {
        AppDialogTextButton(onClick = { selectDuration(0) }) { Text(stringResource(R.string.timer_turn_off)) }
      }
    }
  }

  if (showCustom) {
    MusicSleepTimerTimeDialog(
      remainingTime = remainingTime,
      onDismissRequest = { showCustom = false },
      onConfirm = { selectDuration(it) },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSleepTimerTimeDialog(
  remainingTime: Int,
  onDismissRequest: () -> Unit,
  onConfirm: (Int) -> Unit,
) {
  val initialMinutes = ((remainingTime + 59) / 60).coerceIn(0, 23 * 60 + 59)
  val state = rememberTimePickerState(initialMinutes / 60, initialMinutes % 60, is24Hour = true)
  val seconds = state.hour * 3600 + state.minute * 60
  AppAlertDialog(
    onDismissRequest = onDismissRequest,
    title = { Text(stringResource(R.string.timer_picker_enter_timer)) },
    text = { TimeInput(state = state, modifier = Modifier.verticalScroll(rememberScrollState())) },
    dismissButton = {
      AppDialogTextButton(onClick = onDismissRequest) { Text(stringResource(R.string.generic_cancel)) }
    },
    confirmButton = {
      AppDialogTextButton(enabled = seconds > 0, onClick = {
        onConfirm(seconds)
      }) { Text(stringResource(R.string.generic_ok)) }
    },
  )
}
