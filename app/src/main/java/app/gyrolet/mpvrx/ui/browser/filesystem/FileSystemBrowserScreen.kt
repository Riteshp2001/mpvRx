/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.filesystem

import android.content.Context
import android.content.Intent
import android.util.Log
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.browser.components.rememberVideoSwipeActions
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserBottomBar
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.toPlaylistCandidates
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FileOperationProgressDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FileSystemSortDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.RenameDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoCompressorOverlay
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.states.PermissionDeniedState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.OpenDocumentTreeContract
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Root File System Browser screen - shows storage volumes
 */
@Serializable
object FileSystemBrowserRootScreen : app.gyrolet.mpvrx.presentation.Screen {
  @OptIn(ExperimentalPermissionsApi::class)
  @Composable
  override fun Content() {
    FileSystemBrowserScreen(path = null)
  }
}

/**
 * File System Directory screen - shows contents of a specific directory
 */
@Serializable
data class FileSystemDirectoryScreen(
  val path: String,
) : app.gyrolet.mpvrx.presentation.Screen {
  @OptIn(ExperimentalPermissionsApi::class)
  @Composable
  override fun Content() {
    FileSystemBrowserScreen(path = path)
  }
}

/**
 * File System Browser screen - browses directories and shows both folders and videos
 * @param path The directory path to browse, or null for storage roots
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileSystemBrowserScreen(path: String? = null) {
  val context = LocalContext.current
  val backstack = LocalBackStack.current
  val coroutineScope = rememberCoroutineScope()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<app.gyrolet.mpvrx.preferences.AppearancePreferences>()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()
  val playerPreferences = koinInject<app.gyrolet.mpvrx.preferences.PlayerPreferences>()

  // ViewModel - use path parameter if provided, otherwise show roots
  val viewModel: FileSystemBrowserViewModel =
    viewModel(
      key = "FileSystemBrowser_${path ?: "root"}",
      factory =
        FileSystemBrowserViewModel.factory(
          context.applicationContext as android.app.Application,
          path,
        ),
    )

  // State collection
  val currentPath by viewModel.currentPath.collectAsState()
  val items by viewModel.items.collectAsState()
  val videoFilesWithPlayback by viewModel.videoFilesWithPlayback.collectAsState()
  val newVideoIds by viewModel.newVideoIds.collectAsState()
  val watchedVideoIds by viewModel.watchedVideoIds.collectAsState()
  val isLoading by viewModel.isLoading.collectAsState()
  val error by viewModel.error.collectAsState()
  val isAtRoot by viewModel.isAtRoot.collectAsState()
  val breadcrumbs by viewModel.breadcrumbs.collectAsState()
  val playlistMode by playerPreferences.playlistMode.collectAsState()
  val itemsWereDeletedOrMoved by viewModel.itemsWereDeletedOrMoved.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()

  // Use standalone local states instead of CompositionLocal to avoid scroll issues with predictive back gesture
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val listState = remember { LazyListState() }
  val gridState = rememberLazyGridState()

  // UI state
  val isRefreshing = remember { mutableStateOf(false) }
  val showLinkDialog = remember { mutableStateOf(false) }
  val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
  var deleteDialogOpen by rememberSaveable { mutableStateOf(false) }
  val renameDialogOpen = rememberSaveable { mutableStateOf(false) }
  val addToPlaylistDialogOpen = rememberSaveable { mutableStateOf(false) }
  val compressorDialogOpen = rememberSaveable { mutableStateOf(false) }

  // FAB visibility for scroll-based hiding
  val isFabVisible = remember { mutableStateOf(true) }
  val isFabExpanded = remember { mutableStateOf(false) }

  // Search state
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var isSearching by rememberSaveable { mutableStateOf(false) }
  var searchResults by remember { mutableStateOf<List<FileSystemItem>>(emptyList()) }
  var isSearchLoading by remember { mutableStateOf(false) }
  val keyboardController = LocalSoftwareKeyboardController.current
  val focusRequester = remember { FocusRequester() }

  // Get navigation bar height from MainScreen
  val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

  // Copy/Move state
  val folderPickerOpen = rememberSaveable { mutableStateOf(false) }
  val operationType = remember { mutableStateOf<CopyPasteOps.OperationType?>(null) }
  val progressDialogOpen = rememberSaveable { mutableStateOf(false) }
  val operationProgress by CopyPasteOps.operationProgress.collectAsState()

  // Bottom bar visibility state
  var showFloatingBottomBar by remember { mutableStateOf(false) }
  var showBottomNavigation by remember { mutableStateOf(true) }

  // Animation duration for responsive slide animations
  val animationDuration = 200

  val videos = items.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }

  val selectionManager =
    rememberSelectionManager(
      items = items,
      getId = ::fileSystemSelectionId,
      onDeleteItems = { selectedItems, _ ->
        val selectedFolders = selectedItems.filterIsInstance<FileSystemItem.Folder>()
        val selectedVideos = selectedItems.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }
        var deleted = 0
        var failed = 0

        if (selectedFolders.isNotEmpty()) {
          val (folderDeleted, folderFailed) = viewModel.deleteFolders(selectedFolders)
          deleted += folderDeleted
          failed += folderFailed
        }

        if (selectedVideos.isNotEmpty()) {
          val (videoDeleted, videoFailed) = viewModel.deleteVideos(selectedVideos)
          deleted += videoDeleted
          failed += videoFailed
        }

        deleted to failed
      },
      onRenameItem = { selectedItem, newName ->
        when (selectedItem) {
          is FileSystemItem.Folder ->
            if (viewModel.renameFolder(selectedItem, newName)) {
              Result.success(Unit)
            } else {
              Result.failure(IllegalStateException("Rename failed"))
            }

          is FileSystemItem.VideoFile -> viewModel.renameVideo(selectedItem.video, newName)
        }
      },
      onOperationComplete = { viewModel.refresh() },
    )

  val selectedItems = selectionManager.getSelectedItems()
  val selectedFolders = selectedItems.filterIsInstance<FileSystemItem.Folder>()
  val selectedVideos = selectedItems.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }
  val isInSelectionMode = selectionManager.isInSelectionMode
  val selectedCount = selectionManager.selectedCount
  val totalCount = items.size
  val onlyVideosSelected = selectedVideos.isNotEmpty() && selectedFolders.isEmpty()

  suspend fun selectedPlayableVideos(): List<app.gyrolet.mpvrx.domain.media.model.Video> {
    val videosFromFolders =
      selectedFolders.flatMap { folder ->
        collectVideosRecursively(context, folder.path)
      }
    return (selectedVideos + videosFromFolders).distinctBy { it.path }
  }

  // Update bottom bar visibility with optimized animation sequencing
  LaunchedEffect(isInSelectionMode) {
    if (isInSelectionMode) {
      // Entering selection mode: Hide bottom navigation immediately, then show floating bar
      showBottomNavigation = false
      showFloatingBottomBar = true
    } else {
      // Exiting selection mode: Hide floating bar and show bottom navigation immediately for better responsiveness
      showFloatingBottomBar = false
      showBottomNavigation = true
    }
  }

  // Permissions
  val permissionState =
    PermissionUtils.handleStoragePermission(
      onPermissionGranted = { viewModel.refresh() },
    )

  var isPermissionSetupCompleted by androidx.compose.runtime.saveable.rememberSaveable {
    androidx.compose.runtime.mutableStateOf(
      permissionState.status == com.google.accompanist.permissions.PermissionStatus.Granted ||
        browserPreferences.onboardingCompleted.get(),
    )
  }

  app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect(isInSelectionMode)
  val isActiveRoot = isAtRoot && app.gyrolet.mpvrx.ui.utils.LocalNavigationPageActive.current
  androidx.lifecycle.compose.LifecycleResumeEffect(isActiveRoot, showBottomNavigation, isPermissionSetupCompleted) {
    if (isActiveRoot) {
      try {
        val mainScreenObj = app.gyrolet.mpvrx.ui.browser.MainScreen

        // Update all MainScreen states in one call to reduce overhead
        mainScreenObj.updateBottomBarVisibility(showBottomNavigation)
        mainScreenObj.updatePermissionState(
          isDenied = !isPermissionSetupCompleted,
        )
      } catch (e: Exception) {
        Log.e("FileSystemBrowserScreen", "Failed to update MainScreen state", e)
      }
    }
    onPauseOrDispose { }
  }

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

  // ZIP picker
  val zipPicker =
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
        coroutineScope.launch {
          val resolvedPath = ZipArchiveMedia.resolveZipPath(context, it)
          if (resolvedPath != null && File(resolvedPath).canRead()) {
            val archiveFile = File(resolvedPath)
            val bucketId = ZipArchiveMedia.browserPath(archiveFile.absolutePath)
            backstack.navigateTo(FileSystemDirectoryScreen(bucketId))
          } else {
            android.widget.Toast.makeText(
              context,
              context.getString(app.gyrolet.mpvrx.R.string.ui_cannot_open_zip),
              android.widget.Toast.LENGTH_SHORT,
            ).show()
          }
        }
      }
    }

  // Tree picker for Play Store-safe copy/move destinations
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
        val videosToTransfer = selectedPlayableVideos()
        if (videosToTransfer.isNotEmpty()) {
          when (operationType.value) {
            is CopyPasteOps.OperationType.Copy -> CopyPasteOps.copyFilesToTreeUri(context, videosToTransfer, uri)
            is CopyPasteOps.OperationType.Move -> CopyPasteOps.moveFilesToTreeUri(context, videosToTransfer, uri)
            else -> {}
          }
        }
      }
    }

  // Search functionality - recursive search through all subfolders
  LaunchedEffect(isSearching) {
    if (isSearching) {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  val searchRoots = if (isAtRoot && breadcrumbs.isEmpty()) {
    items.filterIsInstance<FileSystemItem.Folder>().map { it.path }
  } else {
    listOf(currentPath)
  }
  LaunchedEffect(searchQuery, isSearching, searchRoots) {
    if (isSearching && searchQuery.isNotBlank()) {
      searchResults = emptyList()
      isSearchLoading = true
      try {
        delay(250)
        val results = mutableListOf<FileSystemItem>()
        for (root in searchRoots) {
          results += searchRecursively(context, root, searchQuery, onSnapshot = { partial ->
            withContext(Dispatchers.Main.immediate) {
              coroutineContext.ensureActive()
              searchResults = (results + partial).distinctBy { it.path }
            }
          })
        }
        coroutineContext.ensureActive()
        searchResults = results.distinctBy { it.path }
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (e: Exception) {
        Log.e("FileSystemBrowserScreen", "Error during search", e)
      } finally {
        if (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) isSearchLoading = false
      }
    } else {
      searchResults = emptyList()
      isSearchLoading = false
    }
  }

  // Optimized predictive back handler for immediate response
  val shouldHandleBack = isInSelectionMode || isSearching || isFabExpanded.value
  BackHandler(enabled = shouldHandleBack) {
    when {
      isFabExpanded.value -> isFabExpanded.value = false
      isInSelectionMode -> selectionManager.clear()
      isSearching -> {
        isSearching = false
        searchQuery = ""
      }
    }
  }

  // Track scroll for FAB visibility
  app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper.trackScrollForFabVisibility(
    listState = listState,
    gridState = if (mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID) gridState else null,
    isFabVisible = isFabVisible,
    expanded = isFabExpanded.value,
    onExpandedChange = { isFabExpanded.value = it },
  )

  // Main content
  Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (isSearching) {
          // Search mode - show search bar instead of top bar
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = {
              Text(
                if (isAtRoot) {
                  "Search in all storage volumes..."
                } else {
                  "Search in ${breadcrumbs.lastOrNull()?.name ?: "folder"}..."
                },
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
                  isSearching = false
                  searchQuery = ""
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
            title =
              if (isAtRoot) {
                stringResource(app.gyrolet.mpvrx.R.string.app_name)
              } else {
                breadcrumbs.lastOrNull()?.name ?: "Tree View"
              },
            isInSelectionMode = isInSelectionMode,
            selectedCount = selectedCount,
            totalCount = totalCount,
            onBackClick =
              if (isAtRoot) {
                null
              } else {
                { backstack.popSafely() }
              },
            onCancelSelection = { selectionManager.clear() },
            onSortClick = { sortDialogOpen.value = true },
            onSearchClick = {
              isSearching = !isSearching
            },
            onSettingsClick = {
              backstack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
            },
            isSingleSelection = selectionManager.isSingleSelection,
            onInfoClick =
              if (selectedVideos.size == 1 && selectedFolders.isEmpty()) {
                {
                  val video = selectedVideos.firstOrNull()
                  if (video != null) {
                    val intent = Intent(context, app.gyrolet.mpvrx.ui.mediainfo.MediaInfoActivity::class.java)
                    intent.action = Intent.ACTION_VIEW
                    intent.data = video.uri
                    context.startActivity(intent)
                    selectionManager.clear()
                  }
                }
              } else {
                null
              },
            onShareClick = {
              coroutineScope.launch {
                val videosToShare = selectedPlayableVideos()
                if (videosToShare.isNotEmpty()) {
                  MediaUtils.shareVideos(context, videosToShare)
                }
              }
            },
            onPlayClick = {
              coroutineScope.launch {
                val videosToPlay = selectedPlayableVideos()
                if (videosToPlay.isNotEmpty()) {
                  playVideosAsPlaylist(context, videosToPlay)
                }
                selectionManager.clear()
              }
            },
            onDeleteClick = { deleteDialogOpen = true },
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
          )
        }
      },
      floatingActionButton = {
        val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
        val miniPlayerClearance = app.gyrolet.mpvrx.ui.browser.NavigationBarState.miniPlayerClearance
        if (isAtRoot) {
          val isFabShouldBeVisible =
            showQuickPlayFab &&
              !isInSelectionMode &&
              isFabVisible.value &&
              !app.gyrolet.mpvrx.ui.browser.MainScreen
                .getPermissionDeniedState()

          FloatingActionButtonMenu(
            modifier =
              Modifier.padding(
                bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp) + miniPlayerClearance,
              ),
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
                    Modifier
                      .animateFloatingActionButton(
                        visible = isFabShouldBeVisible,
                        alignment = Alignment.BottomEnd,
                      ),
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
                  filePicker.launch(arrayOf("video/*"))
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
                  zipPicker.launch(
                    arrayOf(
                      "application/zip",
                      "application/x-zip-compressed",
                      "application/x-zip",
                      "application/octet-stream",
                    ),
                  )
                },
                icon = { Icon(Icons.RoundedFilled.FolderZip, contentDescription = null) },
                text = {
                  Text(
                    text =
                      androidx.compose.ui.res
                        .stringResource(app.gyrolet.mpvrx.R.string.ui_open_zip_folder),
                  )
                },
              )

              FloatingActionButtonMenuItem(
                onClick = {
                  isFabExpanded.value = false
                  coroutineScope.launch {
                    val recentlyPlayedVideos =
                      app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps.getRecentlyPlayed(
                        limit = 1,
                      )
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
        }
      }
  ) { padding ->
      Box(modifier = Modifier.padding(padding)) {
        if (isPermissionSetupCompleted && permissionState.status == PermissionStatus.Granted) {
            if (isSearching) {
              // Show search results
              FileSystemSearchContent(
                onChanged = { viewModel.refresh() },
                listState = listState, // Use the main listState for FAB tracking
                gridState = gridState,
                searchQuery = searchQuery,
                searchResults = searchResults,
                isLoading = isSearchLoading,
                videoFilesWithPlayback = videoFilesWithPlayback,
                newVideoIds = newVideoIds,
                showSubtitleIndicator = showSubtitleIndicator,
                isAtRoot = isAtRoot,
                navigationBarHeight = navigationBarHeight,
                isFabVisible = isFabVisible, // Pass FAB visibility state
                onVideoClick = { video ->
                  MediaUtils.playFile(video, context, "search")
                },
                onFolderClick = { folder ->
                  backstack.navigateTo(FileSystemDirectoryScreen(folder.path))
                  isSearching = false
                  searchQuery = ""
                },
                modifier = Modifier,
              )
            } else {
              FileSystemBrowserContent(
                listState = listState,
                gridState = gridState,
                items = items,
                videoFilesWithPlayback = videoFilesWithPlayback,
                newVideoIds = newVideoIds,
                watchedVideoIds = watchedVideoIds,
                isLoading = isLoading && items.isEmpty(),
                isRefreshing = isRefreshing,
                error = error,
                isAtRoot = isAtRoot,
                breadcrumbs = breadcrumbs,
                playlistMode = playlistMode,
                itemsWereDeletedOrMoved = itemsWereDeletedOrMoved,
                showSubtitleIndicator = showSubtitleIndicator,
                navigationBarHeight = navigationBarHeight,
                onRefresh = { viewModel.refresh() },
                onFolderClick = { folder ->
                  if (isInSelectionMode && !ZipArchiveMedia.isBrowserPath(folder.path)) {
                    selectionManager.toggleFromUser(folder)
                  } else if (!isInSelectionMode) {
                    backstack.navigateTo(FileSystemDirectoryScreen(folder.path))
                  }
                },
                onFolderLongClick = { folder ->
                  if (!ZipArchiveMedia.isBrowserPath(folder.path)) selectionManager.handleLongClick(folder)
                },
                onVideoClick = { videoFile ->
                  val video = videoFile.video
                  val archiveEntry = ZipArchiveMedia.isPlaybackUri(video.uri.toString())
                  if (isInSelectionMode && !archiveEntry) {
                    selectionManager.toggleFromUser(videoFile)
                  } else if (!isInSelectionMode) {
                    if (playlistMode || archiveEntry) {
                      val allVideos = videos
                      val startIndex = allVideos.indexOfFirst { it.id == video.id }
                      if (startIndex >= 0) {
                        if (allVideos.size == 1) {
                          MediaUtils.playFile(video, context)
                        } else {
                          MediaUtils.playFiles(allVideos, context, startIndex)
                        }
                      } else {
                        MediaUtils.playFile(video, context)
                      }
                    } else {
                      MediaUtils.playFile(video, context)
                    }
                  }
                },
                onVideoLongClick = { videoFile ->
                  if (!ZipArchiveMedia.isPlaybackUri(videoFile.video.uri.toString())) {
                    selectionManager.handleLongClick(videoFile)
                  }
                },
                onBreadcrumbClick = { component ->
                  // Navigate to the breadcrumb by popping until we reach it
                  // or pushing if it's a new path
                  backstack.navigateTo(FileSystemDirectoryScreen(component.fullPath))
                },
                selectionManager = selectionManager,
                modifier = Modifier,
                isInSelectionMode = isInSelectionMode,
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
    // Play Store gating is intentionally bypassed here.
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
        onDownscaleClick = { compressorDialogOpen.value = true },
        onRenameClick = { renameDialogOpen.value = true },
        onDeleteClick = { deleteDialogOpen = true },
        onAddToPlaylistClick = { addToPlaylistDialogOpen.value = true },
        showDownscale =
          selectedVideos.isNotEmpty() && selectedVideos.none { it.isAudio } && selectedFolders.isEmpty(),
        showRename = selectionManager.isSingleSelection,
        showAddToPlaylist = onlyVideosSelected,
        modifier = Modifier.padding(bottom = 0.dp),
      )
    }

    // Dialogs
    PlayLinkSheet(
      isOpen = showLinkDialog.value,
      onDismiss = { showLinkDialog.value = false },
      onPlayLink = { url -> MediaUtils.playFile(url, context, "play_link") },
    )

    FileSystemSortDialog(
      isOpen = sortDialogOpen.value,
      onDismiss = { sortDialogOpen.value = false },
      isAtRoot = isAtRoot,
    )

    if (deleteDialogOpen) {
      DeleteConfirmationDialog(
        isOpen = true,
        onDismiss = { deleteDialogOpen = false },
        onConfirm = {
          deleteDialogOpen = false
          selectionManager.deleteSelected()
        },
        itemType =
          when {
            selectedFolders.isNotEmpty() && selectedVideos.isNotEmpty() -> "item"
            selectedFolders.isNotEmpty() -> "folder"
            else -> "video"
          },
        itemCount = selectedCount,
        itemNames = selectedItems.map { it.name },
      )
    }

    // Rename Dialog
    if (renameDialogOpen.value) {
      val selectedItem = selectedItems.firstOrNull()
      when (selectedItem) {
        is FileSystemItem.Folder -> {
          RenameDialog(
            isOpen = true,
            onDismiss = { renameDialogOpen.value = false },
            onConfirm = { newName ->
              renameDialogOpen.value = false
              selectionManager.renameSelected(newName)
            },
            currentName = selectedItem.name,
            itemType = "folder",
          )
        }

        is FileSystemItem.VideoFile -> {
          val video = selectedItem.video
          val baseName = video.displayName.substringBeforeLast('.')
          val extension = "." + video.displayName.substringAfterLast('.', "")
          RenameDialog(
            isOpen = true,
            onDismiss = { renameDialogOpen.value = false },
            onConfirm = { newName ->
              renameDialogOpen.value = false
              selectionManager.renameSelected(newName)
            },
            currentName = baseName,
            itemType = "file",
            extension = if (extension != ".") extension else null,
          )
        }

        null -> Unit
      }
    }

    // Video Compressor Overlay (for file system browser)
    if (compressorDialogOpen.value) {
      if (selectedVideos.isNotEmpty() && selectedVideos.none { it.isAudio }) {
        VideoCompressorOverlay(
          isOpen = true,
          videos = selectedVideos,
          onDismiss = {
            compressorDialogOpen.value = false
            selectionManager.clear()
            viewModel.refresh()
          },
        )
      } else {
        LaunchedEffect(Unit) {
          compressorDialogOpen.value = false
        }
      }
    }

    // Folder Picker Dialog
    FolderPickerDialog(
      isOpen = folderPickerOpen.value,
      currentPath = currentPath,
      onDismiss = { folderPickerOpen.value = false },
      onFolderSelected = { destinationPath ->
        folderPickerOpen.value = false
        val op = operationType.value
        if (op != null) {
          coroutineScope.launch {
            when (op) {
              is CopyPasteOps.OperationType.Move -> {
                val needFallback = mutableListOf<FileSystemItem.Folder>()
                for (folder in selectedFolders) {
                  val dst = File(destinationPath, folder.name)
                  if (!File(folder.path).renameTo(dst)) needFallback.add(folder)
                }

                if (selectedVideos.isNotEmpty()) {
                  progressDialogOpen.value = true
                  CopyPasteOps.moveFiles(context, selectedVideos, destinationPath)
                }

                if (needFallback.isNotEmpty()) {
                  progressDialogOpen.value = true
                  for (folder in needFallback) {
                    val videos = collectVideosRecursively(context, folder.path)
                    if (videos.isNotEmpty()) {
                      val subDest = File(destinationPath, folder.name).also { it.mkdirs() }.absolutePath
                      CopyPasteOps.moveFiles(context, videos, subDest)
                    }
                  }
                }

                if (selectedVideos.isEmpty() && needFallback.isEmpty()) {
                  viewModel.setItemsWereDeletedOrMoved()
                  selectionManager.clear()
                  viewModel.refresh()
                }
              }

              is CopyPasteOps.OperationType.Copy -> {
                if (selectedVideos.isNotEmpty()) {
                  progressDialogOpen.value = true
                  CopyPasteOps.copyFiles(context, selectedVideos, destinationPath)
                }

                if (selectedFolders.isNotEmpty()) {
                  progressDialogOpen.value = true
                  for (folder in selectedFolders) {
                    val videos = collectVideosRecursively(context, folder.path)
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

    // File Operation Progress Dialog
    if (operationType.value != null) {
      FileOperationProgressDialog(
        isOpen = progressDialogOpen.value,
        operationType = operationType.value!!,
        progress = operationProgress,
        onCancel = {
          CopyPasteOps.cancelOperation()
        },
        onDismiss = {
          progressDialogOpen.value = false
          // Set flag if move operation was successful
          if (operationType.value is CopyPasteOps.OperationType.Move &&
            operationProgress.isComplete &&
            operationProgress.error == null
          ) {
            viewModel.setItemsWereDeletedOrMoved()
          }
          operationType.value = null
          selectionManager.clear()
          viewModel.refresh()
        },
      )
    }

    // Add to Playlist Dialog
    AddToPlaylistDialog(
      isOpen = addToPlaylistDialogOpen.value,
      candidates = selectedVideos.toPlaylistCandidates(),
      onDismiss = { addToPlaylistDialogOpen.value = false },
      onSuccess = {
        selectionManager.clear()
        viewModel.refresh()
      },
    )
  }
}

/**
 * Recursively searches for files matching the query in a directory and its subdirectories
 */
