/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.videolist

import android.content.Intent
import android.os.Environment
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.SecureFolderRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.SecureFolderPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionListGap
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionGridGap
import app.gyrolet.mpvrx.ui.browser.cards.mediaSelectionNeighbors
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.presentation.components.captureLiquidGlassBackdrop
import app.gyrolet.mpvrx.ui.browser.components.BrowserBottomBar
import app.gyrolet.mpvrx.ui.browser.components.rememberBrowserBottomBarBackdrop
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.mediaItemMotion
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.toPlaylistCandidates
import app.gyrolet.mpvrx.ui.browser.components.rememberVideoSwipeActions
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FileOperationProgressDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.FolderPickerDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.LoadingDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.RenameDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.rememberSelectionInfoAction
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoCompressorOverlay
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoSortDialog
import app.gyrolet.mpvrx.ui.browser.fab.FabPlacement
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.browser.selection.SelectionManager
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.browser.states.LoadingState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.securefolder.SecureConfirmDialog
import app.gyrolet.mpvrx.ui.securefolder.SecureFolderGateScreen
import app.gyrolet.mpvrx.ui.securefolder.SecureFolderProgressDialog
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.tv.focusableWhen
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import app.gyrolet.mpvrx.utils.media.CopyPasteOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.media.MediaSearchEngine
import app.gyrolet.mpvrx.utils.media.OpenDocumentTreeContract
import app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

