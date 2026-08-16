/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.NetworkStreamEntryEntity
import app.gyrolet.mpvrx.database.repository.NetworkStreamEntryRepository
import app.gyrolet.mpvrx.domain.network.ConnectionStatus
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.domain.torrent.normalizeTorrentSource
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.RemoteImage
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.ui.browser.cards.NetworkConnectionCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.AddConnectionSheet
import app.gyrolet.mpvrx.ui.browser.dialogs.EditConnectionSheet
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionInput
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionScreen
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionViewModel
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

private const val VIEWED_TORRENT_FILES_PREFS = "torrent_viewed_files"

private enum class NetworkTab(val titleResId: Int) {
  LOCAL_NETWORK(R.string.ui_local_network),
  SYNC_PLAY(R.string.syncplay_title),
  TORRENT(R.string.ui_torrent_files),
}

@Serializable
object NetworkStreamingScreen : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val viewModel: NetworkStreamingViewModel =
      viewModel(factory = NetworkStreamingViewModel.factory(context.applicationContext as android.app.Application))
    val torrentStreamingEngine = koinInject<TorrentStreamingEngine>()
    val streamEntryRepository = koinInject<NetworkStreamEntryRepository>()
    val wyzieSearchRepository = koinInject<WyzieSearchRepository>()
    val torrentPickerViewModel: TorrentSelectionViewModel =
      viewModel(
        key = "network_torrent_picker",
        factory =
          TorrentSelectionViewModel.factory(
            torrentStreamingEngine = torrentStreamingEngine,
            streamEntryRepository = streamEntryRepository,
            wyzieSearchRepository = wyzieSearchRepository,
          ),
      )
    val torrentPickerState by torrentPickerViewModel.uiState.collectAsState()
    val connections by viewModel.connections.collectAsState()
    val connectionStatuses by viewModel.connectionStatuses.collectAsState()
    val recentLinks by viewModel.recentLinks.collectAsState()
    val torrentGroups by viewModel.torrentGroups.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf<NetworkConnection?>(null) }
    var showTorrentPicker by remember { mutableStateOf(false) }
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(torrentPickerViewModel) {
      torrentPickerViewModel.launches.collect { request ->
        showTorrentPicker = false
        MediaUtils.playFile(
          source = request.source,
          context = context,
          launchSource = "network_torrent",
          title = request.file.name,
          torrentFileIndex = request.file.index,
          torrentPreparationId = request.preparationId,
        )
      }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val filteredConnections =
      remember(connections, searchQuery) {
        if (searchQuery.isBlank()) {
          connections
        } else {
          connections.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
              it.host.contains(searchQuery, ignoreCase = true) ||
              it.protocol.displayName.contains(searchQuery, ignoreCase = true)
          }
        }
      }

    val filteredRecentLinks =
      remember(recentLinks, searchQuery) {
        recentLinks.filter { entry ->
          searchQuery.isBlank() ||
            entry.fileName.contains(searchQuery, ignoreCase = true) ||
            entry.canonicalSourceUri.contains(searchQuery, ignoreCase = true)
        }
      }

    val filteredTorrentGroups =
      remember(torrentGroups, searchQuery) {
        val query = searchQuery.trim()
        torrentGroups.mapNotNull { group ->
          if (query.isEmpty()) return@mapNotNull VisibleTorrentGroup(group, group.files)

          val groupMatches =
            group.title.contains(query, ignoreCase = true) ||
              group.infoHash.orEmpty().contains(query, ignoreCase = true) ||
              group.canonicalSourceUri.contains(query, ignoreCase = true) ||
              group.overview.orEmpty().contains(query, ignoreCase = true) ||
              group.releaseYear.orEmpty().contains(query, ignoreCase = true)

          val matchingFiles =
            group.files.filter { entry ->
              entry.fileName.contains(query, ignoreCase = true) ||
                entry.filePath.orEmpty().contains(query, ignoreCase = true) ||
                entry.fileIndex?.toString() == query
            }
          when {
            groupMatches -> VisibleTorrentGroup(group, group.files)
            matchingFiles.isNotEmpty() -> VisibleTorrentGroup(group, matchingFiles)
            else -> null
          }
        }
      }

    BackHandler(enabled = isSearching) {
      isSearching = false
      searchQuery = ""
    }

    val pagerState = rememberPagerState { NetworkTab.entries.size }

    Scaffold(
      topBar = {
        if (isSearching) {
          SearchBar(
            inputField = {
              SearchBarDefaults.InputField(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { },
                expanded = false,
                onExpandedChange = { },
                placeholder = {
                  Text(stringResource(R.string.settings_search_title))
                },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.RoundedFilled.Search,
                    contentDescription = stringResource(R.string.settings_search_title),
                  )
                },
                trailingIcon = {
                  IconButton(
                    onClick = {
                      isSearching = false
                      searchQuery = ""
                    },
                  ) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = stringResource(R.string.generic_cancel),
                    )
                  }
                },
                modifier = Modifier.focusRequester(focusRequester),
              )
            },
            expanded = false,
            onExpandedChange = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          ) {
            // Empty search bar content
          }
        } else {
          BrowserTopBar(
            title = stringResource(R.string.ui_network),
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = connections.size + recentLinks.size + torrentGroups.size,
            onBackClick = null,
            onCancelSelection = { },
            onSortClick = null,
            onSearchClick = null,
            onSettingsClick = {
              backstack.add(app.gyrolet.mpvrx.ui.preferences.PreferencesScreen)
            },
            onDeleteClick = null,
            onRenameClick = null,
            isSingleSelection = false,
            onInfoClick = null,
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = null,
            onInvertSelection = null,
            onDeselectAll = null,
          )
        }
      },
      floatingActionButton = {
        if (pagerState.currentPage == NetworkTab.LOCAL_NETWORK.ordinal) {
          ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
            text = {
              Text(
                stringResource(R.string.ui_add_connection),
              )
            },
            modifier = Modifier.padding(bottom = navigationBarHeight),
          )
        }
      },
    ) { padding ->
      Column(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
      ) {
        StreamLinkSection(
          recentLinks = filteredRecentLinks,
          onPlayLink = { url ->
            val playableSource = normalizeTorrentSource(url) ?: url.trim()
            if (isTorrentSource(playableSource)) {
              showTorrentPicker = true
              torrentPickerViewModel.open(TorrentSelectionInput(source = playableSource))
            } else {
              viewModel.recordSubmittedLink(playableSource)
              MediaUtils.playFile(playableSource, context, "network_stream")
            }
          },
          onPlayRecent = { entry ->
            viewModel.recordSubmittedLink(entry.canonicalSourceUri)
            MediaUtils.playFile(
              source = entry.canonicalSourceUri,
              context = context,
              launchSource = "network_recent",
              title = entry.fileName,
            )
          },
          onDeleteRecent = viewModel::deleteStreamEntry,
        )

        ScrollableTabRow(
          selectedTabIndex = pagerState.currentPage,
          edgePadding = 16.dp,
          containerColor = MaterialTheme.colorScheme.surface,
          contentColor = MaterialTheme.colorScheme.primary,
          divider = {},
        ) {
          NetworkTab.entries.forEachIndexed { index, tab ->
            Tab(
              selected = pagerState.currentPage == index,
              onClick = {
                coroutineScope.launch { pagerState.animateScrollToPage(index) }
              },
              text = {
                Text(
                  text = stringResource(tab.titleResId),
                  fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal,
                )
              },
            )
          }
        }

        HorizontalPager(
          state = pagerState,
          modifier =
            Modifier
              .fillMaxSize()
              .weight(1f),
          userScrollEnabled = true,
          beyondViewportPageCount = 1,
        ) { page ->
          when (NetworkTab.entries[page]) {
            NetworkTab.LOCAL_NETWORK -> {
              LocalNetworkContent(
                connections = filteredConnections,
                connectionStatuses = connectionStatuses,
                onConnect = { viewModel.connect(it) },
                onDisconnect = { viewModel.disconnect(it) },
                onEdit = { editingConnection = it },
                onDelete = { viewModel.deleteConnection(it) },
                onBrowse = { conn, status ->
                  if (status?.isConnected == true) {
                    backstack.add(
                      NetworkBrowserScreen(
                        connectionId = conn.id,
                        connectionName = conn.name,
                        currentPath = "/",
                      ),
                    )
                  }
                },
                onAutoConnectChange = { conn, autoConnect ->
                  viewModel.updateConnection(conn.copy(autoConnect = autoConnect))
                },
              )
            }
            NetworkTab.SYNC_PLAY -> {
              SyncPlayContent()
            }
            NetworkTab.TORRENT -> {
              TorrentContent(
                torrentGroups = filteredTorrentGroups,
                searchQuery = searchQuery,
                onPlayTorrent = { entry ->
                  MediaUtils.playFile(
                    source = entry.canonicalSourceUri,
                    context = context,
                    launchSource = "network_torrent",
                    title = entry.fileName,
                    torrentFileIndex = entry.fileIndex,
                  )
                },
                onDeleteTorrentFile = viewModel::deleteStreamEntry,
                onDeleteTorrentGroup = { viewModel.deleteTorrentGroup(it.group) },
              )
            }
          }
        }
      }

      AddConnectionSheet(
        isOpen = showAddSheet,
        onDismiss = { showAddSheet = false },
        onSave = { connection ->
          viewModel.addConnection(connection)
          showAddSheet = false
        },
      )

      editingConnection?.let { connection ->
        EditConnectionSheet(
          connection = connection,
          isOpen = true,
          onDismiss = { editingConnection = null },
          onSave = { updatedConnection, clearPassword ->
            viewModel.updateConnection(updatedConnection, clearPassword)
            editingConnection = null
          },
        )
      }

      if (showTorrentPicker) {
        TorrentSelectionScreen(
          state = torrentPickerState,
          onBack = {
            showTorrentPicker = false
            torrentPickerViewModel.cancel()
          },
          onRetry = torrentPickerViewModel::retry,
          onSelect = torrentPickerViewModel::select,
        )
      }
    }
  }
}

