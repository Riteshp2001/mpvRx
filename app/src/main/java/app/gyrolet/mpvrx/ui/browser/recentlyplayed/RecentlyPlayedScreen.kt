package app.gyrolet.mpvrx.ui.browser.recentlyplayed

import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R


import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.BoxWithConstraints
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.ConfirmDialog
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.FolderCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object RecentlyPlayedScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: RecentlyPlayedViewModel =
      viewModel(factory = RecentlyPlayedViewModel.factory(context.applicationContext as android.app.Application))

    val recentItems by viewModel.recentItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
    val deleteFilesCheckbox = rememberSaveable { mutableStateOf(false) }
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

    // FAB visibility for scroll-based hiding
    val isFabVisible = remember { mutableStateOf(true) }
    val isFabExpanded = remember { mutableStateOf(false) }
    val showLinkDialog = remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    
    // Selection manager for all items (videos and playlists)
    val selectionManager =
      rememberSelectionManager(
        items = recentItems,
        getId = { item ->
          when (item) {
            is RecentlyPlayedItem.VideoItem -> "video_${item.video.id}"
            is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}"
          }
        },
        onDeleteItems = { items, deleteFiles ->
          val videos = items.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video }
          val playlistIds = items.filterIsInstance<RecentlyPlayedItem.PlaylistItem>().map { it.playlist.id }

          var successCount = 0
          var failCount = 0

          // Delete videos from history
          if (videos.isNotEmpty()) {
            val (videoSuccess, videoFail) = viewModel.deleteVideosFromHistory(videos, deleteFiles)
            successCount += videoSuccess
            failCount += videoFail
          }

          // Delete playlist items from history
          if (playlistIds.isNotEmpty()) {
            val (playlistSuccess, playlistFail) = viewModel.deletePlaylistsFromHistory(playlistIds)
            successCount += playlistSuccess
            failCount += playlistFail
          }

          Pair(successCount, failCount)
        },
        onRenameItem = null, // Cannot rename from history screen
        onOperationComplete = { },
      )

    // Handle back button during selection mode or FAB menu expanded
    BackHandler(enabled = selectionManager.isInSelectionMode || isFabExpanded.value) {
      when {
        isFabExpanded.value -> isFabExpanded.value = false
        selectionManager.isInSelectionMode -> selectionManager.clear()
      }
    }
    
    // File picker for opening external files
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

    // Track scroll for FAB visibility - create states here to pass to content
    val listState = remember { LazyListState() }
    val gridState = remember { LazyGridState() }
    val browserPreferences = koinInject<BrowserPreferences>()
    val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
    app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper.trackScrollForFabVisibility(
      listState = listState,
      gridState = if (mediaLayoutMode == MediaLayoutMode.GRID) gridState else null,
      isFabVisible = isFabVisible,
      expanded = isFabExpanded.value,
      onExpandedChange = { isFabExpanded.value = it },
    )

    Scaffold(
        topBar = {
          BrowserTopBar(
            title = stringResource(R.string.pref_advanced_enable_recently_played_title),
            isInSelectionMode = selectionManager.isInSelectionMode,
            selectedCount = selectionManager.selectedCount,
            totalCount = recentItems.size,
            onBackClick = null, // No back button for recently played screen
            onCancelSelection = { selectionManager.clear() },
            onSortClick = null, // No sorting in recently played
            onSettingsClick = {
              backStack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
            },
            isSingleSelection = selectionManager.isSingleSelection,
            onInfoClick = null, // No info in recently played
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = { selectionManager.selectAll() },
            onInvertSelection = { selectionManager.invertSelection() },
            onDeselectAll = { selectionManager.clear() },
            onDeleteClick = { deleteDialogOpen.value = true },
          )
        },
      floatingActionButton = {
        FloatingActionButtonMenu(
          modifier = Modifier
            .padding(bottom = navigationBarHeight + 8.dp),
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
              tooltip = { PlainTooltip { Text(androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_toggle_menu)) } },
              state = rememberTooltipState(),
            ) {
              ToggleFloatingActionButton(
                modifier = Modifier
                  .animateFloatingActionButton(
                    visible = !selectionManager.isInSelectionMode && isFabVisible.value,
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
            text = { Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_open_file)) },
          )

          FloatingActionButtonMenuItem(
            onClick = {
              isFabExpanded.value = false
              coroutineScope.launch {
                val lastPlayed = app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps.getLastPlayedEntity()
                if (lastPlayed != null) {
                  MediaUtils.playFile(
                    source = lastPlayed.filePath,
                    context = context,
                    launchSource = "recently_played_button",
                    title = lastPlayed.videoTitle?.takeIf { it.isNotBlank() }
                      ?: lastPlayed.fileName.takeIf { it.isNotBlank() },
                  )
                }
              }
            },
            icon = { Icon(Icons.RoundedFilled.History, contentDescription = null) },
            text = { Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.pref_advanced_enable_recently_played_title)) },
          )

          FloatingActionButtonMenuItem(
            onClick = {
              isFabExpanded.value = false
              showLinkDialog.value = true
            },
            icon = { Icon(Icons.RoundedFilled.Link, contentDescription = null) },
            text = { Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_open_link)) },
          )
        }
      },
    ) { padding ->
      when {
        !enableRecentlyPlayed -> {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
            contentAlignment = Alignment.Center,
          ) {
            EmptyState(
              icon = Icons.RoundedFilled.History,
              title = stringResource(R.string.ui_recently_played_disabled),
              message = "Enable it in Advanced Settings to track your playback history",
            )
          }
        }

        isLoading && recentItems.isEmpty() -> {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(48.dp),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        recentItems.isEmpty() && !isLoading -> {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .padding(padding),
            contentAlignment = Alignment.Center,
          ) {
            EmptyState(
              icon = Icons.RoundedFilled.History,
              title = stringResource(R.string.ui_no_recently_played_videos),
              message = "Videos you play will appear here",
            )
          }
        }

        else -> {
          RecentItemsContent(
            recentItems = recentItems,
            selectionManager = selectionManager,
            onVideoClick = { video ->
              coroutineScope.launch {
                val playableVideo = viewModel.resolvePlayableRecentVideo(video)
                if (playableVideo != null) {
                  // Always play individual videos without creating a playlist.
                  MediaUtils.playFile(playableVideo, context, "recently_played")
                } else {
                  Toast.makeText(context, context.getString(app.gyrolet.mpvrx.R.string.ui_recent_file_no_longer_exists), Toast.LENGTH_SHORT).show()
                }
              }
            },
            onPlaylistClick = { playlistItem ->
              // Navigate to playlist detail screen
              backStack.add(PlaylistDetailScreen(playlistItem.playlist.id))
            },
            modifier = Modifier.padding(padding),
            isInSelectionMode = selectionManager.isInSelectionMode,
            listState = listState,
            gridState = gridState,
          )
        }
      }

      // Delete confirmation dialog
      if (deleteDialogOpen.value && selectionManager.isInSelectionMode) {
        // Remove selected items from history
        val itemCount = selectionManager.selectedCount
        val itemText = if (itemCount == 1) "item" else "items"
        val deleteFiles = deleteFilesCheckbox.value

        val title = if (deleteFiles) {
          "Delete $itemCount $itemText?"
        } else {
          "Remove $itemCount $itemText from history?"
        }

        val subtitle = buildString {
          if (deleteFiles) {
            append("This will permanently delete the original video file(s) from your device storage.\n\n")
            append("This action cannot be undone.")
          } else {
            append("This will remove the selected $itemText from your recently played list. ")
            append("The original video files will not be deleted.")
          }
        }

        ConfirmDialog(
          title = title,
          subtitle = subtitle,
          customContent = {
            androidx.compose.foundation.layout.Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
              androidx.compose.material3.Checkbox(
                checked = deleteFilesCheckbox.value,
                onCheckedChange = {
                  deleteFilesCheckbox.value = it
                },
              )
              androidx.compose.material3.Text(text = androidx.compose.ui.res.stringResource(app.gyrolet.mpvrx.R.string.ui_also_delete_original_file_s),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          },
          onConfirm = {
            selectionManager.deleteSelected(deleteFilesCheckbox.value)
            deleteDialogOpen.value = false
            deleteFilesCheckbox.value = false
          },
          onCancel = {
            deleteDialogOpen.value = false
            deleteFilesCheckbox.value = false
          },
        )
      }
      
      // Link dialog
      PlayLinkSheet(
        isOpen = showLinkDialog.value,
        onDismiss = { showLinkDialog.value = false },
        onPlayLink = { url -> MediaUtils.playFile(url, context, "play_link") },
      )
    }
  }
}

