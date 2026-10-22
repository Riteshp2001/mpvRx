package app.gyrolet.mpvrx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.player.AspectMode
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.player.TrackType
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PlayerPanel { None, Audio, Subtitles, Speed, Chapters, Playlist, Aspect }

/** Right-hand sheet used for every panel, mirroring the Android landscape panels. */
@Composable
fun PlayerSidePanel(
  title: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  actions: @Composable () -> Unit = {},
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.width(420.dp).fillMaxHeight().padding(12.dp),
    shape = RoundedCornerShape(32.dp),
    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
    tonalElevation = 6.dp,
  ) {
    Column(Modifier.padding(top = 20.dp, start = 20.dp, end = 12.dp, bottom = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) {
          Text("  ·  $subtitle", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        actions()
        IconButton(onClick = onClose) { Icon(Icons.Close, "Close") }
      }
      Spacer(Modifier.height(12.dp))
      content()
    }
  }
}

@Composable
fun TracksPanel(
  type: TrackType,
  state: PlayerState,
  onClose: () -> Unit,
) {
  val deps = LocalAppDependencies.current
  val scope = rememberCoroutineScope()
  val tracks = if (type == TrackType.Audio) state.audioTracks else state.subtitleTracks
  val anySelected = tracks.any { it.isSelected }
  PlayerSidePanel(
    title = if (type == TrackType.Audio) "Audio" else "Subtitles",
    subtitle = "${tracks.size} tracks",
    onClose = onClose,
    actions = {
      if (type == TrackType.Subtitle) {
        IconButton(onClick = { scope.launch { deps.platform.pickSubtitle()?.let(deps.engine::addSubtitle) } }) {
          Icon(Icons.Add, "Add subtitle file")
        }
      }
    },
  ) {
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (type == TrackType.Subtitle) {
        item { TrackRow("Off", null, selected = !anySelected) { deps.engine.selectTrack(type, null) } }
      }
      itemsIndexed(tracks, key = { _, track -> track.id }) { _, track ->
        TrackRow(
          title = track.title?.takeIf(String::isNotBlank) ?: "Track ${track.id}",
          summary =
            listOfNotNull(
              track.language?.uppercase(),
              track.codec?.uppercase(),
              track.channels?.let { "${it}ch" },
              "External".takeIf { track.isExternal },
              "Default".takeIf { track.isDefault },
            ).joinToString(" · "),
          selected = track.isSelected,
        ) { deps.engine.selectTrack(type, track.id) }
      }
    }
    Spacer(Modifier.height(12.dp))
    val delay = if (type == TrackType.Audio) state.audioDelaySeconds else state.subtitleDelaySeconds
    DelayRow(
      label = if (type == TrackType.Audio) "Audio delay" else "Subtitle delay",
      seconds = delay,
      onChange = { value ->
        if (type == TrackType.Audio) deps.engine.setAudioDelay(value) else deps.engine.setSubtitleDelay(value)
      },
    )
  }
}

@Composable
private fun TrackRow(
  title: String,
  summary: String?,
  selected: Boolean,
  onClick: () -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f))
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    RadioButton(selected = selected, onClick = null)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (!summary.isNullOrBlank()) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
      }
    }
  }
}

