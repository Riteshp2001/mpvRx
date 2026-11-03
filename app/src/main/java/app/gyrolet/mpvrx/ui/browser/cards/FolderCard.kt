/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPageViewPreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.onWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.pow

@androidx.compose.runtime.Immutable
data class FolderCardUiConfig(
  val unlimitedNameLines: Boolean = false,
  val showTotalVideosChip: Boolean = true,
  val showTotalDurationChip: Boolean = true,
  val showTotalSizeChip: Boolean = true,
  val showDateChip: Boolean = false,
  val showFolderPath: Boolean = false,
  val centerGridTitles: Boolean = false,
  val showFolderThumbnails: Boolean = false,
  val thumbnailQuality: app.gyrolet.mpvrx.preferences.ThumbnailQuality = app.gyrolet.mpvrx.preferences.ThumbnailQuality.High,
  val includeAudio: Boolean = false,
  val swipeLeft: VideoSwipeAction = VideoSwipeAction.AddToPlaylist,
  val swipeRight: VideoSwipeAction = VideoSwipeAction.ToggleWatched,
  val thumbnailGenerator: app.gyrolet.mpvrx.preferences.ThumbnailGenerator = app.gyrolet.mpvrx.preferences.ThumbnailGenerator.Auto,
)

/** Hoist this once per screen and pass the result to every card rather than collecting per item. */
@Composable
fun rememberFolderCardUiConfig(viewPreferences: BrowserPageViewPreferences? = null): FolderCardUiConfig {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()

  val unlimitedNameLines by (viewPreferences?.unlimitedNameLines ?: appearancePreferences.unlimitedNameLines).collectAsState()
  val showTotalVideosChip by (viewPreferences?.showItemCount ?: browserPreferences.showTotalVideosChip).collectAsState()
  val showTotalDurationChip by browserPreferences.showTotalDurationChip.collectAsState()
  val showTotalSizeChip by browserPreferences.showTotalSizeChip.collectAsState()
  val showDateChip by (viewPreferences?.showDateChip ?: browserPreferences.showDateChip).collectAsState()
  val showFolderPath by browserPreferences.showFolderPath.collectAsState()
  val centerGridTitles by (viewPreferences?.centerGridTitles ?: browserPreferences.centerGridTitles).collectAsState()
  val showFolderThumbnails by (viewPreferences?.showThumbnails ?: browserPreferences.showFolderThumbnails).collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val thumbnailGenerator by browserPreferences.thumbnailGenerator.collectAsState()
  val includeAudio by browserPreferences.includeAudioBrowser.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()

  return remember(
    unlimitedNameLines,
    showTotalVideosChip,
    showTotalDurationChip,
    showTotalSizeChip,
    showDateChip,
    showFolderPath,
    centerGridTitles,
    showFolderThumbnails,
    thumbnailQuality,
    thumbnailGenerator,
    includeAudio,
    swipeLeft,
    swipeRight,
  ) {
    FolderCardUiConfig(
      unlimitedNameLines = unlimitedNameLines,
      showTotalVideosChip = showTotalVideosChip,
      showTotalDurationChip = showTotalDurationChip,
      showTotalSizeChip = showTotalSizeChip,
      showDateChip = showDateChip,
      showFolderPath = showFolderPath,
      centerGridTitles = centerGridTitles,
      showFolderThumbnails = showFolderThumbnails,
      thumbnailQuality = thumbnailQuality,
      thumbnailGenerator = thumbnailGenerator,
      includeAudio = includeAudio,
      swipeLeft = swipeLeft,
      swipeRight = swipeRight,
    )
  }
}

