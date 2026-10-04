package app.gyrolet.mpvrx.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.ui.theme.AppMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

internal enum class PlayerArtworkDestination { FULL, MINI }

internal data class PlayerArtworkAnchor(
  val owner: Any,
  val mediaId: String,
  val bounds: Rect,
  val bitmap: Bitmap?,
  val cornerRadius: Float,
  val currentBounds: (() -> Rect?)? = null,
)

internal data class PlayerArtworkMotion(
  val id: Long,
  val destination: PlayerArtworkDestination,
  val source: PlayerArtworkAnchor,
  val progress: Animatable<Float, AnimationVector1D> = Animatable(0f),
)

internal object PlayerArtworkTransitions {
  private var fullAnchor by mutableStateOf<PlayerArtworkAnchor?>(null)
  private var miniAnchor by mutableStateOf<PlayerArtworkAnchor?>(null)
  private var nextId = 0L
  private val handler = Handler(Looper.getMainLooper())
  private var timeout: Runnable? = null
  var motion by mutableStateOf<PlayerArtworkMotion?>(null)
    private set

  fun anchor(destination: PlayerArtworkDestination): PlayerArtworkAnchor? =
    if (destination == PlayerArtworkDestination.FULL) fullAnchor else miniAnchor

  fun update(destination: PlayerArtworkDestination, anchor: PlayerArtworkAnchor) {
    if (destination == PlayerArtworkDestination.FULL) fullAnchor = anchor else miniAnchor = anchor
  }

  fun remove(destination: PlayerArtworkDestination, owner: Any) {
    if (anchor(destination)?.owner !== owner) return
    if (destination == PlayerArtworkDestination.FULL) fullAnchor = null else miniAnchor = null
  }

  fun begin(destination: PlayerArtworkDestination, mediaId: String?): Boolean {
    var source = if (destination == PlayerArtworkDestination.FULL) miniAnchor else fullAnchor
    val previous = motion?.takeIf { it.source.mediaId == mediaId }
    if (previous != null) {
      val target = anchor(previous.destination)?.takeIf { it.mediaId == mediaId } ?: previous.source
      val progress = previous.progress.value
      source = previous.source.copy(
        bounds = lerp(previous.source.bounds, target.bounds, progress),
        cornerRadius = previous.source.cornerRadius + (target.cornerRadius - previous.source.cornerRadius) * progress,
      )
    }
    val sourceAnchor = source?.let {
      it.copy(bounds = it.currentBounds?.invoke() ?: it.bounds, currentBounds = null)
    } ?: return false
    val sourceBitmap = sourceAnchor.bitmap ?: return false
    if (sourceAnchor.mediaId != mediaId || sourceBitmap.isRecycled) return false
    if (sourceAnchor.bounds.width <= 0f || sourceAnchor.bounds.height <= 0f) return false
    val id = ++nextId
    motion = PlayerArtworkMotion(id, destination, sourceAnchor)
    timeout?.let(handler::removeCallbacks)
    timeout = Runnable { finish(id) }.also { handler.postDelayed(it, 2_000L) }
    return true
  }

  fun finish(id: Long) {
    if (motion?.id != id) return
    motion = null
    timeout?.let(handler::removeCallbacks)
    timeout = null
  }

  fun contentAlpha(destination: PlayerArtworkDestination): Float {
    val active = motion?.takeIf { it.destination == destination } ?: return 1f
    return if (anchor(destination)?.mediaId == active.source.mediaId) active.progress.value else 1f
  }
}

private fun LayoutCoordinates.artworkScreenBounds(view: View): Rect {
  val location = IntArray(2)
  view.rootView.getLocationOnScreen(location)
  val bounds = Rect(localToWindow(Offset.Zero), localToWindow(Offset(size.width.toFloat(), size.height.toFloat())))
  return bounds.translate(Offset(location[0].toFloat(), location[1].toFloat()))
}

@Composable
internal fun Modifier.playerArtworkAnchor(
  destination: PlayerArtworkDestination,
  mediaId: String?,
  bitmap: Bitmap?,
  cornerRadius: Dp,
): Modifier {
  val owner = remember(destination, mediaId) { Any() }
  val view = LocalView.current
  val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
  var coordinates by remember(owner) { mutableStateOf<LayoutCoordinates?>(null) }

  fun updateAnchor(layout: LayoutCoordinates) {
    if (mediaId != null && layout.isAttached) {
      PlayerArtworkTransitions.update(
        destination,
        PlayerArtworkAnchor(owner, mediaId, layout.artworkScreenBounds(view), bitmap, radiusPx) {
          layout.takeIf { it.isAttached }?.artworkScreenBounds(view)
        },
      )
    }
  }

  SideEffect { coordinates?.let(::updateAnchor) }
  DisposableEffect(destination, owner) {
    onDispose { PlayerArtworkTransitions.remove(destination, owner) }
  }
  return this
    .onGloballyPositioned { layout ->
      coordinates = layout
      updateAnchor(layout)
    }
    .graphicsLayer {
      val motion = PlayerArtworkTransitions.motion
      alpha = if (motion?.destination == destination && motion.source.mediaId == mediaId) 0f else 1f
    }
}

