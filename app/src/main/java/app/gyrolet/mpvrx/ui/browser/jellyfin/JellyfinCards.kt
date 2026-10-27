/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.browser.cards.MediaSelectionNeighbors
import app.gyrolet.mpvrx.ui.browser.cards.animatedSelectionColor
import app.gyrolet.mpvrx.ui.browser.cards.mediaCardClickable
import app.gyrolet.mpvrx.ui.browser.cards.rememberMediaSelectionShape
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusHighlight
import app.gyrolet.mpvrx.ui.player.controls.components.tvContextMenu
import app.gyrolet.mpvrx.ui.player.controls.components.tvFocusGroup
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import app.gyrolet.mpvrx.ui.tv.focusableWhen
import app.gyrolet.mpvrx.ui.theme.LocalDarkAppColorScheme
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.ui.utils.LocalNavigationPageActive
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import android.content.ActivityNotFoundException
import app.gyrolet.mpvrx.ui.components.AppToast as Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Watch-progress overlay drawn on top of artwork; uses the always-dark accent so the
 * fill stays visible on the black track in light mode. */
@Composable
private fun WatchProgressOverlayBar(
  progress: Float,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.height(4.dp)) {
    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)))
    Box(
      modifier =
        Modifier
          .fillMaxHeight()
          .fillMaxWidth(progress.coerceIn(0f, 1f))
          .background((LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme).primary),
    )
  }
}

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
      withStyle(SpanStyle(fontSize = 13.5.sp)) {
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
// Hero Featured Carousel (Material 3 Expressive multi-browse carousel)
// ============================================================================

private val HeroPeekWidth = 56.dp
private val HeroMaxItemWidth = 960.dp
private const val HERO_AUTO_ADVANCE_MS = 5_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinHeroBanner(
  items: List<JellyfinItem>,
  server: JellyfinServer,
  onPlay: (JellyfinItem) -> Unit,
  onDetails: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
  autoAdvance: Boolean = true,
) {
  if (items.isEmpty()) return

  val carouselState = key(server.id) { rememberCarouselState { items.size } }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  val isTelevision = DeviceFormFactor.isTelevision(context)
  val reduceMotion = AppMotion.shouldReduceMotion()
  val pageActive = LocalNavigationPageActive.current
  val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  var hasFocus by remember { mutableStateOf(false) }
  val canAutoAdvance = autoAdvance && pageActive && lifecycle.isAtLeast(Lifecycle.State.STARTED) &&
    !isTelevision && !reduceMotion && !hasFocus
  val gutter = jellyfinGutter()
  val currentItem = carouselState.currentItem.coerceIn(0, items.lastIndex)

  LaunchedEffect(carouselState, items.size) {
    if (carouselState.currentItem > items.lastIndex) carouselState.scrollToItem(items.lastIndex)
  }

  LaunchedEffect(carouselState, currentItem, carouselState.isScrollInProgress, canAutoAdvance, items.size) {
    if (items.size > 1 && canAutoAdvance && !carouselState.isScrollInProgress) {
      delay(HERO_AUTO_ADVANCE_MS)
      carouselState.animateScrollToItem((currentItem + 1) % items.size)
    }
  }

  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val heroHeight = (when {
    isTelevision -> 360.dp
    isLandscape -> 300.dp
    else -> 440.dp
  }) * configuration.fontScale.coerceAtLeast(1f)

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .onFocusChanged { hasFocus = it.hasFocus }
        .focusGroup(),
    verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.HeaderGap),
  ) {
    // The gutter sits outside the carousel, so slides morph against the same edge every row uses
    // and nothing scrolls into the screen margin.
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
      HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = (maxWidth - HeroPeekWidth).coerceIn(1.dp, HeroMaxItemWidth),
        modifier = Modifier.fillMaxWidth().height(heroHeight),
        itemSpacing = JellyfinSpacing.CarouselItemGap,
        flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(carouselState),
      ) { index ->
        val item = items.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
        JellyfinHeroSlide(
          item = item,
          server = server,
          isCurrent = index == currentItem,
          onPlay = { onPlay(item) },
          onDetails = { onDetails(item) },
          onReveal = { scope.launch { carouselState.animateScrollToItem(index) } },
        )
      }
    }

    if (items.size > 1) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalIconButton(
          modifier = Modifier.tvFocusHighlight(CircleShape),
          onClick = { scope.launch { carouselState.animateScrollToItem((currentItem - 1 + items.size) % items.size) } },
        ) {
          Icon(
            if (isRtl) Icons.RoundedFilled.ChevronRight else Icons.RoundedFilled.ChevronLeft,
            stringResource(R.string.pref_gesture_media_previous),
          )
        }
        SlidingWormDotsIndicator(
          selectedItem = currentItem,
          pageCount = items.size,
        )
        FilledTonalIconButton(
          modifier = Modifier.tvFocusHighlight(CircleShape),
          onClick = { scope.launch { carouselState.animateScrollToItem((currentItem + 1) % items.size) } },
        ) {
          Icon(
            if (isRtl) Icons.RoundedFilled.ChevronLeft else Icons.RoundedFilled.ChevronRight,
            stringResource(R.string.pref_gesture_media_next),
          )
        }
      }
    }
  }
}