@Composable
fun FolderCard(
  folder: VideoFolder,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isRecentlyPlayed: Boolean = false,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  onThumbClick: () -> Unit = {},
  showDateModified: Boolean = false,
  customIcon: AppIcon? = null,
  newVideoCount: Int = 0,
  customChipContent: @Composable (() -> Unit)? = null,
  isGridMode: Boolean = false,
  gridRowMinHeight: Dp = 0.dp,
  onGridContentHeightChanged: ((Int) -> Unit)? = null,
  isPinned: Boolean = false,
  onPinClick: (() -> Unit)? = null,
  thumbnail: ImageBitmap? = null,
  isDualPane: Boolean = false,
  isActive: Boolean = false,
  isAudioOnly: Boolean = false,
  onSwipeAction: ((VideoFolder, VideoSwipeAction) -> Unit)? = null,
  placeholderIconSize: Dp? = null,
  viewPreferences: BrowserPageViewPreferences? = null,
  uiConfig: FolderCardUiConfig? = null,
  allowThumbnailGeneration: Boolean = true,
  selectionNeighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
  isWatched: Boolean = false,
) {
  val resolvedUiConfig = uiConfig ?: rememberFolderCardUiConfig(viewPreferences)
  val unlimitedNameLines = resolvedUiConfig.unlimitedNameLines
  val showTotalVideosChip = resolvedUiConfig.showTotalVideosChip
  val showTotalDurationChip = resolvedUiConfig.showTotalDurationChip
  val showTotalSizeChip = resolvedUiConfig.showTotalSizeChip
  val showDateChip = resolvedUiConfig.showDateChip
  val showFolderPath = resolvedUiConfig.showFolderPath
  val centerGridTitles = resolvedUiConfig.centerGridTitles
  val showFolderThumbnails = resolvedUiConfig.showFolderThumbnails
  val thumbnailQuality = resolvedUiConfig.thumbnailQuality
  val thumbnailGenerator = resolvedUiConfig.thumbnailGenerator
  val includeAudio = resolvedUiConfig.includeAudio
  val swipeLeft = resolvedUiConfig.swipeLeft
  val swipeRight = resolvedUiConfig.swipeRight
  val context = androidx.compose.ui.platform.LocalContext.current
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val thumbnailCacheRevision by thumbnailRepository.cacheRevision.collectAsState()
  var thumbnailSize by remember { mutableStateOf(IntSize.Zero) }
  var folderThumbnail by remember(folder.bucketId, thumbnailGenerator, thumbnailCacheRevision) { mutableStateOf<android.graphics.Bitmap?>(null) }
  var folderPreviewFrames by remember(folder.bucketId, thumbnailGenerator, thumbnailCacheRevision) { mutableStateOf<List<android.graphics.Bitmap?>>(emptyList()) }
  // The available tile width, rather than device class alone, determines whether
  // four video previews remain readable at the current grid column count.
  val showPreviewCollage = with(LocalDensity.current) { thumbnailSize.width >= 148.dp.roundToPx() }

  LaunchedEffect(
    folder.bucketId,
    folder.path,
    showFolderThumbnails,
    thumbnailQuality,
    thumbnailGenerator,
    thumbnailCacheRevision,
    isGridMode,
    thumbnailSize,
    showPreviewCollage,
    allowThumbnailGeneration,
  ) {
    if (folder.path.isNotBlank() && isGridMode && showFolderThumbnails && thumbnailSize.width > 0 && thumbnailSize.height > 0) {
      delay(350L)
      withContext(Dispatchers.IO) {
        // On a fresh install there is nothing to reuse. Avoid querying every folder
        // from the Home grid just to discover that the thumbnail cache is empty.
        if (!allowThumbnailGeneration && !thumbnailRepository.hasLocalCachedThumbnails()) {
          return@withContext
        }
        val videos =
          app.gyrolet.mpvrx.repository.MediaFileRepository
            .getVideosInFolder(context, folder.bucketId)
        if (videos.isNotEmpty()) {
          // On narrow cells keep one full cover instead of an unreadable 4-tile preview.
          val preview = videos.take(if (showPreviewCollage) 4 else 1)
          val columns = if (preview.size > 1) 2 else 1
          val cellWidth = (thumbnailSize.width / columns).coerceAtLeast(1)
          val cellHeight = (thumbnailSize.height / columns).coerceAtLeast(1)
          val bitmaps = preview.map { video ->
            if (allowThumbnailGeneration) {
              thumbnailRepository.getThumbnail(video, cellWidth, cellHeight)
            } else {
              thumbnailRepository.getCachedThumbnail(video, cellWidth, cellHeight)
            }
          }
          withContext(Dispatchers.Main) {
            folderPreviewFrames = bitmaps
            folderThumbnail = bitmaps.firstOrNull()
          }
        } else {
          withContext(Dispatchers.Main) {
            folderPreviewFrames = emptyList()
            folderThumbnail = null
          }
        }
      }
    } else if (!showFolderThumbnails) {
      folderThumbnail = null
      folderPreviewFrames = emptyList()
    }
  }

  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2
  val selectionContainerColor = animatedSelectionColor(isSelected)
  val showSelectionBadge = isSelected || selectionContainerColor.alpha > 0.001f

  // Remove the redundant folder name from the path
  val parentPath = folder.path.substringBeforeLast("/", folder.path)

  @Composable
  fun PinnedFolderBadge(modifier: Modifier = Modifier) {
    Surface(
      shape = AppShapeScale.full,
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
      contentColor = MaterialTheme.colorScheme.onPrimary,
      shadowElevation = 3.dp,
      modifier = modifier.rotate(-18f),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.PushPin,
        contentDescription =
          androidx.compose.ui.res
            .stringResource(app.gyrolet.mpvrx.R.string.ui_pinned_folder),
        modifier =
          Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .size(12.dp),
      )
    }
  }

  val cardShape = rememberMediaSelectionShape(isSelected, isGridMode, selectionNeighbors)
  // Derive row height from actual measured labels and chips instead of a fixed footer.

  // Scoped document-tree folders can be swiped for playback, playlists and watched state,
  // but Delete must not target a directory in the Play Store flavor.
  val scopedFolder = app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY
  VideoSwipeSurface(
    identity = folder.path,
    leftAction = if (scopedFolder && swipeLeft == VideoSwipeAction.Delete) VideoSwipeAction.None else swipeLeft,
    rightAction = if (scopedFolder && swipeRight == VideoSwipeAction.Delete) VideoSwipeAction.None else swipeRight,
    isWatched = null,
    enabled = !isGridMode && !isSelected &&
      (folder.path.startsWith('/') ||
        (app.gyrolet.mpvrx.BuildConfig.SCOPED_STORAGE_ONLY &&
          app.gyrolet.mpvrx.utils.media.ScopedHiddenFolderScanner.isScopedFolder(folder.bucketId))),
    onAction = onSwipeAction?.let { action -> { swipe -> action(folder, swipe) } },
    modifier =
      modifier
        .fillMaxWidth()
        .then(if (isGridMode && gridRowMinHeight > 0.dp) Modifier.heightIn(min = gridRowMinHeight) else Modifier)
        .tvFocusMemory("folder:${folder.bucketId}:${folder.path}")
        .tvFocusHighlight(cardShape, focusedScale = 1.03f)
        .clip(cardShape)
                .semantics { selected = isSelected }
        .tvContextMenu(onLongClick)
        .mediaCardClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    // Size the actual Card content, not just the swipe wrapper's outer box; otherwise
    // the selected tonal background stops above a taller neighbor in the same row.
    Box(modifier = if (isGridMode) Modifier.fillMaxWidth().heightIn(min = gridRowMinHeight) else Modifier.fillMaxWidth()) {
      Box(
        modifier =
          Modifier
            .matchParentSize()
            .clip(cardShape)
            .background(selectionContainerColor),
      )

      if (isGridMode) {
        val horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start

        // GRID LAYOUT - Vertical arrangement
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 6.dp)
              .onSizeChanged { size -> onGridContentHeightChanged?.invoke(size.height) },
          horizontalAlignment = horizontalAlignment,
        ) {
          val aspect = 20f / 17f

          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .onSizeChanged { thumbnailSize = it }
                .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.03f)
                .mediaCardClickable(
                  onClick = onThumbClick,
                  onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
          ) {
            val folderImageBitmap = remember(folderThumbnail) { folderThumbnail?.asImageBitmap() }
            val resolvedThumbnail = thumbnail ?: folderImageBitmap.takeIf { showFolderThumbnails }
            val showMosaic = thumbnail == null && showFolderThumbnails && folderPreviewFrames.size > 1 &&
              folderPreviewFrames.any { it != null }
            val previewReveal =
              rememberThumbnailReveal(
                showMosaic || resolvedThumbnail != null,
                folder.bucketId,
                thumbnailGenerator,
                thumbnailCacheRevision,
              )
            if (showMosaic) {
              FolderPreviewMosaic(
                folderPreviewFrames,
                Modifier.matchParentSize().clip(AppShapeScale.medium).thumbnailReveal(previewReveal),
              )
            } else if (resolvedThumbnail != null) {
              androidx.compose.foundation.Image(
                bitmap = resolvedThumbnail,
                contentDescription = null,
                modifier = Modifier.matchParentSize().clip(AppShapeScale.medium).thumbnailReveal(previewReveal),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                customIcon ?: Icons.RoundedFilled.Folder,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
                modifier = placeholderIconSize?.let { Modifier.size(it) } ?: Modifier.fillMaxWidth().aspectRatio(aspect),
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
              )
            }

            if (newVideoCount > 0 && !showSelectionBadge) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .cardOverlay(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style =
                    MaterialTheme.typography.labelSmall.copy(
                      fontWeight = FontWeight.Bold,
                    ),
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
            }

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))

            if (isWatched && !showSelectionBadge) {
              WatchedFolderBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }

            if (isPinned) {
              PinnedFolderBadge(
                modifier =
                  Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
              )
            }

            if (showTotalDurationChip && folder.totalDuration > 0) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                      .cardOverlay()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = formatDuration(folder.totalDuration),
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White,
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Text(
            folder.name,
            style = MaterialTheme.typography.titleMedium,
            color =
              when {
                isActive -> MaterialTheme.colorScheme.primary
                isRecentlyPlayed -> MaterialTheme.colorScheme.tertiary
                isWatched -> MaterialTheme.colorScheme.onSurface.onWallpaper().copy(alpha = 0.6f)
                else -> MaterialTheme.colorScheme.onSurface.onWallpaper()
              },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign =
              if (centerGridTitles) {
                androidx.compose.ui.text.style.TextAlign.Center
              } else {
                androidx.compose.ui.text.style.TextAlign.Start
              },
          )

          if (showTotalVideosChip && folder.videoCount > 0) {
            Text(
              androidx.compose.ui.res.pluralStringResource(
                when {
                  isAudioOnly -> R.plurals.folder_song_count
                  includeAudio -> R.plurals.folder_media_item_count
                  else -> R.plurals.folder_video_count
                },
                folder.videoCount,
                folder.videoCount,
              ),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.onWallpaper(),
            )
          }
          if (customChipContent != null) {
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              customChipContent()
            }
          }
        }
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .size(72.dp)
                .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.03f)
                .mediaCardClickable(
                  onClick = onThumbClick,
                  onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
          ) {
            if (thumbnail != null) {
              androidx.compose.foundation.Image(
                bitmap = thumbnail,
                contentDescription = null,
                modifier = Modifier.matchParentSize().clip(AppShapeScale.medium),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                customIcon ?: Icons.RoundedFilled.Folder,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_folder),
                modifier = placeholderIconSize?.let { Modifier.size(it) } ?: Modifier.matchParentSize(),
                tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
              )
            }

            // Show new video count badge if folder contains new videos
            if (newVideoCount > 0 && !showSelectionBadge) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .cardOverlay(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style =
                    MaterialTheme.typography.labelSmall.copy(
                      fontWeight = FontWeight.Bold,
                    ),
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
            }

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(4.dp))

            if (isWatched && !showSelectionBadge) {
              WatchedFolderBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
            }

            if (isPinned) {
              PinnedFolderBadge(
                modifier =
                  Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp),
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(
            modifier = Modifier.weight(1f),
          ) {
            Text(
              folder.name,
              style = MaterialTheme.typography.titleMedium,
              color =
                when {
                  isActive -> MaterialTheme.colorScheme.primary
                  isRecentlyPlayed -> MaterialTheme.colorScheme.tertiary
                  isWatched -> MaterialTheme.colorScheme.onSurface.onWallpaper().copy(alpha = 0.6f)
                  else -> MaterialTheme.colorScheme.onSurface.onWallpaper()
                },
              maxLines = maxLines,
              overflow = TextOverflow.Ellipsis,
            )
            if (showFolderPath && parentPath.isNotEmpty()) {
              Text(
                parentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.onWallpaper(),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(modifier = Modifier.height(4.dp))
            } else {
              Spacer(modifier = Modifier.height(4.dp))
            }
            FlowRow(
              horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement
                  .spacedBy(4.dp),
              verticalArrangement =
                androidx.compose.foundation.layout.Arrangement
                  .spacedBy(4.dp),
            ) {
              // Render custom chip content first if provided
              var hasChip = false
              if (customChipContent != null) {
                customChipContent()
                hasChip = true
              }

              // Hide chips at storage root level (when videoCount is 0)
              if (showTotalVideosChip && folder.videoCount > 0) {
                Text(
                  androidx.compose.ui.res.pluralStringResource(
                    when {
                      isAudioOnly -> R.plurals.folder_song_count
                      includeAudio -> R.plurals.folder_media_item_count
                      else -> R.plurals.folder_video_count
                    },
                    folder.videoCount,
                    folder.videoCount,
                  ),
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapeScale.small,
                      ).padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                hasChip = true
              }

              if (showTotalSizeChip && folder.totalSize > 0) {
                Text(
                  formatFileSize(folder.totalSize),
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapeScale.small,
                      ).padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                hasChip = true
              }

              if (showTotalDurationChip && folder.totalDuration > 0) {
                Text(
                  formatDuration(folder.totalDuration),
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapeScale.small,
                      ).padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                hasChip = true
              }

              if (showDateChip && folder.lastModified > 0) {
                Text(
                  formatDate(folder.lastModified),
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapeScale.small,
                      ).padding(horizontal = 8.dp, vertical = 4.dp),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun formatDuration(durationMs: Long): String {
  val seconds = durationMs / 1000
  val hours = seconds / 3600
  val minutes = (seconds % 3600) / 60
  val secs = seconds % 60

  return when {
    hours > 0 -> "${hours}h ${minutes}m"
    minutes > 0 -> "${minutes}m"
    else -> "${secs}s"
  }
}

@Composable
private fun WatchedFolderBadge(modifier: Modifier = Modifier) {
  Icon(
    Icons.RoundedFilled.Check,
    contentDescription = androidx.compose.ui.res.stringResource(R.string.video_label_watched),
    modifier = modifier.cardOverlay().padding(2.dp).size(16.dp),
    tint = Color.White,
  )
}

private fun formatFileSize(bytes: Long): String {
  if (bytes <= 0) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB", "TB")
  val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
  val value = bytes / 1024.0.pow(digitGroups.toDouble())
  return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[digitGroups])
}

// Hoisted because a card formats a date on every recomposition and SimpleDateFormat construction
// parses the pattern and clones a Calendar each time.
private val FOLDER_DATE_FORMATTER: java.time.format.DateTimeFormatter =
  java.time.format.DateTimeFormatter
    .ofPattern("MMM dd, yyyy")
    .withZone(java.time.ZoneId.systemDefault())

private fun formatDate(timestampSeconds: Long): String =
  FOLDER_DATE_FORMATTER.format(java.time.Instant.ofEpochSecond(timestampSeconds))


// The collage uses at most four images and responds to the actual display cell width.
// Two frames divide the cover horizontally; three/four use connected 2x2 blocks.
@Composable
private fun FolderPreviewMosaic(
  frames: List<android.graphics.Bitmap?>,
  modifier: Modifier = Modifier,
) {
  val visible = frames.take(4)
  if (visible.isEmpty()) return
  val columns = if (visible.size == 1) 1 else 2
  val rows = (visible.size + columns - 1) / columns
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    repeat(rows) { row ->
      Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        val first = row * columns
        val last = minOf(first + columns, visible.size)
        for (index in first until last) {
          val bitmap = visible[index]
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .clip(rememberConnectedShape(index, visible.size, ConnectedLayout.Grid, columns))
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
          ) {
            if (bitmap != null) {
              val image = remember(bitmap) { bitmap.asImageBitmap() }
              androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                Icons.RoundedFilled.Folder,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
              )
            }
          }
        }
      }
    }
  }
}
