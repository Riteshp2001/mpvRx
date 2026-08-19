/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

@Composable
fun JellyfinLibraryCard(
  item: JellyfinItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
      ),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        val icon =
          when (item.collectionType?.lowercase() ?: item.type.lowercase()) {
            "movies", "tvshows" -> Icons.RoundedFilled.Movie
            "music" -> Icons.RoundedFilled.Audiotrack
            else -> Icons.RoundedFilled.Folder
          }
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(24.dp),
        )
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val typeLabel =
          when (item.collectionType?.lowercase()) {
            "movies" -> "Movies"
            "tvshows" -> "TV Shows"
            "music" -> "Music"
            "books" -> "Books"
            "homevideos" -> "Home Videos"
            "photos" -> "Photos"
            else ->
              when (item.type) {
                "CollectionFolder" -> "Collection"
                "Folder" -> "Folder"
                else -> item.type.replace(Regex("([a-z])([A-Z])"), "$1 $2")
              }
          }
        Text(
          text = typeLabel,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Icon(
        imageVector = Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun JellyfinPosterCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
      JellyfinClient.getImageUrl(
        serverUrl = server.serverUrl,
        itemId = item.id,
        imageTag = item.primaryImageTag,
        maxWidth = 400,
        token = server.accessToken,
      )
    }

  val containerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(12.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = containerColor,
      ),
  ) {
    Column {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        if (!item.primaryImageTag.isNullOrBlank()) {
          RemoteImage(
            url = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            val placeholderIcon =
              when {
                item.isAudio -> Icons.RoundedFilled.Audiotrack
                item.isFolder -> Icons.RoundedFilled.Folder
                else -> Icons.RoundedFilled.VideoLibrary
              }
            Icon(
              imageVector = placeholderIcon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(36.dp),
            )
          }
        }

        // Progress bar if partially watched
        if (item.progressPercent > 0.02f && !item.isPlayed) {
          LinearProgressIndicator(
            progress = { item.progressPercent },
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
          )
        }

        // Selection or Played checkmark badge
        if (isSelected) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .padding(6.dp)
                .size(22.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Selected",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(3.dp),
            )
          }
        } else if (item.isPlayed) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier =
              Modifier
                .padding(6.dp)
                .size(20.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Played",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(3.dp),
            )
          }
        }
      }

      Column(modifier = Modifier.padding(8.dp)) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle =
          item.productionYear?.toString()
            ?: if (item.isSeries && item.childCount != null) "${item.childCount} Seasons" else item.type
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }
    }
  }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun JellyfinEpisodeCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
      JellyfinClient.getImageUrl(
        serverUrl = server.serverUrl,
        itemId = item.id,
        imageTag = item.primaryImageTag,
        maxWidth = 300,
        token = server.accessToken,
      )
    }

  val containerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(
          onClick = onPlay,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(12.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = containerColor,
      ),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier
            .width(96.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        if (!item.primaryImageTag.isNullOrBlank()) {
          RemoteImage(
            url = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (item.progressPercent > 0.02f && !item.isPlayed) {
          LinearProgressIndicator(
            progress = { item.progressPercent },
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
          )
        }

        if (isSelected) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .padding(4.dp)
                .size(20.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Selected",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        } else if (item.isPlayed) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier =
              Modifier
                .padding(4.dp)
                .size(18.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Played",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        val epPrefix = if (item.indexNumber != null) "E${item.indexNumber} • " else ""
        Text(
          text = "$epPrefix${item.name}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (!item.overview.isNullOrBlank()) {
          Text(
            text = item.overview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (item.durationSeconds > 0) {
          val mins = item.durationSeconds / 60
          Text(
            text = "$mins min",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      IconButton(onClick = onPlay) {
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = "Play",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun JellyfinListItemCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
      JellyfinClient.getImageUrl(
        serverUrl = server.serverUrl,
        itemId = item.id,
        imageTag = item.primaryImageTag,
        maxWidth = 200,
        token = server.accessToken,
      )
    }

  val containerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(12.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = containerColor,
      ),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(width = 64.dp, height = 90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        if (!item.primaryImageTag.isNullOrBlank()) {
          RemoteImage(
            url = imageUrl,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            val placeholderIcon =
              when {
                item.isAudio -> Icons.RoundedFilled.Audiotrack
                item.isFolder -> Icons.RoundedFilled.Folder
                else -> Icons.RoundedFilled.VideoLibrary
              }
            Icon(
              imageVector = placeholderIcon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(28.dp),
            )
          }
        }

        if (item.progressPercent > 0.02f && !item.isPlayed) {
          LinearProgressIndicator(
            progress = { item.progressPercent },
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
          )
        }

        if (isSelected) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .padding(4.dp)
                .size(20.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Selected",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        } else if (item.isPlayed) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            modifier =
              Modifier
                .padding(4.dp)
                .size(16.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = "Played",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val details =
          buildList {
            item.productionYear?.let { add(it.toString()) }
            if (item.isSeries && item.childCount != null) {
              add("${item.childCount} Seasons")
            } else {
              add(item.type)
            }
            item.communityRating?.let { add("★ $it") }
          }.joinToString(" • ")

        Text(
          text = details,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        if (!item.overview.isNullOrBlank()) {
          Text(
            text = item.overview,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      Icon(
        imageVector = Icons.RoundedFilled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JellyfinResumeCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  isInSelectionMode: Boolean = false,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.backdropImageTag, item.primaryImageTag, server.accessToken) {
      if (!item.backdropImageTag.isNullOrBlank()) {
        JellyfinClient.getBackdropUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = item.backdropImageTag,
          maxWidth = 500,
          token = server.accessToken,
        )
      } else {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = item.primaryImageTag,
          maxWidth = 400,
          token = server.accessToken,
        )
      }
    }

  val containerColor =
    if (isSelected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Card(
    modifier =
      modifier
        .width(220.dp)
        .clip(RoundedCornerShape(12.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(12.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = containerColor,
      ),
  ) {
    Column {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        RemoteImage(
          url = imageUrl,
          contentDescription = item.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )

        // Selection overlay
        if (isSelected) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(32.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.RoundedFilled.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
        } else if (!isInSelectionMode) {
          // Play icon overlay (only when not in selection mode)
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
            modifier =
              Modifier
                .size(36.dp)
                .align(Alignment.Center),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        // Progress bar at bottom of thumbnail
        if (item.progressPercent > 0.01f) {
          LinearProgressIndicator(
            progress = { item.progressPercent },
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
          )
        }
      }

      Column(modifier = Modifier.padding(8.dp)) {
        val title = item.seriesName ?: item.name
        val subtitle =
          if (item.seriesName != null && item.indexNumber != null) {
            "S${item.parentIndexNumber ?: 1}:E${item.indexNumber} • ${item.name}"
          } else {
            item.type
          }

        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
