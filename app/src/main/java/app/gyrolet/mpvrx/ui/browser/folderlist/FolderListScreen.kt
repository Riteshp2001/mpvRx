/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.folderlist

import android.content.Context
import android.content.Intent
import android.util.Log
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.gyrolet.mpvrx.ui.browser.fab.FabPlacement
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.theme.AppMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.ui.browser.components.rememberVideoSwipeActions
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FolderViewMode
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionListGap
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionGridGap
import app.gyrolet.mpvrx.ui.browser.cards.mediaSelectionNeighbors
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.browser.components.BrowserBottomBar
import app.gyrolet.mpvrx.ui.browser.components.rememberBrowserBottomBarBackdrop
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.mediaItemMotion
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FileOperationProgressDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.BrowserSortDialogScope
import app.gyrolet.mpvrx.ui.browser.dialogs.rememberBrowserSortDialogOpen
import app.gyrolet.mpvrx.ui.browser.dialogs.RenameDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.rememberSelectionInfoAction
import app.gyrolet.mpvrx.ui.browser.filesystem.FileSystemBrowserRootScreen
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryContent
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.states.LoadingState
import app.gyrolet.mpvrx.ui.browser.states.PermissionDeniedState
import app.gyrolet.mpvrx.ui.browser.videolist.VideoListScreen
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.securefolder.SecureFolderGateScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import app.gyrolet.mpvrx.ui.tv.focusableWhen
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.MediaSearchEngine
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.OpenDocumentTreeContract
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.sort.SortUtils
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

private data class FolderDeletionResult(
  val deleted: Int,
  val failed: Int,
  val failedFolders: List<VideoFolder>,
)

@Serializable
object FolderListScreen : Screen {
  @OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val browserPreferences = koinInject<BrowserPreferences>()
    val folderViewMode by browserPreferences.folderViewMode.collectAsState()

