/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.ui.player

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Specification parameters for an FX ambient shader pass (Ambilight, Cinema, Echo, Mirror).
 */
data class FxAmbientShaderSpec(
  val style: AmbientStyle,
  val scaleX: Double,
  val scaleY: Double,
  val blurSamples: Int,
  val spread: Float,
  val intensity: Float,
  val satBoost: Float,
  val vignetteStrength: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
  val centerX: Double = 0.5,
  val centerY: Double = 0.5,
)

data class FxAmbientPreset(
  val blurSamples: Int,
  val spread: Float,
  val intensity: Float,
  val satBoost: Float,
  val vignetteStrength: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
)

object FxAmbientPresets {
  // Ambilight (authentic fx_ambient Glow)
  val ambilightFast = FxAmbientPreset(8, 0.10f, 1.0f, 1.0f, 0.0f, 0.0f, 2.4f, 1.0f)
  val ambilightBalanced = FxAmbientPreset(14, 0.12f, 1.0f, 1.0f, 0.0f, 0.0f, 2.4f, 1.0f)
  val ambilightHighQuality = FxAmbientPreset(20, 0.15f, 1.1f, 1.05f, 0.0f, 0.0f, 2.4f, 1.0f)

  // Cinema (wide-radius diffuse backdrop)
  val cinemaFast = FxAmbientPreset(8, 0.14f, 0.9f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaBalanced = FxAmbientPreset(14, 0.18f, 1.0f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaHighQuality = FxAmbientPreset(22, 0.22f, 1.1f, 1.05f, 0.0f, 0.0f, 1.15f, 1.0f)

  // Echo (2D anisotropic wave smear)
  val echoFast = FxAmbientPreset(5, 0.10f, 0.9f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoBalanced = FxAmbientPreset(9, 0.14f, 1.0f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoHighQuality = FxAmbientPreset(15, 0.18f, 1.1f, 1.05f, 0.0f, 0.0f, 1.8f, 1.0f)

  // Mirror
  val mirrorFast = FxAmbientPreset(3, 0.05f, 0.9f, 1.0f, 0.0f, 0.0f, 2.0f, 1.0f)
  val mirrorBalanced = FxAmbientPreset(3, 0.06f, 1.0f, 1.0f, 0.0f, 0.0f, 2.0f, 1.0f)
  val mirrorHighQuality = FxAmbientPreset(5, 0.08f, 1.1f, 1.05f, 0.0f, 0.0f, 2.0f, 1.0f)
}

fun matchesFxPreset(
  preset: FxAmbientPreset,
  blurSamples: Int,
  spread: Float,
  intensity: Float,
  satBoost: Float,
  vignetteStrength: Float,
  warmth: Float,
  fadeCurve: Float,
  opacity: Float,
): Boolean =
  blurSamples == preset.blurSamples &&
    abs(spread - preset.spread) <= 0.01f &&
    abs(intensity - preset.intensity) <= 0.01f &&
    abs(satBoost - preset.satBoost) <= 0.01f &&
    abs(vignetteStrength - preset.vignetteStrength) <= 0.01f &&
    abs(warmth - preset.warmth) <= 0.01f &&
    abs(fadeCurve - preset.fadeCurve) <= 0.01f &&
    abs(opacity - preset.opacity) <= 0.01f

/**
 * Builds mpv OUTPUT hook GLSL shaders for fx-ambient modes:
 * Ambilight, Cinema, Echo, and Mirror with bicubic B-spline filtering and adjustable parameters.
 */
object FxAmbientShaderBuilder {
  // -- Private helpers ---------------------------------------------------------

  private fun f(value: Double): String {
    val normalised = if (abs(value) < 0.0000005) 0.0 else value
    val s = String.format(Locale.US, "%.8f", normalised).trimEnd('0').trimEnd('.')
    return if ('.' in s) s else "$s.0"
  }

  /**
   * Builds the standard mpv hook header, GLSL helpers, and the bicubic B-spline sampler.
   */
  private fun buildHeader(
    desc: String,
    scaleX: Double,
    scaleY: Double,
    centerX: Double,
    centerY: Double,
  ): String =
    """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC $desc

#ifdef GL_ES
precision highp float;
precision highp int;
#else
#define mediump
#define highp
#define lowp
#endif

// Bar geometry baked from Kotlin so the GPU never recomputes it.
#define SCALE_X ${f(scaleX)}
#define SCALE_Y ${f(scaleY)}
#define CENTER_X ${f(centerX)}
#define CENTER_Y ${f(centerY)}

// -- Common GLSL helpers from fx_common.glsl ----------------------------------

// Jorge Jimenez Interleaved Gradient Noise -- cache-coherent, zero trig.
highp float ign(highp vec2 p) {
    highp vec3 m = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(m.z * fract(dot(p, m.xy)));
}

highp float luma(highp vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Selective chroma boost: lifts saturation without blowing highlights.
highp vec3 vibrance(highp vec3 c, highp float k) {
    return max(vec3(luma(c)) + (c - vec3(luma(c))) * k, vec3(0.0));
}

// Multiplicative color-temperature adjustment.
highp vec3 apply_warmth(highp vec3 rgb, highp float amount) {
    highp vec3 gain = vec3(1.0 + amount * 0.12, 1.0 + amount * 0.05, 1.0 - amount * 0.15);
    return clamp(rgb * gain, 0.0, 1.0);
}

// Highlight roll-off from fx_common.glsl: c / (1 + k*luma).
highp vec3 rolloff(highp vec3 c, highp float k) {
    return c * (1.0 / (1.0 + k * luma(c)));
}

// Screen-space dither to dissolve 8-bit quantisation banding.
highp vec3 dither_out(highp vec3 c, highp vec2 p) {
    highp float amp = clamp(max(c.r, max(c.g, c.b)) * 255.0, 0.0, 1.0);
    return c + (ign(p) - 0.5) * (amp / 255.0);
}

// Cubic B-spline filter using 4 bilinear taps (GPU Gems 2, ch. 20).
// C2-smooth filter that eliminates stair-stepping, grid creases, and noisy stippling.
mediump vec3 bspline_tex(highp vec2 sample_uv, highp vec2 size, highp vec2 inv_size, highp vec2 min_bound, highp vec2 max_bound) {
    highp vec2 st = sample_uv * size - 0.5;
    highp vec2 i = floor(st);
    highp vec2 f = st - i;
    highp vec2 f2 = f * f;
    highp vec2 f3 = f2 * f;
    highp vec2 w0 = (-f3 + 3.0 * f2 - 3.0 * f + 1.0) * (1.0 / 6.0);
    highp vec2 w1 = (3.0 * f3 - 6.0 * f2 + 4.0) * (1.0 / 6.0);
    highp vec2 w2 = (-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0) * (1.0 / 6.0);
    highp vec2 w3 = f3 * (1.0 / 6.0);
    highp vec2 g0 = w0 + w1;
    highp vec2 g1 = w2 + w3;
    highp vec2 p0 = clamp((i + w1 / g0 - 0.5) * inv_size, min_bound, max_bound);
    highp vec2 p1 = clamp((i + w3 / g1 + 1.5) * inv_size, min_bound, max_bound);
    mediump vec3 c00 = HOOKED_tex(vec2(p0.x, p0.y)).rgb;
    mediump vec3 c10 = HOOKED_tex(vec2(p1.x, p0.y)).rgb;
    mediump vec3 c01 = HOOKED_tex(vec2(p0.x, p1.y)).rgb;
    mediump vec3 c11 = HOOKED_tex(vec2(p1.x, p1.y)).rgb;
    // Assignment applies the destination precision; precision-qualified constructors
    // are invalid GLSL ES and break every FX mode, including video pass-through.
    mediump vec2 g0_m = g0;
    mediump vec2 g1_m = g1;
    return (c00 * g0_m.x + c10 * g1_m.x) * g0_m.y + (c01 * g0_m.x + c11 * g1_m.x) * g1_m.y;
}

    """.trimIndent()

  // -- Shared video prologue ---------------------------------------------------

  /**
   * Shared in-shader prologue injected into each mode's hook():
   *   - Remaps screen UV to video UV.
   *   - Computes safe clamping bounds (half-texel inset).
   *   - Early-returns the original pixel when inside the video.
   *   - Computes per-bar geometry: bx, by, dx, dy, d, thick, dn, in_side_bar.
   */
  private fun videoPrologue(reachScale: Double): String =
    """
    highp vec2 uv       = HOOKED_pos;
    highp vec2 video_uv = (uv - vec2(CENTER_X, CENTER_Y)) * vec2(SCALE_X, SCALE_Y) + 0.5;

    // Precalculated resolution vectors to eliminate per-tap runtime 2D divisions
    highp vec2 video_res     = HOOKED_size / vec2(SCALE_X, SCALE_Y);
    highp vec2 inv_video_res = vec2(SCALE_X, SCALE_Y) / HOOKED_size;

    // Half-texel inset to avoid fetching outside decoded frame boundaries.
    highp vec2 half_texel = vec2(0.5) / HOOKED_size;
    highp vec2 safe_min   = half_texel;
    highp vec2 safe_max   = vec2(1.0) - half_texel;

    // -- Inside-video pass-through -------------------------------------------
    bool inside_video = video_uv.x >= 0.0 && video_uv.x <= 1.0 &&
                        video_uv.y >= 0.0 && video_uv.y <= 1.0;
    if (inside_video) {
        return HOOKED_tex(clamp(video_uv, safe_min, safe_max));
    }

    // -- Bar geometry --------------------------------------------------------
    // Thickness of the bar being shaded, including asymmetric bars introduced by pan.
    highp float bx = max((video_uv.x < 0.0 ? CENTER_X : 1.0 - CENTER_X) * SCALE_X - 0.5, 0.001);
    highp float by = max((video_uv.y < 0.0 ? CENTER_Y : 1.0 - CENTER_Y) * SCALE_Y - 0.5, 0.001);

    // Penetration depth into each bar axis (>0 only when outside that axis).
    highp float dx = max(max(-video_uv.x, video_uv.x - 1.0), 0.0);
    highp float dy = max(max(-video_uv.y, video_uv.y - 1.0), 0.0);

    // Euclidean distance into the bar region.
    highp float d = length(vec2(dx, dy));

    // Continuous dominant bar thickness: eliminates diagonal corner cutoffs and blackouts.
    highp float thick = max(max(bx, by), 0.05);

    // Normalised depth: 0 at the video edge, 1 at the nominal reach boundary.
    highp float dn = clamp(d / (thick * ${f(reachScale)}), 0.0, 1.0);
    if (dn >= 1.0) { return vec4(0.0, 0.0, 0.0, 1.0); }

    bool in_side_bar = dx >= dy;
    """.trimIndent().prependIndent("    ")

  /**
   * Spiral tap offsets for Poisson disk sampling.
   */
  private fun tapTable(
    name: String,
    count: Int,
    maxR: Double,
  ): String {
    val goldenAngle = 2.399963229728653
    val taps =
      (0 until count).joinToString(",\n") { i ->
        val r = sqrt((i.toDouble() + 0.5) / count) * maxR
        val theta = (i.toDouble() + 0.5) * goldenAngle
        "    vec2(${f(cos(theta) * r)}, ${f(sin(theta) * r)})"
      }
    return "const vec2 $name[$count] = vec2[$count](\n$taps\n);"
  }

  // -- Ambilight (fx_ambient Glow) --------------------------------------------

  /**
   * Builds the authentic fx_ambient Ambilight mode: each edge lights the dark beside it
   * with physical dual-phase emitter curve, B-spline diffusion, vibrance, and highlight roll-off.
   */
  fun buildAmbilight(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.12).coerceIn(0.2, 4.0)
    val reachScale = 1.25 * spreadScale
    val tapCount = spec.blurSamples.coerceIn(4, 32)
    val maxRadius = (0.12 * spreadScale).coerceIn(0.02, 0.50)
    val tapsDecl = tapTable("AMBILIGHT_TAPS", tapCount, maxRadius)

    val vibranceVal = 1.18 * spec.satBoost.toDouble().coerceIn(0.0, 3.0)
    val intensityVal = 1.0 * spec.intensity.toDouble().coerceIn(0.1, 4.0)
    val fadeCurveVal = spec.fadeCurve.toDouble().coerceIn(0.2, 5.0)
    val warmthVal = spec.warmth.toDouble().coerceIn(-1.0, 1.0)
    val vignetteVal = spec.vignetteStrength.toDouble().coerceIn(0.0, 1.0)
    val opacityVal = spec.opacity.toDouble().coerceIn(0.0, 1.0)

    val warmthLine = if (abs(warmthVal) > 0.0001) "    col = apply_warmth(col, ${f(warmthVal)});\n" else ""
    val vignetteLine =
      if (vignetteVal > 0.0001) {
        """
    highp float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, 1.0 - smoothstep(0.1, 1.3, vig_r), ${f(vignetteVal)});
"""
      } else {
        ""
      }
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    return """
${buildHeader("Fx Ambient -- Ambilight", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$tapsDecl

vec4 hook() {
${videoPrologue(reachScale)}

    // Fixed edge anchor: samples edge light without crawling deep into the frame
    highp vec2 edge_origin = clamp(video_uv, safe_min, safe_max);
    highp vec2 sample_origin = mix(edge_origin, vec2(0.5), 0.015);

    highp float jitter = ign(uv * HOOKED_size) * 6.2831853;
    highp float jitter_s = sin(jitter);
    highp float jitter_c = cos(jitter);
    highp float aspect_x = (HOOKED_size.y / HOOKED_size.x) * ${f(spec.scaleX / spec.scaleY)};
    highp float jitter_c_aspect = jitter_c * aspect_x;
    highp float jitter_s_aspect = jitter_s * aspect_x;

    mediump vec3 acc = vec3(0.0);
    mediump float twt = 0.0;

    for (int i = 0; i < $tapCount; i++) {
        vec2 tap = AMBILIGHT_TAPS[i];
        highp vec2 tap_rot = vec2(
            tap.x * jitter_c_aspect - tap.y * jitter_s_aspect,
            tap.x * jitter_s + tap.y * jitter_c
        );
        highp vec2 tap_pos = clamp(sample_origin + tap_rot, safe_min, safe_max);
        mediump vec3 s = bspline_tex(tap_pos, video_res, inv_video_res, safe_min, safe_max);
        mediump float w = 1.0 + luma(s) * 1.5;
        acc += s * w;
        twt += w;
    }
    mediump vec3 col = acc / max(twt, 0.001);

    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.55);

    // fx_ambient dual-phase emitter curve: luminous core relaxing into a smooth tail
    highp float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) * (0.6 + 0.4 * exp(-5.0 * dn)) * ${f(intensityVal)};
    highp vec3 ambient_rgb = col * fade;
$vignetteLine$opacityLine
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
      """.trimIndent()
  }

  fun buildAmbilight(
    context: Context,
    scaleX: Double,
    scaleY: Double,
  ): String =
    buildAmbilight(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Ambilight,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 14,
        spread = 0.12f,
        intensity = 1.0f,
        satBoost = 1.0f,
        vignetteStrength = 0.0f,
        warmth = 0.0f,
        fadeCurve = 2.4f,
        opacity = 1.0f,
      ),
    )

  // -- Cinema -----------------------------------------------------------------

  /**
   * Builds the authentic fx_ambient Cinema mode: projects the entire picture enlarged
   * and dissolved into a wide-radius soft wash behind the frame with B-spline diffusion.
   */
  fun buildCinema(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.18).coerceIn(0.2, 4.0)
    val reachScale = 1.45 * spreadScale
    val farRadius = (0.36 * spreadScale).coerceIn(0.12, 0.65)
    val baseRadius = (0.16 * spreadScale).coerceIn(0.06, 0.40)
    val tapCount = spec.blurSamples.coerceIn(4, 32)
    val tapTable = tapTable("CINEMA_TAPS", tapCount, farRadius)

    val vibranceVal = 1.08 * spec.satBoost.toDouble().coerceIn(0.0, 3.0)
    val intensityVal = 0.88 * spec.intensity.toDouble().coerceIn(0.1, 4.0)
    val fadeCurveVal = spec.fadeCurve.toDouble().coerceIn(0.2, 4.0)
    val warmthVal = spec.warmth.toDouble().coerceIn(-1.0, 1.0)
    val vignetteVal = spec.vignetteStrength.toDouble().coerceIn(0.0, 1.0)
    val opacityVal = spec.opacity.toDouble().coerceIn(0.0, 1.0)

    val warmthLine = if (abs(warmthVal) > 0.0001) "    col = apply_warmth(col, ${f(warmthVal)});\n" else ""
    val vignetteLine =
      if (vignetteVal > 0.0001) {
        """
    highp float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, 1.0 - smoothstep(0.1, 1.3, vig_r), ${f(vignetteVal)});
"""
      } else {
        ""
      }
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    return """
${buildHeader("Fx Ambient -- Cinema", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$tapTable

vec4 hook() {
${videoPrologue(reachScale)}

    // -- Authentic fx_ambient Cinema whole-picture scaled backdrop ------------
    // Projects the whole picture enlarged and dissolved behind the frame with wide-radius B-spline diffusion.
    highp float cinema_scale = 1.25 + 0.35 * clamp(float(${f(spreadScale)}) * 0.5, 0.1, 2.0);
    highp vec2 cinema_uv = clamp((video_uv - vec2(0.5)) / cinema_scale + vec2(0.5), safe_min, safe_max);

    highp float jitter   = ign(uv * HOOKED_size) * 6.2831853;
    highp float jitter_s = sin(jitter);
    highp float jitter_c = cos(jitter);
    highp float aspect_x = (HOOKED_size.y / HOOKED_size.x) * ${f(spec.scaleX / spec.scaleY)};
    highp float jitter_c_aspect = jitter_c * aspect_x;
    highp float jitter_s_aspect = jitter_s * aspect_x;

    highp float br_scale = mix(${f(baseRadius / farRadius)}, 1.0, smoothstep(0.0, 1.0, dn));

    mediump vec3 acc = vec3(0.0);
    mediump float twt = 0.0;

    for (int i = 0; i < $tapCount; i++) {
        vec2 tap = CINEMA_TAPS[i] * br_scale;
        highp vec2 tap_rot = vec2(
            tap.x * jitter_c_aspect - tap.y * jitter_s_aspect,
            tap.x * jitter_s + tap.y * jitter_c
        );
        highp vec2 tap_pos = clamp(cinema_uv + tap_rot, safe_min, safe_max);
        mediump vec3 s = bspline_tex(tap_pos, video_res, inv_video_res, safe_min, safe_max);
        mediump float w = 1.0 + luma(s) * 1.5;
        acc += s * w;
        twt += w;
    }
    mediump vec3 col = acc / max(twt, 0.001);

    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.55);

    // fx_ambient Cinema fade: smoothstep falloff into the bar depth
    highp float fade = pow(max(1.0 - smoothstep(0.0, 1.0, dn), 0.0), ${f(fadeCurveVal)}) * ${f(intensityVal)};
    highp vec3 ambient_rgb = col * fade;
$vignetteLine$opacityLine
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
      """.trimIndent()
  }

  fun buildCinema(
    context: Context,
    scaleX: Double,
    scaleY: Double,
  ): String =
    buildCinema(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Cinema,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 14,
        spread = 0.18f,
        intensity = 1.0f,
        satBoost = 1.0f,
        vignetteStrength = 0.0f,
        warmth = 0.0f,
        fadeCurve = 1.15f,
        opacity = 1.0f,
      ),
    )

