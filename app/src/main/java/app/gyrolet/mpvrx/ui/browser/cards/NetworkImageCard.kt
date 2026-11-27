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
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkImageRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.components.MediaTypeBadge
import app.gyrolet.mpvrx.ui.browser.components.NetworkMediaType
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import app.gyrolet.mpvrx.ui.icons.Icon as AppIcon

@Composable
fun NetworkImageCard(
  file: NetworkFile,
  connection: NetworkConnection,
  onClick: () -> Unit,
  isGridMode: Boolean,
  modifier: Modifier = Modifier,
  /** Corner label naming the media type; null hides it (shown only when images are mixed in). */
  mediaType: NetworkMediaType? = null,
) {
  val imageRepository = koinInject<NetworkImageRepository>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val showThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showSizeChip by browserPreferences.showSizeChip.collectAsState()
  val showDateChip by browserPreferences.showDateChip.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val maxLines = if (unlimitedNameLines) Int.MAX_VALUE else 2
  val displayName = if (showExtensionField) file.name else file.name.substringBeforeLast('.', file.name)
  val displayThumb = showThumbnails && showNetworkThumbnails

  // A grid card is as wide as its column, and the column count is a preference, so the thumbnail is
  // requested at the size the layout actually gave it. The fixed 160dp guess it replaces was smaller
  // than the box on a two-column phone and about ten times larger on an eight-column one. Null until
  // the first layout pass.
  var thumbSizePx by remember(connection.id, file.path) { mutableStateOf<IntSize?>(null) }

  var thumbnail by remember(connection.id, file.path, displayThumb) { mutableStateOf<Bitmap?>(null) }
  var isLoading by remember(connection.id, file.path, displayThumb) { mutableStateOf(displayThumb) }
  var hasFailed by remember(connection.id, file.path, displayThumb) { mutableStateOf(false) }
  var retryToken by remember(connection.id, file.path) { mutableStateOf(0) }

  LaunchedEffect(connection.id, file.path, retryToken, thumbSizePx, displayThumb) {
    if (!displayThumb) return@LaunchedEffect
    val size = thumbSizePx ?: return@LaunchedEffect
    isLoading = true
    hasFailed = false
    val loaded =
      withContext(Dispatchers.IO) {
        imageRepository.getThumbnail(
          connectionId = connection.id,
          file = file,
          widthPx = size.width,
          heightPx = size.height,
          force = retryToken > 0,
        )
      }
    thumbnail = loaded
    hasFailed = loaded == null
    isLoading = false
  }

  val thumbBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }

  if (isGridMode) {
    NetworkImageCardGridLayout(
      file = file,
      displayName = displayName,
      onClick = onClick,
      thumbBitmap = thumbBitmap,
      isLoading = isLoading,
      hasFailed = hasFailed,
      onRetry = { retryToken++ },
      onThumbSizeChanged = { thumbSizePx = it },
      mediaType = mediaType,
      showSizeChip = showSizeChip,
      showDateChip = showDateChip,
      centerGridTitles = centerGridTitles,
      maxLines = maxLines,
      modifier = modifier,
    )
  } else {
    NetworkImageCardListLayout(
      file = file,
      displayName = displayName,
      onClick = onClick,
      thumbBitmap = thumbBitmap,
      isLoading = isLoading,
      hasFailed = hasFailed,
      onRetry = { retryToken++ },
      onThumbSizeChanged = { thumbSizePx = it },
      mediaType = mediaType,
      showSizeChip = showSizeChip,
      showDateChip = showDateChip,
      maxLines = maxLines,
      modifier = modifier,
    )
  }
}

