/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
internal fun CuboidOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
) {
  val engine = remember { CuboidWarptunnelEngine() }
  var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
  val renderLoopActive = remember { AtomicBoolean(true) }
  val playbackActive = remember { AtomicBoolean(isPlaying) }

  LaunchedEffect(volumeScale) {
    engine.volumeScale = volumeScale
  }

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

  DisposableEffect(Unit) {
    onDispose {
      renderLoopActive.set(false)
      engine.clearAudioData()
      engine.release()
    }
  }

  var engineW by remember { mutableStateOf(1) }
  var engineH by remember { mutableStateOf(1) }

  LaunchedEffect(engineW, engineH, palette, isPlaying) {
    if (engineW < 2 || engineH < 2) return@LaunchedEffect
    renderLoopActive.set(true)
    engine.init(engineW, engineH)
    while (isActive && renderLoopActive.get()) {
      if (playbackActive.get()) {
        val spectrum = features.logSpectrum
        engine.updateFrequencyData(
          ByteArray(spectrum.size) { index ->
            (spectrum[index] * features.volumeScale * 255f).toInt().coerceIn(0, 255).toByte()
          },
        )
      } else {
        engine.clearAudioData()
      }
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
