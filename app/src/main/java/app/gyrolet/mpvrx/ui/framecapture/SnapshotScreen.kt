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
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.framecapture.FolderWriteResult
import app.gyrolet.mpvrx.domain.framecapture.FrameCapture
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.SnapshotFolderSortType
import app.gyrolet.mpvrx.preferences.SnapshotSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotDeleteConfirmDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotDeleteFolderDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotFolderNameDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotMoveTargetDialog
import app.gyrolet.mpvrx.ui.framecapture.dialogs.SnapshotSortDialog
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

/**
 * The root of the snapshot library: the folders, and the snapshots that are not in any of them.
 *
 * Both live on this page because a snapshot saved without picking a folder has to be visible
 * somewhere — a separate "ungrouped" screen was considered and dropped in favour of the gallery
 * convention of listing loose pictures below the albums.
 */
@Serializable
object SnapshotScreen : Screen {

  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val viewModel: SnapshotLibraryViewModel =
      viewModel(factory = SnapshotLibraryViewModel.factory(context.applicationContext as Application))

    val library by viewModel.library.collectAsState()
    val thumbnails by viewModel.thumbnailCache.collectAsState()
    val bottomInset = LocalNavigationBarHeight.current
    val folderSortType by browserPreferences.snapshotFolderSortType.collectAsState()
    val folderSortOrder by browserPreferences.snapshotFolderSortOrder.collectAsState()
    val viewPreferences = browserPreferences.snapshotLibraryView
    val layoutMode by viewPreferences.layoutMode.collectAsState()
    val manualGrid by viewPreferences.manualGridColumnsEnabled.collectAsState()
    val gridColumnsPortrait by viewPreferences.gridColumnsPortrait.collectAsState()
    val gridColumnsLandscape by viewPreferences.gridColumnsLandscape.collectAsState()
    val landscape =
      LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var folderSelection by remember { mutableStateOf(SelectionState<Long>()) }
    var captureSelection by remember { mutableStateOf(SelectionState<Long>()) }
    var confirmFolderDelete by remember { mutableStateOf(false) }
    var confirmCaptureDelete by remember { mutableStateOf(false) }
    var moveDialogOpen by remember { mutableStateOf(false) }
    // Non-null while the name dialog is up. A null folderId means "creating"; otherwise "renaming".
    var nameDialog by remember { mutableStateOf<NameDialogState?>(null) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearching) {
      if (isSearching) focusRequester.requestFocus()
    }

    val query = searchQuery.trim()
    val searching = isSearching && query.isNotEmpty()

    // Section labels are read here, not inside a `remember`: stringResource is a composable call and
    // cannot run inside a remember calculation lambda.
    val foldersLabel = stringResource(R.string.snapshot_section_folders)
    val snapshotsLabel = stringResource(R.string.snapshot_section_snapshots)

    val visibleFolders =
      remember(library.folders, query, folderSortType, folderSortOrder, searching) {
        val matched =
          if (searching) {
            library.folders.filter { it.name.contains(query, ignoreCase = true) }
          } else {
            library.folders
          }
        matched.sortedWith(folderComparator(folderSortType, folderSortOrder))
      }
    val visibleCaptures =
      remember(library.rootCaptures, library.allCaptures, query, searching, folderSortOrder) {
        val matched =
          // Searching reaches across every folder: a match inside a folder is exactly what the user is
          // looking for, and the results are labelled as snapshots rather than as this page's own rows.
          if (searching) {
            library.allCaptures.filter { it.videoTitle.contains(query, ignoreCase = true) }
          } else {
            library.rootCaptures
          }
        // The panel's fields describe folders, but its direction is the page's direction: leaving the
        // pictures pinned to newest-first made half the page ignore the control. Capture time is the
        // only ordering a loose snapshot has, so that is what the chosen direction drives.
        matched.sortedWith(snapshotComparator(SnapshotSortType.CapturedAt, folderSortOrder))
      }

