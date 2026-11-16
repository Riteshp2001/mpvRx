/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.folderlist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.cards.cardOverlay
import app.gyrolet.mpvrx.ui.browser.cards.rememberThumbnailReveal
import app.gyrolet.mpvrx.ui.browser.cards.rememberVideoCardUiConfig
import app.gyrolet.mpvrx.ui.browser.cards.thumbnailReveal
import app.gyrolet.mpvrx.ui.browser.components.rememberSwipePlaybackInfo
import app.gyrolet.mpvrx.ui.browser.recentlyplayed.RecentlyPlayedItem
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive
import app.gyrolet.mpvrx.ui.theme.appPressFeedback
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
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

private val CardWidth = 176.dp
private val CardShape = AppShapeScale.large
private val ThumbnailShape =
  RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 10.dp, bottomEnd = 10.dp)

/** Local, non-audio videos from the existing Recently Played items, newest first. */
internal fun continueWatchingVideos(items: List<RecentlyPlayedItem>): List<Video> =
  items
    .asSequence()
    .filterIsInstance<RecentlyPlayedItem.VideoItem>()
    .map { it.video }
    .filter { video -> !video.isAudio && !FileTypeUtils.isNetworkStreamUri(video.path) }
    .take(ContinueWatchingMaxItems)
    .toList()