suspend fun searchRecursively(
  context: Context,
  directoryPath: String,
  query: String,
  onSnapshot: (suspend (List<FileSystemItem>) -> Unit)? = null,
): List<FileSystemItem> = withContext(Dispatchers.IO) {
  coroutineContext.ensureActive()
  val results = linkedMapOf<String, FileSystemItem>()
  val publisher = app.gyrolet.mpvrx.utils.media.ProgressiveResultsPublisher(onSnapshot) { results.values.toList() }
  suspend fun publishMatches(items: List<FileSystemItem>) {
    for (item in items) {
      if (item.name.contains(query, ignoreCase = true)) {
        results[item.path] = item
        publisher.publishIfNeeded()
      }
    }
  }

  try {
    Log.d("FileSystemBrowserScreen", "Scanning directory: $directoryPath for query: $query")
    // Scan the current directory
    val items =
      app.gyrolet.mpvrx.repository.MediaFileRepository
        .scanDirectory(context, directoryPath, showAllFileTypes = false,
          onSnapshot = if (onSnapshot == null) null else ::publishMatches)
        .getOrThrow()

    Log.d("FileSystemBrowserScreen", "Found ${items.size} items in $directoryPath")

    val completed = items.filter { it.name.contains(query, ignoreCase = true) }.toMutableList()
    publishMatches(items)
    publisher.publishIfNeeded(force = true)
    for (item in items.filterIsInstance<FileSystemItem.Folder>()) {
      coroutineContext.ensureActive()
      val subResults = searchRecursively(context, item.path, query,
        onSnapshot = if (onSnapshot == null) null else ::publishMatches)
      completed += subResults
      publishMatches(subResults)
    }
    results.clear()
    completed.forEach { results[it.path] = it }
    publisher.publishIfNeeded(force = true)

    Log.d("FileSystemBrowserScreen", "Returning ${results.size} results from $directoryPath")
  } catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
  } catch (e: Exception) {
    Log.e("FileSystemBrowserScreen", "Error searching directory $directoryPath", e)
  }

  results.values.toList()
}

