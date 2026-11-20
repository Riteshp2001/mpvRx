/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Floating-lyrics design and motion based on Mochy Music (GPL-3.0):
 * https://github.com/realidkroo/Mochy-Music/tree/bde01cb4321895c406ca93679462447236c2b2c3/app/src/main/kotlin/com/mochyy/mochymusic/floating
 * Adapted to mpvRx's playback, accessibility and window lifecycle.
 */

package app.gyrolet.mpvrx.ui.player.floating

import android.animation.ValueAnimator
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.AudioPreferences
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.ui.player.PlaybackPhase
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import app.gyrolet.mpvrx.ui.theme.MpvrxTheme
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class FloatingLyricsWindow(
  context: Context,
  private val preferences: AudioPreferences,
  private val playerPreferences: PlayerPreferences,
  private val onDismiss: () -> Unit,
  private val onDisable: () -> Unit,
  private val onOpenPlayer: () -> Unit,
  private val onTogglePlay: () -> Unit,
  private val onPrevious: () -> Unit,
  private val onNext: () -> Unit,
  private val onSeek: (Long) -> Unit,
  private val onUnavailable: (RuntimeException) -> Unit,
) : FloatingPillHost {
  private val display = checkNotNull(
    context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY),
  ) { "No display is available for floating lyrics" }
  private val displayContext = context.createDisplayContext(display)
  private val windowContext =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else displayContext
  private val windows = checkNotNull(windowContext.getSystemService(WindowManager::class.java))
  private val owner = OverlayOwner()
  private val state = FloatingPillState()
  private val safeBounds = Rect()
  private var screenWidth = 0
  private var screenHeight = 0
  private var bottomInset = 0
  private val density: Float get() = windowContext.resources.displayMetrics.density
  private fun Dp.pixels(): Int = (value * density).roundToInt()
  private val margin get() = FloatingPillMetrics.Margin.pixels()
  private val cover get() = FloatingPillMetrics.Cover.pixels()
  private val root = DragFrame(windowContext).apply { clipChildren = false; clipToPadding = false }
  private val compose = ComposeView(windowContext)
  private val targets = FloatingLyricsDropTargets(windowContext) { reducedMotion() }
  private var removed = false
  private var added = false
  private var targetsAdded = false
  private var callbacksRegistered = false
  private var landing = false
  private var dropping = false
  private var coverPosition = Offset.Zero
  /** Fixed for given metrics: the open pill fits on either side of the cover's centre. */
  private var frameWidth = 0
  private var frameHeight = 0
  private val pillWidth get() = state.metrics.width(FloatingPillShape.OPEN).pixels()
  private var slots = 2
  private var sentSize = false
  private var sentPosition = false
  private val flushFrame = Runnable { place() }
  private val relayoutDone = ViewTreeObserver.OnPreDrawListener {
    // The last WindowManager change is laid out now, so one that had to wait can follow.
    if (sentSize || sentPosition) {
      sentSize = false
      sentPosition = false
      root.removeCallbacks(flushFrame)
      root.postOnAnimation(flushFrame)
    }
    true
  }
  private val flight = FloatingPillFlight(::moveCover)
  private val params = WindowManager.LayoutParams(
    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
      WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
      WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = Gravity.TOP or Gravity.LEFT
    title = windowContext.getString(R.string.pref_floating_lyrics_title)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
  }
  private val targetParams = WindowManager.LayoutParams(
    1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
      WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
      WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT,
  ).apply {
    gravity = Gravity.TOP or Gravity.LEFT
    title = windowContext.getString(R.string.pref_floating_lyrics_title)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
  }
  private val configuration = object : ComponentCallbacks {
    override fun onConfigurationChanged(newConfig: Configuration) {
      if (!removed) refreshSafely()
    }
    override fun onLowMemory() = Unit
  }

  init {
    state.dockEnd = preferences.floatingLyricsDockEnd.get()
    refreshGeometry(initial = true)
  }

  fun update(transform: (FloatingLyricsModel) -> FloatingLyricsModel) {
    if (!removed) state.model = transform(state.model)
  }

  fun show(): Boolean {
    if (removed) return false
    if (added) return true
    root.setViewTreeLifecycleOwner(owner)
    root.setViewTreeSavedStateRegistryOwner(owner)
    root.viewTreeObserver.addOnPreDrawListener(relayoutDone)
    compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    compose.setContent {
      MpvrxTheme(hostToasts = false) {
        val displayMode by preferences.lyricsTranslationDisplayMode.collectAsState()
        FloatingLyricsPill(state, displayMode, this@FloatingLyricsWindow)
      }
    }
    root.addView(
      compose,
      FrameLayout.LayoutParams(pillWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.LEFT)
        .apply { leftMargin = pillLeft() },
    )
    return try {
      owner.resume()
      // Added first, so the pill's window stays above the drop targets.
      windows.addView(targets.view, targetParams)
      targetsAdded = true
      windows.addView(root, params)
      added = true
      windowContext.registerComponentCallbacks(configuration)
      callbacksRegistered = true
      true
    } catch (error: WindowManager.BadTokenException) {
      failWindow(error); false
    } catch (error: WindowManager.InvalidDisplayException) {
      failWindow(error); false
    } catch (error: SecurityException) {
      failWindow(error); false
    } catch (error: IllegalStateException) {
      failWindow(error); false
    } catch (error: IllegalArgumentException) {
      failWindow(error); false
    }
  }

  fun remove() {
    if (removed) return
    removed = true
    flight.stop()
    root.removeCallbacks(flushFrame)
    root.release()
    targets.hideNow()
    compose.animate().cancel()
    root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(relayoutDone)
    if (callbacksRegistered) {
      callbacksRegistered = false
      windowContext.unregisterComponentCallbacks(configuration)
    }
    if (added) {
      added = false
      removeWindow(root)
    }
    if (targetsAdded) {
      targetsAdded = false
      removeWindow(targets.view)
    }
    compose.disposeComposition()
    owner.destroy()
  }

  private fun removeWindow(view: View) {
    try {
      windows.removeViewImmediate(view)
    } catch (error: IllegalArgumentException) {
      android.util.Log.w(TAG, "Floating lyrics window was already removed", error)
    } catch (error: SecurityException) {
      android.util.Log.w(TAG, "Could not remove floating lyrics window", error)
    }
  }

  private fun failWindow(error: RuntimeException) {
    if (removed) return
    remove()
    onUnavailable(error)
  }

  private fun reducedMotion(): Boolean =
    playerPreferences.reduceMotion.get() || !ValueAnimator.areAnimatorsEnabled()

  private fun refreshSafely() {
    if (removed) return
    try {
      refreshGeometry(initial = false)
    } catch (error: WindowManager.InvalidDisplayException) {
      failWindow(error)
    } catch (error: SecurityException) {
      failWindow(error)
    } catch (error: IllegalStateException) {
      failWindow(error)
    } catch (error: IllegalArgumentException) {
      failWindow(error)
    }
  }

  private fun refreshGeometry(initial: Boolean) {
    val previousWidth = screenWidth
    val previousHeight = screenHeight
    val previousBounds = Rect(safeBounds)
    val left: Int
    val top: Int
    val right: Int
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val metrics = windows.maximumWindowMetrics
      val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
      )
      screenWidth = metrics.bounds.width()
      screenHeight = metrics.bounds.height()
      left = insets.left
      top = insets.top
      right = insets.right
      bottomInset = insets.bottom
    } else {
      // Before API 30 an overlay only sees insets relative to wherever it currently sits.
      val metrics = windowContext.resources.displayMetrics
      screenWidth = metrics.widthPixels
      screenHeight = metrics.heightPixels
      left = 0
      top = 24.dp.pixels()
      right = 0
      bottomInset = 48.dp.pixels()
    }
    safeBounds.set(left + 8.dp.pixels(), top + 6.dp.pixels(), screenWidth - right - 8.dp.pixels(), screenHeight - bottomInset - 6.dp.pixels())
    val displayWidth = (screenWidth - left - right) / density
    val displayHeight = safeBounds.height() / density
    check(displayWidth >= 212f && displayHeight >= 106f) { "Display is too small for floating lyrics" }
    val pitch = maxOf(21f, windowContext.resources.configuration.fontScale * 19f + 2f)
    check(displayHeight >= maxOf(62f, pitch + 18f) + 40f) { "Display cannot fit floating lyrics at the current font scale" }
    val base = maxOf(58f, pitch * 2f + 16f)
    val maxSlots = ((displayHeight - 40f - 2f - 16f) / pitch).toInt().coerceIn(1, 3)
    val metrics = FloatingPillMetrics(
      bubbleWidth = (displayWidth - 172f).coerceIn(40f, 226f).dp,
      rowPitch = pitch.dp,
      baseHeight = base.dp,
      maxSlots = maxSlots,
    )
    val screenChanged = previousWidth != screenWidth || previousHeight != screenHeight
    val metricsChanged = metrics != state.metrics
    // Theme changes and this overlay's own relayouts also deliver configuration callbacks.
    if (!initial && !screenChanged && previousBounds == safeBounds && !metricsChanged) return
    val resetGeometry = initial || screenChanged || metricsChanged
    if (resetGeometry) {
      flight.stop()
      root.release()
      landing = false
      state.lifted = false
      state.tucked = false
      targets.hideNow()
    }
    state.metrics = metrics
    slots = slots.coerceAtMost(maxSlots)
    frameWidth = 2 * (pillWidth - margin) - cover
    val height = metrics.height(currentShape(), slots).pixels()
    // A bounds-only update must not shrink a frame while the controls are still closing.
    frameHeight = if (resetGeometry) height else maxOf(frameHeight, height)
    if (resetGeometry) {
      val y = if (preferences.floatingLyricsY.isSet()) {
        safeBounds.top + (safeBounds.height() - (frameHeight - margin * 2)).coerceAtLeast(0) *
          (preferences.floatingLyricsY.get().coerceIn(0, 1_000) / 1_000f)
      } else screenHeight / 6f + margin
      coverPosition = Offset(restX(state.dockEnd), y)
    }
    if (!root.dragging && !flight.running) coverPosition = coverPosition.copy(y = clampY(coverPosition.y, frameHeight))
    placeTargets()
    place()
  }

  private fun currentShape(): FloatingPillShape = when {
    state.tucked -> FloatingPillShape.COVER
    state.expanded -> FloatingPillShape.OPEN
    else -> FloatingPillShape.PILL
  }

  override fun reserveFrame(shape: FloatingPillShape, slots: Int) {
    if (removed) return
    this.slots = slots.coerceIn(1, state.metrics.maxSlots)
    resize(maxOf(frameHeight, state.metrics.height(shape, this.slots).pixels()))
    if (!root.dragging && !flight.running && !dropping) makeRoom()
  }

  override fun fitFrame(shape: FloatingPillShape, slots: Int) {
    if (removed || shape != currentShape()) return
    this.slots = slots.coerceIn(1, state.metrics.maxSlots)
    resize(state.metrics.height(shape, this.slots).pixels())
  }

  private fun resize(height: Int) {
    if (removed || frameHeight == height) return
    frameHeight = height
    place()
  }

  private fun moveCover(position: Offset) {
    if (removed) return
    coverPosition = position
    place()
  }

  /** The pill's left edge in the frame; on either side its cover sits on the frame's centre. */
  private fun pillLeft(): Int = if (state.dockEnd) 0 else frameWidth - pillWidth

  private fun place() {
    if (removed) return
    val layout = compose.layoutParams as? FrameLayout.LayoutParams
    val left = pillLeft()
    if (layout != null && (layout.width != pillWidth || layout.leftMargin != left)) {
      layout.width = pillWidth
      layout.leftMargin = left
      compose.layoutParams = layout
    }
    updateWindow(
      frameWidth,
      frameHeight,
      coverPosition.x.roundToInt() - (pillWidth - margin - cover),
      coverPosition.y.roundToInt() - margin,
    )
  }

  /**
   * Android runs a 400 ms system move animation for an overlay relayout that changes both its size
   * and its position (WindowState.handleWindowMovedIfNeeded), which the content cannot offset.
   * Only one kind of change goes into a relayout; the other follows once that one is laid out.
   */
  private fun updateWindow(width: Int, height: Int, x: Int, y: Int) {
    if (removed) return
    if (!added) {
      params.width = width
      params.height = height
      params.x = x
      params.y = y
      return
    }
    val resize = params.width != width || params.height != height
    val move = params.x != x || params.y != y
    var changed = false
    if (resize && !sentPosition) {
      params.width = width
      params.height = height
      sentSize = true
      changed = true
    }
    if (move && !sentSize) {
      params.x = x
      params.y = y
      sentPosition = true
      changed = true
    }
    if (!changed) return
    try {
      windows.updateViewLayout(root, params)
    } catch (error: SecurityException) {
      failWindow(error)
    } catch (error: WindowManager.BadTokenException) {
      failWindow(error)
    } catch (error: WindowManager.InvalidDisplayException) {
      failWindow(error)
    } catch (error: IllegalArgumentException) {
      failWindow(error)
    }
  }

  private fun placeTargets() {
    val height = targets.windowHeight(bottomInset)
    val y = screenHeight - height
    targets.origin = Offset(0f, y.toFloat())
    if (targetParams.width == screenWidth && targetParams.height == height && targetParams.y == y) return
    targetParams.width = screenWidth
    targetParams.height = height
    targetParams.x = 0
    targetParams.y = y
    if (!targetsAdded) return
    try {
      windows.updateViewLayout(targets.view, targetParams)
    } catch (error: SecurityException) {
      failWindow(error)
    } catch (error: WindowManager.BadTokenException) {
      failWindow(error)
    } catch (error: WindowManager.InvalidDisplayException) {
      failWindow(error)
    } catch (error: IllegalArgumentException) {
      failWindow(error)
    }
  }

  private fun makeRoom() {
    val y = clampY(coverPosition.y, frameHeight)
    if (y == coverPosition.y) return
    // The frame already has its new height, so only its position is animated here.
    fly(Offset(coverPosition.x, y), 700f, 1f, onLanded = ::savePosition)
  }

  private fun settle(velocity: Velocity) {
    if (removed) return
    state.dockEnd = coverPosition.x + cover / 2 + velocity.x * 0.14f > screenWidth / 2f
    val target = Offset(
      restX(state.dockEnd),
      clampY(coverPosition.y + velocity.y * 0.14f, state.metrics.height(FloatingPillShape.PILL, slots).pixels()),
    )
    landing = true
    // Switching sides only mirrors the pill inside the frame; the window stays where it is.
    place()
    fly(
      target, 420f, 0.74f, velocity,
      wall = target.x + if (state.dockEnd) 5.dp.pixels() else -5.dp.pixels(),
      onHit = ::popOut,
      onLanded = { popOut(); savePosition() },
    )
  }

  private fun popOut() {
    if (!landing || removed) return
    landing = false
    state.tucked = false
    state.lifted = false
  }

  private fun fly(
    target: Offset,
    stiffness: Float,
    damping: Float,
    velocity: Velocity = Velocity.Zero,
    wall: Float = Float.NaN,
    onHit: (() -> Unit)? = null,
    onLanded: (() -> Unit)? = null,
  ) {
    if (removed) return
    if (reducedMotion()) {
      flight.stop()
      moveCover(target)
      onHit?.invoke()
      onLanded?.invoke()
    } else flight.fly(coverPosition, target, stiffness, damping, velocity, wall, onHit, onLanded)
  }

  private fun vanish(drop: FloatingPillDrop) {
    if (removed) return
    dropping = true
    targets.swallow(drop)
    if (reducedMotion()) {
      dismiss(drop == FloatingPillDrop.NEVER)
      return
    }
    targets.center(drop)?.let { fly(it - Offset(cover / 2f, cover / 2f), 1_100f, 1f) }
    compose.pivotX = if (state.dockEnd) pillWidth - margin - cover / 2f else margin + cover / 2f
    compose.pivotY = margin + cover / 2f
    compose.animate().scaleX(0.2f).scaleY(0.2f).alpha(0f).setDuration(220)
      .setInterpolator(AccelerateInterpolator())
      .withEndAction { if (!removed) dismiss(drop == FloatingPillDrop.NEVER) }.start()
  }

  private fun restX(end: Boolean): Float = if (end) (safeBounds.right - cover).toFloat() else safeBounds.left.toFloat()

  private fun clampY(y: Float, height: Int): Float =
    y.coerceIn(safeBounds.top.toFloat(), maxOf(safeBounds.top, safeBounds.bottom - height + margin * 2).toFloat())

  private fun savePosition() {
    if (removed) return
    preferences.floatingLyricsDockEnd.set(state.dockEnd)
    val height = state.metrics.height(FloatingPillShape.PILL, slots).pixels() - margin * 2
    val room = (safeBounds.height() - height).coerceAtLeast(1)
    preferences.floatingLyricsY.set(((coverPosition.y - safeBounds.top) * 1_000f / room).roundToInt().coerceIn(0, 1_000))
  }

  override fun touch() {
    if (!removed) state.touchTick++
  }

  override fun toggleExpanded() {
    if (removed || dropping) return
    touch()
    state.expanded = !state.expanded
  }

  override fun collapse() {
    if (!removed) state.expanded = false
  }

  override fun openPlayer() {
    if (!removed) { collapse(); onOpenPlayer() }
  }

  private fun canControl(generation: Long): Boolean {
    val session = PlaybackSession.state.value
    return !removed && !dropping && state.model.canControl && state.model.generation == generation &&
      PlaybackSession.isCurrentGeneration(generation) &&
      (session.phase == PlaybackPhase.READY || session.phase == PlaybackPhase.BACKGROUND)
  }

  override fun togglePlay(generation: Long) {
    if (canControl(generation)) { touch(); onTogglePlay() }
  }

  override fun previous(generation: Long) {
    if (canControl(generation) && state.model.canPrevious) { touch(); onPrevious() }
  }

  override fun next(generation: Long) {
    if (canControl(generation) && state.model.canNext) { touch(); onNext() }
  }

  override fun seekTo(position: Long, generation: Long) {
    if (canControl(generation) && state.model.canSeek) {
      touch()
      state.model = state.model.copy(seekTick = state.model.seekTick + 1L)
      onSeek(position.coerceIn(0, state.model.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }
  }

  override fun dock(right: Boolean) {
    if (removed || dropping) return
    flight.stop()
    state.dockEnd = right
    coverPosition = Offset(restX(right), clampY(coverPosition.y, frameHeight))
    place()
    touch()
    savePosition()
  }

  override fun dismiss(permanently: Boolean) {
    if (!removed) {
      if (permanently) onDisable() else onDismiss()
    }
  }

  private inner class DragFrame(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var tracker: VelocityTracker? = null
    private var fingerStart = Offset.Zero
    private var carryStart = Offset.Zero
    private var magnet: FloatingPillDrop? = null
    private var seeking = false
    private var cancelled = false
    var dragging = false
      private set

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
      if (removed || dropping) return false
      if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { collapse(); return false }
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        val x = event.x - compose.left - compose.translationX
        val y = event.y - compose.top - compose.translationY
        dragging = false
        magnet = null
        // Only the pill is interactive; the rest of the frame is shadow margin or off-screen.
        if (x !in 0f..compose.width.toFloat() || y !in 0f..compose.height.toFloat()) {
          cancelled = true
          return false
        }
        fingerStart = Offset(event.rawX, event.rawY)
        cancelled = false
        seeking = state.expanded && state.model.canSeek &&
          abs(y - state.metrics.barY(slots.toFloat()).pixels()) <= FloatingPillMetrics.SeekReach.pixels()
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        touch()
      } else if (event.pointerCount > 1) {
        cancelled = true
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        if (dragging) drop(released = false)
      }
      if (cancelled) return true
      val motion = MotionEvent.obtain(event)
      motion.setLocation(event.rawX, event.rawY)
      tracker?.addMovement(motion)
      motion.recycle()
      if (!dragging && !seeking && event.actionMasked == MotionEvent.ACTION_MOVE &&
        hypot(event.rawX - fingerStart.x, event.rawY - fingerStart.y) > slop
      ) {
        dragging = true
        carryStart = coverPosition
        fingerStart = Offset(event.rawX, event.rawY)
        flight.stop()
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        landing = false
        state.expanded = false
        state.tucked = true
        state.lifted = true
        if (!removed) targets.show(bottomInset)
      }
      if (!dragging) {
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) release()
        return handled
      }
      when (event.actionMasked) {
        MotionEvent.ACTION_MOVE -> drag(carryStart + Offset(event.rawX, event.rawY) - fingerStart)
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drop(event.actionMasked == MotionEvent.ACTION_UP)
      }
      return true
    }

    private fun drag(free: Offset) {
      val over = targets.hover(free + Offset(cover / 2f, cover / 2f), cover)
      when {
        over != null && over != magnet -> {
          magnet = over
          targets.restSpot(over, cover)?.let { fly(it, 900f, 0.72f) }
        }
        over != null -> Unit
        magnet != null -> { magnet = null; fly(free, 1_400f, 1f) }
        flight.running -> flight.retarget(free)
        else -> moveCover(free)
      }
    }

    private fun drop(released: Boolean) {
      dragging = false
      val velocity = if (released) tracker?.let {
        it.computeCurrentVelocity(1_000, 1_800f * density)
        Velocity(it.xVelocity, it.yVelocity)
      } ?: Velocity.Zero else Velocity.Zero
      tracker?.recycle()
      tracker = null
      val drop = magnet.takeIf { released }
      magnet = null
      flight.stop()
      if (drop != null) vanish(drop)
      else { targets.hide(); settle(velocity) }
    }

    fun release() {
      dragging = false
      seeking = false
      cancelled = true
      tracker?.recycle()
      tracker = null
    }
  }

  private companion object {
    const val TAG = "FloatingLyricsWindow"
  }
}

