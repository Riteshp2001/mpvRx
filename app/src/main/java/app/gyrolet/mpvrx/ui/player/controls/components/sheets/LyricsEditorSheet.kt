/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.lyrics.LyricsDraft
import app.gyrolet.mpvrx.domain.lyrics.LyricsDraftError
import app.gyrolet.mpvrx.domain.lyrics.LyricsSourceType
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenu
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenuItem
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.player.Sheets
import app.gyrolet.mpvrx.ui.player.controls.components.rememberSmoothedPositionMs
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.fontFamilyForText
import app.gyrolet.mpvrx.utils.media.LyricsUtils
import kotlin.math.roundToLong

@Composable
fun LyricsEditAction(viewModel: PlayerViewModel, origin: Sheets = Sheets.None) {
  val state by viewModel.lyricsUiState.collectAsState()
  val editState by viewModel.lyricsEditUiState.collectAsState()
  val playback by PlaybackSession.state.collectAsStateWithLifecycle()
  IconButton(
    onClick = { viewModel.openLyricsEditor(origin) },
    enabled = !state.isLoading && !editState.isSaving && playback.currentItem?.audiobook == null &&
      playback.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND),
  ) {
    Icon(Icons.RoundedFilled.Edit, contentDescription = stringResource(R.string.lyrics_editor_title))
  }
}