@Serializable
data class VideoListScreen(
  val bucketId: String,
  val folderName: String,
  @kotlinx.serialization.Transient val onBack: (() -> Unit)? = null,
  val isDualPane: Boolean = false,
  val isAudio: Boolean = false,
) : Screen {
  @OptIn(ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backstack = LocalBackStack.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val playerPreferences = koinInject<PlayerPreferences>()
    val appearancePreferences = koinInject<app.gyrolet.mpvrx.preferences.AppearancePreferences>()
    val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

    // ViewModel
    val viewModel: VideoListViewModel =
      viewModel(
        key = "VideoListViewModel_${bucketId}_${isAudio}",
        factory = VideoListViewModel.factory(context.applicationContext as android.app.Application, bucketId, isAudio),
      )
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
      if (app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isScopedFolder(bucketId)) {
        // SAF providers do not produce FileObserver events; re-read the selected folder on resume.
        viewModel.refresh()
      } else {
        viewModel.startWatching()
      }
      onPauseOrDispose { viewModel.stopWatching() }
    }
    val videos by viewModel.videos.collectAsState()
    val videosWithPlaybackInfo by viewModel.videosWithPlaybackInfo.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val recentlyPlayedFilePath by viewModel.recentlyPlayedFilePath.collectAsState()
    val lastPlayedInFolderPath by viewModel.lastPlayedInFolderPath.collectAsState()
    val videosWereDeletedOrMoved by viewModel.videosWereDeletedOrMoved.collectAsState()
    val archiveFolder = remember(bucketId) { ZipArchiveMedia.isBrowserPath(bucketId) }
    val scopedFolder = remember(bucketId) { ScopedHiddenFolderScanner.isScopedFolder(bucketId) }

    // Sorting
    val videoSortType by browserPreferences.videoSortType.collectAsState()
    val videoSortOrder by browserPreferences.videoSortOrder.collectAsState()
    val mediaLayoutMode by browserPreferences.folderViewVideoLayoutMode.collectAsState()
    val musicCoverArtSize by browserPreferences.musicCoverArtSize.collectAsState()
    val foldersPreferences = koinInject<app.gyrolet.mpvrx.preferences.FoldersPreferences>()
    val pinnedVideoPaths by foldersPreferences.pinnedVideos.collectAsState()
    val sortedVideos =
      remember(videos, videoSortType, videoSortOrder, pinnedVideoPaths, archiveFolder) {
        val sorted = SortUtils.sortVideos(videos, videoSortType, videoSortOrder)
        if (archiveFolder || pinnedVideoPaths.isEmpty()) {
          sorted
        } else {
          val (pinned, unpinned) = sorted.partition { it.path in pinnedVideoPaths }
          pinned + unpinned
        }
      }
    val sortedVideosWithInfo =
      remember(sortedVideos, videosWithPlaybackInfo) {
        val infoById = videosWithPlaybackInfo.associateBy { it.video.id }
        sortedVideos.map { video ->
          infoById[video.id] ?: VideoWithPlaybackInfo(video)
        }
      }

    var internalSearchQuery by rememberSaveable { mutableStateOf("") }
    var internalIsSearching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val displayedVideosWithInfo =
      remember(sortedVideosWithInfo, internalSearchQuery, internalIsSearching) {
        if (!internalIsSearching || internalSearchQuery.isBlank()) {
          sortedVideosWithInfo
        } else {
          val matchingVideoIds =
            MediaSearchEngine.searchVideos(
              query = internalSearchQuery,
              videos = sortedVideosWithInfo.map { it.video },
            ).mapTo(hashSetOf()) { it.id }
          sortedVideosWithInfo.filter { it.video.id in matchingVideoIds }
        }
      }

    LaunchedEffect(internalIsSearching) {
      if (internalIsSearching) focusRequester.requestFocus()
    }

    // Selection manager
    val selectionManager =
      rememberSelectionManager(
        items = sortedVideos,
        getId = { it.id },
        onDeleteItems = { items, _ -> viewModel.deleteVideos(items) },
        onRenameItem = { video, newName -> viewModel.renameVideo(video, newName) },
        onOperationComplete = { viewModel.refresh() },
      )
    val selectedVideos = selectionManager.getSelectedItems()
    val showSelectionInfo = rememberSelectionInfoAction(
      videos = { selectionManager.getSelectedItems() },
      onSingleOpened = { selectionManager.clear() },
    )
    val watchedVideoIds = remember(sortedVideosWithInfo) {
      sortedVideosWithInfo.filter(VideoWithPlaybackInfo::isWatched).mapTo(hashSetOf()) { it.video.id }
    }

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }
    val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
    val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
    val renameDialogOpen = rememberSaveable { mutableStateOf(false) }
    val addToPlaylistDialogOpen = rememberSaveable { mutableStateOf(false) }
    val compressorDialogOpen = rememberSaveable { mutableStateOf(false) }

    // Copy/Move state
    val folderPickerOpen = rememberSaveable { mutableStateOf(false) }
    val operationType = remember { mutableStateOf<CopyPasteOps.OperationType?>(null) }
    val progressDialogOpen = rememberSaveable { mutableStateOf(false) }
    val operationProgress by CopyPasteOps.operationProgress.collectAsState()
    val treePickerLauncher =
      rememberLauncherForActivityResult(OpenDocumentTreeContract()) { uri ->
        if (uri == null) {
          return@rememberLauncherForActivityResult
        }
        val selectedVideos = selectionManager.getSelectedItems()
        if (selectedVideos.isEmpty()) {
          return@rememberLauncherForActivityResult
        }

        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
          )
        }

        when {
          operationType.value != null -> {
            progressDialogOpen.value = true
            coroutineScope.launch {
              when (operationType.value) {
                is CopyPasteOps.OperationType.Copy -> {
                  CopyPasteOps.copyFilesToTreeUri(context, selectedVideos, uri)
                }

                is CopyPasteOps.OperationType.Move -> {
                  CopyPasteOps.moveFilesToTreeUri(context, selectedVideos, uri)
                }

                else -> {}
              }
            }
          }
        }
      }

    // Private space state
    val movingToPrivateSpace = rememberSaveable { mutableStateOf(false) }
    val showPrivateSpaceCompletionDialog = rememberSaveable { mutableStateOf(false) }
    val privateSpaceMovedCount = remember { mutableIntStateOf(0) }

    // Move-to-Secure-Folder state
    val secureFolderRepository = koinInject<SecureFolderRepository>()
    val secureFolderPreferences = koinInject<SecureFolderPreferences>()
    val moveToSecureConfirmOpen = rememberSaveable { mutableStateOf(false) }
    val moveToSecureProgressOpen = rememberSaveable { mutableStateOf(false) }
    val secureFolderProgress by secureFolderRepository.progress.collectAsState()

    fun moveSelectedToSecureFolder() {
      val selectedVideos = selectionManager.getSelectedItems()
      if (selectedVideos.isEmpty()) return
      moveToSecureProgressOpen.value = true
      coroutineScope.launch {
        val result = secureFolderRepository.moveIn(context, selectedVideos)
        moveToSecureProgressOpen.value = false
        selectionManager.clear()
        viewModel.refresh()
        result
          .onSuccess { batch ->
            val message =
              if (batch.failedIds.isEmpty()) {
                context.getString(R.string.secure_folder_moved_success, batch.succeededIds.size)
              } else {
                context.getString(R.string.secure_folder_moved_partial, batch.succeededIds.size, batch.failedIds.size)
              }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
          }.onFailure {
            Toast.makeText(context, context.getString(R.string.secure_folder_move_failed), Toast.LENGTH_SHORT).show()
          }
      }
    }

    val displayFolderName = videos.firstOrNull()?.bucketDisplayName ?: folderName

    // FAB visibility state
    val isFabVisible = remember { mutableStateOf(true) }

    // Bottom bar animation state
    var showFloatingBottomBar by remember { mutableStateOf(false) }
    val animationDuration = 300

    // Handle selection mode changes with animation
    LaunchedEffect(selectionManager.isInSelectionMode) {
      if (selectionManager.isInSelectionMode) {
        // Entering selection mode: Show floating bar immediately
        showFloatingBottomBar = true
      } else {
        // Exiting selection mode: Hide floating bar
        showFloatingBottomBar = false
      }
    }

    // Update NavigationBarState synchronously when selection mode changes
    app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect(selectionManager.isInSelectionMode)

    // Predictive back: Only intercept when in selection mode
    BackHandler(enabled = selectionManager.isInSelectionMode) {
      selectionManager.clear()
    }

    val bottomBarBackdrop = rememberBrowserBottomBarBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
      Scaffold(
        modifier =
          Modifier
            .fillMaxSize()
            .captureLiquidGlassBackdrop(bottomBarBackdrop),
        containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (internalIsSearching) {
          InlineSearchBar(
            query = internalSearchQuery,
            onQueryChange = { internalSearchQuery = it },
            onSearch = { },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = { Text(stringResource(R.string.ui_search_videos)) },
            leadingIcon = {
              Icon(Icons.RoundedFilled.Search, contentDescription = stringResource(R.string.settings_search_title))
            },
            trailingIcon = {
              IconButton(
                onClick = {
                  internalIsSearching = false
                  internalSearchQuery = ""
                },
              ) {
                Icon(Icons.RoundedFilled.Close, contentDescription = stringResource(R.string.generic_cancel))
              }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
          title = displayFolderName,
          isInSelectionMode = selectionManager.isInSelectionMode,
          selectedCount = selectionManager.selectedCount,
          totalCount = sortedVideosWithInfo.size,
          onBackClick = {
            if (selectionManager.isInSelectionMode) {
              selectionManager.clear()
            } else {
              if (onBack != null) {
                onBack()
              } else {
                backstack.popSafely()
              }
            }
          },
          onCancelSelection = { selectionManager.clear() },
          onSortClick = { sortDialogOpen.value = true },
          onSearchClick = { internalIsSearching = true },
          onSettingsClick =
            if (isDualPane) {
              null
            } else {
              { backstack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen) }
            },
          onTitleDoubleTap = { backstack.navigateTo(SecureFolderGateScreen) },
          onTitleLongPress = { backstack.navigateTo(SecureFolderGateScreen) },
          isSingleSelection = selectionManager.isSingleSelection,
          onInfoClick = showSelectionInfo,
          onShareClick = { selectionManager.shareSelected() },
          onPlayClick = { selectionManager.playSelected() },
          onSelectAll = { selectionManager.selectAll() },
          onInvertSelection = { selectionManager.invertSelection() },
          onDeselectAll = { selectionManager.clear() },
          onMoveToSecureClick = {
            if (!secureFolderPreferences.isPinSet()) {
              backstack.navigateTo(SecureFolderGateScreen)
            } else if (secureFolderPreferences.dontAskBeforeMove.get()) {
              moveSelectedToSecureFolder()
            } else {
              moveToSecureConfirmOpen.value = true
            }
          },
          )
        }
      },
      floatingActionButton = {
        if (sortedVideosWithInfo.isNotEmpty()) {
          val isFabShouldBeVisible =
            showQuickPlayFab && !selectionManager.isInSelectionMode && isFabVisible.value

          TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = {
              PlainTooltip {
                Text(
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.ui_play_recently_played_or_first_video,
                  ),
                )
              }
            },
            state = rememberTooltipState(),
          ) {
            FloatingActionButton(
              modifier =
                Modifier
                  .padding(
                    end = FabPlacement.StandaloneEndPadding,
                    bottom = FabPlacement.scaffoldBottomPadding(),
                  )
                  .animateFloatingActionButton(
                    visible = isFabShouldBeVisible,
                    alignment = Alignment.BottomEnd,
                  ).focusableWhen(isFabShouldBeVisible),
              onClick = {
                coroutineScope.launch {
                  if (scopedFolder) {
                    val folderPaths = sortedVideos.mapTo(hashSetOf()) { it.path }
                    val lastPlayed =
                      RecentlyPlayedOps.getRecentlyPlayed(limit = 100)
                        .firstOrNull { it.filePath in folderPaths }
                    val startIndex =
                      sortedVideos.indexOfFirst { it.path == lastPlayed?.filePath }.coerceAtLeast(0)
                    if (playerPreferences.shouldBuildAutomaticQueue(sortedVideos[startIndex].isAudio)) {
                      MediaUtils.playFiles(sortedVideos, context, startIndex, "video_list")
                    } else {
                      MediaUtils.playFile(sortedVideos[startIndex], context, "video_list")
                    }
                    return@launch
                  }
                  val folderPath =
                    sortedVideosWithInfo
                      .firstOrNull()
                      ?.video
                      ?.path
                      ?.let { File(it).parent } ?: ""
                  val recentlyPlayedVideos = RecentlyPlayedOps.getRecentlyPlayed(limit = 100)
                  val lastPlayedInFolder =
                    recentlyPlayedVideos.firstOrNull {
                      File(it.filePath).parent == folderPath
                    }

                  if (archiveFolder) {
                    MediaUtils.playFiles(sortedVideos, context, launchSource = "zip_folder")
                  } else if (lastPlayedInFolder != null) {
                    MediaUtils.playFile(lastPlayedInFolder.filePath, context, "recently_played_button")
                  } else {
                    MediaUtils.playFile(sortedVideosWithInfo.first().video, context, "first_video_button")
                  }
                }
              },
            ) {
              Icon(
                Icons.RoundedFilled.PlayArrow,
                contentDescription =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.ui_play_recently_played_or_first_video,
                  ),
              )
            }
          }
        }
      },
    ) { padding ->
      val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()

      Box(modifier = Modifier.fillMaxSize()) {
        VideoListContent(
          folderId = bucketId,
          videosWithInfo = displayedVideosWithInfo,
          isLoading = isLoading && videos.isEmpty(),
          isRefreshing = isRefreshing,
          recentlyPlayedFilePath = lastPlayedInFolderPath ?: recentlyPlayedFilePath,
          videosWereDeletedOrMoved = videosWereDeletedOrMoved,
          autoScrollToLastPlayed = autoScrollToLastPlayed,
          onRefresh = { viewModel.refresh() },
          selectionManager = selectionManager,
          onVideoClick = { video ->
            if (selectionManager.isInSelectionMode && !archiveFolder) {
              selectionManager.toggleFromUser(video)
            } else if (!selectionManager.isInSelectionMode) {
              if (archiveFolder || (scopedFolder && playerPreferences.shouldBuildAutomaticQueue(video.isAudio))) {
                val startIndex = sortedVideos.indexOfFirst { it.uri == video.uri }.coerceAtLeast(0)
                MediaUtils.playFiles(
                  sortedVideos,
                  context,
                  startIndex,
                  if (archiveFolder) "zip_folder" else "video_list",
                )
              } else {
                MediaUtils.playFile(video, context, "video_list")
              }
            }
          },
          onVideoLongClick = { video -> if (!archiveFolder) selectionManager.handleLongClick(video) },
          isFabVisible = isFabVisible,
          modifier = Modifier.padding(padding),
          showFloatingBottomBar = showFloatingBottomBar,
          mediaLayoutMode = mediaLayoutMode,
          isAudio = isAudio,
          musicCoverArtSize = musicCoverArtSize,
          isDualPane = isDualPane,
          pinnedVideoPaths = if (archiveFolder) emptySet() else pinnedVideoPaths,
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
        onDownscaleClick = { compressorDialogOpen.value = true },
        onRenameClick = { renameDialogOpen.value = true },
        onDeleteClick = { deleteDialogOpen.value = true },
        onAddToPlaylistClick = { addToPlaylistDialogOpen.value = true },
        onPinClick = {
          foldersPreferences.togglePinnedVideos(selectionManager.getSelectedItems().map { it.path })
          selectionManager.clear()
        },
        unpinSelected = selectedVideos.isNotEmpty() && selectedVideos.all { it.path in pinnedVideoPaths },
        pinLabelRes = R.string.ui_pin_videos,
        unpinLabelRes = R.string.ui_unpin_videos,
        showDownscale = selectionManager.getSelectedItems().let { items -> items.isNotEmpty() && items.none { it.isAudio } },
        showRename = selectionManager.selectedCount > 0,
        modifier = Modifier.padding(bottom = 0.dp),
      )
    }
  }

      // Audio folders use the same preferences and card fields as video folders.
      VideoSortDialog(
        isOpen = sortDialogOpen.value,
        onDismiss = { sortDialogOpen.value = false },
        sortType = videoSortType,
        sortOrder = videoSortOrder,
        onSortTypeChange = { browserPreferences.videoSortType.set(it) },
        onSortOrderChange = { browserPreferences.videoSortOrder.set(it) },
        isDualPane = isDualPane,
        isAudio = isAudio,
        onMetadataFieldEnabled = viewModel::refresh,
      )

      // Delete Dialog
      DeleteConfirmationDialog(
        isOpen = deleteDialogOpen.value,
        onDismiss = { deleteDialogOpen.value = false },
        onConfirm = { selectionManager.deleteSelected() },
        itemType = "video",
        itemCount = selectionManager.selectedCount,
        itemNames = selectionManager.getSelectedItems().map { it.displayName },
        itemPaths = selectionManager.getSelectedItems().map { it.uri.toString() },
        totalSizeBytes = selectionManager.getSelectedItems().sumOf { it.size.coerceAtLeast(0L) }.takeIf { it > 0L },
      )

      // Rename Dialogs
      if (renameDialogOpen.value) {
        if (selectionManager.isSingleSelection) {
          val video = selectionManager.getSelectedItems().firstOrNull()
          if (video != null) {
            val baseName = video.displayName.substringBeforeLast('.')
            val extension = "." + video.displayName.substringAfterLast('.', "")
            RenameDialog(
              isOpen = true,
              onDismiss = { renameDialogOpen.value = false },
              onConfirm = { newName -> selectionManager.renameSelected(newName) },
              currentName = baseName,
              itemType = "file",
              extension = if (extension != ".") extension else null,
            )
          }
        } else if (selectionManager.selectedCount > 1) {
          app.gyrolet.mpvrx.ui.browser.dialogs.BulkAiRenameDialog(
            isOpen = true,
            onDismiss = { renameDialogOpen.value = false },
            onConfirm = { updates -> selectionManager.renameBulk(updates) },
            selectedVideos = selectionManager.getSelectedItems(),
          )
        }
      }

      // Folder Picker Dialog
      FolderPickerDialog(
        isOpen = folderPickerOpen.value,
        currentPath =
          videos.firstOrNull()?.let { File(it.path).parent }
            ?: Environment.getExternalStorageDirectory().absolutePath,
        onDismiss = {
          folderPickerOpen.value = false
        },
        onFolderSelected = { destinationPath ->
          folderPickerOpen.value = false
          val selectedVideos = selectionManager.getSelectedItems()
          if (selectedVideos.isNotEmpty() && operationType.value != null) {
            progressDialogOpen.value = true
            coroutineScope.launch {
              when (operationType.value) {
                is CopyPasteOps.OperationType.Copy -> {
                  CopyPasteOps.copyFiles(context, selectedVideos, destinationPath)
                }

                is CopyPasteOps.OperationType.Move -> {
                  CopyPasteOps.moveFiles(context, selectedVideos, destinationPath)
                }

                else -> {}
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
              viewModel.setVideosWereDeletedOrMoved()
            }
            operationType.value = null
            selectionManager.clear()
            viewModel.refresh()
          },
        )
      }

      if (compressorDialogOpen.value) {
        val selectedVideos = selectionManager.getSelectedItems()
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

      // Private Space Loading Dialog
      LoadingDialog(
        isOpen = movingToPrivateSpace.value,
        message = "Moving to private space...",
      )

      // Private Space Completion Dialog
      if (showPrivateSpaceCompletionDialog.value) {
        app.gyrolet.mpvrx.presentation.components.AppAlertDialog(
          onDismissRequest = { showPrivateSpaceCompletionDialog.value = false },
          title = {
            Text(
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_moved_to_private_space),
              style = MaterialTheme.typography.headlineSmall,
            )
          },
          text = {
            Text(
              text =
                "Successfully moved ${privateSpaceMovedCount.intValue} video(s) to private space.\n\n" +
                  "To access private space, long press on the app name at the top of the main screen.",
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          confirmButton = {
            androidx.compose.material3.Button(
              onClick = { showPrivateSpaceCompletionDialog.value = false },
            ) {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_close),
              )
            }
          },
        )
      }

      // Add to Playlist Dialog
      AddToPlaylistDialog(
        isOpen = addToPlaylistDialogOpen.value,
        candidates = selectionManager.getSelectedItems().toPlaylistCandidates(),
        onDismiss = { addToPlaylistDialogOpen.value = false },
        onSuccess = {
          selectionManager.clear()
          viewModel.refresh()
        },
      )

      // Move to Secure Folder — confirm (skippable via "don't ask again"), then progress
      SecureConfirmDialog(
        isOpen = moveToSecureConfirmOpen.value,
        title = stringResource(R.string.secure_folder_move_items_title, selectionManager.selectedCount),
        subtitle = stringResource(R.string.secure_folder_move_items_subtitle),
        dontAskAgain = secureFolderPreferences.dontAskBeforeMove,
        onConfirm = {
          moveToSecureConfirmOpen.value = false
          moveSelectedToSecureFolder()
        },
        onDismiss = { moveToSecureConfirmOpen.value = false },
      )

      SecureFolderProgressDialog(
        isOpen = moveToSecureProgressOpen.value,
        progress = secureFolderProgress,
        label = stringResource(R.string.secure_folder_moving_progress),
        onCancel = { secureFolderRepository.cancelOperation() },
      )
    }
  }


