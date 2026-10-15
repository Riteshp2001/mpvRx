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
 * Specification parameters for an FX ambient shader pass (Cinema, Echo, Mirror).
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
  // Cinema
  val cinemaFast = FxAmbientPreset(6, 0.08f, 0.9f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaBalanced = FxAmbientPreset(8, 0.10f, 1.0f, 1.0f, 0.0f, 0.0f, 1.15f, 1.0f)
  val cinemaHighQuality = FxAmbientPreset(16, 0.12f, 1.1f, 1.05f, 0.0f, 0.0f, 1.15f, 1.0f)

  // Echo
  val echoFast = FxAmbientPreset(3, 0.08f, 0.9f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoBalanced = FxAmbientPreset(5, 0.10f, 1.0f, 1.0f, 0.0f, 0.0f, 1.8f, 1.0f)
  val echoHighQuality = FxAmbientPreset(7, 0.12f, 1.1f, 1.05f, 0.0f, 0.0f, 1.8f, 1.0f)

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
 * Builds mpv OUTPUT hook GLSL shaders for the three fx-ambient modes:
 * Cinema, Echo, and Mirror with adjustable user parameters.
 *
 * Defaults yield the exact hand-tuned baseline look:
 * - Cinema: 8-tap Poisson disk, 0.10 spread, 1.35 reach, 1.15 fade curve, 1.08 vibrance, 0.72 intensity.
 * - Echo: 5-tap Gaussian smear, 0.10 spread, 1.20 reach, 1.8 fade curve, 1.15 vibrance, 0.85 intensity.
 * - Mirror: 3-tap reflective blur, 0.06 spread, 1.15 reach, 2.0 fade curve, 0.92 vibrance, 0.55 intensity.
 */
object FxAmbientShaderBuilder {

  // -- Private helpers ---------------------------------------------------------

  private fun f(value: Double): String {
    val normalised = if (abs(value) < 0.0000005) 0.0 else value
    val s = String.format(Locale.US, "%.8f", normalised).trimEnd('0').trimEnd('.')
    return if ('.' in s) s else "$s.0"
  }

  /**
   * Builds the standard mpv hook header and GLSL helper functions.
   */
  private fun buildHeader(desc: String, scaleX: Double, scaleY: Double, centerX: Double, centerY: Double): String = """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC $desc

#ifdef GL_ES
precision mediump float;
precision highp int;
#else
#define mediump
#define highp
#define lowp
#endif

// Bar geometry baked from Kotlin so the GPU never recomputes it.
// SCALE_X > 1 -> pillarboxed (left/right bars).
// SCALE_Y > 1 -> letterboxed (top/bottom bars).
#define SCALE_X ${f(scaleX)}
#define SCALE_Y ${f(scaleY)}
#define CENTER_X ${f(centerX)}
#define CENTER_Y ${f(centerY)}

// -- Common GLSL helpers (identical subset to AmbientShaderBuilder) -----------

// Jorge Jimenez Interleaved Gradient Noise -- cache-coherent, zero trig.
mediump float ign(highp vec2 p) {
    mediump vec3 m = vec3(0.06711056, 0.00583715, 52.9829189);
    return fract(m.z * fract(dot(p, m.xy)));
}

mediump float luma(mediump vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Selective chroma boost: lifts saturation without blowing highlights.
mediump vec3 vibrance(mediump vec3 c, mediump float k) {
    return max(vec3(luma(c)) + (c - vec3(luma(c))) * k, vec3(0.0));
}

// Multiplicative color-temperature adjustment.
mediump vec3 apply_warmth(mediump vec3 rgb, mediump float amount) {
    mediump vec3 gain = vec3(1.0 + amount * 0.12, 1.0 + amount * 0.05, 1.0 - amount * 0.15);
    return clamp(rgb * gain, 0.0, 1.0);
}

// Highlight roll-off from fx_common.glsl: c / (1 + k*luma).
// Dark/mid tones pass almost unchanged; bright bursts are softened.
mediump vec3 rolloff(mediump vec3 c, mediump float k) {
    return c * (1.0 / (1.0 + k * luma(c)));
}

// IGN-amplitude-scaled dither to dissolve 8-bit quantisation banding.
mediump vec3 dither_out(mediump vec3 c, highp vec2 p) {
    mediump float amp = clamp(max(c.r, max(c.g, c.b)) * 255.0, 0.0, 1.0);
    return c + (ign(p) - 0.5) * (amp / 255.0);
}

  """.trimIndent()

  // -- Shared video prologue ---------------------------------------------------

  /**
   * Shared in-shader prologue injected into each mode's hook():
   *   - Remaps screen UV to video UV.
   *   - Computes safe clamping bounds (half-texel inset).
   *   - Early-returns the original pixel when inside the video.
   *   - Computes per-bar geometry: bx, by, dx, dy, d, thick, dn.
   *     dn = normalised depth into the bar (0 = at edge, >= 1 = beyond reach).
   * @param reachScale multiplied against bar thickness for the reach cutoff.
   */
  private fun videoPrologue(reachScale: Double): String = """
    highp vec2 uv       = HOOKED_pos;
    highp vec2 video_uv = (uv - vec2(CENTER_X, CENTER_Y)) * vec2(SCALE_X, SCALE_Y) + 0.5;

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
    // At the default center this reduces to the original (SCALE - 1) / 2.
    highp float bx = max((video_uv.x < 0.0 ? CENTER_X : 1.0 - CENTER_X) * SCALE_X - 0.5, 0.001);
    highp float by = max((video_uv.y < 0.0 ? CENTER_Y : 1.0 - CENTER_Y) * SCALE_Y - 0.5, 0.001);

    // Penetration depth into each bar axis (>0 only when outside that axis).
    highp float dx = max(max(-video_uv.x, video_uv.x - 1.0), 0.0);
    highp float dy = max(max(-video_uv.y, video_uv.y - 1.0), 0.0);

    // Euclidean distance into the bar region.
    highp float d = length(vec2(dx, dy));

    // Which axis dominates: pillarbox (dx > dy) uses bx, letterbox uses by.
    highp float thick = dx > dy ? bx : by;

    // Normalised depth: 0 at the video edge, 1 at the nominal reach boundary.
    highp float dn = clamp(d / (thick * ${f(reachScale)}), 0.0, 1.0);
    if (dn >= 1.0) { return vec4(0.0, 0.0, 0.0, 1.0); }
  """.trimIndent().prependIndent("    ")

  // -- Cinema -----------------------------------------------------------------

  /**
   * Spiral tap offsets for the Cinema mode manual Gaussian blur.
   */
  private fun cinemaTapTable(count: Int, maxR: Double): String {
    val goldenAngle = 2.399963229728653
    val taps = (0 until count).joinToString(",\n") { i ->
      val r = sqrt((i.toDouble() + 0.5) / count) * maxR
      val theta = (i.toDouble() + 0.5) * goldenAngle
      "    vec2(${f(cos(theta) * r)}, ${f(sin(theta) * r)})"
    }
    return "const vec2 CINEMA_TAPS[$count] = vec2[$count](\n$taps\n);"
  }

  /**
   * Builds the Cinema ambient shader with adjustable user parameters.
   */
  fun buildCinema(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.10).coerceIn(0.2, 4.0)
    val reachScale = 1.35 * spreadScale
    val farRadius = (0.10 * spreadScale).coerceIn(0.01, 0.50)
    val baseRadius = (0.025 * spreadScale).coerceIn(0.005, 0.15)
    val tapCount = spec.blurSamples.coerceIn(4, 32)
    val tapTable = cinemaTapTable(tapCount, farRadius)

    val vibranceVal = 1.08 * spec.satBoost.toDouble().coerceIn(0.0, 3.0)
    val intensityVal = 0.72 * spec.intensity.toDouble().coerceIn(0.1, 4.0)
    val fadeCurveVal = spec.fadeCurve.toDouble().coerceIn(0.2, 4.0)
    val warmthVal = spec.warmth.toDouble().coerceIn(-1.0, 1.0)
    val vignetteVal = spec.vignetteStrength.toDouble().coerceIn(0.0, 1.0)
    val opacityVal = spec.opacity.toDouble().coerceIn(0.0, 1.0)

    val warmthLine = if (abs(warmthVal) > 0.0001) "    col = apply_warmth(col, ${f(warmthVal)});\n" else ""
    val vignetteLine = if (vignetteVal > 0.0001) """
    mediump float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, smoothstep(1.3, 0.1, vig_r), ${f(vignetteVal)});
""" else ""
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    return """
${buildHeader("Fx Ambient -- Cinema", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

// Pre-computed Spiral Poisson tap offsets ($tapCount taps, max reach = ${f(farRadius)} UV units).
$tapTable

vec4 hook() {
${videoPrologue(reachScale)}

    // -- Cinema theater atmospheric projection -------------------------------
    // Anchor the sample origin at the video edge with a constant inward inset.
    // Inward inset ensures Poisson taps spread into decoded content without
    // border-wall clamping, while anchoring prevents inverted mirror reflection.
    highp vec2 edge_origin   = clamp(video_uv, safe_min, safe_max);
    highp vec2 sample_origin = mix(edge_origin, vec2(0.5), 0.04);

    // -- Per-pixel Jorge Jimenez IGN jitter rotation -------------------------
    // Rotating the Poisson disk per-pixel turns static tap offsets into a continuous
    // stochastic Monte Carlo blur, eliminating any discrete tap ghosting or reflection.
    mediump float jitter   = ign(uv * HOOKED_size) * 6.2831853;
    mediump float jitter_s = sin(jitter);
    mediump float jitter_c = cos(jitter);
    mediump float aspect_x = HOOKED_size.y / HOOKED_size.x;
    mediump float jitter_c_aspect = jitter_c * aspect_x;
    mediump float jitter_s_aspect = jitter_s * aspect_x;

    // -- $tapCount-tap spiral Gaussian blur ------------------------------------------
    // Blend radius grows from baseRadius (crisp theater edge) to farRadius (diffuse).
    mediump float br       = mix(${f(baseRadius)}, ${f(farRadius)}, dn);
    mediump float br_scale = br / ${f(farRadius)};

    mediump vec3  acc = vec3(0.0);
    mediump float twt = 0.0;
    for (int i = 0; i < $tapCount; i++) {
        vec2 tap = CINEMA_TAPS[i] * br_scale;
        highp vec2 tap_off = vec2(
            tap.x * jitter_c_aspect - tap.y * jitter_s_aspect,
            tap.x * jitter_s + tap.y * jitter_c
        );
        mediump vec3  s = HOOKED_tex(clamp(sample_origin + tap_off, safe_min, safe_max)).rgb;
        mediump float w = 1.0 + luma(s) * 1.5;
        acc += s * w;
        twt += w;
    }
    mediump vec3 col = acc / max(twt, 0.001);

    // -- Colour grade --------------------------------------------------------
    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.55);

    // -- Fade: bright at edge, gone at bar end --------------------------------
    mediump float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) * ${f(intensityVal)};
    mediump vec3 ambient_rgb = col * fade;
$vignetteLine$opacityLine
    // -- Dither --------------------------------------------------------------
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
    """.trimIndent()
  }

  fun buildCinema(context: Context, scaleX: Double, scaleY: Double): String =
    buildCinema(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Cinema,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 8,
        spread = 0.10f,
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
   * Builds the Echo ambient shader with adjustable user parameters.
   */
  fun buildEcho(
    @Suppress("UNUSED_PARAMETER") context: Context,
    spec: FxAmbientShaderSpec,
  ): String {
    val spreadScale = (spec.spread.toDouble() / 0.10).coerceIn(0.2, 4.0)
    val reachScale = 1.20 * spreadScale
    val swBase = 0.02 * spreadScale
    val swFar = 0.10 * spreadScale

    val tapCount = spec.blurSamples.coerceIn(3, 15).let { if (it % 2 == 0) it + 1 else it }
    val halfCount = tapCount / 2
    val sigma = (halfCount * 0.75).coerceAtLeast(0.5)
    val rawWeights = (-halfCount..halfCount).map { i ->
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
    val vignetteLine = if (vignetteVal > 0.0001) """
    mediump float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, smoothstep(1.3, 0.1, vig_r), ${f(vignetteVal)});
""" else ""
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    val weightsDecl = "const float ECHO_WEIGHTS[$tapCount] = float[$tapCount](${weights.joinToString(", ") { f(it) }});"
    val offsetsDecl = "const float ECHO_OFFSETS[$tapCount] = float[$tapCount](${(-halfCount..halfCount).joinToString(", ") { f(it.toDouble() / halfCount) }});"

    return """
${buildHeader("Fx Ambient -- Echo", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$weightsDecl
$offsetsDecl

vec4 hook() {
${videoPrologue(reachScale)}

    // -- Edge origin (nearest video pixel) -----------------------------------
    highp vec2 edge_origin = clamp(video_uv, safe_min, safe_max);

    // Anchor echo_origin at a constant inward inset (independent of dn).
    // This gives smear taps a clean margin inside real decoded content without
    // crawling deeper into the frame, completely eliminating inverted mirror reflections.
    highp vec2 echo_origin = mix(edge_origin, vec2(0.5), 0.035);

    // -- Determine smear axes ------------------------------------------------
    // For pillarbox bars (left/right) smear vertically; for letterbox smear horizontally.
    bool in_side_bar = (SCALE_X > SCALE_Y) && (dx > dy);
    highp vec2 smear_dir = in_side_bar ? vec2(0.0, 1.0) : vec2(1.0, 0.0);
    highp vec2 perp_dir  = in_side_bar ? vec2(1.0, 0.0) : vec2(0.0, 1.0);

    // Smear width in video-UV space grows from tight at edge to wide at depth.
    mediump float sw = ${f(swBase)} + ${f(swFar)} * dn;
    mediump float dw = sw * 0.30;

    // High-frequency stochastic jitter along smear & transverse axes to dissolve tap banding
    mediump float jitter = (ign(uv * HOOKED_size) - 0.5);
    highp vec2 jitter_off = (smear_dir * (jitter * sw * 0.35)) + (perp_dir * (jitter * dw * 0.25));

    // -- $tapCount-tap Gaussian along smear axis with transverse spread ------
    mediump vec3 col = vec3(0.0);
    for (int i = 0; i < $tapCount; i++) {
        float step_factor = ECHO_OFFSETS[i];
        highp vec2 tap_pos = clamp(
            echo_origin + smear_dir * (step_factor * sw) + perp_dir * (step_factor * dw) + jitter_off,
            safe_min, safe_max
        );
        col += HOOKED_tex(tap_pos).rgb * ECHO_WEIGHTS[i];
    }

    // -- Colour grade --------------------------------------------------------
    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.55);

    // -- Fade: power law gives a crisp edge followed by a long soft tail -----
    mediump float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) * ${f(intensityVal)};
    mediump vec3 ambient_rgb = col * fade;
$vignetteLine$opacityLine
    // -- Dither --------------------------------------------------------------
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
    """.trimIndent()
  }

  fun buildEcho(context: Context, scaleX: Double, scaleY: Double): String =
    buildEcho(
      context,
      FxAmbientShaderSpec(
        style = AmbientStyle.Echo,
        scaleX = scaleX,
        scaleY = scaleY,
        blurSamples = 5,
        spread = 0.10f,
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
   * Builds the Mirror ambient shader with adjustable user parameters.
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
    val rawWeights = (-halfCount..halfCount).map { i ->
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
    val vignetteLine = if (vignetteVal > 0.0001) """
    mediump float vig_r = length(uv - 0.5) * 2.0;
    ambient_rgb *= mix(1.0, smoothstep(1.3, 0.1, vig_r), ${f(vignetteVal)});
""" else ""
    val opacityLine = if (abs(opacityVal - 1.0) > 0.0001) "    ambient_rgb *= ${f(opacityVal)};\n" else ""

    val weightsDecl = "const float MIRROR_WEIGHTS[$tapCount] = float[$tapCount](${weights.joinToString(", ") { f(it) }});"
    val offsetsDecl = "const float MIRROR_OFFSETS[$tapCount] = float[$tapCount](${(-halfCount..halfCount).joinToString(", ") { f(it.toDouble() / halfCount) }});"

    return """
${buildHeader("Fx Ambient -- Mirror", spec.scaleX, spec.scaleY, spec.centerX, spec.centerY)}

$weightsDecl
$offsetsDecl

vec4 hook() {
${videoPrologue(reachScale)}

    // -- Fold video_uv across [0,1] boundaries (mirror wrap) -----------------
    highp vec2 m_uv = video_uv;
    m_uv = mix(m_uv, -m_uv,             step(m_uv, vec2(0.0)));
    m_uv = mix(m_uv, vec2(2.0) - m_uv, step(vec2(1.0), m_uv));
    m_uv = clamp(m_uv, safe_min, safe_max);

    // -- Blur axis: fold along the same axis as the bar ----------------------
    bool in_side_bar = (SCALE_X > SCALE_Y) && (dx > dy);
    highp vec2 blur_dir = in_side_bar ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    mediump float bw = mix(0.0, ${f(bwFar)}, pow(dn, 0.7));

    // -- $tapCount-tap blur along blur_dir -------------------------------------------
    mediump vec3 col = vec3(0.0);
    for (int i = 0; i < $tapCount; i++) {
        float step_factor = MIRROR_OFFSETS[i];
        col += HOOKED_tex(clamp(m_uv + blur_dir * (step_factor * bw), safe_min, safe_max)).rgb * MIRROR_WEIGHTS[i];
    }

    // -- Colour grade --------------------------------------------------------
    col = vibrance(col, ${f(vibranceVal)});
$warmthLine    col = rolloff(col, 0.30);

    // -- Mirror fade: tight power + Gaussian decay so seam is crisp ----------
    mediump float fade = pow(max(1.0 - dn, 0.0), ${f(fadeCurveVal)}) *
                         (0.55 + 0.45 * exp(-6.0 * dn));

    // Ceiling (top bar) reflects less light than floor -- dim by 30%.
    if (video_uv.y < 0.0) { fade *= 0.7; }

    mediump vec3 ambient_rgb = col * (fade * ${f(intensityVal)});
$vignetteLine$opacityLine
    // -- Dither --------------------------------------------------------------
    ambient_rgb = dither_out(ambient_rgb, uv * HOOKED_size);
    return vec4(ambient_rgb, 1.0);
}
    """.trimIndent()
  }

  fun buildMirror(context: Context, scaleX: Double, scaleY: Double): String =
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