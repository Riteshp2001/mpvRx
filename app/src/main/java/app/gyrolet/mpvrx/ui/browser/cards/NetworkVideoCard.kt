/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.text.style.TextAlign

@Composable
fun NetworkVideoCard(
  file: NetworkFile,
  connection: NetworkConnection,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
) {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val thumbnailRepository = koinInject<ThumbnailRepository>()

  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showNetworkThumbs by appearancePreferences.showNetworkThumbnails.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()

  val displayThumb = showVideoThumbnails && showNetworkThumbs
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2

  val thumbSizeDp = 64.dp
  val density = LocalDensity.current
  val thumbSizePx = with(density) { thumbSizeDp.roundToPx() }

  val thumbnailKey =
    remember(file.path, thumbSizePx, displayThumb) {
      if (displayThumb) {
        thumbnailRepository.thumbnailKeyForNetworkPath(file.path, thumbSizePx, thumbSizePx)
      } else {
        null
      }
    }
  var thumbnail by remember(thumbnailKey) { mutableStateOf<Bitmap?>(null) }

  // Subscribe to ready-keys so folder-level prefetch also updates this card
  LaunchedEffect(thumbnailKey) {
    if (thumbnailKey == null) return@LaunchedEffect
    thumbnailRepository.thumbnailReadyKeys
      .collect { key ->
        if (key == thumbnailKey) {
          thumbnail =
            withContext(Dispatchers.IO) {
              thumbnailRepository.getThumbnailForNetworkPath(file.path, thumbSizePx, thumbSizePx, connection)
            }
        }
      }
  }

  // On-demand generation
  LaunchedEffect(thumbnailKey, displayThumb) {
    if (thumbnailKey == null || !displayThumb) return@LaunchedEffect
    if (thumbnail != null) return@LaunchedEffect
    thumbnail =
      withContext(Dispatchers.IO) {
        thumbnailRepository.getThumbnailForNetworkPath(file.path, thumbSizePx, thumbSizePx, connection)
      }
  }

  val displayName = if (showExtensionField) file.name else file.name.substringBeforeLast('.', file.name)

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
    if (isGridMode) {
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .background(
              if (isSelected) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f) else Color.Transparent,
            ).padding(8.dp),
        horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start,
      ) {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .clip(AppShapeScale.medium)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh),
          contentAlignment = Alignment.Center,
        ) {
          val thumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }
          if (thumbnailBitmap != null) {
            Image(
              bitmap = thumbnailBitmap,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
              modifier = Modifier.matchParentSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              Icons.RoundedFilled.PlayArrow,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          displayName,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = maxLines,
          overflow = TextOverflow.Ellipsis,
          textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
          horizontalArrangement =
            if (centerGridTitles) androidx.compose.foundation.layout.Arrangement.Center
            else androidx.compose.foundation.layout.Arrangement.Start,
          verticalArrangement =
            androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
        ) {
          if (showSizeChip && file.size > 0) {
            Text(
              formatFileSize(file.size),
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
          if (showDateChip && file.lastModified > 0) {
            Text(
              formatDate(file.lastModified),
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
    } else {
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
            ).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Square thumbnail
        Box(
          modifier =
            Modifier
              .size(thumbSizeDp)
              .clip(AppShapeScale.medium)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh)
              .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
              ),
          contentAlignment = Alignment.Center,
        ) {
          val listThumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }
          if (listThumbnailBitmap != null) {
            Image(
              bitmap = listThumbnailBitmap,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
              modifier = Modifier.matchParentSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Icon(
              Icons.RoundedFilled.PlayArrow,
              contentDescription =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
              modifier = Modifier.size(48.dp),
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(
          modifier = Modifier.weight(1f),
        ) {
          Text(
            displayName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(4.dp))
          FlowRow(
            horizontalArrangement =
              androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
            verticalArrangement =
              androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
          ) {
            if (showSizeChip && file.size > 0) {
              Text(
                formatFileSize(file.size),
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
            if (showDateChip && file.lastModified > 0) {
              Text(
                formatDate(file.lastModified),
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

private fun formatFileSize(bytes: Long): String =
  when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
  }

private fun formatDate(timestamp: Long): String {
  val date = Date(timestamp)
  val format = SimpleDateFormat("MMM dd", Locale.getDefault())
  return format.format(date)
}