    BrowserSortDialogScope {
      when (folderViewMode) {
        FolderViewMode.FileManager -> FileSystemBrowserRootScreen.Content()
        FolderViewMode.AlbumView -> MediaStoreFolderListContent()
        FolderViewMode.MediaLibrary -> MediaLibraryContent()
      }
    }
  }

  @OptIn(ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  internal fun MediaStoreFolderListContent(
    audioOnly: Boolean = false,
    embedded: Boolean = false,
    searchQuery: String = "",
  ) {
    val context = LocalContext.current
    val isTelevision = app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(context)
    val backstack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()
    val folderDeletionMutex = remember { Mutex() }

    // ViewModels and preferences
    val viewModel: FolderListViewModel =
      viewModel(
        key = if (audioOnly) "MusicListViewModel" else "FolderListViewModel",
        factory = FolderListViewModel.factory(context.applicationContext as android.app.Application, audioOnly),
      )
    val browserPreferences = koinInject<BrowserPreferences>()
    val gesturePreferences = koinInject<GesturePreferences>()
    val foldersPreferences = koinInject<FoldersPreferences>()
    val advancedPreferences = koinInject<app.gyrolet.mpvrx.preferences.AdvancedPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
    val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()

    // State collection
    val videoFolders by viewModel.videoFolders.collectAsStateWithLifecycle()
    val foldersWithNewCount by viewModel.foldersWithNewCount.collectAsStateWithLifecycle()
    val recentlyPlayedFilePath by viewModel.recentlyPlayedFilePath.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val hasCompletedInitialLoad by viewModel.hasCompletedInitialLoad.collectAsStateWithLifecycle()
    val foldersWereDeleted by viewModel.foldersWereDeleted.collectAsStateWithLifecycle()

    // Preferences
    val mediaLayoutMode by browserPreferences.folderViewFolderLayoutMode.collectAsState()
    val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
    val folderSortType by browserPreferences.folderSortType.collectAsState()
    val folderSortOrder by browserPreferences.folderSortOrder.collectAsState()
    val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val pinnedFolderPaths by foldersPreferences.pinnedFolders.collectAsState()
    val dualPaneForTablet by browserPreferences.dualPaneForTablet.collectAsState()

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val isDualPaneActive = isTablet && dualPaneForTablet

    var selectedFolderBucketId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFolderName by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(isDualPaneActive) {
      if (!isDualPaneActive) {
        selectedFolderBucketId = null
        selectedFolderName = null
      }
    }

    // UI state - use standalone states to avoid scroll issues with predictive back gesture
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val navigationBarHeight = LocalNavigationBarHeight.current
    val navBarState = app.gyrolet.mpvrx.ui.browser.NavigationBarState
    val isRefreshing = remember { mutableStateOf(false) }
    val sortDialogOpen = rememberBrowserSortDialogOpen(useHomeState = !embedded && !audioOnly)
    var pendingDeleteFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    val showLinkDialog = remember { mutableStateOf(false) }
    val folderPickerOpen = rememberSaveable { mutableStateOf(false) }
    val operationType = remember { mutableStateOf<CopyPasteOps.OperationType?>(null) }
    val progressDialogOpen = rememberSaveable { mutableStateOf(false) }
    var renameDialogOpen by rememberSaveable { mutableStateOf(false) }
    val operationProgress by CopyPasteOps.operationProgress.collectAsState()

    // Move-to-Secure-Folder state (moves every video inside the selected folders)
    val secureFolderRepository = koinInject<app.gyrolet.mpvrx.database.repository.SecureFolderRepository>()
    val secureFolderPreferences = koinInject<app.gyrolet.mpvrx.preferences.SecureFolderPreferences>()
    val moveToSecureConfirmOpen = rememberSaveable { mutableStateOf(false) }
    val moveToSecureProgressOpen = rememberSaveable { mutableStateOf(false) }
    val secureFolderProgress by secureFolderRepository.progress.collectAsState()

    // Search state
    var internalSearchQuery by rememberSaveable { mutableStateOf("") }
    var internalIsSearching by rememberSaveable { mutableStateOf(false) }
    val effectiveSearchQuery = if (embedded) searchQuery else searchQuery.ifBlank { internalSearchQuery }
    val effectiveIsSearching = if (embedded) searchQuery.isNotBlank() else (internalIsSearching || internalSearchQuery.isNotBlank())
    var searchResults by remember { mutableStateOf<List<FileSystemItem>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var searchIndexJob by remember { mutableStateOf<Job?>(null) }
    val foldersBlacklistedMessage = stringResource(app.gyrolet.mpvrx.R.string.pref_folders_blacklisted)

    LaunchedEffect(internalIsSearching) {
      if (internalIsSearching) focusRequester.requestFocus()
    }

    // Rebuilds when folders change so incrementally discovered hidden folders become searchable;
    // chained on the previous job because buildIndex resets the shared index maps.
    LaunchedEffect(effectiveIsSearching, videoFolders, audioOnly) {
      if (audioOnly || !effectiveIsSearching) return@LaunchedEffect
      val previous = searchIndexJob
      searchIndexJob =
        coroutineScope.launch {
          previous?.join()
          buildSearchIndex(context, videoFolders, audioOnly)
        }
    }

    // Search logic
    LaunchedEffect(effectiveSearchQuery, effectiveIsSearching, audioOnly, videoFolders) {
      if (effectiveIsSearching && effectiveSearchQuery.isNotBlank()) {
        delay(250)
        isSearchLoading = true
        try {
          searchResults =
            if (audioOnly) {
              val q = effectiveSearchQuery.trim().lowercase()
              val matchingFolders = videoFolders.filter { folder ->
                folder.name.lowercase().contains(q) || folder.path.lowercase().contains(q)
              }.map { folder ->
                FileSystemItem.Folder(
                  name = folder.name,
                  path = folder.path,
                  lastModified = folder.lastModified,
                  videoCount = folder.videoCount,
                  totalSize = folder.totalSize,
                  totalDuration = folder.totalDuration,
                )
              }
              val matchingAudioFiles = app.gyrolet.mpvrx.repository.MediaFileRepository
                .searchAudio(context, effectiveSearchQuery)
                .map { audio ->
                  FileSystemItem.VideoFile(
                    name = audio.displayName,
                    path = audio.path,
                    lastModified = audio.dateModified,
                    video = audio,
                  )
                }
              matchingFolders + matchingAudioFiles
            } else {
              searchIndexJob?.join()
              searchFoldersAndVideos(context, effectiveSearchQuery)
            }
        } catch (e: Exception) {
          Log.e("FolderListScreen", "Error during search", e)
          searchResults = emptyList()
        } finally {
          isSearchLoading = false
        }
      } else {
        searchResults = emptyList()
        isSearchLoading = false
      }
    }

    // FAB state
    val isFabVisible = remember { mutableStateOf(true) }
    val isFabExpanded = remember { mutableStateOf(false) }

    // File picker
    val filePicker =
      rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
      ) { uri ->
        uri?.let {
          runCatching {
            context.contentResolver.takePersistableUriPermission(
              it,
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          }
          MediaUtils.playFile(it.toString(), context, "open_file")
        }
      }

    // Sorting and filtering
    val sortedFolders =
      remember(videoFolders, folderSortType, folderSortOrder, pinnedFolderPaths) {
        val sorted = SortUtils.sortFolders(videoFolders, folderSortType, folderSortOrder)
        val (pinned, unpinned) = sorted.partition { it.path in pinnedFolderPaths }
        pinned + unpinned
      }

    val filteredFolders = sortedFolders

    suspend fun deleteFolders(folders: List<VideoFolder>): FolderDeletionResult {
      if (BuildConfig.SCOPED_STORAGE_ONLY) {
        // Scoped storage must not expose directory deletion through file-manager actions.
        // Per-video delete remains available through SAF document write grants.
        return FolderDeletionResult(0, folders.size, folders)
      }
      var deleted = 0
      var failed = 0
      val failedFolders = mutableListOf<VideoFolder>()
      val deleteAll = browserPreferences.deleteFolderAllContents.get()
      // The audio-only folder browser must always be able to delete the audio files it shows,
      // regardless of the general "include audio in browser" preference (which only governs the
      // regular video browser). The regular (video) browser keeps its existing preference-driven behavior.
      val includeAudio = audioOnly || browserPreferences.includeAudioBrowser.get()
      for (folder in folders) {
        var folderFailed = false
        try {
          if (deleteAll) {
            val ids = setOf(folder.bucketId)
            val videos =
              app.gyrolet.mpvrx.repository.MediaFileRepository
                .getVideosForBuckets(context, ids, includeAudioOverride = if (audioOnly) true else null)
            if (videos.isNotEmpty()) {
              val (d, f) = viewModel.deleteVideos(videos)
              deleted += d
              failed += f
              folderFailed = f > 0
            }
            val dir = java.io.File(folder.path)
            if (dir.exists()) {
              if (dir.deleteRecursively()) {
                deleted++
              } else {
                failed++
                folderFailed = true
              }
            }
          } else {
            var deletedAny = false
            val dir = java.io.File(folder.path)
            if (dir.exists()) {
              dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                  val ext = file.extension.lowercase()
                  // In the audio-only browser, only touch audio files - never delete video files
                  // that might live alongside them in the same on-disk folder.
                  val isVideo = !audioOnly && ext in FileTypeUtils.VIDEO_EXTENSIONS
                  val isAudio = includeAudio && ext in FileTypeUtils.AUDIO_EXTENSIONS
                  if (isVideo || isAudio) {
                    if (file.delete()) deletedAny = true
                  }
                }
              }
            }
            if (deletedAny) {
              deleted++
            } else {
              failed++
              folderFailed = true
            }
          }
        } catch (e: Exception) {
          Log.e("FolderListScreen", "Error deleting folder ${folder.path}", e)
          failed++
          folderFailed = true
        }
        if (folderFailed) failedFolders += folder
      }
      return FolderDeletionResult(deleted, failed, failedFolders)
    }

    suspend fun deleteFoldersInBackground(folders: List<VideoFolder>): FolderDeletionResult =
      withContext(Dispatchers.IO) {
        folderDeletionMutex.withLock { deleteFolders(folders) }
      }

    fun clearSelectedFolderForDeletion(folders: List<VideoFolder>): VideoFolder? {
      val selectedFolder = folders.firstOrNull { it.bucketId == selectedFolderBucketId } ?: return null
      selectedFolderBucketId = null
      selectedFolderName = null
      return selectedFolder
    }

    fun restoreSelectedFolderAfterFailure(folder: VideoFolder?) {
      folder ?: return
      selectedFolderBucketId = folder.bucketId
      selectedFolderName = folder.name
    }

    // Selection manager
    val selectionManager =
      rememberSelectionManager(
        items = sortedFolders,
        getId = { it.bucketId },
        onDeleteItems = { folders, _ ->
          val selectedFolder = clearSelectedFolderForDeletion(folders)
          viewModel.beginFolderDeletion(folders)
          try {
            val result = deleteFoldersInBackground(folders)
            viewModel.finishFolderDeletion(folders, result.failedFolders)
            if (selectedFolder != null && result.failedFolders.any { it.bucketId == selectedFolder.bucketId }) {
              restoreSelectedFolderAfterFailure(selectedFolder)
            }
            result.deleted to result.failed
          } catch (error: Exception) {
            viewModel.finishFolderDeletion(folders, folders)
            restoreSelectedFolderAfterFailure(selectedFolder)
            throw error
          }
        },
        onOperationComplete = { viewModel.refresh() },
      )

    val showSelectionInfo = rememberSelectionInfoAction(
      videos = { emptyList() },
      onSingleOpened = {},
      folders = { selectionManager.getSelectedItems() },
      loadFolderVideos = { folder ->
        app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosInFolder(
          context,
          folder.bucketId,
          includeAudioOverride = if (audioOnly) true else null,
        )
      },
    )

    fun moveSelectedFoldersToSecureFolder() {
      val selectedIds = selectionManager.getSelectedItems().map { it.bucketId }.toSet()
      if (selectedIds.isEmpty()) return
      moveToSecureProgressOpen.value = true
      coroutineScope.launch {
        val allVideos =
          app.gyrolet.mpvrx.repository.MediaFileRepository
            .getVideosForBuckets(context, selectedIds, includeAudioOverride = if (audioOnly) true else null)
        if (allVideos.isNotEmpty()) {
          val result = secureFolderRepository.moveIn(context, allVideos)
          moveToSecureProgressOpen.value = false
          result
            .onSuccess { batch ->
              val message =
                if (batch.failedIds.isEmpty()) {
                  context.getString(R.string.secure_folder_moved_success, batch.succeededIds.size)
                } else {
                  context.getString(R.string.secure_folder_moved_partial, batch.succeededIds.size, batch.failedIds.size)
                }
              Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
              Toast.error(context, context.getString(
                R.string.secure_folder_move_failed_details,
                error.localizedMessage ?: context.getString(R.string.generic_unknown_error),
              ))
            }
          if (result.isSuccess) selectionManager.clear()
        }
        moveToSecureProgressOpen.value = false
        viewModel.refresh()
      }
    }

    val treePickerLauncher =
      rememberLauncherForActivityResult(
        contract = OpenDocumentTreeContract(),
      ) { uri ->
        if (uri == null || operationType.value == null) return@rememberLauncherForActivityResult
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }
        progressDialogOpen.value = true
        coroutineScope.launch {
          val selectedFolders = selectionManager.getSelectedItems()
          val selectedVideos =
            selectedFolders.flatMap { folder ->
              app.gyrolet.mpvrx.repository.MediaFileRepository
                .getVideosForBuckets(context, setOf(folder.bucketId), includeAudioOverride = if (audioOnly) true else null)
            }
          if (selectedVideos.isNotEmpty()) {
            when (operationType.value) {
              is CopyPasteOps.OperationType.Copy -> CopyPasteOps.copyFilesToTreeUri(context, selectedVideos, uri)
              is CopyPasteOps.OperationType.Move -> CopyPasteOps.moveFilesToTreeUri(context, selectedVideos, uri)
              else -> {}
            }
          }
        }
      }

    // Permissions
    val permissionState =
      PermissionUtils.handleStoragePermission(
        audioOnly = audioOnly,
        onPermissionGranted = { viewModel.refresh() },
      )

    val selectedTreeUris by foldersPreferences.scopedHiddenFolderUris.collectAsState()
    val includeSelectedFolders by foldersPreferences.includeNoMediaFolders.collectAsState()
    val canBrowseSelectedFolders = BuildConfig.SCOPED_STORAGE_ONLY && includeSelectedFolders &&
      selectedTreeUris.isNotEmpty()

    var isPermissionSetupCompleted by androidx.compose.runtime.saveable.rememberSaveable {
      androidx.compose.runtime.mutableStateOf(
        permissionState.status == PermissionStatus.Granted || browserPreferences.onboardingCompleted.get(),
      )
    }

    // After onboarding the app is fully usable; missing storage only shows an in-place prompt.
    LaunchedEffect(permissionState.status, isPermissionSetupCompleted) {
      app.gyrolet.mpvrx.ui.browser.MainScreen.updatePermissionState(
        isDenied = !isPermissionSetupCompleted,
      )
    }

    // Update NavigationBarState synchronously when selection mode changes
    app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect(selectionManager.isInSelectionMode)

    val isNavigationPageActive = app.gyrolet.mpvrx.ui.utils.LocalNavigationPageActive.current
    // Measure a usable library (including empty/error/permission screens), not just first compose.
    // An offscreen pager page must not delay another destination's fully-drawn report.
    androidx.activity.compose.ReportDrawnWhen {
      !isNavigationPageActive || !isPermissionSetupCompleted ||
        (permissionState.status != PermissionStatus.Granted && !canBrowseSelectedFolders) ||
        hasCompletedInitialLoad
    }
    // The Folder tab owns its hidden-folder refresh; visiting Library must never be required
    // to notice a .nomedia marker added or removed by an external file manager.
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel, isNavigationPageActive) {
      val probeJob = if (isNavigationPageActive) {
        coroutineScope.launch(Dispatchers.IO) {
          var resumed = true
          while (isActive) {
            try {
              viewModel.checkExternalFolderChanges(onResume = resumed)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
              throw cancelled
            } catch (error: Exception) {
              android.util.Log.w("FolderListScreen", "Unable to check external folder changes", error)
            }
            resumed = false
            delay(10_000L)
          }
        }
      } else null
      onPauseOrDispose { probeJob?.cancel() }
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(isDualPaneActive, selectedFolderBucketId, isNavigationPageActive) {
      if (isNavigationPageActive) {
        navBarState.isDualPaneFolderSelected = isDualPaneActive && selectedFolderBucketId != null
      }
      onPauseOrDispose {
        if (isNavigationPageActive) navBarState.isDualPaneFolderSelected = false
      }
    }

    // Optimized back handler for immediate response
    val shouldHandleBack =
      selectionManager.isInSelectionMode ||
        (!embedded && internalIsSearching) ||
        isFabExpanded.value ||
        (isDualPaneActive && selectedFolderBucketId != null)
    app.gyrolet.mpvrx.ui.utils.NavigationBackHandler(enabled = shouldHandleBack) {
      when {
        isFabExpanded.value -> isFabExpanded.value = false
        selectionManager.isInSelectionMode -> selectionManager.clear()
        !embedded && internalIsSearching -> {
          internalIsSearching = false
          internalSearchQuery = ""
        }
        isDualPaneActive && selectedFolderBucketId != null -> {
          selectedFolderBucketId = null
          selectedFolderName = null
        }
      }
    }

    // FAB scroll tracking
    app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper.trackScrollForFabVisibility(
      listState = listState,
      gridState = if (mediaLayoutMode == MediaLayoutMode.GRID) gridState else null,
      isFabVisible = isFabVisible,
      expanded = isFabExpanded.value,
      onExpandedChange = { isFabExpanded.value = it },
    )

    @Composable
    fun FoldersPane() {
      val bottomBarBackdrop = rememberBrowserBottomBarBackdrop()
      val showFloatingBottomBar = selectionManager.isInSelectionMode

      Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
          modifier = Modifier.captureLiquidGlassBackdrop(bottomBarBackdrop),
          containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
          contentWindowInsets = if (embedded) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
          topBar = {
          if (embedded) {
            // Embedded inside another screen (e.g. Music tab) which already renders its own top bar.
          } else if (internalIsSearching) {
            InlineSearchBar(
              query = internalSearchQuery,
              onQueryChange = { internalSearchQuery = it },
              onSearch = { },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 8.dp),
              inputFieldModifier = Modifier.focusRequester(focusRequester),
              placeholder = {
                Text(
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_search_folders_and_videos),
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.settings_search_title,
                    ),
                )
              },
              trailingIcon = {
                IconButton(
                  onClick = {
                    internalIsSearching = false
                    internalSearchQuery = ""
                  },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Close,
                    contentDescription =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.generic_cancel,
                      ),
                  )
                }
              },
              shape = RoundedCornerShape(28.dp),
              tonalElevation = 6.dp,
            )
          } else {
            BrowserTopBar(
              title = stringResource(app.gyrolet.mpvrx.R.string.app_name),
              isInSelectionMode = selectionManager.isInSelectionMode,
              selectedCount = selectionManager.selectedCount,
              totalCount = videoFolders.size,
              onBackClick = null,
              onCancelSelection = { selectionManager.clear() },
              onSortClick = { sortDialogOpen.value = true },
              onSearchClick = {
                internalIsSearching = !internalIsSearching
                if (!internalIsSearching) internalSearchQuery = ""
              },
              onSettingsClick = {
                backstack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
              },
              onTitleDoubleTap = { backstack.navigateTo(SecureFolderGateScreen) },
              onTitleLongPress = { backstack.navigateTo(SecureFolderGateScreen) },
              showBetaBadge = BuildConfig.IS_PREVIEW_BUILD,
              onRenameClick = null,
              isSingleSelection = selectionManager.isSingleSelection,
              onInfoClick = showSelectionInfo,
              onShareClick = {
                coroutineScope.launch {
                  val selectedIds = selectionManager.getSelectedItems().map { it.bucketId }.toSet()
                  val allVideos =
                    app.gyrolet.mpvrx.repository.MediaFileRepository
                      .getVideosForBuckets(context, selectedIds, includeAudioOverride = if (audioOnly) true else null)
                  if (allVideos.isNotEmpty()) {
                    MediaUtils.shareVideos(context, allVideos)
                  }
                }
              },
              onPlayClick = {
                coroutineScope.launch {
                  val selectedIds = selectionManager.getSelectedItems().map { it.bucketId }.toSet()
                  val allVideos =
                    app.gyrolet.mpvrx.repository.MediaFileRepository
                      .getVideosForBuckets(context, selectedIds, includeAudioOverride = if (audioOnly) true else null)
                  if (allVideos.isNotEmpty()) {
                    if (allVideos.size == 1) {
                      MediaUtils.playFile(allVideos.first(), context)
                    } else {
                      MediaUtils.playFiles(allVideos, context)
                    }
                    selectionManager.clear()
                  }
                }
              },
              onBlacklistClick = {
                coroutineScope.launch {
                  val selectedFolders = selectionManager.getSelectedItems()
                  val paths = selectedFolders.map { it.path }.toSet()
                  val scope = if (audioOnly) app.gyrolet.mpvrx.preferences.BlacklistScope.AUDIO_ONLY else app.gyrolet.mpvrx.preferences.BlacklistScope.VIDEO_ONLY
                  foldersPreferences.addBlacklistedFolders(paths, scope)
                  selectionManager.clear()
                  viewModel.refresh()
                  app.gyrolet.mpvrx.ui.components.AppToast
                    .makeText(
                      context,
                      foldersBlacklistedMessage,
                      app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
                    ).show()
                }
              },
              onDeleteClick = null,
              onSelectAll = { selectionManager.selectAll() },
              onInvertSelection = { selectionManager.invertSelection() },
              onDeselectAll = { selectionManager.clear() },
              onMoveToSecureClick = {
                if (!secureFolderPreferences.isPinSet()) {
                  backstack.navigateTo(SecureFolderGateScreen)
                } else if (secureFolderPreferences.dontAskBeforeMove.get()) {
                  moveSelectedFoldersToSecureFolder()
                } else {
                  moveToSecureConfirmOpen.value = true
                }
              },
            )
          }
        },
        floatingActionButton = {
          val isFabShouldBeVisible =
            showQuickPlayFab &&
              !selectionManager.isInSelectionMode &&
              isFabVisible.value &&
              !app.gyrolet.mpvrx.ui.browser.MainScreen
                .getPermissionDeniedState() &&
              !(isDualPaneActive && selectedFolderBucketId != null)

          FloatingActionButtonMenu(
            modifier = Modifier.padding(bottom = FabPlacement.scaffoldBottomPadding()),
            expanded = isFabExpanded.value && !quickPlayFabDirect,
            button = {
              TooltipBox(
                positionProvider =
                  TooltipDefaults.rememberTooltipPositionProvider(
                    if (isFabExpanded.value && !quickPlayFabDirect) {
                      TooltipAnchorPosition.Start
                    } else {
                      TooltipAnchorPosition.Above
                    },
                  ),
                tooltip = {
                  PlainTooltip {
                    Text(
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_toggle_menu),
                    )
                  }
                },
                state = rememberTooltipState(),
              ) {
                ToggleFloatingActionButton(
                  modifier =
                    Modifier.animateFloatingActionButton(
                      visible = isFabShouldBeVisible,
                      alignment = Alignment.BottomEnd,
                    ).focusableWhen(isFabShouldBeVisible),
                  checked = isFabExpanded.value && !quickPlayFabDirect,
                  onCheckedChange = {
                    if (quickPlayFabDirect) {
                      coroutineScope.launch {
                        val lastPlayed =
                          app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
                            .getLastPlayedEntity()
                        if (lastPlayed != null) {
                          MediaUtils.playFile(
                            source = lastPlayed.filePath,
                            context = context,
                            launchSource = "quick_play_fab",
                            title =
                              lastPlayed.videoTitle?.takeIf { it.isNotBlank() }
                                ?: lastPlayed.fileName.takeIf { it.isNotBlank() },
                          )
                        }
                      }
                    } else {
                      isFabExpanded.value = !isFabExpanded.value
                    }
                  },
                ) {
                  val imageVector by remember {
                    derivedStateOf {
                      if (checkedProgress > 0.5f && !quickPlayFabDirect) Icons.RoundedFilled.Close else Icons.RoundedFilled.PlayArrow
                    }
                  }
                  Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    modifier = Modifier.animateIcon({ if (quickPlayFabDirect) 0f else checkedProgress }),
                  )
                }
              }
            },
          ) {
            if (!quickPlayFabDirect) {
              FloatingActionButtonMenuItem(
                onClick = {
                  isFabExpanded.value = false
                  filePicker.launch(if (audioOnly) arrayOf("audio/*") else FileTypeUtils.videoPickerMimeTypes())
                },
                icon = { Icon(Icons.RoundedFilled.FileOpen, contentDescription = null) },
                text = {
                  Text(
                    text =
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_open_file),
                  )
                },
              )

              FloatingActionButtonMenuItem(
                onClick = {
                  isFabExpanded.value = false
                  coroutineScope.launch {
                    val recentlyPlayedVideos = RecentlyPlayedOps.getRecentlyPlayed(limit = 1)
                    val lastPlayed = recentlyPlayedVideos.firstOrNull()
                    if (lastPlayed != null) {
                      MediaUtils.playFile(lastPlayed.filePath, context, "recently_played_button")
                    }
                  }
                },
                icon = { Icon(Icons.RoundedFilled.History, contentDescription = null) },
                text = {
                  Text(
                    text =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.pref_advanced_enable_recently_played_title,
                      ),
                  )
                },
              )

              FloatingActionButtonMenuItem(
                onClick = {
                  isFabExpanded.value = false
                  showLinkDialog.value = true
                },
                icon = { Icon(Icons.RoundedFilled.Link, contentDescription = null) },
                text = {
                  Text(
                    text =
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_open_link),
                  )
                },
              )
            }
          }
        },
      ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
          if ((isPermissionSetupCompleted && permissionState.status == PermissionStatus.Granted) || canBrowseSelectedFolders) {
              if (effectiveIsSearching) {
                // Show search results
                Box(modifier = Modifier.fillMaxSize()) {
                  if (isSearchLoading) {
                    // Loading state
                    Box(
                      modifier = Modifier.fillMaxSize(),
                      contentAlignment = Alignment.Center,
                    ) {
                      CircularProgressIndicator()
                    }
                  } else if (searchResults.isEmpty()) {
                    // No results
                    EmptyState(
                      icon = Icons.RoundedFilled.Search,
                      title = stringResource(R.string.ui_no_results_found),
                      message = if (audioOnly) "No audio folders or songs match your search query" else "No folders or videos match your search query",
                      modifier = Modifier.fillMaxSize(),
                    )
                  } else {
                    // Show search results
                    SearchResultsContent(
                      searchResults = searchResults,
                      onChanged = { viewModel.refresh() },
                      audioOnly = audioOnly,
                      navigationBarHeight = navigationBarHeight,
                      onFolderClick = { folder ->
                        if (isDualPaneActive) {
                          selectedFolderBucketId = folder.bucketId
                          selectedFolderName = folder.name
                          if (!embedded) {
                            internalIsSearching = false
                            internalSearchQuery = ""
                          }
                        } else {
                          backstack.navigateTo(
                            app.gyrolet.mpvrx.ui.browser.videolist
                              .VideoListScreen(folder.bucketId, folder.name, isAudio = audioOnly),
                          )
                        }
                      },
                      onVideoClick = { video ->
                        MediaUtils.playFile(video, context)
                      },
                      mediaLayoutMode = mediaLayoutMode,
                    )
                  }
                }
              } else {
                FolderListContent(
                  folders = filteredFolders,
                  foldersWithNewCount = foldersWithNewCount,
                  pinnedFolderPaths = pinnedFolderPaths,
                  recentlyPlayedFilePath = recentlyPlayedFilePath,
                  isLoading = isLoading,
                  scanStatus = scanStatus,
                  hasCompletedInitialLoad = hasCompletedInitialLoad,
                  foldersWereDeleted = foldersWereDeleted,
                  mediaLayoutMode = mediaLayoutMode,
                  tapThumbnailToSelect = tapThumbnailToSelect,
                  navigationBarHeight = navigationBarHeight,
                  listState = listState,
                  gridState = gridState,
                  isRefreshing = isRefreshing,
                  selectionManager = selectionManager,
                  onRefresh = { viewModel.refresh() },
                  onFolderClick = { folder ->
                    if (selectionManager.isInSelectionMode && !ZipArchiveMedia.isBrowserPath(folder.path) &&
                      (!app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId) || BuildConfig.SCOPED_STORAGE_ONLY)) {
                      selectionManager.toggleFromUser(folder)
                    } else if (!selectionManager.isInSelectionMode) {
                      if (isDualPaneActive) {
                        selectedFolderBucketId = folder.bucketId
                        selectedFolderName = folder.name
                      } else {
                        backstack.navigateTo(
                          app.gyrolet.mpvrx.ui.browser.videolist
                            .VideoListScreen(folder.bucketId, folder.name, isAudio = audioOnly),
                        )
                      }
                    }
                  },
                  onFolderLongClick = { folder ->
                    if (!ZipArchiveMedia.isBrowserPath(folder.path) &&
                      (!app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId) || BuildConfig.SCOPED_STORAGE_ONLY)) {
                      selectionManager.handleLongClick(folder)
                    }
                  },
                  onTogglePin = { folder ->
                    coroutineScope.launch {
                      val updated = foldersPreferences.pinnedFolders.get().toMutableSet()
                      if (!updated.add(folder.path)) {
                        updated.remove(folder.path)
                      }
                      foldersPreferences.pinnedFolders.set(updated)
                    }
                  },
                  selectedFolderBucketId = selectedFolderBucketId,
                  audioOnly = audioOnly,
                )
              }
          } else if (isPermissionSetupCompleted) {
            app.gyrolet.mpvrx.ui.browser.states.StoragePermissionPrompt(
              onRequestPermission = { permissionState.launchPermissionRequest() },
            )
          } else {
            PermissionDeniedState(
              onRequestPermission = { permissionState.launchPermissionRequest() },
              onNext = {
                isPermissionSetupCompleted = true
                viewModel.refresh()
              },
              modifier = Modifier,
            )
          }

          FabScrollHelper.FabScrim(
            visible = isFabExpanded.value && !quickPlayFabDirect,
            onDismiss = { isFabExpanded.value = false },
          )
        }
      }

      // Independent Floating Bottom Bar - positioned at absolute bottom
      AnimatedVisibility(
        visible = showFloatingBottomBar,
        enter =
          slideInVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Expressive.dampingRatio,
                stiffness = AppMotion.Spatial.Expressive.stiffness,
              ),
            initialOffsetY = { fullHeight -> fullHeight },
          ),
        exit =
          slideOutVertically(
            animationSpec =
              spring(
                dampingRatio = AppMotion.Spatial.Standard.dampingRatio,
                stiffness = AppMotion.Spatial.Standard.stiffness,
              ),
            targetOffsetY = { fullHeight -> fullHeight },
          ),
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        BrowserBottomBar(
          backdrop = bottomBarBackdrop,
          isSelectionMode = selectionManager.isInSelectionMode,
          onCopyClick = {
            operationType.value = CopyPasteOps.OperationType.Copy
            if (CopyPasteOps.canUseDirectFileOperations()) {
              folderPickerOpen.value = true
            } else {
              treePickerLauncher.launch(null)
            }
          },
          onMoveClick = {
            operationType.value = CopyPasteOps.OperationType.Move
            if (CopyPasteOps.canUseDirectFileOperations()) {
              folderPickerOpen.value = true
            } else {
              treePickerLauncher.launch(null)
            }
          },
          onRenameClick = { renameDialogOpen = true },
          onDeleteClick = { pendingDeleteFolders = selectionManager.getSelectedItems() },
          onAddToPlaylistClick = { },
          onPinClick = {
            val selectedFolders = selectionManager.getSelectedItems()
            if (selectedFolders.isNotEmpty()) {
              val pinned = foldersPreferences.pinnedFolders.get()
              val paths = selectedFolders.map { it.path }.toSet()
              foldersPreferences.pinnedFolders.set(if (pinned.containsAll(paths)) pinned - paths else pinned + paths)
              selectionManager.clear()
            }
          },
          unpinSelected =
            selectionManager.getSelectedItems().let { selected ->
              selected.isNotEmpty() && selected.all { it.path in pinnedFolderPaths }
            },
          showCopy = true,
          showMove = true,
          showRename = selectionManager.isSingleSelection && !BuildConfig.SCOPED_STORAGE_ONLY,
          showDelete = !BuildConfig.SCOPED_STORAGE_ONLY,
          showDownscale = false,
          showAddToPlaylist = false,
          modifier = Modifier.padding(bottom = 0.dp),
        )
      }
    }
  }

    // On TV, replacing FoldersPane with a new Row removes the focused card and can send
    // focus to the navigation rail. Keep the folder pane at a stable composition position.
    @Composable
    fun SelectedFolderContent() {
      val bucketId = selectedFolderBucketId ?: return
      key(bucketId) {
        CompositionLocalProvider(
          LocalNavigationBarHeight provides
            if (app.gyrolet.mpvrx.ui.browser.NavigationBarState.isDualPaneLayout) {
              app.gyrolet.mpvrx.ui.browser.NavigationBarState.miniPlayerClearance
            } else {
              LocalNavigationBarHeight.current
            },
        ) {
          VideoListScreen(
            bucketId = bucketId,
            folderName = selectedFolderName.orEmpty(),
            isDualPane = true,
            isAudio = audioOnly,
            onBack = {
              selectedFolderBucketId = null
              selectedFolderName = null
            },
          ).Content()
        }
      }
    }

    if (isTelevision && isDualPaneActive) {
      Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(if (selectedFolderBucketId != null) 0.4f else 1f)) {
          FoldersPane()
        }
        if (selectedFolderBucketId != null) {
          VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            thickness = 1.dp,
          )
          Box(modifier = Modifier.weight(0.6f)) {
            SelectedFolderContent()
          }
        }
      }
    } else if (isDualPaneActive && selectedFolderBucketId != null) {
      // Non-TV tablet split-pane remains unchanged.
      Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.4f)) {
          FoldersPane()
        }
        VerticalDivider(
          modifier = Modifier.fillMaxHeight(),
          color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
          thickness = 1.dp,
        )
        Box(modifier = Modifier.weight(0.6f)) {
          SelectedFolderContent()
        }
      }
    } else {
      FoldersPane()
    }

    // Dialogs
    PlayLinkSheet(
      isOpen = showLinkDialog.value,
      onDismiss = { showLinkDialog.value = false },
      onPlayLink = { url -> MediaUtils.playFile(url, context, "play_link") },
    )

    FolderPickerDialog(
      isOpen = folderPickerOpen.value,
      currentPath = "",
      onDismiss = { folderPickerOpen.value = false },
      onFolderSelected = { destinationPath ->
        folderPickerOpen.value = false
        val op = operationType.value
        if (op != null) {
          coroutineScope.launch {
            val selectedFolders = selectionManager.getSelectedItems()
            if (selectedFolders.isNotEmpty()) {
              when (op) {
                is CopyPasteOps.OperationType.Move -> {
                  val needFallback = mutableListOf<VideoFolder>()
                  for (folder in selectedFolders) {
                    val dst = File(destinationPath, folder.name)
                    if (!File(folder.path).renameTo(dst)) needFallback.add(folder)
                  }
                  if (needFallback.isNotEmpty()) {
                    progressDialogOpen.value = true
                    for (folder in needFallback) {
                      val videos =
                        app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(
                          context,
                          setOf(folder.bucketId),
                          includeAudioOverride = if (audioOnly) true else null,
                        )
                      if (videos.isNotEmpty()) {
                        val subDest = File(destinationPath, folder.name).also { it.mkdirs() }.absolutePath
                        CopyPasteOps.moveFiles(context, videos, subDest)
                      }
                    }
                  } else {
                    selectionManager.clear()
                    viewModel.refresh()
                  }
                }
                is CopyPasteOps.OperationType.Copy -> {
                  progressDialogOpen.value = true
                  for (folder in selectedFolders) {
                    val videos =
                      app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(
                        context,
                        setOf(folder.bucketId),
                        includeAudioOverride = if (audioOnly) true else null,
                      )
                    if (videos.isNotEmpty()) {
                      val subDest = File(destinationPath, folder.name).also { it.mkdirs() }.absolutePath
                      CopyPasteOps.copyFiles(context, videos, subDest)
                    }
                  }
                }
              }
            }
          }
        }
      },
    )

    if (operationType.value != null) {
      FileOperationProgressDialog(
        isOpen = progressDialogOpen.value,
        operationType = operationType.value!!,
        progress = operationProgress,
        onCancel = { CopyPasteOps.cancelOperation() },
        onDismiss = {
          progressDialogOpen.value = false
          operationType.value = null
          selectionManager.clear()
          viewModel.refresh()
        },
      )
    }

    // Move to Secure Folder — confirm (skippable via "don't ask again"), then progress
    app.gyrolet.mpvrx.ui.securefolder.SecureConfirmDialog(
      isOpen = moveToSecureConfirmOpen.value,
      title = stringResource(R.string.secure_folder_move_folders_title, selectionManager.selectedCount),
      subtitle = stringResource(R.string.secure_folder_move_folders_subtitle),
      dontAskAgain = secureFolderPreferences.dontAskBeforeMove,
      onConfirm = {
        moveToSecureConfirmOpen.value = false
        moveSelectedFoldersToSecureFolder()
      },
      onDismiss = { moveToSecureConfirmOpen.value = false },
    )

    app.gyrolet.mpvrx.ui.securefolder.SecureFolderProgressDialog(
      isOpen = moveToSecureProgressOpen.value,
      progress = secureFolderProgress,
      label = stringResource(R.string.secure_folder_moving_progress),
      onCancel = { secureFolderRepository.cancelOperation() },
    )

    if (renameDialogOpen && selectionManager.isSingleSelection) {
      val folder = selectionManager.getSelectedItems().firstOrNull()
      if (folder != null) {
        RenameDialog(
          isOpen = true,
          onDismiss = { renameDialogOpen = false },
          onConfirm = { newName ->
            renameDialogOpen = false
            coroutineScope.launch {
              val ok = viewModel.renameFolder(folder, newName)
              if (!ok) {
                app.gyrolet.mpvrx.ui.components.AppToast
                  .makeText(
                    context,
                    context.getString(app.gyrolet.mpvrx.R.string.ui_rename_failed),
                    app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
                  ).show()
              }
              selectionManager.clear()
              viewModel.refresh()
            }
          },
          currentName = folder.name,
          itemType = "folder",
        )
      }
    }

    FolderSortDialog(
      isOpen = sortDialogOpen.value,
      onDismiss = { sortDialogOpen.value = false },
      sortType = folderSortType,
      sortOrder = folderSortOrder,
      onSortTypeChange = { browserPreferences.folderSortType.set(it) },
      onSortOrderChange = { browserPreferences.folderSortOrder.set(it) },
      isDualPane = isDualPaneActive && selectedFolderBucketId != null,
    )

    if (!BuildConfig.SCOPED_STORAGE_ONLY && pendingDeleteFolders.isNotEmpty()) {
      DeleteConfirmationDialog(
        isOpen = true,
        onDismiss = { pendingDeleteFolders = emptyList() },
        onConfirm = {
          val foldersToDelete = pendingDeleteFolders
          pendingDeleteFolders = emptyList()
          val selectedFolder = clearSelectedFolderForDeletion(foldersToDelete)
          viewModel.beginFolderDeletion(foldersToDelete)
          selectionManager.clear()
          coroutineScope.launch {
            try {
              val result = deleteFoldersInBackground(foldersToDelete)
              viewModel.finishFolderDeletion(foldersToDelete, result.failedFolders)
              if (selectedFolder != null && result.failedFolders.any { it.bucketId == selectedFolder.bucketId }) {
                restoreSelectedFolderAfterFailure(selectedFolder)
              }
              if (result.deleted > 0) {
                app.gyrolet.mpvrx.ui.components.AppToast
                  .makeText(
                    context,
                    context.getString(app.gyrolet.mpvrx.R.string.ui_deleted_successfully),
                    app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
                  ).show()
              } else if (result.failed > 0) {
                app.gyrolet.mpvrx.ui.components.AppToast
                  .makeText(
                    context,
                    context.getString(app.gyrolet.mpvrx.R.string.ui_failed_to_delete),
                    app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
                  ).show()
              }
            } catch (cancellation: CancellationException) {
              viewModel.finishFolderDeletion(foldersToDelete, foldersToDelete)
              restoreSelectedFolderAfterFailure(selectedFolder)
              throw cancellation
            } catch (error: Exception) {
              viewModel.finishFolderDeletion(foldersToDelete, foldersToDelete)
              restoreSelectedFolderAfterFailure(selectedFolder)
              app.gyrolet.mpvrx.ui.components.AppToast
                .makeText(
                  context,
                  context.getString(
                    R.string.toast_failed_to_delete_reason,
                    error.message ?: context.getString(R.string.generic_unknown_error),
                  ),
                  app.gyrolet.mpvrx.ui.components.AppToast.LENGTH_SHORT,
                ).show()
            } finally {
              viewModel.refresh()
            }
          }
        },
        itemType = "folder",
        itemCount = pendingDeleteFolders.size,
        itemNames = pendingDeleteFolders.map { it.name },
        itemPaths = pendingDeleteFolders.map { it.path },
      )
    }
  }
}

