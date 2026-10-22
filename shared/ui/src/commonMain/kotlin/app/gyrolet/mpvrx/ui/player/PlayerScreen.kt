package app.gyrolet.mpvrx.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.gyrolet.mpvrx.core.player.AspectMode
import app.gyrolet.mpvrx.core.player.PlayerState
import app.gyrolet.mpvrx.core.player.TrackType
import app.gyrolet.mpvrx.core.preferences.VideoAspect
import app.gyrolet.mpvrx.ui.navigation.LocalNavigator
import app.gyrolet.mpvrx.ui.platform.AppDependencies
import app.gyrolet.mpvrx.ui.platform.LocalAppDependencies
import app.gyrolet.mpvrx.ui.theme.PlayerTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Clock

@Composable
fun PlayerScreen() {
  val deps = LocalAppDependencies.current
  val navigator = LocalNavigator.current
  val state by deps.playback.playerState.collectAsState()
  val fullscreen by deps.platform.isFullscreen.collectAsState()
  val focus = remember { FocusRequester() }
  var panel by remember { mutableStateOf(PlayerPanel.None) }
  var controlsVisible by remember { mutableStateOf(true) }
  var lastInteraction by remember { mutableLongStateOf(0L) }
  var pointerOverControls by remember { mutableStateOf(false) }
  var toast by remember { mutableStateOf<String?>(null) }
  var aspect by remember {
    mutableStateOf<AspectMode>(
      when (deps.settings.player.lastVideoAspect.get()) {
        VideoAspect.Fit -> AspectMode.Fit
        VideoAspect.Crop -> AspectMode.Crop
        VideoAspect.Stretch -> AspectMode.Stretch
      },
    )
  }
  var zoom by remember { mutableFloatStateOf(0f) }

  val showControls = {
    controlsVisible = true
    lastInteraction = Clock.System.now().toEpochMilliseconds()
  }
  val close = {
    if (fullscreen) deps.platform.setFullscreen(false)
    if (!(state.isAudioOnly && !state.isPaused)) deps.playback.stop()
    navigator.popPlayer()
  }

  LaunchedEffect(Unit) {
    focus.requestFocus()
    if (deps.settings.player.openInFullscreen.get() && !state.isAudioOnly) deps.platform.setFullscreen(true)
    if (deps.settings.player.alwaysOnTopWhilePlaying.get()) deps.platform.setAlwaysOnTop(true)
  }
  DisposableEffect(Unit) {
    onDispose { if (deps.settings.player.alwaysOnTopWhilePlaying.get()) deps.platform.setAlwaysOnTop(false) }
  }
  LaunchedEffect(aspect) {
    deps.engine.setVideoAspect(aspect)
    when (aspect) {
      AspectMode.Fit -> VideoAspect.Fit
      AspectMode.Crop -> VideoAspect.Crop
      AspectMode.Stretch -> VideoAspect.Stretch
      is AspectMode.Ratio -> null
    }?.let(deps.settings.player.lastVideoAspect::set)
  }
  LaunchedEffect(zoom) { deps.engine.setVideoZoom(zoom.toDouble()) }
  LaunchedEffect(Unit) {
    deps.playback.toasts.collect {
      toast = it
    }
  }
  LaunchedEffect(toast) {
    if (toast != null) {
      delay(1600)
      toast = null
    }
  }
  LaunchedEffect(Unit) { deps.playback.closeRequests.collect { close() } }
  LaunchedEffect(lastInteraction, state.isPaused, state.isIdle, panel, pointerOverControls) {
    if (!state.isPaused && !state.isIdle && panel == PlayerPanel.None && !pointerOverControls) {
      delay(deps.settings.player.playerTimeToDisappear.get().toLong())
      controlsVisible = false
    }
  }

  PlayerTheme {
    Box(
      Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
          showControls()
          handlePlayerKey(event, deps, state, fullscreen, panel, onPanelClose = { panel = PlayerPanel.None }, onClose = close, onToast = { toast = it }, aspect = aspect, onAspect = { aspect = it })
        }.pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                PointerEventType.Move -> showControls()
                PointerEventType.Scroll -> {
                  val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                  if (dy != 0f) deps.playback.changeVolumeBy(if (dy < 0) 5 else -5)
                  event.changes.forEach { it.consume() }
                }
              }
            }
          }
        }.then(
          if (!controlsVisible && panel == PlayerPanel.None) {
            deps.platform.hiddenPointerIcon?.let { Modifier.pointerHoverIcon(it) } ?: Modifier
          } else {
            Modifier
          },
        ),
    ) {
      if (state.isAudioOnly) {
        MusicPlayerLayout(state = state, onCollapse = close, onPanel = { panel = it })
      } else {
        deps.videoSurface(
          Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
              onTap = {
                focus.requestFocus()
                if (panel != PlayerPanel.None) panel = PlayerPanel.None else deps.playback.togglePlayPause()
                showControls()
              },
              onDoubleTap = { deps.platform.setFullscreen(!deps.platform.isFullscreen.value) },
            )
          },
        )

        if (state.isBuffering && deps.settings.player.showLoadingCircle.get()) {
          LoadingIndicator(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
          visible = controlsVisible || state.isPaused || state.isIdle || panel != PlayerPanel.None,
          enter = fadeIn() + slideInVertically { it / 12 },
          exit = fadeOut() + slideOutVertically { it / 12 },
          modifier = Modifier.fillMaxSize(),
        ) {
          VideoControls(
            state = state,
            fullscreen = fullscreen,
            onBack = close,
            onPanel = { panel = if (panel == it) PlayerPanel.None else it },
            onPointerOverControls = { pointerOverControls = it },
            onAspectCycle = { aspect = nextAspect(aspect).also { toast = "Aspect: ${it.label()}" } },
            onScreenshot = {
              val ok = deps.engine.screenshot(deps.platform.screenshotDirectory, includeSubtitles = true)
              toast = if (ok) "Screenshot saved" else "Screenshot failed"
            },
          )
        }
      }

      AnimatedVisibility(
        visible = panel != PlayerPanel.None,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier.align(Alignment.CenterEnd),
      ) {
        val closePanel = { panel = PlayerPanel.None }
        when (panel) {
          PlayerPanel.Audio -> TracksPanel(TrackType.Audio, state, closePanel)
          PlayerPanel.Subtitles -> TracksPanel(TrackType.Subtitle, state, closePanel)
          PlayerPanel.Speed -> SpeedPanel(state.speed, closePanel)
          PlayerPanel.Chapters -> ChaptersPanel(state, closePanel)
          PlayerPanel.Playlist -> PlaylistPanel(closePanel)
          PlayerPanel.Aspect -> AspectPanel(aspect, zoom, { aspect = it }, { zoom = it }, closePanel)
          PlayerPanel.None -> Box(Modifier)
        }
      }

      val message = toast ?: state.errorMessage
      AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
      ) {
        Text(
          message.orEmpty(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          modifier =
            Modifier
              .clip(RoundedCornerShape(50))
              .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f))
              .padding(horizontal = 20.dp, vertical = 10.dp),
        )
      }
    }
  }
}

