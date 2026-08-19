/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.delay

/**
 * Featured Hero Carousel Banner for Torrent Tab (JellyCine style).
 * Displays full-width 16:9 backdrop with smooth gradient overlay,
 * metadata pills, title, year, size, file count, and prominent action buttons.
 */
@Composable
fun TorrentHeroBanner(
  groups: List<TorrentStreamGroup>,
  onPlay: (TorrentStreamGroup) -> Unit,
  onDetails: (TorrentStreamGroup) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (groups.isEmpty()) return

  val pagerState = rememberPagerState(pageCount = { groups.size })

  // Auto-advance every 6 seconds
  LaunchedEffect(pagerState.pageCount) {
    if (groups.size <= 1) return@LaunchedEffect
    while (true) {
      delay(6000)
      val nextPage = (pagerState.currentPage + 1) % groups.size
      pagerState.animateScrollToPage(
        nextPage,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow),
      )
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxWidth(),
      pageSpacing = 16.dp,
    ) { page ->
      val group = groups[page]
      val backdropUrl = group.backdropUrl ?: group.posterUrl

      Card(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(16f / 10f)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .clickable { onDetails(group) },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          // Backdrop Artwork Image
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
                          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
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
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
              )
            }
          }

          // Double Gradient Scrim (Top subtle + Bottom dramatic)
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(
                  Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.4f to Color.Transparent,
                    0.65f to Color.Black.copy(alpha = 0.65f),
                    1f to Color.Black.copy(alpha = 0.95f),
                  ),
                ),
          )

          // Content Layer at Bottom
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            // Badges Bar
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // Type / Source Pill
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

              // Year Pill
              group.releaseYear?.takeIf(String::isNotBlank)?.let { year ->
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color.Black.copy(alpha = 0.6f),
                  border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                ) {
                  Text(
                    text = year,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }

              // File Count Pill
              if (group.files.isNotEmpty()) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color.Black.copy(alpha = 0.6f),
                  border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                ) {
                  Text(
                    text = "${group.files.size} ${if (group.files.size == 1) "File" else "Files"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }

              // Total Size Pill
              if (group.totalSize > 0L) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color.Black.copy(alpha = 0.6f),
                  border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                ) {
                  Text(
                    text = formatTorrentBytes(group.totalSize),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            }

            // Title
            Text(
              text = group.title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )

            // Overview / Tagline preview
            group.overview?.takeIf(String::isNotBlank)?.let { overview ->
              Text(
                text = overview,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }

            // Action Buttons
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Button(
                onClick = { onPlay(group) },
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                  ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Watch Now",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                )
              }

              FilledTonalButton(
                onClick = { onDetails(group) },
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                  ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Info,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Details",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.SemiBold,
                )
              }
            }
          }
        }
      }
    }

    // Animated Dot Indicators
    if (groups.size > 1) {
      Spacer(modifier = Modifier.height(10.dp))
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        repeat(groups.size) { index ->
          val isSelected = pagerState.currentPage == index
          val width by animateDpAsState(
            targetValue = if (isSelected) 22.dp else 6.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "dotWidth",
          )
          val color by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            label = "dotColor",
          )
          Box(
            modifier =
              Modifier
                .height(6.dp)
                .width(width)
                .clip(CircleShape)
                .background(color),
          )
        }
      }
    }
  }
}

/**
 * Section Header for Torrent tab with title, subtitle, and optional "See All" button.
 */
@Composable
fun TorrentSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onSeeAll: (() -> Unit)? = null,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f, fill = false)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (onSeeAll != null) {
      TextButton(
        onClick = onSeeAll,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
      ) {
        Text(
          text = "See All",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

/**
 * Continue Watching Card for Torrent Streams (16:9 aspect ratio).
 * Features backdrop artwork, title, file name, size, and one-tap resume.
 */
@Composable
fun TorrentResumeCard(
  entry: NetworkStreamEntryEntity,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val backdropUrl = entry.backdropUrl ?: entry.posterUrl

  Card(
    modifier =
      modifier
        .width(240.dp)
        .aspectRatio(16f / 9.5f)
        .shadow(6.dp, RoundedCornerShape(18.dp))
        .clip(RoundedCornerShape(18.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      if (!backdropUrl.isNullOrBlank()) {
        RemoteImage(
          url = backdropUrl,
          contentDescription = entry.fileName,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
        )
      } else {
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainerHighest),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
          )
        }
      }

      // Dark gradient overlay
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                0.3f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.88f),
              ),
            ),
      )

      // Center Play Pill
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        modifier =
          Modifier
            .align(Alignment.Center)
            .size(40.dp)
            .shadow(4.dp, CircleShape),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.RoundedFilled.PlayArrow,
            contentDescription = "Play",
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
          )
        }
      }

      // Title & File info at bottom
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .align(Alignment.BottomStart)
            .padding(10.dp),
      ) {
        Text(
          text = entry.groupTitle ?: entry.fileName,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (entry.groupTitle != null && entry.groupTitle != entry.fileName) {
          Text(
            text = entry.fileName,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

/**
 * 2:3 Vertical Poster Card for Torrent Groups (JellyCine style).
 * Features poster artwork, year badge, file count badge, total size, and glowing progress/status.
 */
@Composable
fun TorrentPosterCard(
  group: TorrentStreamGroup,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val posterUrl = group.posterUrl ?: group.backdropUrl

  Card(
    modifier =
      modifier
        .width(135.dp)
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // 2:3 Poster Image Box
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .shadow(6.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
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
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CloudDownload,
              contentDescription = null,
              modifier = Modifier.size(42.dp),
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )
          }
        }

        // Top-left file count badge
        if (group.files.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.72f),
            modifier =
              Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
          ) {
            Text(
              text = "${group.files.size} eps",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }

        // Bottom gradient for subtle depth
        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .height(48.dp)
              .align(Alignment.BottomCenter)
              .background(
                Brush.verticalGradient(
                  0f to Color.Transparent,
                  1f to Color.Black.copy(alpha = 0.65f),
                ),
              ),
        )

        // Bottom size badge
        if (group.totalSize > 0L) {
          Text(
            text = formatTorrentBytes(group.totalSize),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f),
            modifier =
              Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
          )
        }
      }

      // Title & Year info below poster
      Text(
        text = group.title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 2.dp),
      )

      group.releaseYear?.takeIf(String::isNotBlank)?.let { year ->
        Text(
          text = year,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 2.dp),
        )
      }
    }
  }
}

/**
 * Horizontal scrolling section of Torrent cards with a section header.
 */
@Composable
fun TorrentHorizontalSection(
  title: String,
  groups: List<TorrentStreamGroup>,
  onGroupClick: (TorrentStreamGroup) -> Unit,
  onGroupLongClick: ((TorrentStreamGroup) -> Unit)? = null,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  onSeeAll: (() -> Unit)? = null,
) {
  if (groups.isEmpty()) return

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TorrentSectionHeader(
      title = title,
      subtitle = subtitle,
      onSeeAll = onSeeAll,
    )

    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(groups, key = { it.id }) { group ->
        TorrentPosterCard(
          group = group,
          onClick = { onGroupClick(group) },
          onLongClick = onGroupLongClick?.let { { it(group) } },
        )
      }
    }
  }
}