@Composable
private fun FolderListContent(
  folders: List<VideoFolder>,
  foldersWithNewCount: List<app.gyrolet.mpvrx.ui.browser.folderlist.FolderWithNewCount>,
  pinnedFolderPaths: Set<String>,
  recentlyPlayedFilePath: String?,
  isLoading: Boolean,
  scanStatus: String?,
  hasCompletedInitialLoad: Boolean,
  foldersWereDeleted: Boolean,
  mediaLayoutMode: MediaLayoutMode,
  tapThumbnailToSelect: Boolean,
  navigationBarHeight: androidx.compose.ui.unit.Dp,
  listState: LazyListState,
  gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
  isRefreshing: androidx.compose.runtime.MutableState<Boolean>,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<VideoFolder, String>,
  onRefresh: suspend () -> Unit,
  onFolderClick: (VideoFolder) -> Unit,
  onFolderLongClick: (VideoFolder) -> Unit,
  onTogglePin: (VideoFolder) -> Unit,
  selectedFolderBucketId: String? = null,
  audioOnly: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val swipeScope = rememberCoroutineScope()
  val swipeActions = rememberVideoSwipeActions(audioOnly = audioOnly) { swipeScope.launch { onRefresh() } }
  val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID
  val showLoading = isLoading && !hasCompletedInitialLoad
  val showEmpty = folders.isEmpty() && hasCompletedInitialLoad && !foldersWereDeleted
  val folderCardUiConfig = app.gyrolet.mpvrx.ui.browser.cards.rememberFolderCardUiConfig()

  val hasEnoughItems = folders.size > 20
  val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
    targetValue = if (hasEnoughItems) 1f else 0f,
    animationSpec =
      androidx.compose.animation.core.spring(
        dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
        stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
      ),
    label = "scrollbarAlpha",
  )

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    listState = listState,
    modifier = modifier.fillMaxSize(),
  ) {
    if (showLoading || showEmpty) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        if (showLoading) {
          LoadingState(
            icon = Icons.RoundedFilled.Folder,
            title = if (audioOnly) "Scanning for songs" else stringResource(R.string.ui_scanning_for_videos),
            message = scanStatus ?: if (audioOnly) "Please wait while we search your device" else "Please wait while we search your device",
          )
        } else if (showEmpty) {
          EmptyState(
            icon = Icons.RoundedFilled.Folder,
            title = if (audioOnly) "No song folders found" else stringResource(R.string.ui_no_video_folders_found),
            message = if (audioOnly) "Add some audio files to your device to see them here" else "Add some video files to your device to see them here",
          )
        }
      }
    } else {
      if (isGridMode) {
        GridContent(
          folders = folders,
          foldersWithNewCount = foldersWithNewCount,
          pinnedFolderPaths = pinnedFolderPaths,
          recentlyPlayedFilePath = recentlyPlayedFilePath,
          tapThumbnailToSelect = tapThumbnailToSelect,
          navigationBarHeight = navigationBarHeight,
          gridState = gridState,
          scrollbarAlpha = scrollbarAlpha,
          selectionManager = selectionManager,
          onFolderClick = onFolderClick,
          onFolderLongClick = onFolderLongClick,
          onTogglePin = onTogglePin,
          selectedFolderBucketId = selectedFolderBucketId,
          audioOnly = audioOnly,
          uiConfig = folderCardUiConfig,
        )
      } else {
        ListContent(
          onFolderSwipe = swipeActions.folder.takeUnless { selectionManager.isInSelectionMode },
          folders = folders,
          foldersWithNewCount = foldersWithNewCount,
          pinnedFolderPaths = pinnedFolderPaths,
          recentlyPlayedFilePath = recentlyPlayedFilePath,
          tapThumbnailToSelect = tapThumbnailToSelect,
          navigationBarHeight = navigationBarHeight,
          listState = listState,
          scrollbarAlpha = scrollbarAlpha,
          selectionManager = selectionManager,
          onFolderClick = onFolderClick,
          onFolderLongClick = onFolderLongClick,
          onTogglePin = onTogglePin,
          selectedFolderBucketId = selectedFolderBucketId,
          audioOnly = audioOnly,
          uiConfig = folderCardUiConfig,
        )
      }
    }
  }
}

