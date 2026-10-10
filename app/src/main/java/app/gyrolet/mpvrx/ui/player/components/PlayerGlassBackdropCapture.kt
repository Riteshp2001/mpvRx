/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player.components

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.gyrolet.mpvrx.ui.player.HdrScreenMode
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Only capture while player controls can show glass. Unlike VideoAmbientFrame,
// this feed is the *unaltered* video surface: no palette, box blur or interpolation.
private const val GLASS_MAX_FRAME_DIMENSION_PX = 512
private const val GLASS_PLAYING_INTERVAL_MS = 125L
private const val GLASS_PAUSED_INTERVAL_MS = 500L
private const val GLASS_RETRY_INTERVAL_MS = 250L

@Composable
fun PlayerGlassBackdropCapture(
  surfaceView: SurfaceView?,
  backdrop: LayerBackdrop,
  active: Boolean,
  playbackGeneration: Long,
  hdrScreenMode: HdrScreenMode,
  orientation: Int,
  isSurfaceReadyProvider: () -> Boolean,
  isPlayingProvider: () -> Boolean,
) {
  val frame = if (surfaceView != null) {
    rememberPlayerGlassFrame(
      surfaceView = surfaceView,
      active = active,
      playbackGeneration = playbackGeneration,
      hdrScreenMode = hdrScreenMode,
      orientation = orientation,
      isSurfaceReadyProvider = isSurfaceReadyProvider,
      isPlayingProvider = isPlayingProvider,
    )
  } else {
    null
  }

  // Kyant's documented invisible layerBackdrop pattern records the image for
  // refraction without drawing a second, translucent video over the real player.
  Box(Modifier.fillMaxSize().alpha(0f).layerBackdrop(backdrop)) {
    if (frame != null) {
      Image(
        bitmap = frame,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun rememberPlayerGlassFrame(
  surfaceView: SurfaceView,
  active: Boolean,
  playbackGeneration: Long,
  hdrScreenMode: HdrScreenMode,
  orientation: Int,
  isSurfaceReadyProvider: () -> Boolean,
  isPlayingProvider: () -> Boolean,
): ImageBitmap? {
  var frame by remember(surfaceView, playbackGeneration, hdrScreenMode, orientation) {
    mutableStateOf<ImageBitmap?>(null)
  }
  val surfaceReady by rememberUpdatedState(isSurfaceReadyProvider)
  val isPlaying by rememberUpdatedState(isPlayingProvider)
  val lifecycleOwner = LocalLifecycleOwner.current

  LaunchedEffect(surfaceView, active, playbackGeneration, hdrScreenMode, orientation, lifecycleOwner) {
    frame = null
    if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@LaunchedEffect

    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      val copyHandler = Handler(Looper.getMainLooper())
      var buffers: Array<Bitmap>? = null
      var nextBufferIndex = 0
      var consecutiveFailures = 0
      try {
        while (currentCoroutineContext().isActive) {
          val width = surfaceView.width
          val height = surfaceView.height
          if (!surfaceReady() || width <= 0 || height <= 0 || !surfaceView.holder.surface.isValid) {
            frame = null
            delay(GLASS_RETRY_INTERVAL_MS)
            continue
          }

          // Preserve the real SurfaceView aspect ratio, including video letterboxing.
          val scale = (GLASS_MAX_FRAME_DIMENSION_PX.toFloat() / maxOf(width, height)).coerceAtMost(1f)
          val captureWidth = (width * scale).roundToInt().coerceAtLeast(1)
          val captureHeight = (height * scale).roundToInt().coerceAtLeast(1)
          if (buffers == null || buffers[0].width != captureWidth || buffers[0].height != captureHeight) {
            // Triple buffering avoids rewriting a bitmap that Compose is still drawing.
            buffers = Array(3) { Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888) }
            nextBufferIndex = 0
            frame = null
          }

          val bitmap = checkNotNull(buffers)[nextBufferIndex]
          if (copySurfaceFrame(surfaceView, bitmap, copyHandler)) {
            frame = bitmap.asImageBitmap()
            nextBufferIndex = (nextBufferIndex + 1) % checkNotNull(buffers).size
            consecutiveFailures = 0
          } else {
            consecutiveFailures++
            if (consecutiveFailures >= 3) frame = null
          }
          delay(if (isPlaying()) GLASS_PLAYING_INTERVAL_MS else GLASS_PAUSED_INTERVAL_MS)
        }
      } finally {
        frame = null
      }
    }
  }
  return frame
}

private suspend fun copySurfaceFrame(
  surfaceView: SurfaceView,
  bitmap: Bitmap,
  handler: Handler,
): Boolean = suspendCancellableCoroutine { continuation ->
  try {
    PixelCopy.request(
      surfaceView,
      bitmap,
      { result ->
        if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
      },
      handler,
    )
  } catch (_: Exception) {
    // Includes protected/secure surfaces and transient PixelCopy failures.
    // Never let an optional glass effect bring down video playback.
    if (continuation.isActive) continuation.resume(false)
  }
}
