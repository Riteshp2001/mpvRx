/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.graphics.Bitmap
import android.graphics.Picture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import app.gyrolet.mpvrx.utils.logging.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/*
 * Mini player <-> full player morph across MainActivity and PlayerActivity.
 *
 * Both Activities share one UI thread, so a morph drawn while the other Activity is being created
 * or torn down stutters no matter how it is drawn. The morph is therefore always rendered by the
 * window that is already on screen and idle (the "host"), driven by a single expansion fraction:
 *
 *  - Expand: MainActivity grows the mini player into a full-screen sheet. PlayerActivity is
 *    launched only once the sheet has arrived; its first frame draws the same resting sheet and
 *    fades it out over the live controls as soon as they have drawn.
 *  - Collapse: PlayerActivity shrinks itself into the mini player over a frozen copy of the
 *    browser, then finishes without a window animation. MainActivity reappears pixel-identical.
 *
 * Hidden sources keep their last display list instead of re-recording every frame, and the
 * overlay lives in its own layer so its per-frame invalidation never replays the screen below.
 */

internal enum class PlayerSheetDestination {
  FULL,
  MINI,
  ;

  val expansion: Float
    get() = if (this == FULL) 1f else 0f

  val opposite: PlayerSheetDestination
    get() = if (this == FULL) MINI else FULL
}

