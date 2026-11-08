/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.ContentResolver
import android.content.Context
import android.media.AudioManager
import android.view.Window
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.ui.background.BackdropGate
import app.gyrolet.mpvrx.preferences.AudioPlayerOrientation
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.controls.AudioPlayerScreen
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.utils.device.DeviceFormFactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Mini player <-> full music player as one live sheet inside the browser.
 *
 * The music player is composed in the same window as the mini player, so expanding it is a single
 * draw-phase animation over real content: no Activity launch, no snapshot and no hand-off fade.
 * The sheet grows out of the mini player's bounds, the live mini player rides its top edge while
 * fading out, and the live full player fades in as the sheet reaches full screen. Every frame only
 * moves layers, so the motion never waits on composition, layout or the playback core.
 *
 * MediaPlaybackService stays the playback owner the whole time, exactly as while only the mini
 * player shows; the sheet adds a full-screen view of that session and nothing more.
 */

val LocalInAppMusicSheet = staticCompositionLocalOf<InAppMusicSheetState?> { null }

@Stable
class InAppMusicSheetState internal constructor(
  private val scope: CoroutineScope,
) {
  private val animatedExpansion = Animatable(0f).apply { updateBounds(0f, 1f) }
  private var dragExpansion by mutableFloatStateOf(Float.NaN)
  private var motionJob: Job? = null
  private var releaseJob: Job? = null
  private var backStart = 1f

  /** 0 rests on the mini player, 1 fills the window. */
  val expansion: Float
    get() = dragExpansion.takeUnless(Float::isNaN) ?: animatedExpansion.value

  /** Opening or open; false while the sheet collapses or rests on the mini player. */
  var isOpen by mutableStateOf(false)
    private set

  /** False on devices and states that keep using the player screen (TV, video, no session). */
  var isAvailable by mutableStateOf(false)
    internal set

  internal val isDragging: Boolean
    get() = !dragExpansion.isNaN()

  private val visibleState = derivedStateOf { isOpen || isDragging || expansion > 0f }
  private val settledOpenState = derivedStateOf { isOpen && !isDragging && expansion >= 1f }
  private val miniHiddenState = derivedStateOf { isDragging || expansion > 0f }

  /** The sheet is on screen. */
  internal val isVisible: Boolean
    get() = visibleState.value

  /** The sheet rests full screen and nothing under it can be seen or reached. */
  val isSettledOpen: Boolean
    get() = settledOpenState.value

  /** The mini player is drawn by the sheet, riding its top edge. */
  internal val isMiniHidden: Boolean
    get() = miniHiddenState.value

  /** The full player is composed: while visible, plus briefly before and after it. */
  internal var isContentRequested by mutableStateOf(false)
    private set
  internal var isContentReady by mutableStateOf(false)

  // Geometry in root coordinates, recorded during layout so the first moving frame is placed.
  internal var miniBounds by mutableStateOf(Rect.Zero)
  internal var miniCornerRadius by mutableFloatStateOf(0f)
  internal var miniLayer: GraphicsLayer? = null
  internal var hostOrigin by mutableStateOf(Offset.Zero)
  internal var hostSize by mutableStateOf(Size.Zero)
  internal var reduceMotion = false

  internal fun miniRect(): Rect = if (miniBounds.isEmpty) Rect.Zero else miniBounds.translate(-hostOrigin)

  /** Distance the sheet's top edge covers between the mini player and full screen. */
  private val travel: Float
    get() {
      val mini = miniRect()
      return (if (mini.isEmpty) hostSize.height else mini.top).coerceAtLeast(1f)
    }

  internal fun sheetRect(fraction: Float, size: Size): Rect {
    val full = Rect(Offset.Zero, size)
    val mini = miniRect()
    if (mini.isEmpty) return Rect(0f, size.height * (1f - fraction), size.width, size.height)
    return lerp(mini, full, fraction)
  }

  internal fun sheetCornerRadius(fraction: Float): Float = miniCornerRadius * (1f - fraction.coerceIn(0f, 1f))

  fun expand(): Boolean {
    if (!isAvailable) return false
    isOpen = true
    requestContent()
    animateTo(1f, 0f)
    return true
  }

  fun collapse(): Boolean {
    if (!isVisible) return false
    isOpen = false
    animateTo(0f, 0f)
    return true
  }

  /** Starts composing the full player ahead of a likely expansion, e.g. a finger on the mini player. */
  fun prewarm() {
    if (!isAvailable) return
    requestContent()
    if (!isVisible) scheduleRelease(PREWARM_TIMEOUT_MS)
  }

  /** Closes at once and drops the full player, for when the session ends or leaves the browser. */
  internal fun dismiss() {
    motionJob?.cancel()
    releaseJob?.cancel()
    isOpen = false
    motionJob = scope.launch {
      animatedExpansion.snapTo(0f)
      dragExpansion = Float.NaN
      isContentRequested = false
      isContentReady = false
    }
  }

  internal fun startDrag() {
    motionJob?.cancel()
    requestContent()
    dragExpansion = expansion
  }

  internal fun dragBy(deltaY: Float) {
    if (isDragging) dragExpansion = (dragExpansion - deltaY / travel).coerceIn(0f, 1f)
  }

  /** Settles on the anchor the release is heading to, carrying the finger's speed. */
  internal fun endDrag(velocityY: Float) {
    if (!isDragging) return
    val velocity = -velocityY / travel
    isOpen = dragExpansion + velocity * PROJECTION_SECONDS >= 0.5f
    animateTo(if (isOpen) 1f else 0f, velocity)
  }

  internal fun startBack() {
    startDrag()
    backStart = dragExpansion
  }

  /** Predictive back previews the collapse by lowering the sheet a little. */
  internal fun backProgress(progress: Float) {
    if (!isDragging) return
    val eased = 1f - (1f - progress.coerceIn(0f, 1f)).let { it * it }
    dragExpansion = backStart * (1f - BACK_PREVIEW_FRACTION * eased)
  }

  internal fun endBack(committed: Boolean) {
    isOpen = !committed
    animateTo(if (committed) 0f else 1f, 0f)
  }

  private fun requestContent() {
    releaseJob?.cancel()
    releaseJob = null
    isContentRequested = true
  }

  private fun scheduleRelease(delayMs: Long) {
    releaseJob?.cancel()
    releaseJob = scope.launch {
      delay(delayMs)
      if (!isVisible) {
        isContentRequested = false
        isContentReady = false
      }
    }
  }

  private fun animateTo(target: Float, velocity: Float) {
    motionJob?.cancel()
    val waitForContent = target > 0f && !isContentReady && expansion <= 0f
    motionJob = scope.launch {
      if (waitForContent) {
        // Move only once the player has composed and drawn off-screen, so the first frames of the
        // motion carry no composition work.
        withTimeoutOrNull(CONTENT_TIMEOUT_MS) { snapshotFlow { isContentReady }.first { it } }
        withFrameNanos { }
      }
      animatedExpansion.snapTo(expansion)
      dragExpansion = Float.NaN
      if (reduceMotion) {
        animatedExpansion.snapTo(target)
      } else {
        animatedExpansion.animateTo(target, SheetSpring, velocity)
      }
      // Keep the player warm briefly, so reopening right after a collapse is instant.
      if (target <= 0f) scheduleRelease(KEEP_WARM_MS)
    }
  }

  private companion object {
    const val PREWARM_TIMEOUT_MS = 3_000L
    const val KEEP_WARM_MS = 4_000L
    const val CONTENT_TIMEOUT_MS = 1_200L
    const val PROJECTION_SECONDS = 0.18f
    const val BACK_PREVIEW_FRACTION = 0.22f
  }
}

