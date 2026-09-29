/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.framecapture

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.preferences.SnapshotSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.cards.SelectionIndicator
import app.gyrolet.mpvrx.ui.browser.cards.animatedSelectionColor
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.navigateTo
import java.text.DateFormat
import java.util.Date

/**
 * Newest capture first by default; the other fields sort on what the list caption shows.
 *
 * Shared with the folder page, which sorts the same records with the same controls.
 */
internal fun snapshotComparator(
  sortType: SnapshotSortType,
  sortOrder: SortOrder,
): Comparator<FrameCapture> {
  val base =
    when (sortType) {
      SnapshotSortType.CapturedAt -> compareBy<FrameCapture> { it.capturedAt }.thenBy { it.id }
      SnapshotSortType.VideoTitle ->
        compareBy<FrameCapture, String>(String.CASE_INSENSITIVE_ORDER) { it.videoTitle }
          .thenBy { it.capturedAt }
      SnapshotSortType.Position -> compareBy<FrameCapture> { it.positionMs }.thenBy { it.capturedAt }
    }
  return if (sortOrder == SortOrder.Descending) base.reversed() else base
}

/** Localised, because a capture date is read by a person, not parsed by anything. */
internal val FrameCapture.formattedCaptureDate: String
  get() = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(capturedAt))

/** A photo grid that drops below three across stops reading as a grid at all. */
internal const val MIN_SNAPSHOT_GRID_COLUMNS = 3

/** Cells are square, so this is the width at which three still fit; wider screens get more. */
internal val SNAPSHOT_GRID_TARGET_CELL = 128.dp

internal val SNAPSHOT_GRID_SPACING = 2.dp

/** The folder glyph's box, matching the browser's folder cards so both pages read the same. */
private const val FOLDER_GLYPH_ASPECT = 20f / 17f

/**
 * Columns for a snapshot grid. [manualColumns] is whatever the user picked in the sort dialog and is
 * taken as given — asking for two and being handed three would be worse than a wide grid.
 */
internal fun snapshotGridColumns(
  availableWidth: Dp,
  manualColumns: Int,
): Int =
  if (manualColumns > 0) {
    manualColumns
  } else {
    maxOf(MIN_SNAPSHOT_GRID_COLUMNS, (availableWidth / SNAPSHOT_GRID_TARGET_CELL).toInt())
  }

/**
 * The shape a mosaic cell falls back to while its frame's size is still being read — square, which
 * is what the plain grid shows, so an unsettled tile still looks like it belongs on the page.
 */
internal const val SNAPSHOT_MOSAIC_FALLBACK_RATIO = 1f

/**
 * The frame's own width-to-height ratio. Snapshots recorded before the mosaic layout existed carry
 * no size until the backfill reads one, and a file that has since been deleted never will.
 */
internal val FrameCapture.mosaicAspectRatio: Float
  get() {
    val width = imageWidth ?: return SNAPSHOT_MOSAIC_FALLBACK_RATIO
    val height = imageHeight ?: return SNAPSHOT_MOSAIC_FALLBACK_RATIO
    if (width <= 0 || height <= 0) return SNAPSHOT_MOSAIC_FALLBACK_RATIO
    return width.toFloat() / height.toFloat()
  }

/**
 * One line of the mosaic: the frames on it, the height they share, and whether they reach the right
 * edge.
 */
internal data class MosaicRow(
  val captures: List<FrameCapture>,
  val height: Dp,
  /** False only for a short final line, which keeps its natural height rather than being stretched. */
  val fillsWidth: Boolean,
)

