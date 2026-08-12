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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Low-cadence palette sampler for Flow Ambient mode.
 *
 * The palette transition is rendered inside AmbientShaderBuilder's one OUTPUT pass. This object
 * only publishes a new endpoint after meaningful scene movement; it never appends a second shader
 * and never rewrites another feature's shader options.
 */
object FlowAmbientController {
  private const val TAG = "FlowAmbientController"
  private const val FLOW_SHADER_DESC = "//!DESC Flow Palette Ambient Mode"
  private const val SAMPLE_INTERVAL_MS = 1_650L
  private const val POST_SEEK_SETTLE_MS = 120L
  private const val SHADER_LIST_SETTLE_MS = 80L
  private const val THUMBNAIL_SIZE = 96
  private const val SAMPLE_COUNT = 12
  private const val PATCH_RADIUS = 2
  private const val TRANSITION_SECONDS = 1.30f
  private const val MIN_COLOR_DISTANCE = 0.085f
  private const val DISPLAYED_TARGET_REJECT_DISTANCE = MIN_COLOR_DISTANCE * 0.55f
  private const val SCENE_CUT_DISTANCE = 0.46f
  private const val TEMPORAL_BLEND = 0.58f
  private const val SCENE_AVERAGE_MIX = 0.18f

  private const val PARAM_EXTERNAL = "mpvrx_flow_external"
  private const val PARAM_PREV_R = "mpvrx_flow_prev_r"
  private const val PARAM_PREV_G = "mpvrx_flow_prev_g"
  private const val PARAM_PREV_B = "mpvrx_flow_prev_b"
  private const val PARAM_TARGET_R = "mpvrx_flow_target_r"
  private const val PARAM_TARGET_G = "mpvrx_flow_target_g"
  private const val PARAM_TARGET_B = "mpvrx_flow_target_b"
  private const val PARAM_START = "mpvrx_flow_start"

  private val installed = AtomicBoolean(false)
  private val stateMutex = Mutex()
  private var activeFlow: ActiveFlow? = null
  private var transition: PaletteTransition? = null
  private var committedTarget: Rgb? = null
  private var lastSampleElapsedMs = 0L
  private var observedSeeking: Boolean? = null
  private var observedPaused: Boolean? = null

  fun install(scope: CoroutineScope) {
    if (!installed.compareAndSet(false, true)) return

    scope.launch(Dispatchers.Default) {
      PlaybackSession.propString["glsl-shaders"].collectLatest { shaderList ->
        delay(SHADER_LIST_SETTLE_MS)
        stateMutex.withLock {
          runCatching { refreshActiveFlow(shaderList) }
            .onFailure { error -> Log.w(TAG, "Failed to refresh Flow shader state", error) }
        }
      }
    }

    scope.launch(Dispatchers.Default) {
      while (isActive) {
        delay(SAMPLE_INTERVAL_MS)
        stateMutex.withLock {
          runCatching { sampleAndApplyFlowColor(force = false) }
            .onFailure { error -> Log.w(TAG, "Failed to sample Flow ambient colour", error) }
        }
      }
    }

    scope.launch(Dispatchers.Default) {
      PlaybackSession.propBoolean["seeking"].collectLatest { seeking ->
        val refreshAfterSeek =
          stateMutex.withLock {
            val previous = observedSeeking
            observedSeeking = seeking
            if (previous == false && seeking == true) freezeAtDisplayedColor()
            previous == true && seeking == false
          }
        if (refreshAfterSeek) {
          delay(POST_SEEK_SETTLE_MS)
          stateMutex.withLock {
            runCatching { sampleAndApplyFlowColor(force = true) }
              .onFailure { error -> Log.w(TAG, "Failed to refresh Flow colour after seek", error) }
          }
        }
      }
    }

    scope.launch(Dispatchers.Default) {
      PlaybackSession.propBoolean["pause"].collectLatest { paused ->
        val refreshAfterResume =
          stateMutex.withLock {
            val previous = observedPaused
            observedPaused = paused
            previous == true && paused == false
          }
        if (refreshAfterResume) {
          delay(POST_SEEK_SETTLE_MS)
          stateMutex.withLock {
            runCatching { sampleAndApplyFlowColor(force = true) }
              .onFailure { error -> Log.w(TAG, "Failed to refresh Flow colour after resume", error) }
          }
        }
      }
    }
  }