private class FloatingPillFlight(private val onMove: (Offset) -> Unit) : Choreographer.FrameCallback {
  private val frames = Choreographer.getInstance()
  private var position = Offset.Zero
  private var goal = Offset.Zero
  private var velocity = Offset.Zero
  private var stiffness = 420f
  private var damping = 0f
  private var lastFrame = 0L
  private var wall = Float.NaN
  private var wallSide = 0f
  private var onHit: (() -> Unit)? = null
  private var onLanded: (() -> Unit)? = null
  var running = false
    private set

  fun fly(
    from: Offset, to: Offset, stiffness: Float, dampingRatio: Float, velocity: Velocity,
    wall: Float, onHit: (() -> Unit)?, onLanded: (() -> Unit)?,
  ) {
    goal = to
    this.stiffness = stiffness
    damping = 2f * dampingRatio * sqrt(stiffness)
    this.onHit = onHit
    this.onLanded = onLanded
    if (!running) {
      position = from
      this.velocity = Offset(velocity.x, velocity.y)
      lastFrame = 0L
      running = true
      frames.postFrameCallback(this)
    }
    wallSide = if (wall.isNaN()) 0f else kotlin.math.sign(wall - to.x)
    this.wall = if ((position.x - to.x) * wallSide < 0) wall else Float.NaN
  }