  // -- Echo -------------------------------------------------------------------

  /**
   * Builds the Echo ambient shader: 2D anisotropic wave with tangential smear and transverse diffusion.
   */
  fun buildEcho(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.14).coerceIn(0.2, 4.0)
    val reachScale = 1.35 * spreadScale
    val swBase = 0.05 * spreadScale
    val swFar = 0.22 * spreadScale
    val cwBase = 0.02 * spreadScale
    val cwFar = 0.09 * spreadScale

    val tapCount = spec.blurSamples.coerceIn(3, 15).let { if (it % 2 == 0) it + 1 else it }
    val halfCount = tapCount / 2
    val sigma = (halfCount * 0.75).coerceAtLeast(0.5)
    val rawWeights =
      (-halfCount..halfCount).map { i ->
        kotlin.math.exp(-0.5 * (i.toDouble() / sigma).let { it * it })
      }
    val sumWeights = rawWeights.sum()
    val weights = rawWeights.map { it / sumWeights }

    val vibranceVal = 1.15 * spec.satBoost.toDouble().coerceIn(0.0, 3.0)
    val intensityVal = 0.85 * spec.intensity.toDouble().coerceIn(0.1, 4.0)
    val fadeCurveVal = spec.fadeCurve.toDouble().coerceIn(0.2, 4.0)
    val warmthVal = spec.warmth.toDouble().coerceIn(-1.0, 1.0)
    val vignetteVal = spec.vignetteStrength.toDouble().coerceIn(0.0, 1.0)
    val opacityVal = spec.opacity.toDouble().coerceIn(0.0, 1.0)

