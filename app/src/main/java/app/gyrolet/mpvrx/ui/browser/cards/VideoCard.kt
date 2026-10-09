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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.ThumbnailQuality
import app.gyrolet.mpvrx.preferences.VideoSwipeAction
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Immutable
data class VideoCardUiConfig(
  val unlimitedNameLines: Boolean,
  val showThumbnails: Boolean,
  val showSizeChip: Boolean,
  val showResolutionChip: Boolean,
  val showFramerateInResolution: Boolean,
  val showCodecSupportIndicator: Boolean,
  val showProgressBar: Boolean,
  val showDateChip: Boolean,
  val showUnplayedOldVideoLabel: Boolean,
  val unplayedOldVideoDays: Int,
  val showExtensionField: Boolean = true,
  val showDurationField: Boolean = true,
  val centerGridTitles: Boolean = false,
  val thumbnailQuality: ThumbnailQuality = ThumbnailQuality.High,
  val swipeLeft: VideoSwipeAction = VideoSwipeAction.AddToPlaylist,
  val swipeRight: VideoSwipeAction = VideoSwipeAction.ToggleWatched,
)

/** Hoist this once per screen and pass the result to every card rather than collecting per item. */
@Composable
fun rememberVideoCardUiConfig(): VideoCardUiConfig {
  val appearancePreferences = koinInject<AppearancePreferences>()
  val browserPreferences = koinInject<BrowserPreferences>()

  val unlimitedNameLines by appearancePreferences.unlimitedNameLines.collectAsState()
  val showVideoThumbnails by browserPreferences.showVideoThumbnails.collectAsState()
  val showSizeChipPref by browserPreferences.showSizeChip.collectAsState()
  val showResolutionChipPref by browserPreferences.showResolutionChip.collectAsState()
  val showFramerateInResolutionConfig by browserPreferences.showFramerateInResolution.collectAsState()
  val showCodecSupportIndicator by browserPreferences.showCodecSupportIndicator.collectAsState()
  val showProgressBarConfig by browserPreferences.showProgressBar.collectAsState()
  val showDateChipConfig by browserPreferences.showDateChip.collectAsState()
  val showUnplayedOldVideoLabelConfig by appearancePreferences.showUnplayedOldVideoLabel.collectAsState()
  val unplayedOldVideoDaysConfig by appearancePreferences.unplayedOldVideoDays.collectAsState()
  val showExtensionField by browserPreferences.showExtensionField.collectAsState()
  val showDurationFieldConfig by browserPreferences.showDurationField.collectAsState()
  val centerGridTitles by browserPreferences.centerGridTitles.collectAsState()
  val thumbnailQuality by browserPreferences.thumbnailQuality.collectAsState()
  val swipeLeft by browserPreferences.videoSwipeLeft.collectAsState()
  val swipeRight by browserPreferences.videoSwipeRight.collectAsState()

  return remember(
    unlimitedNameLines,
    showVideoThumbnails,
    showSizeChipPref,
    showResolutionChipPref,
    showFramerateInResolutionConfig,
    showCodecSupportIndicator,
    showProgressBarConfig,
    showDateChipConfig,
    showUnplayedOldVideoLabelConfig,
    unplayedOldVideoDaysConfig,
    showExtensionField,
    showDurationFieldConfig,
    centerGridTitles,
    thumbnailQuality,
    swipeLeft,
    swipeRight,
  ) {
    VideoCardUiConfig(
      unlimitedNameLines = unlimitedNameLines,
      showThumbnails = showVideoThumbnails,
      showSizeChip = showSizeChipPref,
      showResolutionChip = showResolutionChipPref,
      showFramerateInResolution = showFramerateInResolutionConfig,
      showCodecSupportIndicator = showCodecSupportIndicator,
      showProgressBar = showProgressBarConfig,
      showDateChip = showDateChipConfig,
      showUnplayedOldVideoLabel = showUnplayedOldVideoLabelConfig,
      unplayedOldVideoDays = unplayedOldVideoDaysConfig,
      showExtensionField = showExtensionField,
      showDurationField = showDurationFieldConfig,
      centerGridTitles = centerGridTitles,
      thumbnailQuality = thumbnailQuality,
      swipeLeft = swipeLeft,
      swipeRight = swipeRight,
    )
  }
}