internal class PlayerSheetCapture(
  private val context: GraphicsContext,
  val view: View,
  val density: Density,
  val layoutDirection: LayoutDirection,
) {
  val layer = context.createGraphicsLayer()
  var isRecorded by mutableStateOf(false)
  private var isReleased = false

  // Live layers belong to their own window; a stopping Activity may discard them, so anything
  // drawn by the other Activity is copied into a hardware bitmap first.
  fun snapshot(): ImageBitmap? {
    if (isReleased || !isRecorded || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    if (layer.size.width <= 0 || layer.size.height <= 0) return null
    // The morph fades this layer; the frozen copy must be opaque, and the live morph must not
    // see the alpha change before its next draw.
    val alpha = layer.alpha
    layer.alpha = 1f
    return try {
      runCatching {
        Bitmap.createBitmap(LayerPicture(layer, density, layoutDirection)).asImageBitmap()
      }.onFailure { Log.w("PlayerSheetTransitions", "Unable to snapshot player sheet", it) }.getOrNull()
    } finally {
      layer.alpha = alpha
    }
  }

  fun release() {
    if (isReleased) return
    isReleased = true
    context.releaseGraphicsLayer(layer)
  }
}

private class LayerPicture(
  private val layer: GraphicsLayer,
  private val density: Density,
  private val layoutDirection: LayoutDirection,
) : Picture() {
  override fun getWidth(): Int = layer.size.width

  override fun getHeight(): Int = layer.size.height

  override fun requiresHardwareAcceleration(): Boolean = true

  override fun draw(canvas: android.graphics.Canvas) {
    CanvasDrawScope().draw(
      density,
      layoutDirection,
      androidx.compose.ui.graphics
        .Canvas(canvas),
      layer.size.toSize(),
    ) { drawLayer(layer) }
  }
}

internal data class PlayerSheetAnchor(
  val owner: Any,
  val mediaId: String?,
  val bounds: Rect,
  val cornerRadius: Float,
  val backgroundColor: Color,
  val capture: PlayerSheetCapture? = null,
  val snapshot: ImageBitmap? = null,
) {
  /** Detaches the anchor from its window so another Activity can draw it safely. */
  fun frozen(): PlayerSheetAnchor =
    if (snapshot != null || capture == null) this else copy(snapshot = capture.snapshot(), capture = null)
}

internal enum class PlayerSheetSlot(
  val destination: PlayerSheetDestination?,
) {
  FULL(PlayerSheetDestination.FULL),
  MINI(PlayerSheetDestination.MINI),
  BACKDROP(null),
  WALLPAPER(null),
}

internal data class PlayerSheetEndpoints(
  val full: PlayerSheetAnchor,
  val mini: PlayerSheetAnchor,
  val backdrop: PlayerSheetAnchor?,
  val wallpaper: PlayerSheetAnchor? = null,
) {
  val travel: Float
    get() = (mini.bounds.top - full.bounds.top).coerceAtLeast(1f)

  fun uses(capture: PlayerSheetCapture): Boolean =
    full.capture === capture ||
      mini.capture === capture ||
      backdrop?.capture === capture ||
      wallpaper?.capture === capture
}

internal class PlayerSheetMotion(
  val id: Long,
  val destination: PlayerSheetDestination,
  val endpoints: PlayerSheetEndpoints,
  initialExpansion: Float,
  val initialVelocity: Float,
  private val onSettled: (arrived: Boolean) -> Unit,
) {
  val mediaId: String?
    get() = endpoints.full.mediaId

  /** The side whose window is already drawn and idle; it renders the whole morph. */
  val host: PlayerSheetDestination = destination.opposite
  val expansion: Animatable<Float, AnimationVector1D> = Animatable(initialExpansion)

  /** Where the host is heading; flips back to [host] when the move is undone. */
  var target by mutableFloatStateOf(destination.expansion)
  var arrived by mutableStateOf(false)

  /** The expanded player has drawn its live controls beneath the resting sheet. */
  var revealed by mutableStateOf(false)
  val cover: Animatable<Float, AnimationVector1D> = Animatable(1f)
  var hasStarted = false
  var velocityConsumed = false
  private var settled = false

  /** Reports once whether the sheet reached [destination] or the move was abandoned. */
  fun settle(arrived: Boolean) {
    if (settled) return
    settled = true
    onSettled(arrived)
  }
}

internal class PlayerSheetDrag(
  val source: PlayerSheetDestination,
  val mediaId: String,
  val endpoints: PlayerSheetEndpoints,
  initialExpansion: Float,
) {
  var expansion by mutableFloatStateOf(initialExpansion)
  var velocity = 0f
}

internal object PlayerSheetTransitions {
  private const val TAG = "PlayerSheetTransitions"
  private const val START_TIMEOUT_MS = 1_000L
  private const val REVERSE_TIMEOUT_MS = 2_000L
  private const val HANDOFF_TIMEOUT_MS = 4_000L
  private const val PARK_TIMEOUT_MS = 6_000L
  private val standInOwner = Any()

  private var fullAnchor by mutableStateOf<PlayerSheetAnchor?>(null)
  private var miniAnchor by mutableStateOf<PlayerSheetAnchor?>(null)
  private var miniPlacement by mutableStateOf<PlayerSheetAnchor?>(null)
  private var backdrop by mutableStateOf<PlayerSheetAnchor?>(null)
  private var wallpaper by mutableStateOf<PlayerSheetAnchor?>(null)
  private var fullBackground: Color? = null

  // Browser content frozen while MainActivity was still visible, for motion inside the player.
  private var browserWallpaper: PlayerSheetAnchor? = null
  private var browserBackdrop: PlayerSheetAnchor? = null
  private var browserMini: PlayerSheetAnchor? = null

  /** The expanded player as it last looked at rest, so expansion grows into real content. */
  private var cachedFull: PlayerSheetAnchor? = null
  private val pendingReleases = mutableSetOf<PlayerSheetCapture>()
  private val handler = Handler(Looper.getMainLooper())
  private var timeout: Runnable? = null
  private var expansionTimeout: Runnable? = null
  private var nextId = 0L
  var motion by mutableStateOf<PlayerSheetMotion?>(null)
    private set
  var drag by mutableStateOf<PlayerSheetDrag?>(null)
    private set

  /** Keeps the collapsed mini player shown until the playback service reports foreground. */
  var collapsedMediaId by mutableStateOf<String?>(null)
    private set

  /** Keep the source laid out until its window is covered, including native video transitions. */
  var expandingMediaId by mutableStateOf<String?>(null)
    private set
  var reducedMotion = false

  fun holdExpansion(mediaId: String?): Boolean {
    if (mediaId == null || expandingMediaId != null) return false
    expandingMediaId = mediaId
    expansionTimeout = Runnable { releaseExpansionHold() }.also { handler.postDelayed(it, 3_000L) }
    return true
  }

  fun releaseExpansionHold() {
    expandingMediaId = null
    expansionTimeout?.let(handler::removeCallbacks)
    expansionTimeout = null
  }

  fun releaseCollapsedHold(mediaId: String) {
    if (collapsedMediaId == mediaId) collapsedMediaId = null
  }

  /** Shows the mini player immediately when the player closes without the sheet morph. */
  fun holdCollapsed(mediaId: String?) {
    collapsedMediaId = mediaId
  }

  fun anchor(destination: PlayerSheetDestination): PlayerSheetAnchor? =
    if (destination == PlayerSheetDestination.FULL) fullAnchor else miniAnchor

  fun update(
    slot: PlayerSheetSlot,
    anchor: PlayerSheetAnchor,
  ) {
    when (slot) {
      PlayerSheetSlot.FULL -> {
        fullAnchor = anchor
        fullBackground = anchor.backgroundColor
      }
      PlayerSheetSlot.MINI -> miniAnchor = anchor
      PlayerSheetSlot.BACKDROP -> backdrop = anchor
      PlayerSheetSlot.WALLPAPER -> wallpaper = anchor
    }
  }

  fun remove(
    slot: PlayerSheetSlot,
    owner: Any,
    capture: PlayerSheetCapture,
  ) {
    when (slot) {
      PlayerSheetSlot.FULL -> if (fullAnchor?.owner === owner) fullAnchor = null
      PlayerSheetSlot.MINI -> if (miniAnchor?.owner === owner) miniAnchor = null
      PlayerSheetSlot.BACKDROP -> if (backdrop?.owner === owner) backdrop = null
      PlayerSheetSlot.WALLPAPER -> if (wallpaper?.owner === owner) wallpaper = null
    }
    if (drag?.endpoints?.uses(capture) == true && drag?.source == slot.destination) drag = null
    // A running morph may still draw this layer after its composable has left.
    pendingReleases.add(capture)
    releaseUnusedCaptures()
  }

  fun updateMiniPlacement(anchor: PlayerSheetAnchor) {
    miniPlacement = anchor
  }

  fun removeMiniPlacement(owner: Any) {
    if (miniPlacement?.owner === owner) miniPlacement = null
  }

  /** Freezes the browser while MainActivity is still visible, before the player covers it. */
  fun captureBrowser() {
    if (drag != null || motion?.host == PlayerSheetDestination.FULL) return
    // Video uses the native window transition; no full-browser GPU snapshot is needed there.
    // Check a freshly staged launch first so a stale video in PlaybackSession doesn't skip audio capture.
    val outgoingKind =
      PreparedPlaybackLaunchStore.pendingMediaKind()
        ?: PlaybackSession.state.value.currentItem
          ?.declaredMediaKind()
    if (outgoingKind == DeclaredPlaybackMediaKind.VIDEO) return
    browserWallpaper = wallpaper?.frozen()?.takeIf { it.snapshot != null }
    browserBackdrop = backdrop?.frozen()?.takeIf { it.snapshot != null }
    // A hidden mini player still holds its last recording, so this also works mid-expansion.
    browserMini = miniAnchor?.takeIf { it.capture?.isRecorded == true }?.frozen()?.takeIf { it.snapshot != null }
  }

  fun clearBrowserCache() {
    browserWallpaper = null
    browserBackdrop = null
    browserMini = null
  }

  private fun endpoints(
    source: PlayerSheetDestination,
    mediaId: String,
  ): PlayerSheetEndpoints? {
    val placement = miniPlacement ?: return null
    val pair =
      if (source == PlayerSheetDestination.MINI) {
        val mini = miniAnchor?.takeIf { it.capture?.isRecorded == true } ?: return null
        val screen = backdrop?.bounds ?: return null
        val cached = cachedFull?.takeIf { it.mediaId == mediaId && it.bounds.size == screen.size }
        PlayerSheetEndpoints(
          // Opaque, so the browser never ghosts through the sheet the player will replace.
          full =
            cached?.copy(bounds = screen) ?: PlayerSheetAnchor(
              owner = standInOwner,
              mediaId = mediaId,
              bounds = screen,
              cornerRadius = 0f,
              backgroundColor = (fullBackground ?: mini.backgroundColor).copy(alpha = 1f),
            ),
          mini = mini.copy(bounds = placement.bounds),
          backdrop = null,
        )
      } else {
        // Until the reopened player has drawn its controls, it shows the resting hand-off sheet.
        val full =
          fullAnchor?.takeIf { it.capture?.isRecorded == true }
            ?: restingHandOff(mediaId)?.endpoints?.full
            ?: return null
        // When AppWallpaperHost is active, browserBackdrop is transparent over the wallpaper; don't
        // run the morph without both layers frozen.
        if (wallpaper != null && browserWallpaper == null) return null
        PlayerSheetEndpoints(
          full = full.copy(mediaId = mediaId),
          mini =
            browserMini?.takeIf { it.mediaId == mediaId }?.copy(bounds = placement.bounds)
              ?: placement.copy(mediaId = mediaId),
          // Without a frozen browser the player's black video surface would show through.
          backdrop = browserBackdrop ?: return null,
          wallpaper = browserWallpaper,
        )
      }
    if (pair.full.bounds.isEmpty || pair.mini.bounds.isEmpty) return null
    return pair
  }

  /**
   * Starts a morph toward [destination], continuing an active drag in the same window.
   * [onSettled] runs once: `true` when the sheet rests at [destination], `false` if abandoned.
   */
  fun begin(
    destination: PlayerSheetDestination,
    mediaId: String?,
    onSettled: (arrived: Boolean) -> Unit = {},
  ): Boolean {
    // The mini player must be present even when this media uses the native Activity animation.
    if (destination == PlayerSheetDestination.MINI) collapsedMediaId = mediaId
    if (mediaId == null || reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val source = destination.opposite
    val activeDrag = drag?.takeIf { it.mediaId == mediaId && it.source == source }
    val pair = activeDrag?.endpoints ?: endpoints(source, mediaId)
    if (pair == null) {
      Log.d(TAG, "No recorded player sheet for $destination; keeping the Activity transition")
      return false
    }
    motion?.let { finish(it.id) }
    if (destination == PlayerSheetDestination.FULL) collapsedMediaId = null
    val active =
      PlayerSheetMotion(
        id = ++nextId,
        destination = destination,
        endpoints = pair,
        initialExpansion = activeDrag?.expansion ?: source.expansion,
        initialVelocity = (-(activeDrag?.velocity ?: 0f) / pair.travel).coerceIn(-6f, 6f),
        onSettled = onSettled,
      )
    motion = active
    drag = null
    schedule(START_TIMEOUT_MS) {
      if (motion === active && !active.hasStarted) {
        Log.w(TAG, "Player sheet transition ${active.id} never started")
        finish(active.id)
      }
    }
    releaseUnusedCaptures()
    return true
  }

  /** Called by the host once the sheet rests at its destination. */
  fun arrive(id: Long) {
    val active = motion?.takeIf { it.id == id && !it.arrived } ?: return
    active.arrived = true
    cancelTimeout()
    if (active.destination == PlayerSheetDestination.MINI) {
      // The screen is at rest now, so the GPU copy cannot cost a visible frame.
      active.endpoints.full
        .frozen()
        .takeIf { it.snapshot != null }
        ?.let { cachedFull = it }
      // Safety net: the player normally finishes or reverses well before this.
      schedule(PARK_TIMEOUT_MS) { reverse(id) }
    } else {
      schedule(HANDOFF_TIMEOUT_MS) {
        Log.w(TAG, "Player sheet hand-off $id timed out")
        finish(id)
      }
    }
    active.settle(true)
  }

  /** The finishing player keeps its resting mini pose on screen until its window is gone. */
  fun holdParked(id: Long) {
    if (motion?.id == id) cancelTimeout()
  }

  /** Undoes a collapse, e.g. when the playback service could not take over. */
  fun reverseCollapse() {
    motion?.takeIf { it.destination == PlayerSheetDestination.MINI }?.let { reverse(it.id) }
  }

  private fun reverse(id: Long) {
    val active = motion?.takeIf { it.id == id } ?: return
    active.arrived = false
    active.target = active.host.expansion
    schedule(REVERSE_TIMEOUT_MS) { if (motion === active) finish(id) }
  }

  /** MainActivity is interactive again; an expansion that never reached the player is void. */
  fun abandonExpansion() {
    motion?.takeIf { it.host == PlayerSheetDestination.MINI }?.let { finish(it.id) }
  }

  fun finish(id: Long) {
    val active = motion?.takeIf { it.id == id } ?: return
    motion = null
    cancelTimeout()
    releaseUnusedCaptures()
    active.settle(false)
  }

  /** The overlay of [destination] left composition; drop the motion it was responsible for. */
  internal fun detachHost(
    destination: PlayerSheetDestination,
    id: Long,
  ) {
    val active = motion?.takeIf { it.id == id } ?: return
    val owned =
      when (destination) {
        // PlayerActivity is gone: it either hosted the collapse or was receiving the expansion.
        PlayerSheetDestination.FULL -> active.host == destination || active.arrived
        PlayerSheetDestination.MINI -> active.host == destination && !active.arrived
      }
    if (owned) finish(id)
  }

  /** An expansion the player is still taking over; its sheet already rests full screen. */
  private fun restingHandOff(mediaId: String): PlayerSheetMotion? =
    motion?.takeIf { it.destination == PlayerSheetDestination.FULL && it.arrived && it.mediaId == mediaId }

  fun startDrag(
    source: PlayerSheetDestination,
    mediaId: String?,
  ): PlayerSheetDrag? {
    if (mediaId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    // The just-opened player may be pulled down again while it still shows the hand-off sheet.
    if (motion != null && (source != PlayerSheetDestination.FULL || restingHandOff(mediaId) == null)) return null
    val pair = endpoints(source, mediaId) ?: return null
    val active = PlayerSheetDrag(source, mediaId, pair, source.expansion)
    drag = active
    releaseUnusedCaptures()
    return active
  }

  fun cancelDrag(active: PlayerSheetDrag) {
    if (drag !== active) return
    drag = null
    releaseUnusedCaptures()
  }

  suspend fun restoreDrag(
    active: PlayerSheetDrag,
    reduceMotion: Boolean,
  ) {
    try {
      if (!reduceMotion) {
        Animatable(active.expansion).animateTo(active.source.expansion, SheetSpring) {
          active.expansion = value
        }
      }
    } finally {
      cancelDrag(active)
    }
  }

  /** Whether the live sheet at [destination] is currently drawn by the morph instead. */
  fun isHidden(destination: PlayerSheetDestination): Boolean {
    motion?.let {
      if (it.host == destination) return true
      if (destination == PlayerSheetDestination.FULL && it.destination == destination && !it.revealed) return true
    }
    return drag?.source == destination
  }

  private fun schedule(
    delayMs: Long,
    block: () -> Unit,
  ) {
    cancelTimeout()
    timeout = Runnable { block() }.also { handler.postDelayed(it, delayMs) }
  }

  private fun cancelTimeout() {
    timeout?.let(handler::removeCallbacks)
    timeout = null
  }

  private fun releaseUnusedCaptures() {
    val unused =
      pendingReleases.filter {
        motion?.endpoints?.uses(it) != true && drag?.endpoints?.uses(it) != true
      }
    unused.forEach { it.release() }
    pendingReleases.removeAll(unused.toSet())
  }
}

private val SheetSpring =
  spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 500f,
    visibilityThreshold = 0.002f,
  )

private fun LayoutCoordinates.sheetScreenBounds(view: View): Rect {
  val location = IntArray(2)
  view.rootView.getLocationOnScreen(location)
  return Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width.toFloat(), size.height.toFloat())))
    .translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

