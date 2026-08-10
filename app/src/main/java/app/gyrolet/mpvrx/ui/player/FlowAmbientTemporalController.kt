/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.graphics.Bitmap
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decouples Flow palette selection from the video render cadence.
 *
 * The GLSL implementation can pick a new palette winner on every rendered frame, which makes small
 * scene changes visible as rapid ambient-colour flicker. This controller follows the timing model
 * used by Flow: capture a tiny current-frame sample roughly every 900 ms, ignore near-identical
 * frames, and move toward an accepted target with a time-based low-pass filter at about 16 Hz.
 *
 * The smoothed RGB value is sent through mpv's tunable shader parameters. If a thumbnail cannot be
 * obtained, the shader keeps its existing GPU picker as a fallback instead of disabling ambience.
 */
object FlowAmbientTemporalController {
  private const val SAMPLE_DIMENSION = 96
  private const val CAPTURE_MS = 900L
  private const val IDLE_CAPTURE_MS = 1500L
  private const val SMOOTH_TICK_MS = 60L
  private const val SMOOTH_IDLE_TICK_MS = 200L
  private const val SMOOTH_TAU_MS = 600f
  private const val CONVERGENCE_EPS = 0.0015f
  private const val FRAME_CHANGE_THRESHOLD = 4f
  private const val CHANGE_SAMPLE_STEP = 7
  private const val FLOW_SHADER_MISSING_GRACE_MS = 2500L

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lock = Any()

  @Volatile private var requestedActive = false
  private var worker: Job? = null

  fun setActive(active: Boolean) {
    synchronized(lock) {
      requestedActive = active
      if (!active) {
        worker?.cancel()
        worker = null
        setExternalColorEnabled(false)
        return
      }
      if (worker?.isActive == true) return
      worker =
        scope.launch {
          try {
            runLoop()
          } finally {
            setExternalColorEnabled(false)
            val finishingJob = currentCoroutineContext()[Job]
            synchronized(lock) {
              if (worker === finishingJob) worker = null
            }
          }
        }
    }
  }

