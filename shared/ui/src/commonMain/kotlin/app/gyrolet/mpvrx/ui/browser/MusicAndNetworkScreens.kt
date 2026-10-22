package app.gyrolet.mpvrx.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.util.formatFileSize
import app.gyrolet.mpvrx.ui.components.EmptyState
import app.gyrolet.mpvrx.ui.components.HeaderAction
import app.gyrolet.mpvrx.ui.components.ScreenHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.util.collectAsState

@Composable
fun MusicScreen(
  library: LibraryViewModel,
  bottomPadding: PaddingValues,
) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val audio by library.audio.collectAsState()
  val queueState by deps.playback.queue.state.collectAsState()
  var query by remember { mutableStateOf("") }
  val visible = remember(audio, query) { audio.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) } }

  Column(Modifier.fillMaxSize()) {
    ScreenHeader("Music", subtitle = "${audio.size} tracks") {
      HeaderAction(Icons.Shuffle, "Shuffle all") {
        if (visible.isNotEmpty()) {
          if (!queueState.shuffle) deps.playback.toggleShuffle()
          deps.playback.playFiles(visible, visible.indices.random())
          navigator.push(Screen.Player)
        }
      }
      HeaderAction(Icons.Refresh, "Refresh") { library.refresh() }
    }
    OutlinedTextField(
      value = query,
      onValueChange = { query = it },
      placeholder = { Text("Search music") },
      leadingIcon = { Icon(Icons.Search, null) },
      singleLine = true,
      shape = RoundedCornerShape(28.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (visible.isEmpty()) {
      EmptyState(Icons.MusicNote, "No music yet", "Audio files in your library folders appear here.")
      return@Column
    }
    LazyColumn(
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding.calculateBottomPadding() + 16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      itemsIndexed(visible, key = { _, file -> file.path }) { index, file ->
        val playing = queueState.current?.path == file.path
        Surface(
          onClick = {
            deps.playback.playFiles(visible, index)
            navigator.push(Screen.Player)
          },
          shape = RoundedCornerShape(16.dp),
          color = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ) {
          Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
              Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiaryContainer),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                if (playing) Icons.Equalizer else Icons.MusicNote,
                null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
              Text(
                file.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                "${file.folderPath.substringAfterLast('\\').substringAfterLast('/')} · ${file.extension.uppercase()} · ${formatFileSize(file.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun NetworkScreen(bottomPadding: PaddingValues) {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val recents by deps.settings.browser.recentNetworkUrls.collectAsState()
  var url by remember { mutableStateOf("") }
  val play = { target: String ->
    if (target.isNotBlank()) {
      deps.playback.playUrl(target)
      navigator.push(Screen.Player)
    }
  }

  Column(Modifier.fillMaxSize()) {
    ScreenHeader("Network", subtitle = "Stream from HTTP, HLS, RTSP, SMB paths or any yt-dlp supported site")
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        placeholder = { Text("https://…") },
        leadingIcon = { Icon(Icons.Link, null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(12.dp))
      Button(onClick = { play(url) }, enabled = url.isNotBlank()) {
        Icon(Icons.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text("Play")
      }
    }
    if (recents.isEmpty()) {
      EmptyState(Icons.Language, "No recent streams", "Paste a link above to start streaming.")
      return@Column
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
      FilledTonalButton(onClick = { deps.settings.browser.recentNetworkUrls.set(emptyList()) }) { Text("Clear") }
    }
    LazyColumn(
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding.calculateBottomPadding() + 16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      itemsIndexed(recents, key = { _, item -> item }) { _, item ->
        Surface(onClick = { play(item) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
          Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.History, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(item, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
          }
        }
      }
    }
  }
}
