/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.jellyfin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gyrolet.mpvrx.data.jellyfin.JellyfinClient
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinItem
import app.gyrolet.mpvrx.domain.jellyfin.JellyfinServer
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.tv.tvFocusable
import app.gyrolet.mpvrx.ui.tv.LocalTvUiEnvironment

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicGridCard
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.player.PlaybackSession

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
  HorizontalPager(
    state = pagerState,
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
    beyondViewportPageCount = 1,
    userScrollEnabled = !LocalTvUiEnvironment.current.isTelevision,
    key = { page -> visibleTabs.getOrNull(page) ?: page },
  ) { page ->
    val tab = visibleTabs.getOrNull(page) ?: JellyfinMusicTab.HOME
    Box(
      modifier = Modifier.fillMaxSize(),
    ) {
      when (tab) {
        JellyfinMusicTab.HOME -> {
          if (uiState.isMusicLoading && uiState.musicJumpBackIn.isEmpty() && uiState.musicPlaylists.isEmpty() && uiState.musicRecentlyPlayedAlbums.isEmpty() && uiState.musicArtistsToExplore.isEmpty()) {
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
            JellyfinTracksList(
              tracks = uiState.musicTracks,
              server = server,
              onTrackClick = onItemClick,
              onTrackLongClick = onItemLongClick,
              navigationBarHeight = navigationBarHeight,
            )
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
            JellyfinMusicGrid(
              items = uiState.musicAlbums,
              server = server,
              onItemClick = onItemClick,
              onItemLongClick = onItemLongClick,
              navigationBarHeight = navigationBarHeight,
            )
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
            JellyfinArtistsGrid(
              artists = uiState.musicArtists,
              server = server,
              onItemClick = onItemClick,
              onItemLongClick = onItemLongClick,
              navigationBarHeight = navigationBarHeight,
            )
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
            JellyfinMusicGrid(
              items = uiState.musicPlaylists,
              server = server,
              onItemClick = onItemClick,
              onItemLongClick = onItemLongClick,
              navigationBarHeight = navigationBarHeight,
            )
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
  val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
  val rowsCount = when {
    tracks.size >= 6 -> 3
    tracks.size >= 3 -> 2
    else -> 1
  }
  val rowHeight = if (isLandscape) 70 else 64
  val itemWidth = if (isLandscape) 320.dp else 280.dp
  val gridHeight = (rowsCount * rowHeight + (rowsCount - 1) * 12).dp

  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
      )
      if (onSeeAllClick != null) {
        Text(
          text = "See all",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .tvFocusable(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAllClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }
    LazyHorizontalGrid(
      rows = GridCells.Fixed(rowsCount),
      modifier = Modifier
        .height(gridHeight)
        .padding(horizontal = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      items(tracks, key = { it.id }) { track ->
        val imageUrl = remember(server.serverUrl, track.id, track.primaryImageTag, server.accessToken) {
          JellyfinClient.getImageUrl(
            serverUrl = server.serverUrl,
            itemId = track.id,
            imageTag = track.primaryImageTag,
            maxWidth = 200,
            token = server.accessToken,
          )
        }

        Row(
          modifier = Modifier
            .width(itemWidth)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onTrackClick(track) },
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier = Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
          ) {
            if (!track.primaryImageTag.isNullOrBlank()) {
              RemoteImage(
                url = imageUrl,
                contentDescription = track.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
              )
            } else {
              Icon(
                imageVector = Icons.RoundedFilled.Audiotrack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
              )
            }
          }
          Spacer(Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = track.name,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            val subtitle = track.seriesName ?: track.overview ?: ""
            if (subtitle.isNotBlank()) {
              Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
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
  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
      )
      if (onSeeAllClick != null) {
        Text(
          text = "See all",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier
            .tvFocusable(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onSeeAllClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }
    }
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(playlists, key = { it.id }) { playlist ->
        JellyfinMusicCard(
          item = playlist,
          server = server,
          onClick = { onPlaylistClick(playlist) },
          onLongClick = { onPlaylistLongClick(playlist) },
          cardWidth = 140.dp,
        )
      }
    }
  }
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
  Column(modifier = modifier) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
    )
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(albums, key = { it.id }) { album ->
        JellyfinMusicCard(
          item = album,
          server = server,
          onClick = { onAlbumClick(album) },
          onLongClick = { onAlbumLongClick(album) },
          cardWidth = 140.dp,
        )
      }
    }
  }
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
  Column(modifier = modifier) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.onBackground,
      modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
    )
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
      items(artists, key = { it.id }) { artist ->
        JellyfinMusicCard(
          item = artist,
          server = server,
          onClick = { onArtistClick(artist) },
          onLongClick = { onArtistLongClick(artist) },
          cardWidth = 130.dp,
        )
      }
    }
  }
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
  val subtitle = if (isArtist) "" else (item.seriesName ?: item.overview ?: "")

  SharedMusicGridCard(
    title = item.name,
    subtitle = subtitle,
    artworkUrl = if (!item.primaryImageTag.isNullOrBlank()) imageUrl else null,
    fallbackIcon = when {
      item.id == "virtual_favorites_playlist" || item.id == "favorites" -> Icons.RoundedFilled.Favorite
      item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist" -> Icons.RoundedFilled.Person
      item.type == "Playlist" -> Icons.RoundedFilled.QueueMusic
      else -> Icons.RoundedFilled.Audiotrack
    },
    isCircular = item.type == "MusicArtist" || item.type == "Artist" || item.type == "AlbumArtist",
    cardWidth = cardWidth,
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier,
  )
}

@Composable
fun JellyfinMusicGrid(
  items: List<JellyfinItem>,
  server: JellyfinServer,
  onItemClick: (JellyfinItem) -> Unit,
  onItemLongClick: (JellyfinItem) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 140.dp),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(items, key = { it.id }) { item ->
      JellyfinMusicCard(
        item = item,
        server = server,
        onClick = { onItemClick(item) },
        onLongClick = { onItemLongClick(item) },
      )
    }
  }
}

