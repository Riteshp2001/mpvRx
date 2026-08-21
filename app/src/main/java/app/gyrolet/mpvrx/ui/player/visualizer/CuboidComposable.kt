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
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val CUBOID_FOV = 250f
private const val CUBOID_SPEED = 0.75f
private const val CUBOID_Z_STEP = 5f
private const val CUBOID_SEGMENTS = 64
private const val CUBOID_RADIUS = 75f
private const val CUBOID_AUDIO_BIN_MIN = 8
private const val CUBOID_AUDIO_BIN_MAX = 512
private const val CUBOID_CAPTURE_MAX_AGE_NS = 750_000_000L
private const val TWO_PI = (Math.PI * 2.0).toFloat()
private const val COLOR_BUCKETS = 8

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

  var pointerX = Random.nextFloat()
    private set

  var pointerY = Random.nextFloat()
    private set

  var pointerActive = false
    private set

  var reverse = false
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

  fun updatePointer(
    normalizedX: Float,
    normalizedY: Float,
    pressed: Boolean,
  ) {
    pointerX = normalizedX.coerceIn(0f, 1f)
    pointerY = normalizedY.coerceIn(0f, 1f)
    pointerActive = pressed
    reverse = pressed
  }

  fun advance(frameScale: Float) {
    val safeScale = frameScale.coerceIn(0.1f, 3f)

    if (!pointerActive) {
      // The CodePen very slowly settles the tunnel centre after the pointer leaves.
      val settle = (0.00025f * safeScale).coerceAtMost(0.01f)
      pointerX += (0.5f - pointerX) * settle
      pointerY += (0.5f - pointerY) * settle
    }

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

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun CuboidOverlay(
  modifier: Modifier = Modifier,
  isPlaying: Boolean = false,
  palette: VisualizerPalette,
  isSheetOpen: Boolean = false,
  volumeScale: Float = 1f,
  features: AudioFeatures,
) {
  val state = remember { CuboidTunnelState() }
  var frameTick by remember { mutableLongStateOf(0L) }

  LaunchedEffect(Unit) {
    var lastFrameNanos = 0L
    while (isActive) {
      withFrameNanos { now ->
        val frameScale =
          if (lastFrameNanos == 0L) {
            1f
          } else {
            (((now - lastFrameNanos) / 1_000_000_000f) * 60f).coerceIn(0.1f, 3f)
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
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              val primary = event.changes.firstOrNull()
              if (primary != null) {
                val width = size.width.coerceAtLeast(1)
                val height = size.height.coerceAtLeast(1)
                state.updatePointer(
                  normalizedX = primary.position.x / width.toFloat(),
                  normalizedY = primary.position.y / height.toFloat(),
                  pressed = event.changes.any { it.pressed },
                )
              }
              event.changes.forEach { it.consume() }
            }
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
    )
  }
}

private fun DrawScope.drawCuboidTunnel(
  state: CuboidTunnelState,
  features: AudioFeatures,
  isPlaying: Boolean,
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
    val ringR = max(state.slowColor.r, state.fastColor.r - depth)
    val ringG = max(state.slowColor.g, state.fastColor.g - depth)
    val ringB = max(state.slowColor.b, state.fastColor.b - depth)

    val bucketPaths = Array(COLOR_BUCKETS) { Path() }
    val bucketUsed = BooleanArray(COLOR_BUCKETS)

    for (segmentIndex in 0 until CUBOID_SEGMENTS) {
      if (segmentIndex % 2 != ring.index % 2) continue

      val frequency =
        if (audioActive) {
          val bin = resolveAudioBin(ring.audioBins[segmentIndex], spectrum.size)
          spectrum[bin].coerceIn(0f, 1f) * 255f * gain
        } else {
          0f
        }
      val frequencyAdd = frequency / 20f
      // Reference behavior: audio pulls the inner face inward by frequency / 20.
      val reactiveRadius = (CUBOID_RADIUS - frequencyAdd).coerceAtLeast(CUBOID_RADIUS * 0.55f)
      val bucket =
        if (audioActive) {
          ((frequency / 256f) * COLOR_BUCKETS).toInt().coerceIn(0, COLOR_BUCKETS - 1)
        } else {
          0
        }
      val path = bucketPaths[bucket]

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
        bucketUsed[bucket] = true
      }

      // Keep the outer face until the ring reaches the near half of the tunnel.
      if (ring.z < CUBOID_FOV / 2f) {
        path.edge(outerCurrent1, outerCurrent0)
        path.edge(outerCurrent0, outerBack0)
        path.edge(outerBack0, outerBack1)
        path.edge(outerBack1, outerCurrent1)
        bucketUsed[bucket] = true
      }
    }

    for (bucket in 0 until COLOR_BUCKETS) {
      if (!bucketUsed[bucket]) continue
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
      val color = cuboidLineColor(ringR, ringG, ringB, lineValue, state.invertValue)
      drawPath(bucketPaths[bucket], color = color, style = stroke)
    }
  }
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
  if (spectrumSize <= CUBOID_AUDIO_BIN_MIN) return requested.mod(spectrumSize)
  val usable = spectrumSize - CUBOID_AUDIO_BIN_MIN
  return CUBOID_AUDIO_BIN_MIN + (requested - CUBOID_AUDIO_BIN_MIN).mod(usable)
}

private fun Path.edge(
  from: Offset,
  to: Offset,
) {
  moveTo(from.x, from.y)
  lineTo(to.x, to.y)
}

private fun cuboidLineColor(
  r: Float,
  g: Float,
  b: Float,
  lineValue: Float,
  invertValue: Float,
): Color {
  var red = (r * lineValue).roundToInt().coerceIn(0, 255)
  var green = (g * lineValue).roundToInt().coerceIn(0, 255)
  var blue = (b * lineValue).roundToInt().coerceIn(0, 255)

  if (invertValue > 0f) {
    val invert = invertValue.roundToInt().coerceIn(0, 255)
    red = abs(invert - red).coerceIn(0, 255)
    green = abs(invert - green).coerceIn(0, 255)
    blue = abs(invert - blue).coerceIn(0, 255)
  }

  return Color(red = red, green = green, blue = blue, alpha = 255)
}
