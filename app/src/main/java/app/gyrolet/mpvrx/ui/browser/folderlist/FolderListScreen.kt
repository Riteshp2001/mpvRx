package app.gyrolet.mpvrx.ui.browser.folderlist

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import android.util.Log
import app.gyrolet.mpvrx.domain.browser.FileSystemItem
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.FolderSortType
import app.gyrolet.mpvrx.preferences.FolderViewMode
import app.gyrolet.mpvrx.preferences.FoldersPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserBottomBar
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FileOperationProgressDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.RenameDialog
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.OpenDocumentTreeContract
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderSortDialog
import app.gyrolet.mpvrx.ui.browser.filesystem.FileSystemDirectoryScreen
import app.gyrolet.mpvrx.ui.browser.medialibrary.MediaLibraryContent
import app.gyrolet.mpvrx.ui.browser.filesystem.FileSystemBrowserRootScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.states.LoadingState
import app.gyrolet.mpvrx.ui.browser.states.PermissionDeniedState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.utils.clipboard.SafeClipboard
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.permission.PermissionUtils
import app.gyrolet.mpvrx.utils.sort.SortUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File

@Serializable
object FolderListScreen : Screen {
  @OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val browserPreferences = koinInject<BrowserPreferences>()
    val folderViewMode by browserPreferences.folderViewMode.collectAsState()

