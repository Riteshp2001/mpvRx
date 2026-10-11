/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.controls.components.sheets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.presentation.components.PlayerSheet
import app.gyrolet.mpvrx.ui.player.PlayerViewModel
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.spacing
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import dev.vivvvek.seeker.Segment
import `is`.xyz.mpv.Utils
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChaptersSheet(
  chapters: ImmutableList<Segment>,
  currentChapter: Segment?,
  onClick: (Segment) -> Unit,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  itemActions: @Composable (Segment) -> Unit = {},
) {
  val listState = rememberLazyListState()

  LaunchedEffect(currentChapter, chapters) {
    val index = if (currentChapter != null) chapters.indexOf(currentChapter) else -1
    if (index >= 0) {
      listState.scrollToItem(index)
    }
  }

  val isAudiobook = app.gyrolet.mpvrx.ui.player.PlaybackSession.state.collectAsStateWithLifecycle().value.currentItem?.audiobook != null
  PlayerSheet(
    onDismissRequest,
    title = stringResource(if (isAudiobook) R.string.audiobook_chapters else R.string.btn_label_bookmarks),
  ) {
    LazyColumn(
      modifier = modifier.fillMaxWidth(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      if (chapters.isEmpty()) {
        item {
          Text(
            stringResource(R.string.playback_bookmarks_empty),
            Modifier.padding(MaterialTheme.spacing.medium),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      itemsIndexed(
        chapters,
        key = { index, chapter -> "${chapter.start}_${chapter.name}_$index" },
      ) { index, chapter ->
        ChapterTrack(
          chapter = chapter,
          index = index,
          chapterCount = chapters.size,
          selected = currentChapter == chapter,
          onClick = { onClick(chapter) },
          trailingContent = { itemActions(chapter) },
        )
      }
    }
  }
}

@Composable
fun ChapterTrack(
  chapter: Segment,
  index: Int,
  chapterCount: Int,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  trailingContent: @Composable () -> Unit = {},
) {
  val shape =
    rememberConnectedShape(
      index = index,
      itemCount = chapterCount,
      layout = ConnectedLayout.Vertical,
    )
  val containerColor by
    animateColorAsState(
      targetValue =
        if (selected) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceContainerLow
        },
      animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
      label = "chapterSelection",
    )
  Surface(
    modifier =
      modifier
        .fillMaxWidth(),
    shape = shape,
    color = containerColor,
    tonalElevation = if (selected) 1.dp else 0.dp,
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .heightIn(min = 64.dp)
          .clickable(onClick = onClick)
          .padding(vertical = 10.dp, horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          stringResource(R.string.player_sheets_track_title_wo_lang, index + 1, chapter.name),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(Utils.prettyTime(chapter.start.toInt()), style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      trailingContent()
    }
  }
}

@Composable
internal fun PlaybackBookmarkEditor(viewModel: PlayerViewModel, onSaved: () -> Unit, onDismiss: () -> Unit) {
  val draft by viewModel.bookmarkDraft.collectAsStateWithLifecycle()
  val mediaId by viewModel.bookmarkMediaId.collectAsStateWithLifecycle()
  val bookmark = draft?.takeIf { it.mediaId == mediaId }
  if (bookmark == null) {
    LaunchedEffect(draft, mediaId) { onDismiss() }
    return
  }
  var name by rememberSaveable(bookmark.mediaId, bookmark.id, bookmark.positionMs) { mutableStateOf(bookmark.title) }
  var saving by remember(bookmark) { mutableStateOf(false) }
  var failed by remember(bookmark) { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val haptics = rememberAppHaptics()
  PlayerSheet(onDismissRequest = { if (!saving) onDismiss() }, title = stringResource(R.string.audiobook_bookmark_name)) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(Utils.prettyTime((viewModel.bookmarkPositionMs(bookmark) / 1000).toInt()),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), enabled = !saving, maxLines = 3,
        label = { Text(stringResource(R.string.audiobook_bookmark_name)) })
      if (failed) Text(stringResource(R.string.playback_bookmark_update_failed), color = MaterialTheme.colorScheme.error)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.generic_cancel)) }
        TextButton(enabled = name.isNotBlank() && !saving, onClick = {
          saving = true
          failed = false
          scope.launch {
            try {
              viewModel.savePlaybackBookmark(bookmark, name)
              haptics.confirm()
              onSaved()
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (_: Exception) {
              failed = true
            } finally {
              saving = false
            }
          }
        }) { Text(stringResource(R.string.audiobook_save)) }
      }
    }
  }
}
