/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
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
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.torrent.TorrentStreamingEngine
import app.gyrolet.mpvrx.domain.torrent.formatTorrentBytes
import app.gyrolet.mpvrx.domain.torrent.isTorrentSource
import app.gyrolet.mpvrx.domain.torrent.normalizeTorrentSource
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.repository.wyzie.WyzieSearchRepository
import app.gyrolet.mpvrx.ui.browser.cards.NetworkConnectionCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.AddConnectionSheet
import app.gyrolet.mpvrx.ui.browser.dialogs.EditConnectionSheet
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionInput
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionScreen
import app.gyrolet.mpvrx.ui.torrent.TorrentSelectionViewModel
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.utils.media.MediaUtils
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

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
    var showTorrentPicker by rememberSaveable { mutableStateOf(false) }
    val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current

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

    val listState = rememberLazyListState()
    val isFabVisible = remember { mutableStateOf(true) }
    app.gyrolet.mpvrx.ui.browser.fab.FabScrollHelper.trackScrollForFabVisibility(
      listState = listState,
      gridState = null,
      isFabVisible = isFabVisible,
      expanded = false,
      onExpandedChange = {},
    )

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
              group.canonicalSourceUri.contains(query, ignoreCase = true)
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
            onSearchClick = { isSearching = true },
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
        val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
        if (isFabVisible.value) {
          ExtendedFloatingActionButton(
            onClick = { showAddSheet = true },
            icon = { Icon(Icons.RoundedFilled.Add, contentDescription = null) },
            text = {
              Text(
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_add_connection),
              )
            },
            modifier = Modifier.padding(bottom = navigationBarHeight),
          )
        }
      },
    ) { padding ->
      LazyColumn(
        state = listState,
        modifier =
          Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding =
          PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = navigationBarHeight,
          ),
      ) {
        item {
          StreamLinkSection(
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
          )
        }

        if (filteredRecentLinks.isNotEmpty()) {
          item {
            StreamEntrySectionHeader(stringResource(R.string.ui_recent_stream_links))
          }
          items(filteredRecentLinks, key = { "recent:${it.stableKey}" }) { entry ->
            StreamEntryCard(
              entry = entry,
              onPlay = {
                viewModel.recordSubmittedLink(entry.canonicalSourceUri)
                MediaUtils.playFile(
                  source = entry.canonicalSourceUri,
                  context = context,
                  launchSource = "network_recent",
                  title = entry.fileName,
                )
              },
              onDelete = { viewModel.deleteStreamEntry(entry.stableKey) },
            )
          }
        }

        item {
          Spacer(modifier = Modifier.height(24.dp))
          var showSyncplaySheet by remember { mutableStateOf(false) }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = stringResource(R.string.syncplay_title),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(vertical = 8.dp),
            )
            Button(onClick = { showSyncplaySheet = true }) {
              Text(stringResource(R.string.syncplay_open))
            }
          }

          if (showSyncplaySheet) {
            SyncplaySheet(onDismiss = { showSyncplaySheet = false })
          }
        }

        item {
          Spacer(modifier = Modifier.height(24.dp))
          Text(
            text =
              androidx.compose.ui.res
                .stringResource(app.gyrolet.mpvrx.R.string.ui_local_network),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
          )
        }

        if (connections.isEmpty()) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors =
                CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.SignalWifiStatusbarConnectedNoInternet4,
                  contentDescription = null,
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                  text =
                    androidx.compose.ui.res
                      .stringResource(app.gyrolet.mpvrx.R.string.ui_no_network_connections),
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_add_smb_ftp_or_webdav_connections_to_browse_network_files,
                    ),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        } else if (
          filteredConnections.isEmpty() &&
          filteredRecentLinks.isEmpty() &&
          filteredTorrentGroups.isEmpty()
        ) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth(),
              colors =
                CardDefaults.cardColors(
                  containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
              Column(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Search,
                  contentDescription = null,
                  modifier = Modifier.size(48.dp),
                  tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                  text = "No matching connections",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = "No network connections match '$searchQuery'",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        } else {
          items(filteredConnections, key = { it.id }) { connection ->
            val status = connectionStatuses[connection.id]
            NetworkConnectionCard(
              connection = connection,
              onConnect = { conn ->
                viewModel.connect(conn)
              },
              onDisconnect = { conn -> viewModel.disconnect(conn) },
              onEdit = { conn -> editingConnection = conn },
              onDelete = { conn -> viewModel.deleteConnection(conn) },
              onBrowse = { conn ->
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
              isConnected = status?.isConnected ?: false,
              isConnecting = status?.isConnecting ?: false,
              error = status?.error,
              modifier = Modifier.padding(bottom = 16.dp),
            )
          }
        }

        if (filteredTorrentGroups.isNotEmpty()) {
          item {
            StreamEntrySectionHeader(stringResource(R.string.ui_torrent_files))
          }
          items(filteredTorrentGroups, key = { "torrent-group:${it.group.id}" }) { result ->
            TorrentGroupCard(
              group = result.group,
              visibleFiles = result.visibleFiles,
              forceExpanded = searchQuery.isNotBlank(),
              onPlay = { entry ->
                MediaUtils.playFile(
                  source = entry.canonicalSourceUri,
                  context = context,
                  launchSource = "network_torrent",
                  title = entry.fileName,
                  torrentFileIndex = entry.fileIndex,
                )
              },
              onDeleteFile = viewModel::deleteStreamEntry,
              onDeleteGroup = { viewModel.deleteTorrentGroup(result.group) },
            )
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

private data class VisibleTorrentGroup(
  val group: TorrentStreamGroup,
  val visibleFiles: List<NetworkStreamEntryEntity>,
)

@Composable
private fun StreamEntrySectionHeader(title: String) {
  Spacer(modifier = Modifier.height(24.dp))
  Text(
    text = title,
    style = MaterialTheme.typography.titleLarge,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(vertical = 8.dp),
  )
}

@Composable
private fun StreamEntryCard(
  entry: NetworkStreamEntryEntity,
  onPlay: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 10.dp)
        .clickable(onClick = onPlay),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(16.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = if (entry.fileIndex == null) Icons.RoundedFilled.Link else Icons.RoundedFilled.CloudDownload,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = entry.fileName,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = entry.filePath ?: entry.canonicalSourceUri,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (entry.fileSize > 0L) {
          Text(
            text = formatTorrentBytes(entry.fileSize),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      IconButton(onClick = onDelete) {
        Icon(
          imageVector = Icons.RoundedFilled.Delete,
          contentDescription = stringResource(R.string.delete),
        )
      }
      Icon(
        imageVector = Icons.RoundedFilled.PlayArrow,
        contentDescription = stringResource(R.string.ui_play),
        modifier = Modifier.padding(end = 12.dp),
      )
    }
  }
}

@Composable
private fun TorrentGroupCard(
  group: TorrentStreamGroup,
  visibleFiles: List<NetworkStreamEntryEntity>,
  forceExpanded: Boolean,
  onPlay: (NetworkStreamEntryEntity) -> Unit,
  onDeleteFile: (String) -> Unit,
  onDeleteGroup: () -> Unit,
) {
  var expanded by rememberSaveable(group.id) { mutableStateOf(false) }
  val showFiles = forceExpanded || expanded
  val expansionDescription =
    stringResource(
      if (showFiles) R.string.ui_collapse_torrent_group else R.string.ui_expand_torrent_group,
    )
  val fileCountLabel =
    pluralStringResource(
      R.plurals.ui_torrent_group_file_count,
      group.files.size,
      group.files.size,
    )

  Card(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(bottom = 12.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clickable(enabled = !forceExpanded) { expanded = !expanded }
          .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.RoundedFilled.Movie,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(28.dp),
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = group.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            buildString {
              append(fileCountLabel)
              if (group.totalSize > 0L) {
                append(" · ")
                append(formatTorrentBytes(group.totalSize))
              }
            },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(onClick = onDeleteGroup) {
        Icon(
          imageVector = Icons.RoundedFilled.Delete,
          contentDescription = stringResource(R.string.ui_delete_torrent_group),
        )
      }
      IconButton(
        onClick = { expanded = !expanded },
        enabled = !forceExpanded,
      ) {
        Icon(
          imageVector = if (showFiles) Icons.RoundedFilled.ExpandLess else Icons.RoundedFilled.ExpandMore,
          contentDescription = expansionDescription,
        )
      }
    }

    if (showFiles) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
      visibleFiles.forEachIndexed { index, entry ->
        TorrentFileRow(
          entry = entry,
          onPlay = { onPlay(entry) },
          onDelete = { onDeleteFile(entry.stableKey) },
        )
        if (index < visibleFiles.lastIndex) {
          HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
          )
        }
      }
    }
  }
}

@Composable
private fun TorrentFileRow(
  entry: NetworkStreamEntryEntity,
  onPlay: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable(onClick = onPlay)
        .padding(start = 16.dp, top = 11.dp, bottom = 11.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      imageVector = Icons.RoundedFilled.CloudDownload,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.fileName,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      entry.filePath?.takeIf { it != entry.fileName }?.let { path ->
        Text(
          text = path,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (entry.fileSize > 0L) {
        Text(
          text = formatTorrentBytes(entry.fileSize),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    IconButton(onClick = onDelete) {
      Icon(
        imageVector = Icons.RoundedFilled.Delete,
        contentDescription = stringResource(R.string.delete),
      )
    }
    Icon(
      imageVector = Icons.RoundedFilled.PlayArrow,
      contentDescription = stringResource(R.string.ui_play),
      modifier = Modifier.padding(end = 12.dp),
    )
  }
}

@Composable
private fun StreamLinkSection(onPlayLink: (String) -> Unit) {
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
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text =
        androidx.compose.ui.res
          .stringResource(app.gyrolet.mpvrx.R.string.ui_stream_link),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(vertical = 8.dp),
    )
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
              text =
                androidx.compose.ui.res
                  .stringResource(app.gyrolet.mpvrx.R.string.ui_enter_stream_url),
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
                  contentDescription =
                    androidx.compose.ui.res.stringResource(
                      app.gyrolet.mpvrx.R.string.ui_paste_stream_url,
                    ),
                  modifier = Modifier.size(18.dp),
                )
              }
              if (linkUrl.isNotBlank()) {
                IconButton(onClick = { linkUrl = "" }) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Close,
                    contentDescription =
                      androidx.compose.ui.res.stringResource(
                        app.gyrolet.mpvrx.R.string.ui_clear_stream_url,
                      ),
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
  }
}
