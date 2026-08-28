/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin.seerr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.seerr.JellyseerrRequest
import app.gyrolet.mpvrx.domain.seerr.MediaStatus
import app.gyrolet.mpvrx.domain.seerr.MediaType
import app.gyrolet.mpvrx.domain.seerr.RequestStatus
import app.gyrolet.mpvrx.domain.seerr.SearchResultItem
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.tvFocusable

@Composable
fun SeerrMediaCard(
  item: SearchResultItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: Dp = 135.dp,
) {
  Column(
    modifier = modifier.width(cardWidth),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .tvFocusable(RoundedCornerShape(8.dp))
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(onClick = onClick),
    ) {
      val posterUrl = item.getPosterUrl()
      if (!posterUrl.isNullOrBlank()) {
        RemoteImage(
          url = posterUrl,
          contentDescription = item.getDisplayTitle(),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (item.getMediaType() == MediaType.TV) Icons.RoundedFilled.Tv else Icons.RoundedFilled.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp),
          )
        }
      }

      // Top Badges (Rating on left, Status on right)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
      ) {
        item.getRating()?.let { rating ->
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color(0xFFFFC107),
            modifier = Modifier.padding(1.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Star,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = Color(0xFFFFC107),
              )
              Spacer(modifier = Modifier.width(2.dp))
              Text(
                text = rating,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
              )
            }
          }
        } ?: Spacer(modifier = Modifier.size(1.dp))

        val displayStatus = item.getDisplayStatus()
        if (displayStatus != null && displayStatus != MediaStatus.UNKNOWN) {
          SeerrStatusChip(status = displayStatus)
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = item.getDisplayTitle(),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(top = 1.dp),
    ) {
      item.getReleaseYear()?.let { year ->
        Text(
          text = year,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "•",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = if (item.getMediaType() == MediaType.TV) "TV" else "Movie",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
fun SeerrStatusChip(
  status: MediaStatus,
  modifier: Modifier = Modifier,
) {
  val (bgColor, textColor, textRes) = when (status) {
    MediaStatus.AVAILABLE -> Triple(
      Color(0xFF1B5E20).copy(alpha = 0.85f),
      Color(0xFFE8F5E9),
      R.string.seerr_status_available,
    )
    MediaStatus.PARTIALLY_AVAILABLE -> Triple(
      Color(0xFFE65100).copy(alpha = 0.85f),
      Color(0xFFFFF3E0),
      R.string.seerr_status_partially_available,
    )
    MediaStatus.PROCESSING -> Triple(
      Color(0xFF0D47A1).copy(alpha = 0.85f),
      Color(0xFFE3F2FD),
      R.string.seerr_status_processing,
    )
    MediaStatus.PENDING -> Triple(
      Color(0xFF4A148C).copy(alpha = 0.85f),
      Color(0xFFF3E5F5),
      R.string.seerr_status_pending,
    )
    else -> Triple(
      Color.Black.copy(alpha = 0.7f),
      Color.White,
      R.string.seerr_status_requested,
    )
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = bgColor,
    contentColor = textColor,
    modifier = modifier,
  ) {
    Text(
      text = stringResource(textRes),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      fontSize = 9.5.sp,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
    )
  }
}

@Composable
fun SeerrRequestStatusChip(
  status: RequestStatus,
  modifier: Modifier = Modifier,
) {
  val (bgColor, textColor, textRes) = when (status) {
    RequestStatus.COMPLETED -> Triple(
      Color(0xFF1B5E20).copy(alpha = 0.85f),
      Color(0xFFE8F5E9),
      R.string.seerr_status_available,
    )
    RequestStatus.APPROVED -> Triple(
      Color(0xFF0D47A1).copy(alpha = 0.85f),
      Color(0xFFE3F2FD),
      R.string.seerr_status_processing,
    )
    RequestStatus.PENDING -> Triple(
      Color(0xFFE65100).copy(alpha = 0.85f),
      Color(0xFFFFF3E0),
      R.string.seerr_status_pending,
    )
    RequestStatus.DECLINED -> Triple(
      Color(0xFFB71C1C).copy(alpha = 0.85f),
      Color(0xFFFFEBEE),
      R.string.seerr_decline,
    )
    RequestStatus.FAILED -> Triple(
      Color(0xFFB71C1C).copy(alpha = 0.85f),
      Color(0xFFFFEBEE),
      R.string.clip_cancel,
    )
  }

  Surface(
    shape = RoundedCornerShape(8.dp),
    color = bgColor,
    contentColor = textColor,
    modifier = modifier,
  ) {
    Text(
      text = stringResource(textRes),
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      fontSize = 10.sp,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
    )
  }
}

@Composable
fun SeerrRequestCard(
  request: JellyseerrRequest,
  baseUrl: String?,
  isAdmin: Boolean,
  onClick: () -> Unit,
  onApprove: () -> Unit = {},
  onDecline: () -> Unit = {},
  onDelete: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  cardWidth: Dp = 230.dp,
) {
  Column(
    modifier = modifier.width(cardWidth),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .tvFocusable(RoundedCornerShape(10.dp))
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(onClick = onClick),
    ) {
      val backdropUrl = request.media.getBackdropUrl() ?: request.media.getPosterUrl()
      if (!backdropUrl.isNullOrBlank()) {
        RemoteImage(
          url = backdropUrl,
          contentDescription = request.media.getDisplayTitle(),
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            if (request.getMediaType() == MediaType.TV) Icons.RoundedFilled.Tv else Icons.RoundedFilled.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp),
          )
        }
      }

      // Subtle horizontal gradient overlay to enhance poster and text clarity
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.horizontalGradient(
              0.0f to Color.Black.copy(alpha = 0.5f),
              0.55f to Color.Black.copy(alpha = 0.15f),
              1.0f to Color.Black.copy(alpha = 0.65f),
            )
          ),
      )

      // Poster on the Right side of the thumbnail
      val posterUrl = request.media.getPosterUrl()
      if (!posterUrl.isNullOrBlank()) {
        RemoteImage(
          url = posterUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = 8.dp, top = 8.dp, bottom = 8.dp)
            .fillMaxHeight()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(6.dp)),
        )
      }

      // Top-Left status badge
      SeerrRequestStatusChip(
        status = request.getRequestStatus(),
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(8.dp),
      )

      // Requester Photo on Bottom Left of Thumbnail
      val rawAvatar = request.requestedBy.avatar
      val avatarUrl = when {
        rawAvatar.isNullOrBlank() -> null
        rawAvatar.startsWith("http") -> rawAvatar
        !baseUrl.isNullOrBlank() -> "${baseUrl.trimEnd('/')}/${rawAvatar.trimStart('/')}"
        else -> null
      }

      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(8.dp),
      ) {
        if (avatarUrl != null) {
          RemoteImage(
            url = avatarUrl,
            contentDescription = request.requestedBy.displayName ?: request.requestedBy.username,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(24.dp)
              .clip(CircleShape),
          )
        } else {
          Box(
            modifier = Modifier
              .size(24.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = (request.requestedBy.displayName ?: request.requestedBy.username ?: "U").take(1).uppercase(),
              style = MaterialTheme.typography.labelSmall,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }
    }

    // Title and Requester underneath thumbnail (not clipped)
    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = request.media.getDisplayTitle(),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier.padding(top = 1.dp),
    ) {
      request.media.getReleaseYear()?.let { year ->
        Text(
          text = year,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "•",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      val requesterName = request.requestedBy.displayName ?: request.requestedBy.username ?: "User"
      Text(
        text = "Requested by $requesterName",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun SeerrSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  actionText: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onBackground,
    )

    if (actionText != null && onActionClick != null) {
      Text(
        text = actionText,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable(onClick = onActionClick)
          .padding(horizontal = 8.dp, vertical = 4.dp),
      )
    }
  }
}

@Composable
fun SeerrSliderRow(
  title: String,
  items: List<SearchResultItem>,
  onItemClick: (SearchResultItem) -> Unit,
  modifier: Modifier = Modifier,
  actionText: String? = null,
  onActionClick: (() -> Unit)? = null,
) {
  if (items.isEmpty()) return

  Column(modifier = modifier.fillMaxWidth()) {
    SeerrSectionHeader(
      title = title,
      actionText = actionText,
      onActionClick = onActionClick,
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      items(items, key = { "${it.mediaType}_${it.id}" }) { item ->
        SeerrMediaCard(
          item = item,
          onClick = { onItemClick(item) },
        )
      }
    }
  }
}
