/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.navidrome

import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import app.gyrolet.mpvrx.ui.tv.TvRefreshContainer
import app.gyrolet.mpvrx.ui.tv.isTelevision
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.navidrome.NavidromeMusicTab
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.repository.JellyfinRepository
import app.gyrolet.mpvrx.repository.NavidromeRepository
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.music.SharedMusicTrackListItem
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.rememberTabNavigation
import app.gyrolet.mpvrx.ui.browser.dialogs.MusicSortDialog
import app.gyrolet.mpvrx.ui.browser.music.MusicSortField
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavidromeContent(
  viewModel: NavidromeViewModel,
  modifier: Modifier = Modifier,
  isMusicOnlyMode: Boolean = true,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val backstack = LocalBackStack.current

  val mediaServerPreferences = koinInject<MediaServerPreferences>()
  val currentMusicSource by mediaServerPreferences.musicSourceProvider.collectAsState()
  val jellyfinRepository = koinInject<JellyfinRepository>()
  val jellyfinServers by jellyfinRepository.allServers.collectAsState(initial = emptyList())
  val navidromeRepository = koinInject<NavidromeRepository>()

  var isSearching by rememberSaveable { mutableStateOf(false) }
  var isAddDialogOpen by remember { mutableStateOf(false) }
  var isSortDialogOpen by rememberSaveable { mutableStateOf(false) }
  val searchFocusRequester = remember { FocusRequester() }

  val musicTabs = remember {
    listOf(
      NavidromeMusicTab.HOME,
      NavidromeMusicTab.TRACKS,
      NavidromeMusicTab.ALBUMS,
      NavidromeMusicTab.ARTISTS,
      NavidromeMusicTab.PLAYLISTS,
    )
  }

  val musicPagerState = rememberPagerState(
    initialPage = 0,
    pageCount = { musicTabs.size },
  )
  val navigateMusicTab = rememberTabNavigation(musicPagerState)

  LaunchedEffect(musicPagerState.settledPage, musicPagerState.isScrollInProgress) {
    if (!musicPagerState.isScrollInProgress) {
      musicTabs.getOrNull(musicPagerState.settledPage)?.let { tab ->
        viewModel.setMusicTab(tab)
      }
    }
  }

  LaunchedEffect(isSearching) {
    if (isSearching) {
      searchFocusRequester.requestFocus()
    }
  }

  // Intercept back button if searching or detail open
  val isBackEnabled = isSearching || uiState.detailAlbum != null || uiState.detailArtist != null || uiState.detailPlaylist != null

  BackHandler(enabled = isBackEnabled) {
    when {
      uiState.detailAlbum != null || uiState.detailArtist != null || uiState.detailPlaylist != null -> {
        viewModel.closeDetail()
      }
      isSearching -> {
        isSearching = false
        viewModel.onSearchQueryChanged("")
      }
    }
  }

  val headerContainerColor =
    if (app.gyrolet.mpvrx.ui.theme.LocalAppWallpaperActive.current) {
      Color.Transparent
    } else if (MaterialTheme.colorScheme.background == Color.Black) {
      Color.Black
    } else {
      MaterialTheme.colorScheme.surfaceContainer
    }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor()),
  ) {
    // Top Bar Container
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(headerContainerColor),
    ) {
      if (isSearching) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          InlineSearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::onSearchQueryChanged,
            onSearch = { },
            placeholder = { Text(stringResource(R.string.settings_search_title)) },
            leadingIcon = {
              IconButton(onClick = {
                isSearching = false
                viewModel.onSearchQueryChanged("")
              }) {
                Icon(
                  Icons.RoundedFilled.ArrowBack,
                  contentDescription = stringResource(R.string.back),
                  tint = MaterialTheme.colorScheme.secondary,
                )
              }
            },
            trailingIcon = {
              if (uiState.searchQuery.isNotEmpty()) {
                IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                  Icon(
                    Icons.RoundedFilled.Close,
                    contentDescription = stringResource(R.string.pref_clear_content_desc),
                    tint = MaterialTheme.colorScheme.secondary,
                  )
                }
              }
            },
            modifier = Modifier.fillMaxWidth(),
            inputFieldModifier = Modifier.focusRequester(searchFocusRequester),
          )
        }
      } else {
        BrowserTopBar(
          title = if (isMusicOnlyMode) stringResource(R.string.ui_music) else (uiState.activeServer?.name ?: stringResource(R.string.pref_navidrome_title)),
          isInSelectionMode = false,
          selectedCount = 0,
          totalCount = 0,
          onCancelSelection = { },
          onSortClick = if (uiState.activeTab != NavidromeMusicTab.HOME) {
            { isSortDialogOpen = true }
          } else null,
          onSearchClick = { isSearching = true },
          onSettingsClick = {
            backstack.navigateTo(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
          },
          titleTrailing = if (isMusicOnlyMode) {
            { app.gyrolet.mpvrx.ui.browser.music.MusicSourceDropdown() }
          } else null,
        )
      }

      if (!isSearching) {
        val selectedTabIndex = musicPagerState.currentPage.coerceIn(0, (musicTabs.size - 1).coerceAtLeast(0))
        PrimaryScrollableTabRow(
          selectedTabIndex = selectedTabIndex,
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.primary,
          edgePadding = 8.dp,
          divider = {},
        ) {
          musicTabs.forEachIndexed { index, tab ->
            val selected = selectedTabIndex == index
            Tab(
              selected = selected,
              onClick = { navigateMusicTab(index) },
              text = {
                Text(
                  text = tab.title,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis,
                )
              },
            )
          }
        }
        HorizontalDivider()
      }
    }

    // Body
    val navigationBarHeight = LocalNavigationBarHeight.current
    val server = uiState.activeServer

    if (server == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
              androidx.compose.material3.Icon(
                painter = painterResource(R.drawable.ic_navidrome),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
          Text(
            text = stringResource(R.string.pref_navidrome_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
          )
          Text(
            text = stringResource(R.string.pref_navidrome_no_server),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
          )
          FilledTonalButton(onClick = { isAddDialogOpen = true }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.generic_configure))
          }
        }
      }
    } else if (isSearching && uiState.searchResult != null) {
      // Search Results
      val result = uiState.searchResult!!
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = navigationBarHeight + 84.dp),
        verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
      ) {
        if (result.songs.isNotEmpty()) {
          item {
            Text(
              text = "Songs (${result.songs.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          itemsIndexed(result.songs, key = { _, song -> "song_${song.id}" }) { index, song ->
            SharedMusicTrackListItem(
              title = song.title,
              subtitle = song.artist,
              durationSeconds = song.durationSeconds.toLong(),
              artworkUrl = navidromeRepository.getSongCoverArtUrl(server, song),
              onClick = { viewModel.playSong(context, song) },
              modifier = Modifier.padding(horizontal = 16.dp),
              shape = rememberConnectedShape(index, result.songs.size, ConnectedLayout.Vertical),
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
          }
        }

        if (result.albums.isNotEmpty()) {
          item {
            Text(
              text = "Albums (${result.albums.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          itemsIndexed(result.albums, key = { _, album -> "album_${album.id}" }) { index, album ->
            SharedMusicTrackListItem(
              title = album.title,
              subtitle = album.artist,
              durationSeconds = null,
              artworkUrl = navidromeRepository.getCoverArtUrl(server, album.coverArtId),
              onClick = { viewModel.openAlbumDetail(album) },
              modifier = Modifier.padding(horizontal = 16.dp),
              shape = rememberConnectedShape(index, result.albums.size, ConnectedLayout.Vertical),
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
          }
        }

        if (result.artists.isNotEmpty()) {
          item {
            Text(
              text = "Artists (${result.artists.size})",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
          itemsIndexed(result.artists, key = { _, artist -> "artist_${artist.id}" }) { index, artist ->
            SharedMusicTrackListItem(
              title = artist.name,
              subtitle = "${artist.albumCount} albums",
              durationSeconds = null,
              artworkUrl = navidromeRepository.getArtistImageUrl(server, artist),
              onClick = { viewModel.openArtistDetail(artist) },
              modifier = Modifier.padding(horizontal = 16.dp),
              shape = rememberConnectedShape(index, result.artists.size, ConnectedLayout.Vertical),
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
          }
        }
      }
    } else {
      val musicView: @Composable () -> Unit = {
        NavidromeMusicView(
          uiState = uiState,
          server = server,
          pagerState = musicPagerState,
          visibleTabs = musicTabs,
          onTabSelected = { tab ->
            navigateMusicTab(musicTabs.indexOf(tab))
          },
          onSongClick = { song -> viewModel.playSong(context, song) },
          onAlbumClick = { album -> viewModel.openAlbumDetail(album) },
          onArtistClick = { artist -> viewModel.openArtistDetail(artist) },
          onPlaylistClick = { playlist -> viewModel.openPlaylistDetail(playlist) },
          onToggleFavorite = { song -> viewModel.toggleFavorite(song) },
          navigationBarHeight = navigationBarHeight,
        )
      }
      if (isTelevision()) {
        TvRefreshContainer(
          isRefreshing = uiState.isLoading,
          onRefresh = { viewModel.refresh() },
          modifier = Modifier.fillMaxSize(),
        ) {
          musicView()
        }
      } else {
        PullToRefreshBox(
          isRefreshing = uiState.isLoading,
          onRefresh = { viewModel.refresh() },
          modifier = Modifier.fillMaxSize(),
        ) {
          musicView()
        }
      }
    }

    // Detail sheet
    if (server != null) {
      NavidromeDetailSheet(
        uiState = uiState,
        server = server,
        viewModel = viewModel,
        onDismiss = { viewModel.closeDetail() },
      )
    }

    // Sort & View dialog
    val availableFields = remember(uiState.activeTab) {
      when (uiState.activeTab) {
        NavidromeMusicTab.TRACKS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.ALBUM,
          MusicSortField.DURATION,
          MusicSortField.YEAR,
        )
        NavidromeMusicTab.ALBUMS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.ARTIST,
          MusicSortField.YEAR,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        NavidromeMusicTab.ARTISTS -> listOf(
          MusicSortField.ARTIST,
          MusicSortField.TRACK_COUNT,
        )
        NavidromeMusicTab.PLAYLISTS -> listOf(
          MusicSortField.TITLE,
          MusicSortField.TRACK_COUNT,
          MusicSortField.DURATION,
        )
        else -> emptyList()
      }
    }

    MusicSortDialog(
      isOpen = isSortDialogOpen,
      onDismiss = { isSortDialogOpen = false },
      sortField = uiState.sortField,
      sortOrder = uiState.sortOrder,
      viewMode = uiState.viewMode,
      onSortFieldChange = { viewModel.setSortField(it) },
      onSortOrderChange = { viewModel.setSortOrder(it) },
      onViewModeChange = { viewModel.setViewMode(it) },
      availableFields = availableFields,
      trackCountLabel = if (uiState.activeTab == NavidromeMusicTab.ARTISTS) {
        stringResource(R.string.ui_albums)
      } else MusicSortField.TRACK_COUNT.displayName,
    )

    // Add server dialog
    AddNavidromeServerDialog(
      isOpen = isAddDialogOpen,
      isLoading = uiState.isConnectingServer,
      errorMessage = uiState.connectServerError,
      onDismiss = { isAddDialogOpen = false },
      onConnect = { url, name, authMode, username, password, token ->
        viewModel.connectServer(
          serverUrl = url,
          serverName = name,
          authMode = authMode,
          username = username,
          password = password,
          token = token,
          onSuccess = { isAddDialogOpen = false },
        )
      },
    )
  }
}