/**
 * One hero slide. Its content is laid out at full size and revealed by the carousel mask; text and
 * actions fade out as the slide narrows into a peek, and tapping a peek brings it forward.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CarouselItemScope.JellyfinHeroSlide(
  item: JellyfinItem,
  server: JellyfinServer,
  isCurrent: Boolean,
  onPlay: () -> Unit,
  onDetails: () -> Unit,
  onReveal: () -> Unit,
) {
  val context = LocalContext.current
  val isTelevision = DeviceFormFactor.isTelevision(context)
  val artworkColors = LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme
  val drawInfo = carouselItemDrawInfo
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

  val logoUrl =
    remember(server.serverUrl, item.id, item.logoImageTag, item.parentLogoImageTag, item.parentLogoItemId, server.accessToken) {
      if (!item.logoImageTag.isNullOrBlank()) {
        JellyfinClient.getLogoUrl(
          serverUrl = server.serverUrl,
          itemId = item.id,
          imageTag = item.logoImageTag,
          maxWidth = 800,
          token = server.accessToken,
        )
      } else if (!item.parentLogoImageTag.isNullOrBlank() && !item.parentLogoItemId.isNullOrBlank()) {
        JellyfinClient.getLogoUrl(
          serverUrl = server.serverUrl,
          itemId = item.parentLogoItemId,
          imageTag = item.parentLogoImageTag,
          maxWidth = 800,
          token = server.accessToken,
        )
      } else null
    }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .maskClip(MaterialTheme.shapes.extraLarge)
        .tvFocusMemory(listOf("hero", server.id, item.id))
        .tvFocusHighlight(MaterialTheme.shapes.extraLarge, clipToShape = false)
        .onFocusChanged { if (isTelevision && it.hasFocus && !isCurrent) onReveal() }
        .tvFocusGroup()
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(
          onClickLabel = stringResource(R.string.jellyfin_details),
          onClick = { if (isCurrent) onDetails() else onReveal() },
        ),
  ) {
    RemoteImage(
      url = backdropUrl,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.fillMaxSize(),
    )

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              0.0f to Color.Black.copy(alpha = 0.28f),
              0.25f to Color.Transparent,
              0.6f to Color.Black.copy(alpha = 0.65f),
              1.0f to Color.Black.copy(alpha = 0.96f),
            ),
          ),
    )

    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            val range = drawInfo.maxSize - drawInfo.minSize
            val reveal = if (range > 0f) ((drawInfo.size - drawInfo.minSize) / range).coerceIn(0f, 1f) else 1f
            alpha = ((reveal - 0.6f) / 0.4f).coerceIn(0f, 1f)
          },
    ) {
      Surface(
        modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
      ) {
        Text(
          stringResource(R.string.jellyfin_featured),
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
      }

      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Bottom,
      ) {
        // Title / Logo (matches AFinity)
        if (!logoUrl.isNullOrBlank()) {
          Box(
            modifier =
              Modifier
                .fillMaxWidth(0.85f)
                .heightIn(min = 44.dp, max = 96.dp)
                .padding(bottom = 6.dp),
            contentAlignment = Alignment.BottomStart,
          ) {
            RemoteImage(
              url = logoUrl,
              contentDescription = item.name,
              contentScale = ContentScale.Fit,
              alignment = Alignment.BottomStart,
              modifier =
                Modifier
                  .fillMaxHeight()
                  .wrapContentWidth(Alignment.Start),
            )
          }
        } else {
          Text(
            text = item.name,
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
          )
        }

        // Genres / Tagline
        val metaText =
          when {
            item.genres.isNotEmpty() -> item.genresString
            !item.taglines.isEmpty() -> item.taglines.first()
            item.isSeries -> stringResource(R.string.jellyfin_series)
            else -> stringResource(R.string.jellyfin_movie)
          }

        if (metaText.isNotBlank()) {
          Text(
            text = metaText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        // Badges Row (Rating, Year, Resolution)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          modifier = Modifier.padding(bottom = 12.dp),
        ) {
          // Type Pill (Featured)
          Surface(
            shape = CircleShape,
            color = artworkColors.primary,
          ) {
            Text(
              text = stringResource(if (item.isSeries) R.string.jellyfin_series else R.string.jellyfin_movie),
              style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
              color = artworkColors.onPrimary,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
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

          // Year Pill
          item.productionYear?.let { year ->
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = Color.Black.copy(alpha = 0.6f),
            ) {
              Text(
                text = year.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
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
                // Always-dark accent: colorScheme.primary is too dark for the black scrim in light mode.
                color = (LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme).primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Button(
            onClick = onPlay,
            enabled = isCurrent,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp).tvFocusHighlight(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = artworkColors.primary, contentColor = artworkColors.onPrimary),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
                  item.progressPercent > 0.05f -> stringResource(R.string.player_resume_action)
                  item.isSeries -> stringResource(R.string.jellyfin_browse_series)
                  else -> stringResource(R.string.ui_play)
                },
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          FilledTonalIconButton(
            onClick = onDetails,
            enabled = isCurrent,
            modifier = Modifier.size(52.dp).tvFocusHighlight(CircleShape),
            shape = CircleShape,
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White,
            ),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Info,
              contentDescription = stringResource(R.string.jellyfin_details),
              modifier = Modifier.size(24.dp),
            )
          }

          FilledTonalIconButton(
            onClick = {
              val rawUrl = item.remoteTrailerUrl?.takeIf { it.isNotBlank() }
              val trailerUrl = if (!rawUrl.isNullOrBlank()) {
                if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl
                else "https://www.youtube.com/watch?v=$rawUrl"
              } else {
                "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode("${item.name} trailer", "UTF-8")}"
              }
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(trailerUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              try {
                context.startActivity(intent)
              } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.jellyfin_open_trailer_error, Toast.LENGTH_LONG).show()
              }
            },
            enabled = isCurrent,
            shape = CircleShape,
            modifier = Modifier.size(52.dp).tvFocusHighlight(CircleShape),
            colors = androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
              containerColor = Color.White.copy(alpha = 0.16f), contentColor = Color.White,
            ),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Movie,
              contentDescription = stringResource(R.string.jellyfin_trailer),
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SlidingWormDotsIndicator(
  selectedItem: Int,
  pageCount: Int,
  modifier: Modifier = Modifier,
  visibleDotCount: Int = 5,
) {
  if (pageCount <= 1) return

  val fraction by animateFloatAsState(
    targetValue = selectedItem.coerceIn(0, pageCount - 1).toFloat(),
    animationSpec = tween(300),
    label = "jellyfin_carousel_indicator",
  )

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        .padding(horizontal = jellyfinGutter())
        .heightIn(min = 40.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // The title starts on the same edge as the cards below it.
    Column(Modifier.weight(1f).semantics { heading() }) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLargeEmphasized,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (onSeeAll != null) {
      FilledTonalButton(
        onClick = onSeeAll,
        modifier = Modifier.heightIn(min = 40.dp).tvFocusHighlight(CircleShape),
        shape = CircleShape,
        contentPadding = PaddingValues(start = 16.dp, end = 10.dp),
      ) {
        Text(
          text = stringResource(R.string.profile_view_all),
          style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
          imageVector = if (LocalLayoutDirection.current == LayoutDirection.Rtl) Icons.RoundedFilled.ChevronLeft else Icons.RoundedFilled.ChevronRight,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
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
    verticalArrangement = Arrangement.spacedBy(JellyfinSpacing.HeaderGap),
  ) {
    JellyfinSectionHeader(
      title = title,
      subtitle = subtitle,
      onSeeAll = onSeeAll,
    )

    // Rows scroll edge to edge; the content padding lines the first card up with the title.
    val gutter = jellyfinGutter()
    LazyRow(
      modifier = Modifier.tvFocusGroup(),
      horizontalArrangement = Arrangement.spacedBy(JellyfinSpacing.RowItemGap),
      contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
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
            focusKey = listOf(title, item.id),
            onClick = { onItemClick(item) },
            onLongClick = onItemLongClick?.let { { it(item) } },
            cardWidth = 156.dp,
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

  // The artwork sits inside the tonal card, so its corners follow the card's, inset by its padding.
  val artworkShape = RoundedCornerShape(JellyfinSpacing.inner(28.dp))
  val posterBorderModifier =
    if (isSelected) {
      Modifier.clip(artworkShape).border(2.dp, MaterialTheme.colorScheme.primary, artworkShape)
    } else {
      Modifier.clip(artworkShape)
    }

  Column(
    modifier = modifier.width(280.dp).clip(MaterialTheme.shapes.extraLarge)
      .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(JellyfinSpacing.CardPadding),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(16f / 9f)
          .tvFocusMemory(listOf("JellyfinResumeCard", server.id, item.id))
          .tvFocusHighlight(artworkShape, focusedScale = 1.03f)
          .then(posterBorderModifier)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest)
          .tvContextMenu(onLongClick)
          .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
          ),
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
              contentDescription = stringResource(R.string.ui_play),
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
          shape = CircleShape,
          color = Color.Black.copy(alpha = 0.75f),
          modifier =
            Modifier
              .padding(8.dp)
              .align(Alignment.BottomEnd),
        ) {
          Text(
            text = remaining,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }

      // Progress bar at bottom
      if (item.progressPercent > 0.01f) {
        WatchProgressOverlayBar(
          progress = item.progressPercent,
          modifier =
            Modifier
              .fillMaxWidth()
              .align(Alignment.BottomCenter),
        )
      }
    }

    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
      horizontalAlignment = Alignment.Start,
    ) {
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
        textAlign = TextAlign.Start,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )
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
  isDownloaded: Boolean = false,
  selectionNeighbors: MediaSelectionNeighbors = MediaSelectionNeighbors(),
  focusKey: Any = item.id,
) {
  val posterImageTag = if (item.type == "Episode" && !item.seriesPrimaryImageTag.isNullOrBlank()) {
    item.seriesPrimaryImageTag
  } else {
    item.primaryImageTag
  }
  val posterItemId = if (item.type == "Episode" && !item.seriesId.isNullOrBlank() && !item.seriesPrimaryImageTag.isNullOrBlank()) {
    item.seriesId
  } else {
    item.id
  }
  val imageUrl =
    remember(server.serverUrl, posterItemId, posterImageTag, server.accessToken) {
      JellyfinClient.getImageUrl(
        serverUrl = server.serverUrl,
        itemId = posterItemId,
        imageTag = posterImageTag,
        maxWidth = 400,
        token = server.accessToken,
      )
    }

  val cardModifier =
    if (cardWidth != null) {
      modifier.width(cardWidth)
    } else {
      modifier.fillMaxWidth()
    }

  val cardShape = rememberMediaSelectionShape(isSelected, true, selectionNeighbors)

  Column(
    modifier = cardModifier
      .tvFocusMemory(listOf("JellyfinPosterCard", server.id, focusKey))
      .tvFocusHighlight(cardShape, focusedScale = 1.03f)
      .clip(cardShape)
      .background(MaterialTheme.colorScheme.surfaceContainerLow)
      .background(animatedSelectionColor(isSelected))
      .semantics { selected = isSelected }
      .tvContextMenu(onLongClick)
      .mediaCardClickable(onClick = onClick, onLongClick = onLongClick)
      .padding(JellyfinSpacing.CardPadding),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(2f / 3f)
          // Concentric with the card's resting 16 dp corners.
          .clip(RoundedCornerShape(JellyfinSpacing.inner(16.dp)))
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
      if (!posterImageTag.isNullOrBlank()) {
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
              item.isSeries || (item.type == "Episode" && !item.seriesName.isNullOrBlank()) -> Icons.RoundedFilled.Tv
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
            // Always-dark accent: colorScheme.primary is too dark for the black scrim in light mode.
            color = (LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme).primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
          )
        }
      }

      if (isDownloaded) {
        DownloadedBadge(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
      }

      // Progress bar if partially watched
      if (item.progressPercent > 0.02f && !item.isPlayed) {
        WatchProgressOverlayBar(
          progress = item.progressPercent,
          modifier =
            Modifier
              .fillMaxWidth()
              .align(Alignment.BottomCenter),
        )
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
            contentDescription = null,
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
            contentDescription = stringResource(R.string.jellyfin_watched),
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(3.dp),
          )
        }
      }
    }

    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 2.dp, end = 2.dp, top = 8.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      val title = if (item.type == "Episode" && !item.seriesName.isNullOrBlank()) item.seriesName else item.name
      Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        minLines = 2,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Start,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
      )
      val subtitle =
        run {
          val before = buildList {
            item.productionYear?.let { add(it.toString()) }
            if (item.isSeries && item.childCount != null && item.childCount > 0) {
              add(if (item.childCount == 1) "1 Season" else "${item.childCount} Seasons")
            } else if (item.type == "Episode") {
              if (item.parentIndexNumber != null && item.indexNumber != null) {
                add("S${item.parentIndexNumber}:E${item.indexNumber}")
              } else {
                add("Episode")
              }
            } else if (item.productionYear == null) {
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
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

// ============================================================================
// Music Card (1:1 Aspect Ratio Square Artwork)
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JellyfinMusicCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp = 140.dp,
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

  val isArtist = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist"
  val artworkShape = if (isArtist) CircleShape else MaterialTheme.shapes.large
  val posterBorderModifier =
    if (isSelected) {
      Modifier.clip(artworkShape).border(2.dp, MaterialTheme.colorScheme.primary, artworkShape)
    } else {
      Modifier.clip(artworkShape)
    }

  Column(
    modifier = modifier.width(cardWidth),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .tvFocusMemory(listOf("JellyfinMusicCard", server.id, item.id))
          .tvFocusHighlight(artworkShape, focusedScale = 1.03f)
          .then(posterBorderModifier)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest)
          .tvContextMenu(onLongClick)
          .combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
          ),
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
            imageVector = Icons.RoundedFilled.Audiotrack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
          )
        }
      }

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
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(3.dp),
          )
        }
      }
    }

    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(top = 8.dp),
      horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
      Text(
        text = item.name,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
      )
      if (!isArtist) {
        val subtitle = item.seriesName ?: item.productionYear?.toString() ?: stringResource(R.string.ui_music)
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

// ============================================================================
// Library Filter Chips Row
// ============================================================================

@Composable
fun JellyfinGenreChipRow(
  genres: List<String>,
  selectedGenre: String?,
  onSelectGenre: (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (genres.isEmpty()) return

  // Scrolls edge to edge; the inner padding starts the first chip on the screen gutter.
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = jellyfinGutter(), vertical = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = selectedGenre == null,
      onClick = { onSelectGenre(null) },
      label = { Text(stringResource(R.string.jellyfin_filter_all), fontWeight = if (selectedGenre == null) FontWeight.Bold else FontWeight.Normal) },
      modifier = Modifier.tvFocusHighlight(CircleShape),
      shape = CircleShape,
      colors =
        FilterChipDefaults.filterChipColors(
          selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
          selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )

    genres.forEach { genre ->
      val isSelected = selectedGenre == genre
      FilterChip(
        selected = isSelected,
        onClick = { onSelectGenre(if (isSelected) null else genre) },
        label = { Text(genre, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
        modifier = Modifier.tvFocusHighlight(CircleShape),
        shape = CircleShape,
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

/** Artwork URL and type icon shared by both library presentations. */
@Composable
private fun rememberLibraryArtwork(
  item: JellyfinItem,
  server: JellyfinServer,
): Pair<String?, app.gyrolet.mpvrx.ui.icons.AppIcon> {
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
  return imageUrl.takeIf { !item.primaryImageTag.isNullOrBlank() || !item.backdropImageTag.isNullOrBlank() } to icon
}