private val SheetSpring = spring(
  dampingRatio = Spring.DampingRatioNoBouncy,
  stiffness = 420f,
  visibilityThreshold = 0.001f,
)

@Composable
fun rememberInAppMusicSheetState(): InAppMusicSheetState {
  val scope = rememberCoroutineScope()
  return remember(scope) { InAppMusicSheetState(scope) }
}

/**
 * Lets the sheet draw the live mini player on its moving top edge. The mini player keeps its
 * touch handling in place; only where it is drawn changes while the sheet is up.
 */
@Composable
fun Modifier.inAppMusicSheetMiniSource(state: InAppMusicSheetState?, shape: Shape): Modifier {
  if (state == null) return this
  val layer = rememberGraphicsLayer()
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  DisposableEffect(state, layer) {
    state.miniLayer = layer
    onDispose {
      if (state.miniLayer === layer) {
        state.miniLayer = null
        state.miniBounds = Rect.Zero
      }
    }
  }
  return this
    .onGloballyPositioned { coordinates ->
      val bounds = coordinates.boundsInRoot()
      state.miniBounds = bounds
      val outline = shape.createOutline(bounds.size, direction, density)
      state.miniCornerRadius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    }
    .graphicsLayer { alpha = if (state.isMiniHidden) 0f else 1f }
    .drawWithContent {
      layer.record { this@drawWithContent.drawContent() }
      drawLayer(layer)
    }
    .then(InAppMusicSheetCoverElement(state))
}

