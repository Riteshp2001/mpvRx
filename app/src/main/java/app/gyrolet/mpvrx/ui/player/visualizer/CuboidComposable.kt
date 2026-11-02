/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Native Android interpretation of Niklas Knaack's Cuboid Warptunnel Audio Visualizer:
 * https://codepen.io/NiklasKnaack/pen/WyWqja
 */

package app.gyrolet.mpvrx.ui.player.visualizer

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val CUBOID_FOV = 250f
private const val CUBOID_SPEED = 0.75f
private const val CUBOID_Z_STEP = 5f
private const val CUBOID_SEGMENTS = 64
private const val CUBOID_RADIUS = 75f
private const val CUBOID_AUDIO_BIN_MIN = 8
private const val CUBOID_AUDIO_BIN_MAX = 1032
private const val CUBOID_SOURCE_FFT_BINS = 4096
private const val CUBOID_AUDIO_SMOOTHING = 0.65f
private const val CUBOID_CAPTURE_MAX_AGE_NS = 750_000_000L
private const val TWO_PI = (Math.PI * 2.0).toFloat()
private const val COLOR_BUCKETS = 16

private data class CuboidRgb(
  val r: Float,
  val g: Float,
  val b: Float,
)

private data class CuboidRing(
  var z: Float,
  val index: Int,
  val audioBins: IntArray =
    IntArray(CUBOID_SEGMENTS) {
      Random.nextInt(CUBOID_AUDIO_BIN_MIN, CUBOID_AUDIO_BIN_MAX)
    },
  val smoothedFrequencies: FloatArray = FloatArray(CUBOID_SEGMENTS),
  val bucketPaths: Array<Path> = Array(COLOR_BUCKETS) { Path() },
  val bucketUsed: BooleanArray = BooleanArray(COLOR_BUCKETS),
)

private data class CuboidTones(
  val primary: CuboidRgb,
  val secondary: CuboidRgb,
  val tertiary: CuboidRgb,
)

/**
 * Keeps only the small amount of persistent state the original canvas experiment needs.
 * Geometry is projected directly by Compose each frame, avoiding the previous low-resolution
 * Bitmap -> Compose up-scale path that made the tunnel soft and also decoupled it from the real
 * viewport size.
 */
private class CuboidTunnelState {
  val rings =
    MutableList(((CUBOID_FOV * 2f) / CUBOID_Z_STEP).toInt()) { index ->
      CuboidRing(
        z = -CUBOID_FOV + index.toFloat() * CUBOID_Z_STEP,
        index = index,
      )
    }

  var time = 0f
    private set

  val interaction = VisualizerInteraction()
  val pointerX: Float get() = (interaction.x + 1f) * 0.5f
  val pointerY: Float get() = (1f - interaction.y) * 0.5f
  val reverse: Boolean get() = interaction.pressed
  var audioRetention = CUBOID_AUDIO_SMOOTHING
    private set

  var invertValue = 0f
    private set

  private var fastR = Random.nextFloat() * TWO_PI
  private var fastG = Random.nextFloat() * TWO_PI
  private var fastB = Random.nextFloat() * TWO_PI
  private var slowR = Random.nextFloat() * TWO_PI
  private var slowG = Random.nextFloat() * TWO_PI
  private var slowB = Random.nextFloat() * TWO_PI

  var fastColor = CuboidRgb(1f, 1f, 1f)
    private set

  var slowColor = CuboidRgb(0.45f, 0.45f, 0.45f)
    private set

