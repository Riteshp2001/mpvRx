/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Keeps the Video Filters > Sharpness control effective on both mpv GPU renderers.
 *
 * mpv's native `sharpen` option is implemented by `vo=gpu`, but not `vo=gpu-next`. For gpu-next
 * we install a small MAIN-stage hook instead. MAIN keeps sharpness on the video image before the
 * later ambient OUTPUT pass, and the shader intentionally preserves values above 1.0 so Linear HDR
 * highlights are not clipped into SDR range.
 */
object SharpnessShaderManager {
  private const val TAG = "SharpnessShader"
  private const val SHADER_NAME = "mpvrx-sharpen.glsl"

  /** Apply the saved value, slider change, or reset to the active/selected renderer. */
  fun applyRuntime(
    context: Context,
    value: Int,
    videoOutputOverride: String? = null,
  ) {
    val clamped = value.coerceIn(-5, 5)
    val videoOutput = videoOutputOverride ?: PlaybackSession.getPropertyString("vo").orEmpty()
    val shaderFile = shaderFile(context)

    if (videoOutput != "gpu-next") {
      removeShader(shaderFile)
      PlaybackSession.setPropertyInt("sharpen", clamped)
      return
    }

    // gpu-next ignores mpv's native sharpen option. Keep it neutral so renderer changes can never
    // leave both native and shader sharpening active at the same time.
    PlaybackSession.setPropertyInt("sharpen", 0)
    removeShader(shaderFile)
    if (clamped == 0) return

    val updated = writeShader(context, clamped) ?: return
    PlaybackSession.command("change-list", "glsl-shaders", "append", updated.absolutePath)
  }

  private fun removeShader(file: File) {
    runCatching {
      PlaybackSession.command("change-list", "glsl-shaders", "remove", file.absolutePath)
    }
  }

  private fun shaderFile(context: Context): File = File(context.filesDir, "shaders/$SHADER_NAME")

  private fun writeShader(
    context: Context,
    value: Int,
  ): File? =
    runCatching {
      val file = shaderFile(context)
      file.parentFile?.mkdirs()
      file.writeText(buildShader(value.coerceIn(-5, 5)))
      file
    }.onFailure { error ->
      Log.w(TAG, "Failed to prepare gpu-next sharpness shader", error)
    }.getOrNull()

  /**
   * Five-tap unsharp mask with a mild detail limiter. The existing -5..5 UI scale is preserved:
   * +5 is intentionally strong/obvious while negative values blend toward the same local blur.
   */
  internal fun buildShader(value: Int): String {
    val normalized = value.coerceIn(-5, 5) / 5.0
    val sharpenStrength = if (normalized > 0.0) normalized * 1.65 else 0.0
    val blurMix = if (normalized < 0.0) abs(normalized) * 0.82 else 0.0
    val sharpen = String.format(Locale.US, "%.4f", sharpenStrength)
    val blur = String.format(Locale.US, "%.4f", blurMix)

    return """
//!HOOK MAIN
//!BIND HOOKED
//!DESC mpvRx Sharpness

#define MPVRX_SHARPEN $sharpen
#define MPVRX_BLUR_MIX $blur

vec4 hook() {
    vec4 center = HOOKED_tex(HOOKED_pos);
    vec2 px = HOOKED_pt;

    vec3 north = HOOKED_tex(HOOKED_pos + vec2(0.0, -px.y)).rgb;
    vec3 south = HOOKED_tex(HOOKED_pos + vec2(0.0,  px.y)).rgb;
    vec3 west  = HOOKED_tex(HOOKED_pos + vec2(-px.x, 0.0)).rgb;
    vec3 east  = HOOKED_tex(HOOKED_pos + vec2( px.x, 0.0)).rgb;

    vec3 local_blur = (center.rgb * 4.0 + north + south + west + east) * 0.125;
    vec3 detail = center.rgb - local_blur;

    // Suppress excessive ringing at very hard edges without flattening ordinary texture detail.
    float detail_peak = max(max(abs(detail.r), abs(detail.g)), abs(detail.b));
    float limiter = 1.0 - smoothstep(0.28, 0.70, detail_peak);
    vec3 sharpened = center.rgb + detail * MPVRX_SHARPEN * mix(0.72, 1.0, limiter);
    vec3 result = mix(sharpened, local_blur, MPVRX_BLUR_MIX);

    // Do not clamp the upper range: gpu-next/Linear HDR can legitimately carry RGB > 1.0 here.
    return vec4(max(result, vec3(0.0)), center.a);
}
    """.trimIndent()
  }
}
