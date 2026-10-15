/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package app.gyrolet.mpvrx.ui.preferences

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import app.gyrolet.mpvrx.preferences.BlacklistScope
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.openPersistedTreeDocument
import app.gyrolet.mpvrx.utils.storage.leafStorageName
import app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    val hiddenFolderMarkerNames by preferences.hiddenFolderMarkerNames.collectAsState()
    val scopedHiddenFolderUris by preferences.scopedHiddenFolderUris.collectAsState()
    val libraryModeEnabled by preferences.libraryHiddenScanEnabled.collectAsState()
    val treeModeEnabled by preferences.treeHiddenScanEnabled.collectAsState()
    val foldersModeEnabled by preferences.foldersHiddenScanEnabled.collectAsState()
    val scanMarkers by preferences.sharedScanMarkedFolders.collectAsState()
    val scanDotFolders by preferences.sharedScanDotFolders.collectAsState()
    val ignoredMarkers by preferences.sharedIgnoredMarkers.collectAsState()
    fun onScanPreferenceChanged() {
      app.gyrolet.mpvrx.repository.MediaFileRepository.clearCache()
      MediaLibraryEvents.notifyChanged()
    }
    var availableFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var selectionState by remember { mutableStateOf(SelectionState<String>()) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    val scopedTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val uri = result.data?.data
      if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
        val granted = runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            (result.data?.flags ?: 0) and
              (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
          )
          openPersistedTreeDocument(context, uri.toString()) != null
        }.getOrDefault(false)
        if (granted) {
          preferences.scopedHiddenFolderUris.set(scopedHiddenFolderUris + uri.toString())
          preferences.includeNoMediaFolders.set(true)
          app.gyrolet.mpvrx.repository.MediaFileRepository.clearCache()
          MediaLibraryEvents.notifyChanged()
        } else {
          android.widget.Toast.makeText(context, R.string.pref_playstore_hidden_grant_failed, android.widget.Toast.LENGTH_LONG).show()
        }
      }
    }
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
                modifier =
                  Modifier
                    .settingsSearchTarget(R.string.pref_folders_clear_all)
                    .padding(horizontal = 2.dp),
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
      // One scrolling viewport: media settings, blacklisted folders and Add all travel
      // together. A weighted inner list used to leave only two blacklist rows visible.
      LazyColumn(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .then(settingsHighlight),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        item(key = "media-settings") {
          Column {
            if (!selectionState.isInSelectionMode) {
          // ── Media Library ─────────────────────────────────────────────
          PreferenceSectionHeader(position = PreferenceSectionPosition.First, 
            title = stringResource(R.string.pref_media_library_section),
            modifier = Modifier.settingsSearchTarget(R.string.pref_folders_title),
            topPadding = 8.dp,
          )

          if (BuildConfig.SCOPED_STORAGE_ONLY) {
            // SAF scanning remains restricted to folders explicitly selected by the user.
            PlayStoreHiddenFolderCard(
              enabled = includeNoMediaFolders,
              grantedTreeUris = scopedHiddenFolderUris,
              onEnabledChange = { enabled ->
                preferences.includeNoMediaFolders.set(enabled)
                onScanPreferenceChanged()
              },
              onAddFolder = {
                scopedTreePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                  Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ))
              },
              onRemoveFolder = { treeUri ->
                preferences.scopedHiddenFolderUris.set(scopedHiddenFolderUris - treeUri)
                runCatching {
                  context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                  )
                }
                onScanPreferenceChanged()
              },
            )
          }

          // One unified compact group; these settings apply to Library, Tree AND Folders.
          HiddenScanSettingsCard(
            enabled = includeNoMediaFolders,
            showMasterToggle = !BuildConfig.SCOPED_STORAGE_ONLY,
            onEnabledChange = { preferences.includeNoMediaFolders.set(it); onScanPreferenceChanged() },
            libraryModeEnabled = libraryModeEnabled,
            treeModeEnabled = treeModeEnabled,
            foldersModeEnabled = foldersModeEnabled,
            onLibraryModeChanged = { preferences.libraryHiddenScanEnabled.set(it); onScanPreferenceChanged() },
            onTreeModeChanged = { preferences.treeHiddenScanEnabled.set(it); onScanPreferenceChanged() },
            onFoldersModeChanged = { preferences.foldersHiddenScanEnabled.set(it); onScanPreferenceChanged() },
            markerEnabled = scanMarkers,
            onMarkerChanged = { preferences.sharedScanMarkedFolders.set(it); onScanPreferenceChanged() },
            dotEnabled = scanDotFolders,
            onDotChanged = { preferences.sharedScanDotFolders.set(it); onScanPreferenceChanged() },
            markerNames = hiddenFolderMarkerNames,
            ignoredMarkers = ignoredMarkers,
            onIndividualMarkerChanged = { marker, checked ->
              preferences.sharedIgnoredMarkers.set(
                if (checked) ignoredMarkers - marker else ignoredMarkers + marker,
              )
              onScanPreferenceChanged()
            },
            onMarkerNamesChanged = { markers ->
              preferences.hiddenFolderMarkerNames.set(markers)
              preferences.sharedIgnoredMarkers.set(ignoredMarkers.intersect(markers))
              onScanPreferenceChanged()
            },
            modifier = Modifier.settingsSearchTarget(R.string.pref_hidden_scan_per_mode_title),
          )

          Spacer(modifier = Modifier.height(16.dp))

          // ── Hidden Folders ────────────────────────────────────────────
          PreferenceSectionHeader(position = PreferenceSectionPosition.Last, title = stringResource(R.string.pref_hidden_folders_section))

          Text(
            text = stringResource(R.string.pref_folders_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Spacer(modifier = Modifier.height(8.dp))
            }
          }
        }

        if (allBlacklistedFolders.isEmpty()) {
          item(key = "blacklist-empty") {
            Box(modifier = Modifier.fillMaxWidth()) {
              EmptyState(
                icon = Icons.RoundedFilled.FolderOff,
                title = stringResource(R.string.pref_folders_empty_title),
                message = stringResource(R.string.pref_folders_empty_message),
              )
            }
          }
        } else {
          itemsIndexed(allBlacklistedFolders, key = { _, path -> path }) { index, folderPath ->
              val isVideo = folderPath in blacklistedVideoFolders
              val isAudio = folderPath in blacklistedAudioFolders
              val scope = when {
                isVideo && isAudio -> BlacklistScope.BOTH
                isVideo -> BlacklistScope.VIDEO_ONLY
                else -> BlacklistScope.AUDIO_ONLY
              }

              BlacklistedFolderItem(
                folderPath = folderPath,
                shape = rememberConnectedShape(index, allBlacklistedFolders.size, ConnectedLayout.Vertical),
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

        if (!selectionState.isInSelectionMode) {
          item(key = "blacklist-add") {
            Column {
              Spacer(modifier = Modifier.height(16.dp))
              Card(
            modifier =
              Modifier
                .settingsSearchTarget(R.string.pref_folders_add_folder)
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

/**
 * Scoped-storage Play Store build: only explicitly user-selected folders can be scanned.
 * Other distributions keep the configurable .nomedia marker scanner unchanged.
 */
@Composable
private fun PlayStoreHiddenFolderCard(
  enabled: Boolean,
  grantedTreeUris: Set<String>,
  onEnabledChange: (Boolean) -> Unit,
  onAddFolder: () -> Unit,
  onRemoveFolder: (String) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = MaterialTheme.shapes.large,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.pref_playstore_hidden_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
          )
          Text(
            text = stringResource(R.string.pref_playstore_hidden_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconSwitch(checked = enabled, onCheckedChange = onEnabledChange)
      }

      if (grantedTreeUris.isNotEmpty()) {
        Text(
          text = stringResource(R.string.pref_playstore_hidden_selected),
          style = MaterialTheme.typography.labelLarge,
        )
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(grantedTreeUris.sorted(), key = { it }) { treeUri ->
            val displayPath = remember(treeUri) {
              Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':')?.takeIf(String::isNotBlank)
                ?: treeUri
            }
            InputChip(
              selected = false,
              onClick = { onRemoveFolder(treeUri) },
              label = { Text(displayPath, maxLines = 1) },
              leadingIcon = {
                Icon(Icons.RoundedFilled.Folder, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize))
              },
              trailingIcon = {
                Icon(
                  Icons.RoundedFilled.Close,
                  contentDescription = stringResource(R.string.pref_playstore_hidden_remove),
                  modifier = Modifier.size(InputChipDefaults.IconSize),
                )
              },
              shape = CircleShape,
              colors = InputChipDefaults.inputChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
              ),
              border = null,
            )
          }
        }
      }
      Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onAddFolder),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(Icons.RoundedFilled.Add, contentDescription = null)
          Text(stringResource(R.string.pref_playstore_hidden_add), style = MaterialTheme.typography.titleSmall)
        }
      }
      Text(
        text = stringResource(R.string.pref_playstore_hidden_limitations),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Three connected Material 3 Expressive selection tiles, independent of marker rules. */
@Composable
private fun HiddenScanModeButton(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  shape: Shape,
) {
  val colors = MaterialTheme.colorScheme
  Card(
    modifier = modifier,
    shape = shape,
    colors = CardDefaults.cardColors(
      containerColor = if (checked) colors.surfaceContainerHighest else colors.surfaceContainerHigh,
    ),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Checkbox,
          onValueChange = onCheckedChange,
        )
        .padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (checked) {
        Icon(
          Icons.RoundedFilled.Check,
          contentDescription = null,
          tint = if (enabled) colors.primary else colors.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(4.dp))
      }
      Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

/** Connected, neutral settings row: the accent belongs to the switch, not the entire card. */
@Composable
private fun CompactHiddenScanOption(
  title: String,
  checked: Boolean,
  enabled: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  shape: Shape,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled) { onCheckedChange(!checked) }
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMediumEmphasized,
        modifier = Modifier.weight(1f),
        maxLines = 1,
      )
      IconSwitch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
  }
}

