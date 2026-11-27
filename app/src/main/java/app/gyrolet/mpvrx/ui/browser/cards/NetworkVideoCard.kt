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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.components.MediaTypeBadge
import app.gyrolet.mpvrx.ui.browser.components.NetworkMediaType
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun NetworkVideoCard(
  file: NetworkFile,
  connection: NetworkConnection,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
  selectionNeighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
  /** Corner label naming the media type; null hides it (shown only when images are mixed in). */
  mediaType: NetworkMediaType? = null,
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

  val thumbSizeDp = 128.dp
  val density = LocalDensity.current
  val thumbSizePx = with(density) { thumbSizeDp.roundToPx() }

  val thumbnailKey =
    remember(file.path, file.size, file.lastModified, connection, thumbSizePx, displayThumb) {
      if (displayThumb) {
        thumbnailRepository.thumbnailKeyForNetworkPath(
          path = file.path,
          widthPx = thumbSizePx,
          heightPx = thumbSizePx,
          connection = connection,
        )
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
              thumbnailRepository.getThumbnailForNetworkPath(
                path = file.path,
                widthPx = thumbSizePx,
                heightPx = thumbSizePx,
                connection = connection,
                fileSize = file.size,
                mimeType = file.mimeType,
              )
            }
        }
      }
  }

  // On-demand generation
  LaunchedEffect(thumbnailKey, displayThumb) {
    if (thumbnailKey == null || !displayThumb) return@LaunchedEffect
    if (thumbnail != null) return@LaunchedEffect
    repeat(2) { attempt ->
      thumbnail =
        withContext(Dispatchers.IO) {
          thumbnailRepository.getThumbnailForNetworkPath(
            path = file.path,
            widthPx = thumbSizePx,
            heightPx = thumbSizePx,
            connection = connection,
            fileSize = file.size,
            mimeType = file.mimeType,
          )
        }
      if (thumbnail != null) return@LaunchedEffect
      if (attempt == 0) delay(30_000L)
    }
  }

  val displayName = if (showExtensionField) file.name else file.name.substringBeforeLast('.', file.name)
  val cardShape = rememberMediaSelectionShape(isSelected, isGridMode, selectionNeighbors)
  val selectionColor = animatedSelectionColor(isSelected)

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusMemory("network:${file.path}")
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
    // Measure visible content so short titles and fewer chips leave no reserved footer.
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier = Modifier.matchParentSize().clip(cardShape).background(selectionColor),
      )

      if (isGridMode) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start,
        ) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
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
            if (mediaType != null) {
              MediaTypeBadge(
                type = mediaType,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
              )
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
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
              if (centerGridTitles) {
                androidx.compose.foundation.layout.Arrangement.Center
              } else {
                androidx.compose.foundation.layout.Arrangement.Start
              },
            verticalArrangement =
              androidx.compose.foundation.layout.Arrangement
                .spacedBy(4.dp),
          ) {
            if (showSizeChip && file.size > 0) {
              Text(
                formatCardFileSize(file.size),
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
                formatCardDate(file.lastModified),
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
              .padding(horizontal = 8.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Match the normal video list thumbnail footprint.
          Box(
            modifier =
              Modifier
                .width(thumbSizeDp)
                .aspectRatio(16f / 9f)
                .clip(AppShapeScale.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .mediaCardClickable(
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
            if (mediaType != null) {
              MediaTypeBadge(
                type = mediaType,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
              )
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
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
                  formatCardFileSize(file.size),
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
                  formatCardDate(file.lastModified),
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
      SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
  }
}