  fun retarget(goal: Offset) { this.goal = goal }

  fun stop() {
    running = false
    onHit = null
    onLanded = null
    frames.removeFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
    if (!running) return
    var remaining = if (lastFrame == 0L) 1f / 120 else
      ((frameTimeNanos - lastFrame) / 1_000_000_000f).coerceIn(0f, 1f / 30)
    lastFrame = frameTimeNanos
    while (remaining > 0) {
      val dt = min(remaining, 1f / 240)
      velocity += ((goal - position) * stiffness - velocity * damping) * dt
      position += velocity * dt
      if (!wall.isNaN() && (position.x - wall) * wallSide > 0) {
        position = position.copy(x = wall)
        if (velocity.x * wallSide > 0) velocity = velocity.copy(x = -velocity.x * 0.32f)
      }
      remaining -= dt
    }
    if (wallSide != 0f && (position.x - goal.x) * wallSide >= 0) hit()
    val landed = abs(position.x - goal.x) < 0.5f && abs(position.y - goal.y) < 0.5f &&
      abs(velocity.x) < 24f && abs(velocity.y) < 24f
    if (!landed) {
      onMove(position)
      if (running) frames.postFrameCallback(this)
    } else {
      running = false
      onMove(goal)
      hit()
      val done = onLanded
      onLanded = null
      done?.invoke()
    }
  }

  private fun hit() {
    val action = onHit
    onHit = null
    action?.invoke()
  }
}

private class OverlayOwner : SavedStateRegistryOwner {
  private val registry = LifecycleRegistry(this)
  private val savedState = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = registry
  override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
  init {
    savedState.performAttach()
    savedState.performRestore(null)
    registry.currentState = Lifecycle.State.CREATED
  }
  fun resume() { registry.currentState = Lifecycle.State.RESUMED }
  fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