/** Hides covered browser content from accessibility while the sheet rests full screen. */
fun Modifier.coveredByInAppMusicSheet(state: InAppMusicSheetState?): Modifier =
  if (state == null) {
    this
  } else {
    this
      .graphicsLayer { alpha = if (state.isSettledOpen) 0f else 1f }
      .then(InAppMusicSheetCoverElement(state))
  }

private data class InAppMusicSheetCoverElement(
  val state: InAppMusicSheetState,
) : ModifierNodeElement<InAppMusicSheetCoverNode>() {
  override fun create() = InAppMusicSheetCoverNode(state)

  override fun update(node: InAppMusicSheetCoverNode) {
    node.update(state)
  }
}

private class InAppMusicSheetCoverNode(
  private var state: InAppMusicSheetState,
) : Modifier.Node(),
  SemanticsModifierNode,
  ObserverModifierNode {
  private var covered = false

  override val shouldClearDescendantSemantics: Boolean
    get() = covered

  override fun SemanticsPropertyReceiver.applySemantics() = Unit

  override fun onAttach() {
    observeCover()
  }

  override fun onObservedReadsChanged() {
    observeCover()
  }

  fun update(next: InAppMusicSheetState) {
    if (state === next) return
    state = next
    observeCover()
  }

  private fun observeCover() {
    var next = false
    observeReads { next = state.isSettledOpen }
    if (next != covered) {
      covered = next
      invalidateSemantics()
    }
  }
}

/**
 * Vertical drag that drives the sheet. Positions are tracked in root coordinates because the full
 * player moves with the finger, which would cancel out any delta measured in its own space.
 */
@Composable
internal fun Modifier.inAppMusicSheetDrag(
  state: InAppMusicSheetState,
  collapsing: Boolean,
  enabled: Boolean,
  allowBothDirections: Boolean = false,
  onDown: (() -> Unit)? = null,
): Modifier {
  val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
  val currentOnDown by rememberUpdatedState(onDown)
  return this
    .onPlaced { coordinates[0] = it }
    .pointerInput(state, collapsing, enabled, allowBothDirections) {
      if (!enabled) return@pointerInput
      fun rootY(change: PointerInputChange): Float =
        coordinates[0]?.takeIf { it.isAttached }?.localToRoot(change.position)?.y ?: change.position.y

      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        currentOnDown?.invoke()
        var overSlop = 0f
        val slopChange =
          awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            val towardTarget = if (collapsing) over > 0f else over < 0f
            if (allowBothDirections || towardTarget) {
              change.consume()
              overSlop = over
            }
          } ?: return@awaitEachGesture

        state.startDrag()
        state.dragBy(overSlop)
        val tracker = VelocityTracker()
        var lastY = rootY(slopChange)
        tracker.addPosition(slopChange.uptimeMillis, Offset(0f, lastY))
        var released = false
        try {
          while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == slopChange.id } ?: break
            if (!change.pressed) {
              change.consume()
              released = true
              state.endDrag(tracker.calculateVelocity().y)
              break
            }
            val y = rootY(change)
            state.dragBy(y - lastY)
            lastY = y
            tracker.addPosition(change.uptimeMillis, Offset(0f, y))
            change.consume()
          }
        } finally {
          // A lost or cancelled pointer settles on the nearest anchor instead of freezing mid-way.
          if (!released) state.endDrag(0f)
        }
      }
    }
}

/**
 * Hosts the live music sheet above the browser. Place it after the browser content so it draws
 * on top and its back handling takes precedence.
 */