    val folderIds = remember(visibleFolders) { visibleFolders.map { it.id } }
    val captureIds = remember(visibleCaptures) { visibleCaptures.map { it.id } }

    // Deleting or filtering away the last selected row would otherwise leave the bar in selection mode
    // with nothing selected and no way back out except the system Back. Both selection domains are
    // checked because only one of them can be active at a time.
    LaunchedEffect(folderIds, captureIds, folderSelection, captureSelection) {
      if (folderSelection.isInSelectionMode && folderIds.none { it in folderSelection.selectedIds }) {
        folderSelection = folderSelection.clear()
      }
      if (captureSelection.isInSelectionMode && captureIds.none { it in captureSelection.selectedIds }) {
        captureSelection = captureSelection.clear()
      }
    }

    BackHandler(enabled = folderSelection.isInSelectionMode || captureSelection.isInSelectionMode || isSearching) {
      when {
        folderSelection.isInSelectionMode -> folderSelection = folderSelection.clear()
        captureSelection.isInSelectionMode -> captureSelection = captureSelection.clear()
        else -> {
          isSearching = false
          searchQuery = ""
        }
      }
    }

    SnapshotSortDialog(isOpen = showSortDialog, onDismiss = { showSortDialog = false }, forFolders = true)

    nameDialog?.let { state ->
      SnapshotFolderNameDialog(
        title =
          stringResource(
            if (state.folderId == null) {
              R.string.snapshot_create_folder_title
            } else {
              R.string.snapshot_rename_folder_title
            },
          ),
        initialName = state.initialName,
        errorMessage = nameError,
        confirmLabel =
          stringResource(if (state.folderId == null) R.string.snapshot_folder_create else R.string.rename),
        onConfirm = { name ->
          nameError = null
          val onResult: (FolderWriteResult) -> Unit = { result ->
            when (result) {
              is FolderWriteResult.Ok -> {
                // Creating from the move dialog continues into the move; renaming just closes.
                state.pendingMoveIds?.let { viewModel.moveCaptures(it, result.id) }
                if (state.pendingMoveIds != null) captureSelection = captureSelection.clear()
                nameDialog = null
              }
              FolderWriteResult.BlankName -> nameError = context.getString(R.string.snapshot_folder_name_blank)
              FolderWriteResult.DuplicateName -> nameError = context.getString(R.string.snapshot_folder_name_taken)
            }
          }
          if (state.folderId == null) {
            viewModel.createFolder(name, onResult)
          } else {
            viewModel.renameFolder(state.folderId, name, onResult)
          }
        },
        onDismiss = {
          nameDialog = null
          nameError = null
        },
      )
    }

    if (confirmFolderDelete) {
      val selectedFolders = library.folders.filter { it.id in folderSelection.selectedIds }
      SnapshotDeleteFolderDialog(
        folderCount = selectedFolders.size,
        snapshotCount = selectedFolders.sumOf { it.captureCount },
        onConfirm = {
          confirmFolderDelete = false
          viewModel.deleteFolders(folderSelection.selectedIds)
          folderSelection = folderSelection.clear()
        },
        onDismiss = { confirmFolderDelete = false },
      )
    }

    if (confirmCaptureDelete) {
      SnapshotDeleteConfirmDialog(
        count = captureSelection.selectedCount,
        onConfirm = {
          confirmCaptureDelete = false
          viewModel.deleteCaptures(captureSelection.selectedIds)
          captureSelection = captureSelection.clear()
        },
        onDismiss = { confirmCaptureDelete = false },
      )
    }

    if (moveDialogOpen) {
      SnapshotMoveTargetDialog(
        folders = library.folders,
        confirmLabel = stringResource(R.string.snapshot_move),
        requirePick = true,
        onConfirm = { targetFolderId ->
          moveDialogOpen = false
          viewModel.moveCaptures(captureSelection.selectedIds, targetFolderId)
          captureSelection = captureSelection.clear()
        },
        onCreateFolder = {
          moveDialogOpen = false
          nameDialog = NameDialogState(folderId = null, initialName = "", pendingMoveIds = captureSelection.selectedIds)
        },
        onDismiss = { moveDialogOpen = false },
      )
    }