@Composable
private fun LibraryArtwork(
  item: JellyfinItem,
  url: String?,
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
    if (url != null) {
      RemoteImage(
        url = url,
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(40.dp).align(Alignment.Center),
      )
    }
  }
}

@Composable
fun JellyfinLibraryCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  cardWidth: androidx.compose.ui.unit.Dp = 248.dp,
) {
  val (url, icon) = rememberLibraryArtwork(item, server)
  val cardShape = MaterialTheme.shapes.extraLarge
  val artworkShape = RoundedCornerShape(JellyfinSpacing.inner(28.dp))

  Column(
    modifier =
      modifier
        .width(cardWidth)
        .tvFocusMemory(listOf("JellyfinLibraryCard", server.id, item.id))
        .tvFocusHighlight(cardShape, focusedScale = 1.02f)
        .clip(cardShape)
        .background(MaterialTheme.colorScheme.surfaceContainerLow)
        .clickable(onClick = onClick)
        .padding(JellyfinSpacing.CardPadding),
  ) {
    Box {
      LibraryArtwork(
        item = item,
        url = url,
        icon = icon,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(artworkShape),
      )
      LibraryTypeBadge(icon, Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
    Text(
      text = item.name,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
    )
  }
}

@Composable
private fun LibraryTypeBadge(
  icon: app.gyrolet.mpvrx.ui.icons.AppIcon,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = CircleShape,
    color = Color.Black.copy(alpha = 0.65f),
    modifier = modifier,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.padding(6.dp).size(16.dp),
    )
  }
}

