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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Flow-only cadence controller.
 *
 * The original Flow shader intentionally remains the owner of video remapping and all non-Flow
 * ambient modes are untouched. When that exact shader is present, this controller appends one
 * lightweight OUTPUT pass that replaces only the letterbox/pillarbox glow with a palette sampled
 * from the current video every [SAMPLE_INTERVAL_MS]. This decouples colour picking from display
 * refresh rate and decoded frame rate while preserving the existing Flow look.
 */
object FlowAmbientController {
  private const val TAG = "FlowAmbientController"
  private const val FLOW_SHADER_DESC = "//!DESC Flow Palette Ambient Mode"
  private const val OVERLAY_SHADER_DESC = "//!DESC mpvRx Flow Cadence Overlay"
  private const val SAMPLE_INTERVAL_MS = 1_650L
  private const val THUMBNAIL_SIZE = 96
  private const val SAMPLE_COUNT = 12
  private const val TRANSITION_SECONDS = 0.45f
  private const val COLOR_EPSILON = 0.008f

  private const val PARAM_PREV_R = "mpvrx_flow_prev_r"
  private const val PARAM_PREV_G = "mpvrx_flow_prev_g"
  private const val PARAM_PREV_B = "mpvrx_flow_prev_b"
  private const val PARAM_TARGET_R = "mpvrx_flow_target_r"
  private const val PARAM_TARGET_G = "mpvrx_flow_target_g"
  private const val PARAM_TARGET_B = "mpvrx_flow_target_b"
  private const val PARAM_START = "mpvrx_flow_start"

  private val installed = AtomicBoolean(false)
  private val stateLock = Any()

  @Volatile
  private var activeFlow: ActiveFlow? = null

  @Volatile
  private var lastTarget: Rgb? = null

  fun install(scope: CoroutineScope) {
    if (!installed.compareAndSet(false, true)) return

    scope.launch(Dispatchers.Default) {
      PlaybackSession.propString["glsl-shaders"].collectLatest { shaderList ->
        runCatching { refreshActiveFlow(shaderList) }
          .onFailure { error -> Log.w(TAG, "Failed to refresh Flow shader state", error) }
      }
    }

    scope.launch(Dispatchers.Default) {
      while (isActive) {
        delay(SAMPLE_INTERVAL_MS)
        runCatching { sampleAndApplyFlowColor() }
          .onFailure { error -> Log.w(TAG, "Failed to sample Flow ambient colour", error) }
      }
    }
  }

  private fun refreshActiveFlow(shaderList: String?) {
    val shaderPaths = parseShaderPaths(shaderList)
    val sourcePath =
      shaderPaths.firstOrNull { path ->
        path.startsWith("/") &&
          !path.contains("mpvrx_flow_cadence_") &&
          runCatching { File(path).readText().contains(FLOW_SHADER_DESC) }.getOrDefault(false)
      }

    if (sourcePath == null) {
      deactivateFlow(shaderPaths)
      return
    }

    val source = runCatching { File(sourcePath).readText() }.getOrNull() ?: return
    val config = parseFlowConfig(source) ?: return
    val overlayFile = File(File(sourcePath).parentFile, "mpvrx_flow_cadence_${File(sourcePath).nameWithoutExtension}.glsl")
    val overlaySource = buildOverlayShader(config)
    if (!overlayFile.exists() || runCatching { overlayFile.readText() }.getOrNull() != overlaySource) {
      overlayFile.writeText(overlaySource)
    }

    val previous = synchronized(stateLock) {
      val old = activeFlow
      activeFlow = ActiveFlow(sourcePath = sourcePath, overlayPath = overlayFile.absolutePath)
      if (old?.sourcePath != sourcePath) lastTarget = null
      old
    }

    if (previous != null && previous.overlayPath != overlayFile.absolutePath && shaderPaths.contains(previous.overlayPath)) {
      PlaybackSession.command("change-list", "glsl-shaders", "remove", previous.overlayPath)
      runCatching { File(previous.overlayPath).delete() }
    }

    val overlayWasMissing = !shaderPaths.contains(overlayFile.absolutePath)
    if (overlayWasMissing) {
      PlaybackSession.command("change-list", "glsl-shaders", "append", overlayFile.absolutePath)
    }

    if (lastTarget == null && (previous?.sourcePath != sourcePath || overlayWasMissing)) {
      runCatching { sampleAndApplyFlowColor() }
        .onFailure { error -> Log.w(TAG, "Failed to initialize Flow ambient colour", error) }
    }
  }