    Scaffold(
      containerColor = wallpaperAwareBackgroundColor(),
      topBar = {
        when {
          folderSelection.isInSelectionMode ->
            BrowserTopBar(
              title = stringResource(R.string.ui_snapshots),
              isInSelectionMode = true,
              selectedCount = folderSelection.selectedCount,
              totalCount = visibleFolders.size,
              onCancelSelection = { folderSelection = folderSelection.clear() },
              isSingleSelection = folderSelection.isSingleSelection,
              onRenameClick = {
                library.folders.firstOrNull { it.id in folderSelection.selectedIds }?.let { row ->
                  nameError = null
                  nameDialog = NameDialogState(folderId = row.id, initialName = row.name)
                }
              },
              onDeleteClick = { confirmFolderDelete = true },
              onSelectAll = { folderSelection = folderSelection.selectAll(folderIds) },
              onInvertSelection = { folderSelection = folderSelection.invertSelection(folderIds) },
              onDeselectAll = { folderSelection = folderSelection.clear() },
            )

          captureSelection.isInSelectionMode ->
            BrowserTopBar(
              title = stringResource(R.string.ui_snapshots),
              isInSelectionMode = true,
              selectedCount = captureSelection.selectedCount,
              totalCount = visibleCaptures.size,
              onCancelSelection = { captureSelection = captureSelection.clear() },
              onDeleteClick = { confirmCaptureDelete = true },
              onSelectAll = { captureSelection = captureSelection.selectAll(captureIds) },
              onInvertSelection = { captureSelection = captureSelection.invertSelection(captureIds) },
              onDeselectAll = { captureSelection = captureSelection.clear() },
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
              placeholder = { Text(stringResource(R.string.snapshot_search_folder_placeholder)) },
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
              title = stringResource(R.string.ui_snapshots),
              isInSelectionMode = false,
              selectedCount = 0,
              totalCount = library.folders.size + library.rootCaptures.size,
              onCancelSelection = { },
              onSortClick = { showSortDialog = true },
              onSearchClick = { isSearching = true },
              onSettingsClick = {
                backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
              },
              additionalActions = {
                IconButton(
                  onClick = {
                    nameError = null
                    nameDialog = NameDialogState(folderId = null, initialName = "")
                  },
                ) {
                  Icon(
                    Icons.RoundedFilled.CreateNewFolder,
                    contentDescription = stringResource(R.string.snapshot_new_folder),
                  )
                }
              },
            )
        }
      },
    ) { paddingValues ->
      // No background of its own: the Scaffold's container is wallpaper-aware, and painting the theme
      // background here would cover the wallpaper over the whole content area.
      Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        when {
          library.folders.isEmpty() && library.allCaptures.isEmpty() ->
            EmptyState(
              icon = Icons.RoundedFilled.Image,
              title = stringResource(R.string.snapshot_empty_title),
              message = stringResource(R.string.snapshot_empty_message),
            )

          visibleFolders.isEmpty() && visibleCaptures.isEmpty() ->
            EmptyState(
              icon = Icons.RoundedFilled.Search,
              title = stringResource(R.string.snapshot_no_results),
              message = stringResource(R.string.snapshot_search_folder_placeholder),
            )

          else ->
            SnapshotLibraryContent(
              folders = visibleFolders,
              captures = visibleCaptures,
              thumbnails = thumbnails,
              layoutMode = layoutMode,
              manualColumns = if (manualGrid) (if (landscape) gridColumnsLandscape else gridColumnsPortrait) else 0,
              bottomInset = bottomInset,
              foldersLabel = foldersLabel,
              snapshotsLabel = snapshotsLabel,
              // Section labels exist only to tell search results apart. Without a search there are no
              // sections — just the folders, and then the loose pictures — and a heading over either
              // of them says nothing the page has not already said.
              showSectionLabels = searching,
              isFolderSelected = { folderSelection.isSelected(it) },
              isCaptureSelected = { captureSelection.isSelected(it) },
              onLoadThumbnail = viewModel::loadThumbnail,
              onFolderClick = { row ->
                if (folderSelection.isInSelectionMode) {
                  folderSelection = folderSelection.toggle(row.id)
                } else if (!captureSelection.isInSelectionMode) {
                  backStack.navigateTo(SnapshotFolderScreen(folderId = row.id))
                }
              },
              onFolderLongClick = { row ->
                folderSelection =
                  if (folderSelection.isInSelectionMode) {
                    folderSelection.selectRange(row.id, folderIds)
                  } else {
                    // Starting a folder selection ends any snapshot selection: the two domains are
                    // deliberately never active at once.
                    captureSelection = captureSelection.clear()
                    folderSelection.toggle(row.id)
                  }
              },
              onCaptureClick = { capture ->
                if (captureSelection.isInSelectionMode) {
                  captureSelection = captureSelection.toggle(capture.id)
                } else if (!folderSelection.isInSelectionMode) {
                  backStack.navigateTo(
                    SnapshotDetailScreen(
                      captures = visibleCaptures.map { it.toDetailItem() },
                      initialIndex = visibleCaptures.indexOf(capture).coerceAtLeast(0),
                    ),
                  )
                }
              },
              onCaptureLongClick = { capture ->
                captureSelection =
                  if (captureSelection.isInSelectionMode) {
                    captureSelection.selectRange(capture.id, captureIds)
                  } else {
                    folderSelection = folderSelection.clear()
                    captureSelection.toggle(capture.id)
                  }
              },
            )
        }
      }
    }
  }
}

