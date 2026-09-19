/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.theme.AppShapeScale

/**
 * Card for displaying a playlist item
 *
 * @param playlist The playlist entity to display
 * @param itemCount Number of items in the playlist
 * @param onClick Action to perform when the card is clicked
 * @param onLongClick Action to perform when the card is long-pressed
 * @param onThumbClick Action to perform when the thumbnail is clicked
 * @param modifier Optional modifier for the card
 * @param isSelected Whether the card is in a selected state
 * @param isGridMode Whether the card should display in grid mode
 * @param thumbnail Optional thumbnail bitmap to display
 */
@Composable
fun PlaylistCard(
  playlist: PlaylistEntity,
  itemCount: Int,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onThumbClick: () -> Unit,
  modifier: Modifier = Modifier,
  isSelected: Boolean = false,
  isGridMode: Boolean = false,
  thumbnail: android.graphics.Bitmap? = null,
  /** Source kinds present in this playlist; null means a local file. Empty for M3U playlists. */
  sources: List<NetworkProtocol?> = emptyList(),
) {
  val isFavorites = playlist.name.equals(PlaylistRepository.FAVORITES_PLAYLIST_NAME, ignoreCase = true)
  val displayName =
    when {
      !isFavorites -> playlist.name
      playlist.isAudio -> stringResource(R.string.playlist_favorite_songs)
      else -> stringResource(R.string.playlist_favorite_videos)
    }
  // Convert playlist to VideoFolder format for FolderCard
  val folderModel =
    VideoFolder(
      bucketId = playlist.id.toString(),
      name = displayName,
      path = "", // Not used for playlists
      videoCount = itemCount,
      totalSize = 0, // Not tracked for playlists
      totalDuration = 0, // Not tracked for playlists
      lastModified = playlist.updatedAt / 1000,
    )

  // Playlists imported from a URL are identified by their type. A regular playlist has no such
  // type of its own, so it badges every source its entries actually come from instead — the same
  // chips the detail screen puts on each video.
  val customChipRenderer: @Composable () -> Unit = {
    val isOnlinePlaylist =
      playlist.m3uSourceUrl?.let { source ->
        YtdlpManager.isPotentialPlaylistUrl(source) && YtdlpManager.requiresYtdlp(source)
      } == true
    val typeBadge =
      when {
        playlist.isXtreamPlaylist -> stringResource(R.string.playlist_xtream_badge)
        isOnlinePlaylist -> stringResource(R.string.playlist_online_badge)
        playlist.isM3uPlaylist -> stringResource(R.string.playlist_m3u_badge)
        else -> null
      }

    if (typeBadge != null) {
      // Use Material Design theme colors
      val materialTheme = androidx.compose.material3.MaterialTheme.colorScheme
      val (chipColor, chipBgColor) =
        if (playlist.isM3uPlaylist) {
          Pair(materialTheme.tertiary, materialTheme.tertiaryContainer)
        } else {
          Pair(materialTheme.primary, materialTheme.primaryContainer)
        }

      androidx.compose.material3.Text(
        text = typeBadge,
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        modifier =
          Modifier
            .background(
              chipBgColor,
              AppShapeScale.small,
            ).padding(horizontal = 8.dp, vertical = 4.dp),
        color = chipColor,
      )
    } else {
      sources.forEach { protocol ->
        SourceChip(
          label = protocol?.displayName ?: "Local",
          color = sourceChipColor(protocol),
        )
      }
    }
  }

  val thumbnailBitmap = remember(thumbnail) { thumbnail?.asImageBitmap() }

  // Use the FolderCard component with playlist-specific customizations
  FolderCard(
    folder = folderModel,
    isSelected = isSelected,
    isRecentlyPlayed = false,
    onClick = onClick,
    onLongClick = onLongClick,
    onThumbClick = onThumbClick,
    showDateModified = true,
    customIcon =
      when {
        isFavorites -> Icons.RoundedFilled.Favorite
        playlist.isXtreamPlaylist -> Icons.RoundedFilled.Tv
        else -> Icons.RoundedFilled.PlaylistPlay
      },
    modifier = modifier,
    customChipContent = customChipRenderer,
    isGridMode = isGridMode,
    thumbnail = thumbnailBitmap,
  )
}