/**
 * Splits frames into justified rows — the photo-wall arrangement, and the reason this is not a
 * column grid with spans.
 *
 * A line keeps taking frames until their combined width at the target height reaches
 * [availableWidth], and is then rescaled to the one height at which every frame on it meets the
 * right edge. Nothing is cropped: a frame's own ratio still sets its width, so it is the *line* that
 * gives, not the picture.
 *
 * That is what a span-based grid cannot do. There, a line's height is the tallest tile on it, so a
 * wide frame — short by nature — leaves a gap under itself beside a portrait one; and a wide frame
 * that will not fit in the columns left over starts a new line, leaving the old one short of the
 * edge. Both artefacts disappear once height is derived from the line instead of the line from its
 * tallest tile.
 *
 * Only the final line can normally end short, because there is nothing left to add to it. It keeps
 * the target height rather than being stretched, so one leftover frame looks like any other tile.
 *
 * [maxRowHeightScale] bounds how much taller than the target a line may become. A line is only ever
 * justified below the target in practice — it stopped taking frames the moment it reached the width —
 * so the bound only bites on frames whose stored size is nonsense (a truncated file can report a
 * 1×60000 header): without it, a line of those would justify to a height taller than the screen, and
 * once the gaps alone exceeded the width, to a negative one.
 */
internal fun mosaicRows(
  captures: List<FrameCapture>,
  columns: Int,
  availableWidth: Dp,
  spacing: Dp,
  maxRowHeightScale: Float = 1.5f,
): List<MosaicRow> {
  if (captures.isEmpty() || columns <= 0) return emptyList()
  // Aim for `columns` frames per line: the average frame is square, so one line's worth of frames is
  // `columns` frames wide and `availableWidth / columns` tall.
  val targetHeight = availableWidth / columns
  val cap = targetHeight * maxRowHeightScale
  val width = availableWidth.value
  val gap = spacing.value

  val rows = mutableListOf<MosaicRow>()
  var start = 0
  while (start < captures.size) {
    var end = start
    var ratioSum = 0f
    while (end < captures.size) {
      ratioSum += captures[end].mosaicAspectRatio
      end++
      if (ratioSum * targetHeight.value + gap * (end - start - 1) >= width) break
    }

    val line = captures.subList(start, end).toList()
    val slack = availableWidth - spacing * (line.size - 1)
    val justified = if (slack > 0.dp) slack / ratioSum else null
    if (justified != null && justified <= cap) {
      rows += MosaicRow(line, justified, fillsWidth = true)
    } else {
      rows += MosaicRow(line, targetHeight, fillsWidth = false)
    }
    start = end
  }
  return rows
}

/**
 * The decoded thumbnail for [capture], asking for it if the store does not have it.
 *
 * Asking again when the bitmap is *gone* is the whole point. The store caps itself by bytes and drops
 * the least recently used entry, so a tile that is still on screen can lose its bitmap under it while
 * the user scrolls. An effect keyed on the capture id alone never runs a second time, which would
 * leave that tile showing its placeholder until it scrolled out of the list and back in.
 *
 * Every snapshot list, grid and mosaic cell goes through here for that reason — one of them loading
 * its own way is one of them getting this wrong.
 */
@Composable
internal fun rememberSnapshotThumbnail(
  capture: FrameCapture,
  thumbnails: Map<Long, Bitmap>,
  onLoad: (FrameCapture) -> Unit,
): Bitmap? {
  val thumbnail = thumbnails[capture.id]
  LaunchedEffect(capture.id, thumbnail != null) { onLoad(capture) }
  return thumbnail
}

/**
 * Tap behaviour shared by both snapshot screens: toggle the row while a selection is active, open the
 * full-screen viewer otherwise. Returns the selection unchanged when it opened the viewer.
 */
internal fun snapshotItemClick(
  selection: SelectionState<Long>,
  items: List<FrameCapture>,
  capture: FrameCapture,
  backStack: NavBackStack<Screen>,
): SelectionState<Long> {
  if (selection.isInSelectionMode) return selection.toggle(capture.id)
  // The viewer carries the whole list so it can swipe between neighbours, and its route key has to
  // include that list's identity — see SnapshotDetailScreen.
  backStack.navigateTo(
    SnapshotDetailScreen(
      captures = items.map { it.toDetailItem() },
      initialIndex = items.indexOf(capture).coerceAtLeast(0),
    ),
  )
  return selection
}