private fun fileSystemSelectionId(item: FileSystemItem): String =
  when (item) {
    is FileSystemItem.Folder -> "folder:${item.path}"
    is FileSystemItem.VideoFile -> "video:${item.path}"
  }

/**
 * Recursively collects all videos from a folder and its subfolders
 */
private suspend fun collectVideosRecursively(
  context: Context,
  folderPath: String,
): List<app.gyrolet.mpvrx.domain.media.model.Video> {
  val videos = mutableListOf<app.gyrolet.mpvrx.domain.media.model.Video>()

  try {
    // Scan the current directory using MediaFileRepository
    val items =
      app.gyrolet.mpvrx.repository.MediaFileRepository
        .scanDirectory(context, folderPath, showAllFileTypes = false)
        .getOrNull() ?: emptyList()

    // Add videos from current folder
    items.filterIsInstance<FileSystemItem.VideoFile>().forEach { videoFile ->
      videos.add(videoFile.video)
    }

    // Recursively scan subfolders
    items.filterIsInstance<FileSystemItem.Folder>().forEach { folder ->
      val subVideos = collectVideosRecursively(context, folder.path)
      videos.addAll(subVideos)
    }
  } catch (e: Exception) {
    Log.e("FileSystemBrowserScreen", "Error collecting videos from $folderPath", e)
  }

  return videos
}