@Composable
fun InAppMusicPlayerSheet(
  state: InAppMusicSheetState,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val sessionState by PlaybackSession.state.collectAsStateWithLifecycle()
  val serviceOwnsPlayback by MediaPlaybackService.foregroundActive.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val lifecycleState by lifecycle.currentStateFlow.collectAsState()
  val store = viewModel<InAppMusicPlayerStore>()
  val isTelevision = remember(context) { DeviceFormFactor.isTelevision(context) }
  val currentItem = sessionState.currentItem
  val canShow =
    !isTelevision &&
      serviceOwnsPlayback &&
      currentItem != null &&
      currentItem.declaredMediaKind() != DeclaredPlaybackMediaKind.VIDEO &&
      sessionState.phase in ShowablePhases
  state.reduceMotion = AppMotion.shouldReduceMotion()
  SideEffect { state.isAvailable = canShow }

  // Another screen took the session over, or playback ended: nothing is left to show.
  LaunchedEffect(canShow) {
    if (!canShow) state.dismiss()
  }
  LaunchedEffect(canShow, state.isContentRequested) {
    if (!canShow && !state.isContentRequested) store.release()
  }

  val shownInApp = state.isOpen && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
  DisposableEffect(shownInApp) {
    MediaPlaybackService.setInAppPlayerVisible(shownInApp)
    onDispose { MediaPlaybackService.setInAppPlayerVisible(false) }
  }
  val covering = state.isSettledOpen
  DisposableEffect(covering) {
    if (covering) BackdropGate.covers++
    onDispose { if (covering) BackdropGate.covers-- }
  }

  PredictiveBackHandler(enabled = state.isOpen && canShow) { progress ->
    state.startBack()
    try {
      progress.collect { event: BackEventCompat -> state.backProgress(event.progress) }
      state.endBack(committed = true)
    } catch (error: CancellationException) {
      state.endBack(committed = false)
      throw error
    }
  }

  if (!canShow || !(state.isVisible || state.isContentRequested)) return

  val density = LocalDensity.current
  val slidePx = with(density) { 24.dp.toPx() }
  val miniBackground = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
  val fullBackground = MaterialTheme.colorScheme.surface
  val miniOverlayLayer = rememberGraphicsLayer()
  Box(
    modifier =
      modifier
        .fillMaxSize()
        .onPlaced {
          state.hostOrigin = it.positionInRoot()
          state.hostSize = it.size.toSize()
        }
        .layout { measurable, constraints ->
          val placeable = measurable.measure(constraints)
          layout(placeable.width, placeable.height) {
            // A warming player is laid out and recorded off-screen, where it can't be seen or touched.
            placeable.placeWithLayer(if (state.isVisible) 0 else placeable.width * 2, 0)
          }
        },
  ) {
    Spacer(
      modifier =
        Modifier
          .fillMaxSize()
          .drawBehind { drawSheetSurface(state, miniBackground, fullBackground, miniOverlayLayer) },
    )
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .graphicsLayer {
            val fraction = state.expansion
            val rect = state.sheetRect(fraction, size)
            val contentAlpha = ((fraction - 0.2f) / 0.6f).coerceIn(0f, 1f)
            translationX = rect.center.x - size.width / 2f
            translationY = rect.top + slidePx * (1f - contentAlpha)
            alpha = contentAlpha
            if (fraction >= 1f) {
              shape = RectangleShape
              clip = false
            } else {
              shape = SheetClipShape(rect.translate(Offset(-translationX, -translationY)), state.sheetCornerRadius(fraction))
              clip = true
            }
          }
          .then(if (state.isSettledOpen) Modifier else Modifier.clearAndSetSemantics { }),
    ) {
      InAppMusicPlayerContent(state, store)
      if (!state.isSettledOpen) {
        // While it moves, the sheet can be caught and thrown either way, and nothing in it reacts.
        Box(
          modifier =
            Modifier
              .fillMaxSize()
              .inAppMusicSheetDrag(state, collapsing = false, enabled = true, allowBothDirections = true),
        )
      }
    }
  }
}

private val ShowablePhases = setOf(PlaybackPhase.LOADING, PlaybackPhase.READY, PlaybackPhase.BACKGROUND)

private fun DrawScope.drawSheetSurface(
  state: InAppMusicSheetState,
  miniBackground: Color,
  fullBackground: Color,
  miniOverlayLayer: GraphicsLayer,
) {
  if (!state.isVisible) return
  val fraction = state.expansion
  val rect = state.sheetRect(fraction, size)
  if (rect.isEmpty) return
  drawRoundRect(
    color = lerp(miniBackground, fullBackground, fraction),
    topLeft = rect.topLeft,
    size = rect.size,
    cornerRadius = CornerRadius(state.sheetCornerRadius(fraction)),
  )
  val miniAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
  val mini = state.miniRect()
  val layer = state.miniLayer ?: return
  if (miniAlpha <= 0f || mini.isEmpty) return
  miniOverlayLayer.record(size = layer.size) { drawLayer(layer) }
  miniOverlayLayer.alpha = miniAlpha
  translate(rect.center.x - mini.width / 2f, rect.top) { drawLayer(miniOverlayLayer) }
}

private class SheetClipShape(
  private val rect: Rect,
  private val radius: Float,
) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density,
  ): Outline = Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