/** Which name dialog is up, and — when opened from the move dialog — what to move once it succeeds. */
private data class NameDialogState(
  val folderId: Long?,
  val initialName: String,
  val pendingMoveIds: Collection<Long>? = null,
)

/**
 * Folders and snapshots share this page but never a row.
 *
 * A folder is a different kind of object from a picture — the browser draws it as a glyph, not a tile —
 * so in the grid each folder row is emitted as a full-width item holding one row of folder cards. That
 * keeps folders above the pictures, keeps their cells the same width as the photo cells below them, and
 * keeps the rows lazy instead of composing every folder at once.
 */
@Composable
private fun SnapshotLibraryContent(
  folders: List<SnapshotFolderRow>,
  captures: List<FrameCapture>,
  thumbnails: Map<Long, Bitmap>,
  layoutMode: MediaLayoutMode,
  manualColumns: Int,
  bottomInset: Dp,
  foldersLabel: String,
  snapshotsLabel: String,
  showSectionLabels: Boolean,
  isFolderSelected: (Long) -> Boolean,
  isCaptureSelected: (Long) -> Boolean,
  onLoadThumbnail: (FrameCapture) -> Unit,
  onFolderClick: (SnapshotFolderRow) -> Unit,
  onFolderLongClick: (SnapshotFolderRow) -> Unit,
  onCaptureClick: (FrameCapture) -> Unit,
  onCaptureLongClick: (FrameCapture) -> Unit,
) {
  if (layoutMode == MediaLayoutMode.LIST) {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = bottomInset + 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (folders.isNotEmpty()) {
        if (showSectionLabels) {
          item(key = "foldersHeader") { SectionHeader(foldersLabel, Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        }
        items(folders, key = { "folder_${it.id}" }) { folder ->
          SnapshotFolderListItem(
            name = folder.name,
            isSelected = isFolderSelected(folder.id),
            onClick = { onFolderClick(folder) },
            onLongClick = { onFolderLongClick(folder) },
          )
        }
        item(key = "foldersGap") { Spacer(Modifier.height(12.dp)) }
      }
      if (showSectionLabels && captures.isNotEmpty()) {
        item(key = "snapshotsHeader") { SectionHeader(snapshotsLabel, Modifier.padding(bottom = 4.dp)) }
      }
      items(captures, key = { "capture_${it.id}" }) { capture ->
        val thumbnail = rememberSnapshotThumbnail(capture, thumbnails, onLoadThumbnail)
        SnapshotListItem(
          capture = capture,
          thumbnail = thumbnail,
          isSelected = isCaptureSelected(capture.id),
          onClick = { onCaptureClick(capture) },
          onLongClick = { onCaptureLongClick(capture) },
        )
      }
    }
    return
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    // The grid's own padding is taken off before counting columns, so a screen sitting right on a
    // column boundary does not get one column more than it can fit.
    val columns = snapshotGridColumns(maxWidth - SNAPSHOT_GRID_SPACING * 2, manualColumns)
    val gridPadding =
      PaddingValues(
        start = SNAPSHOT_GRID_SPACING,
        end = SNAPSHOT_GRID_SPACING,
        // A little more air under the app bar than between the tiles: the tight gutter is about the
        // photo rhythm, and the first row still needs to clear the bar.
        top = 8.dp,
        bottom = bottomInset + SNAPSHOT_GRID_SPACING,
      )

    LazyVerticalGrid(
      columns = GridCells.Fixed(columns),
      modifier = Modifier.fillMaxSize(),
      contentPadding = gridPadding,
      horizontalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
      verticalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
    ) {
      if (folders.isNotEmpty()) {
        if (showSectionLabels) {
          item(key = "foldersHeader", span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(foldersLabel, Modifier.padding(top = 8.dp, bottom = 4.dp))
          }
        }
        folders.chunked(columns).forEach { row ->
          item(key = "folderRow_${row.first().id}", span = { GridItemSpan(maxLineSpan) }) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(SNAPSHOT_GRID_SPACING),
            ) {
              row.forEach { folder ->
                SnapshotFolderGridItem(
                  name = folder.name,
                  isSelected = isFolderSelected(folder.id),
                  onClick = { onFolderClick(folder) },
                  onLongClick = { onFolderLongClick(folder) },
                  modifier = Modifier.weight(1f),
                )
              }
              // Keeps a short last row's cells the same width as every row above it.
              repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
        item(key = "foldersGap", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
      }

      if (showSectionLabels && captures.isNotEmpty()) {
        item(key = "snapshotsHeader", span = { GridItemSpan(maxLineSpan) }) {
          SectionHeader(snapshotsLabel, Modifier.padding(bottom = 4.dp))
        }
      }

      items(captures, key = { "capture_${it.id}" }) { capture ->
        val thumbnail = rememberSnapshotThumbnail(capture, thumbnails, onLoadThumbnail)
        SnapshotGridItem(
          capture = capture,
          thumbnail = thumbnail,
          isSelected = isCaptureSelected(capture.id),
          onClick = { onCaptureClick(capture) },
          onLongClick = { onCaptureLongClick(capture) },
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
      }
    }
  }
}

@Composable
private fun SectionHeader(
  label: String,
  modifier: Modifier = Modifier,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.fillMaxWidth(),
  )
}

private fun folderComparator(
  sortType: SnapshotFolderSortType,
  sortOrder: SortOrder,
): Comparator<SnapshotFolderRow> {
  val base =
    when (sortType) {
      SnapshotFolderSortType.Name -> compareBy<SnapshotFolderRow, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
      // Folder ids are autoincrement, so id order is creation order.
      SnapshotFolderSortType.CreatedAt -> compareBy { it.id }
      SnapshotFolderSortType.ItemCount ->
        compareBy<SnapshotFolderRow> { it.captureCount }.thenBy { it.name.lowercase() }
    }
  return if (sortOrder == SortOrder.Descending) base.reversed() else base
}
