/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.folderlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import app.gyrolet.mpvrx.ui.icons.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.ui.browser.cards.rememberVideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.cards.rememberThumbnailReveal
import app.gyrolet.mpvrx.ui.browser.cards.thumbnailReveal
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedItem
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.utils.storage.VideoScanUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.compose.koinInject

/** How many videos the Home carousel shows. */
internal const val ContinueWatchingMaxItems = 10

/**
 * History rows observed for the carousel. A little larger than [ContinueWatchingMaxItems] because
 * audio and streams are filtered out of this row.
 */
internal const val ContinueWatchingHistoryWindow = 20

/** Stand-in source used while the Continue Watching toggle is off, so no view model is created. */
internal val NoContinueWatchingItems: StateFlow<List<RecentlyPlayedItem>> =
  MutableStateFlow<List<RecentlyPlayedItem>>(emptyList()).asStateFlow()

private val CardWidth = 168.dp
private val CardShape = AppShapeScale.large

/** Local, non-audio videos from the existing Recently Played items, newest first. */
internal fun continueWatchingVideos(items: List<RecentlyPlayedItem>): List<Video> =
  items
    .asSequence()
    .filterIsInstance<RecentlyPlayedItem.VideoItem>()
    .map { it.video }
    .filter { video -> !video.isAudio && !video.path.isNetworkSource() }
    .take(ContinueWatchingMaxItems)
    .toList()

private fun String.isNetworkSource(): Boolean =
  startsWith("http://", ignoreCase = true) ||
    startsWith("https://", ignoreCase = true) ||
    startsWith("rtmp://", ignoreCase = true) ||
    startsWith("rtsp://", ignoreCase = true)

/**
 * Compact "Continue Watching" shelf for the top of Home. Callers only compose it when [videos] is
 * not empty, so the empty state is simply the absence of the section.
 */
@Composable
internal fun ContinueWatchingRow(
  videos: List<Video>,
  onVideoClick: (Video) -> Unit,
  onSeeAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playbackInfo = rememberSwipePlaybackInfo(videos)
  val showThumbnails = rememberVideoCardUiConfig().showThumbnails
  val activeCount = remember(videos, playbackInfo) {
    videos.count { video ->
      val info = playbackInfo[video.path]
      info != null && !info.isWatched
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(R.string.continue_watching_title),
          style = MaterialTheme.typography.titleMediumEmphasized,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.semantics { heading() },
        )
        if (activeCount > 0) {
          Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
              text = activeCount.toString(),
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
          }
        }
      }
      TextButton(
        onClick = onSeeAll,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        shape = CircleShape,
        modifier = Modifier.height(32.dp),
      ) {
        Text(
          text = stringResource(R.string.continue_watching_see_all),
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Icon(
          imageVector = Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      }
    }

    Text(
      text = stringResource(R.string.continue_watching_subtitle),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )

    LazyRow(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(items = videos, key = { it.path }, contentType = { "continue_watching_card" }) { video ->
        val info = playbackInfo[video.path]
        ContinueWatchingCard(
          video = video,
          // Same rule as the Recently Played shelf on Profile: no bar for finished videos.
          progress = info?.progressPercentage?.takeUnless { info.isWatched },
          timeRemaining = info?.timeRemaining?.takeUnless { info.isWatched },
          showThumbnail = showThumbnails,
          onClick = { onVideoClick(video) },
        )
      }
    }
  }
}

@Composable
private fun ContinueWatchingCard(
  video: Video,
  progress: Float?,
  timeRemaining: Long?,
  showThumbnail: Boolean,
  onClick: () -> Unit,
) {
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val density = LocalDensity.current
  val widthPx = with(density) { CardWidth.roundToPx() }
  val heightPx = widthPx * 9 / 16

  var thumbnail by remember(video.path, showThumbnail) {
    mutableStateOf(if (showThumbnail) thumbnailRepository.peekThumbnailFromMemory(video, widthPx, heightPx) else null)
  }
  // Runs only for cards LazyRow actually composes, so off-screen thumbnails are never decoded.
  LaunchedEffect(video.path, showThumbnail) {
    if (showThumbnail && thumbnail == null) {
      thumbnail = thumbnailRepository.getThumbnail(video, widthPx, heightPx)
    }
  }

  val hasDuration =
    video.durationFormatted.isNotBlank() && video.durationFormatted != "--:--" && video.durationFormatted != "--"
  val remainingLabel = timeRemaining?.takeIf { it > 0 }?.let { seconds ->
    val durationMs = seconds * 1000
    stringResource(R.string.timer_remaining, VideoScanUtils.formatDuration(durationMs))
  }

  Column(
    modifier =
      Modifier
        .width(CardWidth)
        .tvFocusHighlight(CardShape)
        .clip(CardShape)
        .clickable(onClick = onClick),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .clip(CardShape)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      val bitmap = thumbnail
      if (bitmap != null) {
        val reveal = rememberThumbnailReveal(true, video.path)
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize().thumbnailReveal(reveal),
        )
      } else {
        // Missing or still-loading thumbnail: a quiet placeholder rather than an empty hole.
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.align(Alignment.Center).size(32.dp),
        )
      }

      // Bottom scrim keeps the duration chip and progress bar readable on any artwork.
      Box(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(56.dp)
            .background(
              Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.55f),
              ),
            ),
      )

      // Resume affordance: a soft play badge so the card reads as "pick up here".
      Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
        modifier = Modifier.align(Alignment.Center).size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.RoundedFilled.PlayArrow,
            contentDescription = stringResource(R.string.ui_play),
            modifier = Modifier.size(22.dp),
          )
        }
      }

      if (hasDuration) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color.Black.copy(alpha = 0.75f),
          contentColor = Color.White,
          modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 8.dp),
        ) {
          Text(
            text = video.durationFormatted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          )
        }
      }

      if (remainingLabel != null) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = Color.Black.copy(alpha = 0.75f),
          contentColor = Color.White,
          modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 8.dp),
        ) {
          Text(
            text = remainingLabel,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          )
        }
      }

      if (progress != null && progress > 0f) {
        Box(
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .padding(horizontal = 6.dp, vertical = 5.dp)
              .height(4.dp)
              .clip(RoundedCornerShape(2.dp))
              .background(Color.White.copy(alpha = 0.25f)),
        ) {
          Box(
            modifier =
              Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
          )
        }
      }
    }

    Text(
      text = video.displayName,
      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 2,
      minLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
    )
  }
}
