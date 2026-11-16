/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
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

/** mpvlib 1.1.8.4+: where direct MediaCodec output (vo=mediacodec_embed) needs the video view. */
internal const val ANDROID_VIDEO_RECT = "android-video-rect"

/** `android-video-rect` ("w:h:left:top:right:bottom"): the video bounds within a w*h viewport. */
internal data class MpvVideoRect(
  val viewportWidth: Int,
  val viewportHeight: Int,
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  fun scaledTo(width: Int, height: Int): Rect {
    val sx = width.toDouble() / viewportWidth
    val sy = height.toDouble() / viewportHeight
    fun coord(value: Int, scale: Double) =
      Math.round(value * scale).coerceIn(-MAX_COORD, MAX_COORD).toInt()
    return Rect(coord(left, sx), coord(top, sy), coord(right, sx), coord(bottom, sy))
  }

  companion object {
    // Far beyond any usable zoom, but inside View's measurable size.
    private const val MAX_COORD = 1L shl 22

    fun parse(value: String?): MpvVideoRect? {
      val parts = value?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
      if (parts.size != 6) return null
      val rect = MpvVideoRect(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5])
      return rect.takeIf {
        it.viewportWidth > 0 && it.viewportHeight > 0 && it.right > it.left && it.bottom > it.top
      }
    }
  }
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
  private var osdOverlayEnabled = false
  private var osdOverlayView: SurfaceView? = null
  internal var osdSurface: Surface? = null
    private set
  internal var osdSurfaceWidth = 0
    private set
  internal var osdSurfaceHeight = 0
    private set
  private var videoView: View? = null
  private var videoRect: MpvVideoRect? = null

  // The new TextureView can be attached to mpv before it receives a drawable video frame.
  // Expose its first real buffer update so the controls never sample a blank HDR/SDR layer.
  private val textureFrameReady = MutableStateFlow(false)
  internal val textureFrameReadyState: StateFlow<Boolean> = textureFrameReady

  internal val isTextureSurface: Boolean
    get() = textureRendering == true

  /**
   * HDR color-space/shader changes can replace the pixels behind an existing TextureView
   * without replacing its Android Surface. Hide the old glass sample until that view posts
   * another frame; changing the renderer itself resets this flag in setTextureRendering().
   */
  internal fun invalidateTextureFrameReadiness() {
    if (textureRendering == true) textureFrameReady.value = false
  }

  protected abstract fun onRenderSurfaceAvailable(surface: Surface, width: Int, height: Int)
  protected abstract fun onRenderSurfaceSizeChanged(width: Int, height: Int)
  protected abstract fun onRenderSurfaceDestroyed()

  protected open fun onOsdSurfaceAvailable(surface: Surface, width: Int, height: Int) {}
  protected open fun onOsdSurfaceSizeChanged(width: Int, height: Int) {}
  protected open fun onOsdSurfaceDestroyed() {}

  /**
   * MediaCodec stretches every frame over its whole Surface, so direct output (mediacodec_embed)
   * only shows mpv's aspect, crop, zoom and pan once the video view covers [ANDROID_VIDEO_RECT].
   * The subtitle overlay stays full size. null, as with every GPU output, restores the full view.
   */
  internal fun setVideoRect(value: String?) {
    val rect = MpvVideoRect.parse(value)
    if (rect == videoRect) return
    videoRect = rect
    requestLayout()
  }

  // mpv's viewport stays the whole player while the video view itself is moved and resized.
  private fun reportedSize(surfaceWidth: Int, surfaceHeight: Int): Pair<Int, Int> =
    if (videoRect != null && width > 0 && height > 0) width to height else surfaceWidth to surfaceHeight

  private fun videoBounds(): Rect? {
    val rect = videoRect ?: return null
    if (measuredWidth <= 0 || measuredHeight <= 0) return null
    return rect.scaledTo(measuredWidth, measuredHeight).takeIf { it.width() > 0 && it.height() > 0 }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    val bounds = videoBounds() ?: return
    videoView?.measure(
      MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY),
    )
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    val bounds = videoBounds() ?: return
    videoView?.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    // A moved video view may keep its size, so report the new player size from here.
    if (videoRect != null && renderSurface != null && w > 0 && h > 0) onRenderSurfaceSizeChanged(w, h)
  }

  /**
   * A transparent SurfaceView above the video for subtitles and OSD, which direct Dolby Vision
   * output needs. It exists only with the native SurfaceView renderer.
   */
  internal fun setOsdOverlay(enabled: Boolean) {
    if (osdOverlayEnabled == enabled) return
    osdOverlayEnabled = enabled
    syncOsdOverlay()
  }

  private fun syncOsdOverlay() {
    val wanted = osdOverlayEnabled && textureRendering == false
    if (wanted == (osdOverlayView != null)) return
    if (!wanted) {
      removeOsdOverlay()
      return
    }
    val overlay = SurfaceView(context)
    overlay.apply {
      // Above the video SurfaceView but still behind the window, so the player controls stay on top.
      setZOrderMediaOverlay(true)
      holder.setFormat(PixelFormat.TRANSLUCENT)
      isClickable = false
      isFocusable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
      holder.addCallback(object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
          if (overlay !== osdOverlayView) return
          osdSurface = holder.surface
          osdSurfaceWidth = overlay.width
          osdSurfaceHeight = overlay.height
          onOsdSurfaceAvailable(holder.surface, overlay.width, overlay.height)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
          if (overlay !== osdOverlayView) return
          osdSurfaceWidth = width
          osdSurfaceHeight = height
          onOsdSurfaceSizeChanged(width, height)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
          if (overlay !== osdOverlayView) return
          releaseOsdSurface()
        }
      })
    }
    osdOverlayView = overlay
    addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  private fun removeOsdOverlay() {
    val overlay = osdOverlayView ?: return
    releaseOsdSurface()
    osdOverlayView = null
    removeView(overlay)
  }

  private fun releaseOsdSurface() {
    if (osdSurface == null) return
    onOsdSurfaceDestroyed()
    osdSurface = null
    osdSurfaceWidth = 0
    osdSurfaceHeight = 0
  }

  /** Only changes for an appearance/HDR preference, never when controls appear or disappear. */
  internal fun setTextureRendering(enabled: Boolean) {
    if (textureRendering == enabled) return
    val wasConfigured = textureRendering != null
    textureRendering = enabled
    textureFrameReady.value = false
    val epoch = ++rendererEpoch
    // A TextureView may clear its Surface before the preference collector switches renderers.
    // Explicitly detach the old native VO on every real mode change, even when renderSurface
    // is already null, so the replacement SurfaceView can bind without inheriting a stale wid.
    if (wasConfigured) onRenderSurfaceDestroyed()
    removeOsdOverlay()
    if (ownsSurface) renderSurface?.release()
    renderSurface = null
    ownsSurface = false
    videoBackdrop = null
    (getChildAt(0) as? ComposeView)?.disposeComposition()
    removeAllViews()
    videoView = null
    descendantFocusability = FOCUS_BLOCK_DESCENDANTS

    fun available(surface: Surface, width: Int, height: Int, owned: Boolean) {
      if (epoch != rendererEpoch) {
        if (owned) surface.release()
        return
      }
      renderSurface = surface
      ownsSurface = owned
      val (reportedWidth, reportedHeight) = reportedSize(width, height)
      onRenderSurfaceAvailable(surface, reportedWidth, reportedHeight)
    }

    fun sizeChanged(width: Int, height: Int) {
      if (epoch != rendererEpoch) return
      val (reportedWidth, reportedHeight) = reportedSize(width, height)
      onRenderSurfaceSizeChanged(reportedWidth, reportedHeight)
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
                      sizeChanged(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                      destroyed()
                      return true // TextureView owns and releases the SurfaceTexture after MPV detaches.
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                      if (epoch == rendererEpoch && !textureFrameReady.value) {
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
            sizeChanged(width, height)
          }

          override fun surfaceDestroyed(holder: SurfaceHolder) = destroyed()
        })
      }
    }
    addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    videoView = child
    syncOsdOverlay()
  }
}
