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
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import app.gyrolet.mpvrx.presentation.components.AppDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import app.gyrolet.mpvrx.ui.components.IconSwitch
import androidx.compose.material3.Text
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.ui.preferences.components.SwitchPreference
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.utils.storage.MediaStoreGenerationGuard
import app.gyrolet.mpvrx.utils.storage.UnindexedVideoDiscovery
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryCache
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import app.gyrolet.mpvrx.preferences.BlacklistScope
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.AppComboBoxChip
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBarActionButton
import app.gyrolet.mpvrx.ui.browser.selection.SelectionState
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.LocalShowSettingsBackArrow
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.utils.media.MediaLibraryEvents
import app.gyrolet.mpvrx.utils.media.HiddenFolderCandidate
import app.gyrolet.mpvrx.utils.media.HiddenFolderDiscovery
import app.gyrolet.mpvrx.utils.media.HiddenFolderSuggestion
import app.gyrolet.mpvrx.utils.media.openPersistedTreeDocument
import app.gyrolet.mpvrx.utils.storage.leafStorageName
import app.gyrolet.mpvrx.utils.storage.normalizeHiddenMarkerName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private enum class ScanFilterDialog { VideoDuration, FileSize }


@Serializable
object FoldersPreferencesScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val preferences = koinInject<FoldersPreferences>()
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val minVideoSeconds by preferences.minimumVideoDurationSeconds.collectAsState()
    val minFileBytes by preferences.minimumMediaFileSizeBytes.collectAsState()
    val whitelist by preferences.whitelistedFolders.collectAsState()
    val whitelistEnabled by preferences.whitelistEnabled.collectAsState()
    val noLimitLabel = stringResource(R.string.pref_filter_no_limit)
    var libraryTaskRunning by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var scanFilterDialog by remember { mutableStateOf<ScanFilterDialog?>(null) }
    fun onScanPreferenceChanged() = invalidateLibraryScan(context)
    fun runLibraryTask(@StringRes doneMessage: Int, task: suspend () -> Unit) {
      libraryTaskRunning = true
      coroutineScope.launch {
        val succeeded =
          try {
            withContext(Dispatchers.IO) { task() }
            true
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            false
          } finally {
            libraryTaskRunning = false
          }
        app.gyrolet.mpvrx.ui.components.AppToast.makeText(
          context,
          if (succeeded) doneMessage else R.string.pref_library_action_failed,
          app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
        ).show()
      }
    }

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
    var discoveredHiddenFolders by remember { mutableStateOf<List<HiddenFolderCandidate>>(emptyList()) }
    var discoveringHiddenFolders by remember { mutableStateOf(true) }
    var discoveryRefresh by remember { mutableStateOf(0) }
    var grantsRefresh by remember { mutableStateOf(0) }
    var heldTreeUris by remember { mutableStateOf<Set<String>?>(null) }
    if (BuildConfig.SCOPED_STORAGE_ONLY) {
      LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { grantsRefresh++ }
      LaunchedEffect(grantsRefresh, scopedHiddenFolderUris) {
        heldTreeUris = withContext(Dispatchers.IO) {
          context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
            .mapTo(hashSetOf()) { it.uri.toString() }
        }
      }
      LaunchedEffect(hiddenFolderMarkerNames, discoveryRefresh) {
        discoveringHiddenFolders = true
        try {
          HiddenFolderDiscovery.discover(
            context.applicationContext,
            hiddenFolderMarkerNames,
            force = discoveryRefresh > 0,
          ).collect { discoveredHiddenFolders = it }
        } finally {
          discoveringHiddenFolders = false
        }
      }
    }
    val liveTreeUris = scopedHiddenFolderUris.intersect(heldTreeUris.orEmpty())
    val lockedFolders = remember(discoveredHiddenFolders, scopedHiddenFolderUris, heldTreeUris) {
      if (heldTreeUris == null) emptyList() else HiddenFolderDiscovery.suggestions(
        candidates = discoveredHiddenFolders,
        grantedTreeUris = liveTreeUris,
        revokedTreeUris = scopedHiddenFolderUris - liveTreeUris,
      )
    }
    val scopedTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val uri = result.data?.data
      if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
        coroutineScope.launch {
          val granted = withContext(Dispatchers.IO) {
            runCatching {
              val flags = (result.data?.flags ?: 0) and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
              context.contentResolver.takePersistableUriPermission(uri, flags)
              openPersistedTreeDocument(context, uri.toString()) != null
            }.getOrDefault(false)
          }
          if (granted) {
            // Save the folder Android actually returned, even if the user chose another location.
            preferences.scopedHiddenFolderUris.set(
              preferences.scopedHiddenFolderUris.get() + uri.toString(),
            )
            heldTreeUris = heldTreeUris.orEmpty() + uri.toString()
            preferences.includeNoMediaFolders.set(true)
            onScanPreferenceChanged()
          } else {
            app.gyrolet.mpvrx.ui.components.AppToast.makeText(
              context,
              R.string.pref_playstore_hidden_grant_failed,
              app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_LONG,
            ).show()
          }
        }
      }
    }
    fun chooseHiddenFolder(initialUri: Uri? = null) {
      scopedTreePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
      })
    }
    val (settingsListState, settingsHighlight) =
      rememberSettingsSearchList(FoldersPreferencesScreen, MaterialTheme.colorScheme.primary)

    val blacklistedFolderCount = (blacklistedVideoFolders + blacklistedAudioFolders).size
    // The Play Store build adds its folder-access card above the shared hidden-scan rows.
    val mediaGroupOffset = if (BuildConfig.SCOPED_STORAGE_ONLY) 1 else 0
    val mediaGroupSize = mediaGroupOffset + HIDDEN_SCAN_ROW_COUNT + SCAN_FILTER_ROW_COUNT

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.pref_folders_title),
          colors = TopAppBarDefaults.topAppBarColors(),
          forceHeadlineSmall = true,
          // This page is not a selection editor; the blacklist editor owns selection state.
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = {},
          onBackClick = if (LocalShowSettingsBackArrow.current) {
            { backstack.popSafely() }
          } else {
            null
          },
        )
      },
    ) { padding ->
      ProvideExpressivePreferenceLocals {
        LazyColumn(
          state = settingsListState,
          modifier = Modifier.fillMaxSize().padding(padding).then(settingsHighlight),
          contentPadding = PaddingValues(bottom = 24.dp),
        ) {
          item(key = "media-settings") {
            Column {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.First,
                title = stringResource(R.string.pref_media_library_section),
                modifier = Modifier.settingsSearchTarget(R.string.pref_folders_title),
                topPadding = 8.dp,
              )
              Column(
                  modifier = Modifier.padding(horizontal = 16.dp),
                  verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
                ) {

          if (BuildConfig.SCOPED_STORAGE_ONLY) {
            // SAF scanning remains restricted to folders explicitly selected by the user.
            PlayStoreHiddenFolderCard(
              enabled = includeNoMediaFolders,
              shape = rememberConnectedShape(0, mediaGroupSize, ConnectedLayout.Vertical),
              onEnabledChange = { enabled ->
                preferences.includeNoMediaFolders.set(enabled)
                onScanPreferenceChanged()
              },
            )
            Spacer(modifier = Modifier.height(AppConnectedShapeTokens.spacing))
          }

          // One unified compact group; these settings apply to Library, Tree AND Folders.
          HiddenScanSettingsCard(
            enabled = includeNoMediaFolders,
            shape = rememberConnectedShape(mediaGroupOffset, mediaGroupSize, ConnectedLayout.Vertical),
            groupOffset = mediaGroupOffset,
            groupSize = mediaGroupSize,
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
          // Scan filters finish the Media Library group; they open a list of limits.
          SettingsNavigationRow(
            title = stringResource(R.string.pref_filter_short_videos),
            summary = stringResource(R.string.pref_filter_short_videos_summary) + " · " +
              scanDurationLabel(minVideoSeconds, noLimitLabel),
            shape = rememberConnectedShape(mediaGroupSize - 2, mediaGroupSize, ConnectedLayout.Vertical),
            onClick = { scanFilterDialog = ScanFilterDialog.VideoDuration },
            showChevron = false,
            modifier = Modifier.settingsSearchTarget(R.string.pref_filter_short_videos),
          )
          SettingsNavigationRow(
            title = stringResource(R.string.pref_filter_small_files),
            summary = stringResource(R.string.pref_filter_small_files_summary) + " · " +
              scanSizeLabel(minFileBytes, noLimitLabel),
            shape = rememberConnectedShape(mediaGroupSize - 1, mediaGroupSize, ConnectedLayout.Vertical),
            onClick = { scanFilterDialog = ScanFilterDialog.FileSize },
            showChevron = false,
            modifier = Modifier.settingsSearchTarget(R.string.pref_filter_small_files),
          )
              }
          if (BuildConfig.SCOPED_STORAGE_ONLY) {
            // Connected section under the scan settings: each folder the user granted is one row.
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_playstore_hidden_selected),
              topPadding = 24.dp,
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PlayStoreGrantedFoldersSection(
              grantedTreeUris = liveTreeUris,
              onAddFolder = { chooseHiddenFolder() },
              onRemoveFolder = { treeUri ->
                preferences.scopedHiddenFolderUris.set(preferences.scopedHiddenFolderUris.get() - treeUri)
                coroutineScope.launch {
                  withContext(Dispatchers.IO) {
                    val uri = Uri.parse(treeUri)
                    context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let { grant ->
                      val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                      runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
                    }
                  }
                  grantsRefresh++
                }
                onScanPreferenceChanged()
              },
            )
            }
            PreferenceSectionHeader(
              position = PreferenceSectionPosition.Middle,
              title = stringResource(R.string.pref_playstore_hidden_not_allowed),
              topPadding = 24.dp,
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PlayStoreLockedFoldersSection(
              folders = lockedFolders,
              loading = discoveringHiddenFolders || heldTreeUris == null,
              onAllow = { chooseHiddenFolder(it.initialUri) },
              onRefresh = { discoveryRefresh++ },
            )
            }
          }
            }
          }
          item(key = "exclusions-navigation") {
            Column {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.Middle,
                title = stringResource(R.string.pref_storage_exclusions),
                topPadding = 24.dp,
              )
              Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
              ) {
                SettingsNavigationRow(
                  title = stringResource(R.string.pref_blacklist_title),
                  summary =
                    if (blacklistedFolderCount == 0) {
                      stringResource(R.string.pref_folders_empty_title)
                    } else {
                      stringResource(R.string.pref_blacklist_count, blacklistedFolderCount)
                    },
                  icon = Icons.RoundedFilled.FolderOff,
                  shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical),
                  onClick = { backstack.navigateTo(FolderBlacklistScreen) },
                  modifier = Modifier.settingsSearchTarget(R.string.pref_blacklist_title),
                )
                SettingsNavigationRow(
                  title = stringResource(R.string.pref_whitelist_title),
                  summary =
                    when {
                      !whitelistEnabled -> stringResource(R.string.pref_whitelist_disabled)
                      whitelist.isEmpty() -> stringResource(R.string.pref_whitelist_empty)
                      else -> stringResource(R.string.pref_whitelist_count, whitelist.size)
                    },
                  icon = Icons.RoundedFilled.Folder,
                  shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
                  onClick = { backstack.navigateTo(FolderWhitelistScreen) },
                  modifier = Modifier.settingsSearchTarget(R.string.pref_whitelist_title),
                )
              }
            }
          }
          item(key = "library-maintenance") {
            Column {
              PreferenceSectionHeader(
                position = PreferenceSectionPosition.Last,
                title = stringResource(R.string.pref_library_maintenance),
                topPadding = 24.dp,
              )
              Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
              ) {
                SettingsNavigationRow(
                  title = stringResource(R.string.pref_library_force_rescan),
                  summary = stringResource(R.string.pref_library_force_rescan_summary),
                  icon = Icons.RoundedFilled.Refresh,
                  shape = rememberConnectedShape(0, 2, ConnectedLayout.Vertical),
                  busy = libraryTaskRunning,
                  onClick = {
                    runLibraryTask(R.string.pref_library_rescan_done) {
                      invalidateLibraryScan(context) {
                        UnindexedVideoDiscovery.refresh(context, force = true)
                        app.gyrolet.mpvrx.repository.MediaFileRepository.getAllVideoFolders(
                          context, forceFileSystemCheck = true,
                        )
                      }
                    }
                  },
                  modifier = Modifier.settingsSearchTarget(R.string.pref_library_force_rescan),
                )
                SettingsNavigationRow(
                  title = stringResource(R.string.pref_library_clear_cache),
                  summary = stringResource(R.string.pref_library_clear_cache_summary),
                  icon = Icons.RoundedFilled.Delete,
                  shape = rememberConnectedShape(1, 2, ConnectedLayout.Vertical),
                  enabled = !libraryTaskRunning,
                  onClick = { confirmClearCache = true },
                  modifier = Modifier.settingsSearchTarget(R.string.pref_library_clear_cache),
                )
              }
            }
          }
        }
      }
    }
    when (scanFilterDialog) {
      ScanFilterDialog.VideoDuration -> ScanFilterOptionsDialog(
        title = R.string.pref_filter_short_videos,
        options = VideoDurationFilterSeconds,
        selected = minVideoSeconds,
        label = { scanDurationLabel(it, noLimitLabel) },
        onSelect = { preferences.minimumVideoDurationSeconds.set(it); onScanPreferenceChanged() },
        onDismiss = { scanFilterDialog = null },
      )
      ScanFilterDialog.FileSize -> ScanFilterOptionsDialog(
        title = R.string.pref_filter_small_files,
        options = FileSizeFilterBytes,
        selected = minFileBytes,
        label = { scanSizeLabel(it, noLimitLabel) },
        onSelect = { preferences.minimumMediaFileSizeBytes.set(it); onScanPreferenceChanged() },
        onDismiss = { scanFilterDialog = null },
      )
      null -> Unit
    }
    if (confirmClearCache) {
      // Resolved only here so opening Folder settings never builds the metadata and thumbnail caches.
      val metadataCache = koinInject<VideoMetadataCacheRepository>()
      val thumbnailRepository = koinInject<ThumbnailRepository>()
      AlertDialog(
        onDismissRequest = { confirmClearCache = false },
        title = { Text(stringResource(R.string.pref_library_clear_cache)) },
        text = { Text(stringResource(R.string.pref_library_clear_cache_confirm)) },
        confirmButton = {
          TextButton(onClick = {
            confirmClearCache = false
            runLibraryTask(R.string.pref_library_cache_cleared) {
              invalidateLibraryScan(context) {
                metadataCache.clearAll()
                thumbnailRepository.clearThumbnailCache()
              }
            }
          }) { Text(stringResource(R.string.generic_confirm)) }
        },
        dismissButton = {
          TextButton(onClick = { confirmClearCache = false }) {
            Text(stringResource(R.string.generic_cancel))
          }
        },
      )
    }
  }
}