@Composable
fun JellyfinArtistsGrid(
  artists: List<JellyfinItem>,
  server: JellyfinServer,
  onItemClick: (JellyfinItem) -> Unit,
  onItemLongClick: (JellyfinItem) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 130.dp),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navigationBarHeight + 84.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(artists, key = { it.id }) { artist ->
      JellyfinMusicCard(
        item = artist,
        server = server,
        onClick = { onItemClick(artist) },
        onLongClick = { onItemLongClick(artist) },
        cardWidth = 130.dp,
      )
    }
  }
}

@Composable
fun JellyfinTracksList(
  tracks: List<JellyfinItem>,
  server: JellyfinServer,
  onTrackClick: (JellyfinItem) -> Unit,
  onTrackLongClick: (JellyfinItem) -> Unit,
  navigationBarHeight: Dp,
  modifier: Modifier = Modifier,
) {
  val queueState by PlaybackSession.queue.collectAsStateWithLifecycle()
  val currentSessionItem = queueState.currentItem

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(bottom = navigationBarHeight + 84.dp),
  ) {
    items(tracks, key = { it.id }) { track ->
      val imageUrl = remember(server.serverUrl, track.id, track.primaryImageTag, server.accessToken) {
        JellyfinClient.getImageUrl(
          serverUrl = server.serverUrl,
          itemId = track.id,
          imageTag = track.primaryImageTag,
          maxWidth = 200,
          token = server.accessToken,
        )
      }

      val isPlaying = remember(currentSessionItem, track.id) {
        if (currentSessionItem == null || track.id.isBlank()) false
        else {
          val orig = currentSessionItem.originalUri
          val play = currentSessionItem.playableUri
          orig.contains(track.id, ignoreCase = true) || play.contains(track.id, ignoreCase = true)
        }
      }
      val subtitle = track.seriesName ?: track.overview ?: ""

      SharedMusicTrackListItem(
        title = track.name,
        subtitle = subtitle,
        artworkUrl = if (!track.primaryImageTag.isNullOrBlank()) imageUrl else null,
        durationSeconds = track.durationSeconds,
        isPlaying = isPlaying,
        onClick = { onTrackClick(track) },
        onLongClick = { onTrackLongClick(track) },
      )
    }
  }
}