  private suspend fun runLoop() {
    var previousAcceptedPixels: IntArray? = null
    var target = FloatArray(3)
    val current = FloatArray(3)
    var hasTarget = false
    var seeded = false
    var nextCaptureAt = 0L
    var lastSmoothAt = SystemClock.elapsedRealtime()
    val startedAt = lastSmoothAt
    var shaderMissingSince = 0L

    while (currentCoroutineContext().isActive && requestedActive) {
      val now = SystemClock.elapsedRealtime()
      val ambientShaderInstalled = hasAmbientShaderInstalled()
      if (!ambientShaderInstalled) {
        if (shaderMissingSince == 0L) shaderMissingSince = now
        if (now - startedAt > FLOW_SHADER_MISSING_GRACE_MS &&
          now - shaderMissingSince > FLOW_SHADER_MISSING_GRACE_MS
        ) {
          return
        }
        delay(SMOOTH_IDLE_TICK_MS)
        lastSmoothAt = SystemClock.elapsedRealtime()
        continue
      }
      shaderMissingSince = 0L

      val paused = PlaybackSession.getPropertyBoolean("pause") == true
      val ready = PlaybackSession.state.value.phase in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)

      if (ready && !paused && now >= nextCaptureAt) {
        val bitmap = PlaybackSession.grabThumbnail(SAMPLE_DIMENSION)
        nextCaptureAt = now + if (bitmap == null) IDLE_CAPTURE_MS else CAPTURE_MS
        if (bitmap != null) {
          try {
            val sampled = readPixels(bitmap)
            if (sampled != null) {
              val (pixels, width, height) = sampled
              val previous = previousAcceptedPixels
              val changed =
                previous == null || previous.size != pixels.size ||
                  meanAbsDiff(pixels, previous) >= FRAME_CHANGE_THRESHOLD
              if (changed) {
                previousAcceptedPixels = pixels.copyOf()
                target = selectFlowColor(pixels, width, height)
                hasTarget = true
              }
            }
          } finally {
            bitmap.recycle()
          }
        }
      } else if (!ready || paused) {
        nextCaptureAt = now + IDLE_CAPTURE_MS
      }

      if (!hasTarget) {
        delay(SMOOTH_IDLE_TICK_MS)
        lastSmoothAt = SystemClock.elapsedRealtime()
        continue
      }

      if (!seeded) {
        target.copyInto(current)
        seeded = true
        publish(current)
        delay(SMOOTH_TICK_MS)
        lastSmoothAt = SystemClock.elapsedRealtime()
        continue
      }

      val tickNow = SystemClock.elapsedRealtime()
      val dt = (tickNow - lastSmoothAt).coerceIn(1L, 500L)
      lastSmoothAt = tickNow
      val alpha = 1f - exp(-dt.toFloat() / SMOOTH_TAU_MS)
      val moved = step(current, target, alpha)
      if (moved) publish(current)
      delay(if (moved) SMOOTH_TICK_MS else SMOOTH_IDLE_TICK_MS)
    }
  }

  private fun hasAmbientShaderInstalled(): Boolean =
    PlaybackSession.getPropertyString("glsl-shaders")?.contains("ambient_") == true

  private fun readPixels(bitmap: Bitmap): Triple<IntArray, Int, Int>? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val readable =
      if (bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
      } else {
        bitmap
      }
    return try {
      val pixels = IntArray(readable.width * readable.height)
      readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
      Triple(pixels, readable.width, readable.height)
    } finally {
      if (readable !== bitmap) readable.recycle()
    }
  }

  private fun publish(color: FloatArray) {
    val options =
      String.format(
        Locale.US,
        "flow_external=1,flow_r=%.6f,flow_g=%.6f,flow_b=%.6f",
        color[0].coerceIn(0f, 1f),
        color[1].coerceIn(0f, 1f),
        color[2].coerceIn(0f, 1f),
      )
    PlaybackSession.command("change-list", "glsl-shader-opts", "add", options)
  }

  private fun setExternalColorEnabled(enabled: Boolean) {
    if (!PlaybackSession.isInitialized) return
    PlaybackSession.command(
      "change-list",
      "glsl-shader-opts",
      "append",
      "flow_external=${if (enabled) 1 else 0}",
    )
  }

  private fun meanAbsDiff(
    current: IntArray,
    previous: IntArray,
  ): Float {
    val count = minOf(current.size, previous.size)
    if (count == 0) return Float.MAX_VALUE
    var sum = 0L
    var channels = 0
    var index = 0
    while (index < count) {
      val a = current[index]
      val b = previous[index]
      sum += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
      sum += abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
      sum += abs((a and 0xFF) - (b and 0xFF))
      channels += 3
      index += CHANGE_SAMPLE_STEP
    }
    return if (channels == 0) Float.MAX_VALUE else sum.toFloat() / channels.toFloat()
  }

  private fun selectFlowColor(
    pixels: IntArray,
    width: Int,
    height: Int,
  ): FloatArray {
    val palette = Array(12) { FloatArray(3) }
    val sceneAverage = FloatArray(3)

    for (sampleIndex in palette.indices) {
      val index = sampleIndex + 1
      val x = (halton(index, 2) * (width - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, width - 1)
      val y = (halton(index, 3) * (height - 1).coerceAtLeast(0)).roundToInt().coerceIn(0, height - 1)
      val pixel = pixels[y * width + x]
      val sample = palette[sampleIndex]
      sample[0] = ((pixel shr 16) and 0xFF) / 255f
      sample[1] = ((pixel shr 8) and 0xFF) / 255f
      sample[2] = (pixel and 0xFF) / 255f
      sceneAverage[0] += sample[0]
      sceneAverage[1] += sample[1]
      sceneAverage[2] += sample[2]
    }
    for (channel in 0..2) sceneAverage[channel] /= palette.size.toFloat()

    var preferred: FloatArray? = null
    var fallback = sceneAverage.copyOf()
    var preferredScore = -1f
    var fallbackScore = -1f

    for (candidate in palette) {
      val luminance = flowLuma(candidate)
      val saturation = flowSaturation(candidate)
      val lumaFit = 1f - (abs(luminance - 0.56f) / 0.56f).coerceIn(0f, 1f)
      val population = flowPopulation(candidate, palette)
      val score = (saturation * 1.5f + lumaFit) * (0.30f + population * 1.70f)

      if (score > fallbackScore) {
        fallbackScore = score
        fallback = candidate.copyOf()
      }
      if (luminance in 0.20f..0.86f && score > preferredScore) {
        preferredScore = score
        preferred = candidate.copyOf()
      }
    }

    val picked = preferred ?: fallback
    return FloatArray(3) { channel -> picked[channel] * 0.88f + sceneAverage[channel] * 0.12f }
  }

  private fun flowLuma(rgb: FloatArray): Float =
    rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f

  private fun flowSaturation(rgb: FloatArray): Float {
    val max = maxOf(rgb[0], rgb[1], rgb[2])
    val min = minOf(rgb[0], rgb[1], rgb[2])
    return if (max <= 1e-5f) 0f else (max - min) / max
  }

  private fun flowPopulation(
    candidate: FloatArray,
    palette: Array<FloatArray>,
  ): Float {
    var population = 0f
    for (sample in palette) {
      val dr = candidate[0] - sample[0]
      val dg = candidate[1] - sample[1]
      val db = candidate[2] - sample[2]
      val distance = sqrt(dr * dr + dg * dg + db * db)
      val similarity = 1f - (distance / 0.48f).coerceIn(0f, 1f)
      population += similarity * similarity
    }
    return population / palette.size.toFloat()
  }

  private fun step(
    current: FloatArray,
    target: FloatArray,
    alpha: Float,
  ): Boolean {
    var moved = false
    for (index in current.indices) {
      val delta = target[index] - current[index]
      if (abs(delta) > CONVERGENCE_EPS) {
        current[index] += delta * alpha
        moved = true
      } else {
        current[index] = target[index]
      }
    }
    return moved
  }

  private fun halton(
    index: Int,
    base: Int,
  ): Float {
    var result = 0f
    var factor = 1f
    var value = index
    while (value > 0) {
      factor /= base.toFloat()
      result += factor * (value % base)
      value /= base
    }
    return result
  }
}