@Composable
private fun DelayRow(
  label: String,
  seconds: Double,
  onChange: (Double) -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
    FilledTonalButton(onClick = { onChange(seconds - 0.1) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("−100ms") }
    Text(
      "${(seconds * 1000).roundToInt()}ms",
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.width(72.dp).clickable { onChange(0.0) },
      maxLines = 1,
    )
    FilledTonalButton(onClick = { onChange(seconds + 0.1) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("+100ms") }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedPanel(
  speed: Double,
  onClose: () -> Unit,
) {
  val deps = LocalAppDependencies.current
  val presets by deps.settings.player.speedPresets.let { pref -> remember(pref) { pref.changes() }.collectAsState(pref.get()) }
  var slider by remember(speed) { mutableFloatStateOf(speed.toFloat()) }
  PlayerSidePanel("Playback speed", onClose, subtitle = "${(slider * 100).roundToInt() / 100.0}x") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      presets.mapNotNull(String::toDoubleOrNull).sorted().forEach { preset ->
        FilterChip(
          selected = kotlin.math.abs(preset - speed) < 0.001,
          onClick = { deps.playback.setSpeed(preset) },
          label = { Text("${preset}x") },
        )
      }
    }
    Spacer(Modifier.height(20.dp))
    Slider(
      value = slider,
      onValueChange = { slider = (it * 20).roundToInt() / 20f },
      onValueChangeFinished = { deps.playback.setSpeed(slider.toDouble()) },
      valueRange = 0.25f..4f,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilledTonalButton(onClick = { deps.playback.setSpeed(1.0) }) { Text("Reset") }
      FilledTonalButton(onClick = { deps.settings.player.defaultSpeed.set(speed.toFloat()) }) { Text("Set as default") }
    }
  }
}

@Composable
fun ChaptersPanel(
  state: PlayerState,
  onClose: () -> Unit,
) {
  val deps = LocalAppDependencies.current
  val listState = rememberLazyListState()
  LaunchedEffect(Unit) { if (state.currentChapter > 0) listState.scrollToItem(state.currentChapter) }
  PlayerSidePanel("Chapters", onClose, subtitle = "${state.chapters.size}") {
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      itemsIndexed(state.chapters, key = { index, _ -> index }) { index, chapter ->
        val current = index == state.currentChapter
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f))
            .clickable { deps.engine.setChapter(index) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            formatDuration(chapter.startSeconds),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(72.dp),
          )
          Text(
            chapter.title.ifBlank { "Chapter ${index + 1}" },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
fun PlaylistPanel(onClose: () -> Unit) {
  val deps = LocalAppDependencies.current
  val queue by deps.playback.queue.state.collectAsState()
  val scope = rememberCoroutineScope()
  val listState = rememberLazyListState()
  LaunchedEffect(Unit) { if (queue.currentIndex > 0) listState.scrollToItem(queue.currentIndex) }
  PlayerSidePanel(
    "Now Playing",
    onClose,
    subtitle = "${queue.items.size} items",
    actions = {
      IconButton(onClick = {
        scope.launch {
          val files = deps.platform.pickMediaFiles()
          if (files.isNotEmpty()) {
            deps.playback.queue.append(
              files.map { path ->
                val name = path.substringAfterLast('\\').substringAfterLast('/')
                app.gyrolet.mpvrx.core.player.QueueItem(path, name.substringBeforeLast('.'), app.gyrolet.mpvrx.core.media.MediaExtensions.isAudio(name))
              },
            )
          }
        }
      }) { Icon(Icons.PlaylistAdd, "Add to queue") }
    },
  ) {
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      itemsIndexed(queue.items, key = { index, item -> "$index:${item.path}" }) { index, item ->
        val current = index == queue.currentIndex
        Row(
          Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(
              if (current) {
                Modifier
                  .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                  .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp))
              } else {
                Modifier
              },
            ).clickable { deps.playback.playQueueIndex(index) }
            .padding(10.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
          ) { Text("${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
          Spacer(Modifier.width(12.dp))
          Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (current) {
            Icon(Icons.Equalizer, null, tint = MaterialTheme.colorScheme.primary)
          } else {
            IconButton(onClick = { deps.playback.queue.remove(index) }) { Icon(Icons.Close, "Remove", modifier = Modifier.size(18.dp)) }
          }
        }
      }
    }
  }
}

private val AspectOptions =
  listOf(
    AspectMode.Fit to "Fit",
    AspectMode.Crop to "Crop",
    AspectMode.Stretch to "Stretch",
    AspectMode.Ratio(16.0 / 9.0, "16:9") to "16:9",
    AspectMode.Ratio(4.0 / 3.0, "4:3") to "4:3",
    AspectMode.Ratio(21.0 / 9.0, "21:9") to "21:9",
    AspectMode.Ratio(2.35, "2.35:1") to "2.35:1",
    AspectMode.Ratio(1.0, "1:1") to "1:1",
  )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AspectPanel(
  current: AspectMode,
  zoom: Float,
  onAspect: (AspectMode) -> Unit,
  onZoom: (Float) -> Unit,
  onClose: () -> Unit,
) {
  PlayerSidePanel("Aspect & zoom", onClose) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      AspectOptions.forEach { (mode, label) ->
        FilterChip(selected = mode == current, onClick = { onAspect(mode) }, label = { Text(label) })
      }
    }
    Spacer(Modifier.height(20.dp))
    // mpv's video-zoom is log2-scaled.
    Text("Zoom ${(2.0.pow(zoom.toDouble()) * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall)
    Slider(value = zoom, onValueChange = onZoom, valueRange = -0.5f..1.5f)
    FilledTonalButton(onClick = { onZoom(0f) }) { Text("Reset zoom") }
  }
}