    when (folderViewMode) {
      FolderViewMode.FileManager -> FileSystemBrowserRootScreen.Content()
      FolderViewMode.AlbumView -> MediaStoreFolderListContent()
      FolderViewMode.MediaLibrary -> MediaLibraryContent()
    }
  }

  @OptIn(ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  private fun MediaStoreFolderListContent() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // ViewModels and preferences
    val viewModel: FolderListViewModel = viewModel(
      factory = FolderListViewModel.factory(context.applicationContext as android.app.Application)
    )
    val browserPreferences = koinInject<BrowserPreferences>()
    val gesturePreferences = koinInject<GesturePreferences>()
    val foldersPreferences = koinInject<FoldersPreferences>()
    val advancedPreferences = koinInject<app.gyrolet.mpvrx.preferences.AdvancedPreferences>()

    // State collection
    val videoFolders by viewModel.videoFolders.collectAsState()
    val foldersWithNewCount by viewModel.foldersWithNewCount.collectAsState()
    val recentlyPlayedFilePath by viewModel.recentlyPlayedFilePath.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val scanStatus by viewModel.scanStatus.collectAsState()
    val hasCompletedInitialLoad by viewModel.hasCompletedInitialLoad.collectAsState()
    val foldersWereDeleted by viewModel.foldersWereDeleted.collectAsState()

    // Preferences
    val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
    val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val folderGridColumns = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
    val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
    val folderSortType by browserPreferences.folderSortType.collectAsState()
    val folderSortOrder by browserPreferences.folderSortOrder.collectAsState()
    val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val pinnedFolderPaths by foldersPreferences.pinnedFolders.collectAsState()

    // UI state - use standalone states to avoid scroll issues with predictive back gesture
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val navigationBarHeight = LocalNavigationBarHeight.current
    val navBarState = app.gyrolet.mpvrx.ui.browser.NavigationBarState
    val isRefreshing = remember { mutableStateOf(false) }
    val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
    var pendingDeleteFolders by remember { mutableStateOf<List<VideoFolder>>(emptyList()) }
    val showLinkDialog = remember { mutableStateOf(false) }
    val folderPickerOpen = rememberSaveable { mutableStateOf(false) }
    val operationType = remember { mutableStateOf<CopyPasteOps.OperationType?>(null) }
    val progressDialogOpen = rememberSaveable { mutableStateOf(false) }
    var renameDialogOpen by rememberSaveable { mutableStateOf(false) }
    val operationProgress by CopyPasteOps.operationProgress.collectAsState()

    // Search state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<FileSystemItem>>(emptyList()) }
    var isSearchLoading by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val foldersBlacklistedMessage = stringResource(app.gyrolet.mpvrx.R.string.pref_folders_blacklisted)

    // Search logic
    LaunchedEffect(searchQuery, isSearching) {
      if (isSearching && searchQuery.isNotBlank()) {
        delay(250)
        isSearchLoading = true
        try {
          val results = searchFoldersAndVideos(context, searchQuery)
          searchResults = results
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

    // Sorting and filtering
    val sortedFolders = remember(videoFolders, folderSortType, folderSortOrder, pinnedFolderPaths) {
      val sorted = SortUtils.sortFolders(videoFolders, folderSortType, folderSortOrder)
      val (pinned, unpinned) = sorted.partition { it.path in pinnedFolderPaths }
      pinned + unpinned
    }

    val filteredFolders = sortedFolders
    
    suspend fun deleteFolders(folders: List<VideoFolder>): Pair<Int, Int> {
      var deleted = 0
      var failed = 0
      for (folder in folders) {
        try {
          val ids = setOf(folder.bucketId)
          val videos = app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(context, ids)
          if (videos.isNotEmpty()) {
            val (d, f) = viewModel.deleteVideos(videos)
            deleted += d
            failed += f
          }
          val dir = java.io.File(folder.path)
          if (dir.exists()) {
            if (dir.deleteRecursively()) {
              deleted++
            } else {
              failed++
            }
          }
        } catch (e: Exception) {
          Log.e("FolderListScreen", "Error deleting folder ${folder.path}", e)
          failed++
        }
      }
      return Pair(deleted, failed)
    }

    // Selection manager
    val selectionManager = rememberSelectionManager(
      items = sortedFolders,
      getId = { it.bucketId },
      onDeleteItems = { folders, _ -> deleteFolders(folders) },
      onOperationComplete = { viewModel.refresh() },
    )

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
        val selectedFolders = selectionManager.getSelectedItems()
        val selectedVideos = selectedFolders.flatMap { folder ->
          app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(context, setOf(folder.bucketId))
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
    val permissionState = PermissionUtils.handleStoragePermission(
      onPermissionGranted = { viewModel.refresh() },
    )

    // Update MainScreen about permission state
    LaunchedEffect(permissionState.status) {
      app.gyrolet.mpvrx.ui.browser.MainScreen.updatePermissionState(
        isDenied = permissionState.status is PermissionStatus.Denied
      )
    }

    // Update NavigationBarState when selection mode changes
    LaunchedEffect(selectionManager.isInSelectionMode) {
      navBarState.updateSelectionState(
        inSelectionMode = selectionManager.isInSelectionMode,
        onlyVideos = true,
      )
    }

    // Lifecycle observer for refresh
    DisposableEffect(lifecycleOwner) {
      val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          viewModel.recalculateNewVideoCounts()
        }
      }
      lifecycleOwner.lifecycle.addObserver(observer)
      onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Optimized back handler for immediate response
    val shouldHandleBack = selectionManager.isInSelectionMode || isSearching || isFabExpanded.value
    androidx.activity.compose.BackHandler(enabled = shouldHandleBack) {
      when {
        isFabExpanded.value -> isFabExpanded.value = false
        selectionManager.isInSelectionMode -> selectionManager.clear()
        isSearching -> {
          isSearching = false
          searchQuery = ""
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

    Scaffold(
      topBar = {
        if (isSearching) {
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = { Text("Search folders and videos...") },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Filled.Search,
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
                      imageVector = Icons.Filled.Close,
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
            title = stringResource(app.gyrolet.mpvrx.R.string.app_name),
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectionManager.selectedCount,
            totalCount = videoFolders.size,
            onBackClick = null,
            onCancelSelection = { selectionManager.clear() },
            onSortClick = { sortDialogOpen.value = true },
            onSearchClick = { isSearching = !isSearching },
            onSettingsClick = {
              backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
            },
            onRenameClick = null,
            isSingleSelection = selectionManager.isSingleSelection,
            onInfoClick = null,
            onShareClick = {
              coroutineScope.launch {
                val selectedIds = selectionManager.getSelectedItems().map { it.bucketId }.toSet()
                val allVideos = app.gyrolet.mpvrx.repository.MediaFileRepository
                  .getVideosForBuckets(context, selectedIds)
                if (allVideos.isNotEmpty()) {
                  MediaUtils.shareVideos(context, allVideos)
                }
              }
            },
            onCopyClick = {
              val selectedPaths = selectionManager.getSelectedItems().map { it.path }.distinct()
              if (selectedPaths.isNotEmpty()) {
                SafeClipboard.copyPlainText(context, "Selected folder paths", selectedPaths.joinToString("\n"))
              }
            },
            onPlayClick = {
              coroutineScope.launch {
                val selectedIds = selectionManager.getSelectedItems().map { it.bucketId }.toSet()
                val allVideos = app.gyrolet.mpvrx.repository.MediaFileRepository
                  .getVideosForBuckets(context, selectedIds)
                if (allVideos.isNotEmpty()) {
                  if (allVideos.size == 1) {
                    MediaUtils.playFile(allVideos.first(), context)
                  } else {
                    val intent = Intent(Intent.ACTION_VIEW, allVideos.first().uri)
                    intent.setClass(context, app.gyrolet.mpvrx.ui.player.PlayerActivity::class.java)
                    intent.putExtra("internal_launch", true)
                    intent.putParcelableArrayListExtra("playlist", ArrayList(allVideos.map { it.uri }))
                    intent.putExtra("playlist_index", 0)
                    intent.putExtra("launch_source", "playlist")
                    context.startActivity(intent)
                  }
                  selectionManager.clear()
                }
              }
            },
            onPinClick = {
              coroutineScope.launch {
                val selectedFolders = selectionManager.getSelectedItems()
                if (selectedFolders.isEmpty()) return@launch
                val updated = foldersPreferences.pinnedFolders.get().toMutableSet()
                val shouldUnpinAll = selectedFolders.all { it.path in updated }
                selectedFolders.forEach { folder ->
                  if (shouldUnpinAll) {
                    updated.remove(folder.path)
                  } else {
                    updated.add(folder.path)
                  }
                }
                foldersPreferences.pinnedFolders.set(updated)
                selectionManager.clear()
              }
            },
            onBlacklistClick = {
              coroutineScope.launch {
                val selectedFolders = selectionManager.getSelectedItems()
                val blacklistedFolders = foldersPreferences.blacklistedFolders.get().toMutableSet()
                selectedFolders.forEach { folder ->
                  blacklistedFolders.add(folder.path)
                }
                foldersPreferences.blacklistedFolders.set(blacklistedFolders)
                selectionManager.clear()
                viewModel.refresh()
                android.widget.Toast.makeText(
                  context,
                  foldersBlacklistedMessage,
                  android.widget.Toast.LENGTH_SHORT,
                ).show()
              }
            },
            onDeleteClick = null,
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
          )
        }
      },
      floatingActionButton = {
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
                modifier = Modifier.animateFloatingActionButton(
                  visible = !selectionManager.isInSelectionMode && isFabVisible.value && !app.gyrolet.mpvrx.ui.browser.MainScreen.getPermissionDeniedState(),
                  alignment = Alignment.BottomEnd,
                ),
                checked = isFabExpanded.value,
                onCheckedChange = { isFabExpanded.value = !isFabExpanded.value },
              ) {
                val imageVector by remember {
                  derivedStateOf {
                    if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.PlayArrow
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
            icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
            text = { Text(text = "Open File") },
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
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            text = { Text(text = "Recently Played") },
          )

          FloatingActionButtonMenuItem(
            onClick = {
              isFabExpanded.value = false
              showLinkDialog.value = true
            },
            icon = { Icon(Icons.Filled.Link, contentDescription = null) },
            text = { Text(text = "Open Link") },
          )
        }
      },
    ) { padding ->
      Box(modifier = Modifier.padding(padding)) {
        when (permissionState.status) {
          PermissionStatus.Granted -> {
            if (isSearching) {
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
                    icon = Icons.Filled.Search,
                    title = "No results found",
                    message = "No folders or videos match your search query",
                    modifier = Modifier.fillMaxSize(),
                  )
                } else {
                  // Show search results
                  SearchResultsContent(
                    searchResults = searchResults,
                    navigationBarHeight = navigationBarHeight,
                    onFolderClick = { folder ->
                      backstack.add(app.gyrolet.mpvrx.ui.browser.videolist.VideoListScreen(folder.bucketId, folder.name))
                    },
                    onVideoClick = { video ->
                      MediaUtils.playFile(video, context)
                    },
                    mediaLayoutMode = mediaLayoutMode,
                    folderGridColumns = folderGridColumns,
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
                folderGridColumns = folderGridColumns,
                tapThumbnailToSelect = tapThumbnailToSelect,
                navigationBarHeight = navigationBarHeight,
                listState = listState,
                gridState = gridState,
                isRefreshing = isRefreshing,
                selectionManager = selectionManager,
                onRefresh = { viewModel.refresh() },
                onFolderClick = { folder ->
                  if (selectionManager.isInSelectionMode) {
                    selectionManager.toggle(folder)
                  } else {
                    backstack.add(app.gyrolet.mpvrx.ui.browser.videolist.VideoListScreen(folder.bucketId, folder.name))
                  }
                },
                onFolderLongClick = { folder ->
                  selectionManager.handleLongClick(folder)
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

        if (selectionManager.isInSelectionMode) {
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
            onRenameClick = { renameDialogOpen = true },
            onDeleteClick = { pendingDeleteFolders = selectionManager.getSelectedItems() },
            onAddToPlaylistClick = { },
            showCopy = true,
            showMove = true,
            showRename = selectionManager.isSingleSelection,
            showDownscale = false,
            showAddToPlaylist = false,
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .padding(bottom = if (navBarState.shouldHideNavigationBar) 0.dp else navigationBarHeight),
          )
        }
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
                        val videos = app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(context, setOf(folder.bucketId))
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
                      val videos = app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosForBuckets(context, setOf(folder.bucketId))
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
                  android.widget.Toast.makeText(context, "Rename failed", android.widget.Toast.LENGTH_SHORT).show()
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
      )

      if (pendingDeleteFolders.isNotEmpty()) {
        DeleteConfirmationDialog(
          isOpen = true,
          onDismiss = { pendingDeleteFolders = emptyList() },
          onConfirm = {
            val foldersToDelete = pendingDeleteFolders
            pendingDeleteFolders = emptyList()
            coroutineScope.launch {
              runCatching {
                val (deleted, failed) = deleteFolders(foldersToDelete)
                if (deleted > 0) {
                  android.widget.Toast.makeText(context, "Deleted successfully", android.widget.Toast.LENGTH_SHORT).show()
                } else if (failed > 0) {
                  android.widget.Toast.makeText(context, "Failed to delete", android.widget.Toast.LENGTH_SHORT).show()
                }
              }.onFailure {
                android.widget.Toast.makeText(context, "Failed to delete: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
              }
              selectionManager.clear()
              viewModel.refresh()
            }
          },
          itemType = "folder",
          itemCount = pendingDeleteFolders.size,
          itemNames = pendingDeleteFolders.map { it.name },
        )
      }
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
  folderGridColumns: Int,
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
) {
  val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID
  val showLoading = isLoading && !hasCompletedInitialLoad
  val showEmpty = folders.isEmpty() && hasCompletedInitialLoad && !foldersWereDeleted

  val hasEnoughItems = folders.size > 20
  val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
    targetValue = if (hasEnoughItems) 1f else 0f,
    animationSpec = androidx.compose.animation.core.spring(
      dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
      stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
    ),
    label = "scrollbarAlpha",
  )

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    listState = listState,
    modifier = Modifier.fillMaxSize(),
  ) {
    if (showLoading || showEmpty) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        if (showLoading) {
          LoadingState(
            icon = Icons.Filled.Folder,
            title = "Scanning for videos...",
            message = scanStatus ?: "Please wait while we search your device",
          )
        } else if (showEmpty) {
          EmptyState(
            icon = Icons.Filled.Folder,
            title = "No video folders found",
            message = "Add some video files to your device to see them here",
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
          folderGridColumns = folderGridColumns,
          tapThumbnailToSelect = tapThumbnailToSelect,
          navigationBarHeight = navigationBarHeight,
          gridState = gridState,
          scrollbarAlpha = scrollbarAlpha,
          selectionManager = selectionManager,
          onFolderClick = onFolderClick,
          onFolderLongClick = onFolderLongClick,
          onTogglePin = onTogglePin,
        )
      } else {
        ListContent(
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
  folderGridColumns: Int,
  tapThumbnailToSelect: Boolean,
  navigationBarHeight: androidx.compose.ui.unit.Dp,
  gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
  scrollbarAlpha: Float,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<VideoFolder, String>,
  onFolderClick: (VideoFolder) -> Unit,
  onFolderLongClick: (VideoFolder) -> Unit,
  onTogglePin: (VideoFolder) -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    LazyVerticalGrid(
      columns = GridCells.Fixed(folderGridColumns),
      state = gridState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        start = 8.dp,
        end = 8.dp,
        bottom = navigationBarHeight
      ),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      items(count = folders.size, key = { index -> folders[index].bucketId }) { index ->
        val folder = folders[index]
        val isRecentlyPlayed = recentlyPlayedFilePath?.let { filePath ->
          val file = File(filePath)
          file.parent == folder.path
        } ?: false

        val newCount = foldersWithNewCount
          .find { it.folder.bucketId == folder.bucketId }
          ?.newVideoCount ?: 0

        FolderCard(
          folder = folder,
          isSelected = selectionManager.isSelected(folder),
          isRecentlyPlayed = isRecentlyPlayed,
          onClick = { onFolderClick(folder) },
          onLongClick = { onFolderLongClick(folder) },
          onThumbClick = if (tapThumbnailToSelect) {
            { selectionManager.toggle(folder) }
          } else {
            { onFolderClick(folder) }
          },
          newVideoCount = newCount,
          isGridMode = true,
          isPinned = folder.path in pinnedFolderPaths,
          onPinClick =
            if (!selectionManager.isInSelectionMode) {
              { onTogglePin(folder) }
            } else {
              null
            },
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
) {
  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        start = 8.dp,
        end = 8.dp,
        bottom = navigationBarHeight
      ),
    ) {
      items(folders) { folder ->
        val isRecentlyPlayed = recentlyPlayedFilePath?.let { filePath ->
          val file = File(filePath)
          file.parent == folder.path
        } ?: false

        val newCount = foldersWithNewCount
          .find { it.folder.bucketId == folder.bucketId }
          ?.newVideoCount ?: 0

        FolderCard(
          folder = folder,
          isSelected = selectionManager.isSelected(folder),
          isRecentlyPlayed = isRecentlyPlayed,
          onClick = { onFolderClick(folder) },
          onLongClick = { onFolderLongClick(folder) },
          onThumbClick = if (tapThumbnailToSelect) {
            { selectionManager.toggle(folder) }
          } else {
            { onFolderClick(folder) }
          },
          newVideoCount = newCount,
          isGridMode = false,
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
                  "Pinned",
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp),
                      )
                      .padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            } else {
              null
            },
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
  folderGridColumns: Int,
) {
  val folders = searchResults.filterIsInstance<FileSystemItem.Folder>().map { folder ->
    app.gyrolet.mpvrx.domain.media.model.VideoFolder(
      bucketId = folder.path,  // Use path as bucketId since FileSystemItem.Folder doesn't have bucketId
      name = folder.name,
      path = folder.path,
      videoCount = folder.videoCount,
      totalSize = folder.totalSize,
      totalDuration = folder.totalDuration,
      lastModified = folder.lastModified
    )
  }
  val videos = searchResults.filterIsInstance<FileSystemItem.VideoFile>().map { it.video }
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
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
      )
    }
  
  val isGridMode = mediaLayoutMode == app.gyrolet.mpvrx.preferences.MediaLayoutMode.GRID
  
  Box(modifier = Modifier.fillMaxSize()) {
    if (isGridMode) {
      LazyVerticalGrid(
        columns = GridCells.Fixed(folderGridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 8.dp,
          end = 8.dp,
          top = 8.dp,
          bottom = navigationBarHeight + 8.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        items(count = folders.size, key = { index -> folders[index].bucketId }) { index ->
          val folder = folders[index]
          FolderCard(
            folder = folder,
            isSelected = false,
            isRecentlyPlayed = false,
            onClick = { onFolderClick(folder) },
            onLongClick = {},
            onThumbClick = { onFolderClick(folder) },
            newVideoCount = 0,
            isGridMode = true,
          )
        }
        
        items(count = videos.size, key = { index -> videos[index].id }) { index ->
          val video = videos[index]
          VideoCard(
            video = video,
            isSelected = false,
            onClick = { onVideoClick(video) },
            onLongClick = {},
            onThumbClick = { onVideoClick(video) },
            isGridMode = true,
            showSubtitleIndicator = showSubtitleIndicator,
            uiConfig = videoCardUiConfig,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
          start = 8.dp,
          end = 8.dp,
          top = 8.dp,
          bottom = navigationBarHeight + 8.dp
        ),
      ) {
        items(count = folders.size, key = { index -> folders[index].bucketId }) { index ->
          val folder = folders[index]
          FolderCard(
            folder = folder,
            isSelected = false,
            isRecentlyPlayed = false,
            onClick = { onFolderClick(folder) },
            onLongClick = {},
            onThumbClick = { onFolderClick(folder) },
            newVideoCount = 0,
            isGridMode = false,
          )
        }
        
        items(count = videos.size, key = { index -> videos[index].id }) { index ->
          val video = videos[index]
          VideoCard(
            video = video,
            isSelected = false,
            onClick = { onVideoClick(video) },
            onLongClick = {},
            onThumbClick = { onVideoClick(video) },
            isGridMode = false,
            showSubtitleIndicator = showSubtitleIndicator,
            uiConfig = videoCardUiConfig,
          )
        }
      }
    }
  }
}

/**
 * Searches for folders and videos matching the query
 * Returns FileSystemItem results containing matching folders and videos
 */
private suspend fun searchFoldersAndVideos(
  context: Context,
  query: String,
): List<FileSystemItem> {
  val results = mutableListOf<FileSystemItem>()
  
  try {
    Log.d("FolderListScreen", "Searching for: $query")
    
    // Get all video folders
    val folders = app.gyrolet.mpvrx.repository.MediaFileRepository
      .getAllVideoFoldersFast(context)
    
    // Search in folders
    folders.forEach { folder ->
      if (folder.name.contains(query, ignoreCase = true) || 
          folder.path.contains(query, ignoreCase = true)) {
        results.add(
          FileSystemItem.Folder(
            name = folder.name,
            path = folder.path,
            lastModified = folder.lastModified,
            videoCount = folder.videoCount,
            totalSize = folder.totalSize,
            totalDuration = folder.totalDuration,
          )
        )
      }
      
      // Also search within videos in this folder
      val videos = app.gyrolet.mpvrx.repository.MediaFileRepository
        .getVideosInFolder(context, folder.bucketId)
      
      videos.forEach { video ->
        if (video.displayName.contains(query, ignoreCase = true)) {
          results.add(
            FileSystemItem.VideoFile(
              name = video.displayName,
              path = video.path,
              lastModified = video.dateModified,
              video = video,
            )
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