    val warmthLine = if (abs(warmthVal) > 0.0001) "    col = apply_warmth(col, ${f(warmthVal)});\n" else ""
    val vignetteLine =
      if (vignetteVal > 0.0001) {
        """
    highp float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, 1.0 - smoothstep(0.1, 1.3, vig_r), ${f(vignetteVal)});
"""
      } else {
        ""
      }
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    val weightsDecl = "const float ECHO_WEIGHTS[$tapCount] = float[$tapCount](${weights.joinToString(", ") { f(it) }});"
    val offsetsDecl = "const float ECHO_OFFSETS[$tapCount] = float[$tapCount](${(-halfCount..halfCount).joinToString(
      ", ",
    ) {
      f(
        it.toDouble() / halfCount,
      )
    }});"
    val crossFactors =
      (0 until tapCount).map { i ->
        when (i % 4) {
          1 -> 1.0
          3 -> -1.0
          else -> 0.0
        }
      }
    val crossDecl = "const float ECHO_CROSS[$tapCount] = float[$tapCount](${crossFactors.joinToString(
      ", ",
    ) { f(it) }});"

    return """
${buildHeader("Fx Ambient -- Echo", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$weightsDecl
$offsetsDecl
$crossDecl

vec4 hook() {
${videoPrologue(reachScale)}

    highp vec2 edge_origin = clamp(video_uv, safe_min, safe_max);
    highp vec2 echo_origin = mix(edge_origin, vec2(0.5), 0.015);

    // Tangential direction along the edge boundary
    highp vec2 smear_dir = in_side_bar ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
    // Transverse direction (radial into the bar/picture)
    highp vec2 cross_dir = in_side_bar ? vec2(1.0, 0.0) : vec2(0.0, 1.0);

    // Aspect normalization for physical screen space
    highp float aspect_ratio = (HOOKED_size.x / HOOKED_size.y) * ${f(spec.scaleY / spec.scaleX)};
    highp float sw = (${f(swBase)} + ${f(swFar)} * dn) * (in_side_bar ? aspect_ratio : 1.0);
    highp float cw = (${f(cwBase)} + ${f(cwFar)} * dn);

    highp float jitter = (ign(uv * HOOKED_size) - 0.5);
    highp vec2 jitter_off = smear_dir * (jitter * sw * 0.35) + cross_dir * (jitter * cw * 0.35);

    mediump vec3 col = vec3(0.0);
    for (int i = 0; i < $tapCount; i++) {
        float step_factor = ECHO_OFFSETS[i];
        // 2D anisotropic wave: tangential smear combined with precomputed transverse diffusion phase
        float cross_factor = ECHO_CROSS[i] * cw;
        highp vec2 tap_pos = clamp(
            echo_origin + smear_dir * (step_factor * sw) + cross_dir * cross_factor + jitter_off,
            safe_min, safe_max
        );
        col += bspline_tex(tap_pos, video_res, inv_video_res, safe_min, safe_max) * ECHO_WEIGHTS[i];
    }

    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.55);

    highp float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) * ${f(intensityVal)};
    highp vec3 ambient_rgb = col * fade;
$vignetteLine$opacityLine
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
      """.trimIndent()
  }

  fun buildEcho(
    context: Context,
    scaleX: Double,
    scaleY: Double,
  ): String =
    buildEcho(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Echo,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 9,
        spread = 0.14f,
        intensity = 1.0f,
        satBoost = 1.0f,
        vignetteStrength = 0.0f,
        warmth = 0.0f,
        fadeCurve = 1.8f,
        opacity = 1.0f,
      ),
    )

  // -- Mirror -----------------------------------------------------------------

  /**
   * Builds the Mirror ambient shader: folded UV reflection with B-spline blur.
   */
  fun buildMirror(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.06).coerceIn(0.2, 4.0)
    val reachScale = 1.15 * spreadScale
    val bwFar = (0.06 * spreadScale).coerceIn(0.01, 0.30)

    val tapCount = spec.blurSamples.coerceIn(3, 9).let { if (it % 2 == 0) it + 1 else it }
    val halfCount = tapCount / 2
    val sigma = (halfCount * 0.75).coerceAtLeast(0.5)
    val rawWeights =
      (-halfCount..halfCount).map { i ->
        kotlin.math.exp(-0.5 * (i.toDouble() / sigma).let { it * it })
      }
    val sumWeights = rawWeights.sum()
    val weights = rawWeights.map { it / sumWeights }

    val vibranceVal = 0.92 * spec.satBoost.toDouble().coerceIn(0.0, 3.0)
    val intensityVal = 0.55 * spec.intensity.toDouble().coerceIn(0.1, 4.0)
    val fadeCurveVal = spec.fadeCurve.toDouble().coerceIn(0.2, 4.0)
    val warmthVal = spec.warmth.toDouble().coerceIn(-1.0, 1.0)
    val vignetteVal = spec.vignetteStrength.toDouble().coerceIn(0.0, 1.0)
    val opacityVal = spec.opacity.toDouble().coerceIn(0.0, 1.0)

    val warmthLine = if (abs(warmthVal) > 0.0001) "    col = apply_warmth(col, ${f(warmthVal)});\n" else ""
    val vignetteLine =
      if (vignetteVal > 0.0001) {
        """
    highp float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, 1.0 - smoothstep(0.1, 1.3, vig_r), ${f(vignetteVal)});
"""
      } else {
        ""
      }
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    val weightsDecl = "const float MIRROR_WEIGHTS[$tapCount] = float[$tapCount](${weights.joinToString(
      ", ",
    ) { f(it) }});"
    val offsetsDecl = "const float MIRROR_OFFSETS[$tapCount] = float[$tapCount](${(-halfCount..halfCount).joinToString(
      ", ",
    ) {
      f(
        it.toDouble() / halfCount,
      )
    }});"

    return """
${buildHeader("Fx Ambient -- Mirror", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$weightsDecl
$offsetsDecl

vec4 hook() {
${videoPrologue(reachScale)}

    highp vec2 m_uv = video_uv;
    m_uv = mix(m_uv, -m_uv,             step(m_uv, vec2(0.0)));
    m_uv = mix(m_uv, vec2(2.0) - m_uv, step(vec2(1.0), m_uv));
    m_uv = clamp(m_uv, safe_min, safe_max);

    highp vec2 blur_dir = in_side_bar ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    highp float bw = mix(0.0, ${f(bwFar)}, pow(dn, 0.7));

    mediump vec3 col = vec3(0.0);
    for (int i = 0; i < $tapCount; i++) {
        float step_factor = MIRROR_OFFSETS[i];
        highp vec2 tap_pos = clamp(m_uv + blur_dir * (step_factor * bw), safe_min, safe_max);
        col += bspline_tex(tap_pos, video_res, inv_video_res, safe_min, safe_max) * MIRROR_WEIGHTS[i];
    }

    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.30);

    highp float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) *
                         (0.55 + 0.45 * exp(-6.0 * dn));

    // Ceiling (top bar) reflects less light than floor -- dim by 30%.
    if (video_uv.y < 0.0) { fade *= 0.7; }

    highp vec3 ambient_rgb = col * (fade * ${f(intensityVal)});
$vignetteLine$opacityLine
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
      """.trimIndent()
  }

  fun buildMirror(
    context: Context,
    scaleX: Double,
    scaleY: Double,
  ): String =
    buildMirror(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Mirror,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 3,
        spread = 0.06f,
        intensity = 1.0f,
        satBoost = 1.0f,
        vignetteStrength = 0.0f,
        warmth = 0.0f,
        fadeCurve = 2.0f,
        opacity = 1.0f,
      ),
    )
}