  fun advance(frameScale: Float) {
    val safeScale = frameScale.coerceIn(0f, 3f)
    interaction.advance(safeScale / 60f)
    audioRetention = CUBOID_AUDIO_SMOOTHING.pow(safeScale)

    var wrapped = false
    val travel = CUBOID_SPEED * safeScale
    for (ring in rings) {
      if (reverse) {
        ring.z += travel
        if (ring.z > CUBOID_FOV) {
          ring.z -= CUBOID_FOV * 2f
          wrapped = true
        }
      } else {
        ring.z -= travel
        if (ring.z < -CUBOID_FOV) {
          ring.z += CUBOID_FOV * 2f
          wrapped = true
        }
      }
    }
    if (wrapped) rings.sortByDescending { it.z }

    time += (if (reverse) -0.005f else 0.005f) * safeScale

    invertValue =
      if (reverse) {
        min(255f, invertValue + 5f * safeScale)
      } else {
        max(0f, invertValue - 5f * safeScale)
      }

    // Same two independent RGB oscillators used by the reference visualizer.
    fastR += 0.040f * safeScale
    fastG += 0.028f * safeScale
    fastB += 0.052f * safeScale
    slowR += 0.010f * safeScale
    slowG += 0.007f * safeScale
    slowB += 0.013f * safeScale

    fastColor =
      CuboidRgb(
        max(0.45f, sin(fastR) + 1f),
        max(0.45f, sin(fastG) + 1f),
        max(0.45f, sin(fastB) + 1f),
      )
    slowColor =
      CuboidRgb(
        max(0.25f, sin(slowR) + 1f),
        max(0.25f, sin(slowG) + 1f),
        max(0.25f, sin(slowB) + 1f),
      )
  }
}

@Composable
internal fun CuboidOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  features: AudioFeatures,
) {
  val state = remember { CuboidTunnelState() }
  val tones = remember(palette) { palette.toCuboidTones() }
  var frameTick by remember { mutableLongStateOf(0L) }

  LaunchedEffect(isSheetOpen) {
    if (isSheetOpen) return@LaunchedEffect
    var lastFrameNanos = 0L
    while (isActive) {
      withFrameNanos { now ->
        val frameScale =
          if (lastFrameNanos == 0L || now - lastFrameNanos > 250_000_000L) {
            1f
          } else {
            (((now - lastFrameNanos) / 1_000_000_000f) * 60f).coerceIn(0f, 3f)
          }
        lastFrameNanos = now
        state.advance(frameScale)
        frameTick = now
      }
    }
  }

  Canvas(
    modifier =
      modifier
        .fillMaxSize()
        .pointerInput(isSheetOpen) {
          if (isSheetOpen) {
            state.interaction.release()
            return@pointerInput
          }
          try {
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                val primary = event.changes.firstOrNull { it.pressed } ?: event.changes.firstOrNull()
                if (primary != null) {
                  val pressed = event.changes.any { it.pressed }
                  val hovering = primary.type != PointerType.Touch && event.type != PointerEventType.Exit
                  state.interaction.update(
                    normalizedX = primary.position.x / size.width.coerceAtLeast(1).toFloat(),
                    normalizedY = primary.position.y / size.height.coerceAtLeast(1).toFloat(),
                    active = pressed || hovering,
                    pressed = pressed,
                  )
                }
                event.changes.forEach { if (it.pressed || it.previousPressed) it.consume() }
              }
            }
          } finally {
            state.interaction.release()
          }
        },
  ) {
    // Read the frame state in the draw phase so animation invalidates drawing without rebuilding
    // the whole audio-player composition every 16 ms.
    frameTick
    drawCuboidTunnel(
      state = state,
      features = features,
      isPlaying = isPlaying,
      tones = tones,
    )
  }
}

