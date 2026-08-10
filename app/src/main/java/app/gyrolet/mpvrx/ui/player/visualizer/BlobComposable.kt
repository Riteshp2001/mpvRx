/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

@Composable
internal fun BlobOverlay(
  modifier: Modifier = Modifier,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
) = VisualizerOverlay(
  modifier = modifier,
  palette = palette,
  isSheetOpen = isSheetOpen,
  volumeScale = volumeScale,
  features = features,
  factory = { ctx, features, p -> BlobVisualizerView(ctx, features, p) },
)

@Composable
internal fun GalaxyOverlay(
  modifier: Modifier = Modifier,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
) = VisualizerOverlay(
  modifier = modifier,
  palette = palette,
  isSheetOpen = isSheetOpen,
  volumeScale = volumeScale,
  features = features,
  factory = { ctx, features, p -> GalaxyVisualizerView(ctx, features, p) },
)

@Composable
internal fun ParticleOverlay(
  modifier: Modifier = Modifier,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
) = VisualizerOverlay(
  modifier = modifier,
  palette = palette,
  isSheetOpen = isSheetOpen,
  volumeScale = volumeScale,
  features = features,
  factory = { ctx, features, p -> ParticleVisualizerView(ctx, features, p) },
)

internal interface PaletteConsumer {
  fun updatePalette(value: VisualizerPalette)
}

@Composable
private fun <T> VisualizerOverlay(
  modifier: Modifier = Modifier,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
  factory: (android.content.Context, AudioFeatures, VisualizerPalette) -> T,
) where T : GLSurfaceView, T : PaletteConsumer {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val realAnalyzerActive = remember(features) { AtomicBoolean(false) }
  var hasRecordPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val recordPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasRecordPermission = granted
    }

  LaunchedEffect(volumeScale) {
    features.volumeScale = volumeScale
  }
  LaunchedEffect(hasRecordPermission) {
    if (!hasRecordPermission) recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
  }

  // The overlay itself is only composed while a visualizer is visible. Keep Android's audio
  // capture pipeline scoped to this lifecycle so album-art-only playback does not hold a Visualizer
  // effect, poll capture freshness, or repeatedly retry the audio session in the background.
  // A modal sheet covers the renderer, so suspend capture there too and let the UI consume zero
  // analyzer work until the visualizer is visible again.
  DisposableEffect(hasRecordPermission, features, isSheetOpen) {
    val analyzer = if (hasRecordPermission && !isSheetOpen) AudioSpectrumAnalyzer(features) else null
    val job =
      scope.launch(Dispatchers.Default) {
        while (isActive && analyzer != null) {
          val captureFresh = features.active && features.hasRecentCapture(1_500_000_000L)
          if (!realAnalyzerActive.get() || !captureFresh) {
            realAnalyzerActive.set(analyzer.start(0).isSuccess)
          }
          delay(if (realAnalyzerActive.get()) 1_500L else 400L)
        }
      }
    onDispose {
      job.cancel()
      realAnalyzerActive.set(false)
      analyzer?.stop(resetFeatures = false)
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
      view.updatePalette(palette)
      if (isSheetOpen) {
        // A sheet fully covers the expensive GLSurfaceView. Keep the last frame but stop the
        // continuous render loop (particle/galaxy renderers otherwise burn GPU underneath it).
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        view.requestRender()
        view.setZOrderOnTop(false)
        view.setZOrderMediaOverlay(true)
      } else {
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.setZOrderMediaOverlay(false)
        view.setZOrderOnTop(true)
      }
    },
  )
}

/** Lightweight feature state shared by every renderer and the audio-reactive seekbar. */
@Composable
internal fun rememberAudioVisualizerFeatures(
  isPlaying: Boolean,
  volumeScale: Float,
): AudioFeatures {
  val features = remember { AudioFeatures() }
  val scope = rememberCoroutineScope()

  LaunchedEffect(volumeScale) {
    features.volumeScale = volumeScale.coerceIn(0f, 1f)
  }

  DisposableEffect(isPlaying) {
    val job =
      scope.launch(Dispatchers.Default) {
        while (isActive) {
          val realCapture = features.active && features.hasRecentCapture(1_500_000_000L)
          if (!realCapture && isPlaying) {
            val time = System.nanoTime() / 1_000_000_000f
            features.energy = 0.025f + sin(time * 0.72f) * 0.006f
            features.bass = 0.018f + sin(time * 0.55f) * 0.004f
            features.mid = 0.014f + sin(time * 0.83f) * 0.003f
            features.treble = 0.010f + sin(time * 1.05f) * 0.002f
            features.beat = 0f
            features.centroid = 0.35f
            features.active = false
          } else if (!isPlaying) {
            features.decay(0.90f, beatFactor = 0.75f)
          }
          delay(33)
        }
      }
    onDispose { job.cancel() }
  }
  return features
}
