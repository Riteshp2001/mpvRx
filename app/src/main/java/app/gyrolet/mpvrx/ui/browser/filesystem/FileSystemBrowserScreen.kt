package app.gyrolet.mpvrx.ui.browser.filesystem

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.gyrolet.mpvrx.utils.media.OpenDocumentTreeContract
import app.gyrolet.mpvrx.ui.theme.AppMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.BoxWithConstraints
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.BuildConfig
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
 import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
 import app.gyrolet.mpvrx.ui.browser.dialogs.FileOperationProgressDialog
 import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
 import app.gyrolet.mpvrx.ui.browser.dialogs.RenameDialog
 import app.gyrolet.mpvrx.ui.browser.dialogs.VideoCompressorOverlay
import app.gyrolet.mpvrx.ui.browser.dialogs.FileSystemSortDialog
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.states.PermissionDeniedState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
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
  val playerPreferences = koinInject<app.gyrolet.mpvrx.preferences.PlayerPreferences>()
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

  // ViewModel - use path parameter if provided, otherwise show roots
  val viewModel: FileSystemBrowserViewModel = viewModel(
    key = "FileSystemBrowser_${path ?: "root"}",
    factory = FileSystemBrowserViewModel.factory(
      context.applicationContext as android.app.Application,
      path,
    ),
  )

  // State collection
  val currentPath by viewModel.currentPath.collectAsState()
  val items by viewModel.items.collectAsState()
  val videoFilesWithPlayback by viewModel.videoFilesWithPlayback.collectAsState()
  val newVideoIds by viewModel.newVideoIds.collectAsState()
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

  val selectionManager = rememberSelectionManager(
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
    val videosFromFolders = selectedFolders.flatMap { folder ->
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
  val permissionState = PermissionUtils.handleStoragePermission(
    onPermissionGranted = { viewModel.refresh() },
  )

  // Combined MainScreen updates for better performance and responsiveness
  LaunchedEffect(
    showBottomNavigation, 
    isInSelectionMode,
    onlyVideosSelected,
    permissionState.status
  ) {
    if (isAtRoot) {
      try {
        val mainScreenObj = app.gyrolet.mpvrx.ui.browser.MainScreen

        // Update all MainScreen states in one call to reduce overhead
        mainScreenObj.updateBottomBarVisibility(showBottomNavigation)
        mainScreenObj.updateSelectionState(
          isInSelectionMode = isInSelectionMode,
          isOnlyVideosSelected = true,
          selectionManager = if (onlyVideosSelected) selectionManager else null
        )
        mainScreenObj.updatePermissionState(
          isDenied = permissionState.status is PermissionStatus.Denied
        )
      } catch (e: Exception) {
        Log.e("FileSystemBrowserScreen", "Failed to update MainScreen state", e)
      }
    }
  }

  // Cleanup: Restore bottom navigation bar when leaving the screen
  DisposableEffect(Unit) {
    onDispose {
      if (isAtRoot) {
        try {
          val mainScreenObj = app.gyrolet.mpvrx.ui.browser.MainScreen
          // Restore bottom navigation when leaving the screen
          mainScreenObj.updateBottomBarVisibility(true)
        } catch (e: Exception) {
          Log.e("FileSystemBrowserScreen", "Failed to restore MainScreen bottom bar visibility", e)
        }
      }
    }
  }

  // File picker
  val filePicker = rememberLauncherForActivityResult(
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

  // Tree picker for Play Store-safe copy/move destinations
  val treePickerLauncher = rememberLauncherForActivityResult(
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

  // Listen for lifecycle resume events
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Search functionality - recursive search through all subfolders
  LaunchedEffect(isSearching) {
    if (isSearching) {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  LaunchedEffect(searchQuery, isSearching, isAtRoot, items) {
    if (isSearching && searchQuery.isNotBlank()) {
      delay(250)
      isSearchLoading = true
      try {
        val results =
          if (isAtRoot) {
            items
              .filterIsInstance<FileSystemItem.Folder>()
              .flatMap { storageVolume ->
                runCatching {
                  Log.d("FileSystemBrowserScreen", "Searching in storage volume: ${storageVolume.path}")
                  app.gyrolet.mpvrx.ui.browser.filesystem.searchRecursively(
                    context,
                    storageVolume.path,
                    searchQuery,
                  )
                }.getOrElse { error ->
                  Log.e("FileSystemBrowserScreen", "Error searching volume ${storageVolume.path}", error)
                  emptyList()
                }
              }
              .distinctBy { item ->
                when (item) {
                  is FileSystemItem.VideoFile -> item.video.path
                  is FileSystemItem.Folder -> item.path
                }
              }
          } else {
            Log.d("FileSystemBrowserScreen", "Searching in directory: $currentPath")
            app.gyrolet.mpvrx.ui.browser.filesystem.searchRecursively(context, currentPath, searchQuery)
          }

        searchResults = results
      } catch (e: Exception) {
        Log.e("FileSystemBrowserScreen", "Error during search", e)
        searchResults = emptyList()
      } finally {
        isSearchLoading = false
      }
    } else {
      searchResults = emptyList()
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
      topBar = {
        if (isSearching) {
          // Search mode - show search bar instead of top bar
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = {
                  Text(
                    if (isAtRoot) {
                      "Search in all storage volumes..."
                    } else {
                      "Search in ${breadcrumbs.lastOrNull()?.name ?: "folder"}..."
                    }
                  )
                },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.RoundedFilled.Search,
                    contentDescription = "Search",
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
                      contentDescription = "Cancel",
                    )
                  }
                },
                modifier = Modifier.focusRequester(focusRequester),
              )
            },
            expanded = false,
            onExpandedChange = { },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          ) {
            // Empty content for SearchBar
          }
        } else {
          BrowserTopBar(
            title = if (isAtRoot) {
              stringResource(app.gyrolet.mpvrx.R.string.app_name)
            } else {
              breadcrumbs.lastOrNull()?.name ?: "Tree View"
            },
            isInSelectionMode = isInSelectionMode,
            selectedCount = selectedCount,
            totalCount = totalCount,
            onBackClick = if (isAtRoot) {
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
              backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
            },

            isSingleSelection = selectionManager.isSingleSelection,
            onInfoClick = if (selectedVideos.size == 1 && selectedFolders.isEmpty()) {
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
            onCopyClick = {
              val selectedPaths =
                selectedItems
                  .map { item ->
                    when (item) {
                      is FileSystemItem.Folder -> item.path
                      is FileSystemItem.VideoFile -> item.video.path
                    }
                  }
                  .distinct()
              if (selectedPaths.isNotEmpty()) {
                SafeClipboard.copyPlainText(context, "Selected paths", selectedPaths.joinToString("\n"))
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
            onAddToPlaylistClick = if (!BuildConfig.ENABLE_UPDATE_FEATURE && onlyVideosSelected) {
              { addToPlaylistDialogOpen.value = true }
            } else null,
          )
        }
      },
      floatingActionButton = {
        if (isAtRoot) {
          FloatingActionButtonMenu(
            modifier = Modifier.padding(bottom = navigationBarHeight + 8.dp),
            expanded = isFabExpanded.value,
            button = {
              TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                  if (isFabExpanded.value) {
                    TooltipAnchorPosition.Start
                  } else {
                    TooltipAnchorPosition.Above
                  }
                ),
                tooltip = { PlainTooltip { Text("Toggle menu") } },
                state = rememberTooltipState(),
              ) {
                ToggleFloatingActionButton(
                  modifier = Modifier
                    .animateFloatingActionButton(
                      visible = !isInSelectionMode && isFabVisible.value && !app.gyrolet.mpvrx.ui.browser.MainScreen.getPermissionDeniedState(),
                      alignment = Alignment.BottomEnd,
                    ),
                  checked = isFabExpanded.value,
                  onCheckedChange = { isFabExpanded.value = !isFabExpanded.value },
                ) {
                  val imageVector by remember {
                    derivedStateOf {
                      if (checkedProgress > 0.5f) Icons.RoundedFilled.Close else Icons.RoundedFilled.PlayArrow
                  }
                }
                Icon(
                  imageVector = imageVector,
                  contentDescription = null,
                  modifier = Modifier.animateIcon({ checkedProgress }),
                )
                }
              }
            },
          ) {
            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded.value = false
                filePicker.launch(arrayOf("video/*"))
              },
              icon = { Icon(Icons.RoundedFilled.FileOpen, contentDescription = null) },
              text = { Text(text = "Open File") },
            )

            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded.value = false
                coroutineScope.launch {
                  val recentlyPlayedVideos = app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps.getRecentlyPlayed(limit = 1)
                  val lastPlayed = recentlyPlayedVideos.firstOrNull()
                  if (lastPlayed != null) {
                    MediaUtils.playFile(lastPlayed.filePath, context, "recently_played_button")
                  }
                }
              },
              icon = { Icon(Icons.RoundedFilled.History, contentDescription = null) },
              text = { Text(text = "Recently Played") },
            )

            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded.value = false
                showLinkDialog.value = true
              },
              icon = { Icon(Icons.RoundedFilled.Link, contentDescription = null) },
              text = { Text(text = "Open Link") },
            )
          }
        }
      },
    ) { padding ->
      Box(modifier = Modifier.padding(padding)) {
        when (permissionState.status) {
          PermissionStatus.Granted -> {
            if (isSearching) {
              // Show search results
              FileSystemSearchContent(
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
                  backstack.add(FileSystemDirectoryScreen(folder.path))
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
                  if (isInSelectionMode) {
                    selectionManager.toggle(folder)
                  } else {
                    backstack.add(FileSystemDirectoryScreen(folder.path))
                  }
                },
                onFolderLongClick = { folder ->
                  selectionManager.handleLongClick(folder)
                },
                onVideoClick = { videoFile ->
                  val video = videoFile.video
                  if (isInSelectionMode) {
                    selectionManager.toggle(videoFile)
                  } else {
                    // If playlist mode is enabled, play all videos in current folder starting from clicked one
                    if (playlistMode) {
                      val allVideos = videos
                      val startIndex = allVideos.indexOfFirst { it.id == video.id }
                      if (startIndex >= 0) {
                        if (allVideos.size == 1) {
                          // Single video - play normally
                          MediaUtils.playFile(video, context)
                        } else {
                          // Multiple videos - play as playlist starting from clicked video
                          val intent = Intent(Intent.ACTION_VIEW, allVideos[startIndex].uri)
                          intent.setClass(context, app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
                          intent.putExtra("internal_launch", true)
                          intent.putParcelableArrayListExtra("playlist", ArrayList(allVideos.map { it.uri }))
                          intent.putExtra("playlist_index", startIndex)
                          intent.putExtra("launch_source", "playlist")
                          intent.putExtra("is_audio", video.isAudio)
                          context.startActivity(intent)
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
                  selectionManager.handleLongClick(videoFile)
                },
                onBreadcrumbClick = { component ->
                  // Navigate to the breadcrumb by popping until we reach it
                  // or pushing if it's a new path
                  backstack.add(FileSystemDirectoryScreen(component.fullPath))
                },
                selectionManager = selectionManager,
                modifier = Modifier,
                isInSelectionMode = isInSelectionMode,
              )
            }
          }

          is PermissionStatus.Denied -> {
            PermissionDeniedState(
              onRequestPermission = { permissionState.launchPermissionRequest() },
              modifier = Modifier,
            )
          }
        }
      }
    }

    // Independent Floating Bottom Bar - positioned at absolute bottom
    // Play Store gating is intentionally bypassed here.
    AnimatedVisibility(
      visible = showFloatingBottomBar,
      enter = slideInVertically(
        animationSpec = spring(dampingRatio = AppMotion.Spatial.Expressive.dampingRatio, stiffness = AppMotion.Spatial.Expressive.stiffness),
        initialOffsetY = { fullHeight -> fullHeight }
      ),
      exit = slideOutVertically(
        animationSpec = spring(dampingRatio = AppMotion.Spatial.Standard.dampingRatio, stiffness = AppMotion.Spatial.Standard.stiffness),
        targetOffsetY = { fullHeight -> fullHeight }
      ),
      modifier = Modifier.align(Alignment.BottomCenter)
    ) {
      BrowserBottomBar(
        isSelectionMode = true,
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
          selectedVideos.singleOrNull()?.isAudio == false && selectedFolders.isEmpty(),
        showRename = selectionManager.isSingleSelection,
        showAddToPlaylist = !BuildConfig.ENABLE_UPDATE_FEATURE && onlyVideosSelected,
        modifier = Modifier.padding(bottom = if (app.gyrolet.mpvrx.ui.browser.NavigationBarState.shouldHideNavigationBar) 0.dp else navigationBarHeight)
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
        itemType = when {
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
            operationProgress.error == null) {
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
      videos = selectedVideos,
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
): List<FileSystemItem> {
  coroutineContext.ensureActive()
  val results = mutableListOf<FileSystemItem>()
  
  try {
    Log.d("FileSystemBrowserScreen", "Scanning directory: $directoryPath for query: $query")
    // Scan the current directory
    val items = app.gyrolet.mpvrx.repository.MediaFileRepository
      .scanDirectory(context, directoryPath, showAllFileTypes = false)
      .getOrNull() ?: emptyList()

    Log.d("FileSystemBrowserScreen", "Found ${items.size} items in $directoryPath")

    // Filter items that match the search query (case-insensitive)
    items.forEach { item ->
      coroutineContext.ensureActive()
      when (item) {
        is FileSystemItem.VideoFile -> {
          if (item.video.displayName.contains(query, ignoreCase = true)) {
            Log.d("FileSystemBrowserScreen", "Found matching video: ${item.video.displayName}")
            results.add(item)
          }
        }
        is FileSystemItem.Folder -> {
          if (item.name.contains(query, ignoreCase = true)) {
            Log.d("FileSystemBrowserScreen", "Found matching folder: ${item.name}")
            results.add(item)
          }
          val subResults = searchRecursively(context, item.path, query)
          results.addAll(subResults)
        }
      }
    }
    
    Log.d("FileSystemBrowserScreen", "Returning ${results.size} results from $directoryPath")
  } catch (e: Exception) {
    Log.e("FileSystemBrowserScreen", "Error searching directory $directoryPath", e)
  }

  return results
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
    val items = app.gyrolet.mpvrx.repository.MediaFileRepository
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
    // Multiple videos - play as playlist
    val intent = Intent(Intent.ACTION_VIEW, videos.first().uri)
    intent.setClass(context, app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
    intent.putExtra("internal_launch", true)
    intent.putParcelableArrayListExtra("playlist", ArrayList(videos.map { it.uri }))
    intent.putExtra("playlist_index", 0)
    intent.putExtra("launch_source", "playlist")
    intent.putExtra("is_audio", videos.first().isAudio)
    context.startActivity(intent)
  }
}

@Composable
private fun FileSystemBrowserContent(
  listState: LazyListState,
  gridState: LazyGridState,
  items: List<FileSystemItem>,
  videoFilesWithPlayback: Map<Long, Float>,
  newVideoIds: Set<Long>,
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
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val thumbnailRepository = koinInject<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>()
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
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
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
        showExtensionField = showExtensionField,
        showDurationField = showDurationField,
        centerGridTitles = centerGridTitles,
      )
    }

  // Calculate thumbnail dimensions for list mode
  val thumbWidthDp = 160.dp
  val density = androidx.compose.ui.platform.LocalDensity.current
  val aspect = 16f / 9f
  val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
  val thumbHeightPx = ((thumbWidthPx.toFloat() / aspect).toInt())

  val folders = items.filterIsInstance<FileSystemItem.Folder>()
  val videoFiles = items.filterIsInstance<FileSystemItem.VideoFile>()
  val videos = videoFiles.map { it.video }

  // Create a unique folderId based on the current directories
  val folderId = remember(folders, isAtRoot, breadcrumbs) {
    if (isAtRoot && breadcrumbs.isEmpty()) {
      "filesystem_root"
    } else {
      breadcrumbs.lastOrNull()?.fullPath ?: "filesystem_${breadcrumbs.size}"
    }
  }

  when {
    isLoading -> {
      Box(
        modifier = modifier
          .fillMaxSize()
          .padding(bottom = 80.dp), // Account for bottom navigation bar
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    error != null -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = "Error loading directory",
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
          title = "Empty folder",
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
        animationSpec = androidx.compose.animation.core.spring(
          dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
          stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
        ),
        label = "scrollbarAlpha",
      )

      val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
      val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID

      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        listState = listState,
        modifier = modifier.fillMaxSize(),
      ) {
        Box(
          modifier = Modifier.fillMaxSize()
        ) {
          if (isGridMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val spansInfo = calculateResponsiveGridSpans(
                maxWidth = maxWidth,
                isGridMode = true
              )

              LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(spansInfo.spans),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  bottom = navigationBarHeight
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
                  items = items.filterIsInstance<FileSystemItem.Folder>(),
                  key = { it.path },
                  span = { GridItemSpan(spansInfo.folderSpan) }
                ) { folder ->
                  val folderModel = app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                    bucketId = folder.path,
                    name = folder.name,
                    path = folder.path,
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
                    onLongClick = { onFolderLongClick(folder) },
                    onThumbClick = if (tapThumbnailToSelect) {
                      { selectionManager.toggle(folder) }
                    } else {
                      { onFolderClick(folder) }
                    },
                    newVideoCount = folder.newCount,
                    isGridMode = true,
                  )
                }

                // Videos second
                items(
                  items = items.filterIsInstance<FileSystemItem.VideoFile>(),
                  key = { "${it.video.id}_${it.video.path}" },
                  span = { GridItemSpan(spansInfo.videoSpan) }
                ) { videoFile ->
                  VideoCard(
                    video = videoFile.video,
                    progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                    isRecentlyPlayed = false,
                    isSelected = selectionManager.isSelected(videoFile),
                    onClick = { onVideoClick(videoFile) },
                    onLongClick = { onVideoLongClick(videoFile) },
                    onThumbClick = if (tapThumbnailToSelect) {
                      { selectionManager.toggle(videoFile) }
                    } else {
                      { onVideoClick(videoFile) }
                    },
                    isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                    isGridMode = true,
                    showSubtitleIndicator = showSubtitleIndicator,
                    overrideShowSizeChip = null,
                    overrideShowResolutionChip = null,
                    useFolderNameStyle = false,
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
              contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                bottom = navigationBarHeight
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
                items = items.filterIsInstance<FileSystemItem.Folder>(),
                key = { it.path },
              ) { folder ->
                val folderModel = app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                  bucketId = folder.path,
                  name = folder.name,
                  path = folder.path,
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
                  onLongClick = { onFolderLongClick(folder) },
                  onThumbClick = if (tapThumbnailToSelect) {
                    { selectionManager.toggle(folder) }
                  } else {
                    { onFolderClick(folder) }
                  },
                  newVideoCount = folder.newCount,
                  isGridMode = false,
                )
              }

              // Videos second
              items(
                items = items.filterIsInstance<FileSystemItem.VideoFile>(),
                key = { "${it.video.id}_${it.video.path}" },
              ) { videoFile ->
                VideoCard(
                  video = videoFile.video,
                  progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                  isRecentlyPlayed = false,
                  isSelected = selectionManager.isSelected(videoFile),
                  onClick = { onVideoClick(videoFile) },
                  onLongClick = { onVideoLongClick(videoFile) },
                  onThumbClick = if (tapThumbnailToSelect) {
                    { selectionManager.toggle(videoFile) }
                  } else {
                    { onVideoClick(videoFile) }
                  },
                  isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                  isGridMode = false,
                  showSubtitleIndicator = showSubtitleIndicator,
                  overrideShowSizeChip = null,
                  overrideShowResolutionChip = null,
                  useFolderNameStyle = false,
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
  modifier: Modifier = Modifier,
) {
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
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
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
    ) {
      VideoCardUiConfig(
        unlimitedNameLines = unlimitedNameLines,
        showThumbnails = showVideoThumbnails,
        showSizeChip = showSizeChip,
        showResolutionChip = showResolutionChip,
        showFramerateInResolution = showFramerateInResolution,
        showProgressBar = showProgressBar,
        showDateChip = showDateChip,
        showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
        unplayedOldVideoDays = unplayedOldVideoDays,
        showExtensionField = showExtensionField,
        showDurationField = showDurationField,
        centerGridTitles = centerGridTitles,
      )
    }

  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID

  // Track scroll for FAB visibility in search mode with proper scroll direction detection
  val previousIndex = remember { mutableIntStateOf(0) }
  val previousOffset = remember { mutableIntStateOf(0) }

  val firstVisibleItemIndex = if (isGridMode) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
  val firstVisibleItemScrollOffset = if (isGridMode) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
  
  LaunchedEffect(firstVisibleItemIndex, firstVisibleItemScrollOffset) {
    val currentIndex = firstVisibleItemIndex
    val currentOffset = firstVisibleItemScrollOffset
    
    // Show FAB when at the top
    if (currentIndex == 0 && currentOffset == 0) {
      isFabVisible.value = true
    } else {
      // Calculate if scrolling down or up
      val isScrollingDown = if (currentIndex != previousIndex.value) {
        currentIndex > previousIndex.value
      } else {
        currentOffset > previousOffset.value
      }
      
      // Hide when scrolling down, show when scrolling up
      isFabVisible.value = !isScrollingDown
    }
    
    previousIndex.value = currentIndex
    previousOffset.value = currentOffset
  }

  Box(modifier = modifier.fillMaxSize()) {
    when {
      isLoading -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp), // Account for bottom navigation bar
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
            title = "No results found",
            message = "No files or folders match \"$searchQuery\"",
          )
        }
      }

      else -> {
        Box(
          modifier = Modifier.fillMaxSize()
        ) {
          if (isGridMode) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
              val spansInfo = calculateResponsiveGridSpans(
                maxWidth = maxWidth,
                isGridMode = true
              )

              LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(spansInfo.spans),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 12.dp,
                  bottom = navigationBarHeight
                ),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                // Separate folders and videos for proper ordering and deduplicate
                val folders = searchResults.filterIsInstance<FileSystemItem.Folder>().distinctBy { it.path }
                val videos = searchResults.filterIsInstance<FileSystemItem.VideoFile>().distinctBy { it.video.id }

                // Folders first
                items(
                  items = folders,
                  key = { "search_folder_${it.path}_${it.hashCode()}" },
                  span = { GridItemSpan(spansInfo.folderSpan) }
                ) { folder ->
                  val folderModel = app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                    bucketId = folder.path,
                    name = folder.name,
                    path = folder.path,
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
                    onLongClick = { },
                    onThumbClick = { onFolderClick(folder) },
                    newVideoCount = folder.newCount,
                    isGridMode = true,
                  )
                }

                // Videos second
                items(
                  items = videos,
                  key = { "search_video_${it.video.id}_${it.video.path}_${it.hashCode()}" },
                  span = { GridItemSpan(spansInfo.videoSpan) }
                ) { videoFile ->
                  VideoCard(
                    video = videoFile.video,
                    progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                    isRecentlyPlayed = false,
                    isSelected = false,
                    onClick = { onVideoClick(videoFile.video) },
                    onLongClick = { },
                    onThumbClick = { onVideoClick(videoFile.video) },
                    isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                    isGridMode = true,
                    showSubtitleIndicator = showSubtitleIndicator,
                    overrideShowSizeChip = null,
                    overrideShowResolutionChip = null,
                    useFolderNameStyle = false,
                    uiConfig = videoCardUiConfig,
                  )
                }
              }

              if (searchResults.size > 20) {
                val scrollbarLabels =
                  remember(searchResults) {
                    buildList<String?> {
                      searchResults.filterIsInstance<FileSystemItem.Folder>().forEach { add(it.name) }
                      searchResults.filterIsInstance<FileSystemItem.VideoFile>().forEach { add(it.video.displayName) }
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
                      .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp),
                )
              }
            }
          } else {
            // Content extends full height for transparency
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(
                start = 8.dp,
                end = 8.dp,
                top = 12.dp,
                bottom = navigationBarHeight
              ),
            ) {
              // Separate folders and videos for proper ordering and deduplicate
              val folders = searchResults.filterIsInstance<FileSystemItem.Folder>().distinctBy { it.path }
              val videos = searchResults.filterIsInstance<FileSystemItem.VideoFile>().distinctBy { it.video.id }
              
              // Folders first
              items(
                items = folders,
                key = { "search_folder_${it.path}_${it.hashCode()}" },
              ) { folder ->
                val folderModel = app.gyrolet.mpvrx.domain.media.model.VideoFolder(
                  bucketId = folder.path,
                  name = folder.name,
                  path = folder.path,
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
                  onLongClick = { },
                  onThumbClick = { onFolderClick(folder) },
                  newVideoCount = folder.newCount,
                  isGridMode = false,
                )
              }
              
              // Videos second
              items(
                items = videos,
                key = { "search_video_${it.video.id}_${it.video.path}_${it.hashCode()}" },
              ) { videoFile ->
                VideoCard(
                  video = videoFile.video,
                  progressPercentage = videoFilesWithPlayback[videoFile.video.id],
                  isRecentlyPlayed = false,
                  isSelected = false,
                  onClick = { onVideoClick(videoFile.video) },
                  onLongClick = { },
                  onThumbClick = { onVideoClick(videoFile.video) },
                  isOldAndUnplayed = newVideoIds.contains(videoFile.video.id),
                  isGridMode = false,
                  showSubtitleIndicator = showSubtitleIndicator,
                  overrideShowSizeChip = null,
                  overrideShowResolutionChip = null,
                  useFolderNameStyle = false,
                  uiConfig = videoCardUiConfig,
                )
              }
            }
            
            if (searchResults.size > 20) {
              val scrollbarLabels =
                remember(searchResults) {
                  buildList<String?> {
                    searchResults.filterIsInstance<FileSystemItem.Folder>().forEach { add(it.name) }
                    searchResults.filterIsInstance<FileSystemItem.VideoFile>().forEach { add(it.video.displayName) }
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
                    .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp),
              )
            }
          }
        }
      }
    }
  }
}