@Composable
fun LyricsEditorSheet(viewModel: PlayerViewModel, onDismiss: () -> Unit) {
  val editTarget by viewModel.lyricsEditTarget.collectAsState()
  val target = editTarget
  if (target == null) {
    PlayerSheet(onDismiss, title = stringResource(R.string.lyrics_editor_title)) {
      Text(stringResource(R.string.lyrics_editor_unavailable), modifier = Modifier.padding(20.dp))
    }
    return
  }

  val initialDraft = remember(target) { LyricsDraft.from(target.lyrics) }
  val draft by viewModel.lyricsEditDraft.collectAsState()
  var selected by rememberSaveable(target.mediaPath) {
    mutableIntStateOf(initialDraft.lines.indexOfFirst { it.timeMs == null }.coerceAtLeast(0))
  }
  var validationError by rememberSaveable { mutableStateOf<LyricsDraftError?>(null) }
  var discardPrompt by rememberSaveable { mutableStateOf(false) }
  var restorePrompt by rememberSaveable { mutableStateOf(false) }
  var clearTimesPrompt by rememberSaveable { mutableStateOf(false) }
  var pasteDialog by rememberSaveable { mutableStateOf(false) }
  val pasteText by viewModel.lyricsEditPasteText.collectAsState()
  var pasteError by rememberSaveable { mutableStateOf(false) }
  var timeIndex by rememberSaveable { mutableStateOf<Int?>(null) }
  var timeText by rememberSaveable { mutableStateOf("") }
  var timeError by rememberSaveable { mutableStateOf(false) }
  var optionsExpanded by remember { mutableStateOf(false) }
  val editState by viewModel.lyricsEditUiState.collectAsState()
  val playback by PlaybackSession.state.collectAsStateWithLifecycle()
  val rawPosition by viewModel.precisePosition.collectAsStateWithLifecycle()
  val paused by PlaybackSession.propBoolean["pause"].collectAsState()
  val speed by PlaybackSession.propFloat["speed"].collectAsState()
  val seekable by PlaybackSession.propBoolean["seekable"].collectAsState()
  val context = LocalContext.current
  val density = LocalDensity.current
  val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
  val reducedMotion = AppMotion.playerReducedMotion()
  val listState = rememberLazyListState()
  val changed = !draft.hasSameContent(initialDraft)
  val sameTrack = viewModel.isLyricsEditTrackCurrent(target)
  val playbackReady = playback.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
  val canTime = sameTrack && playbackReady && rawPosition.isFinite() && !editState.isSaving
  val positionMs by rememberSmoothedPositionMs(
    rawPositionMs = if (rawPosition.isFinite()) (rawPosition * 1000.0).roundToLong().coerceAtLeast(0) else 0,
    isPlaying = paused == false && playbackReady,
    speed = speed ?: 1f,
  )
  val successMessage = stringResource(
    if (editState.restoredOriginal) R.string.lyrics_editor_restored else R.string.lyrics_editor_saved,
  )
  val requestClose: () -> Unit = {
    if (!viewModel.lyricsEditUiState.value.isSaving) {
      if (changed) discardPrompt = true else onDismiss()
    }
  }

  LaunchedEffect(editState.completed) {
    if (editState.completed) {
      Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
      onDismiss()
    }
  }
  LaunchedEffect(selected, draft.lines.size) {
    if (reducedMotion) listState.scrollToItem(selected) else listState.animateScrollToItem(selected)
  }

  Dialog(
    onDismissRequest = requestClose,
    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = requestClose, enabled = !editState.isSaving) {
            Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.generic_cancel))
          }
          Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.lyrics_editor_title), style = MaterialTheme.typography.titleMedium)
            Text(
              target.title,
              fontFamily = fontFamilyForText(target.title),
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Box {
            IconButton(onClick = { optionsExpanded = true }, enabled = !editState.isSaving) {
              Icon(Icons.RoundedFilled.MoreVert, contentDescription = stringResource(R.string.lyrics_editor_options))
            }
            val optionCount = if (target.hasSavedEdits) 3 else 2
            AppDropdownMenu(optionsExpanded, onDismissRequest = { optionsExpanded = false }) {
              AppDropdownMenuItem(
                text = { Text(stringResource(R.string.lyrics_editor_paste)) },
                index = 0,
                itemCount = optionCount,
                onClick = { optionsExpanded = false; pasteDialog = true },
              )
              AppDropdownMenuItem(
                text = { Text(stringResource(R.string.lyrics_editor_clear_times)) },
                index = 1,
                itemCount = optionCount,
                enabled = draft.lines.any { it.timeMs != null },
                onClick = { optionsExpanded = false; clearTimesPrompt = true },
              )
              if (target.hasSavedEdits) {
                AppDropdownMenuItem(
                  text = {
                    Text(stringResource(R.string.lyrics_editor_restore_title), color = MaterialTheme.colorScheme.error)
                  },
                  index = 2,
                  itemCount = optionCount,
                  onClick = { optionsExpanded = false; restorePrompt = true },
                )
              }
            }
          }
          TextButton(
            enabled = changed && !editState.isSaving,
            onClick = {
              validationError = draft.validationError()
              if (validationError == null) viewModel.saveEditedLyrics(draft)
            },
          ) {
            if (editState.isSaving) {
              CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
              Text(stringResource(R.string.ui_save))
            }
          }
        }
        if (!keyboardVisible) {
          Text(
            stringResource(R.string.lyrics_editor_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (!sameTrack) {
          Text(
            stringResource(R.string.lyrics_editor_track_changed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        validationError?.let {
          Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error)
        }
        editState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(
          state = listState,
          modifier = Modifier.weight(1f).fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          itemsIndexed(draft.lines, key = { _, line -> line.id }) { index, line ->
            var lineMenu by remember { mutableStateOf(false) }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = if (selected == index) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            ) {
              Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    stringResource(R.string.lyrics_editor_line, index + 1),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 4.dp),
                  )
                  TextButton(
                    enabled = !editState.isSaving,
                    onClick = {
                      selected = index
                      if (canTime && seekable == true && line.timeMs != null &&
                        viewModel.isLyricsEditTrackCurrent(target)
                      ) {
                        viewModel.seekFromScrub(line.timeMs / 1000f)
                      }
                    },
                  ) {
                    Text(
                      line.timeMs?.let { LyricsDraft.formatTime(it) }
                        ?: stringResource(R.string.lyrics_editor_untimed),
                    )
                  }
                  Spacer(Modifier.weight(1f))
                  Box {
                    IconButton(
                      onClick = { selected = index; lineMenu = true },
                      enabled = !editState.isSaving,
                    ) {
                      Icon(
                        Icons.RoundedFilled.MoreVert,
                        contentDescription = stringResource(R.string.lyrics_editor_line_options, index + 1),
                      )
                    }
                    AppDropdownMenu(lineMenu, onDismissRequest = { lineMenu = false }) {
                      AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_editor_edit_time)) },
                        index = 0,
                        itemCount = 4,
                        onClick = {
                          lineMenu = false
                          timeIndex = index
                          timeText = line.timeMs?.let { LyricsDraft.formatTime(it) }.orEmpty()
                          timeError = false
                        },
                      )
                      AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_editor_remove_time)) },
                        index = 1,
                        itemCount = 4,
                        enabled = line.timeMs != null,
                        onClick = {
                          lineMenu = false
                          viewModel.lyricsEditDraft.value = draft.setTime(index, null)
                          validationError = null
                        },
                      )
                      AppDropdownMenuItem(
                        text = { Text(stringResource(R.string.lyrics_editor_insert_line)) },
                        index = 2,
                        itemCount = 4,
                        onClick = {
                          lineMenu = false
                          viewModel.lyricsEditDraft.value = draft.insertAfter(index)
                          selected = index + 1
                          validationError = null
                        },
                      )
                      AppDropdownMenuItem(
                        text = {
                          Text(stringResource(R.string.lyrics_editor_delete_line), color = MaterialTheme.colorScheme.error)
                        },
                        index = 3,
                        itemCount = 4,
                        onClick = {
                          lineMenu = false
                          val updated = draft.delete(index)
                          viewModel.lyricsEditDraft.value = updated
                          selected = selected.coerceAtMost(updated.lines.lastIndex)
                          validationError = null
                        },
                      )
                    }
                  }
                }
                OutlinedTextField(
                  value = line.text,
                  onValueChange = {
                    viewModel.lyricsEditDraft.value = draft.editText(index, it)
                    validationError = null
                  },
                  enabled = !editState.isSaving,
                  maxLines = 3,
                  modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) selected = index },
                  label = { Text(stringResource(R.string.lyrics_editor_line, index + 1)) },
                  textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = fontFamilyForText(line.text)),
                )
              }
            }
          }
        }
        if (!keyboardVisible) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              enabled = canTime,
              onClick = { if (viewModel.isLyricsEditTrackCurrent(target)) viewModel.pauseUnpause() },
            ) {
              Icon(
                if (paused == false) Icons.RoundedFilled.Pause else Icons.RoundedFilled.PlayArrow,
                contentDescription = stringResource(if (paused == false) R.string.audiobook_pause else R.string.ui_play),
              )
            }
            Text(
              LyricsDraft.formatTime(positionMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
              style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
              enabled = !editState.isSaving,
              onClick = {
                viewModel.lyricsEditDraft.value = draft.insertAfter(selected)
                selected++
                validationError = null
              },
            ) {
              Icon(Icons.RoundedFilled.Add, contentDescription = stringResource(R.string.lyrics_editor_insert_line))
            }
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            val selectedTime = draft.lines[selected].timeMs
            OutlinedButton(
              enabled = selectedTime != null && !editState.isSaving,
              onClick = {
                viewModel.lyricsEditDraft.value =
                  draft.setTime(selected, (requireNotNull(selectedTime) - 100).coerceAtLeast(0))
                validationError = null
              },
            ) { Text(stringResource(R.string.lyrics_editor_earlier)) }
            Button(
              modifier = Modifier.weight(1f),
              enabled = canTime,
              onClick = {
                if (viewModel.isLyricsEditTrackCurrent(target)) {
                  val time = ((positionMs + 5) / 10 * 10).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                  viewModel.lyricsEditDraft.value = draft.setTime(selected, time)
                  if (selected < draft.lines.lastIndex) selected++
                  validationError = null
                }
              },
            ) { Text(stringResource(R.string.lyrics_editor_set_time)) }
            OutlinedButton(
              enabled = selectedTime != null && !editState.isSaving,
              onClick = {
                val time = (requireNotNull(selectedTime).toLong() + 100).coerceAtMost(Int.MAX_VALUE.toLong())
                viewModel.lyricsEditDraft.value = draft.setTime(selected, time.toInt())
                validationError = null
              },
            ) { Text(stringResource(R.string.lyrics_editor_later)) }
          }
          Text(
            stringResource(R.string.lyrics_editor_storage),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  if (discardPrompt) {
    ConfirmDialog(
      title = stringResource(R.string.lyrics_editor_discard_title),
      subtitle = stringResource(R.string.lyrics_editor_discard_message),
      confirmText = stringResource(R.string.lyrics_editor_discard),
      onConfirm = { discardPrompt = false; onDismiss() },
      onCancel = { discardPrompt = false },
    )
  }
  if (restorePrompt) {
    ConfirmDialog(
      title = stringResource(R.string.lyrics_editor_restore_title),
      subtitle = stringResource(R.string.lyrics_editor_restore_message),
      onConfirm = { restorePrompt = false; viewModel.restoreOriginalLyrics() },
      onCancel = { restorePrompt = false },
    )
  }
  if (clearTimesPrompt) {
    ConfirmDialog(
      title = stringResource(R.string.lyrics_editor_clear_times),
      subtitle = stringResource(R.string.lyrics_editor_clear_times_message),
      onConfirm = {
        clearTimesPrompt = false
        viewModel.lyricsEditDraft.value = draft.clearTimes()
        validationError = null
      },
      onCancel = { clearTimesPrompt = false },
    )
  }
  if (pasteDialog) {
    AppAlertDialog(
      onDismissRequest = { pasteDialog = false },
      title = { Text(stringResource(R.string.lyrics_editor_paste)) },
      text = {
        OutlinedTextField(
          value = pasteText,
          onValueChange = { viewModel.lyricsEditPasteText.value = it; pasteError = false },
          modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
          label = { Text(stringResource(R.string.lyrics_editor_paste_hint)) },
          isError = pasteError,
          supportingText = {
            if (pasteError) Text(stringResource(R.string.lyrics_editor_paste_empty))
          },
        )
      },
      dismissButton = {
        AppDialogTextButton(onClick = { pasteDialog = false }) { Text(stringResource(R.string.generic_cancel)) }
      },
      confirmButton = {
        AppDialogTextButton(
          onClick = {
            val imported = LyricsUtils.parseLyrics(pasteText, LyricsSourceType.LOCAL)
            if (!imported.isValid()) {
              pasteError = true
            } else {
              viewModel.lyricsEditDraft.value = LyricsDraft.from(imported)
              selected = 0
              validationError = null
              pasteDialog = false
              viewModel.lyricsEditPasteText.value = ""
            }
          },
        ) { Text(stringResource(R.string.generic_confirm)) }
      },
    )
  }
  timeIndex?.let { index ->
    AppAlertDialog(
      onDismissRequest = { timeIndex = null },
      title = { Text(stringResource(R.string.lyrics_editor_edit_time)) },
      text = {
        OutlinedTextField(
          value = timeText,
          onValueChange = {
            if (it.length <= 64) {
              timeText = it
              timeError = false
            } else {
              timeError = true
            }
          },
          singleLine = true,
          label = { Text(stringResource(R.string.lyrics_editor_time_hint)) },
          isError = timeError,
          supportingText = { if (timeError) Text(stringResource(R.string.lyrics_editor_time_invalid)) },
        )
      },
      dismissButton = {
        AppDialogTextButton(onClick = { timeIndex = null }) { Text(stringResource(R.string.generic_cancel)) }
      },
      confirmButton = {
        AppDialogTextButton(
          onClick = {
            val time = LyricsDraft.parseTime(timeText)
            if (time == null) {
              timeError = true
            } else {
              viewModel.lyricsEditDraft.value = draft.setTime(index, time)
              validationError = null
              timeIndex = null
            }
          },
        ) { Text(stringResource(R.string.generic_confirm)) }
      },
    )
  }
}

@androidx.annotation.StringRes
private fun LyricsDraftError.messageRes(): Int = when (this) {
  LyricsDraftError.EMPTY -> R.string.lyrics_editor_empty
  LyricsDraftError.INCOMPLETE_TIMING -> R.string.lyrics_editor_incomplete_timing
  LyricsDraftError.OUT_OF_ORDER -> R.string.lyrics_editor_out_of_order
  LyricsDraftError.INVALID_TIME -> R.string.lyrics_editor_time_invalid
}
