/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.debug

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.MainThread
import com.ms.square.debugoverlay.DebugOverlay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * In-app, draggable, translucent mini-HUD; the original DebugOverlay diagnostic panel
 * remains available by tapping the HUD. Attaching to an Activity content root keeps this
 * behind Android system UI, requires no draw-over-other-apps permission, and doesn't intercept
 * video/list gestures outside the small HUD bounds.
 */
internal object CompactPerformanceHud {
  private const val REFRESH_MS = 750L
  private const val PREFS = "mpvrx_performance_hud"
  private const val POSITION_X = "x"
  private const val POSITION_Y = "y"

  private var active: Session? = null

  @MainThread
  fun show(activity: Activity) {
    if (!PerformanceDiagnostics.enabled) return
    // Opening the detailed DebugOverlay activity should not render a second HUD over its controls.
    if (activity.javaClass.name.startsWith("com.ms.square.debugoverlay.")) return
    if (active?.activity === activity) return
    hide()
    val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
    val density = activity.resources.displayMetrics.density
    fun dp(value: Float): Int = (value * density).roundToInt()

    val textView = TextView(activity).apply {
      textSize = 10f
      typeface = Typeface.create("monospace", Typeface.NORMAL)
      setTextColor(Color.rgb(246, 248, 252))
      includeFontPadding = false
      setPadding(dp(9f), dp(7f), dp(9f), dp(7f))
      maxWidth = dp(230f)
      background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(11f).toFloat()
        // ~68% opacity: video and list contents remain visible underneath.
        setColor(Color.argb(174, 17, 22, 32))
        setStroke(dp(1f).coerceAtLeast(1), Color.argb(70, 234, 240, 255))
      }
      elevation = dp(3f).toFloat()
      contentDescription = "mpvRx live performance monitor. Drag to move; tap to open diagnostics."
      text = "mpvRx  •  starting metrics\nDrag to move  •  tap for logs"
      setOnClickListener { DebugOverlay.openPanel(activity) }
    }

    root.addView(
      textView,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { gravity = Gravity.TOP or Gravity.START },
    )

    val session = Session(activity, root, textView)
    active = session
    textView.post {
      if (active === session) session.restorePosition()
    }
    session.start()
  }

  @MainThread
  fun hide(activity: Activity? = null) {
    val session = active ?: return
    if (activity != null && session.activity !== activity) return
    active = null
    session.stop()
  }

  private class Session(
    val activity: Activity,
    private val parent: FrameLayout,
    private val view: TextView,
  ) {
    private val preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
    private var lastElapsed = SystemClock.elapsedRealtime()
    private var lastProcessCpu = Process.getElapsedCpuTime()
    private var sampledFrames = 0
    private var dragging = false
    private var touchX = 0f
    private var touchY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        if (active !== this@Session || !PerformanceDiagnostics.enabled) return
        sampledFrames++
        choreographer.postFrameCallback(this)
      }
    }
    private val updateTask = object : Runnable {
      override fun run() {
        if (active !== this@Session || !PerformanceDiagnostics.enabled) return
        val elapsed = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val period = (elapsed - lastElapsed).coerceAtLeast(1L)
        val fps = ((sampledFrames * 1000f) / period).roundToInt().coerceIn(0, 240)
        // Process CPU percentage can exceed 100% when more than one core is busy.
        val cpuPercent = (((cpu - lastProcessCpu).coerceAtLeast(0L) * 100f) / period).roundToInt()
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        lastElapsed = elapsed
        lastProcessCpu = cpu
        sampledFrames = 0
        view.text = buildString {
          append("mpvRx  UI ")
          append(fps)
          append("fps  CPU ")
          append(cpuPercent)
          append('%')
          append("\nHeap ")
          append(heapMb)
          append("MB  ")
          append(PerformanceDiagnostics.compactHudCounts())
          append("\nDrag to move  •  tap for logs")
        }
        view.postDelayed(this, REFRESH_MS)
      }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun start() {
      view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
          MotionEvent.ACTION_DOWN -> {
            touchX = event.rawX
            touchY = event.rawY
            initialX = view.x
            initialY = view.y
            dragging = false
            true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - touchX
            val dy = event.rawY - touchY
            if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
            if (dragging) moveTo(initialX + dx, initialY + dy)
            true
          }
          MotionEvent.ACTION_UP -> {
            if (dragging) {
              preferences.edit().putFloat(POSITION_X, view.x)
                .putFloat(POSITION_Y, view.y).apply()
            } else {
              v.performClick()
            }
            dragging = false
            true
          }
          MotionEvent.ACTION_CANCEL -> {
            dragging = false
            true
          }
          else -> false
        }
      }
      choreographer.postFrameCallback(frameCallback)
      view.postDelayed(updateTask, REFRESH_MS)
    }

    fun restorePosition() {
      val density = activity.resources.displayMetrics.density
      val defaultX = (parent.width - view.width - 12f * density).coerceAtLeast(0f)
      val defaultY = 64f * density
      moveTo(
        if (preferences.contains(POSITION_X)) preferences.getFloat(POSITION_X, defaultX) else defaultX,
        preferences.getFloat(POSITION_Y, defaultY),
      )
    }

    private fun moveTo(x: Float, y: Float) {
      if (parent.width <= 0 || parent.height <= 0) return
      view.x = x.coerceIn(0f, (parent.width - view.width).coerceAtLeast(0).toFloat())
      view.y = y.coerceIn(0f, (parent.height - view.height).coerceAtLeast(0).toFloat())
    }

    fun stop() {
      choreographer.removeFrameCallback(frameCallback)
      view.removeCallbacks(updateTask)
      (view.parent as? ViewGroup)?.removeView(view)
    }
  }
}