  private fun deactivateFlow(shaderPaths: List<String>) {
    val previous = synchronized(stateLock) {
      activeFlow.also {
        activeFlow = null
        lastTarget = null
      }
    }
    previous?.let { active ->
      if (shaderPaths.contains(active.overlayPath)) {
        PlaybackSession.command("change-list", "glsl-shaders", "remove", active.overlayPath)
      }
      runCatching { File(active.overlayPath).delete() }
    }
    clearOurShaderOptions()
  }

  private fun sampleAndApplyFlowColor() {
    val active = activeFlow ?: return
    val playback = PlaybackSession.state.value
    if (!PlaybackSession.isInitialized ||
      playback.paused ||
      playback.phase !in setOf(PlaybackPhase.READY, PlaybackPhase.BACKGROUND)
    ) {
      return
    }

    val livePaths = parseShaderPaths(PlaybackSession.getPropertyString("glsl-shaders"))
    if (!livePaths.contains(active.sourcePath) || !livePaths.contains(active.overlayPath)) return

    val thumbnail = PlaybackSession.grabThumbnail(THUMBNAIL_SIZE) ?: return
    val target = selectFlowColor(thumbnail) ?: return
    val previous = lastTarget ?: target
    if (lastTarget != null && previous.maxDistance(target) < COLOR_EPSILON) return

    val start = PlaybackSession.getPropertyDouble("time-pos")?.toFloat() ?: 0f
    applyShaderOptions(previous, target, start)
    lastTarget = target
  }

  private fun applyShaderOptions(
    previous: Rgb,
    target: Rgb,
    start: Float,
  ) {
    val values =
      linkedMapOf(
        PARAM_PREV_R to previous.r,
        PARAM_PREV_G to previous.g,
        PARAM_PREV_B to previous.b,
        PARAM_TARGET_R to target.r,
        PARAM_TARGET_G to target.g,
        PARAM_TARGET_B to target.b,
        PARAM_START to start,
      )
    val merged = mergeShaderOptions(PlaybackSession.getPropertyString("glsl-shader-opts"), values)
    PlaybackSession.setPropertyString("glsl-shader-opts", merged)
  }

  private fun clearOurShaderOptions() {
    if (!PlaybackSession.isInitialized) return
    val existing = PlaybackSession.getPropertyString("glsl-shader-opts").orEmpty()
    if (existing.isBlank()) return
    val retained =
      splitObjectSettings(existing).filterNot { entry ->
        shaderOptionKeys.contains(entry.substringBefore('=').trim())
      }
    PlaybackSession.setPropertyString("glsl-shader-opts", retained.joinToString(","))
  }

  private fun mergeShaderOptions(
    existing: String?,
    updates: Map<String, Float>,
  ): String {
    val retained =
      splitObjectSettings(existing.orEmpty()).filterNot { entry ->
        updates.containsKey(entry.substringBefore('=').trim())
      }.toMutableList()
    updates.forEach { (key, value) -> retained += "$key=${formatFloat(value)}" }
    return retained.joinToString(",")
  }

  private fun splitObjectSettings(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    raw.forEach { char ->
      when {
        escaped -> {
          current.append(char)
          escaped = false
        }
        char == '\\' -> {
          current.append(char)
          escaped = true
        }
        char == ',' -> {
          current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
          current.clear()
        }
        else -> current.append(char)
      }
    }
    current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
    return result
  }

  private fun parseShaderPaths(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw
      .split(',', ';', ':')
      .map { it.trim().trim('"', '\'') }
      .filter { it.endsWith(".glsl", ignoreCase = true) }
  }

  private fun parseFlowConfig(source: String): FlowConfig? {
    fun number(name: String): Float? =
      Regex("#define\\s+$name\\s+([-+0-9.eE]+)")
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.toFloatOrNull()

    val scaleX = number("SCALE_X") ?: return null
    val scaleY = number("SCALE_Y") ?: return null
    val opacity = number("OPACITY") ?: 1f
    val vignette = number("VIGNETTE_STR") ?: 0f
    val linearHdr = number("IS_LINEAR_HDR")?.roundToInt() == 1
    return FlowConfig(scaleX, scaleY, opacity, vignette, linearHdr)
  }

