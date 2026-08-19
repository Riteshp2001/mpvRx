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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import app.gyrolet.mpvrx.utils.media.MediaUtils
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val StarYellow = Color(0xFFFFC107)

private fun buildStarSubtitle(
  partsBeforeRating: List<String>,
  communityRating: Double?,
  criticRating: Double?,
  partsAfterRating: List<String> = emptyList(),
): AnnotatedString {
  return buildAnnotatedString {
    var hasContent = false
    partsBeforeRating.filter { it.isNotBlank() }.forEach { part ->
      if (hasContent) append(" • ")
      append(part)
      hasContent = true
    }
    communityRating?.let { rating ->
      if (hasContent) append(" • ")
      withStyle(SpanStyle(color = StarYellow, fontSize = 13.5.sp)) {
        append("★ ")
      }
      append("%.1f".format(rating))
      hasContent = true
    }
    criticRating?.let { critic ->
      if (hasContent) append(" • ")
      append("🍅 ${critic.roundToInt()}%")
      hasContent = true
    }
    partsAfterRating.filter { it.isNotBlank() }.forEach { part ->
      if (hasContent) append(" • ")
      append(part)
      hasContent = true
    }
  }
}

// ============================================================================
// Hero Featured Carousel Banner (Material 3 Expressive)
// ============================================================================

@Composable
fun JellyfinHeroBanner(
  items: List<JellyfinItem>,
  server: JellyfinServer,
  onPlay: (JellyfinItem) -> Unit,
  onDetails: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (items.isEmpty()) return

  val pageCount = if (items.size > 1) Int.MAX_VALUE else items.size
  val initialPage = remember(items) {
    if (items.size > 1) {
      val middle = Int.MAX_VALUE / 2
      middle - (middle % items.size)
    } else 0
  }
  val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })

  // Auto-scroll every 5 seconds when not touched
  LaunchedEffect(pagerState.settledPage, items.size) {
    if (items.size > 1) {
      delay(5000L)
      if (!pagerState.isScrollInProgress) {
        pagerState.animateScrollToPage(pagerState.currentPage + 1, animationSpec = tween(800))
      }
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .height(360.dp),
  ) {
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
    ) { page ->
      val item = items[page % items.size]
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

      Box(modifier = Modifier.fillMaxSize()) {
        // High-res backdrop artwork
        RemoteImage(
          url = backdropUrl,
          contentDescription = item.name,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )

        // Gradient Scrim: top dark for status/topbar, bottom gradient melting into background
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  0.0f to Color.Black.copy(alpha = 0.6f),
                  0.3f to Color.Transparent,
                  0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                  1.0f to MaterialTheme.colorScheme.background,
                ),
              ),
        )

        // Hero Info Overlay
        Column(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(horizontal = 20.dp, vertical = 16.dp),
          verticalArrangement = Arrangement.Bottom,
        ) {
          // Badges Row
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp),
          ) {
            // Type Pill (Featured)
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            ) {
              Text(
                text = if (item.isSeries) "SERIES" else "MOVIE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }

            // Rating Pill
            item.communityRating?.let { rating ->
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.6f),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Star,
                    contentDescription = null,
                    tint = Color(0xFFFFC107),
                    modifier = Modifier.size(12.dp),
                  )
                  Text(
                    text = "%.1f".format(rating),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                  )
                }
              }
            }

            // Rotten Tomatoes / Critic Rating Pill
            item.criticRating?.let { critic ->
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.6f),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                  Text(
                    text = "🍅",
                    style = MaterialTheme.typography.labelSmall,
                  )
                  Text(
                    text = "${critic.roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                  )
                }
              }
            }

            // Quality Badge (4K / HDR)
            item.qualityBadge?.let { badge ->
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.Black.copy(alpha = 0.6f),
              ) {
                Text(
                  text = badge,
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }

            // Year
            item.productionYear?.let { year ->
              Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          // Title
          Text(
            text = item.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )

          // Genres / Tagline
          val metaText =
            when {
              item.genres.isNotEmpty() -> item.genresString
              !item.taglines.isEmpty() -> item.taglines.first()
              item.isSeries && item.childCount != null -> "${item.childCount} Seasons"
              else -> item.type
            }

          if (metaText.isNotBlank()) {
            Text(
              text = metaText,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
          }

          // Action Buttons Row
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Button(
              onClick = { onPlay(item) },
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
              Icon(
                imageVector = if (item.isSeries && item.progressPercent <= 0.05f) Icons.RoundedFilled.Tv else Icons.RoundedFilled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text =
                  when {
                    item.progressPercent > 0.05f -> "Resume"
                    item.isSeries -> "Explore Series"
                    else -> "Watch Now"
                  },
                fontWeight = FontWeight.Bold,
              )
            }

            FilledTonalButton(
              onClick = { onDetails(item) },
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(text = "Details", fontWeight = FontWeight.Medium)
            }
          }
        }
      }
    }

    // Sliding 5-Dot Worm Indicator (AFinity style)
    if (items.size > 1) {
      SlidingWormDotsIndicator(
        pagerState = pagerState,
        pageCount = items.size,
        modifier =
          Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 20.dp, bottom = 16.dp),
      )
    }
  }
}