/**
 * Blacklisted folders on a screen of their own. The list fills the height between the toolbar and
 * the Add button and scrolls by itself, so the button and every row stay reachable on phones and
 * tablets alike.
 */
@Serializable
object FolderBlacklistScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = koinInject<FoldersPreferences>()
    val blacklistedVideoFolders by preferences.blacklistedFolders.collectAsState()
    val blacklistedAudioFolders by preferences.blacklistedAudioFolders.collectAsState()
    var selectionState by remember { mutableStateOf(SelectionState<String>()) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var availableFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    val allBlacklistedFolders = remember(blacklistedVideoFolders, blacklistedAudioFolders) {
      (blacklistedVideoFolders + blacklistedAudioFolders).toList().sorted()
    }
    val (listState, highlight) =
      rememberSettingsSearchList(FolderBlacklistScreen, MaterialTheme.colorScheme.primary)

    fun openAddDialog() {
      showAddDialog = true
      isLoading = true
      coroutineScope.launch(Dispatchers.IO) {
        try {
          availableFolders = scanAllMediaFolders(context.applicationContext as Application)
        } finally {
          isLoading = false
        }
      }
    }

    BackHandler(enabled = selectionState.isInSelectionMode) { selectionState = selectionState.clear() }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.pref_blacklist_title),
          colors = TopAppBarDefaults.topAppBarColors(),
          forceHeadlineSmall = true,
          isInSelectionMode = selectionState.isInSelectionMode,
          selectedCount = selectionState.selectedCount,
          totalCount = allBlacklistedFolders.size,
          onCancelSelection = { selectionState = selectionState.clear() },
          onBackClick = {
            if (selectionState.isInSelectionMode) selectionState = selectionState.clear()
            else backstack.popSafely()
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
              BrowserTopBarActionButton(
                icon = Icons.RoundedFilled.Clear,
                contentDescription = stringResource(R.string.pref_folders_clear_all),
                onClick = { showClearAllDialog = true },
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.settingsSearchTarget(R.string.pref_folders_clear_all),
              )
            }
          },
          useRemoveIcon = true,
        )
      },
      bottomBar = {
        if (!selectionState.isInSelectionMode) {
          FolderListAddBar(
            text = stringResource(R.string.pref_folders_add_folder),
            onClick = ::openAddDialog,
            modifier = Modifier.settingsSearchTarget(R.string.pref_folders_add_folder),
          )
        }
      },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (!selectionState.isInSelectionMode) {
          FolderListIntro(stringResource(R.string.pref_blacklist_summary))
        }
        if (allBlacklistedFolders.isEmpty()) {
          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EmptyState(
              icon = Icons.RoundedFilled.FolderOff,
              title = stringResource(R.string.pref_folders_empty_title),
              message = stringResource(R.string.pref_folders_empty_message),
            )
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().then(highlight),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
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
                onRemove = { preferences.removeBlacklistedFolder(folderPath) },
                onScopeChange = { preferences.addBlacklistedFolders(setOf(folderPath), it) },
                onLongClick = { selectionState = selectionState.toggle(folderPath) },
                onClick = {
                  if (selectionState.isInSelectionMode) selectionState = selectionState.toggle(folderPath)
                },
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
        onAddFolders = { folders, scope -> preferences.addBlacklistedFolders(folders, scope) },
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
          }) { Text(stringResource(R.string.generic_confirm)) }
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

