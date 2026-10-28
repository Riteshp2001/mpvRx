/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Imported and adapted from mpvEx (app.marlboroadvance.mpvex).
 */

package app.gyrolet.mpvrx.ui.player

import android.util.Log
import app.gyrolet.mpvrx.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Manages ambient mode functionality imported from mpvEx.
 * Handles YouTube-style GPU shader generation, video scaling, and parameter management.
 */
class AmbientModeManager(
  private val playerPreferences: PlayerPreferences,
  private val cacheDir: File,
  private val scope: CoroutineScope,
  private val onShowText: (String) -> Unit,
) {
  companion object {
    private const val TAG = "AmbientModeManager"
    private const val GOLDEN_ANGLE = 2.399963229728653

    private fun glslFloat(value: Double): String {
      val normalized = if (abs(value) < 0.0000005) 0.0 else value
      val formatted =
        String.format(Locale.US, "%.8f", normalized)
          .trimEnd('0')
          .trimEnd('.')
      return if (formatted.contains('.')) formatted else "$formatted.0"
    }

    private fun generateTapTable(count: Int, maxRadius: Double): String {
      val taps = (0 until count).joinToString(",\n") { i ->
        val r = kotlin.math.sqrt((i.toDouble() + 0.5) / count.toDouble()) * maxRadius
        val theta = (i.toDouble() + 0.5) * GOLDEN_ANGLE
        val x = kotlin.math.cos(theta) * r
        val y = kotlin.math.sin(theta) * r
        "    vec2(${glslFloat(x)}, ${glslFloat(y)})"
      }
      return "const vec2 YT2_TAPS[$count] = vec2[$count](\n$taps\n);"
    }

    private val DEFAULT_YT2_TAP_TABLE: String by lazy {
      generateTapTable(20, 0.16)
    }

    /**
     * Builds an ultra-efficient YouTube-style ambient GLSL shader with dynamic per-side color sampling.
     *
     * Features:
     * - Dynamic separate color changing for each edge/side (matching Glow mode's spatial reactivity).
     * - Precomputed 20-tap golden spiral kernel (0 hash/trig computations in fragment shader).
     * - Distance culling early-exit for bar fragments beyond visible fade reach.
     * - High-speed Interleaved Gradient Noise (IGN) debanding dither.
     * - Temporal stability and YouTube-style saturation/exponential falloff.
     */
    fun buildAmbientShader(
      sx: Double,
      sy: Double,
      cx: Double = 0.5,
      cy: Double = 0.5,
      samples: Int = 20,
      satBoost: Float = 1.3f,
      intensity: Float = 0.30f,
      fadeCurve: Float = 2.5f,
    ): String {
      val glslSx = glslFloat(sx)
      val glslSy = glslFloat(sy)
      val glslCx = glslFloat(cx)
      val glslCy = glslFloat(cy)
      val glslAspectScaleRatio = glslFloat(if (abs(sy) > 0.00001) sx / sy else 1.0)
      val glslSatBoost = glslFloat(satBoost.toDouble())
      val glslIntensity = glslFloat(intensity.toDouble())
      val glslFadeCurve = glslFloat(fadeCurve.toDouble())
      val glslInvSamples = glslFloat(1.0 / samples.toDouble().coerceAtLeast(1.0))

      val tapTable = if (samples == 20) DEFAULT_YT2_TAP_TABLE else generateTapTable(samples, 0.16)

      return """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC YouTube-Style Ambient Mode (Dynamic & High Efficiency)

#define SCALE_X             $glslSx
#define SCALE_Y             $glslSy
#define CENTER_X            $glslCx
#define CENTER_Y            $glslCy
#define ASPECT_SCALE_RATIO  $glslAspectScaleRatio

// Fast Interleaved Gradient Noise for debanding (avoids expensive sin() on mobile GPUs)
highp float ign(highp vec2 p) {
    highp vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(p, magic.xy)));
}

// Precomputed golden-spiral sample offsets
$tapTable

vec4 hook() {
    highp vec2 uv = HOOKED_pos;
    highp vec2 video_uv = (uv - vec2(CENTER_X, CENTER_Y)) * vec2(SCALE_X, SCALE_Y) + 0.5;

    highp vec2 half_texel = vec2(0.5) / HOOKED_size;
    highp vec2 safe_min = half_texel;
    highp vec2 safe_max = vec2(1.0) - half_texel;

    // Return video pixel immediately if inside video bounds
    if (video_uv.x >= 0.0 && video_uv.x <= 1.0 &&
        video_uv.y >= 0.0 && video_uv.y <= 1.0) {
        return HOOKED_tex(clamp(video_uv, safe_min, safe_max));
    }

    // Distance from the nearest video edge
    highp vec2 edge_uv = clamp(video_uv, 0.0, 1.0);
    highp float dist = length(video_uv - edge_uv);

    // Early out: if beyond visual fade reach (fade < 0.0025, invisible in 8-bit color), skip all texture samples
    if (dist >= 2.4) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    // Dynamic edge origin: sample from the video border closest to this fragment.
    // Inset slightly (8%) towards center so we sample actual video content rather than boundary artifacts.
    highp vec2 sample_origin = mix(edge_uv, vec2(0.5), 0.08);

    // Aspect ratio correction keeps the sampling footprint circular on screen
    highp float aspect_x = (HOOKED_size.y / HOOKED_size.x) * ASPECT_SCALE_RATIO;

    // Accumulate samples across the precomputed golden spiral
    mediump vec3 avg_color = vec3(0.0);
    for (int i = 0; i < $samples; i++) {
        highp vec2 tap_offset = vec2(YT2_TAPS[i].x * aspect_x, YT2_TAPS[i].y);
        highp vec2 sample_pos = clamp(sample_origin + tap_offset, safe_min, safe_max);
        avg_color += HOOKED_tex(sample_pos).rgb;
    }
    avg_color *= $glslInvSamples;

    // Boost saturation slightly for vibrant glow (YouTube ambient style)
    mediump float luma = dot(avg_color, vec3(0.2126, 0.7152, 0.0722));
    avg_color = mix(vec3(luma), avg_color, $glslSatBoost);

    // Scale brightness
    avg_color *= $glslIntensity;

    // Smooth exponential fade based on distance from video edge
    highp float fade = exp(-dist * $glslFadeCurve);
    avg_color *= fade;

    // Debanding: subtle screen-space dither noise to eliminate color banding
    highp float dither = (ign(uv * HOOKED_size) - 0.5) * 0.004;
    avg_color = clamp(avg_color + dither, 0.0, 1.0);

    return vec4(avg_color, 1.0);
}
""".trimIndent()
    }
  }

  // ==================== State Management ====================

  private val _isAmbientEnabled = MutableStateFlow(playerPreferences.isAmbientEnabled.get())
  val isAmbientEnabled: StateFlow<Boolean> = _isAmbientEnabled.asStateFlow()

  private val _isAmbientLoading = MutableStateFlow(false)
  val isAmbientLoading: StateFlow<Boolean> = _isAmbientLoading.asStateFlow()

  private var lastAmbientScaleX = -1.0
  private var lastAmbientScaleY = -1.0
  private var ambientDebounceJob: Job? = null
  private var ambientShaderSeq = 0
  private var ambientShaderFile: File? = null

  // ==================== Public API ====================

  fun toggleAmbientMode() {
    _isAmbientEnabled.value = !_isAmbientEnabled.value

    // Save the Ambient Mode ON/OFF state permanently to preferences
    playerPreferences.isAmbientEnabled.set(_isAmbientEnabled.value)
    if (_isAmbientEnabled.value) {
      lastAmbientScaleX = -1.0 // Force rewrite
      updateAmbientStretch()
      onShowText("Ambience Mode: ON")
    } else {
      disableAmbientShader()
      onShowText("Ambience Mode: OFF")
    }
  }

  /** Called when the device orientation changes. Refreshes ambient shader for new dimensions. */
  fun onOrientationChanged(isPortrait: Boolean) {
    if (_isAmbientEnabled.value) {
      // Force shader refresh to adapt to new screen dimensions
      lastAmbientScaleX = -1.0
      lastAmbientScaleY = -1.0
      // Small delay to let the new OSD dimensions settle
      ambientDebounceJob?.cancel()
      ambientDebounceJob = scope.launch {
        delay(200)
        updateAmbientStretch()
      }
    }
  }

  /** Resets ambient mode to OFF when a new video file is loaded. */
  fun resetAmbientMode() {
    if (!_isAmbientEnabled.value) return
    
    // Ambient Mode Persistent Fix for Next/Previous files
    // DO NOT set _isAmbientEnabled.value = false
    // Just temporarily remove the old shader and reset the scale 
    // so the new video starts with a clean slate before recalculating.
    disableAmbientShader()
    lastAmbientScaleX = -1.0
    lastAmbientScaleY = -1.0
  }

  /**
   * Re-injects the ambient shader if ambient mode is currently ON.
   * Called after Anime4K shader changes, since setPropertyString("glsl-shaders", ...)
   * wipes ALL glsl-shaders including the ambient one.
   */
  fun restartAmbientIfActive() {
    if (!_isAmbientEnabled.value) return
    // The old ambient shader file was wiped by the glsl-shaders reset.
    // Clean up our local reference without trying to remove from MPV.
    ambientShaderFile?.delete()
    ambientShaderFile = null
    lastAmbientScaleX = -1.0  // Force rewrite
    // Small delay to let Anime4K shaders settle
    ambientDebounceJob?.cancel()
    ambientDebounceJob = scope.launch {
      delay(200)
      updateAmbientStretch()
    }
  }

  fun updateAmbientStretch() {
    if (!_isAmbientEnabled.value) return

    runCatching {
      val osdW = MPVLib.getPropertyInt("osd-width") ?: 1920
      val osdH = MPVLib.getPropertyInt("osd-height") ?: 1080

      var vidW = (MPVLib.getPropertyInt("video-params/w") ?: 1920).toDouble()
      var vidH = (MPVLib.getPropertyInt("video-params/h") ?: 1080).toDouble()
      val par  = MPVLib.getPropertyDouble("video-params/par") ?: 1.0
      val rot  = MPVLib.getPropertyInt("video-params/rotate") ?: 0

      val crop = MPVLib.getPropertyString("video-crop") ?: ""
      val cropMatch = Regex("""^(\d+)x(\d+)""").find(crop)
      if (cropMatch != null) {
        vidW = cropMatch.groupValues[1].toDouble()
        vidH = cropMatch.groupValues[2].toDouble()
      }

      if (osdW <= 0 || osdH <= 0 || vidW <= 0.0 || vidH <= 0.0) return

      vidW *= par
      if (rot == 90 || rot == 270) { val tmp = vidW; vidW = vidH; vidH = tmp }

      val screenAr = osdW.toDouble() / osdH.toDouble()
      val vidAr    = vidW / vidH
      
      val scaleX = if (screenAr > vidAr) screenAr / vidAr else 1.0
      val scaleY = if (vidAr > screenAr) vidAr / screenAr else 1.0

      if (abs(scaleX - lastAmbientScaleX) > 0.001 ||
          abs(scaleY - lastAmbientScaleY) > 0.001) {
        lastAmbientScaleX = scaleX
        lastAmbientScaleY = scaleY
        MPVLib.setPropertyDouble("video-scale-x", scaleX)
        MPVLib.setPropertyDouble("video-scale-y", scaleY)
      }

      val shaderCode = buildAmbientShader(
        sx = lastAmbientScaleX,
        sy = lastAmbientScaleY
      )

      val newFile = File(cacheDir, "ambient_${++ambientShaderSeq}.glsl")
      newFile.writeText(shaderCode)
      ambientShaderFile?.let { oldFile ->
        runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", oldFile.absolutePath) }
        oldFile.delete()
      }
      MPVLib.command("change-list", "glsl-shaders", "append", newFile.absolutePath)
      ambientShaderFile = newFile
    }.onFailure { e ->
      Log.e(TAG, "Failed to update ambient stretch", e)
    }
  }

  fun disableAmbientShader() {
    ambientDebounceJob?.cancel()
    ambientShaderFile?.let { file ->
      runCatching { MPVLib.command("change-list", "glsl-shaders", "remove", file.absolutePath) }
      file.delete()
    }
    ambientShaderFile = null
    runCatching {
      MPVLib.setPropertyDouble("video-scale-x", 1.0)
      MPVLib.setPropertyDouble("video-scale-y", 1.0)
    }
  }

  fun cleanup() {
    ambientDebounceJob?.cancel()
    ambientShaderFile?.delete()
    ambientShaderFile = null
  }
}