@Composable
private fun RecentItemsContent(
  recentItems: List<RecentlyPlayedItem>,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<RecentlyPlayedItem, String>,
  onVideoClick: (Video) -> Unit,
  onPlaylistClick: suspend (RecentlyPlayedItem.PlaylistItem) -> Unit,
  modifier: Modifier = Modifier,
  isInSelectionMode: Boolean = false,
  listState: LazyListState,
  gridState: LazyGridState,
) {
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by browserPreferences.showSubtitleIndicator.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by browserPreferences.showProgressBar.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val mediaLayoutMode by browserPreferences.mediaLayoutMode.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationField by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val screenWidthDp = configuration.screenWidthDp.dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing
  val videoMinWidth = 130.dp
  val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
  val computedVideoColumns = if (manualGridColumnsEnabled) {
    videoGridColumnsPref.coerceAtLeast(1)
  } else {
    (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)
  }

  val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID

  val coroutineScope = rememberCoroutineScope()
  val isRefreshing = remember { mutableStateOf(false) }

  val thumbWidthDp = if (isGridMode) {
    (screenWidthDp / computedVideoColumns)
  } else {
    160.dp
  }
  val aspect = 16f / 9f
  val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
  val thumbHeightPx = (thumbWidthPx / aspect).toInt()
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

  val recentVideos = remember(recentItems) {
    recentItems.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video }
  }

  // Unified thumbnail generation - starts with initial batch and continues as needed
  // This avoids the overhead of multiple conflicting LaunchedEffect calls
  LaunchedEffect(showVideoThumbnails, thumbWidthPx, thumbHeightPx, recentItems.size) {
    if (showVideoThumbnails && recentVideos.isNotEmpty()) {
      // Start with all videos - the ThumbnailRepository will handle batching internally
      // This avoids redundant job restarts when scrolling
      val allVideos = recentItems
        .filterIsInstance<RecentlyPlayedItem.VideoItem>()
        .map { it.video }
      thumbnailRepository.startFolderThumbnailGeneration(
        folderId = "recently_played",
        videos = allVideos,
        widthPx = thumbWidthPx,
        heightPx = thumbHeightPx,
      )
    }
  }

  val hasEnoughItems = recentItems.size > 20

  val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
    targetValue = if (!hasEnoughItems) 0f else 1f,
    animationSpec = androidx.compose.animation.core.spring(
      dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
      stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
    ),
    label = "scrollbarAlpha",
  )

  PullRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = { },
    listState = listState,
    modifier = modifier.fillMaxSize(),
  ) {
    if (isGridMode) {
      val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
      BoxWithConstraints(
        modifier = Modifier
          .fillMaxSize()
          .padding(bottom = navigationBarHeight)
      ) {
        val spansInfo = calculateResponsiveGridSpans(
          maxWidth = maxWidth,
          isGridMode = true
        )
        LazyVerticalGrid(
          columns = GridCells.Fixed(spansInfo.spans),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            bottom = if (isInSelectionMode) 88.dp else 16.dp
          ),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(
            count = recentItems.size,
            key = { index ->
              when (val item = recentItems[index]) {
                is RecentlyPlayedItem.VideoItem -> "video_${item.video.id}_${item.timestamp}"
                is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}_${item.timestamp}"
              }
            },
            span = { index ->
              val item = recentItems[index]
              val itemSpan = when (item) {
                is RecentlyPlayedItem.PlaylistItem -> spansInfo.folderSpan
                is RecentlyPlayedItem.VideoItem -> spansInfo.videoSpan
              }
              GridItemSpan(itemSpan)
            }
          ) { index ->
            when (val item = recentItems[index]) {
              is RecentlyPlayedItem.VideoItem -> {
                VideoCard(
                  video = item.video,
                  progressPercentage = null,
                  isSelected = selectionManager.isSelected(item),
                  onClick = {
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggle(item)
                    } else {
                      onVideoClick(item.video)
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = if (tapThumbnailToSelect) {
                    { selectionManager.toggle(item) }
                  } else {
                    {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggle(item)
                      } else {
                        onVideoClick(item.video)
                      }
                    }
                  },
                  isGridMode = true,
                  gridColumns = spansInfo.spans,
                  showSubtitleIndicator = showSubtitleIndicator,
                  uiConfig = videoCardUiConfig,
                )
              }

              is RecentlyPlayedItem.PlaylistItem -> {
                val folderModel = VideoFolder(
                  bucketId = item.playlist.id.toString(),
                  name = item.playlist.name,
                  path = "",
                  videoCount = item.videoCount,
                  totalSize = 0,
                  totalDuration = 0,
                  lastModified = item.playlist.updatedAt / 1000,
                )
                FolderCard(
                  folder = folderModel,
                  isSelected = selectionManager.isSelected(item),
                  isRecentlyPlayed = false,
                  onClick = {
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggle(item)
                    } else {
                      coroutineScope.launch {
                        onPlaylistClick(item)
                      }
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = {
                    if (tapThumbnailToSelect) {
                      selectionManager.toggle(item)
                    } else {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggle(item)
                      } else {
                        coroutineScope.launch {
                          onPlaylistClick(item)
                        }
                      }
                    }
                  },
                  customIcon = Icons.RoundedFilled.PlaylistPlay,
                  showDateModified = true,
                  isGridMode = true,
                )
              }
            }
          }
        }
        if (hasEnoughItems && scrollbarAlpha > 0.01f) {
          ExpressiveScrollBar(
            gridState = gridState,
            dragLabelProvider = { index: Int ->
              fastScrollGlyph(
                when (val item = recentItems.getOrNull(index)) {
                  is RecentlyPlayedItem.VideoItem -> item.video.displayName
                  is RecentlyPlayedItem.PlaylistItem -> item.playlist.name
                  null -> null
                },
              )
            },
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .padding(end = 4.dp)
              .graphicsLayer { alpha = scrollbarAlpha },
          )
        }
      }
    } else {
      val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(bottom = navigationBarHeight)
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            bottom = if (isInSelectionMode) 88.dp else 16.dp
          ),
        ) {
          items(
            count = recentItems.size,
            key = { index ->
              when (val item = recentItems[index]) {
                is RecentlyPlayedItem.VideoItem -> "video_${item.video.id}_${item.timestamp}"
                is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}_${item.timestamp}"
              }
            },
          ) { index ->
            when (val item = recentItems[index]) {
              is RecentlyPlayedItem.VideoItem -> {
                VideoCard(
                  video = item.video,
                  progressPercentage = null,
                  isSelected = selectionManager.isSelected(item),
                  onClick = {
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggle(item)
                    } else {
                      onVideoClick(item.video)
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = if (tapThumbnailToSelect) {
                    { selectionManager.toggle(item) }
                  } else {
                    {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggle(item)
                      } else {
                        onVideoClick(item.video)
                      }
                    }
                  },
                  isGridMode = false,
                  showSubtitleIndicator = showSubtitleIndicator,
                  uiConfig = videoCardUiConfig,
                )
              }

              is RecentlyPlayedItem.PlaylistItem -> {
                val folderModel = VideoFolder(
                  bucketId = item.playlist.id.toString(),
                  name = item.playlist.name,
                  path = "",
                  videoCount = item.videoCount,
                  totalSize = 0,
                  totalDuration = 0,
                  lastModified = item.playlist.updatedAt / 1000,
                )
                FolderCard(
                  folder = folderModel,
                  isSelected = selectionManager.isSelected(item),
                  isRecentlyPlayed = false,
                  onClick = {
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggle(item)
                    } else {
                      coroutineScope.launch {
                        onPlaylistClick(item)
                      }
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = {
                    if (tapThumbnailToSelect) {
                      selectionManager.toggle(item)
                    } else {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggle(item)
                      } else {
                        coroutineScope.launch {
                          onPlaylistClick(item)
                        }
                      }
                    }
                  },
                  customIcon = Icons.RoundedFilled.PlaylistPlay,
                  showDateModified = true,
                  isGridMode = false,
                )
              }
            }
          }
        }
        if (hasEnoughItems && scrollbarAlpha > 0.01f) {
          ExpressiveScrollBar(
            listState = listState,
            dragLabelProvider = { index: Int ->
              fastScrollGlyph(
                when (val item = recentItems.getOrNull(index)) {
                  is RecentlyPlayedItem.VideoItem -> item.video.displayName
                  is RecentlyPlayedItem.PlaylistItem -> item.playlist.name
                  null -> null
                },
              )
            },
            modifier = Modifier
              .align(Alignment.CenterEnd)
              .padding(end = 4.dp)
              .graphicsLayer { alpha = scrollbarAlpha },
          )
        }
      }
    }
  }
}



