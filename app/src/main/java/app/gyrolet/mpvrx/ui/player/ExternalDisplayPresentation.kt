package app.gyrolet.mpvrx.ui.player

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup

class ExternalDisplayPresentation(
  context: Context,
  display: Display,
  private val onSurfaceStateChanged: (ExternalDisplayPresentation, Boolean) -> Unit,
) : Presentation(context, display) {
  private val surfaceOwner = Any()
  private var surfaceAvailable = false
  var isSurfaceReady = false
    private set

  private fun updateSurfaceState(ready: Boolean) {
    if (isSurfaceReady == ready) return
    isSurfaceReady = ready
    onSurfaceStateChanged(this, ready)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window?.decorView?.setBackgroundColor(android.graphics.Color.BLACK)

    val surfaceView = SurfaceView(context)
    surfaceView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    surfaceView.holder.addCallback(
      object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
          surfaceAvailable = true
          updateSurfaceState(
            PlaybackSession.bindSurface(
              surface = holder.surface,
              width = surfaceView.width,
              height = surfaceView.height,
              owner = surfaceOwner,
              ownerIsActive = { surfaceAvailable },
            ),
          )
        }

        override fun surfaceChanged(
          holder: SurfaceHolder,
          format: Int,
          width: Int,
          height: Int,
        ) {
          if (isSurfaceReady) {
            PlaybackSession.resizeSurface(width, height, surfaceOwner)
          } else {
            updateSurfaceState(
              PlaybackSession.bindSurface(
                surface = holder.surface,
                width = width,
                height = height,
                owner = surfaceOwner,
                ownerIsActive = { surfaceAvailable },
              ),
            )
          }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
          surfaceAvailable = false
          PlaybackSession.unbindSurface(surfaceOwner)
          updateSurfaceState(false)
        }
      },
    )
    setContentView(
      surfaceView,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
  }
}