/**
 * Collapsible Material 3 Expressive "Continue Watching" panel for the top of Home.
 * Callers only compose it when [videos] is not empty, so the empty state is the absence of the panel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ContinueWatchingRow(
  videos: List<Video>,
  onVideoClick: (Video) -> Unit,
  onSeeAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val isExpanded by browserPreferences.continueWatchingExpanded.collectAsState()
  val haptics = rememberAppHaptics()
  val reduceMotion = AppMotion.shouldReduceMotion()
  val wallpaperActive = LocalAppWallpaperActive.current

  val playbackInfo = rememberSwipePlaybackInfo(videos)
  val cardUiConfig = rememberVideoCardUiConfig()
  val showThumbnails = cardUiConfig.showThumbnails
  val showExtensions = cardUiConfig.showExtensionField

  val activeCount =
    remember(videos, playbackInfo) {
      videos.count { video ->
        val info = playbackInfo[video.path]
        info != null && !info.isWatched
      }
    }

  val badgeCount = if (activeCount > 0) activeCount else videos.size
  val leadVideo =
    remember(videos, playbackInfo) {
      videos.firstOrNull { video ->
        val info = playbackInfo[video.path]
        info != null && !info.isWatched
      } ?: videos.firstOrNull()
    }
  val leadTitle =
    remember(leadVideo, showExtensions) {
      leadVideo?.let { video ->
        if (showExtensions) video.displayName else FileTypeUtils.stripExtension(video.displayName)
      }
    }
  val leadRemainingSec =
    leadVideo?.let { video ->
      val info = playbackInfo[video.path]
      info?.timeRemaining?.takeIf { !info.isWatched && it > 0L }
    }
  val leadRemainingLabel =
    leadRemainingSec?.let { seconds ->
      stringResource(R.string.timer_remaining, VideoScanUtils.formatDuration(seconds * 1000L))
    }
  val defaultSubtitle = stringResource(R.string.continue_watching_subtitle)
  val collapsedSummary =
    remember(leadTitle, leadRemainingLabel, defaultSubtitle) {
      when {
        leadTitle.isNullOrBlank() -> defaultSubtitle
        leadRemainingLabel != null -> "$leadTitle · $leadRemainingLabel"
        else -> leadTitle
      }
    }

  val cornerRadius by animateDpAsState(
    targetValue = if (isExpanded) 28.dp else 22.dp,
    animationSpec = if (reduceMotion) AppMotion.ReducedDp else AppMotion.Spatial.SnappyDp,
    label = "continueWatchingCornerRadius",
  )
  val chevronRotation by animateFloatAsState(
    targetValue = if (isExpanded) 180f else 0f,
    animationSpec = if (reduceMotion) snap() else AppMotion.Spatial.ExpressiveFast,
    label = "continueWatchingChevron",
  )
  val panelShape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
  val headerInteraction = remember { MutableInteractionSource() }
  val toggleLabel = stringResource(if (isExpanded) R.string.generic_collapse else R.string.generic_expand)

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 4.dp),
    shape = panelShape,
    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = if (wallpaperActive) 0.88f else 1f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .appPressFeedback(headerInteraction, pressedScale = 0.99f, reduceMotion = reduceMotion)
            .tvFocusHighlight(panelShape)
            .clickable(
              interactionSource = headerInteraction,
              indication = androidx.compose.material3.ripple(),
              role = Role.Button,
              onClickLabel = toggleLabel,
              onClick = {
                haptics.tick()
                browserPreferences.continueWatchingExpanded.set(!isExpanded)
              },
            )
            .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
          shape = AppShapeScale.medium,
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.size(38.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.RoundedFilled.History,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
          }
        }

        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = stringResource(R.string.continue_watching_title),
              style = MaterialTheme.typography.titleMediumEmphasized,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            if (badgeCount > 0) {
              Surface(
                shape = CircleShape,
                color =
                  if (activeCount > 0) {
                    MaterialTheme.colorScheme.primaryContainer
                  } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                  },
              ) {
                Text(
                  text = badgeCount.toString(),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color =
                    if (activeCount > 0) {
                      MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
              }
            }
          }

          AnimatedContent(
            targetState = if (isExpanded) defaultSubtitle else collapsedSummary,
            transitionSpec = {
              if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
              } else {
                fadeIn(AppMotion.Effect.Alpha) togetherWith fadeOut(AppMotion.Effect.Alpha)
              }
            },
            label = "continueWatchingSubtitle",
          ) { subtitleText ->
            Text(
              text = subtitleText,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          val seeAllInteraction = remember { MutableInteractionSource() }
          Surface(
            onClick = onSeeAll,
            interactionSource = seeAllInteraction,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier
                .height(32.dp)
                .appPressFeedback(seeAllInteraction, pressedScale = 0.96f, reduceMotion = reduceMotion)
                .tvFocusHighlight(CircleShape),
          ) {
            Row(
              modifier = Modifier.padding(start = 12.dp, end = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Text(
                text = stringResource(R.string.continue_watching_see_all),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
              )
              Icon(
                imageVector = Icons.RoundedFilled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            }
          }

          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
              )
            }
          }
        }
      }

      AnimatedVisibility(
        visible = isExpanded,
        enter =
          if (reduceMotion) {
            EnterTransition.None
          } else {
            expandVertically(
              animationSpec = AppMotion.IntSizeSpring,
              expandFrom = Alignment.Top,
            ) + fadeIn(animationSpec = AppMotion.Effect.Alpha)
          },
        exit =
          if (reduceMotion) {
            ExitTransition.None
          } else {
            shrinkVertically(
              animationSpec = AppMotion.IntSizeSpring,
              shrinkTowards = Alignment.Top,
            ) + fadeOut(animationSpec = AppMotion.Effect.Alpha)
          },
      ) {
        LazyRow(
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(items = videos, key = { it.path }, contentType = { "continue_watching_card" }) { video ->
            val info = playbackInfo[video.path]
            ContinueWatchingCard(
              video = video,
              // Same rule as the Recently Played shelf on Profile: no bar for finished videos.
              progress = info?.progressPercentage?.takeUnless { info.isWatched },
              timeRemaining = info?.timeRemaining?.takeUnless { info.isWatched },
              showThumbnail = showThumbnails,
              showExtension = showExtensions,
              reduceMotion = reduceMotion,
              onClick = { onVideoClick(video) },
            )
          }
        }
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
  showExtension: Boolean,
  reduceMotion: Boolean,
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
  val hasProgress = progress != null && progress > 0f
  val remainingLabel =
    timeRemaining?.takeIf { it > 0L }?.let { seconds ->
      stringResource(R.string.timer_remaining, VideoScanUtils.formatDuration(seconds * 1000L))
    }
  val displayTitle =
    remember(video.displayName, showExtension) {
      if (showExtension) video.displayName else FileTypeUtils.stripExtension(video.displayName)
    }
  val cardInteraction = remember { MutableInteractionSource() }

  Surface(
    onClick = onClick,
    interactionSource = cardInteraction,
    shape = CardShape,
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.62f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
    modifier =
      Modifier
        .width(CardWidth)
        .appPressFeedback(cardInteraction, pressedScale = 0.97f, reduceMotion = reduceMotion)
        .tvFocusHighlight(CardShape),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(ThumbnailShape)
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
          // Missing or still-loading thumbnail: a quiet tonal placeholder.
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.Center).size(40.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.RoundedFilled.Movie,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }

        // Bottom scrim keeps the resume pill, duration chip, and progress bar legible on any frame.
        Box(
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .height(48.dp)
              .background(
                Brush.verticalGradient(
                  0f to Color.Transparent,
                  1f to Color.Black.copy(alpha = 0.68f),
                ),
              ),
        )

        val badgeBottomPadding = if (hasProgress) 10.dp else 7.dp

        // Compact corner resume pill so artwork stays unobstructed.
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
          contentColor = MaterialTheme.colorScheme.onPrimary,
          shadowElevation = 2.dp,
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .padding(start = 7.dp, bottom = badgeBottomPadding)
              .size(26.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.RoundedFilled.PlayArrow,
              contentDescription = stringResource(R.string.ui_play),
              modifier = Modifier.size(16.dp),
            )
          }
        }

        if (hasDuration) {
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 7.dp, bottom = badgeBottomPadding)
                .cardOverlay(shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            Text(
              text = video.durationFormatted,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = Color.White,
            )
          }
        }

        if (hasProgress && progress != null) {
          Box(
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 7.dp, vertical = 4.dp)
                .height(3.5.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.28f)),
          ) {
            Box(
              modifier =
                Modifier
                  .fillMaxHeight()
                  .fillMaxWidth(progress.coerceIn(0f, 1f))
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary),
            )
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = displayTitle,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            remainingLabel
              ?: video.bucketDisplayName.ifBlank { stringResource(R.string.continue_watching_subtitle) },
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (remainingLabel != null) FontWeight.Medium else FontWeight.Normal,
          color =
            if (remainingLabel != null) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

