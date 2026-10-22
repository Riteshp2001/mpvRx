package app.gyrolet.mpvrx.ui.browser

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.media.MediaFile
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.ui.components.EmptyState
import app.gyrolet.mpvrx.ui.components.HeaderAction
import app.gyrolet.mpvrx.ui.components.MediaThumbnail
import app.gyrolet.mpvrx.ui.components.ScreenHeader
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.navigation.Screen
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies

@Composable
fun HistoryScreen() {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val records by deps.history.records.collectAsState()
  Column(Modifier.fillMaxSize()) {
    ScreenHeader("History", subtitle = "${records.size} items", onBack = { navigator.pop() }) {
      HeaderAction(Icons.Delete, "Clear history") { deps.history.clear() }
    }
    if (records.isEmpty()) {
      EmptyState(Icons.History, "Nothing played yet", "Media you play shows up here with its resume position.")
      return@Column
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      items(records, key = { it.path }) { record ->
        val file = remember(record.path) { MediaFile(record.path, record.path.substringAfterLast('\\').substringAfterLast('/'), "", 0, 0, record.isAudio) }
        Surface(
          onClick = {
            deps.playback.playPaths(listOf(record.path))
            navigator.push(Screen.Player)
          },
          shape = RoundedCornerShape(18.dp),
        ) {
          Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(file, Modifier.width(140.dp).aspectRatio(16f / 9f), progress = record.progress)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
              Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
              Spacer(Modifier.height(4.dp))
              Text(
                if (record.isFinished) "Finished" else "${formatDuration(record.positionSeconds)} / ${formatDuration(record.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { deps.history.remove(record.path) }) { Icon(Icons.Close, "Remove") }
          }
        }
      }
    }
  }
}