/**
 * The snapshot cell: a frame filling its tile.
 *
 * No caption: a photo grid reads as photos, and the title and timestamp are what the list layout and
 * the full-screen viewer are for.
 *
 * No rounded corners either — the grid is the pictures, and rounding every tile turns a photo wall
 * into a row of cards. This is a deliberate exception to the app's card-radius tokens; the folder
 * cards and list rows around it do follow them.
 *
 * The caller sizes it through [modifier], because the two layouts size a tile very differently: the
 * square grid gives it a whole cell, while a mosaic line hands it a share of the line's width
 * proportional to the frame's own ratio. Everything else about the tile is the same either way.
 */
@Composable
fun SnapshotGridItem(
  capture: FrameCapture,
  thumbnail: Bitmap?,
  isSelected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectionTint = animatedSelectionColor(isSelected)

  Box(
    modifier =
      modifier
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .tvFocusHighlight(AppShapeScale.none, focusedScale = 1.03f)
        .semantics { selected = isSelected }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center,
  ) {
    if (thumbnail != null) {
      Image(
        bitmap = thumbnail.asImageBitmap(),
        contentDescription = null,
        // A mosaic line draws each frame at its own ratio, so this crops nothing there; the square
        // grid is where it does the work.
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize(),
      )
    } else {
      // Also the state a snapshot whose gallery file the user deleted lands in: the record stays
      // visible with a placeholder instead of vanishing.
      Icon(
        Icons.RoundedFilled.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.size(40.dp),
      )
    }
    Box(modifier = Modifier.matchParentSize().background(selectionTint))
    SelectionIndicator(
      selected = isSelected,
      modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
    )
  }
}

/**
 * A folder in the grid, drawn to the same recipe as the browser's own folder cards —
 * `ui/browser/cards/FolderCard.kt` and `NetworkFolderCard.kt` — so a folder looks the same here as
 * anywhere else in the app: the filled folder glyph in a 20:17 box, name centred underneath.
 *
 * Deliberately no snapshot count: no other folder card shows one, and matching them is the point.
 */
@Composable
fun SnapshotFolderGridItem(
  name: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectionTint = animatedSelectionColor(isSelected)

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.03f)
        .semantics { selected = isSelected }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 4.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier.fillMaxWidth().aspectRatio(FOLDER_GLYPH_ASPECT),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.RoundedFilled.Folder,
        contentDescription = stringResource(R.string.ui_folder),
        modifier = Modifier.fillMaxWidth().aspectRatio(FOLDER_GLYPH_ASPECT),
        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
      )
      Box(modifier = Modifier.matchParentSize().background(selectionTint))
      SelectionIndicator(
        selected = isSelected,
        modifier = Modifier.align(Alignment.TopEnd),
      )
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = name,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** The folder row form, matching the browser folder cards' list layout: 72dp glyph, name beside it. */
@Composable
fun SnapshotFolderListItem(
  name: String,
  isSelected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val selectionTint = animatedSelectionColor(isSelected)

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.01f)
        .semantics { selected = isSelected }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
      Icon(
        Icons.RoundedFilled.Folder,
        contentDescription = stringResource(R.string.ui_folder),
        modifier = Modifier.matchParentSize(),
        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
      )
      Box(modifier = Modifier.matchParentSize().background(selectionTint))
    }

    Spacer(modifier = Modifier.width(12.dp))

    Text(
      text = name,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )

    SelectionIndicator(selected = isSelected)
  }
}

/** The compact one-per-row form: a square thumbnail beside the title, timestamp and capture date. */
@Composable
fun SnapshotListItem(
  capture: FrameCapture,
  thumbnail: Bitmap?,
  isSelected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  val selectionTint = animatedSelectionColor(isSelected)

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.01f)
        .semantics { selected = isSelected }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 12.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier =
        Modifier
          .size(56.dp)
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      if (thumbnail != null) {
        Image(
          bitmap = thumbnail.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.matchParentSize(),
        )
      } else {
        Icon(
          Icons.RoundedFilled.Image,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
          modifier = Modifier.size(24.dp),
        )
      }
      Box(modifier = Modifier.matchParentSize().background(selectionTint))
    }

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = capture.videoTitle,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = capture.formattedPosition,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
      Text(
        text = capture.formattedCaptureDate,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }

    SelectionIndicator(selected = isSelected)
  }
}
