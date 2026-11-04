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
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.network.NetworkPlaybackUri
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.browser.playlist.playlistSourceLocation
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
internal fun PlaylistBookmarkButton(
  isFavorite: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  androidx.compose.material3.FilledTonalIconToggleButton(
    checked = isFavorite,
    onCheckedChange = { onToggle() },
    modifier = modifier.size(48.dp),
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.Bookmarks,
      contentDescription = stringResource(if (isFavorite) R.string.audiobook_delete_bookmark else R.string.audiobook_add_bookmark),
    )
  }
}

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
  selectionNeighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
  isRecentlyPlayed: Boolean = false,
  isFavorite: Boolean = false,
  video: Video? = null,
  /** Marks a playlist entry whose backing source is not currently connected. */
  showSourceWarning: Boolean = false,
  /**
   * Replaces the raw [url] line with a source badge plus [sourceSubtitle]. Left null for M3U
   * entries, whose stored URL is the meaningful thing to show.
   */
  sourceLabel: String? = null,
  sourceColor: Color? = null,
  sourceSubtitle: String? = null,
  isGridMode: Boolean = false,
  showLocation: Boolean = true,
  showCategory: Boolean = true,
  showStreamDetails: Boolean = true,
  onThumbClick: () -> Unit = onClick,
  uiConfig: VideoCardUiConfig? = null,
  listThumbnailWidth: Dp = 128.dp,
  showSubtitleIndicator: Boolean = false,
  progressPercentage: Float? = null,
) {
  val displayConfig = uiConfig ?: rememberVideoCardUiConfig()
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()
  var thumbnail by remember(url, logoUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
  var thumbnailSize by remember(url, isGridMode) { mutableStateOf(IntSize.Zero) }
  val networkReference = remember(url) { NetworkPlaybackUri.parse(url) }
  val isYouTubeArtwork =
    remember(logoUrl) {
      val host = runCatching { android.net.Uri.parse(logoUrl).host.orEmpty().lowercase() }.getOrDefault("")
      host == "i.ytimg.com" || host.endsWith(".ytimg.com")
    }

  val isNetwork =
    remember(url, networkReference) {
      networkReference != null ||
        url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("rtmp://", ignoreCase = true) ||
        url.startsWith("rtsp://", ignoreCase = true) ||
        url.startsWith("ftp://", ignoreCase = true) ||
        url.startsWith("sftp://", ignoreCase = true) ||
        url.startsWith("smb://", ignoreCase = true)
    }

  if (displayConfig.showThumbnails && logoUrl.isNullOrBlank() && !hasDrm &&
    thumbnailSize.width > 0 && thumbnailSize.height > 0 && (!isNetwork || showNetworkThumbnails)
  ) {
    val thumbWidthPx = thumbnailSize.width
    val thumbHeightPx = thumbnailSize.height

    val actualVideo =
      remember(video, url) {
        video ?: Video(
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
          resolution = "",
        )
      }

    val thumbnailKey =
      remember(actualVideo, url, thumbWidthPx, thumbHeightPx, isNetwork, networkReference) {
        if (networkReference != null) {
          "network-m3u|${networkReference.connectionId}|${networkReference.path.value}|$thumbWidthPx|$thumbHeightPx"
        } else if (isNetwork) {
          thumbnailRepository.thumbnailKeyForNetworkPath(url, thumbWidthPx, thumbHeightPx)
        } else {
          thumbnailRepository.thumbnailKey(actualVideo, thumbWidthPx, thumbHeightPx)
        }
      }

    LaunchedEffect(thumbnailKey) {
      thumbnailRepository.thumbnailReadyKeys.filter { it == thumbnailKey }.collect {
        thumbnail =
          if (networkReference != null) {
            thumbnailRepository.getThumbnailForNetworkSource(
              connectionId = networkReference.connectionId,
              path = networkReference.path.value,
              widthPx = thumbWidthPx,
              heightPx = thumbHeightPx,
            )
          } else if (isNetwork) {
            thumbnailRepository.getThumbnailForNetworkPath(url, thumbWidthPx, thumbHeightPx)
          } else {
            thumbnailRepository.getThumbnailFromMemory(
              actualVideo,
              thumbWidthPx,
              thumbHeightPx,
            )
          }
      }
    }

    LaunchedEffect(thumbnailKey) {
      thumbnail =
        withContext(Dispatchers.IO) {
          if (networkReference != null) {
            thumbnailRepository.getThumbnailForNetworkSource(
              connectionId = networkReference.connectionId,
              path = networkReference.path.value,
              widthPx = thumbWidthPx,
              heightPx = thumbHeightPx,
            )
          } else if (isNetwork) {
            thumbnailRepository.getThumbnailForNetworkPath(url, thumbWidthPx, thumbHeightPx)
          } else {
            thumbnailRepository.getThumbnail(actualVideo, thumbWidthPx, thumbHeightPx)
          }
        }
    }
  }

  val maxLines = if (displayConfig.unlimitedNameLines) Int.MAX_VALUE else 2
  val displayTitle =
    if (sourceLabel != null && !displayConfig.showExtensionField) {
      if (video?.isAudio == true && video.title.isNotBlank()) video.title else FileTypeUtils.stripExtension(title)
    } else {
      title
    }
  val location = sourceSubtitle?.takeIf(String::isNotBlank) ?: playlistSourceLocation(url)

  val thumbnailWidth = listThumbnailWidth
  val cardShape = rememberMediaSelectionShape(isSelected, isGridMode, selectionNeighbors)
  val selectionColor = animatedSelectionColor(isSelected)

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(cardShape, focusedScale = 1.03f)
        .clip(cardShape)
        .tvContextMenu(onLongClick)
        .mediaCardClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(modifier = Modifier.matchParentSize().clip(cardShape).background(selectionColor))

      FlowRow(
        // Matches VideoCard's list layout exactly (8dp inset, 12dp thumbnail gap) so a mixed
        // playlist's thumbnails and text columns line up across both card types.
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        maxItemsInEachRow = if (isGridMode) 1 else Int.MAX_VALUE,
        verticalArrangement = Arrangement.spacedBy(if (isGridMode) 8.dp else 0.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
      if (displayConfig.showThumbnails) {
      Box(
        modifier =
          Modifier
            .then(if (isGridMode) Modifier.fillMaxWidth() else Modifier.width(thumbnailWidth))
            .aspectRatio(if (video?.isAudio == true) 1f else 16f / 9f)
            .onSizeChanged { thumbnailSize = it }
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .mediaCardClickable(
              onClick = onThumbClick,
              onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
      ) {
        val currentImageBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }
        if (currentImageBitmap != null) {
          androidx.compose.foundation.Image(
            bitmap = currentImageBitmap,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
          )
        } else if (!logoUrl.isNullOrBlank()) {
          RemoteImage(
            url = logoUrl,
            contentDescription = null,
            contentScale = if (isYouTubeArtwork) ContentScale.Crop else ContentScale.Fit,
            modifier =
              if (isYouTubeArtwork) {
                Modifier.matchParentSize()
              } else {
                Modifier
                  .matchParentSize()
                  .padding(8.dp)
              },
          )
        } else {
          Icon(
            Icons.RoundedFilled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f),
          )
        }

        if (displayConfig.showProgressBar && progressPercentage != null) {
          Box(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
              .background(MaterialTheme.colorScheme.surfaceVariant),
          ) {
            Box(
              modifier = Modifier.fillMaxWidth(progressPercentage.coerceIn(0f, 1f)).height(3.dp)
                .background(MaterialTheme.colorScheme.primary),
            )
          }
        }

        if (isSelected) {
          Box(
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(24.dp)
              .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onPrimary,
            )
          }
        }

        if (showSourceWarning) {
          Box(
            modifier =
              Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(SourceWarningAmber)
                .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
          )
        }
      }
      if (!isGridMode) Spacer(modifier = Modifier.width(12.dp))
      }
      Column(
        modifier = if (isGridMode) Modifier.fillMaxWidth() else Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          if (isSelected && !displayConfig.showThumbnails) {
            Icon(Icons.RoundedFilled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
          }
        Text(
          displayTitle,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.titleSmall,
          color =
            if (isRecentlyPlayed) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          fontWeight = if (isFavorite) FontWeight.SemiBold else FontWeight.Normal,
          textAlign = if (isGridMode && displayConfig.centerGridTitles) TextAlign.Center else TextAlign.Start,
        )
          if (onFavoriteClick != null) {
            PlaylistBookmarkButton(isFavorite = isFavorite, onToggle = onFavoriteClick)
          }
        }
        if (showLocation && location.isNotBlank()) {
          Text(
            location,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        FlowRow(
          horizontalArrangement =
            androidx.compose.foundation.layout.Arrangement
              .spacedBy(6.dp),
          verticalArrangement =
            androidx.compose.foundation.layout.Arrangement
              .spacedBy(6.dp),
        ) {
          if (sourceLabel != null) {
            SourceChip(label = sourceLabel, color = sourceColor ?: MaterialTheme.colorScheme.surfaceContainerHigh)
            val fields = buildList {
              video?.let { media ->
                if (displayConfig.showSizeChip) add(media.sizeFormatted)
                if (displayConfig.showDurationField) add(media.durationFormatted)
                if (displayConfig.showDateChip && media.dateModified > 0L) add(formatVideoCardDate(media.dateModified))
                if (!media.isAudio) {
                  if (displayConfig.showResolutionChip) add(media.resolution.substringBefore('@').trim())
                  if (displayConfig.showFramerateInResolution && media.fps.isFinite() && media.fps > 0f) {
                    add("${media.fps} FPS")
                  }
                  if (displayConfig.showCodecSupportIndicator) add(media.videoCodec)
                  if (showSubtitleIndicator && media.hasEmbeddedSubtitles) add(media.subtitleCodec)
                }
              }
            }.filter { it.isNotBlank() && it != "--" && it != "0 B" }
            fields.forEach { field ->
              Text(
                text = field,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapeScale.small)
                  .padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
              )
            }
          }
          if (showCategory && !groupTitle.isNullOrBlank()) {
            M3UMetadataChip(
              text = groupTitle,
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          }
          if (showStreamDetails && hasDrm) {
            M3UMetadataChip(
              text = "DRM",
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
          if (showStreamDetails && hasCustomUserAgent) {
            M3UMetadataChip(
              text = "UA",
              containerColor = MaterialTheme.colorScheme.tertiaryContainer,
              contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
          }
          if (isFavorite) {
            M3UMetadataChip(
              text = stringResource(R.string.ui_saved),
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
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

/** Amber reads as "needs attention" rather than a hard error, on both light and dark themes. */
private val SourceWarningAmber = Color(0xFFFFB300)