@Composable
internal fun VideoListContent(
  folderId: String,
  videosWithInfo: List<VideoWithPlaybackInfo>,
  isLoading: Boolean,
  isRefreshing: androidx.compose.runtime.MutableState<Boolean>,
  recentlyPlayedFilePath: String?,
  videosWereDeletedOrMoved: Boolean,
  autoScrollToLastPlayed: Boolean,
  onRefresh: suspend () -> Unit,
  selectionManager: SelectionManager<Video, Long>,
  onVideoClick: (Video) -> Unit,
  onVideoLongClick: (Video) -> Unit,
  isFabVisible: androidx.compose.runtime.MutableState<Boolean>,
  modifier: Modifier = Modifier,
  showFloatingBottomBar: Boolean = false,
  mediaLayoutMode: app.gyrolet.mpvrx.preferences.MediaLayoutMode,
  isAudio: Boolean = false,
  musicCoverArtSize: Int = 48,
  isFabExpanded: Boolean = false,
  onFabExpandedChange: (Boolean) -> Unit = {},
  isDualPane: Boolean = false,
  pinnedVideoPaths: Set<String> = emptySet(),
) {
  val archiveFolder = remember(folderId) { ZipArchiveMedia.isBrowserPath(folderId) }
  val swipeScope = rememberCoroutineScope()
  val swipeActions = rememberVideoSwipeActions(audioOnly = isAudio) { swipeScope.launch { onRefresh() } }
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val musicGridCoverArtSize by browserPreferences.musicGridCoverArtSize.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val density = LocalDensity.current
  val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
  val miniPlayerClearance = app.gyrolet.mpvrx.ui.browser.NavigationBarState.miniPlayerClearance
  val bottomPadding =
    maxOf(
      navigationBarHeight,
      miniPlayerClearance,
      if (showFloatingBottomBar) app.gyrolet.mpvrx.ui.browser.NavigationBarState.selectionBarClearance else 0.dp,
    )
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
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
  val thumbnailGenerator by browserPreferences.thumbnailGenerator.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()
  val videoGridColumnsDualPanePortrait by browserPreferences.videoGridColumnsDualPanePortrait.collectAsState()
  val videoGridColumnsDualPaneLandscape by browserPreferences.videoGridColumnsDualPaneLandscape.collectAsState()
  val aspect = if (isAudio) 1f else if (mediaLayoutMode == MediaLayoutMode.GRID) 16f / 10f else 16f / 9f

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

  when {
    isLoading && videosWithInfo.isEmpty() -> {
      LoadingState(
        icon = Icons.RoundedFilled.VideoLibrary,
        title = stringResource(R.string.ui_scanning_for_videos),
        message = "Please wait while we search your device",
        modifier = modifier.fillMaxSize().padding(bottom = 80.dp),
      )
    }

    videosWithInfo.isEmpty() && !isLoading && videosWereDeletedOrMoved -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.VideoLibrary,
          title = stringResource(R.string.ui_no_videos_in_folder),
          message = "Videos you add to this folder will appear here",
        )
      }
    }

    else -> {
      BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
        // Focused TV cards scale beyond their bounds; keep the border inside each pane.
        val contentHorizontalPadding =
          if (app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(LocalContext.current)) 20.dp else 8.dp
        val itemSpacing = 2.dp
        val usableWidth = maxWidth - (contentHorizontalPadding * 2) - itemSpacing
        val videoMinWidth =
          if (app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(LocalContext.current)) {
            240.dp
          } else {
            130.dp
          }
        val dynamicVideos = (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)
        val videoGridColumns =
          if (isAudio) {
            val audioMinWidth = musicGridCoverArtSize.dp
            (usableWidth / audioMinWidth).toInt().coerceAtLeast(1)
          } else if (manualGridColumnsEnabled) {
            val maxSafeVideos = maxOf(dynamicVideos + 3, (usableWidth / 90.dp).toInt()).coerceAtLeast(1)
            if (isDualPane) {
              val dualVideoPref = if (isLandscape) videoGridColumnsDualPaneLandscape else videoGridColumnsDualPanePortrait
              if (dualVideoPref > 0) dualVideoPref.coerceIn(1, maxSafeVideos) else dynamicVideos
            } else {
              if (videoGridColumnsPref > 0) videoGridColumnsPref.coerceIn(1, maxSafeVideos) else dynamicVideos
            }
          } else {
            dynamicVideos
          }

        // Must match the thumbnail size logic inside `VideoCard` for this screen,
        // otherwise the cache keys won't line up and the UI won't receive updates.
        val thumbWidthDp =
          if (mediaLayoutMode == MediaLayoutMode.GRID) {
            val cellWidth =
              (maxWidth - contentHorizontalPadding * 2 - itemSpacing * (videoGridColumns - 1)) / videoGridColumns
            (cellWidth - 8.dp).coerceAtLeast(1.dp)
          } else if (isAudio) {
            // List mode for audio folders uses the configurable cover-art size instead of the
            // fixed video thumbnail width, so the Music sort dialog's slider has any effect here.
            musicCoverArtSize.dp
          } else {
            128.dp
          }
        val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
        val thumbHeightPx = (thumbWidthPx / aspect).roundToInt()

        // Lazy list/grid states already save their own index and offset. Mirroring every pixel into
        // rememberSaveable state made this whole content scope recompose continuously while scrolling
        // and repeated the O(n) last-played lookup for large libraries.
        val initialScrollIndex =
          remember(folderId, autoScrollToLastPlayed, recentlyPlayedFilePath) {
            if (autoScrollToLastPlayed && recentlyPlayedFilePath != null) {
              videosWithInfo
                .indexOfFirst { it.video.path == recentlyPlayedFilePath }
                .coerceAtLeast(0)
            } else {
              0
            }
          }

        val listState =
          rememberLazyListState(
            initialFirstVisibleItemIndex = initialScrollIndex,
          )

        val gridState =
          rememberLazyGridState(
            initialFirstVisibleItemIndex = initialScrollIndex,
          )

        val latestVideosWithInfo by rememberUpdatedState(videosWithInfo)
        val thumbnailListKey =
          remember(videosWithInfo) {
            buildString {
              append(videosWithInfo.size)
              append('|')
              append(
                videosWithInfo
                  .firstOrNull()
                  ?.video
                  ?.path
                  .orEmpty(),
              )
              append('|')
              append(
                videosWithInfo
                  .lastOrNull()
                  ?.video
                  ?.path
                  .orEmpty(),
              )
            }
          }

        LaunchedEffect(
          folderId, showVideoThumbnails, thumbnailGenerator, thumbWidthPx, thumbHeightPx,
          mediaLayoutMode, thumbnailListKey, listState, gridState,
        ) {
          val generationId = "$folderId:${mediaLayoutMode.name}"
          try {
            if (showVideoThumbnails && !archiveFolder && latestVideosWithInfo.isNotEmpty()) {
              snapshotFlow {
                if (mediaLayoutMode == MediaLayoutMode.LIST) listState.isScrollInProgress
                else gridState.isScrollInProgress
              }.collectLatest { scrolling ->
                // Visible cards have priority: bulk work resumes only after the scroll settles.
                thumbnailRepository.pauseFolderThumbnailGeneration(generationId)
                if (!scrolling) {
                  delay(1_500L)
                  thumbnailRepository.startFolderThumbnailGeneration(
                    folderId = generationId,
                    videos = latestVideosWithInfo.map { it.video },
                    widthPx = thumbWidthPx,
                    heightPx = thumbHeightPx,
                  )
                }
              }
            }
          } finally {
            thumbnailRepository.cancelFolderThumbnailGeneration(generationId)
          }
        }

        FabScrollHelper.trackScrollForFabVisibility(
          listState = listState,
          gridState = if (mediaLayoutMode == MediaLayoutMode.GRID) gridState else null,
          isFabVisible = isFabVisible,
          expanded = isFabExpanded,
          onExpandedChange = onFabExpandedChange,
        )

        val coroutineScope = rememberCoroutineScope()

        val hasEnoughItems = videosWithInfo.size > 10

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
          modifier = Modifier.fillMaxSize(),
        ) {
          val columns =
            when (mediaLayoutMode) {
              MediaLayoutMode.LIST -> 1
              MediaLayoutMode.GRID,
              MediaLayoutMode.MOSAIC,
              -> videoGridColumns
            }

          if (mediaLayoutMode != MediaLayoutMode.LIST) {
            Box(
              modifier =
                Modifier
                  .fillMaxSize(),
            ) {
              LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = contentHorizontalPadding,
                    end = contentHorizontalPadding,
                    top = 8.dp,
                    bottom = bottomPadding + 8.dp,
                  ),
                horizontalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
                verticalArrangement = Arrangement.spacedBy(MediaSelectionGridGap),
              ) {
                items(
                  count = videosWithInfo.size,
                  key = { index -> videosWithInfo[index].video.stableListKey },
                  contentType = { "video_item" },
                ) { index ->
                  val videoWithInfo = videosWithInfo[index]
                  val isRecentlyPlayed = recentlyPlayedFilePath?.let { videoWithInfo.video.path == it } ?: false

                  VideoCard(
                      video = videoWithInfo.video,
                      modifier = mediaItemMotion(),
                      progressPercentage = videoWithInfo.progressPercentage,
                      isRecentlyPlayed = isRecentlyPlayed,
                      isSelected = selectionManager.isSelected(videoWithInfo.video),
                      selectionNeighbors = mediaSelectionNeighbors(index, videosWithInfo.size, columns) { neighbor ->
                        selectionManager.isSelected(videosWithInfo[neighbor].video)
                      },
                      isOldAndUnplayed = videoWithInfo.isOldAndUnplayed,
                      isWatched = videoWithInfo.isWatched,
                      isPinned = videoWithInfo.video.path in pinnedVideoPaths,
                      onClick = { onVideoClick(videoWithInfo.video) },
                      onLongClick = if (archiveFolder) null else ({ onVideoLongClick(videoWithInfo.video) }),
                      onThumbClick =
                        if (tapThumbnailToSelect && !archiveFolder) {
                          { selectionManager.toggleFromUser(videoWithInfo.video) }
                        } else {
                          { onVideoClick(videoWithInfo.video) }
                        },
                      isGridMode = true,
                      gridColumns = columns,
                      thumbnailWidthPx = thumbWidthPx,
                      thumbnailHeightPx = thumbHeightPx,
                      showSubtitleIndicator = showSubtitleIndicator,
                      allowThumbnailGeneration = !archiveFolder,
                      allowThumbnailLoading = !archiveFolder,
                      uiConfig = videoCardUiConfig,
                  )
                }
              }

              if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                ExpressiveScrollBar(
                  gridState = gridState,
                  dragLabelProvider = { index ->
                    fastScrollGlyph(videosWithInfo.getOrNull(index)?.video?.displayName)
                  },
                  modifier =
                    Modifier
                      .align(Alignment.CenterEnd)
                      .padding(end = 2.dp, top = 6.dp, bottom = bottomPadding + 6.dp)
                      .graphicsLayer { alpha = scrollbarAlpha },
                )
              }
            }
          } else {
            Box(
              modifier =
                Modifier
                  .fillMaxSize(),
            ) {
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(MediaSelectionListGap),
                contentPadding =
                  PaddingValues(
                    start = contentHorizontalPadding,
                    end = contentHorizontalPadding,
                    top = 4.dp,
                    bottom = bottomPadding,
                  ),
              ) {
                items(
                  count = videosWithInfo.size,
                  key = { index -> videosWithInfo[index].video.stableListKey },
                  contentType = { "video_item" },
                ) { index ->
                  val videoWithInfo = videosWithInfo[index]
                  val isRecentlyPlayed = recentlyPlayedFilePath?.let { videoWithInfo.video.path == it } ?: false

                  VideoCard(
                      video = videoWithInfo.video,
                      modifier = mediaItemMotion(),
                      progressPercentage = videoWithInfo.progressPercentage,
                      isRecentlyPlayed = isRecentlyPlayed,
                      isSelected = selectionManager.isSelected(videoWithInfo.video),
                      selectionNeighbors = mediaSelectionNeighbors(index, videosWithInfo.size, 1) { neighbor ->
                        selectionManager.isSelected(videosWithInfo[neighbor].video)
                      },
                      isOldAndUnplayed = videoWithInfo.isOldAndUnplayed,
                      isWatched = videoWithInfo.isWatched,
                      isPinned = videoWithInfo.video.path in pinnedVideoPaths,
                      onClick = { onVideoClick(videoWithInfo.video) },
                      onLongClick = if (archiveFolder) null else ({ onVideoLongClick(videoWithInfo.video) }),
                      onThumbClick =
                        if (tapThumbnailToSelect && !archiveFolder) {
                          { selectionManager.toggleFromUser(videoWithInfo.video) }
                        } else {
                          { onVideoClick(videoWithInfo.video) }
                        },
                      isGridMode = false,
                      onSwipeAction = swipeActions.video.takeUnless { archiveFolder || selectionManager.isInSelectionMode },
                      showSubtitleIndicator = showSubtitleIndicator,
                      allowThumbnailGeneration = !archiveFolder,
                      allowThumbnailLoading = !archiveFolder,
                      uiConfig = videoCardUiConfig,
                      thumbnailWidthPx = if (isAudio || videoWithInfo.video.isAudio) with(density) { musicCoverArtSize.dp.roundToPx() } else null,
                      thumbnailHeightPx = if (isAudio || videoWithInfo.video.isAudio) with(density) { musicCoverArtSize.dp.roundToPx() } else null,
                  )
                }
              }

              if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                ExpressiveScrollBar(
                  listState = listState,
                  dragLabelProvider = { index ->
                    fastScrollGlyph(videosWithInfo.getOrNull(index)?.video?.displayName)
                  },
                  modifier =
                    Modifier
                      .align(Alignment.CenterEnd)
                      .padding(end = 2.dp, top = 6.dp, bottom = bottomPadding + 6.dp)
                      .graphicsLayer { alpha = scrollbarAlpha },
                )
              }
            }
          }
        }
      }
    }
  }
}

private val Video.stableListKey: String
  get() = "$id:$path"
