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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

  // Use the shared audio analyzer instead of creating a duplicate Visualizer
  val sharedAnalyzer = remember { AudioSpectrumAnalyzer() }
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

  // Start the shared analyzer and poll its features to feed the cuboid engine
  DisposableEffect(Unit) {
    val staleThresholdNanos = 2_000_000_000L
    val job =
      scope.launch(Dispatchers.Default) {
        // Start the shared analyzer
        sharedAnalyzer.start(audioSessionId)
        visualizerActive.set(true)

        var fftPeak = 12f
        while (isActive) {
          if (playbackActive.get() && sharedAnalyzer.features.active) {
            // Check if data is still flowing
            if (!sharedAnalyzer.features.hasRecentCapture(staleThresholdNanos)) {
              // Stale — restart
              sharedAnalyzer.start(audioSessionId)
              fftPeak = 12f
              delay(500L)
              continue
            }

            // Read raw FFT magnitude spectrum from the shared analyzer
            val spectrum = sharedAnalyzer.features.spectrum
            val len = min(spectrum.size, frequencyData.size)
            for (k in 0 until len) {
              val magnitude = spectrum[k] * 128f
              fftPeak = max(12f, max(magnitude, fftPeak * 0.992f))
              val normalized =
                (ln(1f + magnitude) / ln(1f + fftPeak) * 255f).toInt().coerceIn(0, 255)
              frequencyData[k] = normalized.toByte()
            }
            engine.updateFrequencyData(frequencyData.copyOf(len))
          } else if (playbackActive.get()) {
            // Fallback: generate synthetic data when analyzer isn't delivering yet
            val simData = ByteArray(64)
            val phase = System.nanoTime() / 100_000_000f
            for (k in simData.indices) {
              val valk = (130 + 90 * sin(phase * 0.2f + k * 0.12f) + 35 * sin(phase * 0.5f)).toInt().coerceIn(20, 255)
              simData[k] = valk.toByte()
            }
            engine.updateFrequencyData(simData)
          }
          delay(16L)
        }
      }

    onDispose {
      job.cancel()
      visualizerActive.set(false)
      sharedAnalyzer.stop()
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
