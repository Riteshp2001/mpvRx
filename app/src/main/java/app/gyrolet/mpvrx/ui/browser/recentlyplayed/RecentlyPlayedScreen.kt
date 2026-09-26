/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.recentlyplayed

import android.content.Intent
import android.widget.Toast
import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper
import app.gyrolet.mpvrx.ui.components.themedSegmentedButtonColors
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.ui.browser.components.rememberVideoSwipeActions
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AdvancedPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.GesturePreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.MediaLibraryType
import app.gyrolet.mpvrx.preferences.RecentSortType
import app.gyrolet.mpvrx.preferences.SortOrder
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
import app.gyrolet.mpvrx.ui.browser.dialogs.RecentSortDialog
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.browser.sheets.PlayLinkSheet
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.rememberTabNavigation
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private fun isRecentlyPlayedItemAudio(item: RecentlyPlayedItem): Boolean =
  when (item) {
    is RecentlyPlayedItem.VideoItem -> item.video.isAudio
    is RecentlyPlayedItem.PlaylistItem -> item.playlist.isAudio
  }

@Serializable
object RecentlyPlayedScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backStack = LocalBackStack.current
    val viewModel: RecentlyPlayedViewModel =
      viewModel(factory = RecentlyPlayedViewModel.factory(context.applicationContext as android.app.Application))

    val browserPreferences = koinInject<BrowserPreferences>()
    val sortType by browserPreferences.recentSortType.collectAsState()
    val sortOrder by browserPreferences.recentSortOrder.collectAsState()
    val mediaLayoutMode by browserPreferences.recentView.layoutMode.collectAsState()
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var contentWidthDp by remember { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val recentItems by viewModel.recentItems.collectAsState()
    val sortedRecentItems = remember(recentItems, sortType, sortOrder) {
      sortRecentItems(recentItems, sortType, sortOrder)
    }
    val isLoading by viewModel.isLoading.collectAsState()
    var recentlyPlayedFilter by rememberSaveable { mutableStateOf(MediaLibraryType.Video) }
    val videoItems =
      remember(sortedRecentItems) {
        sortedRecentItems.filterNot(::isRecentlyPlayedItemAudio)
      }
    val audioItems =
      remember(sortedRecentItems) {
        sortedRecentItems.filter(::isRecentlyPlayedItemAudio)
      }
    val filteredRecentItems = if (recentlyPlayedFilter == MediaLibraryType.Audio) audioItems else videoItems
    val deleteDialogOpen = rememberSaveable { mutableStateOf(false) }
    val deleteFilesCheckbox = rememberSaveable { mutableStateOf(false) }
    val advancedPreferences = koinInject<AdvancedPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val enableRecentlyPlayed by advancedPreferences.enableRecentlyPlayed.collectAsState()
    val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
    val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

    // FAB visibility for scroll-based hiding
    val isFabVisible = remember { mutableStateOf(true) }
    val isFabExpanded = remember { mutableStateOf(false) }
    val showLinkDialog = remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Selection manager for all items (videos and playlists)
    val selectionManager =
      rememberSelectionManager(
        items = filteredRecentItems,
        getId = ::recentlyPlayedItemKey,
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
    // Synchronize NavigationBarState when selection mode changes
    app.gyrolet.mpvrx.ui.browser.NavigationBarSelectionEffect(selectionManager.isInSelectionMode)

    BackHandler(enabled = selectionManager.isInSelectionMode || isFabExpanded.value) {
      when {
        isFabExpanded.value -> isFabExpanded.value = false
        selectionManager.isInSelectionMode -> selectionManager.clear()
      }
    }

    // File picker for opening external files
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

    // Track scroll for FAB visibility - create per-tab states so swiping between
    // Video/Audio doesn't share scroll position, then expose whichever pair is active.
    val videoListState = remember { LazyListState() }
    val videoGridState = remember { LazyGridState() }
    val audioListState = remember { LazyListState() }
    val audioGridState = remember { LazyGridState() }
    val listState = if (recentlyPlayedFilter == MediaLibraryType.Audio) audioListState else videoListState
    val gridState = if (recentlyPlayedFilter == MediaLibraryType.Audio) audioGridState else videoGridState
    app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper.trackScrollForFabVisibility(
      listState = listState,
      gridState = if (mediaLayoutMode == MediaLayoutMode.GRID) gridState else null,
      isFabVisible = isFabVisible,
      expanded = isFabExpanded.value,
      onExpandedChange = { isFabExpanded.value = it },
    )

    // Swipe between the Video/Audio tabs, kept in sync with the segmented buttons.
    val pagerState = rememberPagerState(initialPage = recentlyPlayedFilter.ordinal) { MediaLibraryType.entries.size }
    val navigateTab = rememberTabNavigation(pagerState)
    LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress) {
      if (!pagerState.isScrollInProgress) {
        MediaLibraryType.entries.getOrNull(pagerState.settledPage)?.let { type ->
          if (recentlyPlayedFilter != type) {
            selectionManager.clear()
            recentlyPlayedFilter = type
          }
        }
      }
    }
    LaunchedEffect(recentlyPlayedFilter) {
      navigateTab(recentlyPlayedFilter.ordinal)
    }

    Scaffold(
      modifier = Modifier.onSizeChanged { size ->
        contentWidthDp = with(density) { size.width.toDp().value.toInt() }
      },
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.pref_advanced_enable_recently_played_title),
          isInSelectionMode = selectionManager.isInSelectionMode,
          selectedCount = selectionManager.selectedCount,
          totalCount = filteredRecentItems.size,
          onBackClick = null, // No back button for recently played screen
          onCancelSelection = { selectionManager.clear() },
          onSortClick = { showSortDialog = true },
          onSettingsClick = {
            backStack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
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
        val isFabShouldBeVisible =
          showQuickPlayFab && !selectionManager.isInSelectionMode && isFabVisible.value && filteredRecentItems.isNotEmpty()

        FloatingActionButtonMenu(
          modifier =
            Modifier
              .padding(bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp)),
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
                coroutineScope.launch {
                  val lastPlayed =
                    app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
                      .getLastPlayedEntity()
                  if (lastPlayed != null) {
                    MediaUtils.playFile(
                      source = lastPlayed.filePath,
                      context = context,
                      launchSource = "recently_played_button",
                      title =
                        lastPlayed.videoTitle?.takeIf { it.isNotBlank() }
                          ?: lastPlayed.fileName.takeIf { it.isNotBlank() },
                    )
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
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
      ) {
      Column(
        modifier = Modifier.fillMaxSize(),
      ) {
        if (enableRecentlyPlayed) {
          SingleChoiceSegmentedButtonRow(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            MediaLibraryType.entries.forEachIndexed { index, type ->
              SegmentedButton(
                selected = recentlyPlayedFilter == type,
                onClick = {
                  if (recentlyPlayedFilter != type) {
                    selectionManager.clear()
                    recentlyPlayedFilter = type
                  }
                },
                shape = SegmentedButtonDefaults.itemShape(index, MediaLibraryType.entries.size),
                colors = themedSegmentedButtonColors(),
              ) {
                Text(
                  text =
                    if (type == MediaLibraryType.Audio) {
                      stringResource(R.string.ui_audio_tab)
                    } else {
                      stringResource(R.string.ui_videos)
                    },
                )
              }
            }
          }
        }

        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f),
        ) {
        when {
          !enableRecentlyPlayed -> {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
            EmptyState(
              icon = Icons.RoundedFilled.History,
              title = stringResource(R.string.ui_recently_played_disabled),
              message = stringResource(R.string.ui_recently_played_disabled_message),
            )
          }
        }

        isLoading && recentItems.isEmpty() -> {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(48.dp),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        else -> {
          NavigationPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            allowNestedSwipes = true,
          ) { page ->
            val pageType = MediaLibraryType.entries.getOrNull(page) ?: MediaLibraryType.Video
            val pageIsAudio = pageType == MediaLibraryType.Audio
            val pageItems = if (pageIsAudio) audioItems else videoItems
            val pageListState = if (pageIsAudio) audioListState else videoListState
            val pageGridState = if (pageIsAudio) audioGridState else videoGridState

            if (pageItems.isEmpty()) {
              Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
              ) {
                EmptyState(
                  icon = Icons.RoundedFilled.History,
                  title =
                    if (pageIsAudio) {
                      stringResource(R.string.ui_no_audio_found)
                    } else {
                      stringResource(R.string.ui_no_recently_played_videos)
                    },
                  message = "Items you play will appear here",
                )
              }
            } else {
              RecentItemsContent(
                onChanged = { coroutineScope.launch { viewModel.refresh() } },
                onRefresh = viewModel::refresh,
                recentItems = pageItems,
                selectionManager = selectionManager,
                onVideoClick = { video ->
                  coroutineScope.launch {
                    val playableVideo = viewModel.resolvePlayableRecentVideo(video)
                    if (playableVideo != null) {
                      // Always play individual videos without creating a playlist.
                      MediaUtils.playFile(playableVideo, context, "recently_played")
                    } else {
                      Toast
                        .makeText(
                          context,
                          context.getString(app.gyrolet.mpvrx.R.string.ui_recent_file_no_longer_exists),
                          Toast.LENGTH_SHORT,
                        ).show()
                    }
                  }
                },
                onPlaylistClick = { playlistItem ->
                  // Navigate to playlist detail screen
                  backStack.navigateTo(PlaylistDetailScreen(playlistItem.playlist.id))
                },
                modifier = Modifier,
                isInSelectionMode = selectionManager.isInSelectionMode,
                isAudioTab = pageIsAudio,
                listState = pageListState,
                gridState = pageGridState,
                availableWidthDp = contentWidthDp,
              )
            }
          }
        }
      }
      }
      }

      RecentSortDialog(
        isOpen = showSortDialog,
        onDismiss = { showSortDialog = false },
        availableWidthDp = contentWidthDp,
      )

      // Delete confirmation dialog
      if (deleteDialogOpen.value && selectionManager.isInSelectionMode) {
        // Remove selected items from history
        val itemCount = selectionManager.selectedCount
        val itemText = if (itemCount == 1) "item" else "items"
        val deleteFiles = deleteFilesCheckbox.value

        val title =
          if (deleteFiles) {
            "Delete $itemCount $itemText?"
          } else {
            "Remove $itemCount $itemText from history?"
          }

        val subtitle =
          buildString {
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
              androidx.compose.material3.Text(
                text =
                  androidx.compose.ui.res.stringResource(
                    app.gyrolet.mpvrx.R.string.ui_also_delete_original_file_s,
                  ),
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

      FabScrollHelper.FabScrim(
        visible = isFabExpanded.value && !quickPlayFabDirect,
        onDismiss = { isFabExpanded.value = false },
      )
    }
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
  isAudioTab: Boolean = false,
  listState: LazyListState,
  gridState: LazyGridState,
  onChanged: () -> Unit,
  onRefresh: suspend () -> Unit,
  availableWidthDp: Int? = null,
) {
  val swipeActions = rememberVideoSwipeActions(onChanged = onChanged)
  val swipePlaybackInfo = rememberSwipePlaybackInfo(
    recentItems.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video },
  )
  val gesturePreferences = koinInject<GesturePreferences>()
  val browserPreferences = koinInject<app.gyrolet.mpvrx.preferences.BrowserPreferences>()
  val viewPreferences = browserPreferences.recentView
  val appearancePreferences = koinInject<AppearancePreferences>()
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val tapThumbnailToSelect by gesturePreferences.tapThumbnailToSelect.collectAsState()
  val showSubtitleIndicator by viewPreferences.showSubtitleIndicator.collectAsState()
  val showVideoThumbnails by viewPreferences.showThumbnails.collectAsState()
  val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()
  val unlimitedNameLines by viewPreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by viewPreferences.showSizeChip.collectAsState()
  val showResolutionChip by viewPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolution by viewPreferences.showFramerateInResolution.collectAsState()
  val showProgressBar by viewPreferences.showProgressBar.collectAsState()
  val showDateChip by viewPreferences.showDateChip.collectAsState()
  val showCodecSupportIndicator by viewPreferences.showCodecSupportIndicator.collectAsState()
  val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val mediaLayoutMode by viewPreferences.layoutMode.collectAsState()
  val showExtensionField by viewPreferences.showExtensionField.collectAsState()
  val showDurationField by viewPreferences.showDurationField.collectAsState()
  val centerGridTitles by viewPreferences.centerGridTitles.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val manualGridColumnsEnabled by viewPreferences.manualGridColumnsEnabled.collectAsState()
  val videoGridColumnsPortrait by viewPreferences.gridColumnsPortrait.collectAsState()
  val videoGridColumnsLandscape by viewPreferences.gridColumnsLandscape.collectAsState()
  val musicCoverArtSize by browserPreferences.musicCoverArtSize.collectAsState()
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val screenWidthDp = (availableWidthDp ?: configuration.screenWidthDp).dp
  val contentHorizontalPadding = 8.dp
  val itemSpacing = 2.dp
  val isTelevision = app.gyrolet.mpvrx.utils.device.DeviceFormFactor.isTelevision(LocalContext.current)
  val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
  val computedVideoColumns =
    recentGridColumns(
      screenWidthDp.value.toInt(),
      if (manualGridColumnsEnabled) videoGridColumnsPref else 0,
      isTelevision,
    )

  val isGridMode = mediaLayoutMode == MediaLayoutMode.GRID

  val coroutineScope = rememberCoroutineScope()
  val isRefreshing = remember { mutableStateOf(false) }

  val thumbWidthDp =
    if (isGridMode) {
      val cellWidth =
        (screenWidthDp - contentHorizontalPadding * 2 - itemSpacing * (computedVideoColumns - 1)) / computedVideoColumns
      (cellWidth - 8.dp).coerceAtLeast(1.dp)
    } else if (isAudioTab) {
      musicCoverArtSize.dp
    } else {
      160.dp
    }
  val aspect = if (isAudioTab) 1f else if (isGridMode) 16f / 10f else 16f / 9f
  val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
  val thumbHeightPx = (thumbWidthPx / aspect).toInt()
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

  val recentVideos =
    remember(recentItems) {
      recentItems.filterIsInstance<RecentlyPlayedItem.VideoItem>().map { it.video }
    }

  // Unified thumbnail generation - starts with initial batch and continues as needed
  // This avoids the overhead of multiple conflicting LaunchedEffect calls
      LaunchedEffect(showVideoThumbnails, showNetworkThumbnails, thumbWidthPx, thumbHeightPx, recentItems.size) {
    if (showVideoThumbnails && recentVideos.isNotEmpty()) {
      // Start with all videos - the ThumbnailRepository will handle batching internally
      // This avoids redundant job restarts when scrolling
      val allVideos =
        recentItems
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
    if (isGridMode) {
      val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
      BoxWithConstraints(
        modifier =
          Modifier
            .fillMaxSize(),
      ) {
        val columns = recentGridColumns(
          maxWidth.value.toInt(),
          if (manualGridColumnsEnabled) videoGridColumnsPref else 0,
          isTelevision,
        )
        LazyVerticalGrid(
          columns = GridCells.Fixed(columns),
          state = gridState,
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 8.dp,
              end = 8.dp,
              bottom = if (isInSelectionMode) 88.dp else navigationBarHeight,
            ),
          horizontalArrangement = Arrangement.spacedBy(2.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(
            count = recentItems.size,
            key = { index -> recentlyPlayedItemKey(recentItems[index]) },
            contentType = { index ->
              when (recentItems[index]) {
                is RecentlyPlayedItem.VideoItem -> "video_item"
                is RecentlyPlayedItem.PlaylistItem -> "playlist_item"
              }
            },
          ) { index ->
            when (val item = recentItems[index]) {
              is RecentlyPlayedItem.VideoItem -> {
                VideoCard(
                  video = item.video,
                  isWatched = swipePlaybackInfo[item.video.path]?.isWatched == true,
                  isOldAndUnplayed = swipePlaybackInfo[item.video.path]?.isOldAndUnplayed == true,
                  progressPercentage = null,
                  isSelected = selectionManager.isSelected(item),
                  onClick = {
                    if (selectionManager.isInSelectionMode) {
                      selectionManager.toggleFromUser(item)
                    } else {
                      onVideoClick(item.video)
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick =
                    if (tapThumbnailToSelect) {
                      { selectionManager.toggleFromUser(item) }
                    } else {
                      {
                        if (selectionManager.isInSelectionMode) {
                          selectionManager.toggleFromUser(item)
                        } else {
                          onVideoClick(item.video)
                        }
                      }
                    },
                  isGridMode = true,
                  gridColumns = columns,
                  showSubtitleIndicator = showSubtitleIndicator,
                  uiConfig = videoCardUiConfig,
                )
              }

              is RecentlyPlayedItem.PlaylistItem -> {
                val folderModel =
                  VideoFolder(
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
                      selectionManager.toggleFromUser(item)
                    } else {
                      coroutineScope.launch {
                        onPlaylistClick(item)
                      }
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = {
                    if (tapThumbnailToSelect) {
                      selectionManager.toggleFromUser(item)
                    } else {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggleFromUser(item)
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
                  viewPreferences = viewPreferences,
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
            modifier =
              Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp, top = 6.dp, bottom = navigationBarHeight + 6.dp)
                .graphicsLayer { alpha = scrollbarAlpha },
          )
        }
      }
    } else {
      val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
      Box(
        modifier =
          Modifier
            .fillMaxSize(),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding =
            PaddingValues(
              start = 8.dp,
              end = 8.dp,
              bottom = if (isInSelectionMode) 88.dp else navigationBarHeight,
            ),
        ) {
          items(
            count = recentItems.size,
            key = { index -> recentlyPlayedItemKey(recentItems[index]) },
            contentType = { index ->
              when (recentItems[index]) {
                is RecentlyPlayedItem.VideoItem -> "video_item"
                is RecentlyPlayedItem.PlaylistItem -> "playlist_item"
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
                      selectionManager.toggleFromUser(item)
                    } else {
                      onVideoClick(item.video)
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick =
                    if (tapThumbnailToSelect) {
                      { selectionManager.toggleFromUser(item) }
                    } else {
                      {
                        if (selectionManager.isInSelectionMode) {
                          selectionManager.toggleFromUser(item)
                        } else {
                          onVideoClick(item.video)
                        }
                      }
                    },
                  isGridMode = false,
                  onSwipeAction =
                    swipeActions.video.takeUnless { selectionManager.isInSelectionMode || isInSelectionMode },
                  isWatched = swipePlaybackInfo[item.video.path]?.isWatched == true,
                  isOldAndUnplayed = swipePlaybackInfo[item.video.path]?.isOldAndUnplayed == true,
                  thumbnailWidthPx = if (isAudioTab) with(density) { musicCoverArtSize.dp.roundToPx() } else null,
                  thumbnailHeightPx = if (isAudioTab) with(density) { musicCoverArtSize.dp.roundToPx() } else null,
                  showSubtitleIndicator = showSubtitleIndicator,
                  uiConfig = videoCardUiConfig,
                )
              }

              is RecentlyPlayedItem.PlaylistItem -> {
                val folderModel =
                  VideoFolder(
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
                      selectionManager.toggleFromUser(item)
                    } else {
                      coroutineScope.launch {
                        onPlaylistClick(item)
                      }
                    }
                  },
                  onLongClick = { selectionManager.handleLongClick(item) },
                  onThumbClick = {
                    if (tapThumbnailToSelect) {
                      selectionManager.toggleFromUser(item)
                    } else {
                      if (selectionManager.isInSelectionMode) {
                        selectionManager.toggleFromUser(item)
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
                  viewPreferences = viewPreferences,
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

internal fun recentGridColumns(
  availableWidthDp: Int,
  requestedColumns: Int = 0,
  isTelevision: Boolean = false,
): Int {
  val minimumWidth = if (isTelevision) 240 else 130
  val maximumColumns = ((availableWidthDp - 16 + 2) / (minimumWidth + 2)).coerceIn(1, 8)
  return if (requestedColumns > 0) requestedColumns.coerceIn(1, maximumColumns) else maximumColumns
}

private fun sortRecentItems(
  items: List<RecentlyPlayedItem>,
  type: RecentSortType,
  order: SortOrder,
): List<RecentlyPlayedItem> {
  val comparator = when (type) {
    RecentSortType.LastPlayed -> compareBy { item: RecentlyPlayedItem -> item.timestamp }
    RecentSortType.Name -> compareBy<RecentlyPlayedItem, String>(SortUtils.NaturalOrderComparator.DEFAULT) { item ->
      when (item) {
        is RecentlyPlayedItem.VideoItem -> item.video.displayName
        is RecentlyPlayedItem.PlaylistItem -> item.playlist.name
      }
    }
  }
  val ordered = if (order.isAscending) comparator else comparator.reversed()
  return items.sortedWith(ordered.thenBy(::recentlyPlayedItemKey))
}

private fun recentlyPlayedItemKey(item: RecentlyPlayedItem): String =
  when (item) {
    is RecentlyPlayedItem.VideoItem -> "video_${item.video.id}_${item.timestamp}_${item.video.path}"
    is RecentlyPlayedItem.PlaylistItem -> "playlist_${item.playlist.id}_${item.timestamp}_${item.mostRecentVideoPath}"
  }