/**
 * The server's libraries. Three or more become a Material 3 multi-browse carousel of artwork tiles
 * that grow and shrink as they scroll; fewer stay as fixed cards, since a carousel would only squeeze them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JellyfinLibrariesCarousel(
  libraries: List<JellyfinItem>,
  server: JellyfinServer,
  onOpen: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (libraries.isEmpty()) return
  val gutter = jellyfinGutter()
  // Fixed-width cards keep the full focus outline visible when navigating by D-pad.
  if (libraries.size < 3 || DeviceFormFactor.isTelevision(LocalContext.current)) {
    LazyRow(
      modifier = modifier.fillMaxWidth().tvFocusGroup(),
      horizontalArrangement = Arrangement.spacedBy(JellyfinSpacing.RowItemGap),
      contentPadding = PaddingValues(horizontal = gutter, vertical = 4.dp),
    ) {
      items(libraries, key = { it.id }) { library ->
        JellyfinLibraryCard(item = library, server = server, onClick = { onOpen(library) })
      }
    }
    return
  }

  val state = key(server.id) { rememberCarouselState { libraries.size } }
  HorizontalMultiBrowseCarousel(
    state = state,
    preferredItemWidth = 248.dp,
    modifier = modifier.fillMaxWidth().padding(horizontal = gutter).height(160.dp),
    itemSpacing = JellyfinSpacing.CarouselItemGap,
  ) { index ->
    val library = libraries.getOrNull(index) ?: return@HorizontalMultiBrowseCarousel
    val (url, icon) = rememberLibraryArtwork(library, server)
    val drawInfo = carouselItemDrawInfo
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .maskClip(MaterialTheme.shapes.extraLarge)
          .clickable { onOpen(library) },
    ) {
      LibraryArtwork(item = library, url = url, icon = icon, modifier = Modifier.fillMaxSize())
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))),
      )
      // The label only shows once the tile is wide enough to read it.
      Row(
        modifier =
          Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .padding(16.dp)
            .graphicsLayer {
              val range = drawInfo.maxSize - drawInfo.minSize
              val reveal = if (range > 0f) ((drawInfo.size - drawInfo.minSize) / range).coerceIn(0f, 1f) else 1f
              alpha = ((reveal - 0.5f) / 0.5f).coerceIn(0f, 1f)
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(18.dp),
        )
        Text(
          text = library.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
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
  downloadState: EpisodeDownloadState? = null,
  downloadProgress: Float? = null,
  onDownload: (() -> Unit)? = null,
  onCancelDownload: (() -> Unit)? = null,
) {
  val imageUrl =
    remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
      JellyfinClient.getImageUrl(
        serverUrl = server.serverUrl,
        itemId = item.id,
        imageTag = item.primaryImageTag,
        maxWidth = 480,
        token = server.accessToken,
      )
    }

  val backgroundColor =
    if (isSelected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerLow
    }

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusMemory(listOf("JellyfinEpisodeCard", server.id, item.id))
        .tvFocusHighlight(MaterialTheme.shapes.extraLarge, focusedScale = 1.02f)
        .clip(MaterialTheme.shapes.extraLarge)
        .tvContextMenu(onLongClick)
        .combinedClickable(
          onClick = onPlay,
          onLongClick = onLongClick,
        ),
    shape = MaterialTheme.shapes.extraLarge,
    color = backgroundColor,
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier
            .width(120.dp)
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
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
              modifier = Modifier.size(32.dp),
            )
          }
        }

        // Progress bar
        if (item.progressPercent > 0.02f && !item.isPlayed) {
          WatchProgressOverlayBar(
            progress = item.progressPercent,
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
          )
        }

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
              contentDescription = null,
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
                .size(18.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = stringResource(R.string.jellyfin_watched),
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val epPrefix = if (item.indexNumber != null) "EPISODE ${item.indexNumber} • " else ""
        Text(
          text = "$epPrefix${item.name}",
          style = MaterialTheme.typography.titleMedium,
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
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp),
          )
        }
      }

      when (downloadState) {
        EpisodeDownloadState.NOT_DOWNLOADED ->
          IconButton(onClick = { onDownload?.invoke() }, modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false)) {
            Icon(
              imageVector = Icons.RoundedFilled.Download,
              contentDescription = stringResource(R.string.downloads_download),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(22.dp),
            )
          }
        EpisodeDownloadState.ACTIVE ->
          IconButton(onClick = { onCancelDownload?.invoke() }, modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false)) {
            HotstarDownloadProgressCircle(
              progress = downloadProgress ?: 0f,
              modifier = Modifier.size(22.dp),
              strokeWidth = 2.5.dp,
            )
          }
        EpisodeDownloadState.DOWNLOADED ->
          IconButton(onClick = {}, modifier = Modifier.focusableWhen(!DeviceFormFactor.isTelevision(LocalContext.current)).tvFocusHighlight(CircleShape, clipToShape = false)) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = stringResource(R.string.downloads_downloaded),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(22.dp),
            )
          }
        null -> {}
      }

      IconButton(onClick = onPlay, modifier = Modifier.tvFocusHighlight(CircleShape, clipToShape = false)) {
        Icon(
          imageVector = Icons.RoundedFilled.PlayArrow,
          contentDescription = stringResource(R.string.ui_play),
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(26.dp),
        )
      }
    }
  }
}

/**
 * Hotstar-style clockwise circular download progress indicator.
 * Displays a subtle background track ring, a clockwise sweeping progress arc starting at 12 o'clock (-90°),
 * and a rounded stop square in the center.
 */