/**
 * Compact Material 3 Expressive group shared by all three Home browser modes.
 * A marker can be opted out individually without three duplicated settings stacks.
 */
@Composable
private fun HiddenScanSettingsCard(
  enabled: Boolean,
  showMasterToggle: Boolean,
  onEnabledChange: (Boolean) -> Unit,
  libraryModeEnabled: Boolean,
  treeModeEnabled: Boolean,
  foldersModeEnabled: Boolean,
  onLibraryModeChanged: (Boolean) -> Unit,
  onTreeModeChanged: (Boolean) -> Unit,
  onFoldersModeChanged: (Boolean) -> Unit,
  markerEnabled: Boolean,
  onMarkerChanged: (Boolean) -> Unit,
  dotEnabled: Boolean,
  onDotChanged: (Boolean) -> Unit,
  markerNames: Set<String>,
  ignoredMarkers: Set<String>,
  onIndividualMarkerChanged: (String, Boolean) -> Unit,
  onMarkerNamesChanged: (Set<String>) -> Unit,
  modifier: Modifier = Modifier,
) {
  var showAddMarkerDialog by remember { mutableStateOf(false) }
  var markerInput by remember { mutableStateOf("") }
  var addedMarker by remember { mutableStateOf<String?>(null) }
  val markerListState = rememberLazyListState()
  val orderedMarkers = remember(markerNames) { markerNames.sorted() }
  LaunchedEffect(orderedMarkers, addedMarker) {
    val index = addedMarker?.let { orderedMarkers.indexOf(it) } ?: -1
    if (index >= 0) {
      markerListState.animateScrollToItem(index)
      addedMarker = null
    }
  }

  Card(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = stringResource(R.string.pref_hidden_scan_per_mode_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
            maxLines = 1,
          )
        }
        if (showMasterToggle) {
          IconSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
      }

      Text(
        text = stringResource(R.string.pref_hidden_scan_enable_in),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        HiddenScanModeButton(
          title = stringResource(R.string.pref_hidden_scan_library),
          checked = libraryModeEnabled,
          onCheckedChange = onLibraryModeChanged,
          enabled = enabled,
          modifier = Modifier.weight(1f),
          shape = rememberConnectedShape(0, 3, ConnectedLayout.Horizontal),
        )
        HiddenScanModeButton(
          title = stringResource(R.string.pref_hidden_scan_tree),
          checked = treeModeEnabled,
          onCheckedChange = onTreeModeChanged,
          enabled = enabled,
          modifier = Modifier.weight(1f),
          shape = rememberConnectedShape(1, 3, ConnectedLayout.Horizontal),
        )
        HiddenScanModeButton(
          title = stringResource(R.string.pref_hidden_scan_folders),
          checked = foldersModeEnabled,
          onCheckedChange = onFoldersModeChanged,
          enabled = enabled,
          modifier = Modifier.weight(1f),
          shape = rememberConnectedShape(2, 3, ConnectedLayout.Horizontal),
        )
      }

      Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
        CompactHiddenScanOption(
          title = stringResource(R.string.pref_folders_hidden_markers_title),
          checked = markerEnabled,
          enabled = enabled,
          onCheckedChange = onMarkerChanged,
          shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical),
        )
        CompactHiddenScanOption(
          title = stringResource(R.string.pref_hidden_scan_dot),
          checked = dotEnabled,
          enabled = enabled,
          onCheckedChange = onDotChanged,
          shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
        )
      }

      // Individual markers are small selectable chips instead of a third switch row per marker.
      LazyRow(
        state = markerListState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        items(orderedMarkers, key = { it }) { marker ->
          InputChip(
            selected = markerEnabled && marker !in ignoredMarkers,
            enabled = enabled && markerEnabled,
            onClick = { onIndividualMarkerChanged(marker, marker in ignoredMarkers) },
            label = { Text(marker, maxLines = 1) },
            trailingIcon = {
              IconButton(
                modifier = Modifier.size(24.dp),
                onClick = { onMarkerNamesChanged(markerNames - marker) },
              ) {
                Icon(
                  Icons.RoundedFilled.Close,
                  contentDescription = stringResource(R.string.pref_folders_remove_marker, marker),
                  modifier = Modifier.size(14.dp),
                )
              }
            },
            colors = InputChipDefaults.inputChipColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            ),
          )
        }
        item(key = "add-marker") {
          IconButton(
            onClick = {
              markerInput = ""
              showAddMarkerDialog = true
            },
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.size(40.dp),
          ) {
            Icon(
              Icons.RoundedFilled.Add,
              contentDescription = stringResource(R.string.pref_folders_add_marker),
            )
          }
        }
      }
    }
  }

  if (showAddMarkerDialog) {
    val normalizedMarker = normalizeHiddenMarkerName(markerInput)
    val errorMessage =
      when {
        markerInput.isBlank() -> null
        normalizedMarker == null -> R.string.pref_folders_marker_invalid
        normalizedMarker in markerNames -> R.string.pref_folders_marker_duplicate
        else -> null
      }

    AlertDialog(
      onDismissRequest = { showAddMarkerDialog = false },
      title = { Text(stringResource(R.string.pref_folders_add_marker_title)) },
      text = {
        OutlinedTextField(
          value = markerInput,
          onValueChange = { markerInput = it },
          label = { Text(stringResource(R.string.pref_folders_marker_name)) },
          placeholder = { Text(".nomedia") },
          singleLine = true,
          isError = errorMessage != null,
          supportingText =
            if (errorMessage == null) {
              null
            } else {
              { Text(stringResource(errorMessage)) }
            },
        )
      },
      confirmButton = {
        TextButton(
          enabled = normalizedMarker != null && normalizedMarker !in markerNames,
          onClick = {
            val marker = requireNotNull(normalizedMarker)
            onMarkerNamesChanged(markerNames + marker)
            addedMarker = marker
            showAddMarkerDialog = false
          },
        ) {
          Text(stringResource(R.string.pref_folders_add_marker))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddMarkerDialog = false }) {
          Text(stringResource(R.string.generic_cancel))
        }
      },
    )
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlacklistedFolderItem(
  folderPath: String,
  shape: Shape,
  scope: BlacklistScope,
  isSelected: Boolean,
  isInSelectionMode: Boolean,
  onRemove: () -> Unit,
  onScopeChange: (BlacklistScope) -> Unit,
  onLongClick: () -> Unit,
  onClick: () -> Unit,
) {
  val selectionColor by animateColorAsState(
    targetValue =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceContainerLow,
    animationSpec = AppMotion.spatial(AppMotion.Effect.Color, snap()),
    label = "hiddenFolderSelected",
  )
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = selectionColor),
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
            text = leafStorageName(folderPath),
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
  var query by rememberSaveable { mutableStateOf("") }

  val availableFolders =
    remember(folders, blacklistedFolders) {
      folders.filter { it.path !in blacklistedFolders }
    }
  val availableFolderPaths = remember(availableFolders) { availableFolders.map { it.path } }
  val filteredFolders = remember(availableFolders, query) {
    val normalized = query.trim()
    if (normalized.isEmpty()) availableFolders else availableFolders.filter { folder ->
      folder.name.contains(normalized, ignoreCase = true) || folder.path.contains(normalized, ignoreCase = true)
    }
  }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title =
      if (selectionState.isInSelectionMode) {
        stringResource(R.string.selected_items, selectionState.selectedCount, availableFolders.size)
      } else {
        stringResource(R.string.pref_folders_select_folders)
      },
    scrollContent = false,
    actions = {
      if (!isLoading && availableFolders.isNotEmpty()) {
        Box {
          IconButton(onClick = { showDropdown = true }) {
            Icon(
              Icons.RoundedFilled.MoreVert,
              contentDescription = stringResource(R.string.selection_options),
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
      }
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
      TextButton(
        onClick = {
          onAddFolders(selectionState.selectedIds, selectedScope)
          onDismiss()
        },
        enabled = selectionState.isInSelectionMode && !isLoading,
      ) { Text(stringResource(R.string.generic_ok)) }
    },
  ) {
      if (isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
          Text(stringResource(R.string.pref_folders_loading))
        }
      } else if (availableFolders.isEmpty()) {
        Text(stringResource(R.string.pref_folders_no_folders))
      } else {
        Column {
          PlayerSheetSearchField(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.folder_search_hint),
          )
          LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
            items(filteredFolders, key = { it.path }) { folder ->
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
            if (filteredFolders.isEmpty()) {
              item {
                Text(
                  text = stringResource(R.string.generic_no_matching_options),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                )
              }
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

          Text(
            text = stringResource(R.string.folder_blacklist_scope),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )

          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            BlacklistScope.entries.forEach { scope ->
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { selectedScope = scope },
              ) {
                RadioButton(
                  selected = selectedScope == scope,
                  onClick = { selectedScope = scope },
                )
                Text(
                  text = stringResource(
                    when (scope) {
                      BlacklistScope.BOTH -> R.string.folder_blacklist_both
                      BlacklistScope.VIDEO_ONLY -> R.string.folder_blacklist_video
                      BlacklistScope.AUDIO_ONLY -> R.string.folder_blacklist_audio
                    },
                  ),
                  style = MaterialTheme.typography.bodyMedium,
                )
              }
            }
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

// Same sources as the Videos and Music folder tabs, so the video scan reuses their cache.
private suspend fun scanAllMediaFolders(context: Application): List<VideoFolder> =
  coroutineScope {
    val repository = app.gyrolet.mpvrx.repository.MediaFileRepository
    val videoFolders = async { repository.getAllVideoFoldersFast(context = context) }
    val hiddenFolders = async { repository.getIndexedNoMediaFolders() }
    val audioFolders = async { repository.getAllAudioFolders(context = context) }

    (videoFolders.await() + hiddenFolders.await() + audioFolders.await())
      .distinctBy { it.path.lowercase() }
      .sortedBy { it.name.lowercase() }
  }
