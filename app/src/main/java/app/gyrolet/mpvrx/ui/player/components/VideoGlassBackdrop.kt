/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.components

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import app.gyrolet.mpvrx.ui.player.MPVView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/** Maps the live GPU layer across the native video host and the controls' ComposeView. */
@Stable
internal class VideoGlassSource(
  val scene: LayerBackdrop?,
) {
  var boundsInWindow by mutableStateOf(Rect.Zero)
  var originInWindow by mutableStateOf(Offset.Zero)

  fun DrawScope.drawVideo() {
    drawRect(Color.Black)
    val layer = scene?.graphicsLayer ?: return
    // Use the *recorded* GPU layer size, not the Android parent view's layout size. In HDR
    // TextureView mode the recording can be resized/recreated independently (and the video
    // can be zoomed), so the old ratio stretched colors over unrelated controls.
    val content = layer.size
    val bounds = boundsInWindow
    if (content.width <= 0 || content.height <= 0 || bounds.width <= 0f || bounds.height <= 0f) return
    withTransform({
      translate(bounds.left - originInWindow.x, bounds.top - originInWindow.y)
      scale(bounds.width / content.width, bounds.height / content.height, pivot = Offset.Zero)
    }) {
      // A graphics-layer reference, not a snapshot: TextureView frame updates flow through the
      // RenderThread layer graph at display cadence without CPU readback or Compose state updates.
      drawLayer(layer)
    }
  }
}

@Composable
internal fun rememberVideoGlassSource(videoView: MPVView?): VideoGlassSource =
  rememberViewGlassSource(videoView, videoView?.videoBackdrop)

/** [scene] is recorded in [view]'s own composition, e.g. the video host or the album art layer. */
@Composable
internal fun rememberViewGlassSource(
  view: View?,
  scene: LayerBackdrop?,
): VideoGlassSource {
  val source = remember(view, scene) { VideoGlassSource(scene) }
  DisposableEffect(view, source) {
    if (view == null || scene == null) return@DisposableEffect onDispose {}
    val location = IntArray(2)

    fun updateBounds() {
      view.getLocationInWindow(location)
      source.boundsInWindow =
        if (view.isShown) {
          Rect(
            location[0].toFloat(),
            location[1].toFloat(),
            location[0] + view.width * view.scaleX,
            location[1] + view.height * view.scaleY,
          )
        } else {
          Rect.Zero
        }
    }
    val observer = view.viewTreeObserver
    val listener =
      ViewTreeObserver.OnPreDrawListener {
        updateBounds()
        true
      }
    updateBounds()
    observer.addOnPreDrawListener(listener)
    onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
  }
  return source
}

/** Records the same GPU video layer in control-window coordinates, without displaying it twice. */
@Composable
internal fun VideoGlassBackdropSource(
  source: VideoGlassSource,
  backdrop: LayerBackdrop,
) {
  Box(
    Modifier
      .fillMaxSize()
      .onGloballyPositioned { source.originInWindow = it.positionInWindow() }
      .layerBackdrop(backdrop),
  )
}