@Composable
private fun GridContent(
  folders: List<VideoFolder>,
  foldersWithNewCount: List<app.gyrolet.mpvrx.ui.browser.folderlist.FolderWithNewCount>,
  pinnedFolderPaths: Set<String>,
  recentlyPlayedFilePath: String?,
  tapThumbnailToSelect: Boolean,
  navigationBarHeight: androidx.compose.ui.unit.Dp,
  gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
  scrollbarAlpha: Float,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<VideoFolder, String>,
  onFolderClick: (VideoFolder) -> Unit,
  onFolderLongClick: (VideoFolder) -> Unit,
  onTogglePin: (VideoFolder) -> Unit,
  selectedFolderBucketId: String? = null,
  audioOnly: Boolean = false,
  uiConfig: app.gyrolet.mpvrx.ui.browser.cards.FolderCardUiConfig? = null,
) {
  val folderCardUiConfig = uiConfig ?: app.gyrolet.mpvrx.ui.browser.cards.rememberFolderCardUiConfig()
  val newCountByBucketId =
    remember(foldersWithNewCount) {
      foldersWithNewCount.associate { it.folder.bucketId to it.newVideoCount }
    }
  val recentlyPlayedParent = remember(recentlyPlayedFilePath) { recentlyPlayedFilePath?.let(::File)?.parent }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val browserPreferences = org.koin.compose.koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    val isTablet = configuration.smallestScreenWidthDp >= 600
    val dualPaneForTablet by browserPreferences.dualPaneForTablet.collectAsState()
    val isDualPaneActive = isTablet && dualPaneForTablet
    val isDualPane = isDualPaneActive && selectedFolderBucketId != null
    val focusGutter =
      if (app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(LocalContext.current)) 20.dp else 8.dp

    val spansInfo = calculateResponsiveGridSpans(maxWidth = maxWidth, isDualPane = isDualPane)
    val computedColumns = spansInfo.spans / spansInfo.folderSpan
    val density = LocalDensity.current
    // Row height follows the tallest natural card in the row, and is recalculated
    // when folders, the column count, or display preferences change.
    val measuredRowHeights = remember(folders, computedColumns, folderCardUiConfig) {
      mutableStateMapOf<Int, Int>()
    }
    LazyVerticalGrid(
      columns = GridCells.Fixed(computedColumns),
      state = gridState,
      modifier = Modifier.fillMaxSize(),
      contentPadding =
        PaddingValues(
          start = focusGutter,
          end = focusGutter,
          top = 8.dp,
          bottom = navigationBarHeight + 8.dp,
        ),
      horizontalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
      verticalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
    ) {
      items(
        count = folders.size,
        key = { index -> folders[index].bucketId },
        contentType = { "folder_item" },
      ) { index ->
        val folder = folders[index]
        val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
        val isRecentlyPlayed = recentlyPlayedParent == folder.path
        val newCount = newCountByBucketId[folder.bucketId] ?: 0

        val isActive = isDualPaneActive && folder.bucketId == selectedFolderBucketId
        val rowIndex = index / computedColumns
        val tallestContentInRow = measuredRowHeights[rowIndex] ?: 0

        FolderCard(
          folder = folder,
          modifier = mediaItemMotion(),
          isSelected = selectionManager.isSelected(folder),
          selectionNeighbors = mediaSelectionNeighbors(index, folders.size, computedColumns) { neighbor ->
            selectionManager.isSelected(folders[neighbor])
          },
          isRecentlyPlayed = isRecentlyPlayed,
          onClick = { onFolderClick(folder) },
          onLongClick = if (archiveFolder) null else ({ onFolderLongClick(folder) }),
          onThumbClick =
            if (tapThumbnailToSelect && !archiveFolder) {
              { selectionManager.toggleFromUser(folder) }
            } else {
              { onFolderClick(folder) }
            },
          newVideoCount = newCount,
          customIcon = if (archiveFolder) Icons.RoundedFilled.FolderZip else null,
          isGridMode = true,
          gridRowMinHeight = with(density) { tallestContentInRow.toDp() },
          onGridContentHeightChanged = { contentHeight ->
            // Keep each row height stable; other cards cannot shrink or remeasure it per fling.
            if (contentHeight > (measuredRowHeights[rowIndex] ?: 0)) {
              measuredRowHeights[rowIndex] = contentHeight
            }
          },
          uiConfig = folderCardUiConfig,
          allowThumbnailGeneration = folderCardUiConfig.showFolderThumbnails,
          isPinned = folder.path in pinnedFolderPaths,
          onPinClick =
            if (!selectionManager.isInSelectionMode) {
              { onTogglePin(folder) }
            } else {
              null
            },
          isDualPane = isDualPane,
          isActive = isActive,
          isAudioOnly = audioOnly,
        )
      }
    }

    // Scrollbar with bottom padding
    if (folders.isNotEmpty() && scrollbarAlpha > 0.01f) {
      ExpressiveScrollBar(
        gridState = gridState,
        dragLabelProvider = { index ->
          fastScrollGlyph(folders.getOrNull(index)?.name)
        },
        modifier =
          Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
            .graphicsLayer { alpha = scrollbarAlpha },
      )
    }
  }
}