/** Whitelisted folders on a screen of their own, laid out like [FolderBlacklistScreen]. */
@Serializable
object FolderWhitelistScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val preferences = koinInject<FoldersPreferences>()
    val whitelist by preferences.whitelistedFolders.collectAsState()
    val whitelistEnabled by preferences.whitelistEnabled.collectAsState()
    val sortedWhitelist = remember(whitelist) { whitelist.sorted() }
    val (listState, highlight) =
      rememberSettingsSearchList(FolderWhitelistScreen, MaterialTheme.colorScheme.primary)
    val whitelistPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        // A MediaStore-backed folder can be selected using SAF without changing scan roots.
        val folder = ScopedHiddenFolderScanner.localPathOf(uri) ?: uri.toString()
        preferences.whitelistedFolders.set(preferences.whitelistedFolders.get() + folder)
        preferences.whitelistEnabled.set(true)
        invalidateLibraryScan(context)
      }
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.pref_whitelist_title),
          colors = TopAppBarDefaults.topAppBarColors(),
          forceHeadlineSmall = true,
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = {},
          onBackClick = { backstack.popSafely() },
        )
      },
      bottomBar = {
        FolderListAddBar(
          text = stringResource(R.string.pref_whitelist_add),
          onClick = { whitelistPicker.launch(null) },
          modifier = Modifier.settingsSearchTarget(R.string.pref_whitelist_add),
        )
      },
    ) { padding ->
      Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        FolderListIntro(stringResource(R.string.pref_whitelist_summary))
        ProvideExpressivePreferenceLocals {
          PreferenceCard {
            SwitchPreference(
              modifier = Modifier.settingsSearchTarget(R.string.pref_whitelist_enable),
              value = whitelistEnabled,
              onValueChange = { enabled ->
                if (enabled && whitelist.isEmpty()) {
                  whitelistPicker.launch(null)
                } else {
                  preferences.whitelistEnabled.set(enabled)
                  invalidateLibraryScan(context)
                }
              },
              title = { Text(stringResource(R.string.pref_whitelist_enable)) },
              summary = {
                Text(
                  if (whitelist.isEmpty()) {
                    stringResource(R.string.pref_whitelist_empty)
                  } else {
                    stringResource(R.string.pref_whitelist_count, whitelist.size)
                  },
                )
              },
            )
          }
        }
        if (sortedWhitelist.isEmpty()) {
          Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            EmptyState(
              icon = Icons.RoundedFilled.Folder,
              title = stringResource(R.string.pref_whitelist_empty),
              message = stringResource(R.string.pref_whitelist_empty_message),
            )
          }
        } else {
          LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().then(highlight),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
          ) {
            itemsIndexed(sortedWhitelist, key = { _, path -> path }) { index, path ->
              WhitelistedFolderItem(
                folderPath = path,
                shape = rememberConnectedShape(index, sortedWhitelist.size, ConnectedLayout.Vertical),
                active = whitelistEnabled,
                onRemove = {
                  preferences.whitelistedFolders.set(preferences.whitelistedFolders.get() - path)
                  invalidateLibraryScan(context)
                },
              )
            }
          }
        }
      }
    }
  }
}

