/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin.seerr

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.seerr.MediaDetails
import app.gyrolet.mpvrx.domain.seerr.MediaStatus
import app.gyrolet.mpvrx.domain.seerr.MediaType
import app.gyrolet.mpvrx.domain.seerr.RequestStatus
import app.gyrolet.mpvrx.domain.seerr.SearchResultItem
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons

data class ResolutionOption(
  val label: String,
  val is4k: Boolean,
  val serverId: Int?,
  val profileId: Int?,
  val rootFolder: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeerrDetailSheet(
  searchItem: SearchResultItem?,
  details: MediaDetails?,
  isLoading: Boolean,
  isRequesting: Boolean,
  isAdmin: Boolean,
  radarrServers: List<app.gyrolet.mpvrx.domain.seerr.SeerrRadarrServer> = emptyList(),
  sonarrServers: List<app.gyrolet.mpvrx.domain.seerr.SeerrSonarrServer> = emptyList(),
  isLoadingServers: Boolean = false,
  onDismiss: () -> Unit,
  onRequest: (seasons: List<Int>?, is4k: Boolean, serverId: Int?, profileId: Int?, rootFolder: String?) -> Unit,
  onApprove: (requestId: Int) -> Unit,
  onDecline: (requestId: Int) -> Unit,
  onDeleteRequest: ((requestId: Int) -> Unit)? = null,
  onDeleteMedia: ((mediaId: Int) -> Unit)? = null,
  onOpenJellyfinItem: ((jellyfinId: String) -> Unit)? = null,
  sheetState: SheetState =
    rememberBottomSheetState(
      initialValue = SheetValue.Hidden,
      enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    ),
) {
  if (searchItem == null && details == null) return

  val mediaType = details?.let {
    if (it.numberOfSeason != null || it.seasons != null) MediaType.TV else MediaType.MOVIE
  } ?: searchItem?.getMediaType() ?: MediaType.MOVIE

  val title = details?.getDisplayTitle() ?: searchItem?.getDisplayTitle() ?: "Details"
  val overview = details?.overview ?: searchItem?.overview ?: ""
  val backdropUrl = details?.getBackdropUrl() ?: searchItem?.getBackdropUrl()
  val posterUrl = details?.getPosterUrl() ?: searchItem?.getPosterUrl()
  val rating = details?.getRating() ?: searchItem?.getRating()
  val year = details?.getYear() ?: searchItem?.getReleaseYear()
  val runtime = details?.runtime

  val mediaInfo = details?.mediaInfo ?: searchItem?.mediaInfo
  val mediaStatus = details?.getDisplayStatus() ?: searchItem?.getDisplayStatus() ?: MediaStatus.fromValue(mediaInfo?.status ?: MediaStatus.UNKNOWN.value)
  val jellyfinId = mediaInfo?.getJellyfinItemId()
  val isAvailableInJellyfin = mediaStatus == MediaStatus.AVAILABLE && !jellyfinId.isNullOrBlank()

  val seasons = details?.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()
  val seasonStatusMap = remember(details?.mediaInfo, searchItem?.mediaInfo) {
    val info = details?.mediaInfo ?: searchItem?.mediaInfo
    info?.seasons?.associate { (it.seasonNumber ?: -1) to MediaStatus.fromValue(it.status) } ?: emptyMap()
  }
  val unrequestedSeasons = remember(seasons, seasonStatusMap) {
    seasons.mapNotNull { it.seasonNumber }.filter { num ->
      val st = seasonStatusMap[num] ?: MediaStatus.UNKNOWN
      st == MediaStatus.UNKNOWN || st == MediaStatus.DELETED
    }
  }
  val selectedSeasons = remember(unrequestedSeasons) {
    mutableStateListOf<Int>().apply {
      addAll(unrequestedSeasons)
    }
  }

  val isTv = mediaType == MediaType.TV
  val resolutionOptions = remember(isTv, radarrServers, sonarrServers) {
    val options = mutableListOf<ResolutionOption>()
    if (isTv) {
      sonarrServers.forEach { server ->
        server.profiles.forEach { profile ->
          val label = buildString {
            append(profile.name ?: "Default")
            if (server.is4k == true) append(" (4K)")
            else if (sonarrServers.size > 1) append(" (${server.name})")
          }
          options.add(
            ResolutionOption(
              label = label,
              is4k = server.is4k == true,
              serverId = server.id,
              profileId = profile.id,
              rootFolder = server.activeDirectory,
            ),
          )
        }
      }
    } else {
      radarrServers.forEach { server ->
        server.profiles.forEach { profile ->
          val label = buildString {
            append(profile.name ?: "Default")
            if (server.is4k == true) append(" (4K)")
            else if (radarrServers.size > 1) append(" (${server.name})")
          }
          options.add(
            ResolutionOption(
              label = label,
              is4k = server.is4k == true,
              serverId = server.id,
              profileId = profile.id,
              rootFolder = server.activeDirectory,
            ),
          )
        }
      }
    }

    if (options.isEmpty()) {
      options.add(
        ResolutionOption(
          label = "Standard (1080p)",
          is4k = false,
          serverId = null,
          profileId = null,
          rootFolder = null,
        ),
      )
      options.add(
        ResolutionOption(
          label = "4K Ultra HD",
          is4k = true,
          serverId = null,
          profileId = null,
          rootFolder = null,
        ),
      )
    }
    options
  }

  var selectedResolution by remember(resolutionOptions) {
    mutableStateOf(
      resolutionOptions.firstOrNull { !it.is4k } ?: resolutionOptions.first(),
    )
  }
  var isOverviewExpanded by remember(details?.id, searchItem?.id) { mutableStateOf(false) }
  var canExpandOverview by remember(details?.id, searchItem?.id) { mutableStateOf(false) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    dragHandle = null,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
    ) {
      // Top Backdrop Header
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(220.dp),
      ) {
        if (!backdropUrl.isNullOrBlank()) {
          RemoteImage(
            url = backdropUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceVariant),
          )
        }

        // Gradient Scrim
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  Color.Black.copy(alpha = 0.3f),
                  Color.Transparent,
                  MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
                  MaterialTheme.colorScheme.surfaceContainerLow,
                ),
              ),
            ),
        )

        // Close button top-right
        IconButton(
          onClick = onDismiss,
          colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .size(36.dp),
        ) {
          Icon(
            Icons.RoundedFilled.Close,
            contentDescription = stringResource(R.string.generic_cancel),
            tint = Color.White,
            modifier = Modifier.size(20.dp),
          )
        }

        // Poster + Title + Metadata Row on bottom
        Row(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.Bottom,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          if (!posterUrl.isNullOrBlank()) {
            Card(
              shape = RoundedCornerShape(12.dp),
              elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
              modifier = Modifier
                .width(90.dp)
                .aspectRatio(2f / 3f),
            ) {
              RemoteImage(
                url = posterUrl,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            }
          }

          Column(
            modifier = Modifier
              .weight(1f)
              .padding(bottom = 2.dp),
          ) {
            Text(
              text = title,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )

            details?.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
              Text(
                text = tagline,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
              )
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.padding(top = 6.dp),
            ) {
              if (rating != null) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = Color.Black.copy(alpha = 0.75f),
                  contentColor = Color(0xFFFFC107),
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                  ) {
                    Icon(
                      Icons.RoundedFilled.Star,
                      contentDescription = null,
                      tint = Color(0xFFFFC107),
                      modifier = Modifier.size(12.dp),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                      text = rating,
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.Bold,
                    )
                  }
                }
              }

              if (year != null) {
                Text(
                  text = year,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              if (runtime != null && runtime > 0) {
                Text(
                  text = "${runtime / 60}h ${runtime % 60}m",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              val displayStatus = if (mediaStatus != MediaStatus.UNKNOWN) mediaStatus else searchItem?.getDisplayStatus()
              if (displayStatus != null && displayStatus != MediaStatus.UNKNOWN) {
                SeerrStatusChip(status = displayStatus)
              }
            }
          }
        }
      }

      // Genres Chip Row
      val genres = details?.genres ?: emptyList()
      if (genres.isNotEmpty()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          genres.forEach { genre ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              modifier = Modifier.padding(vertical = 2.dp),
            ) {
              Text(
                text = genre.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Action Buttons Row (Play / Request / Status / Admin Approve)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // 1. Play in Jellyfin (if available and ID present)
        if (isAvailableInJellyfin && !jellyfinId.isNullOrBlank() && onOpenJellyfinItem != null) {
          Button(
            onClick = { onOpenJellyfinItem(jellyfinId) },
            colors = ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp),
          ) {
            Icon(
              Icons.RoundedFilled.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.seerr_play_in_jellyfin),
              fontWeight = FontWeight.Bold,
            )
          }
        }

        // 2. Status / Request Button
        when {
          // Available (Movie or TV all requested)
          mediaStatus == MediaStatus.AVAILABLE && (mediaType == MediaType.MOVIE || unrequestedSeasons.isEmpty()) -> {
            if (jellyfinId.isNullOrBlank() || onOpenJellyfinItem == null) {
              Button(
                onClick = {},
                enabled = false,
                colors = ButtonDefaults.buttonColors(
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                  disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
              ) {
                Icon(Icons.RoundedFilled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = stringResource(R.string.seerr_status_available),
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }

          // Processing
          mediaStatus == MediaStatus.PROCESSING -> {
            Button(
              onClick = {},
              enabled = false,
              colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              Icon(Icons.RoundedFilled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.seerr_status_processing),
                fontWeight = FontWeight.Bold,
              )
            }
          }

          // Pending
          mediaStatus == MediaStatus.PENDING -> {
            Button(
              onClick = {},
              enabled = false,
              colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              Icon(Icons.RoundedFilled.History, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.seerr_status_pending),
                fontWeight = FontWeight.Bold,
              )
            }
          }

          // Unrequested / Partially Available
          else -> {
            // Resolution Profile Dropdown
            ResolutionProfileDropdown(
              selectedResolution = selectedResolution,
              onSelectResolution = { selectedResolution = it },
              resolutionOptions = resolutionOptions,
              isLoading = isLoadingServers,
              modifier = Modifier.padding(bottom = 6.dp),
            )

            Button(
              onClick = {
                val seasonsToRequest = if (mediaType == MediaType.TV) selectedSeasons.toList() else null
                onRequest(
                  seasonsToRequest,
                  selectedResolution.is4k,
                  selectedResolution.serverId,
                  selectedResolution.profileId,
                  selectedResolution.rootFolder,
                )
              },
              enabled = !isRequesting && (mediaType != MediaType.TV || selectedSeasons.isNotEmpty()),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
              if (isRequesting) {
                CircularProgressIndicator(
                  strokeWidth = 2.dp,
                  modifier = Modifier.size(18.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Submitting…")
              } else {
                Icon(
                  Icons.RoundedFilled.AddToQueue,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (mediaType == MediaType.TV) {
                    if (unrequestedSeasons.size < seasons.size && unrequestedSeasons.isNotEmpty()) {
                      "Request Remaining (${selectedSeasons.size})"
                    } else {
                      stringResource(R.string.seerr_request_tv)
                    }
                  } else {
                    stringResource(R.string.seerr_request_movie)
                  },
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }

        // TV Show Season Selection (Only when unrequested / partially available and not already processing/pending/available)
        if (mediaType == MediaType.TV && seasons.isNotEmpty() && unrequestedSeasons.isNotEmpty() && mediaStatus != MediaStatus.AVAILABLE && mediaStatus != MediaStatus.PROCESSING && mediaStatus != MediaStatus.PENDING) {
          Spacer(modifier = Modifier.height(4.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.seerr_select_seasons),
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.SemiBold,
            )
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = if (selectedSeasons.size == unrequestedSeasons.size) "Deselect all" else "Select all",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .clickable {
                    if (selectedSeasons.size == unrequestedSeasons.size) {
                      selectedSeasons.clear()
                    } else {
                      selectedSeasons.clear()
                      selectedSeasons.addAll(unrequestedSeasons)
                    }
                  }
                  .padding(horizontal = 6.dp, vertical = 3.dp),
              )
            }
          }

          // Season Chips
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            seasons.forEach { season ->
              val seasonNum = season.seasonNumber ?: return@forEach
              val isRequested = !unrequestedSeasons.contains(seasonNum)
              val seasonStatus = seasonStatusMap[seasonNum] ?: MediaStatus.UNKNOWN
              val isSelected = selectedSeasons.contains(seasonNum)

              val label = when {
                seasonStatus == MediaStatus.AVAILABLE -> "S$seasonNum (Available)"
                seasonStatus == MediaStatus.PROCESSING -> "S$seasonNum (Processing)"
                seasonStatus == MediaStatus.PENDING -> "S$seasonNum (Pending)"
                else -> "Season $seasonNum"
              }

              FilterChip(
                selected = isSelected,
                onClick = {
                  if (!isRequested) {
                    if (isSelected) {
                      selectedSeasons.remove(seasonNum)
                    } else {
                      selectedSeasons.add(seasonNum)
                    }
                  }
                },
                enabled = !isRequested,
                label = {
                  Text(label)
                },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                  disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                  disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                ),
              )
            }
          }
        }

        // Admin Approval Actions if Pending Request exists
        val pendingRequest = mediaInfo?.requests?.firstOrNull { it.status == RequestStatus.PENDING.value }
        if (pendingRequest != null && isAdmin) {
          HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = { onApprove(pendingRequest.id) },
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.RoundedFilled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.seerr_approve))
            }

            OutlinedButton(
              onClick = { onDecline(pendingRequest.id) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.RoundedFilled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.seerr_decline))
            }
          }
        }

        // Delete Request Action
        val activeRequest = mediaInfo?.requests?.firstOrNull()
        if (activeRequest != null && onDeleteRequest != null) {
          var showDeleteDialog by remember { mutableStateOf(false) }
          OutlinedButton(
            onClick = { showDeleteDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp),
          ) {
            Icon(Icons.RoundedFilled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Delete Request", fontWeight = FontWeight.SemiBold)
          }

          if (showDeleteDialog) {
            AlertDialog(
              onDismissRequest = { showDeleteDialog = false },
              title = { Text("Delete Request?") },
              text = { Text("Are you sure you want to delete and cancel the request for \"$title\"?") },
              confirmButton = {
                Button(
                  onClick = {
                    showDeleteDialog = false
                    onDeleteRequest(activeRequest.id)
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

      HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

      // Overview Section
      if (overview.isNotBlank()) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
          Text(
            text = "Overview",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = overview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isOverviewExpanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
              if (!isOverviewExpanded) {
                canExpandOverview = textLayoutResult.hasVisualOverflow
              }
            },
            modifier = Modifier.clickable(enabled = canExpandOverview) { isOverviewExpanded = !isOverviewExpanded },
          )
          if (canExpandOverview) {
            Text(
              text = if (isOverviewExpanded) "Show less" else "Read more",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .clickable { isOverviewExpanded = !isOverviewExpanded }
                .padding(top = 2.dp),
            )
          }
        }
      }

      // Cast & Crew Section
      val cast = details?.credits?.cast ?: emptyList()
      if (cast.isNotEmpty()) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        ) {
          Text(
            text = "Cast",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )

          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            items(cast.take(16), key = { it.id }) { member ->
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(72.dp),
              ) {
                val profileUrl = member.getProfileUrl()
                if (!profileUrl.isNullOrBlank()) {
                  RemoteImage(
                    url = profileUrl,
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                      .size(54.dp)
                      .clip(CircleShape),
                  )
                } else {
                  Box(
                    modifier = Modifier
                      .size(54.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = member.name.take(1),
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                  text = member.name,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )

                member.character?.takeIf { it.isNotBlank() }?.let { charName ->
                  Text(
                    text = charName,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
        }
      }

      if (isLoading) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }
}

@Composable
private fun ResolutionProfileDropdown(
  selectedResolution: ResolutionOption,
  onSelectResolution: (ResolutionOption) -> Unit,
  resolutionOptions: List<ResolutionOption>,
  isLoading: Boolean,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "Resolution Profile",
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )

    Box(modifier = Modifier.fillMaxWidth()) {
      Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            if (isLoading) {
              CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(
              text = selectedResolution.label,
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Icon(
            Icons.RoundedFilled.ArrowDropDown,
            contentDescription = "Select Resolution",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
      ) {
        resolutionOptions.forEach { option ->
          val isSelected = option == selectedResolution
          DropdownMenuItem(
            text = {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = option.label,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (isSelected) {
                  Icon(
                    Icons.RoundedFilled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            },
            onClick = {
              onSelectResolution(option)
              expanded = false
            },
          )
        }
      }
    }
  }
}