@Composable
private fun ListContent(
  folders: List<VideoFolder>,
  foldersWithNewCount: List<FolderWithNewCount>,
  pinnedFolderPaths: Set<String>,
  recentlyPlayedFilePath: String?,
  tapThumbnailToSelect: Boolean,
  navigationBarHeight: androidx.compose.ui.unit.Dp,
  listState: LazyListState,
  scrollbarAlpha: Float,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<VideoFolder, String>,
  onFolderClick: (VideoFolder) -> Unit,
  onFolderLongClick: (VideoFolder) -> Unit,
  onTogglePin: (VideoFolder) -> Unit,
  selectedFolderBucketId: String? = null,
  audioOnly: Boolean = false,
  onFolderSwipe: ((VideoFolder, VideoSwipeAction) -> Unit)? = null,
  uiConfig: app.gyrolet.mpvrx.ui.browser.cards.FolderCardUiConfig? = null,
) {
  val folderCardUiConfig = uiConfig ?: app.gyrolet.mpvrx.ui.browser.cards.rememberFolderCardUiConfig()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isTablet = configuration.smallestScreenWidthDp >= 600
  val browserPreferences = org.koin.compose.koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val dualPaneForTablet by browserPreferences.dualPaneForTablet.collectAsState()
  val isDualPaneActive = isTablet && dualPaneForTablet
  val focusGutter =
    if (app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(LocalContext.current)) 20.dp else 8.dp
  val newCountByBucketId =
    remember(foldersWithNewCount) {
      foldersWithNewCount.associate { it.folder.bucketId to it.newVideoCount }
    }
  val recentlyPlayedParent = remember(recentlyPlayedFilePath) { recentlyPlayedFilePath?.let(::File)?.parent }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(MediaSelectionListGap),
      contentPadding =
        PaddingValues(
          start = focusGutter,
          end = focusGutter,
          top = 4.dp,
          bottom = navigationBarHeight,
        ),
    ) {
      items(
        count = folders.size,
        key = { index -> folders[index].bucketId },
        contentType = { "folder_item" },
      ) { index ->
        val folder = folders[index]
        val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
        val isRecentlyPlayed = recentlyPlayedParent == folder.path
        val newCount = newCountByBucketId[folder.bucketId] ?: 0

        val isActive = isDualPaneActive && folder.bucketId == selectedFolderBucketId

        FolderCard(
          folder = folder,
          modifier = mediaItemMotion(),
          isSelected = selectionManager.isSelected(folder),
          selectionNeighbors = mediaSelectionNeighbors(index, folders.size, 1) { neighbor ->
            selectionManager.isSelected(folders[neighbor])
          },
          isRecentlyPlayed = isRecentlyPlayed,
          onClick = { onFolderClick(folder) },
          onLongClick = if (archiveFolder) null else ({ onFolderLongClick(folder) }),
          onThumbClick =
            if (tapThumbnailToSelect && !archiveFolder) {
              { selectionManager.toggleFromUser(folder) }
            } else {
              { onFolderClick(folder) }
            },
          newVideoCount = newCount,
          customIcon = if (archiveFolder) Icons.RoundedFilled.FolderZip else null,
          isGridMode = false,
          uiConfig = folderCardUiConfig,
          allowThumbnailGeneration = false,
          onSwipeAction = onFolderSwipe.takeUnless { archiveFolder },
          isPinned = folder.path in pinnedFolderPaths,
          onPinClick =
            if (!selectionManager.isInSelectionMode) {
              { onTogglePin(folder) }
            } else {
              null
            },
          customChipContent =
            if (folder.path in pinnedFolderPaths) {
              {
                Text(
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_pinned),
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp),
                      ).padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            } else {
              null
            },
          isDualPane = isDualPaneActive && selectedFolderBucketId != null,
          isActive = isActive,
          isAudioOnly = audioOnly,
        )
      }
    }

    // Scrollbar with bottom padding
    if (folders.isNotEmpty() && scrollbarAlpha > 0.01f) {
      ExpressiveScrollBar(
        listState = listState,
        dragLabelProvider = { index ->
          fastScrollGlyph(folders.getOrNull(index)?.name)
        },
        modifier =
          Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
            .graphicsLayer { alpha = scrollbarAlpha },
      )
    }
  }
}