private fun nextAspect(current: AspectMode): AspectMode =
  when (current) {
    AspectMode.Fit -> AspectMode.Crop
    AspectMode.Crop -> AspectMode.Stretch
    AspectMode.Stretch -> AspectMode.Ratio(16.0 / 9.0, "16:9")
    is AspectMode.Ratio -> if (current.label == "16:9") AspectMode.Ratio(4.0 / 3.0, "4:3") else AspectMode.Fit
  }

private fun AspectMode.label(): String =
  when (this) {
    AspectMode.Fit -> "Fit"
    AspectMode.Crop -> "Crop"
    AspectMode.Stretch -> "Stretch"
    is AspectMode.Ratio -> label
  }

private fun cycleTrack(deps: AppDependencies, state: PlayerState, type: TrackType): String {
  val tracks = if (type == TrackType.Audio) state.audioTracks else state.subtitleTracks
  if (tracks.isEmpty()) return "No ${if (type == TrackType.Audio) "audio" else "subtitle"} tracks"
  val ids: List<Int?> = (if (type == TrackType.Subtitle) listOf<Int?>(null) else emptyList()) + tracks.map { it.id }
  val current = tracks.firstOrNull { it.isSelected }?.id
  val next = ids[(ids.indexOf(current) + 1) % ids.size]
  deps.engine.selectTrack(type, next)
  val label = tracks.firstOrNull { it.id == next }?.label ?: "Off"
  return "${if (type == TrackType.Audio) "Audio" else "Subtitles"}: $label"
}