@Composable
private fun Modifier.recordPlayerSheet(
  slot: PlayerSheetSlot,
  mediaId: String?,
  shape: Shape,
  backgroundColor: Color,
): Modifier {
  val context = LocalGraphicsContext.current
  val view = LocalView.current
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val capture =
    remember(context, view, slot, mediaId, density, direction) {
      PlayerSheetCapture(context, view, density, direction)
    }
  val owner = remember(capture) { Any() }
  var coordinates by remember(view, slot) { mutableStateOf<LayoutCoordinates?>(null) }

  fun updateAnchor(layout: LayoutCoordinates) {
    if (!layout.isAttached) return
    val bounds = layout.sheetScreenBounds(view)
    val outline = shape.createOutline(Size(bounds.width, bounds.height), direction, density)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    PlayerSheetTransitions.update(
      slot,
      PlayerSheetAnchor(owner, mediaId, bounds, radius, backgroundColor, capture),
    )
  }

  SideEffect { coordinates?.let(::updateAnchor) }
  DisposableEffect(capture) {
    onDispose { PlayerSheetTransitions.remove(slot, owner, capture) }
  }
  return this
    .onGloballyPositioned {
      coordinates = it
      updateAnchor(it)
    }.drawWithContent {
      val destination = slot.destination
      val hidden = destination != null && PlayerSheetTransitions.isHidden(destination)
      // While hidden the morph draws the last recording; replaying the whole player or browser
      // into it every frame would only add UI-thread work to the animation.
      val holdRecording = hidden && capture.isRecorded && capture.layer.size.toSize() == size
      if (!holdRecording) {
        capture.layer.record { this@drawWithContent.drawContent() }
        capture.isRecorded = true
      }
      if (!hidden) {
        capture.layer.alpha = 1f
        drawLayer(capture.layer)
      }
    }
}