@Composable
private fun SlidingWormDotsIndicator(
  pagerState: PagerState,
  pageCount: Int,
  modifier: Modifier = Modifier,
  visibleDotCount: Int = 5,
) {
  if (pageCount <= 1) return

  val fraction by remember(pagerState, pageCount) {
    derivedStateOf {
      val rawPage = pagerState.currentPage
      val offset = pagerState.currentPageOffsetFraction
      val page = rawPage % pageCount
      (page.toFloat() + offset).coerceIn(0f, (pageCount - 1).toFloat())
    }
  }

  val maxStart = (pageCount - visibleDotCount).coerceAtLeast(0).toFloat()
  val windowStart = (fraction - (visibleDotCount - 1) / 2f).coerceIn(0f, maxStart)

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    val countToRender = visibleDotCount.coerceAtMost(pageCount)
    repeat(countToRender) { dotIndex ->
      val itemIndex = windowStart + dotIndex
      val dist = kotlin.math.abs(fraction - itemIndex)
      val activeFactor = (1f - dist).coerceIn(0f, 1f)

      val isLeftEdge = dotIndex == 0 && windowStart > 0.1f
      val isRightEdge = dotIndex == countToRender - 1 && windowStart < maxStart - 0.1f
      val edgeScale = if (isLeftEdge || isRightEdge) 0.65f else 1.0f

      val dotWidth = (6f + 12f * activeFactor) * edgeScale
      val dotHeight = (5f + 1f * activeFactor) * edgeScale
      val dotAlpha = 0.35f + 0.65f * activeFactor

      Box(
        modifier =
          Modifier
            .height(dotHeight.dp)
            .width(dotWidth.dp)
            .clip(CircleShape)
            .background(
              if (activeFactor > 0.4f) {
                MaterialTheme.colorScheme.primary.copy(alpha = dotAlpha)
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dotAlpha)
              },
            ),
      )
    }
  }
}

// ============================================================================
// Section Header with Optional "See All" Action
// ============================================================================

