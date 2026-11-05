/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.audiobooks

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import app.gyrolet.mpvrx.presentation.components.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.gyrolet.mpvrx.presentation.components.AppTextField as OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import app.gyrolet.mpvrx.presentation.components.AppDialogTextButton as TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.database.entities.Audiobook
import app.gyrolet.mpvrx.database.entities.AudiobookEntity
import app.gyrolet.mpvrx.domain.audiobook.AudiobookOnlineMetadata
import app.gyrolet.mpvrx.domain.audiobookshelf.AudiobookshelfBook
import app.gyrolet.mpvrx.domain.thumbnail.EmbeddedArtworkResolver
import app.gyrolet.mpvrx.domain.thumbnail.getArtwork
import app.gyrolet.mpvrx.preferences.AudiobookSortType
import app.gyrolet.mpvrx.preferences.AudiobookSourceProvider
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.MediaServerPreferences
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.components.AppPickerSheet
import app.gyrolet.mpvrx.presentation.components.AppComboBoxChip
import app.gyrolet.mpvrx.presentation.components.AppComboBoxOption
import app.gyrolet.mpvrx.presentation.components.PlayerSheetSearchField
import app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight
import app.gyrolet.mpvrx.ui.browser.fab.FabPlacement
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.dialogs.AudiobookSortDialog
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.AppIcon
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.player.AudiobookPlayback
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.preferences.MediaServersPreferencesScreen
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import app.gyrolet.mpvrx.ui.utils.rememberAppHaptics
import app.gyrolet.mpvrx.ui.theme.AppConnectedShapeTokens
import app.gyrolet.mpvrx.ui.theme.ConnectedLayout
import app.gyrolet.mpvrx.ui.theme.rememberConnectedShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudiobookLibraryContent(
  isMusicTabMode: Boolean = false,
) {
  val model: AudiobookLibraryViewModel = viewModel()
  val books by model.library.collectAsStateWithLifecycle()
  val importing by model.progress.collectAsStateWithLifecycle()
  val error by model.error.collectAsStateWithLifecycle()
  val backStack = LocalBackStack.current
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val haptics = rememberAppHaptics()

  val browserPreferences = koinInject<BrowserPreferences>()
  val mediaServerPreferences = koinInject<MediaServerPreferences>()
  val currentSource by mediaServerPreferences.audiobookSourceProvider.collectAsState()

  val absModel: AudiobookshelfViewModel = viewModel(factory = AudiobookshelfViewModel.factory(context.applicationContext as Application))
  val absState by absModel.uiState.collectAsStateWithLifecycle()

  val sortType by browserPreferences.audiobookSortType.collectAsState()
  val sortOrder by browserPreferences.audiobookSortOrder.collectAsState()
  val layoutMode by browserPreferences.audiobookLayoutMode.collectAsState()

  var isSortMenuExpanded by remember { mutableStateOf(false) }
  var query by rememberSaveable { mutableStateOf("") }
  var filter by rememberSaveable { mutableStateOf(0) }
  var search by rememberSaveable { mutableStateOf(false) }
  val searchFocusRequester = remember { FocusRequester() }
  var importMenu by remember { mutableStateOf(false) }
  var detailsId by rememberSaveable { mutableStateOf<Long?>(null) }
  var absDetailsBook by remember { mutableStateOf<AudiobookshelfBook?>(null) }
  var absSearchOnlineBook by remember { mutableStateOf<AudiobookshelfBook?>(null) }
  var removeId by rememberSaveable { mutableStateOf<Long?>(null) }
  var editing by remember { mutableStateOf<AudiobookEntity?>(null) }
  var opening by remember { mutableStateOf(false) }
  var playbackError by remember { mutableStateOf<String?>(null) }

  val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.importFiles(it) }
  val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) model.importFiles(emptyList(), uri)
  }

  val visibleLocalBooks = remember(books, query, filter, sortType, sortOrder) {
    val filtered = books.orEmpty().filter { !it.book.sourceKey.startsWith("abs:") }.filter { book ->
      val matches = when (filter) {
        1 -> book.book.progressMs > 0 && !book.book.finished
        2 -> book.book.finished
        3 -> book.book.progressMs == 0L && !book.book.finished
        else -> true
      }
      matches && listOf(book.book.title, book.book.author, book.book.narrator, book.book.series)
        .any { it.contains(query, ignoreCase = true) }
    }
    val comparator = when (sortType) {
      AudiobookSortType.Title -> compareBy<Audiobook, String>(String.CASE_INSENSITIVE_ORDER) { it.book.title }
      AudiobookSortType.Author -> compareBy<Audiobook, String>(String.CASE_INSENSITIVE_ORDER) { it.book.author }
      AudiobookSortType.Duration -> compareBy { it.durationMs }
      AudiobookSortType.Progress -> compareBy { it.progress }
      AudiobookSortType.LastPlayed -> compareBy { it.book.lastPlayedAt }
      AudiobookSortType.DateAdded -> compareBy { it.book.addedAt }
    }
    if (sortOrder == SortOrder.Descending) {
      filtered.sortedWith(comparator.reversed())
    } else {
      filtered.sortedWith(comparator)
    }
  }

  val visibleAbsBooks = remember(absState.books, query, filter, sortType, sortOrder) {
    val filtered = absState.books.filter { book ->
      val matches = when (filter) {
        1 -> book.progressMs > 0 && !book.isFinished
        2 -> book.isFinished
        3 -> book.progressMs == 0L && !book.isFinished
        else -> true
      }
      matches && listOf(book.title, book.author, book.narrator, book.series, book.description)
        .any { it.contains(query, ignoreCase = true) }
    }
    val comparator = when (sortType) {
      AudiobookSortType.Title -> compareBy<AudiobookshelfBook, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
      AudiobookSortType.Author -> compareBy<AudiobookshelfBook, String>(String.CASE_INSENSITIVE_ORDER) { it.author }
      AudiobookSortType.Duration -> compareBy { it.durationMs }
      AudiobookSortType.Progress -> compareBy { it.progressPercent }
      AudiobookSortType.LastPlayed -> compareBy { it.updatedAt }
      AudiobookSortType.DateAdded -> compareBy { it.addedAt }
    }
    if (sortOrder == SortOrder.Descending) {
      filtered.sortedWith(comparator.reversed())
    } else {
      filtered.sortedWith(comparator)
    }
  }

  val isAbsSource = currentSource == AudiobookSourceProvider.AUDIOBOOKSHELF
  val totalCount = if (isAbsSource) visibleAbsBooks.size else visibleLocalBooks.size

  fun playLocal(book: Audiobook, restart: Boolean = false) {
    if (opening) return
    opening = true
    scope.launch {
      try {
        AudiobookPlayback.launch(context, book.book.id, fromBeginning = restart)
        detailsId = null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        playbackError = context.getString(R.string.audiobook_play_failed)
      } finally {
        opening = false
      }
    }
  }

  fun playAbs(book: AudiobookshelfBook, restart: Boolean = false) {
    if (opening) return
    opening = true
    scope.launch {
      try {
        absModel.playBook(context, book, startTrackIndex = 0, startPositionMs = if (restart) 0L else book.progressMs, restart = restart)
        absDetailsBook = null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Exception) {
        playbackError = context.getString(R.string.audiobook_play_failed)
      } finally {
        opening = false
      }
    }
  }

  BackHandler(search || importMenu) {
    if (importMenu) {
      importMenu = false
    } else {
      search = false
      query = ""
    }
  }
  LaunchedEffect(isAbsSource, importing) {
    if (isAbsSource || importing != null) importMenu = false
  }
  LaunchedEffect(search) {
    if (search) searchFocusRequester.requestFocus()
  }
  val navBarHeight = LocalNavigationBarHeight.current

  val filters = listOf(
    R.string.audiobook_all,
    R.string.audiobook_in_progress,
    R.string.audiobook_finished,
    R.string.audiobook_not_started,
  )
  Scaffold(
    containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
    topBar = {
      BrowserTopBar(
        title = if (isMusicTabMode) {
          stringResource(R.string.ui_music)
        } else if (isAbsSource) {
          absState.activeServer?.name ?: stringResource(R.string.audiobook_source_audiobookshelf)
        } else {
          stringResource(R.string.audiobooks_title)
        },
        isInSelectionMode = false,
        selectedCount = 0,
        totalCount = totalCount,
        onBackClick = if (isMusicTabMode) null else { { backStack.popSafely() } },
        onCancelSelection = { },
        onSortClick = { isSortMenuExpanded = true },
        onSearchClick = {
          search = !search
          if (!search) query = ""
        },
        onSettingsClick = { backStack.navigateTo(PreferencesScreen) },
        titleTrailing = if (isMusicTabMode) {
          { app.gyrolet.mpvrx.ui.browser.music.MusicSourceDropdown() }
        } else null,
          preSearchActions = {
            if (absState.servers.isNotEmpty()) {
              AppComboBoxChip(
                options = AudiobookSourceProvider.entries,
                selected = currentSource,
                onSelect = { mediaServerPreferences.audiobookSourceProvider.set(it) },
                optionLabel = { source ->
                  when (source) {
                    AudiobookSourceProvider.AUDIOBOOKSHELF -> stringResource(R.string.audiobook_source_audiobookshelf)
                    AudiobookSourceProvider.LOCAL -> stringResource(R.string.audiobook_source_local)
                  }
                },
                // The connected server's name says more than the generic source name.
                valueText =
                  when (currentSource) {
                    AudiobookSourceProvider.AUDIOBOOKSHELF ->
                      absState.activeServer?.name ?: stringResource(R.string.audiobook_source_audiobookshelf)
                    AudiobookSourceProvider.LOCAL -> stringResource(R.string.audiobook_source_local)
                  },
                leadingIcon = { AudiobookSourceIcon(currentSource) },
                optionLeadingIcon = { AudiobookSourceIcon(it) },
                footer = { dismiss ->
                  HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                  )
                  AppComboBoxOption(
                    text = stringResource(R.string.pref_media_servers_title),
                    selected = false,
                    index = 0,
                    itemCount = 1,
                    leadingIcon = {
                      Icon(Icons.RoundedFilled.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    trailingContent = {
                      Icon(Icons.RoundedFilled.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                    },
                    onClick = {
                      dismiss()
                      backStack.navigateTo(MediaServersPreferencesScreen)
                    },
                  )
                },
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp).widthIn(max = 200.dp),
              )
            }
          },
          additionalActions = {
            if (isAbsSource && absState.libraries.size > 1) {
              AppComboBoxChip(
                options = absState.libraries,
                selected = absState.libraries.firstOrNull { it.id == absState.activeLibrary?.id },
                onSelect = { absModel.selectLibrary(it) },
                optionLabel = { it.name },
                valueText = "",
                contentDescription = absState.activeLibrary?.name ?: stringResource(R.string.audiobooks_title),
                leadingIcon = { Icon(Icons.RoundedFilled.Folder, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 4.dp),
              )
            }
          },
        )
      },
      floatingActionButton = {
        if (!isAbsSource && importing == null) {
          FloatingActionButtonMenu(
            modifier = Modifier.padding(bottom = FabPlacement.scaffoldBottomPadding()),
            expanded = importMenu,
            button = {
              TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                  if (importMenu) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above,
                ),
                tooltip = { PlainTooltip { Text(stringResource(R.string.ui_toggle_menu)) } },
                state = rememberTooltipState(),
              ) {
                ToggleFloatingActionButton(
                  checked = importMenu,
                  onCheckedChange = { importMenu = it },
                ) {
                  Icon(
                    imageVector = if (checkedProgress > 0.5f) Icons.RoundedFilled.Close else Icons.RoundedFilled.Add,
                    contentDescription = stringResource(R.string.ui_toggle_menu),
                    modifier = Modifier.animateIcon({ checkedProgress }),
                  )
                }
              }
            },
          ) {
            FloatingActionButtonMenuItem(
              text = { Text(stringResource(R.string.audiobook_import_files)) },
              icon = { Icon(Icons.RoundedFilled.FileOpen, contentDescription = null) },
              onClick = {
                importMenu = false
                files.launch(arrayOf("*/*"))
              },
            )
            FloatingActionButtonMenuItem(
              text = { Text(stringResource(R.string.audiobook_import_folder)) },
              icon = { Icon(Icons.RoundedFilled.FolderOpen, contentDescription = null) },
              onClick = {
                importMenu = false
                folder.launch(null)
              },
            )
          }
        }
      },
    ) { padding ->
      Column(Modifier.fillMaxSize().padding(padding)) {
        if (search) {
          InlineSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSearch = { },
            placeholder = { Text(stringResource(R.string.audiobook_search)) },
            leadingIcon = { Icon(Icons.RoundedFilled.Search, contentDescription = null) },
            trailingIcon = {
              if (query.isNotEmpty()) {
                AudiobookIconButton(Icons.RoundedFilled.Close, stringResource(R.string.pref_clear_content_desc)) { query = "" }
              }
            },
            inputFieldModifier = Modifier.focusRequester(searchFocusRequester),
            windowInsets = WindowInsets(0, 0, 0, 0),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
        ) {
          filters.forEachIndexed { index, label ->
            FilterChip(
              selected = filter == index,
              shape = rememberConnectedShape(index, filters.size, ConnectedLayout.Horizontal),
              onClick = {
                if (filter != index) {
                  filter = index
                  haptics.selection(true)
                }
              },
              label = { Text(stringResource(label)) },
            )
          }
        }

        if (isAbsSource) {
          // --- AUDIOBOOKSHELF VIEW ---
          when {
            absState.servers.isEmpty() -> {
              Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                  Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                      painter = painterResource(R.drawable.ic_audiobookshelf),
                      contentDescription = null,
                      modifier = Modifier.size(42.dp),
                      tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                  }
                }
                Text(
                  stringResource(R.string.pref_audiobookshelf_no_server),
                  modifier = Modifier.padding(16.dp),
                  style = MaterialTheme.typography.titleLargeEmphasized,
                  textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                  onClick = { backStack.navigateTo(MediaServersPreferencesScreen) },
                  shapes = ButtonDefaults.shapes(),
                ) {
                  Text(stringResource(R.string.pref_audiobookshelf_add_server))
                }
              }
            }
            absState.isLoading && absState.books.isEmpty() -> {
              Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
              }
            }
            visibleAbsBooks.isEmpty() -> {
              Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(Icons.RoundedFilled.MenuBook, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
                Text(stringResource(R.string.audiobook_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLargeEmphasized)
                Button(onClick = { absModel.refresh() }) {
                  Text(stringResource(R.string.ui_refresh))
                }
              }
            }
            layoutMode == MediaLayoutMode.GRID -> {
              LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 145.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navBarHeight + 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
              ) {
                items(visibleAbsBooks, key = { it.id }) { book ->
                  AudiobookGridCard(
                    title = book.title,
                    author = book.author,
                    coverUri = book.coverUrl,
                    progressPercent = book.progressPercent,
                    isFinished = book.isFinished,
                    remainingMs = book.remainingMs,
                    opening = opening,
                    onClick = {
                      absDetailsBook = book
                      absModel.openBookDetails(book)
                    },
                    onPlay = { playAbs(book) },
                  )
                }
              }
            }
            else -> {
              LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarHeight + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(visibleAbsBooks, key = { it.id }) { book ->
                  AudiobookListRow(
                    title = book.title,
                    author = book.author,
                    coverUri = book.coverUrl,
                    progressPercent = book.progressPercent,
                    isFinished = book.isFinished,
                    remainingMs = book.remainingMs,
                    opening = opening,
                    onClick = {
                      absDetailsBook = book
                      absModel.openBookDetails(book)
                    },
                    onPlay = { playAbs(book) },
                  )
                }
              }
            }
          }
        } else {
          // --- LOCAL AUDIOBOOKS VIEW ---
          importing?.let { progress ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
              if (progress.second > 0) LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
              else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress.second > 0) Text(stringResource(R.string.audiobook_importing, progress.first, progress.second), Modifier.weight(1f),
                  style = MaterialTheme.typography.bodySmall) else Spacer(Modifier.weight(1f))
                TextButton(onClick = model::cancelImport) { Text(stringResource(R.string.generic_cancel)) }
              }
            }
          }
          when {
            books == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            visibleLocalBooks.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(Icons.RoundedFilled.MenuBook, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.secondary)
              Text(stringResource(R.string.audiobook_empty), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLargeEmphasized)
              if (books.orEmpty().isEmpty()) {
                Button(onClick = { files.launch(arrayOf("*/*")) }, enabled = importing == null, shapes = ButtonDefaults.shapes()) {
                  Text(stringResource(R.string.audiobook_import_files))
                }
                FilledTonalButton(onClick = { folder.launch(null) }, enabled = importing == null, shapes = ButtonDefaults.shapes()) {
                  Text(stringResource(R.string.audiobook_import_folder))
                }
              }
            }
            layoutMode == MediaLayoutMode.GRID -> LazyVerticalGrid(
              columns = GridCells.Adaptive(minSize = 145.dp),
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = navBarHeight + 88.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
              items(visibleLocalBooks, key = { it.book.id }) { book ->
                AudiobookGridCard(
                  title = book.book.title,
                  author = book.book.author,
                  coverUri = book.book.coverUri,
                  sourceUri = book.orderedTracks.firstOrNull()?.uri,
                  progressPercent = book.progress,
                  isFinished = book.book.finished,
                  remainingMs = (((book.durationMs - book.book.progressMs).coerceAtLeast(0) / book.book.playbackSpeed).toLong()),
                  opening = opening,
                  onClick = { detailsId = book.book.id },
                  onPlay = { playLocal(book) },
                )
              }
            }
            else -> LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = navBarHeight + 88.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              items(visibleLocalBooks, key = { it.book.id }) { book ->
                AudiobookListRow(
                  title = book.book.title,
                  author = book.book.author,
                  coverUri = book.book.coverUri,
                  sourceUri = book.orderedTracks.firstOrNull()?.uri,
                  progressPercent = book.progress,
                  isFinished = book.book.finished,
                  remainingMs = (((book.durationMs - book.book.progressMs).coerceAtLeast(0) / book.book.playbackSpeed).toLong()),
                  opening = opening,
                  onClick = { detailsId = book.book.id },
                  onPlay = { playLocal(book) },
                )
              }
            }
          }
        }
      }
    }

    if (isSortMenuExpanded) {
      AudiobookSortDialog(
        isOpen = isSortMenuExpanded,
        onDismiss = { isSortMenuExpanded = false },
        sortType = sortType,
        sortOrder = sortOrder,
        layoutMode = layoutMode,
        onSortTypeChange = { browserPreferences.audiobookSortType.set(it) },
        onSortOrderChange = { browserPreferences.audiobookSortOrder.set(it) },
        onLayoutModeChange = { browserPreferences.audiobookLayoutMode.set(it) },
      )
    }

    // Local Book Details Bottom Sheet
    books?.firstOrNull { it.book.id == detailsId }?.let { book ->
      AudiobookDetailsBottomSheet(
        coverUri = book.book.coverUri,
        sourceUri = book.orderedTracks.firstOrNull()?.uri,
        title = book.book.title,
        author = book.book.author,
        durationMs = book.durationMs,
        isFinished = book.book.finished,
        hasProgress = book.book.progressMs > 0 && !book.book.finished,
        opening = opening,
        onDismiss = { detailsId = null },
        onPlay = { playLocal(book) },
        onPlayFromBeginning = { playLocal(book, true) },
        details = listOf(
          R.string.audiobook_subtitle to book.book.subtitle,
          R.string.audiobook_author to book.book.author,
          R.string.audiobook_narrator to book.book.narrator,
          R.string.audiobook_series to book.book.series,
          R.string.audiobook_series_part to book.book.seriesPart,
          R.string.audiobook_description to book.book.description,
          R.string.audiobook_genre to book.book.genre,
          R.string.audiobook_language to book.book.language,
          R.string.audiobook_publisher to book.book.publisher,
          R.string.audiobook_published to book.book.publishedYear,
          R.string.audiobook_isbn to book.book.isbn,
          R.string.audiobook_asin to book.book.asin,
        ).filter { it.second.isNotBlank() },
        itemsTitleRes = R.string.audiobook_files,
        items = book.orderedTracks.map { track -> "${track.position + 1}. ${track.fileName}" to bookTime(track.durationMs) },
        onToggleFinished = {
          model.setFinished(book.book.id, !book.book.finished)
          detailsId = null
        },
        toggleFinishedEnabled = PlaybackSession.state.value.currentItem?.audiobook?.bookId != book.book.id,
        extraActions = {
          FilledTonalButton(
            onClick = { editing = book.book; detailsId = null },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
          ) {
            Icon(Icons.RoundedFilled.DriveFileRenameOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audiobook_edit))
          }
          FilledTonalButton(
            onClick = { removeId = book.book.id; detailsId = null },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.errorContainer,
              contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
          ) {
            Icon(Icons.RoundedFilled.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audiobook_remove))
          }
        },
      )
    }

    // Audiobookshelf Book Details Bottom Sheet
    absDetailsBook?.let { initialBook ->
      val book = (if (absState.detailBook?.id == initialBook.id) absState.detailBook else null) ?: initialBook
      val absDetails = listOfNotNull(
        book.subtitle.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_subtitle to it },
        book.author.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_author to it },
        book.narrator.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_narrator to it },
        book.series.takeIf { it.isNotBlank() && it != "null" }?.let {
          val partStr = if (book.seriesPart.isNotBlank() && book.seriesPart != "null") " #${book.seriesPart}" else ""
          R.string.audiobook_series to "$it$partStr"
        },
        book.description.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_description to it },
        book.genres.takeIf { it.isNotEmpty() }?.let { R.string.audiobook_genre to it.joinToString(", ") },
        book.language.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_language to it },
        book.publishedYear.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_published to it },
        book.isbn.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_isbn to it },
        book.asin.takeIf { it.isNotBlank() && it != "null" }?.let { R.string.audiobook_asin to it },
      )
      val (itemsTitle, itemList) = when {
        book.chapters.isNotEmpty() -> R.string.audiobook_chapters to book.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" to bookTime(c.startMs) }
        book.tracks.isNotEmpty() -> R.string.audiobook_files to book.tracks.map { "${it.index + 1}. ${it.title}" to bookTime(it.durationMs) }
        else -> null to emptyList()
      }
      AudiobookDetailsBottomSheet(
        coverUri = book.coverUrl,
        title = book.title,
        author = book.author,
        durationMs = book.durationMs,
        isFinished = book.isFinished,
        hasProgress = book.progressMs > 0 && !book.isFinished,
        opening = opening,
        onDismiss = {
          absDetailsBook = null
          absModel.closeBookDetails()
        },
        onPlay = { playAbs(book) },
        onPlayFromBeginning = { playAbs(book, restart = true) },
        details = absDetails,
        itemsTitleRes = itemsTitle,
        items = itemList,
        onToggleFinished = {
          absModel.syncProgress(
            book = book,
            currentTimeSeconds = if (book.isFinished) 0.0 else (book.durationMs / 1000.0),
            durationSeconds = book.durationMs / 1000.0,
            isFinished = !book.isFinished,
          )
          absDetailsBook = null
          absModel.closeBookDetails()
        },
        extraActions = {
          FilledTonalButton(
            onClick = { absSearchOnlineBook = book },
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
          ) {
            Icon(Icons.RoundedFilled.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.audiobook_search_online_title))
          }
        },
      )
    }

    absSearchOnlineBook?.let { book ->
      AudiobookOnlineSearchDialog(
        initialTitle = book.title,
        initialAuthor = book.author,
        onSearch = { title, author -> model.searchOnlineCovers(title, author) },
        onSelect = { result ->
          absModel.updateCover(book.id, result.coverUrl)
          absSearchOnlineBook = null
        },
        onDismiss = { absSearchOnlineBook = null },
      )
    }

    books?.firstOrNull { it.book.id == removeId }?.let { book ->
      AlertDialog(onDismissRequest = { removeId = null }, title = { Text(stringResource(R.string.audiobook_remove)) },
        text = { Text(stringResource(R.string.audiobook_remove_confirmation, book.book.title)) },
        confirmButton = { TextButton(onClick = { model.remove(book.book.id); removeId = null }) { Text(stringResource(R.string.audiobook_remove)) } },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text(stringResource(R.string.generic_cancel)) } })
    }

    editing?.let { book ->
      AudiobookEditDialog(
        book = book,
        onSearchOnline = { title, author -> model.searchOnlineCovers(title, author) },
        onDismiss = { editing = null },
      ) { updatedBook, newCoverUrl ->
        model.edit(updatedBook, newCoverUrl)
        editing = null
      }
    }

    (error ?: playbackError ?: absState.error)?.let { message ->
      AlertDialog(onDismissRequest = { model.dismissError(); playbackError = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { model.dismissError(); playbackError = null }) { Text(stringResource(R.string.generic_ok)) } })
    }
  }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudiobookDetailsBottomSheet(
  coverUri: String?,
  title: String,
  author: String,
  durationMs: Long,
  isFinished: Boolean,
  hasProgress: Boolean,
  opening: Boolean,
  onDismiss: () -> Unit,
  onPlay: () -> Unit,
  onPlayFromBeginning: () -> Unit,
  details: List<Pair<Int, String>>,
  itemsTitleRes: Int?,
  items: List<Pair<String, String>>,
  onToggleFinished: () -> Unit,
  toggleFinishedEnabled: Boolean = true,
  extraActions: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
  sourceUri: String? = null,
) {
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Expanded,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  )
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    dragHandle = { BottomSheetDefaults.DragHandle() },
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth(),
      contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(AppConnectedShapeTokens.spacing),
    ) {
      item(key = "book_header") {
        Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AudiobookArtwork(
            uri = coverUri,
            sourceUri = sourceUri,
            modifier = Modifier.width(96.dp).aspectRatio(0.76f).clip(MaterialTheme.shapes.large),
          )
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
              title,
              style = MaterialTheme.typography.headlineSmallEmphasized,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank() && author != "null") {
              Text(
                author,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Text(bookTime(durationMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Surface(
              shape = MaterialTheme.shapes.large,
              color = if (isFinished) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text = stringResource(if (isFinished) R.string.audiobook_finished else if (hasProgress) R.string.audiobook_in_progress else R.string.audiobook_not_started),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = if (isFinished) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }
      }

      item(key = "playback_actions") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onPlay,
            enabled = !opening,
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
          ) {
            Icon(Icons.RoundedFilled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (isFinished) R.string.audiobook_start_over else R.string.audiobook_continue))
          }
          if (hasProgress) {
            FilledTonalButton(
              onClick = onPlayFromBeginning,
              enabled = !opening,
              modifier = Modifier.fillMaxWidth(),
              shapes = ButtonDefaults.shapes(),
            ) {
              Icon(Icons.RoundedFilled.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
              Spacer(Modifier.width(8.dp))
              Text(stringResource(R.string.audiobook_start_over))
            }
          }
        }
      }

      if (details.isNotEmpty()) {
        item(key = "metadata_heading") {
          Text(
            stringResource(R.string.audiobook_details),
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
          )
        }
        items(details.size, key = { "metadata_$it" }, contentType = { "metadata" }) { index ->
          val (label, value) = details[index]
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = rememberConnectedShape(index, details.size, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
              Text(stringResource(label), style = MaterialTheme.typography.labelMediumEmphasized, color = MaterialTheme.colorScheme.primary)
              Text(value, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }

      if (itemsTitleRes != null && items.isNotEmpty()) {
        item(key = "tracks_heading") {
          Text(
            stringResource(itemsTitleRes),
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
          )
        }
        items(items.size, key = { "track_$it" }, contentType = { "track" }) { index ->
          val (itemTitle, itemDuration) = items[index]
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = rememberConnectedShape(index, items.size, ConnectedLayout.Vertical),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(itemTitle, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
              Text(itemDuration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }

      item(key = "book_actions") {
        Column(
          modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          extraActions?.invoke(this)
          FilledTonalButton(
            onClick = onToggleFinished,
            enabled = toggleFinishedEnabled,
            modifier = Modifier.fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
          ) {
            Text(stringResource(if (isFinished) R.string.audiobook_mark_unfinished else R.string.audiobook_mark_finished))
          }
        }
      }
    }
  }
}

@Composable
private fun AudiobookEditDialog(
  book: AudiobookEntity,
  onSearchOnline: suspend (String, String?) -> List<AudiobookOnlineMetadata>,
  onDismiss: () -> Unit,
  onSave: (AudiobookEntity, String?) -> Unit,
) {
  var title by rememberSaveable(book.id) { mutableStateOf(book.title) }
  var author by rememberSaveable(book.id) { mutableStateOf(book.author) }
  var narrator by rememberSaveable(book.id) { mutableStateOf(book.narrator) }
  var series by rememberSaveable(book.id) { mutableStateOf(book.series) }
  var part by rememberSaveable(book.id) { mutableStateOf(book.seriesPart) }
  var selectedCoverUrl by rememberSaveable(book.id) { mutableStateOf<String?>(null) }
  var showSearchDialog by rememberSaveable(book.id) { mutableStateOf(false) }

  if (showSearchDialog) {
    AudiobookOnlineSearchDialog(
      initialTitle = title,
      initialAuthor = author,
      onSearch = onSearchOnline,
      onSelect = { result ->
        selectedCoverUrl = result.coverUrl
        if (result.title.isNotBlank()) title = result.title
        result.author?.takeIf(String::isNotBlank)?.let { author = it }
        result.narrator?.takeIf(String::isNotBlank)?.let { narrator = it }
        showSearchDialog = false
      },
      onDismiss = { showSearchDialog = false },
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.audiobook_edit)) },
    text = {
      Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        // Cover preview + Search Online button
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          AudiobookArtwork(
            uri = selectedCoverUrl ?: book.coverUri,
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            FilledTonalButton(
              onClick = { showSearchDialog = true },
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text(
                text = stringResource(R.string.audiobook_search_online_title),
                style = MaterialTheme.typography.labelMedium,
              )
            }
          }
        }

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text(stringResource(R.string.audiobook_title)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = author,
          onValueChange = { author = it },
          label = { Text(stringResource(R.string.audiobook_author)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = narrator,
          onValueChange = { narrator = it },
          label = { Text(stringResource(R.string.audiobook_narrator)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = series,
          onValueChange = { series = it },
          label = { Text(stringResource(R.string.audiobook_series)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = part,
          onValueChange = { part = it },
          label = { Text(stringResource(R.string.audiobook_series_part)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            book.copy(
              title = title,
              author = author,
              narrator = narrator,
              series = series,
              seriesPart = part,
            ),
            selectedCoverUrl,
          )
        },
        enabled = title.isNotBlank(),
      ) {
        Text(stringResource(R.string.audiobook_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  )
}

@Composable
private fun AudiobookOnlineSearchDialog(
  initialTitle: String,
  initialAuthor: String,
  onSearch: suspend (String, String?) -> List<AudiobookOnlineMetadata>,
  onSelect: (AudiobookOnlineMetadata) -> Unit,
  onDismiss: () -> Unit,
) {
  var searchQuery by rememberSaveable {
    mutableStateOf(
      listOfNotNull(
        initialTitle.trim().takeIf(String::isNotBlank),
        initialAuthor.trim().takeIf(String::isNotBlank),
      ).joinToString(" "),
    )
  }
  var isSearching by remember { mutableStateOf(false) }
  var searchResults by remember { mutableStateOf<List<AudiobookOnlineMetadata>>(emptyList()) }
  var hasSearched by remember { mutableStateOf(false) }
  var searchGeneration by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()

  fun performSearch(query: String) {
    val normalized = query.trim()
    if (normalized.isEmpty()) return
    val generation = ++searchGeneration
    scope.launch {
      isSearching = true
      hasSearched = true
      val results = try {
        onSearch(normalized, null)
      } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        emptyList()
      }
      if (generation == searchGeneration) {
        searchResults = results
        isSearching = false
      }
    }
  }

  LaunchedEffect(Unit) {
    if (searchQuery.isNotBlank()) {
      performSearch(searchQuery)
    }
  }

  AppPickerSheet(
    onDismissRequest = onDismiss,
    title = stringResource(R.string.audiobook_search_online_title),
    scrollContent = false,
    actions = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.generic_cancel))
      }
    },
  ) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        PlayerSheetSearchField(
          query = searchQuery,
          onQueryChange = { searchQuery = it },
          placeholder = stringResource(R.string.audiobook_search_online_query),
          onSubmit = { performSearch(searchQuery) },
        )

        if (isSearching) {
          Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              CircularProgressIndicator(modifier = Modifier.size(32.dp))
              Text(
                text = stringResource(R.string.audiobook_search_online_searching),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else if (searchResults.isEmpty() && hasSearched) {
          Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = stringResource(R.string.audiobook_search_online_empty),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(
              items = searchResults,
              key = { "${it.title}_${it.author}_${it.coverUrl}" },
            ) { result ->
              Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { onSelect(result) },
              ) {
                Row(
                  modifier = Modifier.padding(8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  AudiobookArtwork(
                    uri = result.coverUrl,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                  )
                  Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                  ) {
                    Text(
                      text = result.title,
                      style = MaterialTheme.typography.titleSmall,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                    if (!result.author.isNullOrBlank()) {
                      Text(
                        text = result.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    if (!result.narrator.isNullOrBlank()) {
                      Text(
                        text = stringResource(R.string.audiobook_narrated_by, result.narrator),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    }
                    Text(
                      text = result.provider,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.primary,
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
internal fun AudiobookArtwork(uri: String?, modifier: Modifier = Modifier, sourceUri: String? = null) {
  val context = LocalContext.current
  val client = org.koin.compose.koinInject<okhttp3.OkHttpClient>()
  val repository = koinInject<app.gyrolet.mpvrx.domain.thumbnail.ThumbnailRepository>()
  val revision by repository.cacheRevision.collectAsState()
  val generator by koinInject<BrowserPreferences>().thumbnailGenerator.collectAsState()
  var image by remember(uri, sourceUri, generator, revision) {
    mutableStateOf<ImageBitmap?>(null)
  }

  LaunchedEffect(uri, sourceUri, generator, revision) {
    if (app.gyrolet.mpvrx.domain.thumbnail.isLocalArtworkSource(sourceUri)) {
      image = repository.getArtwork(sourceUri)?.asImageBitmap()
      return@LaunchedEffect
    }
    if (uri == null) {
      image = null
      return@LaunchedEffect
    }
    val loadedBitmap = withContext(Dispatchers.IO) {
      val isRemote = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
      if (isRemote) {
        app.gyrolet.mpvrx.presentation.components.RemoteImageLoader.load(context, client, uri)
          ?: EmbeddedArtworkResolver.decodeArtworkUri(context, uri)
      } else {
        EmbeddedArtworkResolver.decodeArtworkUri(context, uri)
          ?: runCatching {
            fun stream() = context.contentResolver.openInputStream(Uri.parse(uri))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
              inSampleSize = 1
              while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 800) inSampleSize *= 2
            }
            stream()?.use { BitmapFactory.decodeStream(it, null, options) }
          }.getOrNull()
      }
    }
    image = loadedBitmap?.asImageBitmap()
  }

  Box(
    modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    val loaded = image
    if (loaded != null) {
      Image(
        bitmap = loaded,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Icon(
        Icons.RoundedFilled.MenuBook,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(0.42f),
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}

internal fun bookTime(milliseconds: Long): String = DateUtils.formatElapsedTime(milliseconds.coerceAtLeast(0) / 1000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudiobookIconButton(
  icon: AppIcon,
  label: String,
  enabled: Boolean = true,
  modifier: Modifier = Modifier.padding(horizontal = 2.dp),
  onClick: () -> Unit,
) {
  TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    tooltip = { PlainTooltip { Text(label) } },
    state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.secondary,
      )
    }
  }
}

@Composable
internal fun AudiobookIconButton(
  icon: AppIcon,
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  AudiobookIconButton(
    icon = icon,
    label = label,
    enabled = enabled,
    modifier = Modifier.padding(horizontal = 2.dp),
    onClick = onClick,
  )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudiobookGridCard(
  title: String,
  author: String,
  coverUri: String?,
  progressPercent: Float,
  isFinished: Boolean,
  remainingMs: Long,
  opening: Boolean,
  onClick: () -> Unit,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
  sourceUri: String? = null,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
      Box(
        Modifier
          .fillMaxWidth()
          .aspectRatio(0.76f)
          .clip(RoundedCornerShape(12.dp)),
      ) {
        AudiobookArtwork(coverUri, Modifier.fillMaxSize(), sourceUri)
        if (progressPercent > 0f) {
          LinearProgressIndicator(
            progress = { progressPercent },
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .height(4.dp),
          )
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
        title,
        style = MaterialTheme.typography.titleSmallEmphasized,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (author.isNotBlank()) {
        Text(
          author,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Spacer(Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          if (isFinished) stringResource(R.string.audiobook_finished)
          else stringResource(R.string.audiobook_remaining, bookTime(remainingMs)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        FilledTonalIconButton(
          onClick = onPlay,
          enabled = !opening,
          modifier = Modifier.size(48.dp),
        ) {
          Icon(
            Icons.RoundedFilled.PlayArrow,
            contentDescription = stringResource(if (isFinished) R.string.audiobook_start_over else R.string.audiobook_continue),
            modifier = Modifier.size(20.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AudiobookListRow(
  title: String,
  author: String,
  coverUri: String?,
  progressPercent: Float,
  isFinished: Boolean,
  remainingMs: Long,
  opening: Boolean,
  onClick: () -> Unit,
  onPlay: () -> Unit,
  modifier: Modifier = Modifier,
  sourceUri: String? = null,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      AudiobookArtwork(coverUri, Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)), sourceUri)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (author.isNotBlank()) {
          Text(author, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
          if (isFinished) stringResource(R.string.audiobook_finished)
          else stringResource(R.string.audiobook_remaining, bookTime(remainingMs)),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(progress = { progressPercent }, modifier = Modifier.fillMaxWidth().height(3.dp))
      }
      FilledTonalIconButton(onClick = onPlay, enabled = !opening, modifier = Modifier.size(48.dp)) {
        Icon(
          Icons.RoundedFilled.PlayArrow,
          contentDescription = stringResource(if (isFinished) R.string.audiobook_start_over else R.string.audiobook_continue),
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }
}

@Composable
private fun AudiobookSourceIcon(source: AudiobookSourceProvider) {
  when (source) {
    AudiobookSourceProvider.AUDIOBOOKSHELF ->
      androidx.compose.material3.Icon(
        painter = painterResource(R.drawable.ic_audiobookshelf),
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
    AudiobookSourceProvider.LOCAL ->
      Icon(Icons.RoundedFilled.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
  }
}