private fun DrawScope.drawCuboidTunnel(
  state: CuboidTunnelState,
  features: AudioFeatures,
  isPlaying: Boolean,
  tones: CuboidTones,
) {
  if (size.width <= 1f || size.height <= 1f || state.rings.size < 2) return

  // Use the 512-bin FFT spectrum, not the 64-band logarithmic summary. The old implementation
  // generated CodePen-style random bin indices in the 8..1024 range but fed only 64 values, so
  // most cuboids never received audio at all.
  val spectrum = features.spectrum
  val audioActive =
    isPlaying &&
      spectrum.size > CUBOID_AUDIO_BIN_MIN &&
      features.hasRecentCapture(CUBOID_CAPTURE_MAX_AGE_NS)
  // Volume gating comes from the shared feature state, matching every scaled*() consumer.
  val gain = features.volumeScale.coerceIn(0f, 1.5f)

  val width = size.width
  val height = size.height
  val pointerPxX = state.pointerX * width
  val pointerPxY = state.pointerY * height
  val ringCount = state.rings.size
  val stroke = Stroke(width = 1.15f, cap = StrokeCap.Square)

  for (ringIndex in state.rings.indices) {
    val ring = state.rings[ringIndex]
    val back = state.rings.getOrNull(ringIndex - 1)
    if (back == null || ringIndex >= ringCount - 1) continue

    val center = tunnelCenter(ring.z, width, height, pointerPxX, pointerPxY)
    val backCenter = tunnelCenter(back.z, width, height, pointerPxX, pointerPxY)
    val currentScale = projectionScale(ring.z)
    val backScale = projectionScale(back.z)

    val depth = (ring.z + CUBOID_FOV) / CUBOID_FOV
    val paletteDepth = (depth * 0.5f).coerceIn(0f, 1f)
    val paletteTone =
      if (paletteDepth < 0.5f) {
        mixCuboidRgb(tones.primary, tones.secondary, paletteDepth * 2f)
      } else {
        mixCuboidRgb(tones.secondary, tones.tertiary, (paletteDepth - 0.5f) * 2f)
      }
    val ringTone = mixCuboidRgb(paletteTone, tones.primary, state.invertValue / 255f)

    for (bucket in 0 until COLOR_BUCKETS) {
      ring.bucketPaths[bucket].reset()
      ring.bucketUsed[bucket] = false
    }

    for (segmentIndex in 0 until CUBOID_SEGMENTS) {
      if (segmentIndex % 2 != ring.index % 2) continue

      val frequencyTarget =
        if (audioActive) {
          val bin = resolveAudioBin(ring.audioBins[segmentIndex], spectrum.size)
          spectrum[bin].coerceIn(0f, 1f) * 255f * gain
        } else {
          0f
        }
      val frequency =
        ring.smoothedFrequencies[segmentIndex] * state.audioRetention +
          frequencyTarget * (1f - state.audioRetention)
      ring.smoothedFrequencies[segmentIndex] = frequency
      val frequencyAdd = frequency / 20f
      // Reference behavior: audio pulls the inner face inward by frequency / 20.
      val reactiveRadius = (CUBOID_RADIUS - frequencyAdd).coerceAtLeast(CUBOID_RADIUS * 0.55f)
      val bucket =
        if (audioActive) {
          ((frequency / 256f) * COLOR_BUCKETS).toInt().coerceIn(0, COLOR_BUCKETS - 1)
        } else {
          0
        }
      val path = ring.bucketPaths[bucket]

      val currentAngle = segmentIndex.toFloat() * TWO_PI / CUBOID_SEGMENTS + state.time
      val previousIndex = if (segmentIndex == 0) CUBOID_SEGMENTS - 1 else segmentIndex - 1
      val previousAngle = previousIndex.toFloat() * TWO_PI / CUBOID_SEGMENTS + state.time

      val innerCurrent1 = project(currentAngle, reactiveRadius, currentScale, center)
      val innerCurrent0 = project(previousAngle, reactiveRadius, currentScale, center)
      val innerBack1 = project(currentAngle, reactiveRadius, backScale, backCenter)
      val innerBack0 = project(previousAngle, reactiveRadius, backScale, backCenter)

      val outerCurrent1 = project(currentAngle, CUBOID_RADIUS, currentScale, center)
      val outerCurrent0 = project(previousAngle, CUBOID_RADIUS, currentScale, center)
      val outerBack1 = project(currentAngle, CUBOID_RADIUS, backScale, backCenter)
      val outerBack0 = project(previousAngle, CUBOID_RADIUS, backScale, backCenter)

      // The reactive inner face and its four radial connectors exist only when the mapped FFT bin
      // has energy, matching the reference's frequencyAdd > 0 branch.
      if (frequencyAdd > 0.01f) {
        path.edge(innerCurrent1, innerBack1)
        path.edge(innerBack1, innerBack0)
        path.edge(innerBack0, innerCurrent0)
        path.edge(innerCurrent0, innerCurrent1)
        path.edge(outerCurrent1, innerCurrent1)
        path.edge(outerCurrent0, innerCurrent0)
        path.edge(outerBack0, innerBack0)
        path.edge(outerBack1, innerBack1)
        ring.bucketUsed[bucket] = true
      }

      // Keep the outer face until the ring reaches the near half of the tunnel.
      if (ring.z < CUBOID_FOV / 2f) {
        path.edge(outerCurrent1, outerCurrent0)
        path.edge(outerCurrent0, outerBack0)
        path.edge(outerBack0, outerBack1)
        path.edge(outerBack1, outerCurrent1)
        ring.bucketUsed[bucket] = true
      }
    }

    for (bucket in 0 until COLOR_BUCKETS) {
      if (!ring.bucketUsed[bucket]) continue
      val representativeFrequency =
        if (audioActive) {
          ((bucket + 0.5f) / COLOR_BUCKETS.toFloat()) * 255f
        } else {
          0f
        }
      val lineValue =
        if (audioActive) {
          min(255f, ringIndex.toFloat() / ringCount.toFloat() * (55f + representativeFrequency))
        } else {
          ringIndex.toFloat() / ringCount.toFloat() * 200f
        }
      val color = cuboidLineColor(ringTone, lineValue)
      drawPath(ring.bucketPaths[bucket], color = color, style = stroke)
    }
  }
}

