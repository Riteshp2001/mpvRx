package app.gyrolet.mpvrx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.preferences.RepeatMode
import app.gyrolet.mpvrx.core.preferences.SeekbarStyle
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies

/** Port of the Android in-app music player; adapts to a side-by-side layout on wide windows. */
@Composable
fun MusicPlayerLayout(
  state: PlayerState,
  onCollapse: () -> Unit,
  onPanel: (PlayerPanel) -> Unit,
) {
  val deps = LocalAppDependencies.current
  val queue by deps.playback.queue.state.collectAsState()
  var artist by remember(state.path) { mutableStateOf<String?>(null) }
  var album by remember(state.path) { mutableStateOf<String?>(null) }
  LaunchedEffect(state.path, state.durationSeconds > 0) {
    artist = deps.engine.getProperty("metadata/by-key/artist")?.takeIf(String::isNotBlank)
    album = deps.engine.getProperty("metadata/by-key/album")?.takeIf(String::isNotBlank)
  }
  val codec = state.audioTracks.firstOrNull { it.isSelected }?.codec?.lowercase()
  val lossless = codec in setOf("flac", "alac", "wavpack", "ape", "tta", "pcm_s16le", "pcm_s24le", "truehd", "mlp")

  Box(
    Modifier.fillMaxSize().background(
      Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), MaterialTheme.colorScheme.background)),
    ),
  ) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCollapse) { Icon(Icons.ExpandMore, "Collapse") }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text("Now Playing", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (lossless) {
            Text(
              "LOSSLESS",
              style = MaterialTheme.typography.labelMedium,
              modifier =
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 10.dp, vertical = 3.dp),
            )
          }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onPanel(PlayerPanel.Playlist) }) { Icon(Icons.QueueMusic, "Queue") }
      }

      BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val wide = maxWidth > maxHeight * 1.2f
        if (wide) {
          Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Artwork(state, Modifier.weight(1f, fill = false).fillMaxHeight(0.85f))
            Column(Modifier.weight(1f).widthIn(max = 640.dp)) {
              TrackInfo(state, artist, album, queue.currentIndex, queue.items.size)
              Spacer(Modifier.height(24.dp))
              MusicTransport(state, onPanel)
            }
          }
        } else {
          Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(state, Modifier.weight(1f, fill = false).padding(vertical = 16.dp))
            TrackInfo(state, artist, album, queue.currentIndex, queue.items.size)
            Spacer(Modifier.height(16.dp))
            MusicTransport(state, onPanel)
          }
        }
      }
    }
  }
}

@Composable
private fun Artwork(
  state: PlayerState,
  modifier: Modifier,
) {
  val deps = LocalAppDependencies.current
  Box(
    modifier.aspectRatio(1f).clip(RoundedCornerShape(40.dp)).background(
      Brush.linearGradient(listOf(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.primaryContainer)),
    ),
    contentAlignment = Alignment.Center,
  ) {
    if (state.hasVideo) {
      deps.videoSurface(Modifier.fillMaxSize())
    } else {
      Icon(Icons.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(96.dp))
    }
  }
}

@Composable
private fun TrackInfo(
  state: PlayerState,
  artist: String?,
  album: String?,
  index: Int,
  count: Int,
) {
  val deps = LocalAppDependencies.current
  Column(Modifier.fillMaxWidth()) {
    Text(state.mediaTitle, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
      listOfNotNull(artist, album).joinToString(" · ").ifBlank { "Unknown artist" },
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (count > 0) {
        Text("Track ${index + 1}/$count", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  |  ", color = MaterialTheme.colorScheme.outline)
      }
      Row(
        Modifier.clip(RoundedCornerShape(50)).clickable { deps.playback.setSpeed(if (state.speed == 1.0) 1.25 else 1.0) }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(Icons.Speed, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text("${state.speed}x", style = MaterialTheme.typography.titleSmall)
      }
    }
    Spacer(Modifier.height(16.dp))
    PlayerSeekbar(
      position = state.positionSeconds,
      duration = state.durationSeconds,
      buffered = state.bufferedSeconds,
      chapters = state.chapters,
      style = SeekbarStyle.Standard,
      showChapters = true,
      showBuffered = false,
      onSeek = { deps.engine.seekTo(it) },
      onSeekPreview = {},
    )
    Row(Modifier.fillMaxWidth()) {
      Text(formatDuration(state.positionSeconds), style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.weight(1f))
      Text("-" + formatDuration(state.durationSeconds - state.positionSeconds), style = MaterialTheme.typography.labelLarge)
    }
  }
}

@Composable
private fun MusicTransport(
  state: PlayerState,
  onPanel: (PlayerPanel) -> Unit,
) {
  val deps = LocalAppDependencies.current
  val queue by deps.playback.queue.state.collectAsState()
  Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { deps.playback.previous() }) { Icon(Icons.SkipPrevious, "Previous", Modifier.size(32.dp)) }
      IconButton(onClick = { deps.engine.seekBy(-10.0) }) { Icon(Icons.FastRewind, "Rewind", Modifier.size(32.dp)) }
      Box(
        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { deps.playback.togglePlayPause() },
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          if (state.isPaused || state.isIdle) Icons.PlayArrow else Icons.Pause,
          if (state.isPaused) "Play" else "Pause",
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(48.dp),
        )
      }
      IconButton(onClick = { deps.engine.seekBy(10.0) }) { Icon(Icons.FastForward, "Forward", Modifier.size(32.dp)) }
      IconButton(onClick = { deps.playback.next() }) { Icon(Icons.SkipNext, "Next", Modifier.size(32.dp)) }
    }
    Spacer(Modifier.height(20.dp))
    Row(
      Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      IconButton(onClick = { deps.playback.toggleShuffle() }) {
        Icon(if (queue.shuffle) Icons.ShuffleOn else Icons.Shuffle, "Shuffle", tint = if (queue.shuffle) MaterialTheme.colorScheme.primary else Color.Unspecified)
      }
      IconButton(onClick = { deps.playback.cycleRepeatMode() }) {
        Icon(
          when (queue.repeatMode) {
            RepeatMode.OFF -> Icons.Repeat
            RepeatMode.ALL -> Icons.RepeatOn
            RepeatMode.ONE -> Icons.RepeatOne
          },
          "Repeat",
          tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else Color.Unspecified,
        )
      }
      IconButton(onClick = { onPanel(PlayerPanel.Audio) }) { Icon(Icons.MusicNote, "Audio tracks") }
      IconButton(onClick = { onPanel(PlayerPanel.Speed) }) { Icon(Icons.Speed, "Speed") }
      IconButton(onClick = { deps.engine.setMuted(!state.isMuted) }) { Icon(if (state.isMuted) Icons.VolumeOff else Icons.VolumeUp, "Mute") }
      IconButton(onClick = { onPanel(PlayerPanel.Playlist) }) { Icon(Icons.QueueMusic, "Queue") }
    }
  }
}
