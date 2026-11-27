/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.music.MusicSortOrder
import app.gyrolet.mpvrx.ui.browser.music.MusicViewMode
import app.gyrolet.mpvrx.ui.browser.music.SharedCompactTrackGridSection
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicCarouselSection
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicGridCard
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.tv.tvContentFocus
import app.gyrolet.mpvrx.ui.tv.tvFocusMemory
import app.gyrolet.mpvrx.ui.utils.NavigationPager
import org.koin.compose.koinInject

@Composable
fun JellyfinMusicView(
  uiState: JellyfinUiState,
  server: JellyfinServer,
  pagerState: PagerState,
  visibleTabs: List<JellyfinMusicTab>,
  onTabSelected: (JellyfinMusicTab) -> Unit,
  onItemClick: (JellyfinItem) -> Unit,
  onItemLongClick: (JellyfinItem) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val browserPreferences = koinInject<BrowserPreferences>()
  val coverArtSizeDp by browserPreferences.musicCoverArtSize.collectAsState()
  val gridCoverArtSizeDp by browserPreferences.musicGridCoverArtSize.collectAsState()
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentSessionItem = queueState.currentItem

  NavigationPager(
    state = pagerState,
    modifier =
      modifier
        .fillMaxSize()
        .tvContentFocus()
        .background(
          app.gyrolet.mpvrx.ui.theme
            .wallpaperAwareBackgroundColor(),
        ),
    beyondViewportPageCount = 1,
    key = { page -> visibleTabs.getOrNull(page) ?: page },
  ) { page ->
    val tab = visibleTabs.getOrNull(page) ?: JellyfinMusicTab.HOME
    Box(
      modifier = Modifier.fillMaxSize(),
    ) {
      when (tab) {
        JellyfinMusicTab.HOME -> {
          if (uiState.isMusicLoading &&
            uiState.musicJumpBackIn.isEmpty() &&
            uiState.musicPlaylists.isEmpty() &&
            uiState.musicRecentlyPlayedAlbums.isEmpty() &&
            uiState.musicArtistsToExplore.isEmpty()
          ) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          } else {
            JellyfinMusicHomeContent(
              uiState = uiState,
              server = server,
              onTabSelected = onTabSelected,
              onItemClick = onItemClick,
              onItemLongClick = onItemLongClick,
              navigationBarHeight = navigationBarHeight,
            )
          }
        }
        JellyfinMusicTab.TRACKS -> {
          if (uiState.isLoading && uiState.musicTracks.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          } else {
            val sortedTracks =
              remember(uiState.musicTracks, uiState.musicSortField, uiState.musicSortOrder) {
                val sorted =
                  when (uiState.musicSortField) {
                    MusicSortField.TITLE -> uiState.musicTracks.sortedBy { it.name.lowercase() }
                    MusicSortField.ARTIST ->
                      uiState.musicTracks.sortedBy {
                        (it.seriesName ?: it.overview ?: "").lowercase()
                      }
                    MusicSortField.ALBUM ->
                      uiState.musicTracks.sortedBy {
                        (it.seriesName ?: it.overview ?: "").lowercase()
                      }
                    MusicSortField.DURATION -> uiState.musicTracks.sortedBy { it.durationSeconds }
                    MusicSortField.YEAR -> uiState.musicTracks.sortedBy { it.productionYear ?: 0 }
                    else -> uiState.musicTracks.sortedBy { it.name.lowercase() }
                  }
                if (uiState.musicSortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
              }

            if (uiState.musicViewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedTracks, key = { it.id }) { track ->
                  val imageUrl =
                    remember(server.serverUrl, track.id, track.primaryImageTag, server.accessToken) {
                      if (!track.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = track.id,
                          imageTag = track.primaryImageTag,
                          maxWidth = 300,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  val isPlaying =
                    remember(currentSessionItem, track.id) {
                      if (currentSessionItem == null || track.id.isBlank()) {
                        false
                      } else {
                        val orig = currentSessionItem.originalUri
                        val play = currentSessionItem.playableUri
                        orig.contains(track.id, ignoreCase = true) || play.contains(track.id, ignoreCase = true)
                      }
                    }
                  val artistName = track.seriesName ?: track.overview ?: ""
                  SharedMusicGridCard(
                    modifier = Modifier.tvFocusMemory(track.id),
                    title = track.name,
                    subtitle = artistName,
                    thirdLine = track.durationSeconds.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) },
                    artworkUrl = imageUrl,
                    isPlaying = isPlaying,
                    onClick = { onItemClick(track) },
                    onLongClick = { onItemLongClick(track) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
              ) {
                items(sortedTracks, key = { it.id }) { track ->
                  val imageUrl =
                    remember(server.serverUrl, track.id, track.primaryImageTag, server.accessToken) {
                      if (!track.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = track.id,
                          imageTag = track.primaryImageTag,
                          maxWidth = 200,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  val isPlaying =
                    remember(currentSessionItem, track.id) {
                      if (currentSessionItem == null || track.id.isBlank()) {
                        false
                      } else {
                        val orig = currentSessionItem.originalUri
                        val play = currentSessionItem.playableUri
                        orig.contains(track.id, ignoreCase = true) || play.contains(track.id, ignoreCase = true)
                      }
                    }
                  val subtitle = track.seriesName ?: track.overview ?: ""
                  SharedMusicTrackListItem(
                    modifier = Modifier.tvFocusMemory(track.id),
                    title = track.name,
                    subtitle = subtitle,
                    artworkUrl = imageUrl,
                    durationSeconds = track.durationSeconds,
                    isPlaying = isPlaying,
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onItemClick(track) },
                    onLongClick = { onItemLongClick(track) },
                  )
                }
              }
            }
          }
        }
        JellyfinMusicTab.ALBUMS -> {
          if (uiState.isLoading && uiState.musicAlbums.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          } else {
            val sortedAlbums =
              remember(uiState.musicAlbums, uiState.musicSortField, uiState.musicSortOrder) {
                val sorted =
                  when (uiState.musicSortField) {
                    MusicSortField.TITLE -> uiState.musicAlbums.sortedBy { it.name.lowercase() }
                    MusicSortField.ARTIST ->
                      uiState.musicAlbums.sortedBy {
                        (it.seriesName ?: it.overview ?: "").lowercase()
                      }
                    MusicSortField.YEAR -> uiState.musicAlbums.sortedBy { it.productionYear ?: 0 }
                    MusicSortField.TRACK_COUNT -> uiState.musicAlbums.sortedBy { it.childCount ?: 0 }
                    MusicSortField.DURATION -> uiState.musicAlbums.sortedBy { it.durationSeconds }
                    else -> uiState.musicAlbums.sortedBy { it.name.lowercase() }
                  }
                if (uiState.musicSortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
              }

            if (uiState.musicViewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedAlbums, key = { it.id }) { album ->
                  val imageUrl =
                    remember(server.serverUrl, album.id, album.primaryImageTag, server.accessToken) {
                      if (!album.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = album.id,
                          imageTag = album.primaryImageTag,
                          maxWidth = 300,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  val artistName = album.seriesName ?: album.overview ?: ""
                  SharedMusicGridCard(
                    modifier = Modifier.tvFocusMemory(album.id),
                    title = album.name,
                    subtitle = artistName,
                    thirdLine = album.childCount?.let { if (it == 1) "1 song" else "$it songs" },
                    artworkUrl = imageUrl,
                    onClick = { onItemClick(album) },
                    onLongClick = { onItemLongClick(album) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
              ) {
                items(sortedAlbums, key = { it.id }) { album ->
                  val imageUrl =
                    remember(server.serverUrl, album.id, album.primaryImageTag, server.accessToken) {
                      if (!album.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = album.id,
                          imageTag = album.primaryImageTag,
                          maxWidth = 200,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  val artistName = album.seriesName ?: album.overview ?: ""
                  SharedMusicTrackListItem(
                    modifier = Modifier.tvFocusMemory(album.id),
                    title = album.name,
                    subtitle = artistName,
                    trailingText = album.childCount?.let { if (it == 1) "1 song" else "$it songs" },
                    artworkUrl = imageUrl,
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onItemClick(album) },
                    onLongClick = { onItemLongClick(album) },
                  )
                }
              }
            }
          }
        }
        JellyfinMusicTab.ARTISTS -> {
          if (uiState.isLoading && uiState.musicArtists.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          } else {
            val sortedArtists =
              remember(uiState.musicArtists, uiState.musicSortField, uiState.musicSortOrder) {
                val sorted =
                  when (uiState.musicSortField) {
                    MusicSortField.TRACK_COUNT -> uiState.musicArtists.sortedBy { it.childCount ?: 0 }
                    else -> uiState.musicArtists.sortedBy { it.name.lowercase() }
                  }
                if (uiState.musicSortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
              }

            if (uiState.musicViewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = (gridCoverArtSizeDp * 1.1f).toInt().dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                items(sortedArtists, key = { it.id }) { artist ->
                  val imageUrl =
                    remember(server.serverUrl, artist.id, artist.primaryImageTag, server.accessToken) {
                      if (!artist.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = artist.id,
                          imageTag = artist.primaryImageTag,
                          maxWidth = 300,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  SharedMusicGridCard(
                    modifier = Modifier.tvFocusMemory(artist.id),
                    title = artist.name,
                    subtitle = artist.childCount?.let { if (it == 1) "1 album" else "$it albums" } ?: "",
                    artworkUrl = imageUrl,
                    isCircular = true,
                    fallbackIcon = Icons.RoundedFilled.Person,
                    onClick = { onItemClick(artist) },
                    onLongClick = { onItemLongClick(artist) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
              ) {
                items(sortedArtists, key = { it.id }) { artist ->
                  val imageUrl =
                    remember(server.serverUrl, artist.id, artist.primaryImageTag, server.accessToken) {
                      if (!artist.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = artist.id,
                          imageTag = artist.primaryImageTag,
                          maxWidth = 200,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  SharedMusicTrackListItem(
                    modifier = Modifier.tvFocusMemory(artist.id),
                    title = artist.name,
                    subtitle = artist.childCount?.let { if (it == 1) "1 album" else "$it albums" },
                    artworkUrl = imageUrl,
                    isCircular = true,
                    fallbackIcon = Icons.RoundedFilled.Person,
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onItemClick(artist) },
                    onLongClick = { onItemLongClick(artist) },
                  )
                }
              }
            }
          }
        }
        JellyfinMusicTab.PLAYLISTS -> {
          if (uiState.isLoading && uiState.musicPlaylists.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator()
            }
          } else {
            val sortedPlaylists =
              remember(uiState.musicPlaylists, uiState.musicSortField, uiState.musicSortOrder) {
                val favorites =
                  uiState.musicPlaylists.filter {
                    it.id == "virtual_favorites_playlist" ||
                      it.id == "favorites"
                  }
                val others =
                  uiState.musicPlaylists.filter {
                    it.id != "virtual_favorites_playlist" && it.id != "favorites"
                  }
                val sorted =
                  when (uiState.musicSortField) {
                    MusicSortField.TRACK_COUNT -> others.sortedBy { it.childCount ?: 0 }
                    MusicSortField.DURATION -> others.sortedBy { it.durationSeconds }
                    else -> others.sortedBy { it.name.lowercase() }
                  }
                val result = if (uiState.musicSortOrder == MusicSortOrder.DESCENDING) sorted.reversed() else sorted
                favorites + result
              }

            if (uiState.musicViewMode == MusicViewMode.GRID) {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = gridCoverArtSizeDp.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(sortedPlaylists, key = { it.id }) { playlist ->
                  val imageUrl =
                    remember(server.serverUrl, playlist.id, playlist.primaryImageTag, server.accessToken) {
                      if (!playlist.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = playlist.id,
                          imageTag = playlist.primaryImageTag,
                          maxWidth = 300,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  SharedMusicGridCard(
                    modifier = Modifier.tvFocusMemory(playlist.id),
                    title = playlist.name,
                    subtitle = formatJellyfinPlaylistSubtitle(playlist),
                    artworkUrl = imageUrl,
                    fallbackIcon =
                      if (playlist.id == "virtual_favorites_playlist" ||
                        playlist.id == "favorites"
                      ) {
                        Icons.RoundedFilled.Favorite
                      } else {
                        Icons.RoundedFilled.QueueMusic
                      },
                    onClick = { onItemClick(playlist) },
                    onLongClick = { onItemLongClick(playlist) },
                  )
                }
              }
            } else {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                  PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
                    bottom =
                      navigationBarHeight + 84.dp,
                  ),
              ) {
                items(sortedPlaylists, key = { it.id }) { playlist ->
                  val imageUrl =
                    remember(server.serverUrl, playlist.id, playlist.primaryImageTag, server.accessToken) {
                      if (!playlist.primaryImageTag.isNullOrBlank()) {
                        JellyfinClient.getImageUrl(
                          serverUrl = server.serverUrl,
                          itemId = playlist.id,
                          imageTag = playlist.primaryImageTag,
                          maxWidth = 200,
                          token = server.accessToken,
                        )
                      } else {
                        null
                      }
                    }
                  SharedMusicTrackListItem(
                    modifier = Modifier.tvFocusMemory(playlist.id),
                    title = playlist.name,
                    subtitle = playlist.childCount?.let { if (it == 1) "1 track" else "$it tracks" },
                    durationSeconds = playlist.durationSeconds.takeIf { it > 0 },
                    artworkUrl = imageUrl,
                    fallbackIcon =
                      if (playlist.id == "virtual_favorites_playlist" ||
                        playlist.id == "favorites"
                      ) {
                        Icons.RoundedFilled.Favorite
                      } else {
                        Icons.RoundedFilled.QueueMusic
                      },
                    coverArtSizeDp = coverArtSizeDp,
                    onClick = { onItemClick(playlist) },
                    onLongClick = { onItemLongClick(playlist) },
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
private fun JellyfinMusicHomeContent(
  uiState: JellyfinUiState,
  server: JellyfinServer,
  onTabSelected: (JellyfinMusicTab) -> Unit,
  onItemClick: (JellyfinItem) -> Unit,
  onItemLongClick: (JellyfinItem) -> Unit,
  navigationBarHeight: Dp,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = 16.dp, bottom = navigationBarHeight + 84.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    if (uiState.musicJumpBackIn.isNotEmpty()) {
      item(key = "jump_back_in") {
        JellyfinCompactTrackGridSection(
          title = "Jump back in",
          tracks = uiState.musicJumpBackIn,
          server = server,
          onTrackClick = onItemClick,
        )
      }
    }

    if (uiState.musicPlaylists.isNotEmpty()) {
      item(key = "music_playlists_row") {
        JellyfinPlaylistsRowSection(
          title = "Playlists",
          playlists = uiState.musicPlaylists,
          server = server,
          onPlaylistClick = onItemClick,
          onPlaylistLongClick = onItemLongClick,
          onSeeAllClick = { onTabSelected(JellyfinMusicTab.PLAYLISTS) },
        )
      }
    }

    if (uiState.musicRecentlyPlayedAlbums.isNotEmpty()) {
      item(key = "recently_played_albums") {
        JellyfinMusicAlbumRowSection(
          title = "Recently played album",
          albums = uiState.musicRecentlyPlayedAlbums,
          server = server,
          onAlbumClick = onItemClick,
          onAlbumLongClick = onItemLongClick,
        )
      }
    }

    if (uiState.musicArtistsToExplore.isNotEmpty()) {
      item(key = "artists_to_explore") {
        JellyfinArtistsRowSection(
          title = "Artist to explore",
          artists = uiState.musicArtistsToExplore,
          server = server,
          onArtistClick = onItemClick,
          onArtistLongClick = onItemLongClick,
        )
      }
    }
  }
}

@Composable
fun JellyfinCompactTrackGridSection(
  title: String,
  tracks: List<JellyfinItem>,
  server: JellyfinServer,
  onTrackClick: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
  onSeeAllClick: (() -> Unit)? = null,
) {
  SharedCompactTrackGridSection(
    title = title,
    tracks = tracks,
    getId = { it.id },
    getTitle = { it.name },
    getSubtitle = { it.seriesName ?: it.overview ?: "" },
    getArtworkUrl = { track ->
      if (!track.primaryImageTag.isNullOrBlank()) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = track.id,
          imageTag = track.primaryImageTag,
          maxWidth = 200,
          token = server.accessToken,
        )
      } else {
        null
      }
    },
    onTrackClick = onTrackClick,
    onSeeAllClick = onSeeAllClick,
    modifier = modifier,
  )
}

@Composable
fun JellyfinPlaylistsRowSection(
  title: String,
  playlists: List<JellyfinItem>,
  server: JellyfinServer,
  onPlaylistClick: (JellyfinItem) -> Unit,
  onPlaylistLongClick: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
  onSeeAllClick: (() -> Unit)? = null,
) {
  SharedMusicCarouselSection(
    title = title,
    items = playlists,
    getId = { it.id },
    getTitle = { it.name },
    getSubtitle = { formatJellyfinPlaylistSubtitle(it) },
    getArtworkUrl = { playlist ->
      if (!playlist.primaryImageTag.isNullOrBlank()) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = playlist.id,
          imageTag = playlist.primaryImageTag,
          maxWidth = 300,
          token = server.accessToken,
        )
      } else {
        null
      }
    },
    fallbackIcon = Icons.RoundedFilled.QueueMusic,
    getFallbackIcon = {
      if (it.id == "virtual_favorites_playlist" ||
        it.id == "favorites"
      ) {
        Icons.RoundedFilled.Favorite
      } else {
        Icons.RoundedFilled.QueueMusic
      }
    },
    onClick = onPlaylistClick,
    onLongClick = onPlaylistLongClick,
    onSeeAllClick = onSeeAllClick,
    cardWidth = 140.dp,
    modifier = modifier,
  )
}

@Composable
fun JellyfinMusicAlbumRowSection(
  title: String,
  albums: List<JellyfinItem>,
  server: JellyfinServer,
  onAlbumClick: (JellyfinItem) -> Unit,
  onAlbumLongClick: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  SharedMusicCarouselSection(
    title = title,
    items = albums,
    getId = { it.id },
    getTitle = { it.name },
    getSubtitle = { it.seriesName ?: it.overview ?: "" },
    getArtworkUrl = { album ->
      if (!album.primaryImageTag.isNullOrBlank()) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = album.id,
          imageTag = album.primaryImageTag,
          maxWidth = 300,
          token = server.accessToken,
        )
      } else {
        null
      }
    },
    onClick = onAlbumClick,
    onLongClick = onAlbumLongClick,
    cardWidth = 140.dp,
    modifier = modifier,
  )
}

@Composable
fun JellyfinArtistsRowSection(
  title: String,
  artists: List<JellyfinItem>,
  server: JellyfinServer,
  onArtistClick: (JellyfinItem) -> Unit,
  onArtistLongClick: (JellyfinItem) -> Unit,
  modifier: Modifier = Modifier,
) {
  SharedMusicCarouselSection(
    title = title,
    items = artists,
    getId = { it.id },
    getTitle = { it.name },
    getSubtitle = { "" },
    getArtworkUrl = { artist ->
      if (!artist.primaryImageTag.isNullOrBlank()) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = artist.id,
          imageTag = artist.primaryImageTag,
          maxWidth = 300,
          token = server.accessToken,
        )
      } else {
        null
      }
    },
    isCircular = true,
    cardWidth = 130.dp,
    fallbackIcon = Icons.RoundedFilled.Person,
    onClick = onArtistClick,
    onLongClick = onArtistLongClick,
    modifier = modifier,
  )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JellyfinMusicCard(
  item: JellyfinItem,
  server: JellyfinServer,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  onLongClick: (() -> Unit)? = null,
  cardWidth: Dp = 145.dp,
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
  val isArtist = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist"
  val subtitle =
    when {
      isArtist -> ""
      item.type == "Playlist" -> formatJellyfinPlaylistSubtitle(item)
      else -> item.seriesName ?: item.overview ?: ""
    }

  SharedMusicGridCard(
    title = item.name,
    subtitle = subtitle,
    artworkUrl = if (!item.primaryImageTag.isNullOrBlank()) imageUrl else null,
    fallbackIcon =
      when {
        item.id == "virtual_favorites_playlist" || item.id == "favorites" -> Icons.RoundedFilled.Favorite
        item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist" -> Icons.RoundedFilled.Person
        item.type == "Playlist" -> Icons.RoundedFilled.QueueMusic
        else -> Icons.RoundedFilled.Audiotrack
      },
    isCircular = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist",
    cardWidth = cardWidth,
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier.tvFocusMemory(listOf("music", server.id, item.id)),
  )
}

private fun formatJellyfinPlaylistSubtitle(playlist: JellyfinItem): String {
  val countStr = playlist.childCount?.let { if (it == 1) "1 track" else "$it tracks" }
  val durationStr = playlist.durationSeconds.takeIf { it > 0 }?.let { DateUtils.formatElapsedTime(it) }
  val parts = listOfNotNull(countStr, durationStr)
  return if (parts.isNotEmpty()) parts.joinToString(" • ") else (playlist.seriesName ?: playlist.overview ?: "")
}
