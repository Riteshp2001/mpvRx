/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.framecapture

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FolderWriteResult
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotDeleteConfirmDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotFolderNameDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotMoveTargetDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotSortDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * One folder's snapshots.
 *
 * The route carries the folder id and nothing else — a name would go stale the moment the folder is
 * renamed, and `folderId` must be part of the `viewModel()` key anyway: Navigation3 gives entries no
 * ViewModelStore of their own, so without it opening a second folder would hand back the first
 * folder's contents.
 */
@Serializable
data class SnapshotFolderScreen(val folderId: Long) : Screen {

  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val viewModel: SnapshotViewModel =
      viewModel(
        key = "SnapshotFolder_$folderId",
        factory = SnapshotViewModel.factory(context.applicationContext as Application, folderId),
      )

    val content by viewModel.content.collectAsState()
    val thumbnails by viewModel.thumbnailCache.collectAsState()
    val bottomInset = LocalNavigationBarHeight.current
    val sortType by browserPreferences.snapshotSortType.collectAsState()
    val sortOrder by browserPreferences.snapshotSortOrder.collectAsState()
    val viewPreferences = browserPreferences.snapshotItemView
    val layoutMode by viewPreferences.layoutMode.collectAsState()
    val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
    val gridColumnsPortrait by viewPreferences.gridColumnsPortrait.collectAsState()
    val gridColumnsLandscape by viewPreferences.gridColumnsLandscape.collectAsState()
    val landscape =
      LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // Zero means "no manual override", which hands the column count back to the automatic rule.
    val manualColumns =
      if (manualGrid) (if (landscape) gridColumnsLandscape else gridColumnsPortrait) else 0

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var selection by remember { mutableStateOf(SelectionState<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
      if (isSearching) focusRequester.requestFocus()
    }

    val displayItems =
      remember(content.snapshots, sortType, sortOrder, searchQuery) {
        content.snapshots
          .filter { searchQuery.isBlank() || it.videoTitle.contains(searchQuery, ignoreCase = true) }
          .sortedWith(snapshotComparator(sortType, sortOrder))
      }
    val displayIds = remember(displayItems) { displayItems.map { it.id } }

    // Deleting or filtering away the last selected row would otherwise leave the bar in selection mode
    // with nothing selected and no way back out except the system Back.
    LaunchedEffect(displayIds, selection) {
      if (selection.isInSelectionMode && displayIds.none { it in selection.selectedIds }) {
        selection = selection.clear()
      }
    }

    BackHandler(enabled = selection.isInSelectionMode || isSearching) {
      if (selection.isInSelectionMode) {
        selection = selection.clear()
      } else {
        isSearching = false
        searchQuery = ""
      }
    }

    SnapshotSortDialog(isOpen = showSortDialog, onDismiss = { showSortDialog = false })

    if (confirmDelete) {
      SnapshotDeleteConfirmDialog(
        count = selection.selectedCount,
        onConfirm = {
          confirmDelete = false
          viewModel.delete(selection.selectedIds)
          selection = selection.clear()
        },
        onDismiss = { confirmDelete = false },
      )
    }

    if (moveDialogOpen) {
      SnapshotMoveTargetDialog(
        folders = content.moveTargets,
        confirmLabel = stringResource(R.string.snapshot_move),
        requirePick = true,
        onConfirm = { targetFolderId ->
          moveDialogOpen = false
          viewModel.move(selection.selectedIds, targetFolderId)
          selection = selection.clear()
        },
        onCreateFolder = {
          moveDialogOpen = false
          nameError = null
          nameDialogOpen = true
        },
        onDismiss = { moveDialogOpen = false },
      )
    }

    if (nameDialogOpen) {
      SnapshotFolderNameDialog(
        title = stringResource(R.string.snapshot_create_folder_title),
        initialName = "",
        errorMessage = nameError,
        confirmLabel = stringResource(R.string.snapshot_folder_create),
        onConfirm = { name ->
          nameError = null
          viewModel.createFolder(name) { result ->
            when (result) {
              is FolderWriteResult.Ok -> {
                // The folder was made for these snapshots, so filing them straight into it is what the
                // user asked for by choosing "New folder" from the move dialog.
                viewModel.move(selection.selectedIds, result.id)
                selection = selection.clear()
                nameDialogOpen = false
              }
              FolderWriteResult.BlankName -> nameError = context.getString(R.string.snapshot_folder_name_blank)
              FolderWriteResult.DuplicateName -> nameError = context.getString(R.string.snapshot_folder_name_taken)
            }
          }
        },
        onDismiss = {
          nameDialogOpen = false
          nameError = null
        },
      )
    }

    Scaffold(
      containerColor = wallpaperAwareBackgroundColor(),
      topBar = {
        when {
          selection.isInSelectionMode ->
            BrowserTopBar(
              title = content.folder?.name ?: stringResource(R.string.ui_snapshots),
              isInSelectionMode = true,
              selectedCount = selection.selectedCount,
              totalCount = displayItems.size,
              onCancelSelection = { selection = selection.clear() },
              onDeleteClick = { confirmDelete = true },
              onSelectAll = { selection = selection.selectAll(displayIds) },
              onInvertSelection = { selection = selection.invertSelection(displayIds) },
              onDeselectAll = { selection = selection.clear() },
              additionalActions = {
                IconButton(onClick = { moveDialogOpen = true }) {
                  Icon(
                    Icons.RoundedFilled.DriveFileMove,
                    contentDescription = stringResource(R.string.snapshot_move_to_folder),
                  )
                }
              },
            )

          isSearching ->
            InlineSearchBar(
              query = searchQuery,
              onQueryChange = { searchQuery = it },
              onSearch = { },
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
              inputFieldModifier = Modifier.focusRequester(focusRequester),
              placeholder = { Text(stringResource(R.string.snapshot_search_placeholder)) },
              leadingIcon = {
                Icon(
                  Icons.RoundedFilled.Search,
                  contentDescription = stringResource(R.string.settings_search_title),
                )
              },
              trailingIcon = {
                IconButton(
                  onClick = {
                    isSearching = false
                    searchQuery = ""
                  },
                ) {
                  Icon(
                    Icons.RoundedFilled.Close,
                    contentDescription = stringResource(R.string.generic_cancel),
                  )
                }
              },
              shape = RoundedCornerShape(28.dp),
              tonalElevation = 6.dp,
            )

          else ->
            BrowserTopBar(
              title = content.folder?.name ?: stringResource(R.string.ui_snapshots),
              isInSelectionMode = false,
              selectedCount = 0,
              totalCount = content.snapshots.size,
              onCancelSelection = { },
              onBackClick = { backStack.popSafely() },
              onSortClick = { showSortDialog = true },
              onSearchClick = { isSearching = true },
            )
        }
      },
    ) { paddingValues ->
      // No background of its own: the Scaffold's container is wallpaper-aware, and painting the theme
      // background here would cover the wallpaper over the whole content area.
      Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        when {
          content.snapshots.isEmpty() ->
            EmptyState(
              icon = Icons.RoundedFilled.FolderOpen,
              title = stringResource(R.string.snapshot_folder_empty_title),
              message = stringResource(R.string.snapshot_folder_empty_message),
            )

          displayItems.isEmpty() ->
            EmptyState(
              icon = Icons.RoundedFilled.Search,
              title = stringResource(R.string.snapshot_no_results),
              message = stringResource(R.string.snapshot_search_placeholder),
            )

          layoutMode == MediaLayoutMode.LIST ->
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = bottomInset + 8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              items(displayItems, key = { it.id }) { capture ->
                val thumbnail = rememberSnapshotThumbnail(capture, thumbnails, viewModel::loadThumbnail)
                SnapshotListItem(
                  capture = capture,
                  thumbnail = thumbnail,
                  isSelected = selection.isSelected(capture.id),
                  onClick = { selection = snapshotItemClick(selection, displayItems, capture, backStack) },
                  onLongClick = {
                    selection =
                      if (selection.isInSelectionMode) {
                        selection.selectRange(capture.id, displayIds)
                      } else {
                        selection.toggle(capture.id)
                      }
                  },
                )
              }
            }

          layoutMode == MediaLayoutMode.MOSAIC ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              // The mosaic justifies its own lines, so the column count is not a column count here —
              // it is how many frames a line aims for before it is scaled flush with the edge.
              val lineWidth = maxWidth - SNAPSHOT_GRID_SPACING * 2
              val columns = snapshotGridColumns(lineWidth, manualColumns)
              val rows =
                remember(displayItems, columns, lineWidth) {
                  mosaicRows(displayItems, columns, lineWidth, SNAPSHOT_GRID_SPACING)
                }

              // Snapshots recorded before the mosaic existed carry no size to tile by. Reading them
              // waits until here rather than running on every folder open: it is a header pass over
              // every file in the folder, and nothing but this layout wants the answer.
              LaunchedEffect(content.snapshots) { viewModel.backfillImageSizes(content.snapshots) }

              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = SNAPSHOT_GRID_SPACING,
                    end = SNAPSHOT_GRID_SPACING,
                    top = SNAPSHOT_GRID_SPACING,
                    bottom = bottomInset + SNAPSHOT_GRID_SPACING,
                  ),
                verticalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
              ) {
                // Keyed off the line's first frame, which is the one frame of a line guaranteed to
                // stay put when the rest of the line is filled in by the size backfill.
                items(rows, key = { it.captures.first().id }) { row ->
                  Row(
                    modifier = Modifier.fillMaxWidth().height(row.height),
                    horizontalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
                  ) {
                    row.captures.forEach { capture ->
                      val thumbnail = rememberSnapshotThumbnail(capture, thumbnails, viewModel::loadThumbnail)
                      SnapshotGridItem(
                        capture = capture,
                        thumbnail = thumbnail,
                        isSelected = selection.isSelected(capture.id),
                        onClick = { selection = snapshotItemClick(selection, displayItems, capture, backStack) },
                        onLongClick = {
                          selection =
                            if (selection.isInSelectionMode) {
                              selection.selectRange(capture.id, displayIds)
                            } else {
                              selection.toggle(capture.id)
                            }
                        },
                        // A justified line shares its width out by ratio, which lands each frame at
                        // exactly its own shape. A short final line is laid out at its natural widths
                        // instead, and simply ends before the edge.
                        modifier =
                          if (row.fillsWidth) {
                            Modifier.weight(capture.mosaicAspectRatio).fillMaxHeight()
                          } else {
                            Modifier.width(row.height * capture.mosaicAspectRatio).fillMaxHeight()
                          },
                      )
                    }
                  }
                }
              }
            }

          else ->
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              // The grid's own padding is taken off before counting columns, so a screen sitting right
              // on a column boundary does not get one column more than it can fit.
              val columns = snapshotGridColumns(maxWidth - SNAPSHOT_GRID_SPACING * 2, manualColumns)

              LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = SNAPSHOT_GRID_SPACING,
                    end = SNAPSHOT_GRID_SPACING,
                    top = SNAPSHOT_GRID_SPACING,
                    bottom = bottomInset + SNAPSHOT_GRID_SPACING,
                  ),
                horizontalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
                verticalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
              ) {
                items(displayItems, key = { it.id }) { capture ->
                  val thumbnail = rememberSnapshotThumbnail(capture, thumbnails, viewModel::loadThumbnail)
                  SnapshotGridItem(
                    capture = capture,
                    thumbnail = thumbnail,
                    isSelected = selection.isSelected(capture.id),
                    onClick = { selection = snapshotItemClick(selection, displayItems, capture, backStack) },
                    onLongClick = {
                      selection =
                        if (selection.isInSelectionMode) {
                          selection.selectRange(capture.id, displayIds)
                        } else {
                          selection.toggle(capture.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                  )
                }
              }
            }
        }
      }
    }
  }
}
