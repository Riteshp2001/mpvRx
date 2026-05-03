package app.gyrolet.mpvrx.ui.browser.cards

import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * Card for displaying M3U/M3U8 playlist items (streaming URLs)
 */
@Composable
fun M3UVideoCard(
  title: String,
  url: String,
  logoUrl: String?,
  groupTitle: String?,
  hasDrm: Boolean,
  hasCustomUserAgent: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  onFavoriteClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isRecentlyPlayed: Boolean = false,
  isFavorite: Boolean = false,
) {
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()
  var thumbnail by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }

  if (showNetworkThumbnails) {
    val density = LocalDensity.current
    val targetThumbnailSize = 128.dp
    val thumbWidthPx = with(density) { targetThumbnailSize.toPx().roundToInt() }
    val thumbHeightPx = (thumbWidthPx / (16f / 9f)).roundToInt()

    val dummyVideo = remember(url) {
      Video(
        id = url.hashCode().toLong(),
        title = title,
        displayName = title,
        path = url,
        uri = android.net.Uri.parse(url),
        duration = 0,
        durationFormatted = "",
        size = 0,
        sizeFormatted = "",
        dateModified = 0,
        dateAdded = 0,
        mimeType = "video/*",
        bucketId = "",
        bucketDisplayName = "",
        width = 0,
        height = 0,
        fps = 0f,
        resolution = ""
      )
    }

    val thumbnailKey = remember(dummyVideo.id, thumbWidthPx, thumbHeightPx) {
      thumbnailRepository.thumbnailKeyForNetworkPath(url, thumbWidthPx, thumbHeightPx)
    }

    LaunchedEffect(thumbnailKey) {
      thumbnailRepository.thumbnailReadyKeys.filter { it == thumbnailKey }.collect {
        thumbnail = thumbnailRepository.getThumbnailFromMemory(
          dummyVideo,
          thumbWidthPx,
          thumbHeightPx
        )
      }
    }

    LaunchedEffect(thumbnailKey) {
      if (thumbnail == null) {
        thumbnail = withContext(Dispatchers.IO) {
          thumbnailRepository.getThumbnailForNetworkPath(url, thumbWidthPx, thumbHeightPx)
        }
      }
    }
  }

  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  val thumbSizeDp = 72.dp

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .background(
            if (isSelected) {
              MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
            } else {
              Color.Transparent
            },
          )
          .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(thumbSizeDp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
              onClick = onClick,
              onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
      ) {
        val currentThumbnail = thumbnail
        if (currentThumbnail != null) {
          androidx.compose.foundation.Image(
            bitmap = currentThumbnail.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        } else if (!logoUrl.isNullOrBlank()) {
          AsyncImage(
            model = logoUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
              .matchParentSize()
              .padding(8.dp),
          )
        } else {
          Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f),
          )
        }
      }
      Spacer(modifier = Modifier.width(16.dp))
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          title,
          style = MaterialTheme.typography.titleSmall,
          color = if (isRecentlyPlayed) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          fontWeight = if (isFavorite) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
          url,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
          horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
          verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
          if (!groupTitle.isNullOrBlank()) {
            M3UMetadataChip(
              text = groupTitle,
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
          if (hasDrm) {
            M3UMetadataChip(
              text = "DRM",
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          if (hasCustomUserAgent) {
            M3UMetadataChip(
              text = "UA",
              containerColor = MaterialTheme.colorScheme.tertiaryContainer,
              contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
          }
          if (isFavorite) {
            M3UMetadataChip(
              text = "Saved",
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }

      if (onFavoriteClick != null) {
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onFavoriteClick) {
          Icon(
            imageVector = Icons.Outlined.Bookmarks,
            contentDescription = if (isFavorite) "Unsave stream" else "Save stream",
            tint = if (isFavorite) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
      }
    }
  }
}

@Composable
private fun M3UMetadataChip(
  text: String,
  containerColor: Color,
  contentColor: Color,
) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    modifier =
      Modifier
        .clip(RoundedCornerShape(999.dp))
        .background(containerColor)
        .padding(horizontal = 8.dp, vertical = 4.dp),
    color = contentColor,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}




