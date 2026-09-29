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
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.domain.network.NetworkProtocol
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.domain.media.model.VideoFolder
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.utils.storage.FileTypeUtils
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

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
  val context = LocalContext.current
  val repository = koinInject<PlaylistRepository>()
  val thumbnailRepository = koinInject<ThumbnailRepository>()
  val preferences = koinInject<BrowserPreferences>()
  val appearancePreferences = koinInject<AppearancePreferences>()
  val viewPreferences = preferences.playlistView
  val thumbnailQuality by preferences.thumbnailQuality.collectAsState()
  val thumbnailMode by preferences.thumbnailMode.collectAsState()
  val thumbnailFramePosition by preferences.thumbnailFramePosition.collectAsState()
  val showThumbnails by viewPreferences.showThumbnails.collectAsState()
  val showLocation by viewPreferences.showLocation.collectAsState()
  val sourceLocation = remember(playlist.m3uSourceUrl, playlist.xtreamServerUrl) {
    app.gyrolet.mpvrx.ui.browser.playlist.playlistSourceLocation(playlist.m3uSourceUrl ?: playlist.xtreamServerUrl)
  }
  val showNetworkThumbnails by appearancePreferences.showNetworkThumbnails.collectAsState()
  val firstItem by remember(repository, playlist.id) {
    repository.observeFirstPlaylistItem(playlist.id)
  }.collectAsState(initial = null)
  val thumbnailSizePx = with(LocalDensity.current) { (if (isGridMode) 192.dp else 96.dp).roundToPx() }
  val resolvedThumbnail by produceState<Bitmap?>(
    initialValue = thumbnail.takeIf { showThumbnails },
    playlist.id, thumbnail, firstItem?.filePath, firstItem?.tvgLogo, firstItem?.addedAt,
    firstItem?.licenseType, thumbnailSizePx, thumbnailQuality, thumbnailMode, thumbnailFramePosition, showNetworkThumbnails, showThumbnails,
  ) {
    value = thumbnail.takeIf { showThumbnails }
    if (!showThumbnails) return@produceState
    if (thumbnail != null) return@produceState
    val item = firstItem ?: return@produceState
    if (app.gyrolet.mpvrx.domain.archive.ZipArchiveMedia.isPlaybackUri(item.filePath)) return@produceState
    value = withContext(Dispatchers.IO) {
      try {
        EmbeddedArtworkResolver.decodeArtworkUri(context, item.tvgLogo) ?: if (item.licenseType.isNullOrBlank()) {
          val path = item.filePath.substringBefore('|')
          val uri = Uri.parse(path).let { if (it.scheme.isNullOrBlank()) Uri.fromFile(File(path)) else it }
          val isAudio = FileTypeUtils.isAudioFile(File(path))
          thumbnailRepository.getThumbnail(
            Video(
              id = item.id.toLong(),
              title = item.fileName,
              displayName = item.fileName,
              path = path,
              uri = uri,
              duration = 0L,
              durationFormatted = "",
              size = item.fileSize ?: 0L,
              sizeFormatted = "",
              dateModified = item.addedAt / 1000L,
              dateAdded = item.addedAt / 1000L,
              mimeType = if (isAudio) "audio/*" else "video/*",
              bucketId = "",
              bucketDisplayName = "",
              width = 0,
              height = 0,
              fps = 0f,
              resolution = "",
              isAudio = isAudio,
            ),
            thumbnailSizePx,
            thumbnailSizePx,
          )
        } else null
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        null
      }
    }
  }
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
        playlist.isZipPlaylist -> stringResource(R.string.playlist_zip_read_only)
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
    if (showLocation && sourceLocation.isNotBlank()) {
      androidx.compose.material3.Text(
        text = sourceLocation,
        modifier = Modifier.fillMaxWidth(),
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      )
    }
  }

  val thumbnailBitmap = remember(resolvedThumbnail) { resolvedThumbnail?.asImageBitmap() }

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
        isFavorites -> Icons.RoundedFilled.Bookmarks
        playlist.isZipPlaylist -> Icons.RoundedFilled.FolderZip
        playlist.isXtreamPlaylist -> Icons.RoundedFilled.Tv
        else -> Icons.RoundedFilled.PlaylistPlay
      },
    modifier = modifier,
    customChipContent = customChipRenderer,
    isGridMode = isGridMode,
    thumbnail = thumbnailBitmap,
    placeholderIconSize = if (isFavorites) (if (isGridMode) 40.dp else 32.dp) else null,
    viewPreferences = viewPreferences,
  )
}
