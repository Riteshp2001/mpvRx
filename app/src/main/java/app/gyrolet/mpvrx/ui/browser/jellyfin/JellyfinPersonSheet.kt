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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinPerson
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icons

private enum class PersonMediaFilter(val label: String) {
  ALL("All"),
  MOVIES("Movies"),
  SERIES("TV Shows"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JellyfinPersonSheet(
  person: JellyfinPerson?,
  server: JellyfinServer,
  overview: String?,
  media: List<JellyfinItem>,
  isLoading: Boolean,
  onDismiss: () -> Unit,
  onItemClick: (JellyfinItem) -> Unit,
  sheetState: SheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    ),
) {
  if (person == null) return

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // ── Person Hero Header ──────────────────────────────────────────
      val avatarUrl = remember(server.serverUrl, person.id, person.primaryImageTag, server.accessToken) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = person.id,
          imageTag = person.primaryImageTag,
          maxWidth = 300,
          token = server.accessToken,
        )
      }

      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                  Color.Transparent,
                ),
              ),
            )
            .padding(16.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            // Circular Avatar
            if (!person.primaryImageTag.isNullOrBlank()) {
              RemoteImage(
                url = avatarUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                  .size(80.dp)
                  .clip(CircleShape),
              )
            } else {
              Box(
                modifier = Modifier
                  .size(80.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = person.name.take(1).uppercase(),
                  style = MaterialTheme.typography.headlineMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              }
            }

            // Name & Role Info
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = person.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )

              val roleText = person.role?.takeIf { it.isNotBlank() } ?: person.type
              if (!roleText.isNullOrBlank()) {
                Text(
                  text = roleText,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }

              if (!isLoading && media.isNotEmpty()) {
                val movieCount = media.count { it.type == "Movie" }
                val seriesCount = media.count { it.isSeries || it.type == "Series" }
                val stats = buildList {
                  if (movieCount > 0) add("$movieCount ${if (movieCount == 1) "Movie" else "Movies"}")
                  if (seriesCount > 0) add("$seriesCount ${if (seriesCount == 1) "Series" else "Series"}")
                  if (isEmpty()) add("${media.size} Titles")
                }.joinToString(" • ")

                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                  Text(
                    text = stats,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                  )
                }
              }
            }
          }
        }
      }

      // ── Biography Section ─────────────────────────────────────────
      if (!overview.isNullOrBlank()) {
        var isBioExpanded by remember { mutableStateOf(false) }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(14.dp)
              .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = "Biography",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
              text = overview,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              lineHeight = 20.sp,
              maxLines = if (isBioExpanded) Int.MAX_VALUE else 3,
              overflow = TextOverflow.Ellipsis,
            )

            if (overview.length > 140) {
              TextButton(
                onClick = { isBioExpanded = !isBioExpanded },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(28.dp),
              ) {
                Text(
                  text = if (isBioExpanded) "Read less" else "Read more",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }

      // ── Filmography Media Section ────────────────────────────────
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        var selectedFilter by remember { mutableStateOf(PersonMediaFilter.ALL) }
        val movies = remember(media) { media.filter { it.type == "Movie" } }
        val series = remember(media) { media.filter { it.isSeries || it.type == "Series" } }

        val filteredMedia = remember(media, selectedFilter) {
          when (selectedFilter) {
            PersonMediaFilter.ALL -> media
            PersonMediaFilter.MOVIES -> movies
            PersonMediaFilter.SERIES -> series
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Known For",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )

          if (movies.isNotEmpty() && series.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              PersonMediaFilter.entries.forEach { filter ->
                val count = when (filter) {
                  PersonMediaFilter.ALL -> media.size
                  PersonMediaFilter.MOVIES -> movies.size
                  PersonMediaFilter.SERIES -> series.size
                }
                if (count > 0) {
                  FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text("${filter.label} ($count)", style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                      selectedContainerColor = MaterialTheme.colorScheme.primary,
                      selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                  )
                }
              }
            }
          }
        }

        if (isLoading) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(180.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              modifier = Modifier.size(36.dp),
              color = MaterialTheme.colorScheme.primary,
            )
          }
        } else if (filteredMedia.isEmpty()) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "No titles found in your library for this person",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
          }
        } else {
          // Responsive 3-column or 2-column Grid
          BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 600.dp) 4 else if (maxWidth >= 400.dp) 3 else 2
            val spacing = 10.dp
            val totalSpacing = spacing * (columns - 1)
            val cardWidth = (maxWidth - totalSpacing) / columns

            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(spacing),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              maxItemsInEachRow = columns,
            ) {
              filteredMedia.forEach { item ->
                JellyfinPosterCard(
                  item = item,
                  server = server,
                  onClick = { onItemClick(item) },
                  cardWidth = cardWidth,
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}
