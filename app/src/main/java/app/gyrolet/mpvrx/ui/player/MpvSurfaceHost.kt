/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import app.gyrolet.mpvrx.preferences.MpvConfigControlledFeatures
import app.gyrolet.mpvrx.preferences.MpvConfigOverridePolicy
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * HDR stays on a native SurfaceView: Android tone-maps TextureView content into the app window's
 * SDR buffer. [liquidGlassInHdr] is the user's explicit opt-in to that trade for live glass.
 */
internal fun shouldUseTextureRendering(
  liquidGlass: Boolean,
  hdrOutput: Boolean,
  liquidGlassInHdr: Boolean,
): Boolean {
  if (!liquidGlass) return false
  if (liquidGlassInHdr) return true
  return !hdrOutput && !MpvConfigOverridePolicy.ownsAny(MpvConfigControlledFeatures.HDR_OUTPUT)
}

/** Keeps one MPV surface owner while choosing a native or Compose-recordable renderer. */
abstract class MpvSurfaceHost(context: Context, attributes: AttributeSet) : FrameLayout(context, attributes) {
  internal var renderSurface: Surface? = null
    private set
  internal var videoBackdrop: LayerBackdrop? by mutableStateOf(null)
    private set
  private var ownsSurface = false
  private var rendererEpoch = 0
  private var textureRendering: Boolean? = null

  // A TextureView layer exists before mpv has submitted its first frame. Never let Kyant sample
  // that blank layer during HDR <-> SDR or Liquid Glass renderer switches.
  private val textureFrameReady = MutableStateFlow(false)
  internal val textureFrameReadyState: StateFlow<Boolean> = textureFrameReady

  internal val isTextureSurface: Boolean
    get() = textureRendering == true

  protected abstract fun onRenderSurfaceAvailable(surface: Surface, width: Int, height: Int)
  protected abstract fun onRenderSurfaceSizeChanged(width: Int, height: Int)
  protected abstract fun onRenderSurfaceDestroyed()

  /** Only changes for an appearance/HDR preference, never when controls appear or disappear. */
  internal fun setTextureRendering(enabled: Boolean) {
    if (textureRendering == enabled) return
    textureRendering = enabled
    textureFrameReady.value = false
    val epoch = ++rendererEpoch
    if (renderSurface != null) onRenderSurfaceDestroyed()
    if (ownsSurface) renderSurface?.release()
    renderSurface = null
    ownsSurface = false
    videoBackdrop = null
    (getChildAt(0) as? ComposeView)?.disposeComposition()
    removeAllViews()
    descendantFocusability = FOCUS_BLOCK_DESCENDANTS

    fun available(surface: Surface, width: Int, height: Int, owned: Boolean) {
      if (epoch != rendererEpoch) {
        if (owned) surface.release()
        return
      }
      renderSurface = surface
      ownsSurface = owned
      onRenderSurfaceAvailable(surface, width, height)
    }

    fun destroyed() {
      if (epoch != rendererEpoch) return
      textureFrameReady.value = false
      onRenderSurfaceDestroyed()
      if (ownsSurface) renderSurface?.release()
      renderSurface = null
      ownsSurface = false
    }

    val child = if (enabled) {
      ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        consumeWindowInsets = false
        setContent {
          val scene = rememberLayerBackdrop()
          DisposableEffect(scene) {
            if (epoch == rendererEpoch) videoBackdrop = scene
            onDispose { if (videoBackdrop === scene) videoBackdrop = null }
          }
          Box(Modifier.fillMaxSize().background(Color.Black).layerBackdrop(scene)) {
            AndroidView(
              modifier = Modifier.fillMaxSize().graphicsLayer {
                // Issue #82's stable compositing path: record the actual TextureView GPU layer.
                // No getBitmap, PixelCopy, timer, or second video decoder is involved.
                alpha = 0.999f
              },
              factory = { viewContext ->
                TextureView(viewContext).apply {
                  isOpaque = true
                  isClickable = false
                  isFocusable = false
                  importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                  surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                      available(Surface(texture), width, height, owned = true)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                      if (epoch == rendererEpoch) onRenderSurfaceSizeChanged(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                      destroyed()
                      return true // TextureView owns and releases the SurfaceTexture after MPV detaches.
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                      if (epoch == rendererEpoch && !textureFrameReady.value) {
                        // Surface attachment alone doesn't guarantee a sampleable HDR/SDR frame.
                        textureFrameReady.value = true
                      }
                    }
                  }
                }
              },
            )
          }
        }
      }
    } else {
      SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
          override fun surfaceCreated(holder: SurfaceHolder) {
            available(holder.surface, this@apply.width, this@apply.height, owned = false)
          }

          override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (epoch == rendererEpoch) onRenderSurfaceSizeChanged(width, height)
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) = destroyed()
        })
      }
    }
    addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }
}
