/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 *
 * Cuboid Warptunnel Audio Visualizer
 * Original by Niklas Knaack — https://codepen.io/NiklasKnaack/pen/WyWqja
 * Ported to native Android Compose Canvas for mpvRx
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun CuboidOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
) {
  val context = LocalContext.current
  val engine = remember { CuboidWarptunnelEngine() }
  var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
  val renderLoopActive = remember { AtomicBoolean(true) }
  val playbackActive = remember { AtomicBoolean(isPlaying) }
  val frequencyData = remember { ByteArray(2048) }
  val audioSessionId = remember { AudioSessionProvider.get(context) }

  val isDark = androidx.compose.foundation.isSystemInDarkTheme()

  LaunchedEffect(isDark) {
    engine.isLightTheme = !isDark
  }

  LaunchedEffect(palette) {
    engine.palette = palette
  }

  LaunchedEffect(isPlaying) {
    playbackActive.set(isPlaying)
    if (!isPlaying) engine.clearAudioData()
  }

  val scope = rememberCoroutineScope()
  val visualizerActive = remember { AtomicBoolean(false) }

  // mpv creates its AudioTrack lazily, so the session may briefly not exist yet; retry until
  // the FFT capture attaches without recreating the engine.
  DisposableEffect(Unit) {
    val visualizer = AtomicReference<Visualizer?>(null)
    val job =
      scope.launch(Dispatchers.Default) {
        var fftPeak = 12f
        while (isActive && !visualizerActive.get()) {
          try {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = min(range[1], 2048)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(
              object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                  v: Visualizer?,
                  w: ByteArray?,
                  sr: Int,
                ) {}

                override fun onFftDataCapture(
                  v: Visualizer?,
                  fft: ByteArray?,
                  sr: Int,
                ) {
                  if (playbackActive.get() && fft != null && fft.size >= 8) {
                    synchronized(frequencyData) {
                      val len = min(fft.size / 2, frequencyData.size)
                      for (k in 0 until len) {
                        val real = fft[k * 2].toInt().toFloat()
                        val imag = fft[k * 2 + 1].toInt().toFloat()
                        val magnitude = hypot(real.toDouble(), imag.toDouble()).toFloat()
                        fftPeak = max(12f, max(magnitude, fftPeak * 0.992f))
                        val normalized =
                          (ln(1f + magnitude) / ln(1f + fftPeak) * 255f).toInt().coerceIn(0, 255)
                        frequencyData[k] = normalized.toByte()
                      }
                    }
                    engine.updateFrequencyData(frequencyData.copyOf(min(fft.size / 2, frequencyData.size)))
                  }
                }
              },
              Visualizer.getMaxCaptureRate(),
              false,
              true,
            )
            v.enabled = true
            visualizer.set(v)
            visualizerActive.set(true)
          } catch (_: Throwable) {
            visualizer.getAndSet(null)?.let { runCatching { it.release() } }
            delay(500L)
          }
        }
      }

    onDispose {
      job.cancel()
      visualizerActive.set(false)
      visualizer.getAndSet(null)?.let { runCatching { it.release() } }
      renderLoopActive.set(false)
      engine.clearAudioData()
      engine.release()
    }
  }

  var engineW by remember { mutableStateOf(1) }
  var engineH by remember { mutableStateOf(1) }

  LaunchedEffect(engineW, engineH, palette) {
    if (engineW < 2 || engineH < 2) return@LaunchedEffect
    renderLoopActive.set(true)
    engine.init(engineW, engineH)
    while (isActive && renderLoopActive.get()) {
      val bmp = withContext(Dispatchers.Default) { engine.render() }
      if (bmp != null) {
        bitmap = bmp
      }
      delay(16)
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    Canvas(
      modifier =
        Modifier
          .fillMaxSize()
          .pointerInput(Unit) {
            var pointerId: androidx.compose.ui.input.pointer.PointerId? = null
            var pointerCount = 0

            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                val changes = event.changes

                when (event.type) {
                  PointerEventType.Press -> {
                    val first = changes.firstOrNull() ?: continue
                    if (pointerCount == 0 && changes.isNotEmpty()) {
                      pointerCount = 1
                      pointerId = first.id
                      val sx = if (size.width > 0) engineW.toFloat() / size.width else 1f
                      val sy = if (size.height > 0) engineH.toFloat() / size.height else 1f
                      engine.mousePos = CuboidWarptunnelEngine.Offset(first.position.x * sx, first.position.y * sy)
                      engine.touchActive = true
                      engine.mouseDown = true
                    }
                    changes.forEach { it.consume() }
                  }

                  PointerEventType.Move -> {
                    val primary =
                      changes.firstOrNull { pointerId == null || it.id == pointerId } ?: continue
                    val sx = if (size.width > 0) engineW.toFloat() / size.width else 1f
                    val sy = if (size.height > 0) engineH.toFloat() / size.height else 1f
                    engine.mousePos = CuboidWarptunnelEngine.Offset(primary.position.x * sx, primary.position.y * sy)
                    engine.touchActive = true
                    changes.forEach { it.consume() }
                  }

                  PointerEventType.Release -> {
                    if (changes.isNotEmpty()) {
                      pointerCount = 0
                      pointerId = null
                      engine.mouseDown = false
                      engine.touchActive = false
                    }
                    changes.forEach { it.consume() }
                  }
                }
              }
            }
          },
    ) {
      val maxW = 540
      val scaleFactor = if (size.width > maxW) maxW.toFloat() / size.width else 1.0f
      engineW = (size.width * scaleFactor).toInt().coerceAtLeast(120)
      engineH = (size.height * scaleFactor).toInt().coerceAtLeast(120)
      val bmp = bitmap
      if (bmp != null && !bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
        try {
          drawImage(
            image = bmp.asImageBitmap(),
            dstSize = androidx.compose.ui.unit.IntSize(
              size.width.roundToInt(),
              size.height.roundToInt(),
            ),
          )
        } catch (_: Throwable) {}
      }
    }
  }
}