private fun VisualizerPalette.toCuboidTones(): CuboidTones =
  CuboidTones(
    primary = primary.toCuboidRgb(),
    secondary = secondary.toCuboidRgb(),
    tertiary = tertiary.toCuboidRgb(),
  )

private fun Int.toCuboidRgb(): CuboidRgb =
  CuboidRgb(
    r = AndroidColor.red(this) / 255f,
    g = AndroidColor.green(this) / 255f,
    b = AndroidColor.blue(this) / 255f,
  )

private fun mixCuboidRgb(
  from: CuboidRgb,
  to: CuboidRgb,
  amount: Float,
): CuboidRgb {
  val fraction = amount.coerceIn(0f, 1f)
  return CuboidRgb(
    r = from.r + (to.r - from.r) * fraction,
    g = from.g + (to.g - from.g) * fraction,
    b = from.b + (to.b - from.b) * fraction,
  )
}

private fun tunnelCenter(
  z: Float,
  width: Float,
  height: Float,
  pointerX: Float,
  pointerY: Float,
): Offset {
  val depthFactor = (z - CUBOID_FOV) / 500f
  return Offset(
    x = ((width / 2f) - pointerX) * depthFactor + width / 2f,
    y = ((height / 2f) - pointerY) * depthFactor + height / 2f,
  )
}

private fun projectionScale(z: Float): Float =
  CUBOID_FOV / (CUBOID_FOV + z).coerceAtLeast(1f)

private fun project(
  angle: Float,
  radius: Float,
  scale: Float,
  center: Offset,
): Offset =
  Offset(
    x = cos(angle) * radius * scale + center.x,
    y = sin(angle) * radius * scale + center.y,
  )

private fun resolveAudioBin(
  requested: Int,
  spectrumSize: Int,
): Int {
  if (spectrumSize <= 1) return 0
  val normalized = requested.coerceIn(0, CUBOID_SOURCE_FFT_BINS - 1) / (CUBOID_SOURCE_FFT_BINS - 1f)
  return (normalized * (spectrumSize - 1)).roundToInt().coerceIn(1, spectrumSize - 1)
}

private fun Path.edge(
  from: Offset,
  to: Offset,
) {
  moveTo(from.x, from.y)
  lineTo(to.x, to.y)
}

private fun cuboidLineColor(
  tone: CuboidRgb,
  lineValue: Float,
): Color =
  Color(
    red = (tone.r * 255f).roundToInt().coerceIn(0, 255),
    green = (tone.g * 255f).roundToInt().coerceIn(0, 255),
    blue = (tone.b * 255f).roundToInt().coerceIn(0, 255),
    alpha = lineValue.roundToInt().coerceIn(0, 255),
  )
