package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
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
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showTotalVideosChip by browserPreferences.showTotalVideosChip.collectAsState()
  val showTotalDurationChip by browserPreferences.showTotalDurationChip.collectAsState()
  val showTotalSizeChip by browserPreferences.showTotalSizeChip.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showFolderPath by browserPreferences.showFolderPath.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val showFolderThumbnails by browserPreferences.showFolderThumbnails.collectAsState()
  val includeAudio by browserPreferences.includeAudioBrowser.collectAsState()
  val manualGridColumnsEnabled by browserPreferences.manualGridColumnsEnabled.collectAsState()
  val folderGridColumnsPortrait by browserPreferences.folderGridColumnsPortrait.collectAsState()
  val folderGridColumnsLandscape by browserPreferences.folderGridColumnsLandscape.collectAsState()
  val context = androidx.compose.ui.platform.LocalContext.current
  val density = LocalDensity.current
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  var folderThumbnail by remember(folder.bucketId) { mutableStateOf<android.graphics.Bitmap?>(null) }

  LaunchedEffect(folder.bucketId, showFolderThumbnails, isGridMode, manualGridColumnsEnabled, folderGridColumnsPortrait, folderGridColumnsLandscape, isDualPane) {
    if (isGridMode && showFolderThumbnails) {
      withContext(Dispatchers.IO) {
        val videos = app.gyrolet.mpvrx.repository.MediaFileRepository.getVideosInFolder(context, folder.bucketId)
        if (videos.isNotEmpty()) {
          val configuration = context.resources.configuration
          val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
          val screenWidthDp = if (isDualPane) configuration.screenWidthDp.dp * 0.4f else configuration.screenWidthDp.dp
          val contentHorizontalPadding = 8.dp
          val itemSpacing = 2.dp
          val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing
          val folderMinWidth = 100.dp
          val folderGridColumnsPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
          val folderGridColumns = if (manualGridColumnsEnabled) {
            folderGridColumnsPref.coerceAtLeast(1)
          } else {
            (usableWidth / folderMinWidth).toInt().coerceAtLeast(1)
          }
          val horizontalPadding = 32.dp
          val spacing = 8.dp
          val thumbWidthDp = if (folderGridColumns > 1) {
            val totalSpacing = spacing * (folderGridColumns - 1)
            ((screenWidthDp - horizontalPadding - totalSpacing) / folderGridColumns).coerceAtLeast(120.dp)
          } else {
            (screenWidthDp - horizontalPadding).coerceAtLeast(160.dp)
          }
          val aspect = 16f / 9f
          val thumbWidthPx = with(density) { thumbWidthDp.roundToPx() }
          val thumbHeightPx = (thumbWidthPx / aspect).toInt()

          val bmp = thumbnailRepository.getFolderThumbnail(folder.bucketId, videos, thumbWidthPx, thumbHeightPx)
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
  val selectionContainerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
    } else if (isActive) {
      MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    } else {
      Color.Transparent
    }

  // Remove the redundant folder name from the path
  val parentPath = folder.path.substringBeforeLast("/", folder.path)

  @Composable
  fun PinnedFolderBadge(
    modifier: Modifier = Modifier,
  ) {
    Surface(
      shape = AppShapeScale.full,
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
      contentColor = MaterialTheme.colorScheme.onPrimary,
      modifier = modifier.rotate(-18f),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.PushPin,
        contentDescription = "Pinned folder",
        modifier =
          Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .size(12.dp),
      )
    }
  }

  val cardShape = AppShapeScale.large

  Card(
    modifier = modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick,
      ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      if (isSelected || isActive) {
        Box(
          modifier =
            Modifier
              .matchParentSize()
              .padding(selectionInset)
              .clip(cardShape)
              .background(selectionContainerColor),
        )
      }

      if (isGridMode) {
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val screenWidthDp = if (isDualPane) LocalConfiguration.current.screenWidthDp.dp * 0.4f else LocalConfiguration.current.screenWidthDp.dp
        val contentHorizontalPadding = 8.dp
        val itemSpacing = 2.dp
        val usableWidth = screenWidthDp - (contentHorizontalPadding * 2) - itemSpacing
        val folderMinWidth = 100.dp
        val folderGridColumnsPref = if (isLandscape) folderGridColumnsLandscape else folderGridColumnsPortrait
        val folderGridColumns = if (manualGridColumnsEnabled) {
          folderGridColumnsPref.coerceAtLeast(1)
        } else {
          (usableWidth / folderMinWidth).toInt().coerceAtLeast(1)
        }
        val isSingleColumn = folderGridColumns == 1

        val horizontalAlignment = if (isSingleColumn) {
          Alignment.Start
        } else {
          if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start
        }

        // GRID LAYOUT - Vertical arrangement
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
          horizontalAlignment = horizontalAlignment,
        ) {
          val horizontalPadding = 32.dp
          val spacing = 8.dp

          val thumbWidthDp = if (folderGridColumns > 1) {
            // (screen - padding - total spacing) / columns
            val totalSpacing = spacing * (folderGridColumns - 1)
            ((screenWidthDp - horizontalPadding - totalSpacing) / folderGridColumns).coerceAtLeast(120.dp)
          } else {
            // single column fallback
            (screenWidthDp - horizontalPadding).coerceAtLeast(160.dp)
          }
          val aspect = 16f / 9f
          val thumbHeightDp = thumbWidthDp / aspect

          Box(
            modifier = (if (isSingleColumn) {
              Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
            } else {
              Modifier
                .width(thumbWidthDp)
                .height(thumbHeightDp)
            })
              .clip(AppShapeScale.medium)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .combinedClickable(
                onClick = onThumbClick,
                onLongClick = onLongClick,
              ),
            contentAlignment = Alignment.Center,
          ) {
            val resolvedThumbnail = if (showFolderThumbnails) {
              thumbnail ?: folderThumbnail?.asImageBitmap()
            } else null
            if (resolvedThumbnail != null) {
              androidx.compose.foundation.Image(
                bitmap = resolvedThumbnail,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                customIcon ?: Icons.RoundedFilled.Folder,
                contentDescription = "Folder",
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }

            if (newVideoCount > 0) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(AppShapeScale.extraSmall)
                    .background(Color(0xFFD32F2F))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                  ),
                  color = Color.White,
                )
              }
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
                modifier = Modifier
                  .align(Alignment.BottomEnd)
                  .padding(6.dp)
                  .clip(AppShapeScale.extraSmall)
                  .background(Color.Black.copy(alpha = 0.65f))
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

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            folder.name,
            style = if (isSingleColumn) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = if (isRecentlyPlayed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isSingleColumn) androidx.compose.ui.text.style.TextAlign.Start else (if (centerGridTitles) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start),
          )

          if (showTotalVideosChip && folder.videoCount > 0) {
            Text(
              if (includeAudio) {
                if (folder.videoCount == 1) "1 Media item" else "${folder.videoCount} Media items"
              } else if (folder.videoCount == 1) {
                "1 Video"
              } else {
                "${folder.videoCount} Videos"
              },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme. onSurfaceVariant,
            )
          }
        }
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .size(64.dp)
                .clip(AppShapeScale.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                customIcon ?: Icons.RoundedFilled.Folder,
                contentDescription = "Folder",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }

            // Show new video count badge if folder contains new videos
            if (newVideoCount > 0) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(AppShapeScale.extraSmall)
                    .background(Color(0xFFD32F2F)) // Warning red color
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = newVideoCount.toString(),
                  style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                  ),
                  color = Color.White,
                )
              }
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
          Spacer(modifier = Modifier.width(16.dp))
          Column(
            modifier = Modifier.weight(1f),
          ) {
            Text(
              folder.name,
              style = MaterialTheme.typography.titleMedium,
              color = if (isRecentlyPlayed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
              maxLines = maxLines,
              overflow = TextOverflow.Ellipsis,
            )
            if (showFolderPath && parentPath.isNotEmpty()) {
              Text(
                parentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(modifier = Modifier.height(4.dp))
            } else {
              Spacer(modifier = Modifier.height(4.dp))
            }
            FlowRow(
              horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
              verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
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
                  if (includeAudio) {
                    if (folder.videoCount == 1) "1 Media item" else "${folder.videoCount} Media items"
                  } else if (folder.videoCount == 1) {
                    "1 Video"
                  } else {
                    "${folder.videoCount} Videos"
                  },
                  style = MaterialTheme.typography.labelSmall,
                  modifier =
                    Modifier
                      .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        AppShapeScale.small,
                      )
                      .padding(horizontal = 8.dp, vertical = 4.dp),
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
                      )
                      .padding(horizontal = 8.dp, vertical = 4.dp),
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
                      )
                      .padding(horizontal = 8.dp, vertical = 4.dp),
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
                      )
                      .padding(horizontal = 8.dp, vertical = 4.dp),
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

private fun formatDate(timestampSeconds: Long): String {
  val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
  return sdf.format(java.util.Date(timestampSeconds * 1000))
}