@Composable
private fun NetworkImageCardGridLayout(
  file: NetworkFile,
  displayName: String,
  onClick: () -> Unit,
  thumbBitmap: ImageBitmap?,
  isLoading: Boolean,
  hasFailed: Boolean,
  onRetry: () -> Unit,
  onThumbSizeChanged: (IntSize) -> Unit,
  mediaType: NetworkMediaType?,
  showSizeChip: Boolean,
  showDateChip: Boolean,
  centerGridTitles: Boolean,
  maxLines: Int,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.large, focusedScale = 1.03f)
        .mediaCardClickable(onClick = onClick),
    shape = AppShapeScale.large,
    colors =
      CardDefaults.cardColors(
        containerColor = Color.Transparent,
      ),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .clip(AppShapeScale.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onSizeChanged(onThumbSizeChanged),
        contentAlignment = Alignment.Center,
      ) {
        ThumbnailImage(
          thumbBitmap = thumbBitmap,
          isLoading = isLoading,
          hasFailed = hasFailed,
          onRetry = onRetry,
          modifier = Modifier.matchParentSize(),
          progressSize = 32.dp,
          iconSize = 48.dp,
        )

        if (mediaType != null) {
          MediaTypeBadge(
            type = mediaType,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = displayName,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(modifier = Modifier.height(4.dp))

      FlowRow(
        horizontalArrangement = if (centerGridTitles) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (showSizeChip && file.size > 0) {
          Text(
            text = formatCardFileSize(file.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapeScale.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        if (showDateChip && file.lastModified > 0) {
          Text(
            text = formatCardDate(file.lastModified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, AppShapeScale.small)
                .padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun NetworkImageCardListLayout(
  file: NetworkFile,
  displayName: String,
  onClick: () -> Unit,
  thumbBitmap: ImageBitmap?,
  isLoading: Boolean,
  hasFailed: Boolean,
  onRetry: () -> Unit,
  onThumbSizeChanged: (IntSize) -> Unit,
  mediaType: NetworkMediaType?,
  showSizeChip: Boolean,
  showDateChip: Boolean,
  maxLines: Int,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusHighlight(AppShapeScale.medium)
        .mediaCardClickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier =
        Modifier
          .width(128.dp)
          .aspectRatio(16f / 9f)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceContainerHigh)
          .onSizeChanged(onThumbSizeChanged),
      contentAlignment = Alignment.Center,
    ) {
      ThumbnailImage(
        thumbBitmap = thumbBitmap,
        isLoading = isLoading,
        hasFailed = hasFailed,
        onRetry = onRetry,
        modifier = Modifier.matchParentSize(),
        progressSize = 28.dp,
        iconSize = 28.dp,
      )
      if (mediaType != null) {
        MediaTypeBadge(
          type = mediaType,
          modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
      }
    }

    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.Center,
    ) {
      Text(
        text = displayName,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(4.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (showSizeChip && file.size > 0) {
          Text(
            text = formatCardFileSize(file.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .background(
                  MaterialTheme.colorScheme.surfaceContainerHigh,
                  AppShapeScale.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        if (showDateChip && file.lastModified > 0) {
          Text(
            text = formatCardDate(file.lastModified),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier =
              Modifier
                .background(
                  MaterialTheme.colorScheme.surfaceContainerHigh,
                  AppShapeScale.small,
                ).padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun ThumbnailImage(
  thumbBitmap: ImageBitmap?,
  isLoading: Boolean,
  hasFailed: Boolean,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
  progressSize: Dp = 32.dp,
  iconSize: Dp = 48.dp,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    when {
      thumbBitmap != null ->
        Image(
          bitmap = thumbBitmap,
          contentDescription = null,
          modifier = Modifier.matchParentSize(),
          contentScale = ContentScale.Crop,
        )

      isLoading ->
        CircularProgressIndicator(
          modifier = Modifier.size(progressSize),
          color = MaterialTheme.colorScheme.primary,
        )

      // Retries in place rather than opening the viewer, so a transient network failure is
      // recoverable without leaving the list. The refresh glyph is what distinguishes this from
      // the "no thumbnail" placeholder below.
      hasFailed ->
        Box(
          modifier = Modifier.matchParentSize().clickable(onClick = onRetry),
          contentAlignment = Alignment.Center,
        ) {
          AppIcon(
            imageVector = Icons.RoundedFilled.Refresh,
            contentDescription = stringResource(R.string.ui_retry),
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

      else ->
        AppIcon(
          imageVector = Icons.RoundedFilled.Image,
          contentDescription = null,
          modifier = Modifier.size(iconSize),
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
  }
}