@Composable
internal fun Modifier.playerSheetAnchor(
  destination: PlayerSheetDestination,
  mediaId: String?,
  shape: Shape = RectangleShape,
  backgroundColor: Color,
): Modifier =
  recordPlayerSheet(
    if (destination == PlayerSheetDestination.FULL) PlayerSheetSlot.FULL else PlayerSheetSlot.MINI,
    mediaId,
    shape,
    backgroundColor,
  )

@Composable
internal fun Modifier.playerSheetBackdrop(): Modifier =
  recordPlayerSheet(PlayerSheetSlot.BACKDROP, null, RectangleShape, Color.Transparent)

@Composable
internal fun Modifier.playerSheetWallpaper(backgroundColor: Color = Color.Transparent): Modifier =
  recordPlayerSheet(PlayerSheetSlot.WALLPAPER, null, RectangleShape, backgroundColor)

@Composable
internal fun Modifier.playerSheetMiniPlacement(
  shape: Shape,
  backgroundColor: Color,
): Modifier {
  val view = LocalView.current
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val owner = remember { Any() }
  DisposableEffect(owner) {
    onDispose { PlayerSheetTransitions.removeMiniPlacement(owner) }
  }
  return onGloballyPositioned {
    val bounds = it.sheetScreenBounds(view)
    val outline = shape.createOutline(Size(bounds.width, bounds.height), direction, density)
    PlayerSheetTransitions.updateMiniPlacement(
      PlayerSheetAnchor(
        owner,
        null,
        bounds,
        (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f,
        backgroundColor,
      ),
    )
  }
}

private fun DrawScope.drawSheetContent(
  anchor: PlayerSheetAnchor,
  hostView: View,
  left: Float,
  top: Float,
  alpha: Float,
) {
  if (alpha <= 0f) return
  val snapshot = anchor.snapshot
  if (snapshot != null) {
    drawImage(snapshot, topLeft = Offset(left, top), alpha = alpha)
    return
  }
  val capture = anchor.capture?.takeIf { it.isRecorded && it.view.rootView === hostView.rootView } ?: return
  capture.layer.alpha = alpha
  translate(left, top) { drawLayer(capture.layer) }
}

private fun DrawScope.drawPlayerSheet(
  endpoints: PlayerSheetEndpoints,
  expansion: Float,
  hostView: View,
  origin: Offset,
  drawBackdrop: Boolean,
) {
  if (drawBackdrop) {
    endpoints.wallpaper?.let {
      if (it.backgroundColor.alpha > 0f) {
        drawRect(it.backgroundColor)
      }
      drawSheetContent(it, hostView, it.bounds.left - origin.x, it.bounds.top - origin.y, 1f)
    }
    endpoints.backdrop?.let {
      drawSheetContent(it, hostView, it.bounds.left - origin.x, it.bounds.top - origin.y, 1f)
    }
  }
  val fraction = expansion.coerceIn(0f, 1f)
  val full = endpoints.full
  val mini = endpoints.mini
  // The sheet rests on its anchors: nothing is offset past full screen or below the mini player.
  val sheetBounds = lerp(mini.bounds, full.bounds, fraction).translate(-origin)
  if (sheetBounds.isEmpty) return
  val radius = mini.cornerRadius + (full.cornerRadius - mini.cornerRadius) * fraction
  val clip = Path().apply { addRoundRect(RoundRect(sheetBounds, CornerRadius(radius))) }
  val fullAlpha = ((fraction - 0.25f) / 0.75f).coerceIn(0f, 1f)
  val miniAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
  clipPath(clip) {
    drawRect(
      color = lerp(mini.backgroundColor, full.backgroundColor, fraction),
      topLeft = sheetBounds.topLeft,
      size = sheetBounds.size,
    )
    drawSheetContent(
      full,
      hostView,
      sheetBounds.center.x - full.bounds.width / 2f,
      sheetBounds.top + 24.dp.toPx() * (1f - fullAlpha),
      fullAlpha,
    )
    drawSheetContent(mini, hostView, sheetBounds.center.x - mini.bounds.width / 2f, sheetBounds.top, miniAlpha)
  }
}

/**
 * Draws the player sheet for the window it lives in: the whole morph when this window hosts it,
 * the resting hand-off sheet when the player receives an expansion, and live drags.
 * [onMinimize] lets the resting hand-off sheet be swiped straight back into the mini player.
 */
@Composable
internal fun PlayerSheetTransitionOverlay(
  destination: PlayerSheetDestination,
  modifier: Modifier = Modifier,
  contentReady: Boolean = true,
  onMinimize: (() -> Boolean)? = null,
) {
  val motion = PlayerSheetTransitions.motion
  val drag = PlayerSheetTransitions.drag
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val reducedMotion = AppMotion.shouldReduceMotion()
  val view = LocalView.current
  // Written during layout, which runs before draw in the same frame, so the first frame is placed.
  val origin = remember { arrayOf(Offset.Zero) }
  val participated = remember { longArrayOf(-1L) }

  SideEffect { PlayerSheetTransitions.reducedMotion = reducedMotion }
  DisposableEffect(destination) {
    onDispose { PlayerSheetTransitions.detachHost(destination, participated[0]) }
  }

  val hosted = motion?.takeIf { it.host == destination }
  LaunchedEffect(hosted?.id, hosted?.target, lifecycle) {
    val active = hosted ?: return@LaunchedEffect
    val target = active.target
    val arriving = target == active.destination.expansion
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      if (PlayerSheetTransitions.motion !== active || active.target != target) return@repeatOnLifecycle
      if (arriving && active.arrived) return@repeatOnLifecycle
      var completed = false
      try {
        active.hasStarted = true
        if (reducedMotion) {
          active.expansion.snapTo(target)
        } else {
          val velocity = if (active.velocityConsumed) active.expansion.velocity else active.initialVelocity
          active.velocityConsumed = true
          active.expansion.animateTo(target, SheetSpring, velocity)
        }
        completed = true
      } finally {
        if (PlayerSheetTransitions.motion === active && active.target == target) {
          when {
            completed && arriving -> PlayerSheetTransitions.arrive(active.id)
            completed || !active.arrived -> PlayerSheetTransitions.finish(active.id)
          }
        }
      }
    }
  }

  // The player Activity takes over a finished expansion: keep the resting sheet until the live
  // controls have drawn beneath it, then fade it away.
  val received =
    motion?.takeIf {
      destination == PlayerSheetDestination.FULL &&
        it.host != destination &&
        it.destination == destination &&
        it.arrived
    }
  LaunchedEffect(received?.id, contentReady) {
    val active = received ?: return@LaunchedEffect
    if (!contentReady) return@LaunchedEffect
    try {
      withTimeoutOrNull(2_500L) {
        snapshotFlow {
          PlayerSheetTransitions
            .anchor(destination)
            ?.capture
            ?.takeIf { it.view.rootView === view.rootView }
            ?.isRecorded == true
        }.first { it }
      }
      active.revealed = true
      // One frame for the live controls to record and draw under the still-opaque sheet.
      withFrameNanos { }
      withFrameNanos { }
      // A drag that picked the resting sheet up owns it until it is released or restored.
      snapshotFlow { PlayerSheetTransitions.drag?.source == destination }.first { !it }
      if (!reducedMotion) active.cover.animateTo(0f, tween(180, easing = LinearOutSlowInEasing))
    } finally {
      PlayerSheetTransitions.finish(active.id)
    }
  }

  val drawn = hosted ?: received
  val showDrag = drag?.source == destination && !reducedMotion
  SideEffect { drawn?.let { participated[0] = it.id } }
  if (drawn == null && !showDrag) return
  // Drags never coexist with a hosted morph, but may pick up the resting hand-off sheet.
  val activeDrag = drag?.takeIf { showDrag && hosted == null }
  val touches =
    when {
      drawn == null -> Modifier
      drawn === received && onMinimize != null ->
        Modifier.swipeDownToMiniPlayer(drawn.mediaId, enabled = true, onMinimize = onMinimize)
      else -> Modifier.blockTouches()
    }
  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .onGloballyPositioned { origin[0] = it.sheetScreenBounds(view).topLeft }
        // Own layer: per-frame redraws stay here instead of replaying the screen underneath.
        .graphicsLayer {
          alpha =
            if (activeDrag == null &&
              drawn != null &&
              drawn === received
            ) {
              drawn.cover.value
            } else {
              1f
            }
        }.then(touches),
  ) {
    val drawBackdrop = destination == PlayerSheetDestination.FULL
    if (activeDrag != null) {
      drawPlayerSheet(activeDrag.endpoints, activeDrag.expansion, view, origin[0], drawBackdrop)
    } else if (drawn != null) {
      drawPlayerSheet(drawn.endpoints, drawn.expansion.value, view, origin[0], drawBackdrop)
    }
  }
}

