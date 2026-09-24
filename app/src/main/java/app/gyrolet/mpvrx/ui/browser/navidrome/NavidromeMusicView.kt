/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.navidrome

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.domain.navidrome.NavidromeAlbum
import app.gyrolet.mpvrx.domain.navidrome.NavidromeArtist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeMusicTab
import app.gyrolet.mpvrx.domain.navidrome.NavidromePlaylist
import app.gyrolet.mpvrx.domain.navidrome.NavidromeServer
import app.gyrolet.mpvrx.domain.navidrome.NavidromeSong
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.music.MusicSortOrder
import app.gyrolet.mpvrx.ui.browser.music.MusicViewMode
import app.gyrolet.mpvrx.ui.browser.music.SharedCompactTrackGridSection
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicCarouselSection
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicGridCard
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import org.koin.compose.koinInject

@Composable
fun NavidromeMusicView(
  uiState: NavidromeUiState,
  server: NavidromeServer,
  pagerState: PagerState,
  visibleTabs: List<NavidromeMusicTab>,
  onTabSelected: (NavidromeMusicTab) -> Unit,
  onSongClick: (NavidromeSong) -> Unit,
  onAlbumClick: (NavidromeAlbum) -> Unit,
  onArtistClick: (NavidromeArtist) -> Unit,
  onPlaylistClick: (NavidromePlaylist) -> Unit,
  onToggleFavorite: (NavidromeSong) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val navidromeRepository = koinInject<NavidromeRepository>()
  val browserPreferences = koinInject<BrowserPreferences>()
  val coverArtSizeDp by browserPreferences.musicCoverArtSize.collectAsState()
  val gridCoverArtSizeDp by browserPreferences.musicGridCoverArtSize.collectAsState()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentSessionItem = queueState.currentItem

  NavigationPager(
    state = pagerState,
    modifier = modifier
      .fillMaxSize()
      .background(app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor()),
    beyondViewportPageCount = 1,
    key = { page -> visibleTabs.getOrNull(page) ?: page },
  ) { page ->
    val tab = visibleTabs.getOrNull(page) ?: NavidromeMusicTab.HOME
    Box(modifier = Modifier.fillMaxSize()) {
      when (tab) {
        NavidromeMusicTab.HOME -> {
          if (uiState.isLoading && uiState.jumpBackIn.isEmpty() && uiState.playlists.isEmpty() &&
            uiState.recentlyAddedAlbums.isEmpty() && uiState.artistsToExplore.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else {
            NavidromeHomeContent(
              uiState = uiState,
              server = server,
              navidromeRepository = navidromeRepository,
              onTabSelected = onTabSelected,
              onSongClick = onSongClick,
              onAlbumClick = onAlbumClick,
              onArtistClick = onArtistClick,
              onPlaylistClick = onPlaylistClick,
              navigationBarHeight = navigationBarHeight,
            )
          }
        }

        NavidromeMusicTab.TRACKS -> {
          if (uiState.isLoading && uiState.tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else {
            val sortedTracks = remember(uiState.tracks, uiState.sortField, uiState.sortOrder) {
              val sorted = when (uiState.sortField) {
                MusicSortField.TITLE -> uiState.tracks.sortedBy { it.title.lowercase() }
                MusicSortField.ARTIST -> uiState.tracks.sortedBy { it.artist.lowercase() }
                MusicSortField.ALBUM -> uiState.tracks.sortedBy { it.album.lowercase() }
                MusicSortField.DURATION -> uiState.tracks.sortedBy { it.durationSeconds }
                MusicSortField.YEAR -> uiState.tracks.sortedBy { it.year ?: 0 }
                else -> uiState.tracks.sortedBy { it.title.lowercase() }
              }
              if (uiState.sortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
            }

            if (uiState.viewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedTracks, key = { it.id }) { song ->
                  val isPlaying = remember(currentSessionItem, song.id) {
                    if (currentSessionItem == null || song.id.isBlank()) false
                    else {
                      val orig = currentSessionItem.originalUri
                      val play = currentSessionItem.playableUri
                      orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
                    }
                  }
                  SharedMusicGridCard(
                    title = song.title,
                    subtitle = song.artist,
                    thirdLine = DateUtils.formatElapsedTime(song.durationSeconds.toLong()),
                    artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
                    isPlaying = isPlaying,
                    onClick = { onSongClick(song) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = navigationBarHeight + 84.dp),
              ) {
                items(sortedTracks, key = { it.id }) { song ->
                  val isPlaying = remember(currentSessionItem, song.id) {
                    if (currentSessionItem == null || song.id.isBlank()) false
                    else {
                      val orig = currentSessionItem.originalUri
                      val play = currentSessionItem.playableUri
                      orig.contains(song.id, ignoreCase = true) || play.contains(song.id, ignoreCase = true)
                    }
                  }
                  SharedMusicTrackListItem(
                    title = song.title,
                    subtitle = "${song.artist} • ${song.album}",
                    durationSeconds = song.durationSeconds.toLong(),
                    artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
                    isPlaying = isPlaying,
                    isFavorite = song.isFavorite,
                    onFavoriteClick = { onToggleFavorite(song) },
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onSongClick(song) },
                  )
                }
              }
            }
          }
        }

        NavidromeMusicTab.ALBUMS -> {
          if (uiState.isLoading && uiState.albums.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else {
            val sortedAlbums = remember(uiState.albums, uiState.sortField, uiState.sortOrder) {
              val sorted = when (uiState.sortField) {
                MusicSortField.TITLE -> uiState.albums.sortedBy { it.title.lowercase() }
                MusicSortField.ARTIST -> uiState.albums.sortedBy { it.artist.lowercase() }
                MusicSortField.YEAR -> uiState.albums.sortedBy { it.year ?: 0 }
                MusicSortField.TRACK_COUNT -> uiState.albums.sortedBy { it.songCount }
                MusicSortField.DURATION -> uiState.albums.sortedBy { it.durationSeconds }
                else -> uiState.albums.sortedBy { it.title.lowercase() }
              }
              if (uiState.sortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
            }

            if (uiState.viewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedAlbums, key = { it.id }) { album ->
                  SharedMusicGridCard(
                    title = album.title,
                    subtitle = album.artist,
                    thirdLine = "${album.songCount} songs",
                    artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId),
                    onClick = { onAlbumClick(album) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = navigationBarHeight + 84.dp),
              ) {
                items(sortedAlbums, key = { it.id }) { album ->
                  SharedMusicTrackListItem(
                    title = album.title,
                    subtitle = album.artist,
                    trailingText = "${album.songCount} songs",
                    artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId),
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onAlbumClick(album) },
                  )
                }
              }
            }
          }
        }

        NavidromeMusicTab.ARTISTS -> {
          if (uiState.isLoading && uiState.artists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else {
            val sortedArtists = remember(uiState.artists, uiState.sortField, uiState.sortOrder) {
              val sorted = when (uiState.sortField) {
                MusicSortField.TRACK_COUNT -> uiState.artists.sortedBy { it.albumCount }
                else -> uiState.artists.sortedBy { it.name.lowercase() }
              }
              if (uiState.sortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
            }

            if (uiState.viewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = (gridCoverArtSizeDp * 1.1f).toInt().dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                items(sortedArtists, key = { it.id }) { artist ->
                  SharedMusicGridCard(
                    title = artist.name,
                    subtitle = "${artist.albumCount} albums",
                    artworkUrl = navidromeRepository.getArtistImageUrl(server, artist),
                    isCircular = true,
                    fallbackIcon = Icons.RoundedFilled.Person,
                    onClick = { onArtistClick(artist) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = navigationBarHeight + 84.dp),
              ) {
                items(sortedArtists, key = { it.id }) { artist ->
                  SharedMusicTrackListItem(
                    title = artist.name,
                    subtitle = "${artist.albumCount} albums",
                    artworkUrl = navidromeRepository.getArtistImageUrl(server, artist),
                    isCircular = true,
                    fallbackIcon = Icons.RoundedFilled.Person,
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onArtistClick(artist) },
                  )
                }
              }
            }
          }
        }

        NavidromeMusicTab.PLAYLISTS -> {
          if (uiState.isLoading && uiState.playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          } else {
            val sortedPlaylists = remember(uiState.playlists, uiState.sortField, uiState.sortOrder) {
              val favorites = uiState.playlists.filter { it.id == "virtual_favorites_playlist" || it.id == "favorites" }
              val others = uiState.playlists.filter { it.id != "virtual_favorites_playlist" && it.id != "favorites" }
              val sorted = when (uiState.sortField) {
                MusicSortField.TRACK_COUNT -> others.sortedBy { it.songCount }
                MusicSortField.DURATION -> others.sortedBy { it.durationSeconds }
                else -> others.sortedBy { it.name.lowercase() }
              }
              val result = if (uiState.sortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
              favorites + result
            }

            if (uiState.viewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedPlaylists, key = { it.id }) { playlist ->
                  SharedMusicGridCard(
                    title = playlist.name,
                    subtitle = formatNavidromePlaylistSubtitle(playlist),
                    artworkUrl = navidromeRepository.getCoverArtUrl(server, playlist.coverArtId),
                    fallbackIcon = if (playlist.id == "virtual_favorites_playlist" || playlist.id == "favorites") Icons.RoundedFilled.Favorite else Icons.RoundedFilled.QueueMusic,
                    onClick = { onPlaylistClick(playlist) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = navigationBarHeight + 84.dp),
              ) {
                items(sortedPlaylists, key = { it.id }) { playlist ->
                  SharedMusicTrackListItem(
                    title = playlist.name,
                    subtitle = if (playlist.songCount == 1) "1 track" else "${playlist.songCount} tracks",
                    durationSeconds = playlist.durationSeconds.takeIf { it > 0 }?.toLong(),
                    artworkUrl = navidromeRepository.getCoverArtUrl(server, playlist.coverArtId),
                    fallbackIcon = if (playlist.id == "virtual_favorites_playlist" || playlist.id == "favorites") Icons.RoundedFilled.Favorite else Icons.RoundedFilled.QueueMusic,
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onPlaylistClick(playlist) },
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
private fun NavidromeHomeContent(
  uiState: NavidromeUiState,
  server: NavidromeServer,
  navidromeRepository: NavidromeRepository,
  onTabSelected: (NavidromeMusicTab) -> Unit,
  onSongClick: (NavidromeSong) -> Unit,
  onAlbumClick: (NavidromeAlbum) -> Unit,
  onArtistClick: (NavidromeArtist) -> Unit,
  onPlaylistClick: (NavidromePlaylist) -> Unit,
  navigationBarHeight: Dp,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = 16.dp, bottom = navigationBarHeight + 84.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    // 1. Quick Mix / Jump back in
    if (uiState.jumpBackIn.isNotEmpty()) {
      item(key = "jump_back_in") {
        SharedCompactTrackGridSection(
          title = "Quick mix",
          tracks = uiState.jumpBackIn,
          getId = { it.id },
          getTitle = { it.title },
          getSubtitle = { it.artist },
          getArtworkUrl = { navidromeRepository.getSongCoverArtUrl(server, it, size = 200) },
          onTrackClick = onSongClick,
          onSeeAllClick = { onTabSelected(NavidromeMusicTab.TRACKS) },
        )
      }
    }

    // 2. Playlists row
    if (uiState.playlists.isNotEmpty()) {
      item(key = "playlists_row") {
        SharedMusicCarouselSection(
          title = "Playlists",
          items = uiState.playlists,
          getId = { it.id },
          getTitle = { it.name },
          getSubtitle = { formatNavidromePlaylistSubtitle(it) },
          getArtworkUrl = { navidromeRepository.getCoverArtUrl(server, it.coverArtId) },
          fallbackIcon = Icons.RoundedFilled.QueueMusic,
          getFallbackIcon = { if (it.id == "virtual_favorites_playlist" || it.id == "favorites") Icons.RoundedFilled.Favorite else Icons.RoundedFilled.QueueMusic },
          onClick = onPlaylistClick,
          onSeeAllClick = { onTabSelected(NavidromeMusicTab.PLAYLISTS) },
          cardWidth = 140.dp,
        )
      }
    }

    // 3. Recently added albums
    if (uiState.recentlyAddedAlbums.isNotEmpty()) {
      item(key = "recently_added_albums") {
        SharedMusicCarouselSection(
          title = "Recently added",
          items = uiState.recentlyAddedAlbums,
          getId = { it.id },
          getTitle = { it.title },
          getSubtitle = { it.artist },
          getArtworkUrl = { navidromeRepository.getCoverArtUrl(server, it.coverArtId) },
          onClick = onAlbumClick,
          onSeeAllClick = { onTabSelected(NavidromeMusicTab.ALBUMS) },
          cardWidth = 140.dp,
        )
      }
    }

    // 4. Artists to explore
    if (uiState.artistsToExplore.isNotEmpty()) {
      item(key = "artists_to_explore") {
        SharedMusicCarouselSection(
          title = "Artists to explore",
          items = uiState.artistsToExplore,
          getId = { it.id },
          getTitle = { it.name },
          getSubtitle = { "" },
          getArtworkUrl = { navidromeRepository.getArtistImageUrl(server, it) },
          isCircular = true,
          cardWidth = 130.dp,
          fallbackIcon = Icons.RoundedFilled.Person,
          onClick = onArtistClick,
          onSeeAllClick = { onTabSelected(NavidromeMusicTab.ARTISTS) },
        )
      }
    }
  }
}

private fun formatNavidromePlaylistSubtitle(playlist: NavidromePlaylist): String {
  val countStr = if (playlist.songCount == 1) "1 track" else "${playlist.songCount} tracks"
  val durationStr = playlist.durationSeconds.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it.toLong()) }
  return listOfNotNull(countStr, durationStr).joinToString(" • ")
}
