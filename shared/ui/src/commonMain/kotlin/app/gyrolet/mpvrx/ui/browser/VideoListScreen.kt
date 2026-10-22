package app.gyrolet.mpvrx.ui.browser

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.media.MediaFolder
import app.gyrolet.mpvrx.core.media.MediaMetadata
import app.gyrolet.mpvrx.core.preferences.VideoSortOrder
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.core.util.formatFileSize
import app.gyrolet.mpvrx.ui.components.HeaderAction
import app.gyrolet.mpvrx.ui.components.InfoChip
import app.gyrolet.mpvrx.ui.components.MediaThumbnail
import app.gyrolet.mpvrx.ui.components.ScreenHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.util.collectAsState

@Composable
fun VideoListScreen(folder: MediaFolder) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  var files by remember(folder.path) { mutableStateOf<List<MediaFile>?>(null) }
  val metadata = remember(folder.path) { mutableStateMapOf<String, MediaMetadata>() }
  val history by deps.history.records.collectAsState()
  val sortOrder by deps.settings.browser.videoSortOrder.collectAsState()
  val ascending by deps.settings.browser.videoSortAscending.collectAsState()
  val grid by deps.settings.browser.gridLayout.collectAsState()
  var sortMenu by remember { mutableStateOf(false) }

  LaunchedEffect(folder.path) {
    files = deps.scanner.listMedia(folder.path, deps.settings.browser.showHiddenFiles.get())
  }
  LaunchedEffect(files) {
    // Probe sequentially; the provider caches results so revisits are instant.
    files.orEmpty().filterNot { it.isAudio }.forEach { file ->
      deps.mediaInfo.cachedMetadata(file.path)?.let { metadata[file.path] = it }
    }
    files.orEmpty().filterNot { it.isAudio || it.path in metadata }.forEach { file ->
      deps.mediaInfo.metadata(file.path)?.let { metadata[file.path] = it }
    }
  }

  val sorted =
    remember(files, sortOrder, ascending, metadata.size) {
      files.orEmpty().sortedFor(sortOrder, ascending) { metadata[it.path]?.durationSeconds ?: 0.0 }
    }
  val progressByPath = remember(history) { history.associateBy { it.path } }

  Column(Modifier.fillMaxSize()) {
    ScreenHeader(
      title = folder.name,
      subtitle = "${sorted.size} items · ${formatFileSize(sorted.sumOf { it.sizeBytes })}",
      onBack = { navigator.pop() },
    ) {
      HeaderAction(Icons.PlayCircle, "Play all") {
        if (sorted.isNotEmpty()) {
          deps.playback.playFiles(sorted, 0)
          navigator.push(Screen.Player)
        }
      }
      Box {
        HeaderAction(Icons.SortByAlpha, "Sort") { sortMenu = true }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
          VideoSortOrder.entries.forEach { order ->
            DropdownMenuItem(
              text = { Text(order.title) },
              trailingIcon = { if (order == sortOrder) Icon(Icons.Check, null) },
              onClick = {
                if (order == sortOrder) {
                  deps.settings.browser.videoSortAscending.set(!ascending)
                } else {
                  deps.settings.browser.videoSortOrder.set(order)
                }
                sortMenu = false
              },
            )
          }
        }
      }
      HeaderAction(if (grid) Icons.ViewList else Icons.GridView, "Layout") { deps.settings.browser.gridLayout.set(!grid) }
      HeaderAction(Icons.FolderOpen, "Show in Explorer") { deps.platform.revealInFileManager(folder.path) }
    }

    if (files == null) {
      LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
      return@Column
    }

    LazyVerticalGrid(
      columns = if (grid) GridCells.Adaptive(240.dp) else GridCells.Adaptive(520.dp),
      contentPadding = PaddingValues(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(if (grid) 16.dp else 6.dp),
      modifier = Modifier.fillMaxSize(),
    ) {
      itemsIndexed(sorted, key = { _, file -> file.path }) { index, file ->
        val meta = metadata[file.path]
        val record = progressByPath[file.path]
        val onClick = {
          deps.playback.playFiles(sorted, index)
          navigator.push(Screen.Player)
        }
        if (grid) {
          VideoGridCard(file, meta, record?.progress, onClick)
        } else {
          VideoRow(file, meta, record?.progress, record?.isFinished == true, onClick)
        }
      }
    }
  }
}

@Composable
private fun VideoRow(
  file: MediaFile,
  meta: MediaMetadata?,
  progress: Float?,
  finished: Boolean,
  onClick: () -> Unit,
) {
  val unlimited by LocalAppDependencies.current.settings.appearance.unlimitedNameLines.collectAsState()
  Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      MediaThumbnail(
        file,
        Modifier.width(168.dp).aspectRatio(16f / 9f),
        progress = progress?.takeUnless { finished },
        badge = meta?.durationSeconds?.takeIf { it > 0 }?.let(::formatDuration),
      )
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text(
          file.displayName,
          style = MaterialTheme.typography.titleMedium,
          maxLines = if (unlimited) Int.MAX_VALUE else 2,
          overflow = TextOverflow.Ellipsis,
          color = if (finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          meta?.resolutionLabel?.let { InfoChip(it) }
          InfoChip(formatFileSize(file.sizeBytes))
          InfoChip(file.extension.uppercase())
          if (finished) InfoChip("Watched", highlighted = true)
        }
      }
    }
  }
}

@Composable
private fun VideoGridCard(
  file: MediaFile,
  meta: MediaMetadata?,
  progress: Float?,
  onClick: () -> Unit,
) {
  Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.fillMaxWidth().padding(6.dp)) {
      MediaThumbnail(
        file,
        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        progress = progress,
        badge = meta?.durationSeconds?.takeIf { it > 0 }?.let(::formatDuration),
      )
      Spacer(Modifier.height(8.dp))
      Text(file.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(
        listOfNotNull(meta?.resolutionLabel, formatFileSize(file.sizeBytes)).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