private fun handlePlayerKey(
  event: KeyEvent,
  deps: AppDependencies,
  state: PlayerState,
  fullscreen: Boolean,
  panel: PlayerPanel,
  onPanelClose: () -> Unit,
  onClose: () -> Unit,
  onToast: (String) -> Unit,
  aspect: AspectMode,
  onAspect: (AspectMode) -> Unit,
): Boolean {
  val engine = deps.engine
  val step = deps.settings.player.doubleTapSeekDuration.get().toDouble()
  when (event.key) {
    Key.Spacebar, Key.K, Key.MediaPlayPause -> deps.playback.togglePlayPause()
    Key.DirectionLeft -> engine.seekBy(if (event.isShiftPressed) -60.0 else -step)
    Key.DirectionRight -> engine.seekBy(if (event.isShiftPressed) 60.0 else step)
    Key.DirectionUp -> deps.playback.changeVolumeBy(5)
    Key.DirectionDown -> deps.playback.changeVolumeBy(-5)
    Key.M -> engine.setMuted(!state.isMuted).also { onToast(if (state.isMuted) "Unmuted" else "Muted") }
    Key.F, Key.F11 -> deps.platform.setFullscreen(!fullscreen)
    Key.Escape ->
      when {
        panel != PlayerPanel.None -> onPanelClose()
        fullscreen -> deps.platform.setFullscreen(false)
        else -> onClose()
      }
    Key.N, Key.PageDown, Key.MediaNext -> deps.playback.next()
    Key.P, Key.PageUp, Key.MediaPrevious -> deps.playback.previous()
    Key.LeftBracket -> deps.playback.setSpeed(state.speed - 0.1).also { onToast("Speed ${((state.speed - 0.1) * 10).roundToInt() / 10.0}x") }
    Key.RightBracket -> deps.playback.setSpeed(state.speed + 0.1).also { onToast("Speed ${((state.speed + 0.1) * 10).roundToInt() / 10.0}x") }
    Key.Backspace -> deps.playback.setSpeed(1.0).also { onToast("Speed 1.0x") }
    Key.J -> onToast(cycleTrack(deps, state, if (event.isShiftPressed) TrackType.Audio else TrackType.Subtitle))
    Key.Z -> engine.setSubtitleDelay(state.subtitleDelaySeconds - 0.1).also { onToast("Subtitle delay ${((state.subtitleDelaySeconds - 0.1) * 1000).roundToInt()}ms") }
    Key.X -> engine.setSubtitleDelay(state.subtitleDelaySeconds + 0.1).also { onToast("Subtitle delay ${((state.subtitleDelaySeconds + 0.1) * 1000).roundToInt()}ms") }
    Key.Period -> engine.frameStep(true)
    Key.Comma -> engine.frameStep(false)
    Key.L ->
      when {
        state.abLoopA == null -> engine.setAbLoop(state.positionSeconds, null).also { onToast("Loop A set") }
        state.abLoopB == null -> engine.setAbLoop(state.abLoopA, state.positionSeconds).also { onToast("A-B loop on") }
        else -> engine.setAbLoop(null, null).also { onToast("A-B loop cleared") }
      }
    Key.S -> {
      val ok = engine.screenshot(deps.platform.screenshotDirectory, includeSubtitles = !event.isShiftPressed)
      onToast(if (ok) "Screenshot saved" else "Screenshot failed")
    }
    Key.A -> onAspect(nextAspect(aspect).also { onToast("Aspect: ${it.label()}") })
    Key.T -> {
      val enabled = !deps.platform.isAlwaysOnTop.value
      deps.platform.setAlwaysOnTop(enabled)
      onToast(if (enabled) "Always on top" else "Always on top off")
    }
    Key.I -> deps.platform.setMiniPlayer(!deps.platform.isMiniPlayer.value)
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine -> {
      val digit = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(event.key)
      if (state.durationSeconds > 0) engine.seekTo(state.durationSeconds * digit / 10.0)
    }
    else -> return false
  }
  return true
}
