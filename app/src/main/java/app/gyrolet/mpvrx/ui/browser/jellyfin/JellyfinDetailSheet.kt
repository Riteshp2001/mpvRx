/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinDetailSheet(
  item: JellyfinItem?,
  server: JellyfinServer,
  seasons: List<JellyfinItem>,
  selectedSeasonId: String?,
  episodes: List<JellyfinItem>,
  similarItems: List<JellyfinItem>,
  isLoading: Boolean,
  isEpisodesLoading: Boolean,
  onDismiss: () -> Unit,
  onPlay: (JellyfinItem, Boolean) -> Unit,
  onSelectSeason: (String) -> Unit,
  onToggleFavorite: (JellyfinItem) -> Unit,
  onTogglePlayed: (JellyfinItem) -> Unit,
  onItemClick: (JellyfinItem) -> Unit,
  sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
  if (item == null) return

  var isOverviewExpanded by remember { mutableStateOf(false) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    dragHandle = null,
    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
    ) {
      // Backdrop Header with Poster
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .height(240.dp),
      ) {
        val backdropUrl =
          remember(server.serverUrl, item.id, item.backdropImageTag, item.primaryImageTag, server.accessToken) {
            if (!item.backdropImageTag.isNullOrBlank()) {
              JellyfinClient.getBackdropUrl(
                serverUrl = server.serverUrl,
                itemId = item.id,
                imageTag = item.backdropImageTag,
                maxWidth = 1280,
                token = server.accessToken,
              )
            } else {
              JellyfinClient.getImageUrl(
                serverUrl = server.serverUrl,
                itemId = item.id,
                imageTag = item.primaryImageTag,
                maxWidth = 800,
                token = server.accessToken,
              )
            }
          }

        RemoteImage(
          url = backdropUrl,
          contentDescription = item.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )

        // Gradient Scrim
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  0.0f to Color.Black.copy(alpha = 0.4f),
                  0.4f to Color.Transparent,
                  0.8f to MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                  1.0f to MaterialTheme.colorScheme.surface,
                ),
              ),
        )

        // Close button
        IconButton(
          onClick = onDismiss,
          colors =
            IconButtonDefaults.iconButtonColors(
              containerColor = Color.Black.copy(alpha = 0.6f),
              contentColor = Color.White,
            ),
          modifier =
            Modifier
              .align(Alignment.TopEnd)
              .padding(16.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Close,
            contentDescription = "Close",
            modifier = Modifier.size(20.dp),
          )
        }

        // Floating Poster Overlay at Bottom Left
        val posterUrl =
          remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
            JellyfinClient.getImageUrl(
              serverUrl = server.serverUrl,
              itemId = item.id,
              imageTag = item.primaryImageTag,
              maxWidth = 300,
              token = server.accessToken,
            )
          }

        Row(
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .padding(horizontal = 20.dp),
          verticalAlignment = Alignment.Bottom,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Card(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier =
              Modifier
                .size(width = 86.dp, height = 126.dp)
                .clip(RoundedCornerShape(12.dp)),
          ) {
            RemoteImage(
              url = posterUrl,
              contentDescription = item.name,
              contentScale = ContentScale.Crop,
              modifier = Modifier.fillMaxSize(),
            )
          }

          Column(
            modifier = Modifier.padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            // Type Pill
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text =
                  when {
                    item.isSeries -> "TV SERIES"
                    item.type == "Movie" -> "MOVIE"
                    else -> item.type.uppercase()
                  },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }

            Text(
              text = item.name,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        // Tagline
        if (item.taglines.isNotEmpty()) {
          Text(
            text = "\"${item.taglines.first()}\"",
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        // Metadata Badges Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // Community Rating
          item.communityRating?.let { rating ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Star,
                  contentDescription = null,
                  tint = Color(0xFFFFC107),
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = "%.1f".format(rating),
                  style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          // Rotten Tomatoes / Critic Rating
          item.criticRating?.let { critic ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = "🍅",
                  style = MaterialTheme.typography.labelMedium,
                )
                Text(
                  text = "${critic.roundToInt()}%",
                  style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          // Official Rating (PG-13, TV-MA, etc.)
          item.officialRating?.let { official ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = official,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          // Production Year
          item.productionYear?.let { year ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          // Duration
          val durStr = item.formattedDuration
          if (durStr.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = durStr,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }

          // Quality Badge (4K / HDR)
          item.qualityBadge?.let { badge ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }

        // Genre Chips
        if (item.genres.isNotEmpty()) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            item.genres.forEach { genre ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
              ) {
                Text(
                  text = genre,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }
            }
          }
        }

        // Action Buttons Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Play / Resume Button
          Button(
            onClick = { onPlay(item, false) },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 12.dp),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                when {
                  item.progressPercent > 0.05f -> "Resume"
                  item.isSeries -> "Watch S1:E1"
                  else -> "Play Movie"
                },
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.labelLarge,
            )
          }

          // Favorite Toggle Button
          FilledTonalIconButton(
            onClick = { onToggleFavorite(item) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = if (item.isFavorite) Icons.RoundedFilled.Favorite else Icons.RoundedFilled.FavoriteBorder,
              contentDescription = "Favorite",
              tint = if (item.isFavorite) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }

          // Mark Watched Toggle Button
          FilledTonalIconButton(
            onClick = { onTogglePlayed(item) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = if (item.isPlayed) Icons.RoundedFilled.Check else Icons.RoundedFilled.Visibility,
              contentDescription = "Watched",
              tint = if (item.isPlayed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }
        }

        // Restart from Beginning option if in progress
        if (item.progressPercent > 0.05f) {
          OutlinedButton(
            onClick = { onPlay(item, true) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Refresh,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Play from Beginning", style = MaterialTheme.typography.labelMedium)
          }
        }

        // Overview / Synopsis with expand animation
        if (!item.overview.isNullOrBlank()) {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable { isOverviewExpanded = !isOverviewExpanded },
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "Storyline",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = item.overview,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = if (isOverviewExpanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = if (isOverviewExpanded) "Show less" else "Read more",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }

        // TV Shows: Seasons and Episodes Browser
        if (item.isSeries && seasons.isNotEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Text(
              text = "Episodes",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            // Seasons Chips Row
            Row(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              seasons.forEach { season ->
                val isSelected = season.id == selectedSeasonId
                FilterChip(
                  selected = isSelected,
                  onClick = { onSelectSeason(season.id) },
                  label = { Text(season.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                  shape = RoundedCornerShape(12.dp),
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                      selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
              }
            }

            // Episode List
            if (isEpisodesLoading) {
              Box(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
              }
            } else {
              Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                episodes.forEach { episode ->
                  JellyfinEpisodeCard(
                    item = episode,
                    server = server,
                    onPlay = { onPlay(episode, false) },
                  )
                }
              }
            }
          }
        }

        // Media Stream Technical Specs Box
        if (item.videoCodec != null || item.audioCodec != null) {
          Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "Technical Details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                item.videoCodec?.let {
                  Column {
                    Text(
                      text = "Video",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      text = "$it ${item.videoResolution ?: ""}",
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }

                item.audioCodec?.let {
                  Column {
                    Text(
                      text = "Audio",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      text = "$it ${item.audioChannels ?: ""}",
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }

                item.container?.let {
                  Column {
                    Text(
                      text = "Container",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                      text = it.uppercase(),
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = FontWeight.SemiBold,
                    )
                  }
                }
              }
            }
          }
        }

        // More Like This / Similar Titles
        if (similarItems.isNotEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "More Like This",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              contentPadding = PaddingValues(bottom = 8.dp),
            ) {
              items(similarItems, key = { it.id }) { similarItem ->
                JellyfinPosterCard(
                  item = similarItem,
                  server = server,
                  onClick = { onItemClick(similarItem) },
                  cardWidth = 120.dp,
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}