/** Short explanation above a folder list; not shown while selecting. */
@Composable
private fun FolderListIntro(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
  )
}

/** The Add button pinned below a folder list, above the system navigation bar. */
@Composable
private fun FolderListAddBar(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Button(
      onClick = onClick,
      modifier =
        modifier
          .fillMaxWidth()
          .heightIn(min = 56.dp)
          .tvFocusHighlight(CircleShape, focusedScale = 1.02f, clipToShape = false),
      colors =
        ButtonDefaults.buttonColors(
          containerColor = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
      Icon(Icons.RoundedFilled.CreateNewFolder, contentDescription = null, modifier = Modifier.size(22.dp))
      Spacer(Modifier.width(10.dp))
      Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun WhitelistedFolderItem(
  folderPath: String,
  shape: Shape,
  active: Boolean,
  onRemove: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FolderRowIcon(
        icon = Icons.RoundedFilled.Folder,
        tinted = active,
      )
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = leafStorageName(folderPath),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = folderPath,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onRemove,
        modifier = Modifier.tvFocusHighlight(CircleShape, focusedScale = 1.06f),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.RemoveCircle,
          contentDescription = stringResource(R.string.delete),
          tint = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

/** Tonal icon tile shared by the blacklist and whitelist rows; muted while the list is inactive. */
@Composable
private fun FolderRowIcon(
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  tinted: Boolean,
) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = if (tinted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor = if (tinted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
  ) {
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
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
  shape: Shape,
  onEnabledChange: (Boolean) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    shape = shape,
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

      Text(
        text = stringResource(R.string.pref_playstore_hidden_limitations),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Connected rows for the folders the user has granted; the row after them adds another folder. */
@Composable
private fun PlayStoreGrantedFoldersSection(
  grantedTreeUris: Set<String>,
  onAddFolder: () -> Unit,
  onRemoveFolder: (String) -> Unit,
) {
  val granted = remember(grantedTreeUris) { grantedTreeUris.sorted() }
  val rowCount = granted.size + 1
  Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
    granted.forEachIndexed { index, treeUri ->
      PlayStoreGrantedFolderRow(
        treeUri = treeUri,
        shape = rememberConnectedShape(index, rowCount, ConnectedLayout.Vertical),
        onRemove = { onRemoveFolder(treeUri) },
      )
    }
    PlayStoreAddFolderRow(
      shape = rememberConnectedShape(granted.size, rowCount, ConnectedLayout.Vertical),
      onAddFolder = onAddFolder,
    )
  }
}

@Composable
private fun PlayStoreLockedFoldersSection(
  folders: List<HiddenFolderSuggestion>,
  loading: Boolean,
  onAllow: (HiddenFolderSuggestion) -> Unit,
  onRefresh: () -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val visible = if (expanded) folders else folders.take(4)
  val hasExpansion = folders.size > 4
  val rowCount = visible.size + (if (hasExpansion) 1 else 0) + 1
  Column(verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing)) {
    visible.forEachIndexed { index, folder ->
      key(folder.key) {
        PlayStoreLockedFolderRow(
          folder = folder,
          shape = rememberConnectedShape(index, rowCount, ConnectedLayout.Vertical),
          onAllow = { onAllow(folder) },
        )
      }
    }
    if (hasExpansion) {
      Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = rememberConnectedShape(visible.size, rowCount, ConnectedLayout.Vertical),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      ) {
        Text(
          text = if (expanded) stringResource(R.string.generic_collapse)
            else stringResource(R.string.pref_playstore_hidden_more, folders.size - visible.size),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = rememberConnectedShape(rowCount - 1, rowCount, ConnectedLayout.Vertical),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(
            if (loading) R.string.pref_folders_loading
            else if (folders.isEmpty()) R.string.pref_playstore_hidden_none
            else R.string.pref_playstore_hidden_discovery_hint,
          ),
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (loading) {
          Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          }
        } else {
          IconButton(onClick = onRefresh) {
            Icon(
              Icons.RoundedFilled.Refresh,
              contentDescription = stringResource(R.string.pref_playstore_hidden_refresh),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PlayStoreLockedFolderRow(
  folder: HiddenFolderSuggestion,
  shape: Shape,
  onAllow: () -> Unit,
) {
  val reason = when {
    folder.childPaths.isNotEmpty() -> folder.childPaths.joinToString(", ")
    folder.isDotFolder -> stringResource(R.string.pref_playstore_hidden_dot)
    folder.markerName != null -> stringResource(R.string.pref_playstore_hidden_marker, folder.markerName)
    else -> stringResource(R.string.pref_playstore_hidden_access_required)
  }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        Icons.RoundedFilled.Lock,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = folder.displayPath,
          style = MaterialTheme.typography.bodyLarge,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = reason,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      FilledTonalButton(
        onClick = onAllow,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Text(stringResource(R.string.pref_playstore_hidden_allow))
      }
    }
  }
}

@Composable
private fun PlayStoreGrantedFolderRow(
  treeUri: String,
  shape: Shape,
  onRemove: () -> Unit,
) {
  val displayPath = remember(treeUri) {
    Uri.parse(treeUri).lastPathSegment?.substringAfterLast(':')?.takeIf(String::isNotBlank) ?: treeUri
  }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(Icons.RoundedFilled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
      Text(
        text = displayPath,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      IconButton(onClick = onRemove) {
        Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.pref_playstore_hidden_remove))
      }
    }
  }
}

@Composable
private fun PlayStoreAddFolderRow(
  shape: Shape,
  onAddFolder: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onAddFolder),
    shape = shape,
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
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
  shape: Shape,
  // Position of this card's first row in the caller's connected group, and the group's size.
  groupOffset: Int,
  groupSize: Int,
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

  // Each setting draws its own connected surface; avoid one oversized parent card.
  val offset = groupOffset
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = shape,
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
    }
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = rememberConnectedShape(offset + 1, groupSize, ConnectedLayout.Vertical),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      Text(
        text = stringResource(R.string.pref_hidden_scan_enable_in),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
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
      }
    }
    CompactHiddenScanOption(
      title = stringResource(R.string.pref_folders_hidden_markers_title),
      checked = markerEnabled,
      enabled = enabled,
      onCheckedChange = onMarkerChanged,
      shape = rememberConnectedShape(offset + 2, groupSize, ConnectedLayout.Vertical),
    )
    CompactHiddenScanOption(
      title = stringResource(R.string.pref_hidden_scan_dot),
      checked = dotEnabled,
      enabled = enabled,
      onCheckedChange = onDotChanged,
      shape = rememberConnectedShape(offset + 3, groupSize, ConnectedLayout.Vertical),
    )
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = rememberConnectedShape(offset + 4, groupSize, ConnectedLayout.Vertical),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
      LazyRow(
        state = markerListState,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
          .tvContextMenu(onLongClick)
          .combinedClickable(onClick = onClick, onLongClick = onLongClick)
          .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isInSelectionMode) {
        Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.size(40.dp))
      } else {
        FolderRowIcon(icon = Icons.RoundedFilled.FolderOff, tinted = true)
      }
      Spacer(Modifier.width(14.dp))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = leafStorageName(folderPath),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = folderPath,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!isInSelectionMode) {
          Spacer(modifier = Modifier.height(4.dp))
          AppComboBoxChip(
            options = BlacklistScope.entries,
            selected = scope,
            onSelect = onScopeChange,
            optionLabel = { blacklistScopeLabel(it) },
            contentDescription = stringResource(R.string.folder_blacklist_scope),
          )
        }
      }
      if (!isInSelectionMode) {
        IconButton(
          onClick = onRemove,
          modifier = Modifier.tvFocusHighlight(CircleShape, focusedScale = 1.06f),
        ) {
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
private fun blacklistScopeLabel(scope: BlacklistScope): String =
  stringResource(
    when (scope) {
      BlacklistScope.BOTH -> R.string.folder_blacklist_both
      BlacklistScope.VIDEO_ONLY -> R.string.folder_blacklist_video
      BlacklistScope.AUDIO_ONLY -> R.string.folder_blacklist_audio
    },
  )

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

@Composable
private fun ScanFilterOptionRow(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = onClick)
    Text(text = label, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun <T> ScanFilterOptionsDialog(
  @StringRes title: Int,
  options: List<T>,
  selected: T,
  label: (T) -> String,
  onSelect: (T) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(title)) },
    text = {
      Column {
        options.forEach { option ->
          ScanFilterOptionRow(
            label = label(option),
            selected = option == selected,
            onClick = {
              onSelect(option)
              onDismiss()
            },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.generic_cancel)) }
    },
  )
}

/** Drops every cached listing, runs [rebuild], then tells open lists to reload with the new settings. */
private inline fun invalidateLibraryScan(
  context: Context,
  rebuild: () -> Unit = {},
) {
  app.gyrolet.mpvrx.repository.MediaFileRepository.clearCache()
  MediaLibraryCache.clear(context)
  MediaStoreGenerationGuard.invalidate(context)
  rebuild()
  MediaLibraryEvents.notifyChanged()
}

private val VideoDurationFilterSeconds = listOf(0, 5, 15, 30, 60)
private val FileSizeFilterBytes = listOf(0L, 500L * 1024L, 1024L * 1024L, 5L * 1024L * 1024L)

// Rows drawn by HiddenScanSettingsCard, and the scan-filter rows that complete its group.
private const val HIDDEN_SCAN_ROW_COUNT = 5
private const val SCAN_FILTER_ROW_COUNT = 2

private fun scanDurationLabel(seconds: Int, noLimit: String): String = if (seconds == 0) noLimit else "${seconds}s"

private fun scanSizeLabel(bytes: Long, noLimit: String): String =
  when {
    bytes == 0L -> noLimit
    bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)} MB"
    else -> "${bytes / 1024L} KB"
  }