  private fun buildOverlayShader(config: FlowConfig): String =
    """
//!PARAM PTS
//!TYPE float
0.0

//!PARAM $PARAM_PREV_R
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_PREV_G
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_PREV_B
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_TARGET_R
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_TARGET_G
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_TARGET_B
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.18

//!PARAM $PARAM_START
//!TYPE float
0.0

//!HOOK OUTPUT
//!BIND HOOKED
$OVERLAY_SHADER_DESC

#define SCALE_X          ${formatFloat(config.scaleX)}
#define SCALE_Y          ${formatFloat(config.scaleY)}
#define OPACITY          ${formatFloat(config.opacity)}
#define VIGNETTE_STR     ${formatFloat(config.vignetteStrength)}
#define IS_LINEAR_HDR    ${if (config.isLinearHdr) 1 else 0}
#define TRANSITION_SEC   ${formatFloat(TRANSITION_SECONDS)}

precision mediump float;

float flow_luma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

#if IS_LINEAR_HDR
vec3 to_linear(vec3 c) { return c * c; }
#endif

vec4 hook() {
    highp vec2 uv = HOOKED_pos;
    highp vec2 video_uv = (uv - 0.5) * vec2(SCALE_X, SCALE_Y) + 0.5;

    if (video_uv.x >= 0.0 && video_uv.x <= 1.0 &&
        video_uv.y >= 0.0 && video_uv.y <= 1.0) {
        return HOOKED_tex(uv);
    }

    vec3 previous_color = vec3($PARAM_PREV_R, $PARAM_PREV_G, $PARAM_PREV_B);
    vec3 target_color = vec3($PARAM_TARGET_R, $PARAM_TARGET_G, $PARAM_TARGET_B);
    float transition = smoothstep(0.0, 1.0, clamp((PTS - $PARAM_START) / TRANSITION_SEC, 0.0, 1.0));
    vec3 ambient_color = mix(previous_color, target_color, transition);

    float lum = flow_luma(ambient_color);
    ambient_color = clamp(mix(vec3(lum), ambient_color, 1.16), 0.0, 1.0);

#if IS_LINEAR_HDR
    ambient_color = to_linear(ambient_color) * 0.40;
#else
    ambient_color *= 0.34;
#endif

    highp vec2 edge_uv = clamp(video_uv, 0.0, 1.0);
    float dist = length(video_uv - edge_uv);
    ambient_color *= exp(-dist * 2.35);

    float vig_r = length(uv - 0.5) * 2.0;
    ambient_color *= mix(1.0, smoothstep(1.3, 0.1, vig_r), VIGNETTE_STR);
    ambient_color *= OPACITY;

    highp vec2 screen_pos = floor(uv * HOOKED_size);
    float ign = fract(dot(screen_pos, vec2(0.75487766, 0.56984029)));
    ambient_color = clamp(ambient_color + (ign - 0.5) * 0.004, 0.0, 1.0);

    return vec4(ambient_color, 1.0);
}
    """.trimIndent()

  private fun selectFlowColor(bitmap: Bitmap): Rgb? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val palette =
      (1..SAMPLE_COUNT).map { index ->
        val x = (halton(index, 2) * (bitmap.width - 1)).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (halton(index, 3) * (bitmap.height - 1)).roundToInt().coerceIn(0, bitmap.height - 1)
        Rgb.fromArgb(bitmap.getPixel(x, y))
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

    return (preferred ?: fallback).mix(sceneAverage, 0.12f)
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
    String.format(Locale.US, "%.6f", value.coerceIn(-100_000f, 100_000f)).trimEnd('0').trimEnd('.').let { text ->
      if (text.contains('.')) text else "$text.0"
    }

  private data class FlowConfig(
    val scaleX: Float,
    val scaleY: Float,
    val opacity: Float,
    val vignetteStrength: Float,
    val isLinearHdr: Boolean,
  )

  private data class ActiveFlow(
    val sourcePath: String,
    val overlayPath: String,
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

    fun maxDistance(other: Rgb): Float =
      max(abs(r - other.r), max(abs(g - other.g), abs(b - other.b)))

    companion object {
      fun fromArgb(pixel: Int): Rgb =
        Rgb(
          r = ((pixel ushr 16) and 0xff) / 255f,
          g = ((pixel ushr 8) and 0xff) / 255f,
          b = (pixel and 0xff) / 255f,
        )
    }
  }

  private val shaderOptionKeys =
    setOf(
      PARAM_PREV_R,
      PARAM_PREV_G,
      PARAM_PREV_B,
      PARAM_TARGET_R,
      PARAM_TARGET_G,
      PARAM_TARGET_B,
      PARAM_START,
    )
}