@Composable
internal fun PlayerArtworkTransitionOverlay(
  destination: PlayerArtworkDestination,
  modifier: Modifier = Modifier,
) {
  val motion = PlayerArtworkTransitions.motion?.takeIf { it.destination == destination } ?: return
  val bitmap = motion.source.bitmap ?: return
  val session by PlaybackSession.state.collectAsState()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val reducedMotion = AppMotion.shouldReduceMotion()
  val density = LocalDensity.current
  val view = LocalView.current
  var origin by remember { mutableStateOf(Offset.Zero) }

  LaunchedEffect(motion.id, session.currentItem?.stableId, lifecycle) {
    if (session.currentItem?.stableId != motion.source.mediaId) {
      PlayerArtworkTransitions.finish(motion.id)
      return@LaunchedEffect
    }
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      try {
        val target = withTimeoutOrNull(1_000) {
          snapshotFlow { PlayerArtworkTransitions.anchor(destination)?.takeIf { it.mediaId == motion.source.mediaId } }
            .filterNotNull()
            .first()
        }
        if (target != null) {
          // A re-entry into an already-READY session is showing the same frame the poster depicts,
          // so there is nothing to cross-fade. Snapping keeps the extra 320ms off the open.
          val alreadyLive = session.phase == PlaybackPhase.READY || session.phase == PlaybackPhase.BACKGROUND
          if (reducedMotion || alreadyLive) motion.progress.snapTo(1f)
          else motion.progress.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        }
      } finally {
        PlayerArtworkTransitions.finish(motion.id)
      }
    }
  }

  val target = PlayerArtworkTransitions.anchor(destination)?.takeIf { it.mediaId == motion.source.mediaId }
    ?: if (destination == PlayerArtworkDestination.MINI) motion.source else return
  val progress = motion.progress.value
  val bounds = lerp(motion.source.bounds, target.bounds, progress)
  val radius = motion.source.cornerRadius + (target.cornerRadius - motion.source.cornerRadius) * progress
  Box(
    modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.artworkScreenBounds(view).topLeft },
  ) {
    Image(
      bitmap = remember(bitmap) { bitmap.asImageBitmap() },
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .offset { IntOffset((bounds.left - origin.x).roundToInt(), (bounds.top - origin.y).roundToInt()) }
        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        .clip(RoundedCornerShape(with(density) { radius.toDp() })),
    )
  }
}

@Composable
internal fun Modifier.swipeDownToMiniPlayer(
  enabled: Boolean,
  onMinimize: () -> Boolean,
): Modifier {
  var dragOffset by remember { mutableFloatStateOf(0f) }
  var returnJob by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()
  val minimize by rememberUpdatedState(onMinimize)
  val threshold = with(LocalDensity.current) { 80.dp.toPx() }
  val reducedMotion = AppMotion.shouldReduceMotion()
  DisposableEffect(Unit) {
    onDispose { returnJob?.cancel() }
  }

  fun restorePosition() {
    returnJob?.cancel()
    returnJob = scope.launch {
      if (reducedMotion) dragOffset = 0f
      else Animatable(dragOffset).animateTo(0f, spring(stiffness = 500f)) { dragOffset = value }
    }
  }

  LaunchedEffect(enabled) {
    if (!enabled) {
      returnJob?.cancel()
      dragOffset = 0f
    }
  }

  return this
    .graphicsLayer { translationY = if (reducedMotion) 0f else dragOffset }
    .pointerInput(enabled, threshold) {
      if (!enabled) return@pointerInput
      detectVerticalDragGestures(
        onDragStart = { returnJob?.cancel() },
        onDragCancel = { restorePosition() },
        onDragEnd = {
          if (dragOffset >= threshold) minimize()
          restorePosition()
        },
        onVerticalDrag = { change, amount ->
          change.consume()
          dragOffset = (dragOffset + amount).coerceIn(0f, size.height.toFloat())
        },
      )
    }
}