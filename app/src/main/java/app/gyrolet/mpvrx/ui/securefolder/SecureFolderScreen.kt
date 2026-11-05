/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.securefolder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.repository.VideoMetadataCacheRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.ui.browser.cards.VideoCard
import app.gyrolet.mpvrx.ui.browser.cards.VideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.VideoSortDialog
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.utils.media.MediaInfoOps
import app.gyrolet.mpvrx.utils.media.MediaUtils
import app.gyrolet.mpvrx.utils.sort.SortUtils
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

/**
 * The unlocked Secure Folder: supports List and Grid layouts (reusing [VideoCard] from video list),
 * sorting by Title/Date/Size/Duration, view options via [VideoSortDialog], multi-select
 * for bulk restore/delete, plus overflow-menu actions to hide/unhide the entry point and change PIN.
 */
@Serializable
data object SecureFolderScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val context = LocalContext.current
    val backstack = LocalBackStack.current
    val viewModel: SecureFolderViewModel =
      viewModel(factory = SecureFolderViewModel.factory(context.applicationContext as android.app.Application))


    val browserPreferences = koinInject<BrowserPreferences>()
    val appearancePreferences = koinInject<AppearancePreferences>()
    val metadataCacheRepository = koinInject<VideoMetadataCacheRepository>()

    val media by viewModel.secureMedia.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val operationProgress by viewModel.operationProgress.collectAsState()
    val operationResult by viewModel.operationResult.collectAsState()

    // Preferences for sorting, layout mode and video card UI config
    val videoSortType by browserPreferences.videoSortType.collectAsState()
    val videoSortOrder by browserPreferences.videoSortOrder.collectAsState()
    val mediaLayoutMode by browserPreferences.folderViewVideoLayoutMode.collectAsState()

    val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
    val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
    val showSizeChip by browserPreferences.showSizeChip.collectAsState()
    val showResolutionChip by browserPreferences.showResolutionChip.collectAsState()
    val showFramerateInResolution by browserPreferences.showFramerateInResolution.collectAsState()
    val showProgressBar by browserPreferences.showProgressBar.collectAsState()
    val grayscaleWatchedThumbnails by browserPreferences.grayscaleWatchedThumbnails.collectAsState()
    val showDateChip by browserPreferences.showDateChip.collectAsState()
    val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
    val showUnplayedOldVideoLabel by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
    val unplayedOldVideoDays by appearancePreferences.unplayedOldVideoDays.collectAsState()
    val showExtensionField by browserPreferences.showExtensionField.collectAsState()
    val showDurationField by browserPreferences.showDurationField.collectAsState()
    val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
    val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
    val thumbnailGenerator by browserPreferences.thumbnailGenerator.collectAsState()

    val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
    val videoGridColumnsPortrait by browserPreferences.videoGridColumnsPortrait.collectAsState()
    val videoGridColumnsLandscape by browserPreferences.videoGridColumnsLandscape.collectAsState()

    val videoCardUiConfig =
      remember(
        unlimitedNameLines,
        showVideoThumbnails,
        showSizeChip,
        showResolutionChip,
        showFramerateInResolution,
        showCodecSupportIndicator,
        showProgressBar,
        grayscaleWatchedThumbnails,
        showDateChip,
        showUnplayedOldVideoLabel,
        unplayedOldVideoDays,
        showExtensionField,
        showDurationField,
        centerGridTitles,
        thumbnailQuality,
        thumbnailGenerator,
      ) {
        VideoCardUiConfig(
          unlimitedNameLines = unlimitedNameLines,
          showThumbnails = showVideoThumbnails,
          showSizeChip = showSizeChip,
          showResolutionChip = showResolutionChip,
          showFramerateInResolution = showFramerateInResolution,
          showCodecSupportIndicator = showCodecSupportIndicator,
          showProgressBar = showProgressBar,
          grayscaleWatchedThumbnails = grayscaleWatchedThumbnails,
          showDateChip = showDateChip,
          showUnplayedOldVideoLabel = showUnplayedOldVideoLabel,
          unplayedOldVideoDays = unplayedOldVideoDays,
          showExtensionField = showExtensionField,
          showDurationField = showDurationField,
          centerGridTitles = centerGridTitles,
          thumbnailQuality = thumbnailQuality,
          thumbnailGenerator = thumbnailGenerator,
        )
      }

    // Secure files live outside MediaStore, so duration/resolution aren't available for free
    // like they are for normal library videos; extract them via MediaMetadataRetriever instead.
    var secureVideoMetadata by remember { mutableStateOf<Map<String, MediaInfoOps.VideoMetadata>>(emptyMap()) }
    LaunchedEffect(media) {
      val fileTriples =
        media.map { entity ->
          val file = File(entity.secureFilePath)
          Triple(file, android.net.Uri.fromFile(file), entity.fileName)
        }
      secureVideoMetadata = metadataCacheRepository.getOrExtractMetadataBatch(fileTriples)
    }

    // Convert secure media entities into Video models and sort them
    val secureMediaVideos =
      remember(media, secureVideoMetadata) {
        media.map { entity ->
          val metadata = secureVideoMetadata[entity.secureFilePath]
          entity to
            Video(
              id = entity.id,
              title = entity.fileName,
              displayName = entity.fileName,
              path = entity.secureFilePath,
              uri = android.net.Uri.fromFile(java.io.File(entity.secureFilePath)),
              duration = metadata?.durationMs ?: 0L,
              durationFormatted = metadata?.let { formatDuration(it.durationMs) } ?: "",
              size = entity.fileSize,
              sizeFormatted = MediaUtils.formatFileSize(entity.fileSize),
              dateModified = entity.dateHidden / 1000,
              dateAdded = entity.dateHidden / 1000,
              mimeType = entity.mimeType,
              bucketId = "secure_folder",
              bucketDisplayName = "Secure Folder",
              width = metadata?.width ?: 0,
              height = metadata?.height ?: 0,
              fps = metadata?.fps ?: 0f,
              resolution = metadata?.let { VideoScanUtils.formatResolutionWithFps(it.width, it.height, it.fps) } ?: "--",
              isAudio = entity.mimeType.startsWith("audio/"),
            )
        }
      }

    val sortedSecureMediaVideos =
      remember(secureMediaVideos, videoSortType, videoSortOrder) {
        val sortedVideos = SortUtils.sortVideos(secureMediaVideos.map { it.second }, videoSortType, videoSortOrder)
        val entityByVideoId = secureMediaVideos.associate { it.second.id to it.first }
        sortedVideos.mapNotNull { video ->
          entityByVideoId[video.id]?.let { entity -> entity to video }
        }
      }

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var sortDialogOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(operationResult) {
      operationResult?.let {
        snackbarHostState.showSnackbar(it)
        viewModel.clearOperationResult()
      }
    }

    Scaffold(
      topBar = {
        BrowserTopBar(
          title = stringResource(R.string.secure_folder_title),
          isInSelectionMode = isInSelectionMode,
          selectedCount = selectedIds.size,
          totalCount = media.size,
          onBackClick = {
            if (isInSelectionMode) {
              viewModel.clearSelection()
            } else {
              backstack.popSafely()
            }
          },
          onCancelSelection = { viewModel.clearSelection() },
          onSortClick = { sortDialogOpen = true },
          onSelectAll = { viewModel.selectAll() },
          onInvertSelection = { viewModel.invertSelection() },
          onDeselectAll = { viewModel.clearSelection() },
          onRestoreClick = {
            if (viewModel.preferences.dontAskBeforeRestore.get()) {
              viewModel.restoreSelected()
            } else {
              pendingAction = PendingAction.RESTORE
            }
          },
          onDeleteClick = {
            if (viewModel.preferences.dontAskBeforeDelete.get()) {
              viewModel.deleteSelectedForever()
            } else {
              pendingAction = PendingAction.DELETE
            }
          },
        )
      },
      snackbarHost = {
        SnackbarHost(snackbarHostState) { data ->
          Snackbar(snackbarData = data)
        }
      },
      floatingActionButton = {
        if (!isInSelectionMode) {
          // The mini player stays on screen here, so the FAB must clear it like the browser FABs do.
          ExtendedFloatingActionButton(
            onClick = { backstack.navigateTo(SecureFolderAddFilesScreen) },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.secure_folder_add_files)) },
            modifier = Modifier.padding(bottom = app.gyrolet.mpvrx.ui.browser.fab.FabPlacement.scaffoldBottomPadding()),
          )
        }
      },
    ) { padding ->
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
      ) {
        if (sortedSecureMediaVideos.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
              icon = Icons.RoundedFilled.Lock,
              title = stringResource(R.string.secure_folder_empty_title),
              message = stringResource(R.string.secure_folder_empty_message),
            )
          }
        } else {
          BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val videoGridColumnsPref = if (isLandscape) videoGridColumnsLandscape else videoGridColumnsPortrait
            val contentHorizontalPadding = 8.dp
            val itemSpacing = 2.dp
            val usableWidth = maxWidth - (contentHorizontalPadding * 2) - itemSpacing
            val videoMinWidth = 130.dp
            val dynamicVideos = (usableWidth / videoMinWidth).toInt().coerceAtLeast(1)
            val videoGridColumns =
              if (manualGridColumnsEnabled) {
                val maxSafeVideos = maxOf(dynamicVideos + 3, (usableWidth / 90.dp).toInt()).coerceAtLeast(1)
                videoGridColumnsPref.coerceIn(1, maxSafeVideos)
              } else {
                dynamicVideos
              }

            val thumbWidthDp =
              if (mediaLayoutMode == MediaLayoutMode.GRID) {
                val cellWidth =
                  (maxWidth - contentHorizontalPadding * 2 - itemSpacing * (videoGridColumns - 1)) / videoGridColumns
                (cellWidth - 8.dp).coerceAtLeast(1.dp)
              } else {
                128.dp
              }
            val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
            val aspect = if (mediaLayoutMode == MediaLayoutMode.GRID) 16f / 10f else 16f / 9f
            val thumbHeightPx = (thumbWidthPx / aspect).roundToInt()

            val hasEnoughItems = sortedSecureMediaVideos.size > 10
            val scrollbarAlpha by animateFloatAsState(
              targetValue = if (hasEnoughItems) 1f else 0f,
              animationSpec =
                app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.let {
                  androidx.compose.animation.core.spring(
                    dampingRatio = it.dampingRatio,
                    stiffness = it.stiffness,
                  )
                },
              label = "scrollbarAlpha",
            )

            if (mediaLayoutMode == MediaLayoutMode.GRID) {
              Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                  columns = GridCells.Fixed(videoGridColumns),
                  state = gridState,
                  contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(2.dp),
                  verticalArrangement = Arrangement.spacedBy(2.dp),
                  modifier = Modifier.fillMaxSize(),
                ) {
                  items(sortedSecureMediaVideos, key = { it.first.id }) { (entity, video) ->
                    VideoCard(
                      video = video,
                      isSelected = selectedIds.contains(entity.id),
                      onClick = {
                        if (isInSelectionMode) {
                          viewModel.toggleSelection(entity.id)
                        } else {
                          MediaUtils.playFile(
                            entity.secureFilePath,
                            context,
                            launchSource = "secure_folder",
                            title = entity.fileName,
                          )
                        }
                      },
                      onLongClick = { viewModel.handleLongClick(entity.id) },
                      onThumbClick = {
                        if (isInSelectionMode) {
                          viewModel.toggleSelection(entity.id)
                        } else {
                          MediaUtils.playFile(
                            entity.secureFilePath,
                            context,
                            launchSource = "secure_folder",
                            title = entity.fileName,
                          )
                        }
                      },
                      isGridMode = true,
                      gridColumns = videoGridColumns,
                      thumbnailWidthPx = thumbWidthPx,
                      thumbnailHeightPx = thumbHeightPx,
                      allowThumbnailGeneration = true,
                      uiConfig = videoCardUiConfig,
                    )
                  }
                }

                if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                  ExpressiveScrollBar(
                    gridState = gridState,
                    dragLabelProvider = { index ->
                      fastScrollGlyph(sortedSecureMediaVideos.getOrNull(index)?.second?.displayName)
                    },
                    modifier =
                      Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp, top = 6.dp, bottom = 6.dp)
                        .graphicsLayer { alpha = scrollbarAlpha },
                  )
                }
              }
            } else {
              Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                  state = listState,
                  contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
                  modifier = Modifier.fillMaxSize(),
                ) {
                  items(sortedSecureMediaVideos, key = { it.first.id }) { (entity, video) ->
                    VideoCard(
                      video = video,
                      isSelected = selectedIds.contains(entity.id),
                      onClick = {
                        if (isInSelectionMode) {
                          viewModel.toggleSelection(entity.id)
                        } else {
                          MediaUtils.playFile(
                            entity.secureFilePath,
                            context,
                            launchSource = "secure_folder",
                            title = entity.fileName,
                          )
                        }
                      },
                      onLongClick = { viewModel.handleLongClick(entity.id) },
                      onThumbClick = {
                        if (isInSelectionMode) {
                          viewModel.toggleSelection(entity.id)
                        } else {
                          MediaUtils.playFile(
                            entity.secureFilePath,
                            context,
                            launchSource = "secure_folder",
                            title = entity.fileName,
                          )
                        }
                      },
                      isGridMode = false,
                      allowThumbnailGeneration = true,
                      uiConfig = videoCardUiConfig,
                    )
                  }
                }

                if (hasEnoughItems && scrollbarAlpha > 0.01f) {
                  ExpressiveScrollBar(
                    listState = listState,
                    dragLabelProvider = { index ->
                      fastScrollGlyph(sortedSecureMediaVideos.getOrNull(index)?.second?.displayName)
                    },
                    modifier =
                      Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp, top = 6.dp, bottom = 6.dp)
                        .graphicsLayer { alpha = scrollbarAlpha },
                  )
                }
              }
            }
          }
        }
      }
    }

    // Back handler: clear selection first, then go back
    BackHandler(enabled = isInSelectionMode) {
      viewModel.clearSelection()
    }

    SecureConfirmDialog(
      isOpen = pendingAction == PendingAction.RESTORE,
      title = stringResource(R.string.secure_folder_restore_confirm_title, selectedIds.size),
      subtitle = stringResource(R.string.secure_folder_restore_confirm_subtitle),
      dontAskAgain = viewModel.preferences.dontAskBeforeRestore,
      onConfirm = {
        pendingAction = null
        viewModel.restoreSelected()
      },
      onDismiss = { pendingAction = null },
    )

    SecureConfirmDialog(
      isOpen = pendingAction == PendingAction.DELETE,
      title = stringResource(R.string.secure_folder_delete_confirm_title, selectedIds.size),
      subtitle = stringResource(R.string.secure_folder_delete_confirm_subtitle),
      dontAskAgain = viewModel.preferences.dontAskBeforeDelete,
      deletePaths = media.filter { it.id in selectedIds }.map { it.secureFilePath },
      onConfirm = {
        pendingAction = null
        viewModel.deleteSelectedForever()
      },
      onDismiss = { pendingAction = null },
    )

    SecureFolderProgressDialog(
      isOpen = isBusy,
      progress = operationProgress,
      label = stringResource(R.string.secure_folder_working_on_it),
      onCancel = { viewModel.cancelCurrentOperation() },
    )

    VideoSortDialog(
      isOpen = sortDialogOpen,
      onDismiss = { sortDialogOpen = false },
      sortType = videoSortType,
      sortOrder = videoSortOrder,
      onSortTypeChange = { browserPreferences.videoSortType.set(it) },
      onSortOrderChange = { browserPreferences.videoSortOrder.set(it) },
      isFolderView = true,
      enableViewModeOptions = false,
      enableLayoutModeOptions = true,
    )
  }
}

private enum class PendingAction { RESTORE, DELETE }

private fun formatDuration(durationMs: Long): String {
  if (durationMs <= 0) return "0s"

  val seconds = durationMs / 1000
  val hours = seconds / 3600
  val minutes = (seconds % 3600) / 60
  val secs = seconds % 60

  return when {
    hours > 0 -> String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
    minutes > 0 -> String.format(java.util.Locale.getDefault(), "%d:%02d", minutes, secs)
    else -> "${secs}s"
  }
}

private fun formatResolution(
  width: Int,
  height: Int,
): String {
  return VideoScanUtils.formatResolution(width, height)
}

private fun formatResolutionWithFps(
  width: Int,
  height: Int,
  fps: Float,
): String {
  return VideoScanUtils.formatResolutionWithFps(width, height, fps)
}