@Composable
private fun VideoTopStartBadges(
  isPinned: Boolean,
  showNewLabel: Boolean,
  modifier: Modifier = Modifier,
) {
  if (!isPinned && !showNewLabel) return
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isPinned) {
      Surface(
        shape = AppShapeScale.full,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 3.dp,
        modifier = Modifier.rotate(-18f),
      ) {
        Icon(
          imageVector = Icons.RoundedFilled.PushPin,
          contentDescription = stringResource(R.string.ui_pinned_video),
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp).size(12.dp),
        )
      }
    }
    if (showNewLabel) {
      Box(
        modifier =
          Modifier
            .cardOverlay(containerColor = Color(0xFFD32F2F))
            .padding(horizontal = 8.dp, vertical = 3.dp),
      ) {
        Text(
          text = stringResource(R.string.video_label_new),
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
          color = Color.White,
        )
      }
    }
  }
}

@Composable
fun VideoCard(
  video: Video,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  isRecentlyPlayed: Boolean = false,
  onLongClick: (() -> Unit)? = null,
  isSelected: Boolean = false,
  progressPercentage: Float? = null,
  isOldAndUnplayed: Boolean = false,
  isWatched: Boolean = false,
  isPinned: Boolean = false,
  onThumbClick: () -> Unit = {},
  isGridMode: Boolean = false,
  gridColumns: Int = 1,
  thumbnailWidthPx: Int? = null,
  thumbnailHeightPx: Int? = null,
  showSubtitleIndicator: Boolean = true,
  overrideShowSizeChip: Boolean? = null,
  overrideShowResolutionChip: Boolean? = null,
  useFolderNameStyle: Boolean = false,
  allowThumbnailGeneration: Boolean = true,
  allowThumbnailLoading: Boolean = true,
  uiConfig: VideoCardUiConfig? = null,
  onSwipeAction: ((Video, Boolean, VideoSwipeAction) -> Unit)? = null,
  /** Source badge shown ahead of the metadata chips, e.g. "Local". Null renders nothing. */
  sourceLabel: String? = null,
  sourceColor: Color? = null,
  /** Path line under the title, mirroring the network card's source line. */
  sourceSubtitle: String? = null,
  titleAction: (@Composable () -> Unit)? = null,
) {
  // Screens hoist this once and pass it down; collecting per card would register a dozen
  // preference observers for every visible item in a grid.
  val resolvedUiConfig = uiConfig ?: rememberVideoCardUiConfig()
  val maxLines = if (resolvedUiConfig.unlimitedNameLines) Int.MAX_VALUE else 2

  val showThumbnails = resolvedUiConfig.showThumbnails
  val thumbnailQuality = resolvedUiConfig.thumbnailQuality
  val showFramerateInResolution = resolvedUiConfig.showFramerateInResolution
  val showCodecSupportIndicator = resolvedUiConfig.showCodecSupportIndicator
  val showProgressBar = resolvedUiConfig.showProgressBar
  val showDateChip = resolvedUiConfig.showDateChip
  val showUnplayedOldVideoLabel = resolvedUiConfig.showUnplayedOldVideoLabel
  val showDurationField = resolvedUiConfig.showDurationField
  val showSizeChip = overrideShowSizeChip ?: resolvedUiConfig.showSizeChip
  val showResolutionChip = overrideShowResolutionChip ?: resolvedUiConfig.showResolutionChip
  val hasDuration = showDurationField && video.durationFormatted.isNotBlank() && video.durationFormatted != "--"
  val hasSizeChip =
    showSizeChip &&
      video.sizeFormatted.isNotBlank() &&
      video.sizeFormatted != "0 B" &&
      video.sizeFormatted != "--"
  val hasResolution = !video.isAudio && video.resolution.isNotBlank() && video.resolution != "--"
  val fpsOnly = video.resolution.substringAfter("@", "")
  val hasFps = hasResolution && fpsOnly.isNotEmpty()
  val hasCodecChip = showCodecSupportIndicator && !video.isAudio && video.videoCodec.isNotBlank()
  val hasSubtitleChips = showSubtitleIndicator && !video.isAudio && video.hasEmbeddedSubtitles && video.subtitleCodec.isNotBlank()
  val hasDateMetadataChip = showDateChip && video.dateModified > 0
  val hasResolutionMetadataChip = !isGridMode && showResolutionChip && hasResolution
  val hasFramerateMetadataChip = showFramerateInResolution && hasFps && (isGridMode || !showResolutionChip)
  val hasMetadataChips =
    sourceLabel != null ||
      hasCodecChip ||
      hasSubtitleChips ||
      hasSizeChip ||
      hasResolutionMetadataChip ||
      hasFramerateMetadataChip ||
      hasDateMetadataChip
  val displayName =
    if (resolvedUiConfig.showExtensionField) {
      video.displayName
    } else if (video.isAudio && video.title.isNotBlank()) {
      video.title
    } else {
      app.gyrolet.mpvrx.utils.storage.FileTypeUtils.stripExtension(video.displayName)
    }

  val selectionInset = 2.dp
  val selectionContainerColor = animatedSelectionColor(isSelected)
  val showSelectionBadge = isSelected || selectionContainerColor.alpha > 0.001f

  val cardShape = AppShapeScale.large

  VideoSwipeSurface(
    identity = video.path,
    leftAction = resolvedUiConfig.swipeLeft,
    rightAction = resolvedUiConfig.swipeRight,
    isWatched = isWatched,
    enabled = !isGridMode && !isSelected && video.path.startsWith('/'),
    onAction = onSwipeAction?.let { action -> { swipe -> action(video, isWatched, swipe) } },
    modifier =
      modifier
        .then(
          if (isGridMode) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(),
        ).tvFocusHighlight(cardShape, focusedScale = 1.03f)
        .clip(cardShape)
        .semantics { selected = isSelected }
        .tvContextMenu(onLongClick)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
  ) {
    Box(modifier = Modifier.fillMaxWidth()) {
      Box(
        modifier =
          Modifier
            .matchParentSize()
            .padding(selectionInset)
            .clip(cardShape)
            .background(selectionContainerColor),
      )

      if (isGridMode) {
        val centerGridTitles = resolvedUiConfig.centerGridTitles
        val horizontalAlignment = if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start
        // GRID LAYOUT - Vertical arrangement
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalAlignment = horizontalAlignment,
          verticalArrangement = if (sourceLabel != null) Arrangement.spacedBy(6.dp) else Arrangement.Top,
        ) {
          val thumbnailRepository = koinInject<ThumbnailRepository>()
          val aspect = if (video.isAudio) 1f else 16f / 10f
          // Screens that know their grid-cell dimensions pass them here. This is
          // essential for a one-column grid, whose full-width artwork used to be
          // rendered from a fixed 160 dp thumbnail.
          val defaultThumbWidthPx = with(LocalDensity.current) { 160.dp.roundToPx() }
          val resolvedThumbWidthPx = thumbnailWidthPx?.takeIf { it > 0 } ?: defaultThumbWidthPx
          val resolvedThumbHeightPx =
            thumbnailHeightPx?.takeIf { it > 0 }
              ?: (resolvedThumbWidthPx / aspect).roundToInt()

          val thumbnailRequestKey =
            remember(
              video.id,
              video.path,
              video.dateModified,
              video.size,
              video.duration,
              resolvedThumbWidthPx,
              resolvedThumbHeightPx,
              thumbnailQuality,
            ) {
              Any()
            }

          // Seeded during composition: reading the cache asynchronously shows the placeholder icon
          // for a frame first, which flickers every visible card when the list recomposes.
          var thumbnail by remember(thumbnailRequestKey) {
            mutableStateOf<Bitmap?>(
              thumbnailRepository.peekThumbnailFromMemory(video, resolvedThumbWidthPx, resolvedThumbHeightPx),
            )
          }

          // Read cached thumbnail immediately from disk if not in memory, or generate if permitted
          LaunchedEffect(thumbnailRequestKey, allowThumbnailGeneration, allowThumbnailLoading, showThumbnails) {
            if (thumbnail == null && showThumbnails) {
              thumbnail =
                withContext(Dispatchers.IO) {
                  if (allowThumbnailGeneration && allowThumbnailLoading) {
                    thumbnailRepository.getThumbnail(video, resolvedThumbWidthPx, resolvedThumbHeightPx)
                  } else {
                    thumbnailRepository.getCachedThumbnail(video, resolvedThumbWidthPx, resolvedThumbHeightPx)
                  }
                }
            }
          }

          val thumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }

          // Thumbnail
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(AppShapeScale.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                  onClick = onThumbClick,
                  onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
          ) {
            if (showThumbnails) {
              if (!video.artworkUrl.isNullOrBlank()) {
                RemoteImage(
                  url = video.artworkUrl,
                  contentDescription = stringResource(R.string.ui_thumbnail),
                  modifier = Modifier.matchParentSize(),
                  contentScale = ContentScale.Crop,
                )
              } else thumbnailBitmap?.let {
                Image(
                  bitmap = it,
                  contentDescription =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
                  modifier = Modifier.matchParentSize(),
                  contentScale = ContentScale.Crop,
                )
              } ?: run {
                Icon(
                  if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
                  contentDescription =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            } else {
              Icon(
                if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }

            VideoTopStartBadges(
              // The pin always stays visible, including on a selected card: it says the video is
              // pinned, not anything about the current selection.
              isPinned = isPinned,
              showNewLabel = showUnplayedOldVideoLabel && isOldAndUnplayed && !showSelectionBadge,
              modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))

            if (
              !showSelectionBadge &&
              (isWatched || (showCodecSupportIndicator && !video.isAudio && video.videoCodec.isNotBlank()))
            ) {
              Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (isWatched) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Check,
                    contentDescription = stringResource(R.string.video_label_watched),
                    modifier = Modifier.size(24.dp),
                    tint = Color.White,
                  )
                }
                if (showCodecSupportIndicator && !video.isAudio && video.videoCodec.isNotBlank()) {
                  CodecSupportIndicator(video = video, compact = true)
                }
              }
            }

            // Duration overlay
            if (hasDuration) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                      .cardOverlay()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = video.durationFormatted,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White,
                )
              }
            }

            if (isGridMode && showResolutionChip && hasResolution) {
              val displayResolution =
                if (showFramerateInResolution) video.resolution else video.resolution.substringBefore("@")
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .cardOverlay()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = displayResolution,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White,
                )
              }
            }

            // Progress bar
            if (progressPercentage != null && showProgressBar) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
              ) {
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
                Box(
                  modifier =
                    Modifier
                      .fillMaxHeight()
                      .fillMaxWidth(progressPercentage)
                      .background(MaterialTheme.colorScheme.primary),
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          // Title below thumbnail
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centerGridTitles) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
          ) {
          Text(
            text = displayName,
            modifier = if (titleAction != null) Modifier.weight(1f) else Modifier,
            style =
              MaterialTheme.typography.titleMedium.let { baseStyle ->
                if (isRecentlyPlayed) baseStyle.copy(fontStyle = FontStyle.Italic) else baseStyle
              },
            color =
              if (isRecentlyPlayed) {
                MaterialTheme.colorScheme.tertiary
              } else if (isWatched) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
              } else {
                MaterialTheme.colorScheme.onSurface
              },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centerGridTitles) TextAlign.Center else TextAlign.Start,
          )
            titleAction?.invoke()
          }
          if (!sourceSubtitle.isNullOrBlank()) {
            Text(
              text = sourceSubtitle,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (hasMetadataChips) {
            Spacer(modifier = Modifier.height(if (sourceLabel != null) 0.dp else 4.dp))
            FlowRow(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement =
                androidx.compose.foundation.layout.Arrangement
                  .spacedBy(4.dp, if (centerGridTitles) Alignment.CenterHorizontally else Alignment.Start),
              verticalArrangement =
                androidx.compose.foundation.layout.Arrangement
                  .spacedBy(4.dp),
            ) {
              if (sourceLabel != null) {
                SourceChip(label = sourceLabel, color = sourceColor ?: MaterialTheme.colorScheme.surfaceContainerHigh)
              }
              if (showSubtitleIndicator && !video.isAudio) {
                if (video.hasEmbeddedSubtitles && video.subtitleCodec.isNotBlank()) {
                  video.subtitleCodec.split(" ").forEach { codec ->
                    Text(
                      text = codec,
                      style = MaterialTheme.typography.labelSmall,
                      modifier =
                        Modifier
                          .background(
                            MaterialTheme.colorScheme.primary,
                            AppShapeScale.small,
                          ).padding(horizontal = 8.dp, vertical = 4.dp),
                      color = MaterialTheme.colorScheme.onPrimary,
                    )
                  }
                }
              }
              if (hasSizeChip) {
                Text(
                  video.sizeFormatted,
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

              if (!isGridMode && showResolutionChip && !video.isAudio) {
                if (hasResolution) {
                  val displayResolution =
                    if (showFramerateInResolution) {
                      video.resolution
                    } else {
                      video.resolution.substringBefore("@")
                    }

                  Text(
                    displayResolution,
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
              } else if (!video.isAudio && showFramerateInResolution && hasFps) {
                Text(
                  "$fpsOnly FPS",
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

              if (hasDateMetadataChip) {
                Text(
                  formatDate(video.dateModified),
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
      } else {
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          val thumbnailRepository = koinInject<ThumbnailRepository>()
          // Audio artwork is square; video thumbnails retain their 16:9 presentation.
          val aspect = if (video.isAudio) 1f else 16f / 9f
          // Respect a caller-supplied size (e.g. the configurable Music cover-art size) instead of
          // always hardcoding 128dp, otherwise controls like the Cover Art Size slider have no effect
          // on this list layout.
          val thumbWidthPx = thumbnailWidthPx?.takeIf { it > 0 } ?: with(LocalDensity.current) { (if (video.isAudio) 56.dp else 128.dp).roundToPx() }
          val thumbWidthDp = with(LocalDensity.current) { thumbWidthPx.toDp() }
          val thumbHeightPx = thumbnailHeightPx?.takeIf { it > 0 } ?: (thumbWidthPx / aspect).roundToInt()

          // Load thumbnail with optimized state management
          // Key includes video identity to prevent reloading same thumbnail
          val thumbnailRequestKey =
            remember(
              video.id,
              video.path,
              video.dateModified,
              video.size,
              video.duration,
              thumbWidthPx,
              thumbHeightPx,
              thumbnailQuality,
            ) {
              Any()
            }

          // Seeded during composition: reading the cache asynchronously shows the placeholder icon
          // for a frame first, which flickers every visible card when the list recomposes.
          var thumbnail by remember(thumbnailRequestKey) {
            mutableStateOf<Bitmap?>(
              thumbnailRepository.peekThumbnailFromMemory(video, thumbWidthPx, thumbHeightPx),
            )
          }

          // Read cached thumbnail immediately from disk if not in memory, or generate if permitted
          LaunchedEffect(thumbnailRequestKey, allowThumbnailGeneration, allowThumbnailLoading, showThumbnails) {
            if (thumbnail == null && showThumbnails) {
              thumbnail =
                withContext(Dispatchers.IO) {
                  if (allowThumbnailGeneration && allowThumbnailLoading) {
                    thumbnailRepository.getThumbnail(video, thumbWidthPx, thumbHeightPx)
                  } else {
                    thumbnailRepository.getCachedThumbnail(video, thumbWidthPx, thumbHeightPx)
                  }
                }
            }
          }

          val listThumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }

          Box(
            modifier =
              Modifier
                .width(thumbWidthDp)
                .aspectRatio(aspect)
                .clip(AppShapeScale.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                  onClick = onThumbClick,
                  onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
          ) {
            if (showThumbnails) {
              if (!video.artworkUrl.isNullOrBlank()) {
                RemoteImage(
                  url = video.artworkUrl,
                  contentDescription = stringResource(R.string.ui_thumbnail),
                  modifier = Modifier.matchParentSize(),
                  contentScale = ContentScale.Crop,
                )
              } else listThumbnailBitmap?.let {
                Image(
                  bitmap = it,
                  contentDescription =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_thumbnail),
                  modifier = Modifier.matchParentSize(),
                  contentScale = ContentScale.Crop,
                )
              } ?: run {
                Icon(
                  if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
                  contentDescription =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            } else {
              Icon(
                if (video.isAudio) Icons.RoundedFilled.Audiotrack else Icons.RoundedFilled.PlayArrow,
                contentDescription =
                  androidx.compose.ui.res
                    .stringResource(app.gyrolet.mpvrx.R.string.ui_play),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary,
              )
            }

            VideoTopStartBadges(
              // The pin always stays visible, including on a selected card: it says the video is
              // pinned, not anything about the current selection.
              isPinned = isPinned,
              showNewLabel = showUnplayedOldVideoLabel && isOldAndUnplayed && !showSelectionBadge,
              modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            )

            SelectionIndicator(isSelected, Modifier.align(Alignment.TopEnd).padding(6.dp))

            if (isWatched && !showSelectionBadge) {
              Icon(
                imageVector = Icons.RoundedFilled.Check,
                contentDescription = stringResource(R.string.video_label_watched),
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                tint = Color.White,
              )
            }

            // Duration timestamp overlay at bottom-right of the thumbnail
            if (hasDuration) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                      .cardOverlay()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
              ) {
                Text(
                  text = video.durationFormatted,
                  style = MaterialTheme.typography.labelSmall,
                  color = Color.White,
                )
              }
            }

            // Progress bar at bottom of thumbnail
            if (progressPercentage != null && showProgressBar) {
              Box(
                modifier =
                  Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp),
              ) {
                // Background (unwatched portion)
                Box(
                  modifier =
                    Modifier
                      .matchParentSize()
                      .background(Color.Black.copy(alpha = 0.6f)),
                )
                // Progress (watched portion)
                Box(
                  modifier =
                    Modifier
                      .fillMaxHeight()
                      .fillMaxWidth(progressPercentage)
                      .background(MaterialTheme.colorScheme.primary),
                )
              }
            }
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column(
            modifier = Modifier.weight(1f),
            // Mirror M3UVideoCard's line rhythm when a source line is present, so the two card
            // types produce the same text-block height in a mixed playlist and stay aligned.
            verticalArrangement = if (sourceLabel != null) Arrangement.spacedBy(6.dp) else Arrangement.Top,
          ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
              displayName,
              modifier = Modifier.weight(1f),
              style =
                if (useFolderNameStyle) {
                  MaterialTheme.typography.titleMedium
                } else {
                  MaterialTheme.typography.titleSmall
                }.let { baseStyle ->
                  if (isRecentlyPlayed) baseStyle.copy(fontStyle = FontStyle.Italic) else baseStyle
                },
              color =
                if (isRecentlyPlayed) {
                  MaterialTheme.colorScheme.tertiary
                } else if (isWatched) {
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
              maxLines = maxLines,
              overflow = TextOverflow.Ellipsis,
            )
              titleAction?.invoke()
            }
            if (!sourceSubtitle.isNullOrBlank()) {
              Text(
                text = sourceSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            if (hasMetadataChips) {
              Spacer(modifier = Modifier.height(if (sourceLabel != null) 0.dp else 4.dp))
              FlowRow(
                horizontalArrangement =
                  androidx.compose.foundation.layout.Arrangement
                    .spacedBy(4.dp),
                verticalArrangement =
                  androidx.compose.foundation.layout.Arrangement
                    .spacedBy(4.dp),
              ) {
                if (sourceLabel != null) {
                  SourceChip(label = sourceLabel, color = sourceColor ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                }
                if (showCodecSupportIndicator && !video.isAudio && video.videoCodec.isNotBlank()) {
                  CodecSupportIndicator(video = video)
                }
                if (showSubtitleIndicator && !video.isAudio) {
                  if (video.hasEmbeddedSubtitles && video.subtitleCodec.isNotBlank()) {
                    video.subtitleCodec.split(" ").forEach { codec ->
                      Text(
                        text = codec,
                        style = MaterialTheme.typography.labelSmall,
                        modifier =
                          Modifier
                            .background(
                              MaterialTheme.colorScheme.primary,
                              AppShapeScale.small,
                            ).padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                      )
                    }
                  }
                }
                if (hasSizeChip) {
                  Text(
                    video.sizeFormatted,
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
                // Resolution and Framerate logic (List view)
                if (showResolutionChip && !video.isAudio) {
                  if (hasResolution) {
                    val displayResolution =
                      if (showFramerateInResolution) {
                        video.resolution
                      } else {
                        video.resolution.substringBefore("@")
                      }

                    Text(
                      displayResolution,
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
                } else if (showFramerateInResolution && hasFps) {
                  // Resolution is hidden, but framerate is enabled -> show only framerate
                  Text(
                    "$fpsOnly FPS",
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

                if (hasDateMetadataChip) {
                  Text(
                    formatDate(video.dateModified),
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
      }
    }
  }
}

@Composable
private fun CodecSupportIndicator(
  video: Video,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val support =
    remember(video.videoCodec, video.videoCodecMimeType, video.width, video.height, video.fps) {
      app.gyrolet.mpvrx.utils.media.VideoCodecSupportInspector.inspect(
        codecLabel = video.videoCodec,
        mimeType = video.videoCodecMimeType,
        width = video.width,
        height = video.height,
        frameRate = video.fps,
      )
    }
  val statusLabel =
    when (support.decodeSupport) {
      app.gyrolet.mpvrx.utils.media.VideoDecodeSupport.HARDWARE -> "HW"
      app.gyrolet.mpvrx.utils.media.VideoDecodeSupport.SOFTWARE -> "SW"
      app.gyrolet.mpvrx.utils.media.VideoDecodeSupport.UNSUPPORTED -> if (compact) "NO" else "Unsupported"
      app.gyrolet.mpvrx.utils.media.VideoDecodeSupport.UNKNOWN -> "Unknown"
    }
  Row(
    modifier =
      modifier
        .cardOverlay(
          shape = AppShapeScale.small,
          containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 3.dp else 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "${support.codecLabel} · $statusLabel",
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

// Hoisted because a card formats a date on every recomposition and SimpleDateFormat construction
// parses the pattern and clones a Calendar each time.
private val CARD_DATE_FORMATTER: java.time.format.DateTimeFormatter =
  java.time.format.DateTimeFormatter
    .ofPattern("MMM dd, yyyy")
    .withZone(java.time.ZoneId.systemDefault())

private fun formatDate(timestampSeconds: Long): String =
  CARD_DATE_FORMATTER.format(java.time.Instant.ofEpochSecond(timestampSeconds))
