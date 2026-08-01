/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

@Composable
internal fun BlobOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
) = VisualizerOverlay(
  modifier = modifier,
  isPlaying = isPlaying,
  palette = palette,
  isSheetOpen = isSheetOpen,
  factory = { ctx, features, p -> BlobVisualizerView(ctx, features, p) },
)

@Composable
internal fun GalaxyOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
) = VisualizerOverlay(
  modifier = modifier,
  isPlaying = isPlaying,
  palette = palette,
  isSheetOpen = isSheetOpen,
  factory = { ctx, features, p -> GalaxyVisualizerView(ctx, features, p) },
)

@Composable
internal fun ParticleOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
) = VisualizerOverlay(
  modifier = modifier,
  isPlaying = isPlaying,
  palette = palette,
  isSheetOpen = isSheetOpen,
  factory = { ctx, features, p -> ParticleVisualizerView(ctx, features, p) },
)


internal interface PaletteConsumer {
  fun updatePalette(value: VisualizerPalette)
}

@Composable
private fun <T> VisualizerOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  factory: (android.content.Context, AudioFeatures, VisualizerPalette) -> T,
) where T : GLSurfaceView, T : PaletteConsumer {
  val context = LocalContext.current
  val features = remember { AudioFeatures() }
  val scope = rememberCoroutineScope()
  val realAnalyzerActive = remember { AtomicBoolean(false) }

  // Keep the analyzer resilient across player/audio-session changes. Some devices briefly
  // reject Visualizer creation while mpv swaps files; retry without recreating the GL view so
  // the blob keeps its animation state instead of stuttering or snapping to idle.
  // Also detect when the platform silently stops delivering FFT callbacks (e.g. AudioTrack
  // session swap) and re-create the Visualizer automatically.
  // A fresh session id is fetched on each retry so that audio-routing changes
  // (headphone connect/disconnect) are picked up without restarting the composable.
  DisposableEffect(Unit) {
    val analyzer = AudioSpectrumAnalyzer(features)
    val staleThresholdNanos = 2_000_000_000L // 2 seconds without FFT data → stale
    val job =
      scope.launch(Dispatchers.Default) {
        while (isActive) {
          if (!realAnalyzerActive.get()) {
            val sessionId = AudioSessionProvider.get(context)
            realAnalyzerActive.set(analyzer.start(sessionId).isSuccess)
          } else if (!features.hasRecentCapture(staleThresholdNanos)) {
            // Visualizer attached but stopped delivering data — tear down and retry
            realAnalyzerActive.set(false)
            analyzer.stop(resetFeatures = false)
          }
          delay(if (realAnalyzerActive.get()) 1_000L else 350L)
        }
      }

    onDispose {
      job.cancel()
      realAnalyzerActive.set(false)
      analyzer.stop(resetFeatures = false)
    }
  }

  // Fluid audio reactive motion when system FFT capture is negotiating or on hardware routes where session capture is restricted.
  DisposableEffect(isPlaying) {
    val job =
      scope.launch(Dispatchers.Default) {
        var phase = 0f
        var lastBeat = 0L
        while (isActive) {
          if (realAnalyzerActive.get() && features.hasRecentCapture(1_500_000_000L)) {
            delay(33)
            continue
          } else if (isPlaying) {
            phase += 0.08f
            val bassVal = (0.45f + sin(phase * 0.7f) * 0.25f + sin(phase * 1.8f) * 0.15f).coerceIn(0.1f, 0.95f)
            val midVal = (0.40f + sin(phase * 1.1f + 1.2f) * 0.20f).coerceIn(0.1f, 0.90f)
            val trebleVal = (0.35f + sin(phase * 1.6f + 2.4f) * 0.20f).coerceIn(0.1f, 0.85f)
            val energyVal = (bassVal * 0.5f + midVal * 0.35f + trebleVal * 0.15f).coerceIn(0.2f, 0.95f)

            val nowNanos = System.nanoTime()
            val isBeat = (bassVal > 0.65f) && (nowNanos - lastBeat > 220_000_000L)
            if (isBeat) lastBeat = nowNanos

            features.energy = energyVal
            features.bass = bassVal
            features.mid = midVal
            features.treble = trebleVal
            features.beat = if (isBeat) 1f else 0f
            features.centroid = 0.45f
            features.active = true
          } else {
            features.decay(0.90f, beatFactor = 0.75f)
          }
          delay(33)
        }
      }

    onDispose {
      job.cancel()
    }
  }

  AndroidView(
    factory = { ctx ->
      factory(ctx, features, palette).apply {
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
      }
    },
    modifier = modifier,
    update = { view ->
      view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
      view.updatePalette(palette)
      if (isSheetOpen) {
        view.setZOrderOnTop(false)
        view.setZOrderMediaOverlay(true)
      } else {
        view.setZOrderMediaOverlay(false)
        view.setZOrderOnTop(true)
      }
    },
  )
}