  private fun refreshActiveFlow(shaderList: String?) {
    val shaderPaths = parseShaderPaths(shaderList)
    val playbackGeneration = PlaybackSession.state.value.generation
    val sourcePath =
      shaderPaths.firstOrNull { path ->
        path.startsWith("/") &&
          runCatching { File(path).readText().contains(FLOW_SHADER_DESC) }.getOrDefault(false)
      }

    if (sourcePath == null) {
      if (activeFlow != null) {
        activeFlow = null
        resetPaletteState(clearShaderOptions = true)
      }
      return
    }

    val previous = activeFlow
    val generationChanged = previous != null && previous.playbackGeneration != playbackGeneration
    activeFlow = ActiveFlow(sourcePath = sourcePath, playbackGeneration = playbackGeneration)
    if (previous == null || generationChanged) {
      // A unique ambient filename can replace the active shader within one media generation
      // (orientation/HDR recompiles). Preserve that transition, but never carry a palette across
      // files even when the transient no-Flow list update is coalesced by the settle window.
      resetPaletteState(clearShaderOptions = true)
      sampleAndApplyFlowColor(force = true)
    }
  }

  private fun sampleAndApplyFlowColor(force: Boolean) {
    var active = activeFlow ?: return
    val playback = PlaybackSession.state.value
    if (active.playbackGeneration != playback.generation) {
      active = active.copy(playbackGeneration = playback.generation)
      activeFlow = active
      resetPaletteState(clearShaderOptions = true)
    }
    if (!PlaybackSession.isInitialized ||
      (playback.paused && !force) ||
      observedSeeking == true ||
      !playback.surfaceAttached ||
      playback.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    ) {
      return
    }

    val nowElapsed = SystemClock.elapsedRealtime()
    if (!force && nowElapsed - lastSampleElapsedMs < SAMPLE_INTERVAL_MS) return
    val livePaths = parseShaderPaths(PlaybackSession.getPropertyString("glsl-shaders"))
    if (active.sourcePath !in livePaths) return

    val thumbnail = PlaybackSession.grabThumbnail(THUMBNAIL_SIZE) ?: return
    val rawTarget =
      try {
        selectFlowColor(thumbnail)
      } finally {
        if (!thumbnail.isRecycled) thumbnail.recycle()
      } ?: return
    lastSampleElapsedMs = nowElapsed

    val pts = PlaybackSession.getPropertyDouble("time-pos")?.toFloat()?.takeIf { it.isFinite() } ?: return
    val oldTarget = committedTarget
    if (oldTarget == null) {
      publishTransition(rawTarget, rawTarget, pts)
      transition = PaletteTransition(rawTarget, rawTarget, pts)
      committedTarget = rawTarget
      return
    }

    val rawDistance = oldTarget.distance(rawTarget)
    if (!force && rawDistance < MIN_COLOR_DISTANCE) return

    val nextTarget =
      if (rawDistance >= SCENE_CUT_DISTANCE) rawTarget else oldTarget.mix(rawTarget, TEMPORAL_BLEND)
    val displayed = displayedColorAt(transition, pts) ?: oldTarget
    if (displayed.distance(nextTarget) < DISPLAYED_TARGET_REJECT_DISTANCE) return

    publishTransition(displayed, nextTarget, pts)
    transition = PaletteTransition(displayed, nextTarget, pts)
    committedTarget = nextTarget
  }

  private fun resetPaletteState(clearShaderOptions: Boolean) {
    transition = null
    committedTarget = null
    lastSampleElapsedMs = 0L
    if (clearShaderOptions) ShaderOptionRegistry.clear(ShaderOptionOwner.FLOW_AMBIENT)
  }

  private fun freezeAtDisplayedColor() {
    if (activeFlow == null) return
    val pts = PlaybackSession.getPropertyDouble("time-pos")?.toFloat()?.takeIf { it.isFinite() } ?: return
    val displayed = displayedColorAt(transition, pts) ?: committedTarget ?: return
    publishTransition(displayed, displayed, pts)
    transition = PaletteTransition(displayed, displayed, pts)
    committedTarget = displayed
  }

  private fun displayedColorAt(
    activeTransition: PaletteTransition?,
    pts: Float,
  ): Rgb? {
    activeTransition ?: return null
    val elapsed = pts - activeTransition.startPts
    if (!elapsed.isFinite() || elapsed <= 0f) return activeTransition.from
    if (elapsed >= TRANSITION_SECONDS) return activeTransition.target
    val linear = (elapsed / TRANSITION_SECONDS).coerceIn(0f, 1f)
    val smooth = linear * linear * (3f - 2f * linear)
    return activeTransition.from.mix(activeTransition.target, smooth)
  }

  private fun publishTransition(
    previous: Rgb,
    target: Rgb,
    start: Float,
  ) {
    ShaderOptionRegistry.replace(
      ShaderOptionOwner.FLOW_AMBIENT,
      listOf(
        PARAM_EXTERNAL to "1.0",
        PARAM_PREV_R to formatFloat(previous.r),
        PARAM_PREV_G to formatFloat(previous.g),
        PARAM_PREV_B to formatFloat(previous.b),
        PARAM_TARGET_R to formatFloat(target.r),
        PARAM_TARGET_G to formatFloat(target.g),
        PARAM_TARGET_B to formatFloat(target.b),
        PARAM_START to formatFloat(start),
      ),
    )
  }

