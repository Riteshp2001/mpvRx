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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinPerson
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import kotlin.math.roundToInt

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicDetailHeader
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession

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
  onPersonClick: ((JellyfinPerson) -> Unit)? = null,
  onDeleteItem: ((JellyfinItem) -> Unit)? = null,
  onDownload: ((JellyfinItem) -> Unit)? = null,
  onDownloadSeason: (() -> Unit)? = null,
  onDownloadSeries: (() -> Unit)? = null,
  downloadedItemIds: Set<String> = emptySet(),
  activeDownloadItemIds: Set<String> = emptySet(),
  sheetState: SheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    ),
) {
  if (item == null) return

  if (item.type == "MusicArtist" || item.type == "MusicAlbum" || item.type == "Album" || item.type == "Playlist" || item.type == "Artist" || item.type == "AlbumArtist") {
    val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
    val currentSessionItem = queueState.currentItem

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
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Header Row (Avatar / Artwork + Title + Play Button)
        val imageUrl = remember(server.serverUrl, item.id, item.primaryImageTag, server.accessToken) {
          JellyfinClient.getImageUrl(
            serverUrl = server.serverUrl,
            itemId = item.id,
            imageTag = item.primaryImageTag,
            maxWidth = 300,
            token = server.accessToken,
          )
        }
        val isArtist = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist"
        val subtitle = if (isArtist) null else (item.seriesName ?: item.overview)?.takeIf { it.isNotBlank() }
        val totalSec = item.durationSeconds.takeIf { it > 0 }
          ?: episodes.sumOf { it.durationSeconds }.takeIf { it > 0 }
        val durationFormatted = totalSec?.let { DateUtils.formatElapsedTime(it) }
        val countText = if (isArtist) null else "${episodes.size} ${if (item.type == "Playlist") "Items" else "Tracks"}"
        val itemCountText = listOfNotNull(countText, durationFormatted).joinToString(" • ").takeIf { it.isNotBlank() }

        SharedMusicDetailHeader(
          title = item.name,
          subtitle = subtitle,
          itemCountText = itemCountText,
          artworkUrl = if (!item.primaryImageTag.isNullOrBlank()) imageUrl else null,
          fallbackIcon = when {
            isArtist -> Icons.RoundedFilled.Person
            item.type == "Playlist" -> Icons.RoundedFilled.QueueMusic
            else -> Icons.RoundedFilled.Audiotrack
          },
          isCircular = isArtist,
          onPlayAll = if (episodes.isNotEmpty()) ({ onPlay(episodes.first(), false) }) else null,
          playButtonText = if (isArtist) "Play All" else "Play",
        )

        if (isLoading) {
          GhostDetailSections()
        } else {
          // Albums section for Artist Sheet
          if ((item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist") && seasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "Albums",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 0.dp),
              ) {
                items(seasons, key = { it.id }) { album ->
                  JellyfinMusicCard(
                    item = album,
                    server = server,
                    onClick = { onItemClick(album) },
                    cardWidth = 130.dp,
                  )
                }
              }
            }
          }

          // Songs / Tracks list section
          if (episodes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = if (item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist") "Songs" else "Tracks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              episodes.forEach { track ->
                val trackImageUrl = remember(server.serverUrl, track.id, track.primaryImageTag, server.accessToken) {
                  JellyfinClient.getImageUrl(
                    serverUrl = server.serverUrl,
                    itemId = track.id,
                    imageTag = track.primaryImageTag,
                    maxWidth = 200,
                    token = server.accessToken,
                  )
                }

                val isTrackPlaying = remember(currentSessionItem, track.id) {
                  if (currentSessionItem == null || track.id.isBlank()) false
                  else {
                    val orig = currentSessionItem.originalUri
                    val play = currentSessionItem.playableUri
                    orig.contains(track.id, ignoreCase = true) || play.contains(track.id, ignoreCase = true)
                  }
                }
                val trackSubtitle = track.seriesName ?: track.overview ?: ""

                SharedMusicTrackListItem(
                  title = track.name,
                  subtitle = trackSubtitle,
                  artworkUrl = if (!track.primaryImageTag.isNullOrBlank()) trackImageUrl else null,
                  durationSeconds = track.durationSeconds,
                  isPlaying = isTrackPlaying,
                  onClick = { onPlay(track, false) },
                )
              }
            }
          }
        }
      }
    }
    return
  }

  var isOverviewExpanded by remember(item.id) { mutableStateOf(false) }
  var canExpandOverview by remember(item.id) { mutableStateOf(false) }
  val context = LocalContext.current

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
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier =
              Modifier
                .size(width = 86.dp, height = 126.dp)
                .clip(RoundedCornerShape(8.dp)),
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

        // Action Buttons: play on its own row, secondary icons below (one row was cramped)
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Compute playback state for Series, Episode, or Movie
          val inProgressEpisode = remember(episodes) {
            episodes
              .filter { it.progressPercent > 0.05f }
              .maxWithOrNull(
                compareBy<JellyfinItem> { it.lastPlayedDate ?: "" }
                  .thenBy { it.parentIndexNumber ?: 1 }
                  .thenBy { it.indexNumber ?: 1 },
              )
          }
          val nextUnplayedEpisode = remember(episodes) { episodes.firstOrNull { !it.isPlayed } }
          val isAllEpisodesPlayed = remember(episodes) { episodes.isNotEmpty() && episodes.all { it.isPlayed } }

          val targetItem: JellyfinItem
          val playLabel: String
          val isResumeMode: Boolean
          val isRestartSeriesMode: Boolean

          if (item.isSeries) {
            when {
              inProgressEpisode != null -> {
                targetItem = inProgressEpisode
                val s = inProgressEpisode.parentIndexNumber ?: 1
                val e = inProgressEpisode.indexNumber ?: 1
                playLabel = "Resume S$s:E$e"
                isResumeMode = true
                isRestartSeriesMode = false
              }
              nextUnplayedEpisode != null -> {
                targetItem = nextUnplayedEpisode
                val s = nextUnplayedEpisode.parentIndexNumber ?: 1
                val e = nextUnplayedEpisode.indexNumber ?: 1
                playLabel = "Watch S$s:E$e"
                isResumeMode = false
                isRestartSeriesMode = false
              }
              isAllEpisodesPlayed || item.isPlayed -> {
                targetItem = episodes.firstOrNull() ?: item
                playLabel = "Restart Series"
                isResumeMode = false
                isRestartSeriesMode = true
              }
              else -> {
                targetItem = episodes.firstOrNull() ?: item
                val s = targetItem.parentIndexNumber ?: 1
                val e = targetItem.indexNumber ?: 1
                playLabel = "Watch S$s:E$e"
                isResumeMode = false
                isRestartSeriesMode = false
              }
            }
          } else {
            targetItem = item
            isRestartSeriesMode = false
            when {
              item.progressPercent > 0.05f -> {
                playLabel = "Resume"
                isResumeMode = true
              }
              item.isPlayed -> {
                playLabel = "Watch Again"
                isResumeMode = false
              }
              item.type == "Episode" -> {
                val s = item.parentIndexNumber ?: 1
                val e = item.indexNumber ?: 1
                playLabel = "Watch S$s:E$e"
                isResumeMode = false
              }
              else -> {
                playLabel = "Play Movie"
                isResumeMode = false
              }
            }
          }

          // Play / Resume Row
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = { onPlay(targetItem, isRestartSeriesMode) },
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(vertical = 12.dp),
            ) {
              Icon(
                imageVector = if (isRestartSeriesMode) Icons.RoundedFilled.Refresh else Icons.RoundedFilled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = playLabel,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
              )
            }

            // Play from Beginning icon button if in progress
            if (isResumeMode) {
              FilledTonalIconButton(
                onClick = { onPlay(targetItem, true) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(48.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Refresh,
                  contentDescription = "Play from Beginning",
                  tint = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.size(22.dp),
                )
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
          ) {
          // Trailer Button for Movies & Series
          if (item.type == "Movie" || item.isSeries || item.type == "Series") {
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
                runCatching { context.startActivity(intent) }
              },
              shape = RoundedCornerShape(14.dp),
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Movie,
                contentDescription = "Trailer",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
              )
            }
          }

          // Download Button (movie: direct; series: season / all-seasons menu)
          if (onDownload != null && (item.type == "Movie" || item.isSeries)) {
            var isDownloadMenuOpen by remember { mutableStateOf(false) }
            val isItemDownloaded = item.id in downloadedItemIds
            val isItemDownloading = item.id in activeDownloadItemIds
            Box {
              FilledTonalIconButton(
                onClick = {
                  when {
                    item.isSeries -> isDownloadMenuOpen = true
                    isItemDownloaded || isItemDownloading -> {}
                    else -> onDownload(item)
                  }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(48.dp),
              ) {
                when {
                  isItemDownloading ->
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                  isItemDownloaded ->
                    Icon(
                      imageVector = Icons.RoundedFilled.CheckCircle,
                      contentDescription = stringResource(R.string.downloads_downloaded),
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(22.dp),
                    )
                  else ->
                    Icon(
                      imageVector = Icons.RoundedFilled.Download,
                      contentDescription = stringResource(R.string.downloads_download),
                      tint = MaterialTheme.colorScheme.onSecondaryContainer,
                      modifier = Modifier.size(22.dp),
                    )
                }
              }

              DropdownMenu(
                expanded = isDownloadMenuOpen,
                onDismissRequest = { isDownloadMenuOpen = false },
              ) {
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.downloads_download_season)) },
                  onClick = {
                    isDownloadMenuOpen = false
                    onDownloadSeason?.invoke()
                  },
                )
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.downloads_download_series)) },
                  onClick = {
                    isDownloadMenuOpen = false
                    onDownloadSeries?.invoke()
                  },
                )
              }
            }
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
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
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
              tint = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.size(22.dp),
            )
          }

          // Delete Media Button (Allowed if user/item has deletion permissions)
          if (onDeleteItem != null && item.canDelete) {
            var showDeleteDialog by remember { mutableStateOf(false) }
            FilledTonalIconButton(
              onClick = { showDeleteDialog = true },
              shape = RoundedCornerShape(14.dp),
              colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.error,
              ),
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Delete,
                contentDescription = "Delete Item",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
              )
            }

            if (showDeleteDialog) {
              AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete ${if (item.isSeries) "Series" else "Item"}?") },
                text = { Text("Are you sure you want to delete \"${item.name}\" from your Jellyfin server? This will permanently delete the media files.") },
                confirmButton = {
                  Button(
                    onClick = {
                      showDeleteDialog = false
                      onDeleteItem(item)
                    },
                    colors = ButtonDefaults.buttonColors(
                      containerColor = MaterialTheme.colorScheme.error,
                      contentColor = MaterialTheme.colorScheme.onError,
                    ),
                  ) {
                    Text("Delete")
                  }
                },
                dismissButton = {
                  TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                  }
                },
              )
            }
          }
          }
        }

        // Overview / Synopsis with expand animation
        val isArtistItem = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist"
        if (!isArtistItem && !item.overview.isNullOrBlank()) {
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable(enabled = canExpandOverview) { isOverviewExpanded = !isOverviewExpanded },
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
              onTextLayout = { textLayoutResult ->
                if (!isOverviewExpanded) {
                  canExpandOverview = textLayoutResult.hasVisualOverflow
                }
              },
            )
            if (canExpandOverview) {
              Text(
                text = if (isOverviewExpanded) "Show less" else "Read more",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }

        // Directors, Writers, Producers
        val directors = remember(item.people) { item.directors }
        val writers = remember(item.people) { item.writers }
        val producers = remember(item.people) { item.producers }

        if (directors.isNotEmpty() || writers.isNotEmpty() || producers.isNotEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            if (directors.isNotEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = if (directors.size > 1) "Directors: " else "Director: ",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                directors.forEachIndexed { index, person ->
                  Text(
                    text = person.name + if (index < directors.lastIndex) ", " else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (onPersonClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = onPersonClick != null) {
                      onPersonClick?.invoke(person)
                    },
                  )
                }
              }
            }

            if (writers.isNotEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = if (writers.size > 1) "Writers: " else "Writer: ",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                writers.forEachIndexed { index, person ->
                  Text(
                    text = person.name + if (index < writers.lastIndex) ", " else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (onPersonClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = onPersonClick != null) {
                      onPersonClick?.invoke(person)
                    },
                  )
                }
              }
            }

            if (producers.isNotEmpty()) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = if (producers.size > 1) "Producers: " else "Producer: ",
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                )
                producers.forEachIndexed { index, person ->
                  Text(
                    text = person.name + if (index < producers.lastIndex) ", " else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (onPersonClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = onPersonClick != null) {
                      onPersonClick?.invoke(person)
                    },
                  )
                }
              }
            }
          }
        }

        // Cast Section
        val cast = remember(item.people) { item.actors }
        if (cast.isNotEmpty()) {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Text(
              text = "Cast",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
            )

            LazyRow(
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              contentPadding = PaddingValues(vertical = 4.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              items(cast, key = { "${it.id}|${it.role ?: ""}" }) { person ->
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  modifier = Modifier
                    .width(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = onPersonClick != null) { onPersonClick?.invoke(person) }
                    .padding(vertical = 4.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  val personImageUrl = remember(person.id, person.primaryImageTag, server.serverUrl, server.accessToken) {
                    JellyfinClient.getImageUrl(
                      serverUrl = server.serverUrl,
                      itemId = person.id,
                      imageTag = person.primaryImageTag,
                      maxWidth = 200,
                      token = server.accessToken,
                    )
                  }

                  if (!person.primaryImageTag.isNullOrBlank()) {
                    RemoteImage(
                      url = personImageUrl,
                      contentDescription = person.name,
                      contentScale = ContentScale.Crop,
                      modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                    )
                  } else {
                    Box(
                      modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = person.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }

                  Text(
                    text = person.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                  )

                  person.role?.takeIf { it.isNotBlank() }?.let { role ->
                    Text(
                      text = role,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      textAlign = TextAlign.Center,
                    )
                  }
                }
              }
            }
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

            // Seasons Dropdown Menu
            val sortedSeasons = remember(seasons) {
              seasons.sortedWith(
                compareBy<JellyfinItem> { it.indexNumber ?: Int.MAX_VALUE }
                  .thenBy { it.name }
              )
            }
            val selectedSeason = remember(sortedSeasons, selectedSeasonId) {
              sortedSeasons.find { it.id == selectedSeasonId } ?: sortedSeasons.firstOrNull()
            }
            var isSeasonDropdownExpanded by remember { mutableStateOf(false) }
            val arrowRotation by animateFloatAsState(
              targetValue = if (isSeasonDropdownExpanded) 180f else 0f,
              label = "season_arrow_rotation",
            )

            Box(modifier = Modifier.wrapContentSize()) {
              Surface(
                onClick = { isSeasonDropdownExpanded = !isSeasonDropdownExpanded },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(
                  width = 1.dp,
                  color = if (isSeasonDropdownExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = selectedSeason?.name ?: "Select Season",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Icon(
                    imageVector = Icons.RoundedFilled.ArrowDropDown,
                    contentDescription = "Select Season",
                    tint = if (isSeasonDropdownExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(arrowRotation),
                  )
                }
              }

              DropdownMenu(
                expanded = isSeasonDropdownExpanded,
                onDismissRequest = { isSeasonDropdownExpanded = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.widthIn(min = 180.dp),
              ) {
                sortedSeasons.forEach { season ->
                  val isSelected = season.id == selectedSeasonId
                  Surface(
                    onClick = {
                      isSeasonDropdownExpanded = false
                      onSelectSeason(season.id)
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 6.dp, vertical = 2.dp),
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                      Text(
                        text = season.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                      )
                      if (season.childCount != null && season.childCount > 0) {
                        Text(
                          text = "${season.childCount} ep",
                          style = MaterialTheme.typography.labelSmall,
                          color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                  }
                }
              }
            }

            // Episode List
            if (isEpisodesLoading) {
              GhostEpisodeRows()
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
                    downloadState =
                      when {
                        onDownload == null -> null
                        episode.id in downloadedItemIds -> EpisodeDownloadState.DOWNLOADED
                        episode.id in activeDownloadItemIds -> EpisodeDownloadState.ACTIVE
                        else -> EpisodeDownloadState.NOT_DOWNLOADED
                      },
                    onDownload = { onDownload?.invoke(episode) },
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

/** Pulsing placeholder block used while sheet sections stream in. */
@Composable
private fun GhostBlock(modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "ghost_pulse")
  val alpha by transition.animateFloat(
    initialValue = 0.35f,
    targetValue = 0.8f,
    animationSpec =
      infiniteRepeatable(
        animation = tween(durationMillis = 650),
        repeatMode = RepeatMode.Reverse,
      ),
    label = "ghost_alpha",
  )
  Box(
    modifier =
      modifier.background(
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
        RoundedCornerShape(10.dp),
      ),
  )
}

/** Skeleton for the episode list while a season's episodes load. */
@Composable
private fun GhostEpisodeRows(count: Int = 4) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    repeat(count) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        GhostBlock(Modifier.width(128.dp).height(72.dp))
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          GhostBlock(Modifier.fillMaxWidth(0.72f).height(14.dp))
          GhostBlock(Modifier.fillMaxWidth(0.45f).height(12.dp))
        }
      }
    }
  }
}

/** Skeleton for the seasons/similar area while the full item detail loads. */
@Composable
private fun GhostDetailSections() {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    GhostBlock(Modifier.width(160.dp).height(36.dp))
    GhostEpisodeRows(count = 3)
    GhostBlock(Modifier.width(140.dp).height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      repeat(3) {
        GhostBlock(Modifier.width(120.dp).height(180.dp))
      }
    }
  }
}