@Composable
private fun LocalNetworkContent(
  connections: List<NetworkConnection>,
  connectionStatuses: Map<Long, ConnectionStatus>,
  onConnect: (NetworkConnection) -> Unit,
  onDisconnect: (NetworkConnection) -> Unit,
  onEdit: (NetworkConnection) -> Unit,
  onDelete: (NetworkConnection) -> Unit,
  onBrowse: (NetworkConnection, ConnectionStatus?) -> Unit,
  onAutoConnectChange: (NetworkConnection, Boolean) -> Unit,
) {
  val navBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current.takeIf { it > 0.dp } ?: 88.dp
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = navBarHeight + 16.dp),
    verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    if (connections.isEmpty()) {
      item {
        EmptyStateCard(
          icon = Icons.RoundedFilled.SignalWifiStatusbarConnectedNoInternet4,
          title = stringResource(R.string.ui_no_network_connections),
          subtitle = stringResource(R.string.ui_add_smb_ftp_or_webdav_connections_to_browse_network_files),
        )
      }
    } else {
      items(connections, key = { it.id }) { connection ->
        val status = connectionStatuses[connection.id]
        NetworkConnectionCard(
          connection = connection,
          onConnect = { onConnect(it) },
          onDisconnect = { onDisconnect(it) },
          onEdit = { onEdit(it) },
          onDelete = { onDelete(it) },
          onBrowse = { onBrowse(it, status) },
          onAutoConnectChange = { conn, autoConnect -> onAutoConnectChange(conn, autoConnect) },
          isConnected = status?.isConnected ?: false,
          isConnecting = status?.isConnecting ?: false,
          error = status?.error,
          modifier = Modifier.padding(bottom = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun SyncPlayContent() {
  SyncplayPanel()
}

@Composable
private fun TorrentContent(
  torrentGroups: List<VisibleTorrentGroup>,
  searchQuery: String,
  onPlayTorrent: (NetworkStreamEntryEntity) -> Unit,
  onDeleteTorrentFile: (String) -> Unit,
  onDeleteTorrentGroup: (VisibleTorrentGroup) -> Unit,
) {
  val navBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current.takeIf { it > 0.dp } ?: 88.dp
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = navBarHeight + 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (torrentGroups.isNotEmpty()) {
      items(torrentGroups, key = { "torrent-group:${it.group.id}" }) { result ->
        AnimeTorrentCard(
          group = result.group,
          visibleFiles = result.visibleFiles,
          forceExpanded = searchQuery.isNotBlank(),
          onPlay = { onPlayTorrent(it) },
          onDeleteFile = onDeleteTorrentFile,
          onDeleteGroup = { onDeleteTorrentGroup(result) },
        )
      }
    } else {
      item {
        EmptyStateCard(
          icon = Icons.RoundedFilled.CloudDownload,
          title = stringResource(R.string.ui_no_torrents_title),
          subtitle = stringResource(R.string.ui_no_torrents_description),
        )
      }
    }
  }
}

@Composable
private fun EmptyStateCard(
  icon: AppIcon,
  title: String,
  subtitle: String,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
      ),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(52.dp),
        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
  }
}

private data class VisibleTorrentGroup(
  val group: TorrentStreamGroup,
  val visibleFiles: List<NetworkStreamEntryEntity>,
)

@Composable
private fun AnimeTorrentCard(
  group: TorrentStreamGroup,
  visibleFiles: List<NetworkStreamEntryEntity>,
  forceExpanded: Boolean,
  onPlay: (NetworkStreamEntryEntity) -> Unit,
  onDeleteFile: (String) -> Unit,
  onDeleteGroup: () -> Unit,
) {
  val context = LocalContext.current
  val viewedPreferences =
    remember(context) {
      context.getSharedPreferences(VIEWED_TORRENT_FILES_PREFS, Context.MODE_PRIVATE)
    }
  var viewedFileIndices by
    remember(group.infoHash) {
      mutableStateOf(group.infoHash?.let { loadViewedFileIndices(viewedPreferences, it) } ?: emptySet())
    }

  var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
  var overviewExpanded by rememberSaveable(group.id) { mutableStateOf(false) }
  var overviewOverflow by remember(group.id) { mutableStateOf(false) }
  var isSearchOpen by rememberSaveable(group.id) { mutableStateOf(false) }
  var episodeSearchQuery by rememberSaveable(group.id) { mutableStateOf("") }
  var sortDescending by rememberSaveable(group.id) { mutableStateOf(false) }
  val showFiles = forceExpanded || expanded || (isSearchOpen && episodeSearchQuery.isNotBlank())

  val fileCountLabel =
    pluralStringResource(
      R.plurals.ui_torrent_group_file_count,
      group.files.size,
      group.files.size,
    )

  val mediaTypeLabel =
    when {
      group.mediaType?.contains("anime", ignoreCase = true) == true -> stringResource(R.string.ui_torrent_media_anime)
      group.mediaType?.contains("tv", ignoreCase = true) == true -> stringResource(R.string.ui_torrent_media_series)
      group.mediaType?.contains("movie", ignoreCase = true) == true -> stringResource(R.string.ui_torrent_media_movie)
      group.files.any { it.fileName.contains("anime", ignoreCase = true) } -> stringResource(R.string.ui_torrent_media_anime)
      group.files.size > 1 -> stringResource(R.string.ui_torrent_media_series)
      else -> stringResource(R.string.ui_torrent_media_torrent)
    }

  val firstPlayableFile =
    remember(group.files, viewedFileIndices) {
      group.files.firstOrNull { file -> file.fileIndex !in viewedFileIndices } ?: group.files.firstOrNull()
    }

  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .animateContentSize(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(20.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // 2. Main Card Content (Header & Info)
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(14.dp),
          verticalAlignment = Alignment.Top,
        ) {
          if (group.posterUrl != null) {
            val posterUrl = group.posterUrl
            Box(
              modifier =
                Modifier
                  .width(72.dp)
                  .aspectRatio(2f / 3f)
                  .clip(RoundedCornerShape(12.dp)),
            ) {
              RemoteImage(
                url = posterUrl,
                contentDescription = group.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
              )
            }
          } else {
            Surface(
              modifier = Modifier.size(52.dp),
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.RoundedFilled.Movie,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(28.dp),
                )
              }
            }
          }

          Column(modifier = Modifier.weight(1f)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              ) {
                Text(
                  text = mediaTypeLabel.uppercase(),
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }

              IconButton(
                onClick = onDeleteGroup,
                modifier = Modifier.size(28.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Delete,
                  contentDescription = stringResource(R.string.ui_delete_torrent_group),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = group.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              group.releaseYear?.takeIf(String::isNotBlank)?.let { year ->
                MetaChip(text = year)
              }
              if (group.totalSize > 0L) {
                MetaChip(text = formatTorrentBytes(group.totalSize))
              }
              MetaChip(text = fileCountLabel)
            }
          }
        }

        // Synopsis / Description (if available)
        if (group.overview != null) {
          val overview = group.overview
          Column(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable { overviewExpanded = !overviewExpanded },
          ) {
            Text(
              text = overview,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = if (overviewExpanded) Int.MAX_VALUE else 2,
              overflow = TextOverflow.Ellipsis,
              onTextLayout = { result -> overviewOverflow = result.hasVisualOverflow },
            )
            if (overviewExpanded || overviewOverflow) {
              Text(
                text = stringResource(if (overviewExpanded) R.string.ui_show_less else R.string.ui_show_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
              )
            }
          }
        }

        // Card Action Row
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (firstPlayableFile != null) {
            Button(
              onClick = {
                val fileIdx = firstPlayableFile.fileIndex ?: 0
                val infoHash = group.infoHash
                if (infoHash != null) {
                  val updated = viewedFileIndices + fileIdx
                  viewedFileIndices = updated
                  saveViewedFileIndices(viewedPreferences, infoHash, updated)
                }
                onPlay(firstPlayableFile)
              },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = stringResource(if (viewedFileIndices.isEmpty()) R.string.ui_play_now else R.string.ui_resume_playback),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          Spacer(modifier = Modifier.weight(1f))

          if (visibleFiles.size > 1) {
            IconButton(
              onClick = {
                isSearchOpen = !isSearchOpen
                if (isSearchOpen && !expanded) {
                  expanded = true
                }
              },
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = stringResource(R.string.ui_search_episodes),
                tint =
                  if (isSearchOpen || episodeSearchQuery.isNotBlank()) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }

            IconButton(
              onClick = { sortDescending = !sortDescending },
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.SwapVert,
                contentDescription =
                  stringResource(
                    if (sortDescending) R.string.ui_sort_descending else R.string.ui_sort_ascending,
                  ),
                tint =
                  if (sortDescending) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                  },
              )
            }
          }

          Button(
            onClick = { expanded = !expanded },
            enabled = !forceExpanded,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
          ) {
            Text(
              text = stringResource(R.string.ui_episodes),
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
              imageVector = if (showFiles) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      // 3. Expandable Episodes / Files Section
      AnimatedVisibility(
        visible = showFiles,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        val displayedFiles =
          remember(visibleFiles, episodeSearchQuery, sortDescending) {
            val filtered =
              if (episodeSearchQuery.isBlank()) {
                visibleFiles
              } else {
                val query = episodeSearchQuery.trim()
                visibleFiles.filter { file ->
                  file.fileName.contains(query, ignoreCase = true) ||
                    file.filePath?.contains(query, ignoreCase = true) == true ||
                    (file.fileIndex != null && (file.fileIndex + 1).toString() == query)
                }
              }
            if (sortDescending) filtered.reversed() else filtered
          }

        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainerLow)
              .padding(vertical = 4.dp),
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

          if (isSearchOpen && visibleFiles.size > 1) {
            OutlinedTextField(
              value = episodeSearchQuery,
              onValueChange = { episodeSearchQuery = it },
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 6.dp),
              placeholder = {
                Text(
                  stringResource(R.string.ui_search_episodes),
                  style = MaterialTheme.typography.bodySmall,
                )
              },
              leadingIcon = {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              },
              trailingIcon = {
                if (episodeSearchQuery.isNotBlank()) {
                  IconButton(onClick = { episodeSearchQuery = "" }) {
                    Icon(
                      imageVector = Icons.RoundedFilled.Close,
                      contentDescription = "Clear",
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              textStyle = MaterialTheme.typography.bodySmall,
            )
          }

          if (displayedFiles.isEmpty()) {
            Box(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(R.string.ui_no_matching_episodes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            displayedFiles.forEachIndexed { index, entry ->
              val isViewed = entry.fileIndex in viewedFileIndices
              EpisodeCardRow(
                entry = entry,
                position = index,
                viewed = isViewed,
                onPlay = {
                  val fileIdx = entry.fileIndex ?: 0
                  val infoHash = group.infoHash
                  if (infoHash != null) {
                    val updated = viewedFileIndices + fileIdx
                    viewedFileIndices = updated
                    saveViewedFileIndices(viewedPreferences, infoHash, updated)
                  }
                  onPlay(entry)
                },
                onDelete = { onDeleteFile(entry.stableKey) },
              )
              if (index < displayedFiles.lastIndex) {
                HorizontalDivider(
                  modifier = Modifier.padding(start = 64.dp),
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MetaChip(text: String) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
    )
  }
}

@Composable
private fun EpisodeCardRow(
  entry: NetworkStreamEntryEntity,
  position: Int,
  viewed: Boolean,
  onPlay: () -> Unit,
  onDelete: () -> Unit,
) {
  val epInfo = remember(entry.fileName, entry.filePath) { parseEpisodeDetails(entry.fileName, entry.filePath) }

  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onPlay)
        .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Episode Number Badge
    Surface(
      modifier = Modifier.size(width = 44.dp, height = 36.dp),
      shape = RoundedCornerShape(8.dp),
      color = if (viewed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = if (viewed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = epInfo.badge.ifBlank { (position + 1).toString().padStart(2, '0') },
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
        )
      }
    }

    // Episode Title & Tags
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = epInfo.cleanTitle,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        epInfo.quality?.let { q ->
          Text(
            text = q,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
          )
        }
        epInfo.format?.let { f ->
          Text(
            text = f,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (entry.fileSize > 0L) {
          Text(
            text = "•  ${formatTorrentBytes(entry.fileSize)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    if (viewed) {
      Icon(
        imageVector = Icons.RoundedFilled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }

    IconButton(
      onClick = onDelete,
      modifier = Modifier.size(36.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Delete,
        contentDescription = stringResource(R.string.delete),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.size(18.dp),
      )
    }

    IconButton(
      onClick = onPlay,
      modifier = Modifier.size(36.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.ui_play),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
    }
  }
}

@Composable
private fun StreamLinkSection(
  recentLinks: List<NetworkStreamEntryEntity>,
  onPlayLink: (String) -> Unit,
  onPlayRecent: (NetworkStreamEntryEntity) -> Unit,
  onDeleteRecent: (String) -> Unit,
) {
  val context = LocalContext.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val playStreamContentDescription = stringResource(R.string.ui_play_stream)
  var linkUrl by rememberSaveable { mutableStateOf("") }

  fun pasteFromClipboard() {
    val clipboardManager =
      context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val clipData = clipboardManager?.primaryClip
    if (clipData != null && clipData.itemCount > 0) {
      val text =
        clipData
          .getItemAt(0)
          .text
          ?.toString()
          ?.trim()
          .orEmpty()
      if (text.isNotBlank()) {
        linkUrl = text
      }
    }
  }

  fun playCurrentLink() {
    val sanitizedUrl = linkUrl.trim()
    if (sanitizedUrl.isBlank()) return

    keyboardController?.hide()
    onPlayLink(sanitizedUrl)
    linkUrl = ""
  }

  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Stream URL Input Box
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors =
        CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
      shape = RoundedCornerShape(16.dp),
    ) {
      Row(
        modifier =
          Modifier
            .fillMaxWidth()
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = linkUrl,
          onValueChange = { linkUrl = it },
          modifier = Modifier.weight(1f),
          placeholder = {
            Text(
              text = stringResource(R.string.ui_enter_stream_url),
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
          },
          leadingIcon = {
            Icon(
              imageVector = Icons.RoundedFilled.Link,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
              modifier = Modifier.size(20.dp),
            )
          },
          trailingIcon = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(onClick = { pasteFromClipboard() }) {
                Icon(
                  imageVector = Icons.RoundedFilled.ContentPaste,
                  contentDescription = stringResource(R.string.ui_paste_stream_url),
                  modifier = Modifier.size(18.dp),
                )
              }
              if (linkUrl.isNotBlank()) {
                IconButton(onClick = { linkUrl = "" }) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Close,
                    contentDescription = stringResource(R.string.ui_clear_stream_url),
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(14.dp),
          colors =
            OutlinedTextFieldDefaults.colors(
              focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
              unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
              focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
              unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
          keyboardActions =
            KeyboardActions(
              onGo = { playCurrentLink() },
            ),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Button(
          onClick = { playCurrentLink() },
          enabled = linkUrl.isNotBlank(),
          contentPadding = PaddingValues(12.dp),
          shape = RoundedCornerShape(14.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
            ),
          modifier =
            Modifier.semantics {
              contentDescription = playStreamContentDescription
            },
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }

    // 2. Top 3 Recent Stream Links with Quick Autofill
    val topRecent = remember(recentLinks) { recentLinks.take(3) }
    if (topRecent.isNotEmpty()) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.RoundedFilled.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = stringResource(R.string.ui_recent_streams),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        topRecent.forEach { entry ->
          Surface(
            modifier =
              Modifier
                .fillMaxWidth()
                .clickable {
                  linkUrl = entry.canonicalSourceUri
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
          ) {
            Row(
              modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = entry.fileName,
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.Medium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = entry.canonicalSourceUri,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(
                onClick = { onDeleteRecent(entry.stableKey) },
                modifier = Modifier.size(32.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Delete,
                  contentDescription = stringResource(R.string.delete),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                  modifier = Modifier.size(16.dp),
                )
              }
              IconButton(
                onClick = { onPlayRecent(entry) },
                modifier = Modifier.size(32.dp),
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.PlayArrow,
                  contentDescription = stringResource(R.string.ui_play),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

private fun loadViewedFileIndices(
  preferences: SharedPreferences,
  infoHash: String,
): Set<Int> =
  preferences
    .getStringSet(infoHash, emptySet())
    .orEmpty()
    .mapNotNull(String::toIntOrNull)
    .toSet()

private fun saveViewedFileIndices(
  preferences: SharedPreferences,
  infoHash: String,
  indices: Set<Int>,
) {
  preferences
    .edit()
    .putStringSet(infoHash, indices.map(Int::toString).toSet())
    .apply()
}

private data class ParsedEpisodeInfo(
  val badge: String,
  val season: Int? = null,
  val episode: Int? = null,
  val cleanTitle: String,
  val quality: String? = null,
  val format: String? = null,
)

private val seasonEpisodeRegex = Regex("(?i)(?:^|[^a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?:[^a-z0-9]|$)")
private val epNumRegex = Regex("(?i)(?:^|[^a-z0-9])(?:episode|ep)[ ._-]*(\\d{1,3})(?:[^a-z0-9]|$)")
private val animeDashNumRegex = Regex("(?i)-\\s*(\\d{1,3})(?:v\\d+)?(?:\\s|\\[|\\(|\\.)")
private val qualityRegex = Regex("(?i)\\b(2160p|4K|1080p|720p|480p|HDR|HDRip|WEBRip|BluRay|BRRip|DVDRip)\\b")

private fun parseEpisodeDetails(
  fileName: String,
  filePath: String?,
): ParsedEpisodeInfo {
  val sourceText = fileName.ifBlank { filePath.orEmpty().substringAfterLast('/') }
  val extension = sourceText.substringAfterLast('.', "").uppercase().take(5)
  val quality = qualityRegex.find(sourceText)?.value?.uppercase()

  // 1. Check Season + Episode (S01E02)
  seasonEpisodeRegex.find(sourceText)?.let { match ->
    val season = match.groupValues[1].toIntOrNull() ?: 1
    val episode = match.groupValues[2].toIntOrNull() ?: 1
    return ParsedEpisodeInfo(
      badge = "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}",
      season = season,
      episode = episode,
      cleanTitle = cleanEpisodeTitle(sourceText),
      quality = quality,
      format = extension.takeIf { it.isNotBlank() },
    )
  }

  // 2. Check "Episode 01" / "EP 01"
  epNumRegex.find(sourceText)?.let { match ->
    val episode = match.groupValues[1].toIntOrNull() ?: 1
    return ParsedEpisodeInfo(
      badge = "EP ${episode.toString().padStart(2, '0')}",
      season = null,
      episode = episode,
      cleanTitle = cleanEpisodeTitle(sourceText),
      quality = quality,
      format = extension.takeIf { it.isNotBlank() },
    )
  }

  // 3. Check Anime Dash numbering: " - 01 "
  animeDashNumRegex.find(sourceText)?.let { match ->
    val episode = match.groupValues[1].toIntOrNull() ?: 1
    return ParsedEpisodeInfo(
      badge = "EP ${episode.toString().padStart(2, '0')}",
      season = null,
      episode = episode,
      cleanTitle = cleanEpisodeTitle(sourceText),
      quality = quality,
      format = extension.takeIf { it.isNotBlank() },
    )
  }

  return ParsedEpisodeInfo(
    badge = extension.ifBlank { "PLAY" },
    season = null,
    episode = null,
    cleanTitle = cleanEpisodeTitle(sourceText),
    quality = quality,
    format = extension.takeIf { it.isNotBlank() },
  )
}

private fun cleanEpisodeTitle(name: String): String =
  name
    .substringBeforeLast('.')
    .replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
    .replace(Regex("\\[[^\\]]+\\]"), "")
    .replace(Regex("\\([^\\)]+\\)"), "")
    .replace(Regex("[._]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim(' ', '-', '_')
    .ifBlank { name }
