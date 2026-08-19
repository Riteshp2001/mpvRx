/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.utils.media.MediaInfoParser

/**
 * Cinematic Torrent Details Modal Bottom Sheet (matching JellyCine / JellyfinDetailSheet).
 * Provides full-bleed backdrop, floating poster, metadata badges, synopsis expander,
 * action buttons, and a searchable/sortable episode & file selector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailSheet(
  group: TorrentStreamGroup?,
  viewedFileIndices: Set<Int>,
  onDismiss: () -> Unit,
  onPlayFile: (NetworkStreamEntryEntity) -> Unit,
  onDeleteGroup: (TorrentStreamGroup) -> Unit,
  onDeleteFile: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (group == null) return

  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val context = LocalContext.current
  var isOverviewExpanded by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }
  var isSearchOpen by remember { mutableStateOf(false) }
  var sortDescending by remember { mutableStateOf(false) }

  val backdropUrl = group.backdropUrl ?: group.posterUrl
  val posterUrl = group.posterUrl ?: group.backdropUrl

  val sortedFiles =
    remember(group.files, searchQuery, sortDescending) {
      val base =
        group.files.sortedWith { e1, e2 ->
          MediaInfoParser.compareMediaFiles(e1.fileName, e1.fileIndex, e2.fileName, e2.fileIndex)
        }
      val filtered =
        if (searchQuery.isBlank()) {
          base
        } else {
          val q = searchQuery.trim()
          base.filter { file ->
            file.fileName.contains(q, ignoreCase = true) ||
              file.filePath?.contains(q, ignoreCase = true) == true ||
              (file.fileIndex != null && (file.fileIndex + 1).toString() == q)
          }
        }
      if (sortDescending) filtered.reversed() else filtered
    }

  val firstUnviewedFile =
    remember(group.files, viewedFileIndices) {
      val base =
        group.files.sortedWith { e1, e2 ->
          MediaInfoParser.compareMediaFiles(e1.fileName, e1.fileIndex, e2.fileName, e2.fileIndex)
        }
      base.firstOrNull { it.fileIndex !in viewedFileIndices } ?: base.firstOrNull()
    }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    dragHandle = null,
    modifier = modifier,
  ) {
    val listState = rememberLazyListState()

    LazyColumn(
      state = listState,
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding(),
      contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      // 1. Full-Bleed Backdrop Header with floating poster & close button
      item {
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f),
        ) {
          if (!backdropUrl.isNullOrBlank()) {
            RemoteImage(
              url = backdropUrl,
              contentDescription = group.title,
              modifier = Modifier.fillMaxSize(),
              contentScale = ContentScale.Crop,
            )
          } else {
            Box(
              modifier =
                Modifier
                  .fillMaxSize()
                  .background(
                    Brush.radialGradient(
                      colors =
                        listOf(
                          MaterialTheme.colorScheme.primaryContainer,
                          MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    ),
                  ),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
              )
            }
          }

          // Dynamic Gradient Scrim
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.5f to Color.Transparent,
                    0.8f to MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    1f to MaterialTheme.colorScheme.surface,
                  ),
                ),
          )

          // Close button at top right
          FilledTonalIconButton(
            onClick = onDismiss,
            modifier =
              Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(36.dp),
            colors =
              IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.5f),
                contentColor = Color.White,
              ),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Close,
              contentDescription = "Close",
              modifier = Modifier.size(20.dp),
            )
          }

          // Floating Poster at bottom left
          Row(
            modifier =
              Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            Box(
              modifier =
                Modifier
                  .width(90.dp)
                  .aspectRatio(2f / 3f)
                  .shadow(10.dp, RoundedCornerShape(14.dp))
                  .clip(RoundedCornerShape(14.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
              if (!posterUrl.isNullOrBlank()) {
                RemoteImage(
                  url = posterUrl,
                  contentDescription = group.title,
                  modifier = Modifier.fillMaxSize(),
                  contentScale = ContentScale.Crop,
                )
              } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = Icons.RoundedFilled.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }

            Column(
              modifier = Modifier.padding(bottom = 4.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
              ) {
                Text(
                  text = "TORRENT",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Black,
                  color = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  letterSpacing = 0.8.sp,
                )
              }

              Text(
                text = group.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      // 2. Metadata Pills & Badges Row
      item {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          group.releaseYear?.takeIf(String::isNotBlank)?.let { year ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = year,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          if (group.files.isNotEmpty()) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = "${group.files.size} Episodes",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          if (group.totalSize > 0L) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = formatTorrentBytes(group.totalSize),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // 3. Action Buttons Row
      item {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (firstUnviewedFile != null) {
              Button(
                onClick = { onPlayFile(firstUnviewedFile) },
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                  ),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (viewedFileIndices.isEmpty()) "Watch Now" else "Resume Playback",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                )
              }
            }

            // Copy Magnet Action
            FilledTonalIconButton(
              onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Magnet URI", group.canonicalSourceUri)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Magnet URL copied to clipboard", Toast.LENGTH_SHORT).show()
              },
              modifier = Modifier.size(48.dp),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Share,
                contentDescription = "Copy Magnet",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }

            // Delete Group Action
            FilledTonalIconButton(
              onClick = {
                onDeleteGroup(group)
                onDismiss()
              },
              modifier = Modifier.size(48.dp),
              shape = RoundedCornerShape(14.dp),
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                  contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Delete,
                contentDescription = "Delete Torrent",
                modifier = Modifier.size(20.dp),
              )
            }
          }

          // Play from beginning button if resume available
          if (viewedFileIndices.isNotEmpty() && group.files.size > 1) {
            val firstFile = group.files.firstOrNull()
            if (firstFile != null && firstFile != firstUnviewedFile) {
              OutlinedButton(
                onClick = { onPlayFile(firstFile) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play from Beginning (Episode 1)", style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
      }

      // 4. Synopsis / Overview Section
      if (!group.overview.isNullOrBlank()) {
        item {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { isOverviewExpanded = !isOverviewExpanded },
          ) {
            Text(
              text = "Storyline",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = group.overview,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = if (isOverviewExpanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = if (isOverviewExpanded) "Show less" else "Read more",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
      }

      // 5. Episode / Files Header with Search & Sort
      item {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Files & Episodes (${group.files.size})",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            if (group.files.size > 1) {
              Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { isSearchOpen = !isSearchOpen }) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Search,
                    contentDescription = "Search files",
                    tint = if (isSearchOpen || searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                IconButton(onClick = { sortDescending = !sortDescending }) {
                  Icon(
                    imageVector = Icons.RoundedFilled.SwapVert,
                    contentDescription = "Sort",
                    tint = if (sortDescending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }

          if (isSearchOpen && group.files.size > 1) {
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp),
              placeholder = { Text("Filter episodes by name or number...") },
              leadingIcon = {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
              },
              trailingIcon = {
                if (searchQuery.isNotBlank()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = "Clear",
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
            )
          }
        }
      }

      // 6. Episode / File Cards List
      if (sortedFiles.isEmpty()) {
        item {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "No matching episodes found",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        itemsIndexed(sortedFiles, key = { _, file -> file.stableKey }) { index, file ->
          val isViewed = file.fileIndex in viewedFileIndices

          Card(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable { onPlayFile(file) },
            shape = RoundedCornerShape(14.dp),
            colors =
              CardDefaults.cardColors(
                containerColor = if (isViewed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
              ),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              // Episode Index / Play Box
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isViewed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  if (isViewed) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Check,
                      contentDescription = "Watched",
                      modifier = Modifier.size(20.dp),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  } else {
                    Text(
                      text = "${(file.fileIndex ?: index) + 1}",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                  }
                }
              }

              // Episode Title & File Size
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = file.fileName,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = if (isViewed) FontWeight.Normal else FontWeight.SemiBold,
                  color = if (isViewed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                if (file.fileSize > 0L) {
                  Text(
                    text = formatTorrentBytes(file.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                  )
                }
              }

              // Play Icon Button
              FilledTonalIconButton(
                onClick = { onPlayFile(file) },
                modifier = Modifier.size(36.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.PlayArrow,
                  contentDescription = "Play",
                  modifier = Modifier.size(20.dp),
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }
    }
  }
}