@Composable
fun JellyfinSectionHeader(
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
    Column {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (!subtitle.isNullOrBlank()) {
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
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
      ) {
        Text(
          text = "See All",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
        )
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

// ============================================================================
// Horizontal Scroll Section Container
// ============================================================================

@Composable
fun JellyfinHorizontalSection(
  title: String,
  items: List<JellyfinItem>,
  server: JellyfinServer,
  onItemClick: (JellyfinItem) -> Unit,
  onItemLongClick: ((JellyfinItem) -> Unit)? = null,
  modifier: Modifier = Modifier,
  onSeeAll: (() -> Unit)? = null,
  subtitle: String? = null,
  isContinueWatching: Boolean = false,
) {
  if (items.isEmpty()) return

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    JellyfinSectionHeader(
      title = title,
      subtitle = subtitle,
      onSeeAll = onSeeAll,
    )

    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(items, key = { it.id }) { item ->
        if (isContinueWatching) {
          JellyfinResumeCard(
            item = item,
            server = server,
            onClick = { onItemClick(item) },
            onLongClick = onItemLongClick?.let { { it(item) } },
          )
        } else {
          JellyfinPosterCard(
            item = item,
            server = server,
            onClick = { onItemClick(item) },
            onLongClick = onItemLongClick?.let { { it(item) } },
            cardWidth = 136.dp,
          )
        }
      }
    }
  }
}

// ============================================================================
// Continue Watching Card (16:9 Cinematic Backdrop)
// ============================================================================

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
          maxWidth = 540,
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
        .width(230.dp)
        .clip(RoundedCornerShape(16.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
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

        // Gradient at bottom of thumbnail for progress legibility
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  0.5f to Color.Transparent,
                  1.0f to Color.Black.copy(alpha = 0.7f),
                ),
              ),
        )

        // Play Button Overlay
        if (!isInSelectionMode && !isSelected) {
          Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            modifier =
              Modifier
                .size(40.dp)
                .align(Alignment.Center),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.PlayArrow,
                contentDescription = "Play",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
              )
            }
          }
        }

        // Selection Checkmark
        if (isSelected) {
          Box(
            modifier =
              Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
          ) {
            Surface(
              shape = CircleShape,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(34.dp),
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.RoundedFilled.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        }

        // Remaining runtime chip
        val remaining = item.formattedRemainingDuration
        if (remaining.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Black.copy(alpha = 0.75f),
            modifier =
              Modifier
                .padding(6.dp)
                .align(Alignment.BottomEnd),
          ) {
            Text(
              text = remaining,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
              color = Color.White,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }

        // Progress bar at bottom
        if (item.progressPercent > 0.01f) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(4.dp),
          ) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
            Box(
              modifier =
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(item.progressPercent.coerceIn(0f, 1f))
                  .background(MaterialTheme.colorScheme.primary),
            )
          }
        }
      }

      Column(modifier = Modifier.padding(10.dp)) {
        val title = item.seriesName ?: item.name
        val subtitle =
          if (item.seriesName != null && item.indexNumber != null) {
            AnnotatedString("S${item.parentIndexNumber ?: 1}:E${item.indexNumber} • ${item.name}")
          } else {
            val before = buildList {
              item.productionYear?.let { add(it.toString()) }
              val dur = item.formattedDuration
              if (dur.isNotBlank()) add(dur) else if (item.productionYear == null) add(item.type)
            }
            buildStarSubtitle(before, item.communityRating, item.criticRating)
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

// ============================================================================
// Modern Poster Card (2:3 Aspect Ratio)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JellyfinPosterCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp? = null,
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

  val cardModifier =
    if (cardWidth != null) {
      modifier.width(cardWidth)
    } else {
      modifier.fillMaxWidth()
    }

  Card(
    modifier =
      cardModifier
        .clip(RoundedCornerShape(14.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
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
                item.isSeries -> Icons.RoundedFilled.Tv
                else -> Icons.RoundedFilled.Movie
              }
            Icon(
              imageVector = placeholderIcon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(36.dp),
            )
          }
        }

        // Top badge: Quality on left
        item.qualityBadge?.let { qBadge ->
          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier =
              Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
          ) {
            Text(
              text = qBadge,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
          }
        }

        // Progress bar if partially watched
        if (item.progressPercent > 0.02f && !item.isPlayed) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(4.dp),
          ) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
            Box(
              modifier =
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(item.progressPercent.coerceIn(0f, 1f))
                  .background(MaterialTheme.colorScheme.primary),
            )
          }
        }

        // Selection / Played Check badge
        if (isSelected) {
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .padding(6.dp)
                .size(24.dp)
                .align(Alignment.BottomEnd),
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
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            modifier =
              Modifier
                .padding(6.dp)
                .size(20.dp)
                .align(Alignment.BottomEnd),
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
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val subtitle =
          run {
            val before = buildList {
              item.productionYear?.let { add(it.toString()) }
                ?: if (item.isSeries && item.childCount != null) {
                  add("${item.childCount} Seasons")
                } else {
                  add(item.type)
                }
            }
            buildStarSubtitle(before, item.communityRating, item.criticRating)
          }
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

// ============================================================================
// Library Filter Chips Row
// ============================================================================

@Composable
fun JellyfinLibraryChipRow(
  libraries: List<JellyfinItem>,
  selectedLibraryId: String?,
  onSelectLibrary: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (libraries.isEmpty()) return

  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = selectedLibraryId == null,
      onClick = { onSelectLibrary(null) },
      label = { Text("All", fontWeight = if (selectedLibraryId == null) FontWeight.Bold else FontWeight.Normal) },
      shape = RoundedCornerShape(12.dp),
      colors =
        FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )

    libraries.forEach { library ->
      val isSelected = selectedLibraryId == library.id
      val colType = library.collectionType?.lowercase() ?: library.type.lowercase()
      val libName = library.name.lowercase()
      val icon =
        when {
          libName.contains("anime") || colType.contains("anime") -> Icons.RoundedFilled.Movie
          colType == "movies" || library.type.lowercase() == "movie" -> Icons.RoundedFilled.Movie
          colType == "tvshows" || library.type.lowercase() == "series" -> Icons.RoundedFilled.Tv
          colType == "music" || library.type.lowercase() == "audio" -> Icons.RoundedFilled.Audiotrack
          else -> Icons.RoundedFilled.Folder
        }

      FilterChip(
        selected = isSelected,
        onClick = { onSelectLibrary(if (isSelected) null else library.id) },
        leadingIcon = {
          Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
          )
        },
        label = { Text(library.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
        shape = RoundedCornerShape(12.dp),
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
          ),
      )
    }
  }
}

