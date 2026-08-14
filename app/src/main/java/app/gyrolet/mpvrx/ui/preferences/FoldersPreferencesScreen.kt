/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.preferences

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import app.gyrolet.mpvrx.preferences.BlacklistScope
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object FoldersPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<FoldersPreferences>()
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val blacklistedVideoFolders by preferences.blacklistedFolders.collectAsState()
    val blacklistedAudioFolders by preferences.blacklistedAudioFolders.collectAsState()
    val includeNoMediaFolders by preferences.includeNoMediaFolders.collectAsState()
    var availableFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var selectionState by remember { mutableStateOf(SelectionState<String>()) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    val settingsHighlight =
      rememberSettingsSearchHighlight(FoldersPreferencesScreen, MaterialTheme.colorScheme.primary)

    val allBlacklistedFolders = remember(blacklistedVideoFolders, blacklistedAudioFolders) {
      (blacklistedVideoFolders + blacklistedAudioFolders).toList().sorted()
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.pref_folders_title),
          colors = TopAppBarDefaults.topAppBarColors(),
          forceHeadlineSmall = true,
          isInSelectionMode = selectionState.isInSelectionMode,
          selectedCount = selectionState.selectedCount,
          totalCount = allBlacklistedFolders.size,
          onCancelSelection = { selectionState = selectionState.clear() },
          onBackClick =
            if (LocalShowSettingsBackArrow.current) {
              { backstack.popSafely() }
            } else {
              null
            },
          onDeleteClick = {
            preferences.removeBlacklistedFolders(selectionState.selectedIds)
            selectionState = selectionState.clear()
          },
          onSelectAll = { selectionState = selectionState.selectAll(allBlacklistedFolders) },
          onInvertSelection = { selectionState = selectionState.invertSelection(allBlacklistedFolders) },
          onDeselectAll = { selectionState = selectionState.clear() },
          additionalActions = {
            if (!selectionState.isInSelectionMode && allBlacklistedFolders.isNotEmpty()) {
              IconButton(
                onClick = { showClearAllDialog = true },
                modifier = Modifier.padding(horizontal = 2.dp),
              ) {
                Icon(
                  Icons.RoundedFilled.Clear,
                  contentDescription = stringResource(R.string.pref_folders_clear_all),
                  modifier = Modifier.size(28.dp),
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
          },
          useRemoveIcon = true,
        )
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .then(settingsHighlight),
      ) {
        if (!selectionState.isInSelectionMode) {
          // ── Media Library ─────────────────────────────────────────────
          PreferenceSectionHeader(title = stringResource(R.string.pref_media_library_section))

          NoMediaPreferenceCard(
            includeNoMediaFolders = includeNoMediaFolders,
            onIncludeNoMediaFoldersChanged = { enabled ->
              preferences.includeNoMediaFolders.set(enabled)
              app.gyrolet.mpvrx.repository.MediaFileRepository
                .clearCache()
              MediaLibraryEvents.notifyChanged()
            },
          )

          Spacer(modifier = Modifier.height(16.dp))

          // ── Hidden Folders ────────────────────────────────────────────
          PreferenceSectionHeader(title = stringResource(R.string.pref_hidden_folders_section))

          Text(
            text = stringResource(R.string.pref_folders_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Spacer(modifier = Modifier.height(8.dp))
        }

        if (allBlacklistedFolders.isEmpty()) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .weight(1f),
          ) {
            EmptyState(
              icon = Icons.RoundedFilled.FolderOff,
              title = stringResource(R.string.pref_folders_empty_title),
              message = stringResource(R.string.pref_folders_empty_message),
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(allBlacklistedFolders, key = { it }) { folderPath ->
              val isVideo = folderPath in blacklistedVideoFolders
              val isAudio = folderPath in blacklistedAudioFolders
              val scope = when {
                isVideo && isAudio -> BlacklistScope.BOTH
                isVideo -> BlacklistScope.VIDEO_ONLY
                else -> BlacklistScope.AUDIO_ONLY
              }

              BlacklistedFolderItem(
                folderPath = folderPath,
                scope = scope,
                isSelected = selectionState.isSelected(folderPath),
                isInSelectionMode = selectionState.isInSelectionMode,
                onRemove = {
                  preferences.removeBlacklistedFolder(folderPath)
                },
                onScopeChange = { newScope ->
                  preferences.addBlacklistedFolders(setOf(folderPath), newScope)
                },
                onLongClick = { selectionState = selectionState.toggle(folderPath) },
                onClick = {
                  if (selectionState.isInSelectionMode) selectionState = selectionState.toggle(folderPath)
                },
              )
            }
          }
        }

        if (!selectionState.isInSelectionMode) {
          Spacer(modifier = Modifier.height(16.dp))

          Card(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable {
                  showAddDialog = true
                  isLoading = true
                  coroutineScope.launch(Dispatchers.IO) {
                    try {
                      availableFolders = scanAllMediaFolders(context.applicationContext as Application)
                    } finally {
                      isLoading = false
                    }
                  }
                },
            colors =
              CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
              ),
          ) {
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
              Spacer(modifier = Modifier.padding(8.dp))
              Text(
                text = stringResource(R.string.pref_folders_add_folder),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }
    }

    if (showAddDialog) {
      AddFolderDialog(
        folders = availableFolders,
        blacklistedFolders = allBlacklistedFolders.toSet(),
        isLoading = isLoading,
        onDismiss = { showAddDialog = false },
        onAddFolders = { folderPaths, scope ->
          preferences.addBlacklistedFolders(folderPaths, scope)
        },
      )
    }

    if (showClearAllDialog) {
      AlertDialog(
        onDismissRequest = { showClearAllDialog = false },
        title = { Text(stringResource(R.string.pref_folders_clear_all_confirm_title)) },
        text = { Text(stringResource(R.string.pref_folders_clear_all_confirm_message)) },
        confirmButton = {
          TextButton(onClick = {
            preferences.clearAllBlacklistedFolders()
            showClearAllDialog = false
          }) {
            Text(stringResource(R.string.generic_confirm))
          }
        },
        dismissButton = {
          TextButton(onClick = { showClearAllDialog = false }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
      )
    }
  }
}

@Composable
private fun NoMediaPreferenceCard(
  includeNoMediaFolders: Boolean,
  onIncludeNoMediaFoldersChanged: (Boolean) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
      ),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable { onIncludeNoMediaFoldersChanged(!includeNoMediaFolders) }
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.pref_folders_include_nomedia_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = stringResource(R.string.pref_folders_include_nomedia_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconSwitch(
        checked = includeNoMediaFolders,
        onCheckedChange = onIncludeNoMediaFoldersChanged,
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlacklistedFolderItem(
  folderPath: String,
  scope: BlacklistScope,
  isSelected: Boolean,
  isInSelectionMode: Boolean,
  onRemove: () -> Unit,
  onScopeChange: (BlacklistScope) -> Unit,
  onLongClick: () -> Unit,
  onClick: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
      ),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .combinedClickable(onClick = onClick, onLongClick = onLongClick)
          .padding(16.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        if (isInSelectionMode) {
          Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        }
        Column {
          Text(
            text = folderPath.substringAfterLast('/'),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Text(
            text = folderPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (!isInSelectionMode) {
            Spacer(modifier = Modifier.height(4.dp))
            AssistChip(
              onClick = {
                val nextScope = when (scope) {
                  BlacklistScope.BOTH -> BlacklistScope.VIDEO_ONLY
                  BlacklistScope.VIDEO_ONLY -> BlacklistScope.AUDIO_ONLY
                  BlacklistScope.AUDIO_ONLY -> BlacklistScope.BOTH
                }
                onScopeChange(nextScope)
              },
              label = {
                Text(
                  text = when (scope) {
                    BlacklistScope.BOTH -> "Both (Video & Audio)"
                    BlacklistScope.VIDEO_ONLY -> "Videos Only"
                    BlacklistScope.AUDIO_ONLY -> "Audio Only"
                  },
                  style = MaterialTheme.typography.labelSmall
                )
              }
            )
          }
        }
      }
      if (!isInSelectionMode) {
        IconButton(onClick = onRemove) {
          Icon(
            imageVector = Icons.RoundedFilled.RemoveCircle,
            contentDescription = stringResource(R.string.delete),
            tint = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

@Composable
private fun AddFolderDialog(
  folders: List<VideoFolder>,
  blacklistedFolders: Set<String>,
  isLoading: Boolean,
  onDismiss: () -> Unit,
  onAddFolders: (Set<String>, BlacklistScope) -> Unit,
) {
  var selectionState by remember { mutableStateOf(SelectionState<String>()) }
  var showDropdown by remember { mutableStateOf(false) }
  var selectedScope by remember { mutableStateOf(BlacklistScope.BOTH) }

  val availableFolders =
    remember(folders, blacklistedFolders) {
      folders.filter { it.path !in blacklistedFolders }
    }
  val availableFolderPaths = remember(availableFolders) { availableFolders.map { it.path } }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = !isLoading && availableFolders.isNotEmpty()) { showDropdown = true },
      ) {
        Text(
          text =
            if (selectionState.isInSelectionMode) {
              stringResource(R.string.selected_items, selectionState.selectedCount, availableFolders.size)
            } else {
              stringResource(R.string.pref_folders_select_folders)
            },
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!isLoading && availableFolders.isNotEmpty()) {
          Icon(
            Icons.RoundedFilled.ArrowDropDown,
            contentDescription = stringResource(R.string.selection_options),
            modifier = Modifier.size(24.dp),
          )
        }
        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
          DropdownMenuItem(text = { Text(stringResource(R.string.select_all)) }, onClick = {
            selectionState = selectionState.selectAll(availableFolderPaths)
            showDropdown = false
          })
          DropdownMenuItem(text = { Text(stringResource(R.string.invert_selection)) }, onClick = {
            selectionState = selectionState.invertSelection(availableFolderPaths)
            showDropdown = false
          })
          DropdownMenuItem(text = { Text(stringResource(R.string.deselect_all)) }, onClick = {
            selectionState = selectionState.clear()
            showDropdown = false
          })
        }
      }
    },
    text = {
      if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.pref_folders_loading))
        }
      } else if (availableFolders.isEmpty()) {
        Text(stringResource(R.string.pref_folders_no_folders))
      } else {
        Column {
          LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            items(availableFolders, key = { it.path }) { folder ->
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clickable { selectionState = selectionState.toggle(folder.path) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Checkbox(
                  checked = selectionState.isSelected(folder.path),
                  onCheckedChange = { selectionState = selectionState.toggle(folder.path) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                  Text(text = folder.name, style = MaterialTheme.typography.bodyLarge)
                  Text(
                    text = folder.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

          Text(
            text = "Blacklist for",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )

          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.clickable { selectedScope = BlacklistScope.BOTH },
            ) {
              RadioButton(
                selected = selectedScope == BlacklistScope.BOTH,
                onClick = { selectedScope = BlacklistScope.BOTH },
              )
              Text("Both", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.clickable { selectedScope = BlacklistScope.VIDEO_ONLY },
            ) {
              RadioButton(
                selected = selectedScope == BlacklistScope.VIDEO_ONLY,
                onClick = { selectedScope = BlacklistScope.VIDEO_ONLY },
              )
              Text("Video", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.clickable { selectedScope = BlacklistScope.AUDIO_ONLY },
            ) {
              RadioButton(
                selected = selectedScope == BlacklistScope.AUDIO_ONLY,
                onClick = { selectedScope = BlacklistScope.AUDIO_ONLY },
              )
              Text("Audio", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onAddFolders(selectionState.selectedIds, selectedScope)
          onDismiss()
        },
        enabled = selectionState.isInSelectionMode && !isLoading,
      ) { Text(stringResource(R.string.generic_ok)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

@Composable
internal fun StorageRootPickerCard(
  currentPath: String,
  onPickClick: () -> Unit,
  onClearClick: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
      ),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(onClick = onPickClick)
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Folder,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text =
            androidx.compose.ui.res
              .stringResource(app.gyrolet.mpvrx.R.string.ui_base_storage_folder),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text =
            if (currentPath.isNotEmpty()) {
              getSimplifiedStoragePath(currentPath)
            } else {
              "Tap to select - creates Subtitles/, Fonts/, scripts/, script-opts/ subdirs"
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (currentPath.isNotEmpty()) {
        IconButton(onClick = onClearClick) {
          Icon(
            imageVector = Icons.RoundedFilled.Clear,
            contentDescription =
              androidx.compose.ui.res.stringResource(
                app.gyrolet.mpvrx.R.string.pref_clear_content_desc,
              ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

internal fun getSimplifiedStoragePath(uriString: String): String =
  try {
    Uri.decode(uriString).substringAfterLast(':').ifEmpty { uriString }
  } catch (_: Exception) {
    uriString
  }

private suspend fun scanAllMediaFolders(context: Application): List<VideoFolder> {
  val repoFolders = app.gyrolet.mpvrx.repository.MediaFileRepository
    .getAllVideoFoldersFast(context = context, includeAudioOverride = true)

  val songs = try {
    app.gyrolet.mpvrx.ui.browser.music.MusicLibraryScanner.scanSongs(context)
  } catch (_: Exception) {
    emptyList()
  }

  val audioFolderPaths = songs.mapNotNull { java.io.File(it.path).parent }.toSet()
  val existingPaths = repoFolders.map { it.path.lowercase() }.toSet()

  val extraAudioFolders = audioFolderPaths.filter { path -> path.lowercase() !in existingPaths }.map { path ->
    VideoFolder(
      bucketId = path,
      name = path.substringAfterLast('/'),
      path = path,
      videoCount = 0,
      totalSize = 0L,
      totalDuration = 0L,
      lastModified = 0L,
    )
  }

  return (repoFolders + extraAudioFolders).sortedBy { it.name.lowercase() }
}