@Composable
fun HotstarDownloadProgressCircle(
  progress: Float,
  modifier: Modifier = Modifier,
  strokeWidth: Dp = 2.5.dp,
  trackColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
  progressColor: Color = MaterialTheme.colorScheme.primary,
  showStopSquare: Boolean = true,
) {
  val animatedProgress by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
    label = "download_progress",
  )

  val infiniteTransition = rememberInfiniteTransition(label = "indeterminate_spin")
  val indeterminateRotation by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(1200, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "spin_angle",
  )

  Box(
    modifier = modifier,
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val diameter = size.minDimension
      val strokePx = strokeWidth.toPx()
      val arcSize = Size(diameter - strokePx, diameter - strokePx)
      val topLeft = Offset(strokePx / 2f, strokePx / 2f)

      // 1. Subtle background track
      drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokePx, cap = StrokeCap.Round),
      )

      // 2. Clockwise progress fill starting at 12 o'clock (-90 degrees)
      if (progress > 0f) {
        val sweepAngle = (animatedProgress * 360f).coerceIn(4f, 360f)
        drawArc(
          color = progressColor,
          startAngle = -90f,
          sweepAngle = sweepAngle,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
      } else {
        // Queued / initial state: smoothly spinning arc
        drawArc(
          color = progressColor,
          startAngle = -90f + indeterminateRotation,
          sweepAngle = 70f,
          useCenter = false,
          topLeft = topLeft,
          size = arcSize,
          style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
      }
    }

    if (showStopSquare) {
      val centerSquareSize = (strokeWidth * 2.4f).coerceAtLeast(6.dp).coerceAtMost(8.dp)
      Box(
        modifier = Modifier
          .size(centerSquareSize)
          .background(progressColor, shape = RoundedCornerShape(1.5.dp)),
      )
    }
  }
}

