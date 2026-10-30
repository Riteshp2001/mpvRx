/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.dialogs

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.storage.StorageVolumeUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderPickerDialog(
  modifier: Modifier = Modifier,
  isOpen: Boolean,
  currentPath: String = Environment.getExternalStorageDirectory().absolutePath,
  onDismiss: () -> Unit,
  onFolderSelected: (String) -> Unit,
) {
  if (!isOpen) return

  val context = LocalContext.current

  // Get all available storage volumes
  val storageVolumes =
    remember(isOpen) {
      StorageVolumeUtils.getAllStorageVolumes(context)
    }

  // If there's only one storage volume, start there directly
  // Otherwise, start at storage root view to show all volumes
  var selectedPath by rememberSaveable(isOpen, currentPath, storageVolumes.size) {
    val requested = File(currentPath)
    val initialPath = when {
      requested.isDirectory -> requested.absolutePath
      storageVolumes.size == 1 -> StorageVolumeUtils.getVolumePath(storageVolumes.first())
      else -> null
    }
    mutableStateOf(initialPath)
  }
  var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
  var query by rememberSaveable(isOpen) { mutableStateOf("") }

  // Determine what to show based on selectedPath
  val showStorageRoot = selectedPath == null

  val currentDir =
    remember(selectedPath) {
      selectedPath?.let { File(it) }
    }

  LaunchedEffect(selectedPath) {
    query = ""
  }

  val listing by produceState<FolderPickerListing?>(null, selectedPath) {
    val path = selectedPath
    value = withContext(Dispatchers.IO) {
      val directories = runCatching {
        path?.let { File(it).listFiles { file -> file.isDirectory && !file.name.startsWith(".") } }
      }.getOrNull().orEmpty().distinctBy { it.absolutePath }.sortedBy { it.name.lowercase() }
      FolderPickerListing(path, directories)
    }
  }
  val currentListing = listing?.takeIf { it.path == selectedPath }
  val folders = remember(currentListing, query) {
    currentListing?.folders.orEmpty().filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
  }

  // Check if selected path is the same as current path
  val isSameAsSource =
    remember(selectedPath, currentPath) {
      selectedPath != null && selectedPath == currentPath
    }

  if (showCreateFolderDialog && selectedPath != null) {
    CreateFolderDialog(
      parentPath = selectedPath!!,
      onDismiss = { showCreateFolderDialog = false },
      onFolderCreated = { newFolderPath ->
        selectedPath = newFolderPath
        showCreateFolderDialog = false
      },
    )
    return
  }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title =
      androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_select_folder),
    subtitle = selectedPath
      ?: androidx.compose.ui.res
        .stringResource(app.gyrolet.mpvrx.R.string.ui_select_storage_location),
    warning =
      if (isSameAsSource) {
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_cannot_select_the_same_folder)
      } else {
        null
      },
    scrollContent = false,
    headerActions = {
      if (selectedPath != null) {
        FilledTonalIconButton(
          onClick = { selectedPath = currentDir?.parent },
          modifier = Modifier.size(40.dp),
          colors =
            IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
          shape = MaterialTheme.shapes.extraLarge,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.ArrowBack,
            contentDescription =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_go_back),
          )
        }
      }
      FilledTonalIconButton(
        onClick = { selectedPath = Environment.getExternalStorageDirectory().absolutePath },
        modifier = Modifier.size(40.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.Home,
          contentDescription =
            androidx.compose.ui.res.stringResource(
              app.gyrolet.mpvrx.R.string.ui_go_to_internal_storage,
            ),
        )
      }
      FilledTonalIconButton(
        onClick = { showCreateFolderDialog = true },
        enabled = selectedPath != null,
        modifier = Modifier.size(40.dp),
        colors =
          IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.CreateNewFolder,
          contentDescription =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_create_folder),
        )
      }
    },
    actions = {
      TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
      Button(
        onClick = { selectedPath?.let { onFolderSelected(it) } },
        enabled = selectedPath != null && !isSameAsSource,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_select),
          fontWeight = FontWeight.Bold,
        )
      }
    },
    modifier = modifier,
  ) {
    BackHandler(enabled = selectedPath != null && !showCreateFolderDialog) {
      selectedPath = currentDir?.parent
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!showStorageRoot) {
          PlayerSheetSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.folder_search_hint),
          )
        }

        // Folder/Volume list
        LazyColumn(
          modifier =
            Modifier
              .fillMaxWidth()
              .heightIn(min = 120.dp, max = 360.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          if (showStorageRoot) {
            // Show storage volumes
            items(storageVolumes, key = { "volume:${StorageVolumeUtils.getVolumePath(it) ?: it.toString()}" }) { volume ->
              val volumePath = StorageVolumeUtils.getVolumePath(volume)
              if (volumePath != null) {
                StorageVolumeItem(
                  context = context,
                  volume = volume,
                  volumePath = volumePath,
                  onClick = { selectedPath = volumePath },
                )
              }
            }

            if (storageVolumes.isEmpty()) {
              item {
                Text(
                  text =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_no_storage_devices_found),
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          } else {
            // Show folders
            items(folders, key = { "folder:${it.absolutePath}" }) { folder ->
              FolderItem(
                folder = folder,
                onClick = { selectedPath = folder.absolutePath },
              )
            }

            if (currentListing == null) {
              item(key = "loading") {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(24.dp))
              }
            } else if (folders.isEmpty()) {
              item {
                Text(
                  text =
                    androidx.compose.ui.res
                      .stringResource(
                        if (query.isBlank()) app.gyrolet.mpvrx.R.string.ui_no_subfolders
                        else app.gyrolet.mpvrx.R.string.generic_no_matching_options,
                      ),
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = FontWeight.Medium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(16.dp),
                )
              }
            }
          }
        }
      }
  }
}

@Composable
private fun FolderItem(
  folder: File,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Folder,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(28.dp),
    )
    Text(
      text = folder.name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CreateFolderDialog(
  parentPath: String,
  onDismiss: () -> Unit,
  onFolderCreated: (String) -> Unit,
) {
  var folderName by remember { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_create_new_folder),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = folderName,
          onValueChange = {
            folderName = it
            error = null
          },
          label = {
            Text(
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_folder_name),
              fontWeight = FontWeight.Medium,
            )
          },
          singleLine = true,
          isError = error != null,
          modifier = Modifier.fillMaxWidth(),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary,
              focusedLabelColor = MaterialTheme.colorScheme.primary,
            ),
          shape = MaterialTheme.shapes.extraLarge,
        )
        if (error != null) {
          Text(
            text = error!!,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (folderName.isBlank()) {
            error = "Folder name cannot be empty"
            return@Button
          }

          val newFolder = File(parentPath, folderName)
          if (newFolder.exists()) {
            error = "Folder already exists"
            return@Button
          }

          try {
            if (newFolder.mkdirs()) {
              onFolderCreated(newFolder.absolutePath)
            } else {
              error = "Failed to create folder"
            }
          } catch (e: Exception) {
            error = e.message ?: "Unknown error"
          }
        },
        enabled = folderName.isNotBlank(),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_create),
          fontWeight = FontWeight.Bold,
        )
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
      ) {
        Text(
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.generic_cancel),
          fontWeight = FontWeight.Medium,
        )
      }
    },
    containerColor = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shape = MaterialTheme.shapes.extraLarge,
  )
}

private data class FolderPickerListing(val path: String?, val folders: List<File>)
