package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import app.gyrolet.mpvrx.utils.logging.Log

class ExternalDisplayManager(
  context: Context,
  private val onStateChanged: (Boolean) -> Unit,
) {
  private val appContext = context.applicationContext
  private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  private val handler = Handler(Looper.getMainLooper())
  private var presentation: ExternalDisplayPresentation? = null
  private var released = true

  var enabled = true
    set(value) {
      field = value
      reconcile()
    }

  val isActive: Boolean
    get() = presentation?.isSurfaceReady == true

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) = reconcile()

      override fun onDisplayRemoved(displayId: Int) = reconcile()

      override fun onDisplayChanged(displayId: Int) = reconcile()
    }

  fun start() {
    released = false
    displayManager.registerDisplayListener(displayListener, handler)
    reconcile()
  }

  fun release() {
    released = true
    displayManager.unregisterDisplayListener(displayListener)
    presentation?.dismiss()
    presentation = null
    onStateChanged(false)
  }

  private fun reconcile() {
    if (released) return

    val target =
      if (enabled) {
        displayManager
          .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
          .firstOrNull()
      } else {
        null
      }
    val currentDisplayId = presentation?.display?.displayId

    if (target == null || target.displayId != currentDisplayId) {
      presentation?.dismiss()
      presentation = null
      if (currentDisplayId != null) onStateChanged(false)
    }

    if (target != null && presentation == null) {
      Log.d(TAG, "Showing external display on ${target.name} (${target.displayId})")
      val shown = ExternalDisplayPresentation(appContext, target) { source, ready ->
        if (!released && presentation === source) onStateChanged(ready)
      }
      shown.setOnDismissListener {
        if (presentation === shown) {
          presentation = null
          onStateChanged(false)
          reconcile()
        }
      }
      presentation = shown
      try {
        shown.show()
      } catch (error: android.view.WindowManager.InvalidDisplayException) {
        presentation = null
        shown.dismiss()
        onStateChanged(false)
        Log.w(TAG, "External display became unavailable", error)
      }
    }
  }

  companion object {
    private const val TAG = "ExternalDisplayManager"
  }
}