  private fun selectFlowColor(bitmap: Bitmap): Rgb? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val readable =
      if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
      else bitmap
    try {
      val pixels = IntArray(readable.width * readable.height)
      readable.getPixels(pixels, 0, readable.width, 0, 0, readable.width, readable.height)
      val palette =
        (1..SAMPLE_COUNT).map { index ->
          val x = (halton(index, 2) * (readable.width - 1)).roundToInt().coerceIn(0, readable.width - 1)
          val y = (halton(index, 3) * (readable.height - 1)).roundToInt().coerceIn(0, readable.height - 1)
          averagePatch(pixels, readable.width, readable.height, x, y)
        }
      if (palette.isEmpty()) return null

      val sceneAverage =
        Rgb(
          r = palette.sumOf { it.r.toDouble() }.toFloat() / palette.size,
          g = palette.sumOf { it.g.toDouble() }.toFloat() / palette.size,
          b = palette.sumOf { it.b.toDouble() }.toFloat() / palette.size,
        )
      var preferred: Rgb? = null
      var fallback = sceneAverage
      var preferredScore = -1f
      var fallbackScore = -1f

      palette.forEach { candidate ->
        val luma = candidate.luma()
        val saturation = candidate.saturation()
        val lumaFit = 1f - ((abs(luma - 0.56f) / 0.56f).coerceIn(0f, 1f))
        val population =
          palette.sumOf { other ->
            val similarity = 1f - (candidate.distance(other) / 0.48f).coerceIn(0f, 1f)
            (similarity * similarity).toDouble()
          }.toFloat() / palette.size
        val score = (saturation * 1.5f + lumaFit) * (0.30f + population * 1.70f)
        if (score > fallbackScore) {
          fallbackScore = score
          fallback = candidate
        }
        if (luma in 0.20f..0.86f && score > preferredScore) {
          preferredScore = score
          preferred = candidate
        }
      }
      return (preferred ?: fallback).mix(sceneAverage, SCENE_AVERAGE_MIX)
    } finally {
      if (readable !== bitmap && !readable.isRecycled) readable.recycle()
    }
  }

  private fun averagePatch(
    pixels: IntArray,
    width: Int,
    height: Int,
    centerX: Int,
    centerY: Int,
  ): Rgb {
    var red = 0f
    var green = 0f
    var blue = 0f
    var count = 0
    for (y in max(0, centerY - PATCH_RADIUS)..min(height - 1, centerY + PATCH_RADIUS)) {
      for (x in max(0, centerX - PATCH_RADIUS)..min(width - 1, centerX + PATCH_RADIUS)) {
        val color = Rgb.fromArgb(pixels[y * width + x])
        red += color.r
        green += color.g
        blue += color.b
        count++
      }
    }
    return Rgb(red / count, green / count, blue / count)
  }

  private fun parseShaderPaths(raw: String?): List<String> {
    return MpvPathList.decode(raw)
      .map { it.trim().trim('"', '\'') }
      .filter { it.endsWith(".glsl", ignoreCase = true) }
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

  private fun formatFloat(value: Float): String =
    String.format(Locale.US, "%.6f", value.coerceIn(-100_000f, 100_000f))
      .trimEnd('0')
      .trimEnd('.')
      .let { text -> if (text.contains('.')) text else "$text.0" }

  private data class ActiveFlow(
    val sourcePath: String,
    val playbackGeneration: Long,
  )

  private data class PaletteTransition(
    val from: Rgb,
    val target: Rgb,
    val startPts: Float,
  )

  private data class Rgb(
    val r: Float,
    val g: Float,
    val b: Float,
  ) {
    fun luma(): Float = r * 0.2126f + g * 0.7152f + b * 0.0722f

    fun saturation(): Float {
      val maximum = max(r, max(g, b))
      val minimum = min(r, min(g, b))
      return if (maximum <= 0.00001f) 0f else (maximum - minimum) / maximum
    }

    fun distance(other: Rgb): Float =
      sqrt(
        (r - other.r) * (r - other.r) +
          (g - other.g) * (g - other.g) +
          (b - other.b) * (b - other.b),
      )

    fun mix(
      other: Rgb,
      amount: Float,
    ): Rgb {
      val t = amount.coerceIn(0f, 1f)
      return Rgb(
        r = r + (other.r - r) * t,
        g = g + (other.g - g) * t,
        b = b + (other.b - b) * t,
      )
    }

    companion object {
      fun fromArgb(pixel: Int): Rgb =
        Rgb(
          r = ((pixel ushr 16) and 0xff) / 255f,
          g = ((pixel ushr 8) and 0xff) / 255f,
          b = (pixel and 0xff) / 255f,
        )
    }
  }
}
