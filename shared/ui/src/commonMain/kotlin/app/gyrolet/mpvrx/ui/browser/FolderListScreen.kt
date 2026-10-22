package app.gyrolet.mpvrx.ui.browser

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.history.PlaybackRecord
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.media.MediaFolder
import app.gyrolet.mpvrx.core.media.isNetworkUrl
import app.gyrolet.mpvrx.core.preferences.FolderSortOrder
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.core.util.formatFileSize
import app.gyrolet.mpvrx.ui.components.EmptyState
import app.gyrolet.mpvrx.ui.components.HeaderAction
import app.gyrolet.mpvrx.ui.components.MediaThumbnail
import app.gyrolet.mpvrx.ui.components.ScreenHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.util.collectAsState
import kotlinx.coroutines.launch

@Composable
fun FolderListScreen(
  library: LibraryViewModel,
  bottomPadding: PaddingValues,
) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val scope = rememberCoroutineScope()
  val folders by library.folders.collectAsState()
  val scanning by library.isScanning.collectAsState()
  val history by deps.history.records.collectAsState()
  val sortOrder by deps.settings.browser.folderSortOrder.collectAsState()
  val ascending by deps.settings.browser.folderSortAscending.collectAsState()
  val grid by deps.settings.browser.gridLayout.collectAsState()
  var query by remember { mutableStateOf("") }
  var searching by remember { mutableStateOf(false) }
  var sortMenu by remember { mutableStateOf(false) }

  val visible =
    remember(folders, sortOrder, ascending, query) {
      folders.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.sortedFor(sortOrder, ascending)
    }
  val continueWatching = remember(history) { history.filter { !it.isFinished && !it.isAudio && it.progress > 0f }.take(12) }

  Column(Modifier.fillMaxSize()) {
    ScreenHeader(title = "mpvRx") {
      HeaderAction(Icons.Search, "Search") { searching = !searching; if (!searching) query = "" }
      HeaderAction(Icons.FileOpen, "Open files") {
        scope.launch {
          val files = deps.platform.pickMediaFiles()
          if (files.isNotEmpty()) {
            deps.playback.playPaths(files)
            navigator.push(Screen.Player)
          }
        }
      }
      HeaderAction(Icons.Add, "Add folder") {
        scope.launch { deps.platform.pickFolder()?.let(library::addFolder) }
      }
      Box {
        HeaderAction(Icons.SortByAlpha, "Sort") { sortMenu = true }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
          FolderSortOrder.entries.forEach { order ->
            DropdownMenuItem(
              text = { Text(order.title) },
              trailingIcon = { if (order == sortOrder) Icon(Icons.Check, null) },
              onClick = {
                if (order == sortOrder) {
                  deps.settings.browser.folderSortAscending.set(!ascending)
                } else {
                  deps.settings.browser.folderSortOrder.set(order)
                }
                sortMenu = false
              },
            )
          }
        }
      }
      HeaderAction(if (grid) Icons.ViewList else Icons.GridView, "Layout") { deps.settings.browser.gridLayout.set(!grid) }
      HeaderAction(Icons.Refresh, "Refresh") { library.refresh() }
    }

    if (searching) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search folders") },
        leadingIcon = { Icon(Icons.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
      )
    }
    if (scanning) {
      LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
    }

    if (visible.isEmpty() && continueWatching.isEmpty() && !scanning) {
      EmptyState(
        icon = Icons.VideoLibrary,
        title = "No videos found",
        message = "Add a folder or open files to start watching.",
        action = {
          FilledTonalButton(onClick = { scope.launch { deps.platform.pickFolder()?.let(library::addFolder) } }) {
            Text("Add folder")
          }
        },
      )
      return@Column
    }

    LazyVerticalGrid(
      columns = if (grid) GridCells.Adaptive(200.dp) else GridCells.Adaptive(420.dp),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding.calculateBottomPadding() + 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 8.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      if (continueWatching.isNotEmpty() && query.isBlank()) {
        item(span = { GridItemSpan(maxLineSpan) }) {
          ContinueWatchingRow(continueWatching) { record ->
            deps.playback.playPaths(listOf(record.path))
            navigator.push(Screen.Player)
          }
        }
      }
      items(visible, key = { it.path }) { folder ->
        if (grid) {
          FolderGridCard(folder) { navigator.push(Screen.FolderVideos(folder)) }
        } else {
          FolderRow(folder) { navigator.push(Screen.FolderVideos(folder)) }
        }
      }
    }
  }
}

@Composable
private fun ContinueWatchingRow(
  records: List<PlaybackRecord>,
  onOpen: (PlaybackRecord) -> Unit,
) {
  Column(Modifier.padding(bottom = 8.dp)) {
    Text(
      "Continue watching",
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      items(records, key = { it.path }) { record ->
        val file =
          remember(record.path) {
            val name = record.path.substringAfterLast('/').substringAfterLast('\\')
            MediaFile(record.path, name, "", 0, 0, record.isAudio)
          }
        Column(
          Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).clickable { onOpen(record) }.padding(4.dp),
        ) {
          if (isNetworkUrl(record.path)) {
            Box(
              Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
              contentAlignment = Alignment.Center,
            ) { Icon(Icons.Language, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
          } else {
            MediaThumbnail(
              file,
              Modifier.fillMaxWidth().aspectRatio(16f / 9f),
              progress = record.progress,
              badge = "-" + formatDuration(record.durationSeconds - record.positionSeconds),
            )
          }
          Spacer(Modifier.height(6.dp))
          Text(record.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

@Composable
private fun FolderRow(folder: MediaFolder, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      FolderGlyph(Modifier.size(56.dp))
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
          folderSummary(folder),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
        Text(
          folder.path,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
      }
      Icon(Icons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun FolderGridCard(folder: MediaFolder, onClick: () -> Unit) {
  Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
    Column(Modifier.fillMaxWidth().padding(14.dp)) {
      FolderGlyph(Modifier.fillMaxWidth().aspectRatio(1.6f))
      Spacer(Modifier.height(10.dp))
      Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(folderSummary(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun FolderGlyph(modifier: Modifier) {
  Box(
    modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primaryContainer),
    contentAlignment = Alignment.Center,
  ) {
    Icon(Icons.Folder, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
  }
}

private fun folderSummary(folder: MediaFolder): String =
  buildList {
    add("${folder.videoCount} video${if (folder.videoCount == 1) "" else "s"}")
    if (folder.audioCount > 0) add("${folder.audioCount} audio")
    add(formatFileSize(folder.totalSizeBytes))
  }.joinToString(" · ")
