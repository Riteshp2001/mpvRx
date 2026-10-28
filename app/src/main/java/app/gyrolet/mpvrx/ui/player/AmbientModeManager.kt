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

    private fun glslFloat(value: Double): String {
      val normalized = if (abs(value) < 0.0000005) 0.0 else value
      val formatted =
        String.format(Locale.US, "%.8f", normalized)
          .trimEnd('0')
          .trimEnd('.')
      return if (formatted.contains('.')) formatted else "$formatted.0"
    }

    /**
     * Builds an ultra-clean, high-efficiency YouTube-style ambient GLSL shader.
     *
     * Features:
     * - Spatially-fixed 4-quadrant sampling (16 fetches total): completely eliminates overlapping /
     *   crossing streak lines while providing smooth, continuous dynamic color changing for both sides.
     * - All calculations use highp precision (zero 16-bit mediump quantization artifacts).
     * - Early distance culling (dist >= 2.4 skips all texture fetches).
     * - Screen-space Interleaved Gradient Noise (IGN) debanding.
     * - True YouTube ambient aesthetics: 30% saturation boost, 30% intensity, exponential fade.
     */
    fun buildAmbientShader(
      sx: Double,
      sy: Double,
      cx: Double = 0.5,
      cy: Double = 0.5,
      samples: Int = 16,
      satBoost: Float = 1.3f,
      intensity: Float = 0.30f,
      fadeCurve: Float = 2.5f,
    ): String {
      val glslSx = glslFloat(sx)
      val glslSy = glslFloat(sy)
      val glslCx = glslFloat(cx)
      val glslCy = glslFloat(cy)
      val glslSatBoost = glslFloat(satBoost.toDouble())
      val glslIntensity = glslFloat(intensity.toDouble())
      val glslFadeCurve = glslFloat(fadeCurve.toDouble())

      return """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC YouTube-Style Ambient Mode (Smooth Dynamic Bilinear Field)

#define SCALE_X    $glslSx
#define SCALE_Y    $glslSy
#define CENTER_X   $glslCx
#define CENTER_Y   $glslCy

// Fast screen-space Interleaved Gradient Noise for 8-bit panel debanding
highp float ign(highp vec2 p) {
    highp vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(magic.z * fract(dot(p, magic.xy)));
}

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

    // 16 total texture fetches across the 4 corners/quadrants.
    // Fixed spatial coordinates guarantee ZERO crossing streak lines or tap artifacts.
    highp vec3 col_tl = (HOOKED_tex(vec2(0.12, 0.18)).rgb +
                         HOOKED_tex(vec2(0.25, 0.15)).rgb +
                         HOOKED_tex(vec2(0.15, 0.30)).rgb +
                         HOOKED_tex(vec2(0.30, 0.30)).rgb) * 0.25;

    highp vec3 col_tr = (HOOKED_tex(vec2(0.88, 0.18)).rgb +
                         HOOKED_tex(vec2(0.75, 0.15)).rgb +
                         HOOKED_tex(vec2(0.85, 0.30)).rgb +
                         HOOKED_tex(vec2(0.70, 0.30)).rgb) * 0.25;

    highp vec3 col_bl = (HOOKED_tex(vec2(0.12, 0.82)).rgb +
                         HOOKED_tex(vec2(0.25, 0.85)).rgb +
                         HOOKED_tex(vec2(0.15, 0.70)).rgb +
                         HOOKED_tex(vec2(0.30, 0.70)).rgb) * 0.25;

    highp vec3 col_br = (HOOKED_tex(vec2(0.88, 0.82)).rgb +
                         HOOKED_tex(vec2(0.75, 0.85)).rgb +
                         HOOKED_tex(vec2(0.85, 0.70)).rgb +
                         HOOKED_tex(vec2(0.70, 0.70)).rgb) * 0.25;

    // Smoothstep bilinear interpolation: both sides have separate dynamic colors with silky-smooth gradient
    highp vec2 s = smoothstep(0.0, 1.0, edge_uv);
    highp vec3 col_top = mix(col_tl, col_tr, s.x);
    highp vec3 col_bot = mix(col_bl, col_br, s.x);
    highp vec3 avg_color = mix(col_top, col_bot, s.y);

    // Boost saturation slightly for vibrant glow (YouTube ambient style)
    highp float luma = dot(avg_color, vec3(0.2126, 0.7152, 0.0722));
    avg_color = mix(vec3(luma), avg_color, $glslSatBoost);

    // Scale brightness
    avg_color *= $glslIntensity;

    // Smooth exponential fade based on distance from video edge
    highp float fade = exp(-dist * $glslFadeCurve);
    avg_color *= fade;

    // Debanding: high-precision screen-space dither noise to eliminate color banding
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