/**
 * Displays search results based on the user's layout preference (grid or list)
 */
@Composable
private fun SearchResultsContent(
  searchResults: List<FileSystemItem>,
  navigationBarHeight: androidx.compose.ui.unit.Dp,
  onFolderClick: (app.gyrolet.mpvrx.domain.media.model.VideoFolder) -> Unit,
  onVideoClick: (app.gyrolet.mpvrx.domain.media.model.Video) -> Unit,
  mediaLayoutMode: app.gyrolet.mpvrx.preferences.MediaLayoutMode,
  onChanged: () -> Unit,
  audioOnly: Boolean,
) {
  val swipeActions = rememberVideoSwipeActions(audioOnly = audioOnly, onChanged = onChanged)
  val folders =
    searchResults.filterIsInstance<FileSystemItem.Folder>().map { folder ->
      app.gyrolet.mpvrx.domain.media.model.VideoFolder(
        bucketId = folder.path, // Use path as bucketId since FileSystemItem.Folder doesn't have bucketId
        name = folder.name,
        path = folder.path,
        videoCount = folder.videoCount,
        totalSize = folder.totalSize,
        totalDuration = folder.totalDuration,
        lastModified = folder.lastModified,
      )
    }
  val videos = searchResults.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }
  val swipePlaybackInfo = rememberSwipePlaybackInfo(videos)
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val musicCoverArtSize by browserPreferences.musicCoverArtSize.collectAsState()
  val audioCoverArtSizePx = with(LocalDensity.current) { musicCoverArtSize.dp.roundToPx() }
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val thumbnailGenerator by browserPreferences.thumbnailGenerator.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showCodecSupportIndicator,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
      showExtensionField,
      showDurationField,
      centerGridTitles,
      thumbnailQuality,
      thumbnailGenerator,
      swipeLeft,
      swipeRight,
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showCodecSupportIndicator = showCodecSupportIndicator,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
        showExtensionField = showExtensionField,
        showDurationField = showDurationField,
        centerGridTitles = centerGridTitles,
        thumbnailQuality = thumbnailQuality,
        thumbnailGenerator = thumbnailGenerator,
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
      )
    }

  val folderCardUiConfig = app.gyrolet.mpvrx.ui.browser.cards.rememberFolderCardUiConfig()

  val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID

  Box(modifier = Modifier.fillMaxSize()) {
    if (isGridMode) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spansInfo =
          calculateResponsiveGridSpans(
            maxWidth = maxWidth,
            isGridMode = true,
          )
        LazyVerticalGrid(
          columns = GridCells.Fixed(spansInfo.spans),
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 8.dp,
              end = 8.dp,
              top = 8.dp,
              bottom = navigationBarHeight + 8.dp,
            ),
          horizontalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
          verticalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
        ) {
          items(
            count = folders.size,
            key = { index -> folders[index].bucketId },
            contentType = { "folder_item" },
            span = { GridItemSpan(spansInfo.folderSpan) },
          ) { index ->
            val folder = folders[index]
            val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
            FolderCard(
              folder = folder,
              isSelected = false,
              isRecentlyPlayed = false,
              onClick = { onFolderClick(folder) },
              onLongClick = null,
              onThumbClick = { onFolderClick(folder) },
              newVideoCount = 0,
              isGridMode = true,
              uiConfig = folderCardUiConfig,
              customIcon = if (archiveFolder) Icons.RoundedFilled.FolderZip else null,
            )
          }

          items(
            count = videos.size,
            key = { index -> videos[index].id },
            contentType = { "video_item" },
            span = { GridItemSpan(spansInfo.videoSpan) },
          ) { index ->
            val video = videos[index]
            val archiveEntry = ZipArchiveMedia.isPlaybackUri(video.uri.toString())
            VideoCard(
              video = video,
              isWatched = swipePlaybackInfo[video.path]?.isWatched == true,
              isOldAndUnplayed = swipePlaybackInfo[video.path]?.isOldAndUnplayed == true,
              isSelected = false,
              onClick = { onVideoClick(video) },
              onLongClick = null,
              onThumbClick = { onVideoClick(video) },
              isGridMode = true,
              showSubtitleIndicator = showSubtitleIndicator,
              allowThumbnailGeneration = !archiveEntry,
              allowThumbnailLoading = !archiveEntry,
              uiConfig = videoCardUiConfig,
            )
          }
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MediaSelectionListGap),
        contentPadding =
          PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = navigationBarHeight + 8.dp,
          ),
      ) {
        items(
          count = folders.size,
          key = { index -> folders[index].bucketId },
          contentType = { "folder_item" },
        ) { index ->
          val folder = folders[index]
          val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
          FolderCard(
            folder = folder,
            isSelected = false,
            isRecentlyPlayed = false,
            onClick = { onFolderClick(folder) },
            onLongClick = null,
            onThumbClick = { onFolderClick(folder) },
            newVideoCount = 0,
            isGridMode = false,
            uiConfig = folderCardUiConfig,
            customIcon = if (archiveFolder) Icons.RoundedFilled.FolderZip else null,
            onSwipeAction = swipeActions.folder.takeUnless { archiveFolder },
          )
        }

        items(
          count = videos.size,
          key = { index -> videos[index].id },
          contentType = { "video_item" },
        ) { index ->
          val video = videos[index]
          val archiveEntry = ZipArchiveMedia.isPlaybackUri(video.uri.toString())
          VideoCard(
            video = video,
            isSelected = false,
            onClick = { onVideoClick(video) },
            onLongClick = null,
            onThumbClick = { onVideoClick(video) },
            isGridMode = false,
              onSwipeAction = swipeActions.video.takeUnless { archiveEntry },
            isWatched = swipePlaybackInfo[video.path]?.isWatched == true,
            isOldAndUnplayed = swipePlaybackInfo[video.path]?.isOldAndUnplayed == true,
            showSubtitleIndicator = showSubtitleIndicator,
            allowThumbnailGeneration = !archiveEntry,
            allowThumbnailLoading = !archiveEntry,
            uiConfig = videoCardUiConfig,
            thumbnailWidthPx = if (video.isAudio) audioCoverArtSizePx else null,
            thumbnailHeightPx = if (video.isAudio) audioCoverArtSizePx else null,
          )
        }
      }
    }
  }
}

