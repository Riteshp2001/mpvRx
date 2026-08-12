/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.browser.music

import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sqrt
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.PlaylistEntity
import app.gyrolet.mpvrx.database.repository.PlaylistRepository
import app.gyrolet.mpvrx.domain.media.model.Video
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.AppearancePreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.MainScreen
import app.gyrolet.mpvrx.ui.browser.NavigationBarState
import app.gyrolet.mpvrx.ui.browser.cards.PlaylistCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserBottomBar
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.AddToPlaylistDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.DeleteConfirmationDialog
import app.gyrolet.mpvrx.ui.browser.dialogs.MusicSortDialog
import app.gyrolet.mpvrx.ui.browser.folderlist.FolderListScreen
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.selection.rememberSelectionManager
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.theme.AppShapeScale
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private fun MusicSong.toVideo(): Video {
  return Video(
    id = id,
    title = title,
    displayName = title,
    path = path,
    uri = uri,
    duration = durationMs,
    durationFormatted = DateUtils.formatElapsedTime(durationMs / 1000),
    size = 0L,
    sizeFormatted = "",
    dateModified = dateAdded,
    dateAdded = dateAdded,
    mimeType = "audio/*",
    bucketId = "",
    bucketDisplayName = "",
    width = 0,
    height = 0,
    fps = 0f,
    resolution = "",
    isAudio = true
  )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicLibraryContent(
  modifier: Modifier = Modifier,
  musicViewModel: MusicLibraryViewModel = viewModel()
) {
  val context = LocalContext.current
  val backStack = LocalBackStack.current
  val scope = rememberCoroutineScope()

  val selectedTab by musicViewModel.selectedTab.collectAsState()
  val searchQuery by musicViewModel.searchQuery.collectAsState()
  val sortField by musicViewModel.sortField.collectAsState()
  val sortOrder by musicViewModel.sortOrder.collectAsState()
  val viewMode by musicViewModel.viewMode.collectAsState()
  val isLoading by musicViewModel.isLoading.collectAsState()

  val songs by musicViewModel.filteredSongs.collectAsState()
  val albums by musicViewModel.filteredAlbums.collectAsState()
  val artists by musicViewModel.filteredArtists.collectAsState()
  val playlists by musicViewModel.playlists.collectAsState()

  val selectedAlbum by musicViewModel.selectedAlbum.collectAsState()
  val selectedArtist by musicViewModel.selectedArtist.collectAsState()
  val recentlyPlayedFilePath by musicViewModel.recentlyPlayedFilePath.collectAsState()
  val isPlaybackActive by musicViewModel.isPlaybackActive.collectAsState()

  val browserPreferences = koinInject<BrowserPreferences>()
  val foldersPreferences = koinInject<app.gyrolet.mpvrx.preferences.FoldersPreferences>()
  val coverArtSizeDp by browserPreferences.musicCoverArtSize.collectAsState()

  val isRefreshing = remember { mutableStateOf(false) }
  var isSearchActive by remember { mutableStateOf(false) }
  var isSortMenuExpanded by remember { mutableStateOf(false) }
  var showCreatePlaylistDialog by remember { mutableStateOf(false) }
  var selectedPlaylistForDetail by remember { mutableStateOf<PlaylistEntity?>(null) }

  var selectedSongForOptions by remember { mutableStateOf<MusicSong?>(null) }
  var selectedAlbumForOptions by remember { mutableStateOf<MusicAlbum?>(null) }
  var selectedArtistForOptions by remember { mutableStateOf<MusicArtist?>(null) }
  var selectedPlaylistForOptions by remember { mutableStateOf<PlaylistEntity?>(null) }
  var selectedVideosForAddToPlaylist by remember { mutableStateOf<List<Video>?>(null) }
  var showDeletePlaylistDialog by remember { mutableStateOf<PlaylistEntity?>(null) }
  var showDeleteSelectedDialog by remember { mutableStateOf(false) }
  var selectedSongForDelete by remember { mutableStateOf<MusicSong?>(null) }

  val songSelectionManager = rememberSelectionManager(
    items = songs,
    getId = { it.id },
    onDeleteItems = { selectedSongs, _ ->
      musicViewModel.deleteSongs(context, selectedSongs)
    },
    onOperationComplete = { musicViewModel.scanLibrary(context) }
  )

  val albumSelectionManager = rememberSelectionManager(
    items = albums,
    getId = { it.id },
    onDeleteItems = { _, _ -> Pair(0, 0) }
  )

  val artistSelectionManager = rememberSelectionManager(
    items = artists,
    getId = { it.id },
    onDeleteItems = { _, _ -> Pair(0, 0) }
  )

  val playlistSelectionManager = rememberSelectionManager(
    items = playlists,
    getId = { it.id.toLong() },
    onDeleteItems = { selectedPlaylists, _ ->
      selectedPlaylists.forEach { musicViewModel.deletePlaylist(it) }
      Pair(selectedPlaylists.size, 0)
    }
  )

  val activeSelectionManager = when (selectedTab) {
    MusicTab.SONGS -> songSelectionManager
    MusicTab.ALBUMS -> albumSelectionManager
    MusicTab.ARTISTS -> artistSelectionManager
    MusicTab.PLAYLISTS -> playlistSelectionManager
    // Folders tab reuses FolderListScreen, which owns its own selection state.
    MusicTab.FOLDERS -> songSelectionManager
  }

  val visibleTabs by musicViewModel.visibleTabs.collectAsState()

  val appearancePreferences = koinInject<AppearancePreferences>()
  val showQuickPlayFab by appearancePreferences.showQuickPlayFab.collectAsState()
  val quickPlayFabDirect by appearancePreferences.quickPlayFabDirect.collectAsState()
  val isFabVisible = remember { mutableStateOf(true) }
  val isFabExpanded = remember { mutableStateOf(false) }
  val navigationBarHeight = LocalNavigationBarHeight.current

  val initialPageIndex = remember(selectedTab, visibleTabs) {
    visibleTabs.indexOf(selectedTab).coerceAtLeast(0)
  }
  val pagerState = rememberPagerState(initialPage = initialPageIndex) { visibleTabs.size }

  LaunchedEffect(Unit) {
    musicViewModel.scanLibrary(context)
  }

  LaunchedEffect(visibleTabs) {
    if (selectedTab !in visibleTabs) {
      visibleTabs.firstOrNull()?.let { musicViewModel.setTab(it) }
    }
  }

  LaunchedEffect(pagerState.settledPage, visibleTabs) {
    visibleTabs.getOrNull(pagerState.settledPage)?.let { tab ->
      musicViewModel.setTab(tab)
    }
  }

  LaunchedEffect(selectedTab, visibleTabs) {
    val targetIndex = visibleTabs.indexOf(selectedTab)
    if (targetIndex >= 0 && pagerState.currentPage != targetIndex) {
      pagerState.animateScrollToPage(targetIndex)
    }
    songSelectionManager.clear()
    albumSelectionManager.clear()
    artistSelectionManager.clear()
    playlistSelectionManager.clear()
  }

  val navBarState = NavigationBarState
  SideEffect {
    navBarState.updateSelectionState(
      inSelectionMode = activeSelectionManager.isInSelectionMode,
      onlyVideos = false,
    )
  }

  DisposableEffect(Unit) {
    onDispose {
      navBarState.updateSelectionState(inSelectionMode = false)
    }
  }

  BackHandler(enabled = isSearchActive || activeSelectionManager.isInSelectionMode) {
    if (isSearchActive) {
      isSearchActive = false
      musicViewModel.setSearchQuery("")
    } else if (activeSelectionManager.isInSelectionMode) {
      activeSelectionManager.clear()
    }
  }

  // Playlist detail overlay when a playlist is opened
  selectedPlaylistForDetail?.let { playlist ->
    BackHandler { selectedPlaylistForDetail = null }
    PlaylistDetailScreen(playlistId = playlist.id).Content()
    return
  }

  val totalCount = when (selectedTab) {
    MusicTab.SONGS -> songs.size
    MusicTab.ALBUMS -> albums.size
    MusicTab.ARTISTS -> artists.size
    MusicTab.PLAYLISTS -> playlists.size
    MusicTab.FOLDERS -> 0
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(
            if (MaterialTheme.colorScheme.background == Color.Black) Color.Black else MaterialTheme.colorScheme.surfaceContainer
          )
      ) {
        if (isSearchActive) {
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { musicViewModel.setSearchQuery(it) },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = { Text(if (selectedTab == MusicTab.FOLDERS) "Search folders & songs..." else "Search songs, albums, artists...") },
                leadingIcon = {
                  IconButton(onClick = {
                    isSearchActive = false
                    musicViewModel.setSearchQuery("")
                  }) {
                    Icon(imageVector = Icons.RoundedFilled.ArrowBack, contentDescription = "Back")
                  }
                },
                trailingIcon = {
                  if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { musicViewModel.setSearchQuery("") }) {
                      Icon(imageVector = Icons.RoundedFilled.Close, contentDescription = "Clear search")
                    }
                  }
                }
              )
            },
            expanded = false,
            onExpandedChange = { },
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
          ) { }
        } else {
          Box {
            BrowserTopBar(
              title = stringResource(R.string.ui_music),
              isInSelectionMode = activeSelectionManager.isInSelectionMode,
              selectedCount = activeSelectionManager.selectedCount,
              totalCount = totalCount,
              onBackClick = null,
              onCancelSelection = { activeSelectionManager.clear() },
              onSortClick = { isSortMenuExpanded = true },
              onSearchClick = { isSearchActive = true },
              onSettingsClick = {
                backStack.add(PreferencesScreen)
              },
              onSelectAll = { activeSelectionManager.selectAll() },
              onInvertSelection = { activeSelectionManager.invertSelection() },
              onDeselectAll = { activeSelectionManager.clear() },
              onDeleteClick = null,
              onShareClick = if (selectedTab == MusicTab.SONGS) {
                {
                  @Suppress("UNCHECKED_CAST")
                  val selected = activeSelectionManager.getSelectedItems() as List<MusicSong>
                  if (selected.isNotEmpty()) {
                    MediaUtils.shareVideos(context, selected.map { it.toVideo() })
                  }
                }
              } else null,
              onPlayClick = {
                val items = activeSelectionManager.getSelectedItems()
                when (selectedTab) {
                  MusicTab.SONGS -> {
                    @Suppress("UNCHECKED_CAST")
                    musicViewModel.playAllSongs(context, items as List<MusicSong>, shuffle = false)
                  }
                  MusicTab.ALBUMS -> {
                    @Suppress("UNCHECKED_CAST")
                    val selAlbums = items as List<MusicAlbum>
                    val albumSongs = songs.filter { s -> selAlbums.any { a -> s.albumId == a.id || s.album.equals(a.title, ignoreCase = true) } }
                    musicViewModel.playAllSongs(context, albumSongs, shuffle = false)
                  }
                  MusicTab.ARTISTS -> {
                    @Suppress("UNCHECKED_CAST")
                    val selArtists = items as List<MusicArtist>
                    val artistSongs = songs.filter { s -> selArtists.any { ar -> s.artist.equals(ar.name, ignoreCase = true) } }
                    musicViewModel.playAllSongs(context, artistSongs, shuffle = false)
                  }
                  MusicTab.PLAYLISTS -> { }
                  MusicTab.FOLDERS -> { }
                }
              },
              onPinClick = null,
              onBlacklistClick = {
                val selectedItems = activeSelectionManager.getSelectedItems()
                val selectedPaths = selectedItems.mapNotNull { item ->
                  when (item) {
                    is MusicSong -> java.io.File(item.path).parent
                    else -> null
                  }
                }.toSet()
                if (selectedPaths.isNotEmpty()) {
                  foldersPreferences.addBlacklistedFolders(selectedPaths, app.gyrolet.mpvrx.preferences.BlacklistScope.AUDIO_ONLY)
                  activeSelectionManager.clear()
                }
              },
              onRenameClick = null,
              isSingleSelection = activeSelectionManager.isSingleSelection,
              onInfoClick = null,
              onAddToPlaylistClick = null
            )
          }
        }

        MusicSortDialog(
          isOpen = isSortMenuExpanded,
          onDismiss = { isSortMenuExpanded = false },
          sortField = sortField,
          sortOrder = sortOrder,
          viewMode = viewMode,
          onSortFieldChange = { musicViewModel.setSortField(it) },
          onSortOrderChange = { musicViewModel.setSortOrder(it) },
          onViewModeChange = { musicViewModel.setViewMode(it) }
        )

        ScrollableTabRow(
          selectedTabIndex = pagerState.currentPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0)),
          containerColor = Color.Transparent,
          contentColor = MaterialTheme.colorScheme.primary,
          edgePadding = 8.dp,
          divider = {}
        ) {
          visibleTabs.forEachIndexed { index, tab ->
            Tab(
              selected = pagerState.currentPage == index,
              onClick = {
                scope.launch {
                  musicViewModel.setTab(tab)
                  pagerState.animateScrollToPage(index)
                }
              },
              text = {
                Text(
                  text = tab.title,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }
            )
          }
        }
        HorizontalDivider()
      }
    },
    floatingActionButton = {
      val isPlaylistsTab = visibleTabs.getOrNull(pagerState.currentPage) == MusicTab.PLAYLISTS
      val isFoldersTab = visibleTabs.getOrNull(pagerState.currentPage) == MusicTab.FOLDERS
      if (isFoldersTab) {
        // FolderListScreen.MediaStoreFolderListContent renders its own FAB, skip ours.
      } else if (isPlaylistsTab) {
        FloatingActionButtonMenu(
          modifier = Modifier.padding(bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp)),
          expanded = false,
          button = {
            ToggleFloatingActionButton(
              modifier = Modifier.animateFloatingActionButton(
                visible = showQuickPlayFab && !activeSelectionManager.isInSelectionMode && isFabVisible.value && !MainScreen.getPermissionDeniedState(),
                alignment = Alignment.BottomEnd,
              ),
              checked = false,
              onCheckedChange = { showCreatePlaylistDialog = true }
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Add,
                contentDescription = "New Playlist"
              )
            }
          }
        ) { }
      } else if (songs.isNotEmpty()) {
        FloatingActionButtonMenu(
          modifier = Modifier.padding(bottom = (navigationBarHeight - 16.dp).coerceAtLeast(0.dp)),
          expanded = isFabExpanded.value && !quickPlayFabDirect,
          button = {
            TooltipBox(
              positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                if (isFabExpanded.value && !quickPlayFabDirect) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above
              ),
              tooltip = { PlainTooltip { Text(stringResource(R.string.ui_toggle_menu)) } },
              state = rememberTooltipState(),
            ) {
              ToggleFloatingActionButton(
                modifier = Modifier.animateFloatingActionButton(
                  visible = showQuickPlayFab && !activeSelectionManager.isInSelectionMode && isFabVisible.value && !MainScreen.getPermissionDeniedState(),
                  alignment = Alignment.BottomEnd,
                ),
                checked = isFabExpanded.value && !quickPlayFabDirect,
                onCheckedChange = {
                  if (quickPlayFabDirect) {
                    musicViewModel.playAllSongs(context, songs, shuffle = false)
                  } else {
                    isFabExpanded.value = !isFabExpanded.value
                  }
                },
              ) {
                val imageVector by remember {
                  derivedStateOf {
                    if (checkedProgress > 0.5f && !quickPlayFabDirect) Icons.RoundedFilled.Close else Icons.RoundedFilled.PlayArrow
                  }
                }
                Icon(
                  imageVector = imageVector,
                  contentDescription = null,
                  modifier = Modifier.animateIcon({ if (quickPlayFabDirect) 0f else checkedProgress }),
                )
              }
            }
          },
        ) {
          if (!quickPlayFabDirect) {
            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded.value = false
                musicViewModel.playAllSongs(context, songs, shuffle = false)
              },
              icon = { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null) },
              text = { Text("Play All Songs") },
            )
            FloatingActionButtonMenuItem(
              onClick = {
                isFabExpanded.value = false
                musicViewModel.playAllSongs(context, songs, shuffle = true)
              },
              icon = { Icon(Icons.RoundedFilled.Shuffle, contentDescription = null) },
              text = { Text("Shuffle Songs") },
            )
          }
        }
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { musicViewModel.refreshLibrary(context) },
        modifier = Modifier.fillMaxSize()
      ) {
        if (isLoading && songs.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator()
          }
        } else {
          HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
          ) { page ->
            val activeTab = visibleTabs.getOrNull(page) ?: MusicTab.SONGS
            when (activeTab) {
              MusicTab.SONGS -> SongsTabContent(
                songs = songs,
                viewMode = viewMode,
                recentlyPlayedFilePath = recentlyPlayedFilePath,
                isPlaybackActive = isPlaybackActive,
                coverArtSizeDp = coverArtSizeDp,
                onSongClick = { song ->
                  if (songSelectionManager.isInSelectionMode) {
                    songSelectionManager.toggle(song)
                  } else {
                    musicViewModel.playSong(context, song, songs)
                  }
                },
                onSongLongClick = { song ->
                  songSelectionManager.toggle(song)
                },
                selectionManager = songSelectionManager
              )

              MusicTab.ALBUMS -> AlbumsTabContent(
                albums = albums,
                viewMode = viewMode,
                coverArtSizeDp = coverArtSizeDp,
                onAlbumClick = { album ->
                  if (albumSelectionManager.isInSelectionMode) {
                    albumSelectionManager.toggle(album)
                  } else {
                    musicViewModel.selectAlbum(album)
                  }
                },
                onAlbumLongClick = { album ->
                  albumSelectionManager.toggle(album)
                },
                selectionManager = albumSelectionManager
              )

              MusicTab.ARTISTS -> ArtistsTabContent(
                artists = artists,
                viewMode = viewMode,
                coverArtSizeDp = coverArtSizeDp,
                onArtistClick = { artist ->
                  if (artistSelectionManager.isInSelectionMode) {
                    artistSelectionManager.toggle(artist)
                  } else {
                    musicViewModel.selectArtist(artist)
                  }
                },
                onArtistLongClick = { artist ->
                  artistSelectionManager.toggle(artist)
                },
                selectionManager = artistSelectionManager
              )

              MusicTab.PLAYLISTS -> PlaylistsTabContent(
                playlists = playlists,
                songs = songs,
                viewMode = viewMode,
                coverArtSizeDp = coverArtSizeDp.dp,
                onPlaylistClick = { playlist ->
                  if (playlistSelectionManager.isInSelectionMode) {
                    playlistSelectionManager.toggle(playlist)
                  } else {
                    selectedPlaylistForDetail = playlist
                  }
                },
                onPlaylistLongClick = { playlist ->
                  playlistSelectionManager.toggle(playlist)
                },
                selectionManager = playlistSelectionManager,
              )

              // Reuse the exact same folder-browsing screen Home uses for videos,
              // just scoped to audio (audioOnly = true).
              MusicTab.FOLDERS -> FolderListScreen.MediaStoreFolderListContent(
                audioOnly = true,
                embedded = true,
                searchQuery = searchQuery,
              )
            }
          }
        }
      }

        // Album Detail Sheet
        selectedAlbum?.let { album ->
          val albumSongs = remember(songs, album) {
            songs.filter { it.albumId == album.id || it.album.equals(album.title, ignoreCase = true) }
          }
          AlbumDetailSheet(
            album = album,
            songs = albumSongs,
            recentlyPlayedFilePath = recentlyPlayedFilePath,
            isPlaybackActive = isPlaybackActive,
            onDismiss = { musicViewModel.selectAlbum(null) },
            onSongClick = { song -> musicViewModel.playSong(context, song, albumSongs) },
            onSongLongClick = { song -> selectedSongForOptions = song },
            onPlayAlbum = { musicViewModel.playAllSongs(context, albumSongs, shuffle = false) }
          )
        }

        // Artist Detail Sheet
        selectedArtist?.let { artist ->
          val artistSongs = remember(songs, artist) {
            songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
          }
          ArtistDetailSheet(
            artist = artist,
            songs = artistSongs,
            recentlyPlayedFilePath = recentlyPlayedFilePath,
            isPlaybackActive = isPlaybackActive,
            onDismiss = { musicViewModel.selectArtist(null) },
            onSongClick = { song -> musicViewModel.playSong(context, song, artistSongs) },
            onSongLongClick = { song -> selectedSongForOptions = song },
            onPlayArtist = { musicViewModel.playAllSongs(context, artistSongs, shuffle = false) }
          )
        }

        // Create Playlist Dialog
        if (showCreatePlaylistDialog) {
          CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onCreate = { name ->
              musicViewModel.createPlaylist(name)
              showCreatePlaylistDialog = false
            }
          )
        }

        // Song Options Sheet
        selectedSongForOptions?.let { song ->
          ModalBottomSheet(
            onDismissRequest = { selectedSongForOptions = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(56.dp)
                    .clip(AppShapeScale.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                  contentAlignment = Alignment.Center
                ) {
                  LocalAlbumArtImage(uri = song.albumArtUri, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = song.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(text = "${song.artist} • ${song.album}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

              ListItem(
                headlineContent = { Text("Play") },
                leadingContent = { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = song
                  selectedSongForOptions = null
                  musicViewModel.playSong(context, target, songs)
                }
              )
              ListItem(
                headlineContent = { Text("Add to Playlist") },
                leadingContent = { Icon(Icons.RoundedFilled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = song
                  selectedSongForOptions = null
                  selectedVideosForAddToPlaylist = listOf(target.toVideo())
                }
              )
              ListItem(
                headlineContent = { Text("Share") },
                leadingContent = { Icon(Icons.RoundedFilled.Share, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = song
                  selectedSongForOptions = null
                  MediaUtils.shareVideos(context, listOf(target.toVideo()))
                }
              )
              ListItem(
                headlineContent = { Text("Delete Song", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.RoundedFilled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                  val target = song
                  selectedSongForOptions = null
                  selectedSongForDelete = target
                }
              )
            }
          }
        }

        // Single Song Delete Confirmation Dialog
        selectedSongForDelete?.let { song ->
          DeleteConfirmationDialog(
            isOpen = true,
            onDismiss = { selectedSongForDelete = null },
            onConfirm = {
              scope.launch {
                musicViewModel.deleteSongs(context, listOf(song))
                selectedSongForDelete = null
              }
            },
            itemCount = 1,
            itemType = "song",
            itemNames = listOf(song.title)
          )
        }

        // Multi Selection Delete Confirmation Dialog
        if (showDeleteSelectedDialog) {
          val count = activeSelectionManager.selectedCount
          val itemType = if (selectedTab == MusicTab.SONGS) "song" else "playlist"
          val itemNames = when (selectedTab) {
            MusicTab.SONGS -> {
              @Suppress("UNCHECKED_CAST")
              (activeSelectionManager.getSelectedItems() as List<MusicSong>).map { it.title }
            }
            MusicTab.PLAYLISTS -> {
              @Suppress("UNCHECKED_CAST")
              (activeSelectionManager.getSelectedItems() as List<PlaylistEntity>).map { it.name }
            }
            else -> emptyList()
          }
          DeleteConfirmationDialog(
            isOpen = true,
            onDismiss = { showDeleteSelectedDialog = false },
            onConfirm = {
              scope.launch {
                activeSelectionManager.deleteSelected()
                showDeleteSelectedDialog = false
              }
            },
            itemCount = count,
            itemType = itemType,
            itemNames = itemNames
          )
        }

        // Album Options Sheet
        selectedAlbumForOptions?.let { album ->
          val albumSongs = remember(songs, album) {
            songs.filter { it.albumId == album.id || it.album.equals(album.title, ignoreCase = true) }
          }
          ModalBottomSheet(
            onDismissRequest = { selectedAlbumForOptions = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(56.dp)
                    .clip(AppShapeScale.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                  contentAlignment = Alignment.Center
                ) {
                  LocalAlbumArtImage(uri = album.albumArtUri, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = album.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(text = "${album.artist} • ${albumSongs.size} tracks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

              ListItem(
                headlineContent = { Text("View Album Tracks") },
                leadingContent = { Icon(Icons.RoundedFilled.Audiotrack, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = album
                  selectedAlbumForOptions = null
                  musicViewModel.selectAlbum(target)
                }
              )
              ListItem(
                headlineContent = { Text("Play Album") },
                leadingContent = { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = albumSongs
                  selectedAlbumForOptions = null
                  musicViewModel.playAllSongs(context, list, shuffle = false)
                }
              )
              ListItem(
                headlineContent = { Text("Shuffle Album") },
                leadingContent = { Icon(Icons.RoundedFilled.Shuffle, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = albumSongs
                  selectedAlbumForOptions = null
                  musicViewModel.playAllSongs(context, list, shuffle = true)
                }
              )
              ListItem(
                headlineContent = { Text("Add Album to Playlist") },
                leadingContent = { Icon(Icons.RoundedFilled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = albumSongs
                  selectedAlbumForOptions = null
                  selectedVideosForAddToPlaylist = list.map { it.toVideo() }
                }
              )
            }
          }
        }

        // Artist Options Sheet
        selectedArtistForOptions?.let { artist ->
          val artistSongs = remember(songs, artist) {
            songs.filter { it.artist.equals(artist.name, ignoreCase = true) }
          }
          ModalBottomSheet(
            onDismissRequest = { selectedArtistForOptions = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                ArtistAvatarImage(artistName = artist.name, modifier = Modifier.size(56.dp), iconSize = 28.dp)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(text = artist.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(text = "${artistSongs.size} songs", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }

              HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

              ListItem(
                headlineContent = { Text("View Artist Songs") },
                leadingContent = { Icon(Icons.RoundedFilled.Person, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = artist
                  selectedArtistForOptions = null
                  musicViewModel.selectArtist(target)
                }
              )
              ListItem(
                headlineContent = { Text("Play Artist Songs") },
                leadingContent = { Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = artistSongs
                  selectedArtistForOptions = null
                  musicViewModel.playAllSongs(context, list, shuffle = false)
                }
              )
              ListItem(
                headlineContent = { Text("Shuffle Artist Songs") },
                leadingContent = { Icon(Icons.RoundedFilled.Shuffle, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = artistSongs
                  selectedArtistForOptions = null
                  musicViewModel.playAllSongs(context, list, shuffle = true)
                }
              )
              ListItem(
                headlineContent = { Text("Add Artist Songs to Playlist") },
                leadingContent = { Icon(Icons.RoundedFilled.PlaylistAdd, contentDescription = null) },
                modifier = Modifier.clickable {
                  val list = artistSongs
                  selectedArtistForOptions = null
                  selectedVideosForAddToPlaylist = list.map { it.toVideo() }
                }
              )
            }
          }
        }

        // Playlist Options Sheet
        selectedPlaylistForOptions?.let { playlist ->
          ModalBottomSheet(
            onDismissRequest = { selectedPlaylistForOptions = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.RoundedFilled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = playlist.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
              }

              HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

              ListItem(
                headlineContent = { Text("Open Playlist") },
                leadingContent = { Icon(Icons.RoundedFilled.PlaylistPlay, contentDescription = null) },
                modifier = Modifier.clickable {
                  val target = playlist
                  selectedPlaylistForOptions = null
                  selectedPlaylistForDetail = target
                }
              )
              ListItem(
                headlineContent = { Text("Delete Playlist", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.RoundedFilled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable {
                  val target = playlist
                  selectedPlaylistForOptions = null
                  showDeletePlaylistDialog = target
                }
              )
            }
          }
        }

        // Add To Playlist Dialog
        selectedVideosForAddToPlaylist?.let { videos ->
          AddToPlaylistDialog(
            isOpen = true,
            videos = videos,
            onDismiss = { selectedVideosForAddToPlaylist = null },
            onSuccess = { selectedVideosForAddToPlaylist = null }
          )
        }

        // Delete Playlist Dialog
        showDeletePlaylistDialog?.let { playlist ->
          DeleteConfirmationDialog(
            isOpen = true,
            onDismiss = { showDeletePlaylistDialog = null },
            onConfirm = {
              musicViewModel.deletePlaylist(playlist)
              showDeletePlaylistDialog = null
            },
            itemCount = 1,
            itemType = "playlist",
            itemNames = listOf(playlist.name)
          )
        }

        BrowserBottomBar(
          isSelectionMode = activeSelectionManager.isInSelectionMode,
          onCopyClick = { },
          onMoveClick = { },
          onRenameClick = { },
          onDeleteClick = { showDeleteSelectedDialog = true },
          onAddToPlaylistClick = @Suppress("UNCHECKED_CAST") {
            val items = activeSelectionManager.getSelectedItems()
            val videosToAdd = when (selectedTab) {
              MusicTab.SONGS -> (items as List<MusicSong>).map { it.toVideo() }
              MusicTab.ALBUMS -> {
                val selAlbums = items as List<MusicAlbum>
                songs.filter { s -> selAlbums.any { a -> s.albumId == a.id || s.album.equals(a.title, ignoreCase = true) } }.map { it.toVideo() }
              }
              MusicTab.ARTISTS -> {
                val selArtists = items as List<MusicArtist>
                songs.filter { s -> selArtists.any { ar -> s.artist.equals(ar.name, ignoreCase = true) } }.map { it.toVideo() }
              }
              MusicTab.PLAYLISTS -> emptyList()
              MusicTab.FOLDERS -> emptyList()
            }
            if (videosToAdd.isNotEmpty()) {
              selectedVideosForAddToPlaylist = videosToAdd
            }
          },
          showCopy = false,
          showMove = false,
          showRename = false,
          showDelete = selectedTab == MusicTab.SONGS || selectedTab == MusicTab.PLAYLISTS,
          showAddToPlaylist = selectedTab != MusicTab.PLAYLISTS && selectedTab != MusicTab.FOLDERS,
          modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
  }
}

@Composable
private fun LocalAlbumArtImage(
  uri: Uri?,
  contentDescription: String?,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(uri) {
    if (uri != null) {
      bitmap = withContext(Dispatchers.IO) {
        try {
          context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
          }
        } catch (e: Exception) {
          null
        }
      }
    } else {
      bitmap = null
    }
  }

  val loaded = bitmap
  if (loaded != null) {
    Image(
      bitmap = loaded,
      contentDescription = contentDescription,
      contentScale = ContentScale.Crop,
      modifier = modifier
    )
  } else {
    Box(
      modifier = modifier,
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Audiotrack,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

@Composable
private fun ArtistAvatarImage(
  artistName: String,
  modifier: Modifier = Modifier,
  iconSize: Dp? = null,
) {
  val client = koinInject<OkHttpClient>()
  var imageUrl by remember(artistName) { mutableStateOf<String?>(null) }

  LaunchedEffect(artistName) {
    imageUrl = ArtistImageRepository.getArtistImageUrl(client, artistName)
  }

  val url = imageUrl
  BoxWithConstraints(
    modifier = modifier
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center
  ) {
    if (!url.isNullOrBlank()) {
      RemoteImage(
        url = url,
        contentDescription = artistName,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    } else {
      val dynamicIconSize = iconSize ?: (maxWidth * 0.55f)
      Icon(
        imageVector = Icons.RoundedFilled.Person,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(dynamicIconSize)
      )
    }
  }
}

@Composable
private fun SongsTabContent(
  songs: List<MusicSong>,
  viewMode: MusicViewMode,
  recentlyPlayedFilePath: String?,
  isPlaybackActive: Boolean = false,
  coverArtSizeDp: Int = 48,
  onSongClick: (MusicSong) -> Unit,
  onSongLongClick: (MusicSong) -> Unit,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<MusicSong, Long>
) {
  if (songs.isEmpty()) {
    EmptyMusicState(text = "No songs found")
    return
  }

  Column(modifier = Modifier.fillMaxSize()) {
    if (viewMode == MusicViewMode.GRID) {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 145.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        items(songs, key = { it.id }) { song ->
          val isPlaying = isPlaybackActive && recentlyPlayedFilePath != null && song.path == recentlyPlayedFilePath
          SongGridCard(
            song = song,
            isSelected = selectionManager.isSelected(song),
            isPlaying = isPlaying,
            onClick = { onSongClick(song) },
            onLongClick = { onSongLongClick(song) }
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp)
      ) {
        items(songs, key = { it.id }) { song ->
          val isPlaying = isPlaybackActive && recentlyPlayedFilePath != null && song.path == recentlyPlayedFilePath
          SongListItem(
            song = song,
            isSelected = selectionManager.isSelected(song),
            isPlaying = isPlaying,
            coverArtSizeDp = coverArtSizeDp,
            onClick = { onSongClick(song) },
            onLongClick = { onSongLongClick(song) }
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongGridCard(
  song: MusicSong,
  isSelected: Boolean = false,
  isPlaying: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else -> Color.Transparent
      }
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        LocalAlbumArtImage(
          uri = song.albumArtUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(36.dp)
            )
          }
        } else if (isPlaying) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Audiotrack,
              contentDescription = "Playing",
              tint = Color.White,
              modifier = Modifier.size(36.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
      ) {
        Text(
          text = song.title,
          style = MaterialTheme.typography.bodyLarge.copy(
            fontWeight = if (isPlaying) FontWeight.ExtraBold else FontWeight.Bold
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = song.artist,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = DateUtils.formatElapsedTime(song.durationMs / 1000),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongListItem(
  song: MusicSong,
  isSelected: Boolean = false,
  isPlaying: Boolean = false,
  coverArtSizeDp: Int = 48,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 3.dp)
      .clip(AppShapeScale.large)
      .then(
        if (onLongClick != null) {
          Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
          Modifier.clickable(onClick = onClick)
        }
      ),
    shape = AppShapeScale.large,
    color = when {
      isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
      isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
      else -> Color.Transparent
    }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(coverArtSizeDp.dp)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        LocalAlbumArtImage(
          uri = song.albumArtUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(24.dp)
            )
          }
        } else if (isPlaying) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.Audiotrack,
              contentDescription = "Playing",
              tint = Color.White,
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = song.title,
            style = MaterialTheme.typography.bodyLarge.copy(
              fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false)
          )
          if (isPlaying) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.primary
            ) {
              Text(
                text = "PLAYING",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
              )
            }
          }
        }
        Text(
          text = "${song.artist} • ${song.album}",
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Spacer(modifier = Modifier.width(8.dp))

      Text(
        text = DateUtils.formatElapsedTime(song.durationMs / 1000),
        style = MaterialTheme.typography.labelMedium,
        color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun AlbumsTabContent(
  albums: List<MusicAlbum>,
  viewMode: MusicViewMode,
  coverArtSizeDp: Int = 48,
  onAlbumClick: (MusicAlbum) -> Unit,
  onAlbumLongClick: (MusicAlbum) -> Unit,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<MusicAlbum, Long>
) {
  if (albums.isEmpty()) {
    EmptyMusicState(text = "No albums found")
    return
  }

  if (viewMode == MusicViewMode.GRID) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 145.dp),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      items(albums, key = { it.id }) { album ->
        AlbumGridCard(
          album = album,
          isSelected = selectionManager.isSelected(album),
          onClick = { onAlbumClick(album) },
          onLongClick = { onAlbumLongClick(album) }
        )
      }
    }
  } else {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 80.dp)
    ) {
      items(albums, key = { it.id }) { album ->
        AlbumListCard(
          album = album,
          isSelected = selectionManager.isSelected(album),
          coverArtSizeDp = coverArtSizeDp,
          onClick = { onAlbumClick(album) },
          onLongClick = { onAlbumLongClick(album) }
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridCard(
  album: MusicAlbum,
  isSelected: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(1f)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        LocalAlbumArtImage(
          uri = album.albumArtUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(36.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
      ) {
        Text(
          text = album.title,
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = album.artist,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = "${album.songCount} songs",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Start,
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumListCard(
  album: MusicAlbum,
  isSelected: Boolean = false,
  coverArtSizeDp: Int = 48,
  onClick: () -> Unit,
  onLongClick: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 3.dp)
      .clip(AppShapeScale.large)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(coverArtSizeDp.dp)
          .clip(AppShapeScale.medium)
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        LocalAlbumArtImage(
          uri = album.albumArtUri,
          contentDescription = null,
          modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = album.title,
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = album.artist,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Text(
        text = "${album.songCount} songs",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
      )
    }
  }
}

@Composable
private fun ArtistsTabContent(
  artists: List<MusicArtist>,
  viewMode: MusicViewMode,
  coverArtSizeDp: Int = 48,
  onArtistClick: (MusicArtist) -> Unit,
  onArtistLongClick: (MusicArtist) -> Unit,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<MusicArtist, Long>
) {
  if (artists.isEmpty()) {
    EmptyMusicState(text = "No artists found")
    return
  }

  if (viewMode == MusicViewMode.GRID) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 160.dp),
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      items(artists, key = { it.id }) { artist ->
        ArtistGridCard(
          artist = artist,
          isSelected = selectionManager.isSelected(artist),
          onClick = { onArtistClick(artist) },
          onLongClick = { onArtistLongClick(artist) }
        )
      }
    }
  } else {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(bottom = 80.dp)
    ) {
      items(artists, key = { it.id }) { artist ->
        ArtistListCard(
          artist = artist,
          isSelected = selectionManager.isSelected(artist),
          coverArtSizeDp = coverArtSizeDp,
          onClick = { onArtistClick(artist) },
          onLongClick = { onArtistLongClick(artist) }
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistGridCard(
  artist: MusicArtist,
  isSelected: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier = Modifier.size(132.dp),
        contentAlignment = Alignment.Center
      ) {
        ArtistAvatarImage(
          artistName = artist.name,
          modifier = Modifier.fillMaxSize(),
          iconSize = 60.dp
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(36.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = artist.name,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
      )
      Text(
        text = "${artist.songCount} songs",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistListCard(
  artist: MusicArtist,
  isSelected: Boolean = false,
  coverArtSizeDp: Int = 48,
  onClick: () -> Unit,
  onLongClick: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 3.dp)
      .clip(AppShapeScale.large)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    shape = AppShapeScale.large,
    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      val avatarSize = (coverArtSizeDp * 1.3f).toInt().coerceAtLeast(48).dp
      Box(
        modifier = Modifier.size(avatarSize),
        contentAlignment = Alignment.Center
      ) {
        ArtistAvatarImage(
          artistName = artist.name,
          modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.RoundedFilled.CheckCircle,
              contentDescription = "Selected",
              tint = Color.White,
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.width(14.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = artist.name,
          style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = "${artist.songCount} songs • ${artist.albumCount} albums",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
private fun PlaylistArtCollage(
  artUris: List<Uri>,
  modifier: Modifier = Modifier
) {
  val collageUris = remember(artUris) { artUris.take(4) }
  Box(
    modifier = modifier
      .aspectRatio(1f)
      .clip(AppShapeScale.medium)
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center
  ) {
    when (collageUris.size) {
      0 -> {
        Icon(
          imageVector = Icons.RoundedFilled.QueueMusic,
          contentDescription = "Playlist",
          modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
      1 -> {
        LocalAlbumArtImage(
          uri = collageUris[0],
          contentDescription = null,
          modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clip(CircleShape)
        )
      }
      2 -> {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          LocalAlbumArtImage(
            uri = collageUris[0],
            contentDescription = null,
            modifier = Modifier
              .weight(1f)
              .aspectRatio(1f)
              .clip(CircleShape)
          )
          LocalAlbumArtImage(
            uri = collageUris[1],
            contentDescription = null,
            modifier = Modifier
              .weight(1f)
              .aspectRatio(1f)
              .clip(CircleShape)
          )
        }
      }
      3 -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
          contentAlignment = Alignment.Center
        ) {
          Layout(
            content = {
              collageUris.forEach { uri ->
                LocalAlbumArtImage(
                  uri = uri,
                  contentDescription = null,
                  modifier = Modifier.clip(CircleShape)
                )
              }
            },
            modifier = Modifier.fillMaxSize()
          ) { measurables, constraints ->
            val separation = 2.dp.toPx()
            val itemSize = floor((constraints.maxWidth * 2f / (2f + sqrt(3f))) - separation).toInt()

            val placeables = measurables.map {
              it.measure(
                constraints.copy(
                  minWidth = itemSize, maxWidth = itemSize,
                  minHeight = itemSize, maxHeight = itemSize
                )
              )
            }

            val L = itemSize + separation
            val h = L * sqrt(3f) / 2f

            val collageHeight = h + itemSize
            val collageWidth = L + itemSize

            val offsetX = ((constraints.maxWidth - collageWidth) / 2f).toInt()
            val offsetY = ((constraints.maxHeight - collageHeight) / 2f).toInt()

            layout(constraints.maxWidth, constraints.maxHeight) {
              placeables.getOrNull(0)?.placeRelative(
                x = (offsetX + (collageWidth - itemSize) / 2f).toInt(),
                y = offsetY
              )
              placeables.getOrNull(1)?.placeRelative(
                x = offsetX,
                y = (offsetY + h).toInt()
              )
              placeables.getOrNull(2)?.placeRelative(
                x = (offsetX + L).toInt(),
                y = (offsetY + h).toInt()
              )
            }
          }
        }
      }
      else -> {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            LocalAlbumArtImage(
              uri = collageUris.getOrNull(0),
              contentDescription = null,
              modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(CircleShape)
            )
            LocalAlbumArtImage(
              uri = collageUris.getOrNull(1),
              contentDescription = null,
              modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(CircleShape)
            )
          }
          Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            LocalAlbumArtImage(
              uri = collageUris.getOrNull(2),
              contentDescription = null,
              modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(CircleShape)
            )
            LocalAlbumArtImage(
              uri = collageUris.getOrNull(3),
              contentDescription = null,
              modifier = Modifier
                .weight(1f)
                .aspectRatio(1f)
                .clip(CircleShape)
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicPlaylistCard(
  playlist: PlaylistEntity,
  itemCount: Int,
  artUris: List<Uri>,
  isSelected: Boolean,
  isGridMode: Boolean,
  coverArtSizeDp: Dp = 52.dp,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
) {
  if (isGridMode) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
      shape = AppShapeScale.large,
      colors = CardDefaults.cardColors(
        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent
      )
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(8.dp)
      ) {
        Box(modifier = Modifier.fillMaxWidth()) {
          PlaylistArtCollage(
            artUris = artUris,
            modifier = Modifier.fillMaxWidth()
          )
          if (isSelected) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
          Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = if (itemCount == 1) "1 song" else "$itemCount songs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  } else {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .combinedClickable(onClick = onClick, onLongClick = onLongClick),
      color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(modifier = Modifier.size(coverArtSizeDp)) {
          PlaylistArtCollage(
            artUris = artUris,
            modifier = Modifier.fillMaxSize()
          )
          if (isSelected) {
            Box(
              modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
              )
            }
          }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
          )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
          text = if (itemCount == 1) "1 song" else "$itemCount songs",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
  }
}

@Composable
private fun PlaylistsTabContent(
  playlists: List<PlaylistEntity>,
  songs: List<MusicSong>,
  viewMode: MusicViewMode,
  coverArtSizeDp: Dp = 52.dp,
  onPlaylistClick: (PlaylistEntity) -> Unit,
  onPlaylistLongClick: (PlaylistEntity) -> Unit,
  selectionManager: app.gyrolet.mpvrx.ui.browser.selection.SelectionManager<PlaylistEntity, Long>,
) {
  val playlistRepository: PlaylistRepository = koinInject()

  val playlistDetails by produceState<Map<Int, Pair<Int, List<Uri>>>>(initialValue = emptyMap(), playlists, songs) {
    value = withContext(Dispatchers.IO) {
      playlists.associate { playlist ->
        val items = playlistRepository.getPlaylistItems(playlist.id)
        val artUris = items.mapNotNull { item ->
          songs.find { s -> s.path == item.filePath }?.albumArtUri
        }.distinct().take(4)
        playlist.id to Pair(items.size, artUris)
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "${playlists.size} Playlists",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    if (playlists.isEmpty()) {
      EmptyMusicState(text = "No playlists found. Create one!")
    } else {
      if (viewMode == MusicViewMode.GRID) {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 145.dp),
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
          horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          items(playlists, key = { it.id }) { playlist ->
            val details = playlistDetails[playlist.id]
            val itemCount = details?.first ?: 0
            val artUris = details?.second ?: emptyList()
            MusicPlaylistCard(
              playlist = playlist,
              itemCount = itemCount,
              artUris = artUris,
              isSelected = selectionManager.isSelected(playlist),
              isGridMode = true,
              onClick = { onPlaylistClick(playlist) },
              onLongClick = { onPlaylistLongClick(playlist) }
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(bottom = 80.dp)
        ) {
          items(playlists, key = { it.id }) { playlist ->
            val details = playlistDetails[playlist.id]
            val itemCount = details?.first ?: 0
            val artUris = details?.second ?: emptyList()
            MusicPlaylistCard(
              playlist = playlist,
              itemCount = itemCount,
              artUris = artUris,
              isSelected = selectionManager.isSelected(playlist),
              isGridMode = false,
              coverArtSizeDp = coverArtSizeDp,
              onClick = { onPlaylistClick(playlist) },
              onLongClick = { onPlaylistLongClick(playlist) }
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumDetailSheet(
  album: MusicAlbum,
  songs: List<MusicSong>,
  recentlyPlayedFilePath: String?,
  isPlaybackActive: Boolean = false,
  onDismiss: () -> Unit,
  onSongClick: (MusicSong) -> Unit,
  onSongLongClick: (MusicSong) -> Unit,
  onPlayAlbum: () -> Unit
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .size(64.dp)
            .clip(AppShapeScale.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center
        ) {
          LocalAlbumArtImage(
            uri = album.albumArtUri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize()
          )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = album.title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
          )
          Text(
            text = album.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Text(
            text = "${songs.size} Tracks",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
        }

        Button(onClick = onPlayAlbum) {
          Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Play")
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .height(350.dp)
      ) {
        items(songs, key = { it.id }) { song ->
          val isPlaying = isPlaybackActive && recentlyPlayedFilePath != null && song.path == recentlyPlayedFilePath
          SongListItem(
            song = song,
            isPlaying = isPlaying,
            onClick = { onSongClick(song) },
            onLongClick = { onSongLongClick(song) }
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistDetailSheet(
  artist: MusicArtist,
  songs: List<MusicSong>,
  recentlyPlayedFilePath: String?,
  isPlaybackActive: Boolean = false,
  onDismiss: () -> Unit,
  onSongClick: (MusicSong) -> Unit,
  onSongLongClick: (MusicSong) -> Unit,
  onPlayArtist: () -> Unit
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        ArtistAvatarImage(
          artistName = artist.name,
          modifier = Modifier.size(64.dp),
          iconSize = 32.dp
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = artist.name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
          )
          Text(
            text = "${songs.size} Songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Button(onClick = onPlayArtist) {
          Icon(imageVector = Icons.RoundedFilled.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Play All")
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .height(350.dp)
      ) {
        items(songs, key = { it.id }) { song ->
          val isPlaying = isPlaybackActive && recentlyPlayedFilePath != null && song.path == recentlyPlayedFilePath
          SongListItem(
            song = song,
            isPlaying = isPlaying,
            onClick = { onSongClick(song) },
            onLongClick = { onSongLongClick(song) }
          )
        }
      }
    }
  }
}

@Composable
private fun CreatePlaylistDialog(
  onDismiss: () -> Unit,
  onCreate: (String) -> Unit
) {
  var playlistName by remember { mutableStateOf("") }

  androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Create Playlist") },
    text = {
      OutlinedTextField(
        value = playlistName,
        onValueChange = { playlistName = it },
        label = { Text("Playlist Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
      )
    },
    confirmButton = {
      TextButton(
        onClick = { onCreate(playlistName) },
        enabled = playlistName.isNotBlank()
      ) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun EmptyMusicState(text: String) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .padding(32.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Icon(
        imageVector = Icons.RoundedFilled.Audiotrack,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.size(64.dp)
      )
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
    }
  }
}