/** Download indicator shown at the trailing edge of an episode row. */
enum class EpisodeDownloadState {
  NOT_DOWNLOADED,
  ACTIVE,
  DOWNLOADED,
}

/** Small overlay badge marking items with a completed local download. */
@Composable
private fun DownloadedBadge(modifier: Modifier = Modifier) {
  Surface(
    shape = CircleShape,
    color = Color.Black.copy(alpha = 0.7f),
    modifier = modifier.size(20.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
        imageVector = Icons.RoundedFilled.Download,
        contentDescription = null,
        // Always-dark accent keeps the badge readable on the black scrim in light mode.
        tint = (LocalDarkAppColorScheme.current ?: MaterialTheme.colorScheme).primary,
        modifier = Modifier.size(13.dp),
      )
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
  isDownloaded: Boolean = false,
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

  val backgroundColor =
    if (isSelected) {
      MaterialTheme.colorScheme.secondaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceContainerLow
    }
  // Same tonal card as episode rows; the poster's corners stay concentric with the card's.
  val cardShape = RoundedCornerShape(24.dp)
  val listPadding = 10.dp

  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .tvFocusMemory(listOf("JellyfinListItemCard", server.id, item.id))
        .tvFocusHighlight(cardShape, focusedScale = 1.02f)
        .clip(cardShape)
        .semantics { selected = isSelected }
        .tvContextMenu(onLongClick)
        .combinedClickable(
          onClick = onClick,
          onLongClick = onLongClick,
        ),
    shape = cardShape,
    color = backgroundColor,
  ) {
    Row(
      modifier = Modifier.padding(listPadding),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Box(
        modifier =
          Modifier
            .size(width = 84.dp, height = 120.dp)
            .clip(RoundedCornerShape(JellyfinSpacing.inner(24.dp, listPadding)))
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
              modifier = Modifier.size(34.dp),
            )
          }
        }

        if (item.progressPercent > 0.02f && !item.isPlayed) {
          WatchProgressOverlayBar(
            progress = item.progressPercent,
            modifier =
              Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
          )
        }

        if (isDownloaded) {
          DownloadedBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }

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
              contentDescription = null,
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
                .size(18.dp)
                .align(Alignment.TopEnd),
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Check,
              contentDescription = stringResource(R.string.jellyfin_watched),
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(2.dp),
            )
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(5.dp),
      ) {
        Text(
          text = item.name,
          style = MaterialTheme.typography.titleMedium,
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
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        if (!item.overview.isNullOrBlank()) {
          Text(
            text = item.overview,
            style = MaterialTheme.typography.bodyMedium,
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
        modifier = Modifier.size(22.dp),
      )
    }
  }
}
