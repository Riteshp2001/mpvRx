/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.framecapture.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.framecapture.SnapshotFolderRow
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField

/**
 * Create and rename share one dialog: both are "name a folder, reject a blank or duplicate one".
 *
 * [errorMessage] is owned by the caller so the same dialog can report either rejection without
 * closing — the ViewModel decides, this only renders.
 */
@Composable
fun SnapshotFolderNameDialog(
  title: String,
  initialName: String,
  errorMessage: String?,
  confirmLabel: String,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var name by remember(initialName) { mutableStateOf(initialName) }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        singleLine = true,
        isError = errorMessage != null,
        label = { Text(stringResource(R.string.snapshot_folder_name_hint)) },
        supportingText = errorMessage?.let { { Text(it) } },
      )
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(name) }) { Text(confirmLabel) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

/** Confirms a folder deletion, spelling out how many snapshot records go with it. */
@Composable
fun SnapshotDeleteFolderDialog(
  folderCount: Int,
  snapshotCount: Int,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  paths: List<String> = emptyList(),
) {
  AlertDialog(
    singleRowActions = true,
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (folderCount > 1) {
          stringResource(R.string.snapshot_delete_folders_title, folderCount)
        } else if (snapshotCount > 0) {
          stringResource(R.string.snapshot_delete_folder_title)
        } else {
          stringResource(R.string.snapshot_delete_folder_selected_title)
        },
      )
    },
    text = {
      Text(
        if (snapshotCount > 0) {
          stringResource(R.string.snapshot_delete_folder_message, snapshotCount)
        } else {
          stringResource(R.string.snapshot_delete_folder_empty_message)
        },
      )
    },
    confirmButton = {
      app.gyrolet.mpvrx.presentation.components
        .DeleteSizeButton(paths = paths, onClick = onConfirm)
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

/**
 * The shared confirmation for deleting snapshot records without deleting a folder. Both the folder
 * page and the library page's root snapshots use it, so it lives with the other snapshot dialogs
 * rather than inside either screen.
 */
@Composable
fun SnapshotDeleteConfirmDialog(
  count: Int,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  paths: List<String> = emptyList(),
) {
  AlertDialog(
    singleRowActions = true,
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.snapshot_delete_selected_title, count)) },
    text = { Text(stringResource(R.string.snapshot_delete_message)) },
    confirmButton = {
      app.gyrolet.mpvrx.presentation.components
        .DeleteSizeButton(paths = paths, onClick = onConfirm)
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

/**
 * The destination picker, used both for filing existing snapshots and for choosing where a new capture
 * goes.
 *
 * Rows are radio options with an explicit confirm rather than tap-to-pick, because a default is only
 * meaningful if the user can see it and accept it without touching anything: [preselectedFolderId] is
 * the folder they used last time, and a null selection means the snapshot library's root.
 *
 * [requirePick] exists for the two callers' different shapes. Saving a capture with nothing chosen
 * means the root, so a default is the right starting point; moving an existing snapshot with nothing
 * chosen would mean "move everything to the root", which is never why someone opened this dialog — so
 * that path starts with no row picked and the confirm disabled until they choose one.
 *
 * The root is always offered, including when the caller is already showing root snapshots — moving
 * something to where it already is costs nothing, and special-casing the exclusion per screen would
 * be two rules to keep in sync instead of none.
 */
@Composable
fun SnapshotMoveTargetDialog(
  folders: List<SnapshotFolderRow>,
  confirmLabel: String,
  preselectedFolderId: Long? = null,
  requirePick: Boolean = false,
  onConfirm: (Long?) -> Unit,
  onCreateFolder: () -> Unit,
  onDismiss: () -> Unit,
) {
  var picked by remember(preselectedFolderId) { mutableStateOf(preselectedFolderId) }
  var hasPicked by remember(preselectedFolderId) { mutableStateOf(!requirePick) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.snapshot_move_to_folder)) },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        item(key = "root") {
          MoveTargetRow(
            name = stringResource(R.string.snapshot_root),
            icon = Icons.RoundedFilled.FolderOpen,
            isPicked = hasPicked && picked == null,
            onClick = {
              picked = null
              hasPicked = true
            },
          )
        }
        items(folders, key = { it.id }) { folder ->
          MoveTargetRow(
            name = folder.name,
            icon = Icons.RoundedFilled.Folder,
            isPicked = hasPicked && picked == folder.id,
            onClick = {
              picked = folder.id
              hasPicked = true
            },
          )
        }
        item(key = "new") {
          MoveTargetRow(
            name = stringResource(R.string.snapshot_new_folder),
            icon = Icons.RoundedFilled.CreateNewFolder,
            isPicked = false,
            onClick = onCreateFolder,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = hasPicked,
        onClick = { onConfirm(picked) },
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

@Composable
private fun MoveTargetRow(
  name: String,
  icon: AppIcon,
  isPicked: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    RadioButton(selected = isPicked, onClick = onClick)
    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
    Text(
      text = name,
      style = MaterialTheme.typography.bodyLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}