/**
 * Plays a list of videos as a playlist
 */
private fun playVideosAsPlaylist(
  context: Context,
  videos: List<app.gyrolet.mpvrx.domain.media.model.Video>,
) {
  if (videos.isEmpty()) return

  if (videos.size == 1) {
    // Single video - play normally
    MediaUtils.playFile(videos.first(), context)
  } else {
    MediaUtils.playFiles(videos, context)
  }
}

@Composable
private fun FileSystemBrowserContent(
  listState: LazyListState,
  gridState: LazyGridState,
  items: List<FileSystemItem>,
  videoFilesWithPlayback: Map<Long, Float>,
  newVideoIds: Set<Long>,
  watchedVideoIds: Set<Long>,
  isLoading: Boolean,
  isRefreshing: androidx.compose.runtime.MutableState<Boolean>,
  error: String?,
  isAtRoot: Boolean,
  breadcrumbs: List<app.gyrolet.mpvrx.domain.browser.PathComponent>,
  playlistMode: Boolean,
  itemsWereDeletedOrMoved: Boolean,
  showSubtitleIndicator: Boolean,
  navigationBarHeight: Dp,
  onRefresh: suspend () -> Unit,
  onFolderClick: (FileSystemItem.Folder) -> Unit,
  onFolderLongClick: (FileSystemItem.Folder) -> Unit,
  onVideoClick: (FileSystemItem.VideoFile) -> Unit,
  onVideoLongClick: (FileSystemItem.VideoFile) -> Unit,
  onBreadcrumbClick: (app.gyrolet.mpvrx.domain.browser.PathComponent) -> Unit,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<FileSystemItem, String>,
  modifier: Modifier = Modifier,
  isInSelectionMode: Boolean = false,
) {
  val swipeScope = rememberCoroutineScope()
  val swipeActions = rememberVideoSwipeActions { swipeScope.launch { onRefresh() } }
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val thumbnailRepository = koinInject<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>()
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
      showExtensionField,
      showDurationField,
      centerGridTitles,
      showCodecSupportIndicator,
      thumbnailQuality,
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
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
      )
    }

  // Calculate thumbnail dimensions for list mode
  val thumbWidthDp = 160.dp
  val density = androidx.compose.ui.platform.LocalDensity.current
  val aspect = if (mediaLayoutMode == MediaLayoutMode.GRID) 16f / 10f else 16f / 9f
  val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
  val thumbHeightPx = ((thumbWidthPx.toFloat() / aspect).toInt())

  val folders = items.filterIsInstance<FileSystemItem.Folder>()
  val videoFiles = items.filterIsInstance<FileSystemItem.VideoFile>()
  val videos = videoFiles.map { it.video }

  // Create a unique folderId based on the current directories
  val folderId =
    remember(folders, isAtRoot, breadcrumbs) {
      if (isAtRoot && breadcrumbs.isEmpty()) {
        "filesystem_root"
      } else {
        breadcrumbs.lastOrNull()?.fullPath ?: "filesystem_${breadcrumbs.size}"
      }
    }

  when {
    isLoading && items.isEmpty() -> {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        // Account for bottom navigation bar
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    error != null && items.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(R.string.ui_error_loading_directory),
          message = error,
        )
      }
    }

    items.isEmpty() && itemsWereDeletedOrMoved && !isAtRoot -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.FolderOpen,
          title = stringResource(R.string.ui_empty_folder),
          message = "This folder contains no videos or subfolders",
        )
      }
    }

    else -> {
      // Unified thumbnail generation - starts with initial batch and continues as needed
      // This avoids the overhead of multiple conflicting LaunchedEffect calls
      LaunchedEffect(folderId, showVideoThumbnails, thumbWidthPx, thumbHeightPx, videos.size) {
        if (showVideoThumbnails && videos.isNotEmpty()) {
          // Start with all videos - the ThumbnailRepository will handle batching internally
          // This avoids redundant job restarts when scrolling
          thumbnailRepository.startFolderThumbnailGeneration(
            folderId = folderId,
            videos = videos,
            widthPx = thumbWidthPx,
            heightPx = thumbHeightPx,
          )
        }
      }

      // Only show scrollbar if list has more than 20 items
      val hasEnoughItems = items.size > 20

      // Animate scrollbar alpha
      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hasEnoughItems) 1f else 0f,
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
            stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
          ),
        label = "scrollbarAlpha",
      )

      val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID

      val folderItems = remember(items) { items.filterIsInstance<FileSystemItem.Folder>() }
      val videoItems = remember(items) { items.filterIsInstance<FileSystemItem.VideoFile>() }

      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        listState = listState,
        modifier = modifier.fillMaxSize(),
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
        ) {
          if (isGridMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val spansInfo =
                calculateResponsiveGridSpans(
                  maxWidth = maxWidth,
                  isGridMode = true,
                )

              LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(spansInfo.spans),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = navigationBarHeight,
                  ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                // Breadcrumb navigation (if not at root)
                if (!isAtRoot && breadcrumbs.isNotEmpty()) {
                  item(span = { GridItemSpan(maxLineSpan) }) {
                    app.gyrolet.mpvrx.ui.browser.filesystem.BreadcrumbNavigation(
                      breadcrumbs = breadcrumbs,
                      onBreadcrumbClick = onBreadcrumbClick,
                    )
                  }
                }

                // Folders first
                items(
                  items = folderItems,
                  key = { it.path },
                  contentType = { "folder_item" },
                  span = { GridItemSpan(spansInfo.folderSpan) },
                ) { folder ->
                  val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
                  val folderModel =
                    app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                      bucketId = folder.path,
                      name = folder.name,
                      path = ZipArchiveMedia.displayPath(folder.path) ?: folder.path,
                      videoCount = folder.videoCount,
                      totalSize = folder.totalSize,
                      totalDuration = folder.totalDuration,
                      lastModified = folder.lastModified / 1000,
                    )

                  FolderCard(
                    folder = folderModel,
                    isSelected = selectionManager.isSelected(folder),
                    isRecentlyPlayed = false,
                    onClick = { onFolderClick(folder) },
                    onLongClick = if (archiveFolder) null else ({ onFolderLongClick(folder) }),
                    onThumbClick =
                      if (tapThumbnailToSelect && !archiveFolder) {
                        { selectionManager.toggleFromUser(folder) }
                      } else {
                        { onFolderClick(folder) }
                      },
                    customIcon = if (ZipArchiveMedia.isArchiveRoot(folder.path)) Icons.RoundedFilled.FolderZip else null,
                    newVideoCount = folder.newCount,
                    isGridMode = true,
                  )
                }

                // Videos second
                items(
                  items = videoItems,
                  key = { "${it.video.id}_${it.video.path}" },
                  contentType = { "video_item" },
                  span = { GridItemSpan(spansInfo.videoSpan) },
                ) { videoFile ->
                  val archiveEntry = ZipArchiveMedia.isPlaybackUri(videoFile.video.uri.toString())
                  VideoCard(
                      video = videoFile.video,
                      progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                      isRecentlyPlayed = false,
                      isSelected = selectionManager.isSelected(videoFile),
                      onClick = { onVideoClick(videoFile) },
                      onLongClick = if (archiveEntry) null else ({ onVideoLongClick(videoFile) }),
                      onThumbClick =
                        if (tapThumbnailToSelect && !archiveEntry) {
                          { selectionManager.toggleFromUser(videoFile) }
                        } else {
                          { onVideoClick(videoFile) }
                        },
                      isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                      isWatched = watchedVideoIds.contains(videoFile.video.id),
                      isGridMode = true,
                      showSubtitleIndicator = showSubtitleIndicator,
                      overrideShowSizeChip = null,
                      overrideShowResolutionChip = null,
                      useFolderNameStyle = false,
                      allowThumbnailGeneration = !archiveEntry,
                      allowThumbnailLoading = !archiveEntry,
                      uiConfig = videoCardUiConfig,
                  )
                }
              }

              if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                val scrollbarLabels =
                  remember(folderItems, videoItems, isAtRoot, breadcrumbs) {
                    buildList<String?> {
                      if (!isAtRoot && breadcrumbs.isNotEmpty()) add(null)
                      folderItems.forEach { add(it.name) }
                      videoItems.forEach { add(it.video.displayName) }
                    }
                  }

                ExpressiveScrollBar(
                  gridState = gridState,
                  dragLabelProvider = { index ->
                    fastScrollGlyph(scrollbarLabels.getOrNull(index))
                  },
                  modifier =
                    Modifier
                      .align(Alignment.CenterEnd)
                      .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
                      .graphicsLayer { alpha = scrollbarAlpha },
                )
              }
            }
          } else {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 4.dp,
                  bottom = navigationBarHeight,
                ),
            ) {
              // Breadcrumb navigation (if not at root)
              if (!isAtRoot && breadcrumbs.isNotEmpty()) {
                item {
                  app.gyrolet.mpvrx.ui.browser.filesystem.BreadcrumbNavigation(
                    breadcrumbs = breadcrumbs,
                    onBreadcrumbClick = onBreadcrumbClick,
                  )
                }
              }

              // Folders first
              items(
                items = folderItems,
                key = { it.path },
                contentType = { "folder_item" },
              ) { folder ->
                val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
                val folderModel =
                  app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                    bucketId = folder.path,
                    name = folder.name,
                    path = ZipArchiveMedia.displayPath(folder.path) ?: folder.path,
                    videoCount = folder.videoCount,
                    totalSize = folder.totalSize,
                    totalDuration = folder.totalDuration,
                    lastModified = folder.lastModified / 1000,
                  )

                FolderCard(
                  folder = folderModel,
                  isSelected = selectionManager.isSelected(folder),
                  isRecentlyPlayed = false,
                  onClick = { onFolderClick(folder) },
                  onLongClick = if (archiveFolder) null else ({ onFolderLongClick(folder) }),
                  onThumbClick =
                    if (tapThumbnailToSelect && !archiveFolder) {
                      { selectionManager.toggleFromUser(folder) }
                    } else {
                      { onFolderClick(folder) }
                    },
                  newVideoCount = folder.newCount,
                  isGridMode = false,
                  customIcon = if (ZipArchiveMedia.isArchiveRoot(folder.path)) Icons.RoundedFilled.FolderZip else null,
                  onSwipeAction =
                    swipeActions.folder.takeUnless { archiveFolder || selectionManager.isInSelectionMode || isInSelectionMode },
                )
              }

              // Videos second
              items(
                items = videoItems,
                key = { "${it.video.id}_${it.video.path}" },
                contentType = { "video_item" },
              ) { videoFile ->
                val archiveEntry = ZipArchiveMedia.isPlaybackUri(videoFile.video.uri.toString())
                VideoCard(
                    video = videoFile.video,
                    progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                    isRecentlyPlayed = false,
                    isSelected = selectionManager.isSelected(videoFile),
                    onClick = { onVideoClick(videoFile) },
                    onLongClick = if (archiveEntry) null else ({ onVideoLongClick(videoFile) }),
                    onThumbClick =
                      if (tapThumbnailToSelect && !archiveEntry) {
                        { selectionManager.toggleFromUser(videoFile) }
                      } else {
                        { onVideoClick(videoFile) }
                      },
                    isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                    isWatched = watchedVideoIds.contains(videoFile.video.id),
                    isGridMode = false,
                    onSwipeAction =
                      swipeActions.video.takeUnless { archiveEntry || selectionManager.isInSelectionMode || isInSelectionMode },
                    showSubtitleIndicator = showSubtitleIndicator,
                    overrideShowSizeChip = null,
                    overrideShowResolutionChip = null,
                    useFolderNameStyle = false,
                    allowThumbnailGeneration = !archiveEntry,
                    allowThumbnailLoading = !archiveEntry,
                    uiConfig = videoCardUiConfig,
                )
              }
            }

            if (hasEnoughItems && scrollbarAlpha > 0.01f) {
              val scrollbarLabels =
                remember(items, isAtRoot, breadcrumbs) {
                  buildList<String?> {
                    if (!isAtRoot && breadcrumbs.isNotEmpty()) add(null)
                    items.filterIsInstance<FileSystemItem.Folder>().forEach { add(it.name) }
                    items.filterIsInstance<FileSystemItem.VideoFile>().forEach { add(it.video.displayName) }
                  }
                }

              ExpressiveScrollBar(
                listState = listState,
                dragLabelProvider = { index ->
                  fastScrollGlyph(scrollbarLabels.getOrNull(index))
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
      }
    }
  }
}