// ============================================================================
// Library Card (Directory Card)
// ============================================================================

@Composable
fun JellyfinLibraryCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp = 220.dp,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.primaryImageTag, item.backdropImageTag, server.accessToken) {
      if (!item.primaryImageTag.isNullOrBlank()) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = item.primaryImageTag,
          maxWidth = 800,
          token = server.accessToken,
        )
      } else if (!item.backdropImageTag.isNullOrBlank()) {
        JellyfinClient.getBackdropUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = item.backdropImageTag,
          maxWidth = 1000,
          token = server.accessToken,
        )
      } else {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = null,
          maxWidth = 800,
          token = server.accessToken,
        )
      }
    }

  val colType = item.collectionType?.lowercase() ?: item.type.lowercase()
  val itemName = item.name.lowercase()
  val icon =
    when {
      itemName.contains("anime") || colType.contains("anime") -> Icons.RoundedFilled.Movie
      colType == "movies" || item.type.lowercase() == "movie" -> Icons.RoundedFilled.Movie
      colType == "tvshows" || item.type.lowercase() == "series" -> Icons.RoundedFilled.Tv
      colType == "music" || item.type.lowercase() == "audio" -> Icons.RoundedFilled.Audiotrack
      else -> Icons.RoundedFilled.Folder
    }

  Column(
    modifier =
      modifier
        .width(cardWidth)
        .clickable(onClick = onClick),
  ) {
    Card(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .clip(RoundedCornerShape(16.dp)),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        if (!item.primaryImageTag.isNullOrBlank() || !item.backdropImageTag.isNullOrBlank()) {
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
              imageVector = icon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(40.dp),
            )
          }
        }

        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color.Black.copy(alpha = 0.65f),
          modifier =
            Modifier
              .padding(8.dp)
              .align(Alignment.BottomEnd),
        ) {
          Box(
            modifier = Modifier.padding(5.dp),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = icon,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(15.dp),
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = item.name,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

// ============================================================================
// Episode Card (For Series Episodes View)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
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
        maxWidth = 360,
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
        .clip(RoundedCornerShape(14.dp))
        .combinedClickable(
          onClick = onPlay,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier
            .width(112.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
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

        // Progress bar
        if (item.progressPercent > 0.02f && !item.isPlayed) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(4.dp),
          ) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
            Box(
              modifier =
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(item.progressPercent.coerceIn(0f, 1f))
                  .background(MaterialTheme.colorScheme.primary),
            )
          }
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
              contentDescription = null,
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
        val epPrefix = if (item.indexNumber != null) "EPISODE ${item.indexNumber} • " else ""
        Text(
          text = "$epPrefix${item.name}",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold,
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
        val epMeta =
          run {
            val after = buildList {
              val dur = item.formattedDuration
              if (dur.isNotBlank()) add(dur)
            }
            buildStarSubtitle(emptyList(), item.communityRating, item.criticRating, after)
          }
        if (epMeta.isNotBlank()) {
          Text(
            text = epMeta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }

      IconButton(onClick = onPlay) {
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = "Play",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

// ============================================================================
// List Item Card (List Mode in Browsing)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
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
        maxWidth = 240,
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
        .clip(RoundedCornerShape(14.dp))
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(width = 68.dp, height = 96.dp)
            .clip(RoundedCornerShape(10.dp))
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
                item.isSeries -> Icons.RoundedFilled.Tv
                else -> Icons.RoundedFilled.Movie
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
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .height(4.dp),
          ) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
            Box(
              modifier =
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(item.progressPercent.coerceIn(0f, 1f))
                  .background(MaterialTheme.colorScheme.primary),
            )
          }
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
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val details =
          run {
            val before = buildList {
              item.productionYear?.let { add(it.toString()) }
              if (item.isSeries && item.childCount != null) {
                add("${item.childCount} Seasons")
              } else if (item.productionYear == null) {
                add(item.type)
              }
            }
            val after = buildList {
              item.qualityBadge?.let { add(it) }
            }
            buildStarSubtitle(before, item.communityRating, item.criticRating, after)
          }

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
