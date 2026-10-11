package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.AudiobookPlayback
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics

@Composable
internal fun AudiobookSheet(sheet: Sheets, onChapterEnd: () -> Unit, onDismiss: () -> Unit) {
  val state by PlaybackSession.state.collectAsStateWithLifecycle()
  val info = state.currentItem?.audiobook ?: return
  val activeBook by AudiobookPlayback.book.collectAsStateWithLifecycle()
  val book = activeBook?.takeIf { it.book.id == info.bookId }
  val timer by AudiobookPlayback.timer.collectAsStateWithLifecycle()
  val ready = state.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
  val haptics = rememberAppHaptics()
  PlayerSheet(
    onDismissRequest = onDismiss,
    title =
      stringResource(
        when (sheet) {
          Sheets.AudiobookRewind -> R.string.audiobook_smart_rewind
          else -> R.string.audiobook_sleep_timer
        },
      ),
  ) {
    LazyColumn(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      when (sheet) {
        Sheets.AudiobookRewind -> {
          val choices = listOf(0, 5, 10, 15, 30)
          itemsIndexed(choices, key = { _, seconds -> seconds }) { index, seconds ->
            BookSettingOption(
              label =
                if (seconds == 0) {
                  stringResource(R.string.audiobook_timer_off)
                } else {
                  stringResource(R.string.audiobook_seconds, seconds)
                },
              selected = book?.book?.rewindSeconds == seconds,
              enabled = book != null,
              groupIndex = index,
              groupSize = choices.size,
            ) {
              if (book?.book?.rewindSeconds != seconds) {
                AudiobookPlayback.setRewind(seconds)
                haptics.selection(true)
              }
              onDismiss()
            }
          }
        }
        Sheets.AudiobookSleepTimer -> {
          val choices = listOf(5, 10, 15, 30, 45, 60, 90)
          val groupSize = choices.size + 2
          item {
            BookSettingOption(
              stringResource(R.string.audiobook_timer_off),
              timer == null,
              groupIndex = 0,
              groupSize = groupSize,
            ) {
              AudiobookPlayback.clearTimer()
              onDismiss()
            }
          }
          itemsIndexed(choices, key = { _, minutes -> minutes }) { index, minutes ->
            BookSettingOption(
              stringResource(R.string.audiobook_minutes, minutes),
              timer?.durationMinutes == minutes,
              enabled = ready,
              groupIndex = index + 1,
              groupSize = groupSize,
            ) {
              AudiobookPlayback.setTimer(minutes)
              haptics.selection(true)
              onDismiss()
            }
          }
          item {
            BookSettingOption(
              label = stringResource(R.string.audiobook_end_chapter),
              selected = timer?.chapterEndMs != null,
              enabled = ready && book != null,
              groupIndex = groupSize - 1,
              groupSize = groupSize,
            ) {
              onChapterEnd()
              haptics.selection(true)
              onDismiss()
            }
          }
        }
        else -> Unit
      }
    }
  }
}

@Composable
private fun BookSettingOption(
  label: String,
  selected: Boolean,
  enabled: Boolean = true,
  groupIndex: Int,
  groupSize: Int,
  onClick: () -> Unit,
) {
  AudioTrackRow(
    title = label,
    isSelected = selected,
    enabled = enabled,
    onClick = onClick,
    groupIndex = groupIndex,
    groupSize = groupSize,
  )
}
