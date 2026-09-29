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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.onWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.pow

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
  isPinned: Boolean = false,
  onPinClick: (() -> Unit)? = null,
  thumbnail: ImageBitmap? = null,
  isDualPane: Boolean = false,
  isActive: Boolean = false,
  isAudioOnly: Boolean = false,
  onSwipeAction: ((VideoFolder, VideoSwipeAction) -> Unit)? = null,
  placeholderIconSize: Dp? = null,
  viewPreferences: BrowserPageViewPreferences? = null,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val unlimitedNameLines by
    (viewPreferences?.unlimitedNameLines ?: appearancePreferences.unlimitedNameLines).collectAsState()
  val showTotalVideosChip by (viewPreferences?.showItemCount ?: browserPreferences.showTotalVideosChip).collectAsState()
  val showTotalDurationChip by browserPreferences.showTotalDurationChip.collectAsState()
  val showTotalSizeChip by browserPreferences.showTotalSizeChip.collectAsState()
  val showDateChip by (viewPreferences?.showDateChip ?: browserPreferences.showDateChip).collectAsState()
  val showFolderPath by browserPreferences.showFolderPath.collectAsState()
  val centerGridTitles by (viewPreferences?.centerGridTitles ?: browserPreferences.centerGridTitles).collectAsState()
  val showFolderThumbnails by
    (viewPreferences?.showThumbnails ?: browserPreferences.showFolderThumbnails).collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val includeAudio by browserPreferences.includeAudioBrowser.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  var thumbnailSize by remember { mutableStateOf(IntSize.Zero) }
  var folderThumbnail by remember(folder.bucketId) { mutableStateOf<android.graphics.Bitmap?>(null) }

  LaunchedEffect(
    folder.bucketId,
    folder.path,
    showFolderThumbnails,
    thumbnailQuality,
    isGridMode,
    thumbnailSize,
  ) {
    if (folder.path.isNotBlank() && isGridMode && showFolderThumbnails && thumbnailSize.width > 0 && thumbnailSize.height > 0) {
      withContext(Dispatchers.IO) {
        val videos =
          app.gyrolet.mpvrx.repository.MediaFileRepository
            .getVideosInFolder(context, folder.bucketId)
        if (videos.isNotEmpty()) {
          val bmp =
            thumbnailRepository.getFolderThumbnail(folder.bucketId, videos, thumbnailSize.width, thumbnailSize.height)
          withContext(Dispatchers.Main) {
            folderThumbnail = bmp
          }
        }
      }
    } else {
      folderThumbnail = null
    }
  }

  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2
  val selectionInset = 2.dp
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

  val cardShape = AppShapeScale.large

  VideoSwipeSurface(
    identity = folder.path,
    leftAction = swipeLeft,
    rightAction = swipeRight,
    isWatched = null,
    enabled = !isGridMode && !isSelected && folder.path.startsWith('/'),
    onAction = onSwipeAction?.let { action -> { swipe -> action(folder, swipe) } },
    modifier =
      modifier
        .fillMaxWidth()
        .clip(cardShape)
        .semantics { selected = isSelected }
        .tvContextMenu(onLongClick)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier =
          Modifier
            .matchParentSize()
            .padding(selectionInset)
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
              .padding(horizontal = 4.dp, vertical = 6.dp),
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
                .combinedClickable(
                  onClick = onThumbClick,
                  onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
          ) {
            val folderImageBitmap = remember(folderThumbnail) { folderThumbnail?.asImageBitmap() }
            val resolvedThumbnail = thumbnail ?: folderImageBitmap.takeIf { showFolderThumbnails }
            if (resolvedThumbnail != null) {
              androidx.compose.foundation.Image(
                bitmap = resolvedThumbnail,
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
                      .cardOverlay(containerColor = Color(0xFFD32F2F))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style =
                    MaterialTheme.typography.labelSmall.copy(
                      fontWeight = FontWeight.Bold,
                    ),
                  color = Color.White,
                )
              }
            }

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))

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
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .size(72.dp)
                .tvFocusHighlight(AppShapeScale.medium, focusedScale = 1.03f)
                .combinedClickable(
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
                      .cardOverlay(containerColor = Color(0xFFD32F2F))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style =
                    MaterialTheme.typography.labelSmall.copy(
                      fontWeight = FontWeight.Bold,
                    ),
                  color = Color.White,
                )
              }
            }

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(4.dp))

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
