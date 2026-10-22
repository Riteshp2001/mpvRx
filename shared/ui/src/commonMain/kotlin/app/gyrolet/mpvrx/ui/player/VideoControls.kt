package app.gyrolet.mpvrx.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.util.formatDuration
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.util.collectAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Edge vignette from the Android controls so buttons stay legible on bright frames. */
private val ControlsEdgeVignetteBrush =
  Brush.verticalGradient(
    0.00f to Color.Black.copy(alpha = 0.55f),
    0.06f to Color.Black.copy(alpha = 0.38f),
    0.14f to Color.Black.copy(alpha = 0.18f),
    0.22f to Color.Transparent,
    0.62f to Color.Transparent,
    0.74f to Color.Black.copy(alpha = 0.16f),
    0.86f to Color.Black.copy(alpha = 0.36f),
    0.95f to Color.Black.copy(alpha = 0.52f),
    1.00f to Color.Black.copy(alpha = 0.58f),
  )

@Composable
fun VideoControls(
  state: PlayerState,
  fullscreen: Boolean,
  onBack: () -> Unit,
  onPanel: (PlayerPanel) -> Unit,
  onPointerOverControls: (Boolean) -> Unit,
  onAspectCycle: () -> Unit,
  onScreenshot: () -> Unit,
) {
  val deps = LocalAppDependencies.current
  val queue by deps.playback.queue.state.collectAsState()
  val invertDuration by deps.settings.player.invertDuration.collectAsState()
  val seekbarStyle by deps.settings.appearance.seekbarStyle.collectAsState()
  val showChapters by deps.settings.player.showChapterIndicators.collectAsState()
  val showBuffered by deps.settings.player.showBufferedRange.collectAsState()
  val skip by deps.settings.player.customSkipDuration.collectAsState()
  var preview by remember { mutableStateOf<Double?>(null) }
  var menu by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val hoverTracker =
    Modifier.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          when (awaitPointerEvent().type) {
            PointerEventType.Enter -> onPointerOverControls(true)
            PointerEventType.Exit -> onPointerOverControls(false)
          }
        }
      }
    }

  Box(Modifier.fillMaxSize().background(ControlsEdgeVignetteBrush)) {
    // Top bar
    Row(
      Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 24.dp, vertical = 20.dp).then(hoverTracker),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      PlayerIconButton(Icons.ArrowBack, "Back", onBack)
      val position = if (queue.items.size > 1) " • ${queue.currentIndex + 1}/${queue.items.size}" else ""
      PlayerTextPill(
        text = state.mediaTitle.ifBlank { queue.current?.title.orEmpty() } + position,
        modifier = Modifier.weight(1f, fill = false).widthIn(max = 760.dp),
      )
      Spacer(Modifier.weight(0.01f))
      PlayerPill(onClick = { deps.settings.decoder.tryHWDecoding.set(!deps.settings.decoder.tryHWDecoding.get()) }) {
        val hw = state.hwdecCurrent?.takeIf { it.isNotBlank() && it != "no" }
        Text(if (hw != null) "HW+" else "SW", style = MaterialTheme.typography.titleMedium)
      }
      PlayerIconButton(Icons.Audiotrack, "Audio tracks", { onPanel(PlayerPanel.Audio) })
      PlayerIconButton(Icons.Subtitles, "Subtitles", { onPanel(PlayerPanel.Subtitles) })
      Box {
        PlayerIconButton(Icons.MoreVert, "More", { menu = true })
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
          val onTop by deps.platform.isAlwaysOnTop.collectAsState()
          DropdownMenuItem(
            text = { Text("Always on top") },
            leadingIcon = { Icon(Icons.PushPin, null) },
            trailingIcon = { if (onTop) Icon(Icons.Check, null) },
            onClick = { deps.platform.setAlwaysOnTop(!onTop); menu = false },
          )
          DropdownMenuItem(
            text = { Text("Picture-in-picture") },
            leadingIcon = { Icon(Icons.PictureInPicture, null) },
            onClick = { deps.platform.setMiniPlayer(true); menu = false },
          )
          DropdownMenuItem(
            text = { Text("Load subtitle file…") },
            leadingIcon = { Icon(Icons.Subtitles, null) },
            onClick = {
              menu = false
              scope.launch { deps.platform.pickSubtitle()?.let(deps.engine::addSubtitle) }
            },
          )
          DropdownMenuItem(
            text = { Text("Repeat: ${queue.repeatMode.name.lowercase()}") },
            leadingIcon = { Icon(if (queue.repeatMode.name == "ONE") Icons.RepeatOne else Icons.Repeat, null) },
            onClick = { deps.playback.cycleRepeatMode() },
          )
          DropdownMenuItem(
            text = { Text("Shuffle") },
            leadingIcon = { Icon(Icons.Shuffle, null) },
            trailingIcon = { if (queue.shuffle) Icon(Icons.Check, null) },
            onClick = { deps.playback.toggleShuffle() },
          )
          state.path?.takeIf { !it.contains("://") }?.let { path ->
            DropdownMenuItem(
              text = { Text("Show in folder") },
              leadingIcon = { Icon(Icons.FolderOpen, null) },
              onClick = { deps.platform.revealInFileManager(path); menu = false },
            )
          }
          DropdownMenuItem(
            text = { Text("Open screenshot folder") },
            leadingIcon = { Icon(Icons.Screenshot, null) },
            onClick = { deps.platform.revealInFileManager(deps.platform.screenshotDirectory); menu = false },
          )
        }
      }
    }

    // Center transport
    Row(
      Modifier.align(Alignment.Center).then(hoverTracker),
      horizontalArrangement = Arrangement.spacedBy(28.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PlayerIconButton(Icons.SkipPrevious, "Previous", { deps.playback.previous() }, size = 72.dp, enabled = queue.hasPrevious || state.positionSeconds > 3)
      val showPlay = state.isPaused || state.isIdle
      PlayerIconButton(if (showPlay) Icons.PlayArrow else Icons.Pause, if (showPlay) "Play" else "Pause", { deps.playback.togglePlayPause() }, size = 88.dp)
      PlayerIconButton(Icons.SkipNext, "Next", { deps.playback.next() }, size = 72.dp, enabled = queue.hasNext)
    }

    // Bottom area
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).then(hoverTracker)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayerIconButton(Icons.Speed, "Speed ${state.speed}x", { onPanel(PlayerPanel.Speed) }, active = state.speed != 1.0)
        PlayerIconButton(
          Icons.Repeat,
          "A-B loop",
          {
            val engine = deps.engine
            when {
              state.abLoopA == null -> engine.setAbLoop(state.positionSeconds, null)
              state.abLoopB == null -> engine.setAbLoop(state.abLoopA, state.positionSeconds)
              else -> engine.setAbLoop(null, null)
            }
          },
          active = state.abLoopA != null,
        )
        ClockPill(state)
        if (state.chapters.isNotEmpty()) {
          PlayerTextPill(
            text = state.chapterTitle?.ifBlank { null } ?: "Chapter ${state.currentChapter + 1}",
            accent = formatDuration(state.chapters.getOrNull(state.currentChapter)?.startSeconds ?: 0.0),
            onClick = { onPanel(PlayerPanel.Chapters) },
            modifier = Modifier.widthIn(max = 360.dp),
          )
        }
        Spacer(Modifier.weight(1f))
        PlayerIconButton(Icons.FastForward, "Skip ${skip}s", { deps.engine.seekBy(skip.toDouble()) })
        PlayerIconButton(Icons.PlaylistPlay, "Playlist", { onPanel(PlayerPanel.Playlist) })
        PlayerIconButton(Icons.AspectRatio, "Aspect ratio", onAspectCycle)
        PlayerIconButton(Icons.ZoomIn, "Zoom", { onPanel(PlayerPanel.Aspect) })
        PlayerIconButton(Icons.Screenshot, "Screenshot", onScreenshot)
        PlayerIconButton(if (fullscreen) Icons.FullscreenExit else Icons.Fullscreen, "Fullscreen", { deps.platform.setFullscreen(!fullscreen) })
      }
      Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TimeLabel(formatDuration(preview ?: state.positionSeconds))
        Spacer(Modifier.width(16.dp))
        PlayerSeekbar(
          position = state.positionSeconds,
          duration = state.durationSeconds,
          buffered = state.bufferedSeconds,
          chapters = state.chapters,
          style = seekbarStyle,
          showChapters = showChapters,
          showBuffered = showBuffered,
          onSeek = { deps.engine.seekTo(it, precise = true) },
          onSeekPreview = { preview = it },
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        TimeLabel(
          if (invertDuration) "-" + formatDuration(state.durationSeconds - state.positionSeconds) else formatDuration(state.durationSeconds),
          onClick = { deps.settings.player.invertDuration.set(!invertDuration) },
        )
      }
    }
  }
}

@Composable
private fun TimeLabel(
  text: String,
  onClick: (() -> Unit)? = null,
) {
  Text(
    text,
    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
    color = Color.White,
    modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
  )
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ClockPill(state: PlayerState) {
  var now by remember { mutableStateOf(Clock.System.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      now = Clock.System.now()
      delay(10_000)
    }
  }
  val time = remember(now) { localClockText(now.toEpochMilliseconds()) }
  val cache = if (state.bufferedSeconds > state.positionSeconds && state.path?.contains("://") == true) {
    " • ${(state.bufferedSeconds - state.positionSeconds).roundToInt()}s cached"
  } else {
    ""
  }
  PlayerTextPill(text = "$time • ${state.volume}%$cache", leading = Icons.Schedule)
}

/** Local wall-clock "HH:mm" without platform date APIs. */
expect fun localClockText(epochMillis: Long): String