/** Indexes the folders already on screen, so hidden (dot/.nomedia) folders stay searchable. */
private suspend fun buildSearchIndex(
  context: Context,
  folders: List<VideoFolder>,
  audioOnly: Boolean,
) {
  val videosByFolder =
    folders.associate { folder ->
      folder.bucketId to
        app.gyrolet.mpvrx.repository.MediaFileRepository
          .getVideosInFolder(
            context,
            folder.bucketId,
            includeAudioOverride = if (audioOnly) true else null,
          )
    }
  MediaSearchEngine.buildIndex(folders, videosByFolder)
}

private suspend fun searchFoldersAndVideos(
  context: Context,
  query: String,
): List<FileSystemItem> {
  val results = mutableListOf<FileSystemItem>()
  try {
    Log.d("FolderListScreen", "Searching for: $query")

    // Get all search matches from the optimized engine
    val matches = MediaSearchEngine.search(query, limit = 50)

    for (item in matches) {
      when (item) {
        // Kotlin smart-casts 'item' to your domain model VideoFolder
        is VideoFolder -> {
          results.add(
            FileSystemItem.Folder(
              name = item.name,
              path = item.path,
              lastModified = item.lastModified,
              videoCount = item.videoCount,
              totalSize = item.totalSize,
              totalDuration = item.totalDuration,
            ),
          )
        }
        // Kotlin smart-casts 'item' to your domain model Video
        is Video -> {
          results.add(
            FileSystemItem.VideoFile(
              name = item.displayName,
              path = item.path,
              lastModified = item.dateModified,
              video = item,
            ),
          )
        }
      }
    }

    Log.d("FolderListScreen", "Found ${results.size} results for: $query")
  } catch (e: Exception) {
    Log.e("FolderListScreen", "Error searching folders and videos", e)
  }
  return results
}