@Composable
private fun FileSystemSearchContent(
  listState: LazyListState,
  gridState: LazyGridState,
  searchQuery: String,
  searchResults: List<FileSystemItem>,
  isLoading: Boolean,
  videoFilesWithPlayback: Map<Long, Float>,
  newVideoIds: Set<Long>,
  showSubtitleIndicator: Boolean,
  isAtRoot: Boolean,
  navigationBarHeight: Dp,
  isFabVisible: androidx.compose.runtime.MutableState<Boolean>, // Add FAB visibility state
  onVideoClick: (app.gyrolet.mpvrx.domain.media.model.Video) -> Unit,
  onFolderClick: (FileSystemItem.Folder) -> Unit,
  onChanged: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val swipeActions = rememberVideoSwipeActions(onChanged = onChanged)
  val swipePlaybackInfo = rememberSwipePlaybackInfo(
    searchResults.filterIsInstance<FileSystemItem.VideoFile>().map { it.video },
  )
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val videoCardUiConfig =
    remember(
      unlimitedNameLines,
      showVideoThumbnails,
      showSizeChip,
      showResolutionChip,
      showFramerateInResolution,
      showProgressBar,
      showDateChip,
      showUnplayedOldVideoLabel,
      unplayedOldVideoDays,
      showExtensionField,
      showDurationField,
      centerGridTitles,
      showCodecSupportIndicator,
      thumbnailQuality,
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
        swipeLeft = swipeLeft,
        swipeRight = swipeRight,
      )
    }

  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID
  val searchFolders =
    remember(searchResults) {
      searchResults.filterIsInstance<FileSystemItem.Folder>().distinctBy { it.path }
    }
  val searchVideos =
    remember(searchResults) {
      searchResults.filterIsInstance<FileSystemItem.VideoFile>().distinctBy { it.video.id }
    }
  val scrollbarLabels =
    remember(searchFolders, searchVideos) {
      buildList<String?> {
        searchFolders.forEach { add(it.name) }
        searchVideos.forEach { add(it.video.displayName) }
      }
    }

  // Keep high-frequency scroll reads out of composition in large search result sets.
  LaunchedEffect(isGridMode, listState, gridState) {
    var previousIndex = if (isGridMode) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
    var previousOffset =
      if (isGridMode) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset

    snapshotFlow {
      if (isGridMode) {
        gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
      } else {
        listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
      }
    }.distinctUntilChanged()
      .collect { (currentIndex, currentOffset) ->
        if (currentIndex == 0 && currentOffset == 0) {
          isFabVisible.value = true
        } else {
          val isScrollingDown =
            if (currentIndex != previousIndex) {
              currentIndex > previousIndex
            } else {
              currentOffset > previousOffset
            }
          isFabVisible.value = !isScrollingDown
        }
        previousIndex = currentIndex
        previousOffset = currentOffset
      }
  }

  Box(modifier = modifier.fillMaxSize()) {
    when {
      isLoading && searchResults.isEmpty() -> {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(bottom = 80.dp),
          // Account for bottom navigation bar
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(48.dp),
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = if (isAtRoot) "Searching all storage volumes..." else "Searching...",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      searchResults.isEmpty() && searchQuery.isNotBlank() -> {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          EmptyState(
            icon = Icons.RoundedFilled.Search,
            title = stringResource(R.string.ui_no_results_found),
            message = "No files or folders match \"$searchQuery\"",
          )
        }
      }

      else -> {
        Box(
          modifier = Modifier.fillMaxSize(),
        ) {
          if (isGridMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val spansInfo =
                calculateResponsiveGridSpans(
                  maxWidth = maxWidth,
                  isGridMode = true,
                )

              LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(spansInfo.spans),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 8.dp,
                    end = 8.dp,
                    top = 12.dp,
                    bottom = navigationBarHeight,
                  ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                // Folders first
                items(
                  items = searchFolders,
                  key = { "search_folder_${it.path}" },
                  contentType = { "folder_item" },
                  span = { GridItemSpan(spansInfo.folderSpan) },
                ) { folder ->
                  val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
                  val folderModel =
                    app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                      bucketId = folder.path,
                      name = folder.name,
                      path = ZipArchiveMedia.displayPath(folder.path) ?: folder.path,
                      videoCount = folder.videoCount,
                      totalSize = folder.totalSize,
                      totalDuration = folder.totalDuration,
                      lastModified = folder.lastModified / 1000,
                    )

                  FolderCard(
                    folder = folderModel,
                    isSelected = false,
                    isRecentlyPlayed = false,
                    onClick = { onFolderClick(folder) },
                    onLongClick = null,
                    onThumbClick = { onFolderClick(folder) },
                    newVideoCount = folder.newCount,
                    isGridMode = true,
                    customIcon = if (ZipArchiveMedia.isArchiveRoot(folder.path)) Icons.RoundedFilled.FolderZip else null,
                  )
                }

                // Videos second
                items(
                  items = searchVideos,
                  key = { "search_video_${it.video.id}_${it.video.path}" },
                  contentType = { "video_item" },
                  span = { GridItemSpan(spansInfo.videoSpan) },
                ) { videoFile ->
                  val archiveEntry = ZipArchiveMedia.isPlaybackUri(videoFile.video.uri.toString())
                  VideoCard(
                    video = videoFile.video,
                    progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                    isRecentlyPlayed = false,
                    isSelected = false,
                    onClick = { onVideoClick(videoFile.video) },
                    onLongClick = null,
                    onThumbClick = { onVideoClick(videoFile.video) },
                    isOldAndUnplayed = swipePlaybackInfo[videoFile.video.path]?.isOldAndUnplayed == true,
                    isWatched = swipePlaybackInfo[videoFile.video.path]?.isWatched == true,
                    isGridMode = true,
                    showSubtitleIndicator = showSubtitleIndicator,
                    overrideShowSizeChip = null,
                    overrideShowResolutionChip = null,
                    useFolderNameStyle = false,
                    allowThumbnailGeneration = !archiveEntry,
                    allowThumbnailLoading = !archiveEntry,
                    uiConfig = videoCardUiConfig,
                  )
                }
              }

              if (scrollbarLabels.size > 20) {
                ExpressiveScrollBar(
                  gridState = gridState,
                  dragLabelProvider = { index ->
                    fastScrollGlyph(scrollbarLabels.getOrNull(index))
                  },
                  modifier =
                    Modifier
                      .align(Alignment.CenterEnd)
                      .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp),
                )
              }
            }
          } else {
            // Content extends full height for transparency
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 12.dp,
                  bottom = navigationBarHeight,
                ),
            ) {
              // Folders first
              items(
                items = searchFolders,
                key = { "search_folder_${it.path}" },
                contentType = { "folder_item" },
              ) { folder ->
                val archiveFolder = ZipArchiveMedia.isBrowserPath(folder.path)
                val folderModel =
                  app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                    bucketId = folder.path,
                    name = folder.name,
                    path = ZipArchiveMedia.displayPath(folder.path) ?: folder.path,
                    videoCount = folder.videoCount,
                    totalSize = folder.totalSize,
                    totalDuration = folder.totalDuration,
                    lastModified = folder.lastModified / 1000,
                  )

                FolderCard(
                  folder = folderModel,
                  isSelected = false,
                  isRecentlyPlayed = false,
                  onClick = { onFolderClick(folder) },
                  onLongClick = null,
                  onThumbClick = { onFolderClick(folder) },
                  newVideoCount = folder.newCount,
                  isGridMode = false,
                  customIcon = if (ZipArchiveMedia.isArchiveRoot(folder.path)) Icons.RoundedFilled.FolderZip else null,
                  onSwipeAction = swipeActions.folder.takeUnless { archiveFolder },
                )
              }

              // Videos second
              items(
                items = searchVideos,
                key = { "search_video_${it.video.id}_${it.video.path}" },
                contentType = { "video_item" },
              ) { videoFile ->
                val archiveEntry = ZipArchiveMedia.isPlaybackUri(videoFile.video.uri.toString())
                VideoCard(
                  video = videoFile.video,
                  progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                  isRecentlyPlayed = false,
                  isSelected = false,
                  onClick = { onVideoClick(videoFile.video) },
                  onLongClick = null,
                  onThumbClick = { onVideoClick(videoFile.video) },
                  isOldAndUnplayed = swipePlaybackInfo[videoFile.video.path]?.isOldAndUnplayed == true,
                  isGridMode = false,
                  isWatched = swipePlaybackInfo[videoFile.video.path]?.isWatched == true,
                  onSwipeAction = swipeActions.video.takeUnless { archiveEntry },
                  showSubtitleIndicator = showSubtitleIndicator,
                  overrideShowSizeChip = null,
                  overrideShowResolutionChip = null,
                  useFolderNameStyle = false,
                  allowThumbnailGeneration = !archiveEntry,
                  allowThumbnailLoading = !archiveEntry,
                  uiConfig = videoCardUiConfig,
                )
              }
            }

            if (scrollbarLabels.size > 20) {
              ExpressiveScrollBar(
                listState = listState,
                dragLabelProvider = { index ->
                  fastScrollGlyph(scrollbarLabels.getOrNull(index))
                },
                modifier =
                  Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp),
              )
            }
          }
        }
      }
    }
  }
}
