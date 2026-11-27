/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.navidrome

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.presentation.components.ResponsiveBottomSheet
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicCarouselSection
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicDetailHeader
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavidromeDetailSheet(
  uiState: NavidromeUiState,
  server: NavidromeServer,
  viewModel: NavidromeViewModel,
  onDismiss: () -> Unit,
) {
  val album = uiState.detailAlbum
  val artist = uiState.detailArtist
  val playlist = uiState.detailPlaylist

  if (album == null && artist == null && playlist == null) return

  val context = LocalContext.current
  val navidromeRepository = koinInject<NavidromeRepository>()

  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentSessionItem = queueState.currentItem

  ResponsiveBottomSheet(
    onDismissRequest = onDismiss,
    animateEntrance = false,
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    LazyColumn(
      modifier =
        Modifier
          .fillMaxWidth()
          .navigationBarsPadding(),
      contentPadding = PaddingValues(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      if (album != null) {
        item {
          val details =
            buildList {
              album.year?.let { add(it.toString()) }
              if (album.songCount > 0) add("${album.songCount} tracks")
            }.joinToString(" • ")

          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            SharedMusicDetailHeader(
              title = album.title,
              subtitle = album.artist.ifBlank { null },
              itemCountText = details.ifBlank { null },
              artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId, size = 300),
              onPlayAll = { viewModel.playAll(context, album.songs, 0) },
              onShuffle = { viewModel.shufflePlay(context, album.songs) },
              playbackEnabled = album.songs.isNotEmpty(),
              trailingAction = {
                FilledTonalIconButton(
                  onClick = { viewModel.toggleAlbumFavorite(album) },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(
                    imageVector =
                      if (album.isFavorite) {
                        Icons.RoundedFilled.Favorite
                      } else {
                        Icons.RoundedFilled.FavoriteBorder
                      },
                    contentDescription =
                      stringResource(
                        if (album.isFavorite) {
                          R.string.generic_remove_from_favorites
                        } else {
                          R.string.notification_add_to_favorites
                        },
                      ),
                    tint =
                      if (album.isFavorite) {
                        MaterialTheme.colorScheme.error
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                  )
                }
              },
            )
          }

          if (album.songs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = "Tracks",
              style = MaterialTheme.typography.titleMediumEmphasized,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }

        itemsIndexed(album.songs, key = { _, s -> s.id }) { index, song ->
          val isTrackPlaying =
            remember(currentSessionItem, song.id) {
              if (currentSessionItem == null || song.id.isBlank()) {
                false
              } else {
                val orig = currentSessionItem.originalUri
                val play = currentSessionItem.playableUri
                orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
              }
            }
          SharedMusicTrackListItem(
            title = song.title,
            subtitle = song.artist,
            durationSeconds = song.durationSeconds.toLong(),
            artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
            isPlaying = isTrackPlaying,
            isFavorite = song.isFavorite,
            onFavoriteClick = { viewModel.toggleFavorite(song) },
            onClick = { viewModel.playSong(context, song) },
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = rememberConnectedShape(index, album.songs.size, ConnectedLayout.Vertical),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          )
        }
      } else if (playlist != null) {
        item {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            val playlistDuration =
              playlist.durationSeconds.takeIf { it > 0 }
                ?: playlist.songs.sumOf { it.durationSeconds }.takeIf { it > 0 }
            val playlistDurationFormatted = playlistDuration?.let { DateUtils.formatElapsedTime(it.toLong()) }
            val countText =
              if (playlist.songCount ==
                1
              ) {
                "1 track"
              } else {
                "${playlist.songCount.coerceAtLeast(playlist.songs.size)} tracks"
              }
            val itemCountText = listOfNotNull(countText, playlistDurationFormatted).joinToString(" • ")

            SharedMusicDetailHeader(
              title = playlist.name,
              subtitle = null,
              itemCountText = itemCountText,
              artworkUrl = navidromeRepository.getCoverArtUrl(server, playlist.coverArtId, size = 300),
              fallbackIcon =
                if (playlist.id == "virtual_favorites_playlist" ||
                  playlist.id == "favorites"
                ) {
                  Icons.RoundedFilled.Favorite
                } else {
                  Icons.RoundedFilled.QueueMusic
                },
              onPlayAll = { viewModel.playAll(context, playlist.songs, 0) },
              onShuffle = { viewModel.shufflePlay(context, playlist.songs) },
              playbackEnabled = playlist.songs.isNotEmpty(),
            )
          }

          if (playlist.songs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
              text = "Tracks",
              style = MaterialTheme.typography.titleMediumEmphasized,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }

        itemsIndexed(playlist.songs, key = { _, s -> s.id }) { index, song ->
          val isTrackPlaying =
            remember(currentSessionItem, song.id) {
              if (currentSessionItem == null || song.id.isBlank()) {
                false
              } else {
                val orig = currentSessionItem.originalUri
                val play = currentSessionItem.playableUri
                orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
              }
            }
          SharedMusicTrackListItem(
            title = song.title,
            subtitle = song.artist,
            durationSeconds = song.durationSeconds.toLong(),
            artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
            isPlaying = isTrackPlaying,
            isFavorite = song.isFavorite,
            onFavoriteClick = { viewModel.toggleFavorite(song) },
            onClick = { viewModel.playSong(context, song) },
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = rememberConnectedShape(index, playlist.songs.size, ConnectedLayout.Vertical),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
          )
        }
      } else if (artist != null) {
        item {
          Box(
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
          ) {
            SharedMusicDetailHeader(
              title = artist.name,
              subtitle = "${artist.albumCount} albums",
              artworkUrl = navidromeRepository.getArtistImageUrl(server, artist, size = 300),
              fallbackIcon = Icons.RoundedFilled.Person,
              isCircular = true,
              trailingAction = {
                FilledTonalIconButton(
                  onClick = { viewModel.toggleArtistFavorite(artist) },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(
                    imageVector =
                      if (artist.isFavorite) {
                        Icons.RoundedFilled.Favorite
                      } else {
                        Icons.RoundedFilled.FavoriteBorder
                      },
                    contentDescription =
                      stringResource(
                        if (artist.isFavorite) {
                          R.string.generic_remove_from_favorites
                        } else {
                          R.string.notification_add_to_favorites
                        },
                      ),
                    tint =
                      if (artist.isFavorite) {
                        MaterialTheme.colorScheme.error
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      },
                  )
                }
              },
            )
          }
        }

        if (artist.albums.isNotEmpty()) {
          item {
            SharedMusicCarouselSection(
              title = "Albums (${artist.albums.size})",
              items = artist.albums,
              getId = { it.id },
              getTitle = { it.title },
              getSubtitle = { it.year?.toString() ?: "" },
              getArtworkUrl = { navidromeRepository.getCoverArtUrl(server, it.coverArtId) },
              onClick = { viewModel.openAlbumDetail(it) },
              cardWidth = 140.dp,
            )
          }
        }
      }
    }
  }
}