@Composable
private fun InAppMusicPlayerContent(
  state: InAppMusicSheetState,
  store: InAppMusicPlayerStore,
) {
  val activity = LocalActivity.current as? ComponentActivity ?: return
  val preferences = org.koin.compose.koinInject<AudioPreferences>()
  val audioOrientation by preferences.audioOrientation.collectAsState()
  val browserOrientation = rememberSaveable { activity.requestedOrientation }
  LaunchedEffect(activity, state.isVisible, audioOrientation) {
    val target = if (state.isVisible && audioOrientation != AudioPlayerOrientation.Auto) {
      PlayerOrientationPolicy.forAudioPreference(audioOrientation)
    } else browserOrientation
    if (activity.requestedOrientation != target) activity.requestedOrientation = target
  }
  DisposableEffect(activity) {
    onDispose {
      if (!activity.isChangingConfigurations && activity.requestedOrientation != browserOrientation) {
        activity.requestedOrientation = browserOrientation
      }
    }
  }
  val viewModel = remember(store, store.generation) { store.playerViewModel() }
  val host = remember(activity, state) { InAppMusicPlayerHost(activity) { state.collapse() } }
  DisposableEffect(viewModel, host) {
    viewModel.attachHost(host)
    viewModel.attachToDetachedPlayback()
    onDispose {
      viewModel.detachFromDetachedPlayback()
      viewModel.detachHost(host)
    }
  }
  AudioPlayerScreen(
    viewModel = viewModel,
    onBackPress = { state.collapse() },
    inAppSheet = state,
  )
  SideEffect { state.isContentReady = true }
}

/**
 * Keeps the in-app player's ViewModel for the whole playback session, so lyrics, queue and the
 * sleep timer survive collapsing the sheet. Cleared when the session ends or leaves the browser.
 */
internal class InAppMusicPlayerStore : ViewModel() {
  private val store = ViewModelStore()
  private var hasInstance = false

  var generation by mutableIntStateOf(0)
    private set

  fun playerViewModel(): PlayerViewModel {
    hasInstance = true
    return ViewModelProvider(store, BrowserPlayerViewModelFactory)[PlayerViewModel::class.java]
  }

  fun release() {
    if (!hasInstance) return
    hasInstance = false
    store.clear()
    generation++
  }

  override fun onCleared() {
    store.clear()
  }
}

private object BrowserPlayerViewModelFactory : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerViewModel(hostedInBrowser = true) as T
}

/** The browser as a player host: transport goes through the detached owner, the window stays put. */
private class InAppMusicPlayerHost(
  private val activity: ComponentActivity,
  private val onMinimize: () -> Boolean,
) : PlayerHost {
  override val context: Context
    get() = activity
  override val windowInsetsController: WindowInsetsControllerCompat
    get() = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
  override val audioManager: AudioManager
    get() = activity.getSystemService(AudioManager::class.java)
  override val hostWindow: Window
    get() = activity.window
  override val hostWindowManager: WindowManager
    get() = activity.windowManager
  override val hostContentResolver: ContentResolver
    get() = activity.contentResolver

  override var hostRequestedOrientation: Int
    get() = activity.requestedOrientation
    set(value) {
      if (activity.requestedOrientation != value) activity.requestedOrientation = value
    }

  override fun requestAudioFocus(): Boolean = MediaPlaybackService.takeAudioOwnershipForDetachedPlayback()

  // Like a notification pause, the detached owner keeps focus while paused.
  override fun abandonAudioFocus() = Unit

  override fun currentThumbnailSource(): String? = PlaybackSession.state.value.currentItem?.playableUri

  override fun isCurrentMediaKnownAudio(): Boolean = true

  override fun minimizeToMiniPlayer(): Boolean = onMinimize()

  override fun playQueueItem(index: Int) {
    MediaPlaybackService.playQueueItemFromApp(index)
  }

  override fun reorderQueueItem(
    from: Int,
    to: Int,
  ) {
    if (from != to) PlaybackSession.moveQueueItem(from, to)
  }

  override fun hasNextQueueItem(): Boolean = PlaybackSession.hasNext()

  override fun hasPreviousQueueItem(): Boolean = PlaybackSession.hasPrevious()

  override fun playNextQueueItem() {
    MediaPlaybackService.skipToNextFromApp()
  }

  override fun playPreviousQueueItem() {
    MediaPlaybackService.skipToPreviousFromApp()
  }

  override fun onQueueShuffleChanged(enabled: Boolean) {
    PlaybackSession.setShuffleEnabled(enabled)
  }
}