/** The morph imitates the other screen; taps must not reach the hidden one underneath. */
private fun Modifier.blockTouches(): Modifier =
  pointerInput(Unit) {
    awaitPointerEventScope {
      while (true) {
        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
      }
    }
  }

@Composable
private fun Modifier.swipePlayerSheet(
  source: PlayerSheetDestination,
  mediaId: String?,
  enabled: Boolean,
  animateSheet: Boolean,
  onToggle: () -> Boolean,
): Modifier {
  val scope = rememberCoroutineScope()
  val toggle by rememberUpdatedState(onToggle)
  val reducedMotion = AppMotion.shouldReduceMotion()
  val density = LocalDensity.current
  val directionThreshold = with(density) { 5.dp.toPx() }
  val fallbackThreshold = with(density) { (if (source == PlayerSheetDestination.FULL) 80.dp else 48.dp).toPx() }
  var restoreJob by remember { mutableStateOf<Job?>(null) }
  var activeDrag by remember { mutableStateOf<PlayerSheetDrag?>(null) }

  fun restore() {
    restoreJob?.cancel()
    val active = activeDrag ?: return
    restoreJob =
      scope.launch {
        try {
          PlayerSheetTransitions.restoreDrag(active, reducedMotion)
        } finally {
          PlayerSheetTransitions.cancelDrag(active)
          if (activeDrag === active) activeDrag = null
        }
      }
  }

  DisposableEffect(source, mediaId, enabled) {
    onDispose {
      restoreJob?.cancel()
      activeDrag?.let(PlayerSheetTransitions::cancelDrag)
      activeDrag = null
    }
  }
  return pointerInput(source, mediaId, enabled, animateSheet, directionThreshold, fallbackThreshold, reducedMotion) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      var overSlop = 0f
      val first =
        awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
          val towardTarget = if (source == PlayerSheetDestination.FULL) over > 0f else over < 0f
          if (towardTarget) {
            change.consume()
            overSlop = over
          }
        } ?: return@awaitEachGesture

      // Do not hide the live player for a drag away from the other sheet anchor.
      val previousExpansion = activeDrag?.takeIf { PlayerSheetTransitions.drag === it }?.expansion
      restoreJob?.cancel()
      activeDrag = if (animateSheet && !reducedMotion) PlayerSheetTransitions.startDrag(source, mediaId) else null
      if (previousExpansion != null) activeDrag?.expansion = previousExpansion
      val tracker = VelocityTracker()
      tracker.addPosition(down.uptimeMillis, down.position)
      var accumulated = 0f

      fun dragBy(amount: Float) {
        accumulated += amount
        if (activeDrag == null) {
          accumulated =
            if (source == PlayerSheetDestination.FULL) {
              accumulated.coerceAtLeast(0f)
            } else {
              accumulated.coerceAtMost(0f)
            }
          if (accumulated != 0f && animateSheet && !reducedMotion) {
            activeDrag = PlayerSheetTransitions.startDrag(source, mediaId)
          }
        }
        activeDrag?.let { active ->
          active.expansion = (active.expansion - amount / active.endpoints.travel).coerceIn(0f, 1f)
          if (active.expansion == source.expansion) {
            PlayerSheetTransitions.cancelDrag(active)
            if (activeDrag === active) activeDrag = null
          }
        }
      }
      var released = false
      try {
        dragBy(overSlop)
        tracker.addPosition(first.uptimeMillis, first.position)
        released =
          verticalDrag(first.id) { change ->
            dragBy(change.position.y - change.previousPosition.y)
            tracker.addPosition(change.uptimeMillis, change.position)
            change.consume()
          }
      } finally {
        if (!released) restore()
      }
      if (!released) return@awaitEachGesture
      val active = activeDrag
      val velocity = tracker.calculateVelocity().y
      val towardTarget = if (source == PlayerSheetDestination.FULL) accumulated else -accumulated
      val targetVelocity = if (source == PlayerSheetDestination.FULL) velocity else -velocity
      val shouldToggle =
        if (active != null) {
          when {
            source == PlayerSheetDestination.FULL && accumulated <= 0f -> false
            abs(accumulated) > directionThreshold -> towardTarget > 0f
            abs(velocity) > 150f -> targetVelocity > 0f
            else -> if (source == PlayerSheetDestination.FULL) active.expansion < 0.5f else active.expansion > 0.5f
          }
        } else {
          towardTarget > fallbackThreshold
        }
      if (active != null) active.velocity = velocity
      if (!shouldToggle || !toggle()) {
        restore()
      } else if (active != null && PlayerSheetTransitions.drag === active) {
        restoreJob =
          scope.launch {
            kotlinx.coroutines.delay(1_000L)
            if (PlayerSheetTransitions.drag === active) restore()
          }
      } else {
        activeDrag = null
      }
    }
  }
}

@Composable
internal fun Modifier.swipeDownToMiniPlayer(
  mediaId: String?,
  enabled: Boolean,
  onMinimize: () -> Boolean,
): Modifier = swipePlayerSheet(PlayerSheetDestination.FULL, mediaId, enabled, true, onMinimize)

@Composable
internal fun Modifier.swipeUpToFullPlayer(
  mediaId: String?,
  animateSheet: Boolean,
  onExpand: () -> Boolean,
): Modifier = swipePlayerSheet(PlayerSheetDestination.MINI, mediaId, true, animateSheet, onExpand)
