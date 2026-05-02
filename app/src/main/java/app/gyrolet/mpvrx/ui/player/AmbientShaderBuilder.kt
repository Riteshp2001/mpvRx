package app.gyrolet.mpvrx.ui.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class AmbientRenderContext(
  val scaleX: Double,
  val scaleY: Double,
)

data class AmbientSharedShaderConfig(
  val bezelDepth: Float,
  val vignetteStrength: Float,
  val opacity: Float,
)

enum class AmbientVisualMode(
  val label: String,
) {
  GLOW("Glow"),
  FRAME_EXTEND("Frame Extend"),
}

sealed interface AmbientShaderSpec {
  val context: AmbientRenderContext
  val shared: AmbientSharedShaderConfig
}

data class AmbientGlowShaderSpec(
  override val context: AmbientRenderContext,
  override val shared: AmbientSharedShaderConfig,
  val blurSamples: Int,
  val maxRadius: Float,
  val glowIntensity: Float,
  val satBoost: Float,
  val warmth: Float,
  val fadeCurve: Float,
) : AmbientShaderSpec

data class AmbientFrameExtendShaderSpec(
  override val context: AmbientRenderContext,
  override val shared: AmbientSharedShaderConfig,
  val sampleBudget: Int,
  val extendStrength: Float,
  val detailProtection: Float,
  val glowMix: Float,
  val ditherNoise: Float,
) : AmbientShaderSpec

data class AmbientGlowPreset(
  val blurSamples: Int,
  val maxRadius: Float,
  val glowIntensity: Float,
  val satBoost: Float,
  val vignetteStrength: Float,
  val warmth: Float,
  val fadeCurve: Float,
  val opacity: Float,
)

data class AmbientFrameExtendPreset(
  val sampleBudget: Int,
  val extendStrength: Float,
  val detailProtection: Float,
  val glowMix: Float,
  val ditherNoise: Float,
  val bezelDepth: Float,
  val vignetteStrength: Float,
  val opacity: Float,
)

object AmbientShaderPresets {
  val glowFast =
    AmbientGlowPreset(
      blurSamples = 12,
      maxRadius = 0.22f,
      glowIntensity = 1.4f,
      satBoost = 1.2f,
      vignetteStrength = 0.4f,
      warmth = 0.0f,
      fadeCurve = 1.6f,
      opacity = 1.0f,
    )

  val glowBalanced =
    AmbientGlowPreset(
      blurSamples = 18,
      maxRadius = 0.28f,
      glowIntensity = 1.45f,
      satBoost = 1.25f,
      vignetteStrength = 0.55f,
      warmth = 0.0f,
      fadeCurve = 1.7f,
      opacity = 1.0f,
    )

  val glowHighQuality =
    AmbientGlowPreset(
      blurSamples = 24,
      maxRadius = 0.35f,
      glowIntensity = 1.5f,
      satBoost = 1.3f,
      vignetteStrength = 0.7f,
      warmth = 0.0f,
      fadeCurve = 1.8f,
      opacity = 1.0f,
    )

  val frameExtendFast =
    AmbientFrameExtendPreset(
      sampleBudget = 16,
      extendStrength = 0.54f,
      detailProtection = 0.86f,
      glowMix = 0.24f,
      ditherNoise = 0.012f,
      bezelDepth = 0.0f,
      vignetteStrength = 0.42f,
      opacity = 1.0f,
    )

  val frameExtendBalanced =
    AmbientFrameExtendPreset(
      sampleBudget = 24,
      extendStrength = 0.70f,
      detailProtection = 0.72f,
      glowMix = 0.12f,
      ditherNoise = 0.020f,
      bezelDepth = 0.0f,
      vignetteStrength = 0.55f,
      opacity = 1.0f,
    )

  val frameExtendHighQuality =
    AmbientFrameExtendPreset(
      sampleBudget = 32,
      extendStrength = 0.84f,
      detailProtection = 0.62f,
      glowMix = 0.08f,
      ditherNoise = 0.028f,
      bezelDepth = 0.0f,
      vignetteStrength = 0.62f,
      opacity = 1.0f,
    )
}

fun matchesGlowPreset(
  preset: AmbientGlowPreset,
  blurSamples: Int,
  maxRadius: Float,
  glowIntensity: Float,
  satBoost: Float,
  vignetteStrength: Float,
  warmth: Float,
  fadeCurve: Float,
  opacity: Float,
): Boolean =
  blurSamples == preset.blurSamples &&
    closeTo(maxRadius, preset.maxRadius) &&
    closeTo(glowIntensity, preset.glowIntensity) &&
    closeTo(satBoost, preset.satBoost) &&
    closeTo(vignetteStrength, preset.vignetteStrength) &&
    closeTo(warmth, preset.warmth) &&
    closeTo(fadeCurve, preset.fadeCurve) &&
    closeTo(opacity, preset.opacity)

fun matchesFrameExtendPreset(
  preset: AmbientFrameExtendPreset,
  sampleBudget: Int,
  extendStrength: Float,
  detailProtection: Float,
  glowMix: Float,
  ditherNoise: Float,
  bezelDepth: Float,
  vignetteStrength: Float,
  opacity: Float,
): Boolean =
  sampleBudget == preset.sampleBudget &&
    closeTo(extendStrength, preset.extendStrength) &&
    closeTo(detailProtection, preset.detailProtection) &&
    closeTo(glowMix, preset.glowMix) &&
    closeTo(ditherNoise, preset.ditherNoise, 0.001f) &&
    closeTo(bezelDepth, preset.bezelDepth, 0.001f) &&
    closeTo(vignetteStrength, preset.vignetteStrength) &&
    closeTo(opacity, preset.opacity)

fun closeTo(
  left: Float,
  right: Float,
  tolerance: Float = 0.01f,
): Boolean = kotlin.math.abs(left - right) <= tolerance

private const val GOLDEN_ANGLE = 2.399963229728653

private fun glslFloat(value: Double): String {
  val normalized = if (abs(value) < 0.0000005) 0.0 else value
  val formatted = String.format(Locale.US, "%.8f", normalized)
    .trimEnd('0')
    .trimEnd('.')
  return if (formatted.contains('.')) formatted else "$formatted.0"
}

private fun buildSpiralTapTable(
  name: String,
  samples: Int,
  thirdComponent: (radiusNorm: Double, indexNorm: Double) -> Double,
): String {
  val count = samples.coerceAtLeast(1)
  val taps =
    (0 until count).joinToString(",\n") { index ->
      val indexNorm = (index.toDouble() + 0.5) / count.toDouble()
      val radiusNorm = sqrt(indexNorm)
      val theta = (index.toDouble() + 0.5) * GOLDEN_ANGLE
      val x = cos(theta) * radiusNorm
      val y = sin(theta) * radiusNorm
      "    vec3(${glslFloat(x)}, ${glslFloat(y)}, ${glslFloat(thirdComponent(radiusNorm, indexNorm))})"
    }
  return "const vec3 $name[$count] = vec3[$count](\n$taps\n);"
}

object AmbientShaderBuilder {
  fun build(spec: AmbientShaderSpec): String =
    when (spec) {
      is AmbientGlowShaderSpec -> buildGlow(spec)
      is AmbientFrameExtendShaderSpec -> buildFrameExtend(spec)
    }

  private fun buildGlow(spec: AmbientGlowShaderSpec): String =
    """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC True Ambient Mode

#define BLUR_SAMPLES     ${spec.blurSamples}
#define MAX_RADIUS       ${spec.maxRadius}
#define GLOW_INTENSITY   ${spec.glowIntensity}
#define SAT_BOOST        ${spec.satBoost}
#define BEZEL_DEPTH      ${spec.shared.bezelDepth}
#define VIGNETTE_STR     ${spec.shared.vignetteStrength}
#define WARMTH           ${spec.warmth}
#define FADE_CURVE       ${spec.fadeCurve}
#define OPACITY          ${spec.shared.opacity}
#define SCALE_X          ${spec.context.scaleX}
#define SCALE_Y          ${spec.context.scaleY}

const float PI  = 3.14159265358979;
${buildSpiralTapTable("GLOW_TAPS", spec.blurSamples) { radiusNorm, _ -> radiusNorm }}

float rand(vec2 seed) {
    return fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453);
}

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

vec3 adjust_saturation(vec3 rgb, float amount) {
    return mix(vec3(luma(rgb)), rgb, amount);
}

vec3 apply_warmth(vec3 rgb, float amount) {
    rgb.r = clamp(rgb.r + amount * 0.060, 0.0, 1.0);
    rgb.g = clamp(rgb.g + amount * 0.025, 0.0, 1.0);
    rgb.b = clamp(rgb.b - amount * 0.080, 0.0, 1.0);
    return rgb;
}

vec4 hook() {
    vec2 uv = HOOKED_pos;
    vec2 video_uv = (uv - 0.5) * vec2(SCALE_X, SCALE_Y) + 0.5;

    if (video_uv.x >= 0.0 && video_uv.x <= 1.0 &&
        video_uv.y >= 0.0 && video_uv.y <= 1.0) {
        return HOOKED_tex(video_uv);
    }

    vec2 edge_origin = clamp(video_uv, 0.0, 1.0);
    float edge_dist = length(video_uv - edge_origin);
    float edge_fade = exp(-edge_dist * (3.0 / max(MAX_RADIUS, 0.001)));

    float jitter = rand(uv * HOOKED_size) * (PI * 2.0);
    float jitter_s = sin(jitter);
    float jitter_c = cos(jitter);
    vec2 aspect_fix = vec2(HOOKED_size.y / HOOKED_size.x, 1.0);

    vec3 acc_color = vec3(0.0);
    float acc_weight = 0.0;

    for (int i = 0; i < BLUR_SAMPLES; i++) {
        vec3 tap = GLOW_TAPS[i];
        vec2 base_offset = tap.xy * MAX_RADIUS;
        float r = tap.z * MAX_RADIUS;

        vec2 offset = vec2(
            base_offset.x * jitter_c - base_offset.y * jitter_s,
            base_offset.x * jitter_s + base_offset.y * jitter_c
        ) * aspect_fix;
        vec2 sample_uv = clamp(edge_origin + offset, 0.0, 1.0);
        vec3 sample_rgb = HOOKED_tex(sample_uv).rgb;

        float dist_w = pow(max(1.0 / (1.0 + r * 40.0), 0.0), FADE_CURVE);
        float luma_w = 1.0 + luma(sample_rgb) * 2.0;
        float weight = dist_w * luma_w;

        acc_color += sample_rgb * weight;
        acc_weight += weight;
    }

    vec3 glow = (acc_color / max(acc_weight, 1e-5)) * GLOW_INTENSITY;
    glow = adjust_saturation(glow, SAT_BOOST);
    glow = apply_warmth(glow, WARMTH);
    glow *= edge_fade;

    float vig_r = length(uv - 0.5) * 2.0;
    glow *= mix(1.0, smoothstep(1.3, 0.1, vig_r), VIGNETTE_STR);

    float bezel = max(BEZEL_DEPTH, 0.001);
    vec2 outside_dist = max(max(-video_uv, video_uv - vec2(1.0)), vec2(0.0));
    float dist_to_edge = max(outside_dist.x, outside_dist.y);
    float bezel_alpha = smoothstep(0.0, bezel, dist_to_edge);

    vec4 edge_pixel = HOOKED_tex(edge_origin);
    vec4 ambient_out = vec4(glow * OPACITY, 1.0);
    return mix(edge_pixel, ambient_out, bezel_alpha);
}
    """.trimIndent()

  private fun buildFrameExtend(spec: AmbientFrameExtendShaderSpec): String {
    val effectiveBudget = spec.sampleBudget.coerceIn(8, 32)
    val extendSteps = (effectiveBudget / 5).coerceIn(4, 8)
    val glowSamples = (effectiveBudget / 3).coerceIn(6, 14)
    val anchorRadius = if (effectiveBudget >= 28) 2 else 1
    val orthoRadius = 1
    return """
//!HOOK OUTPUT
//!BIND HOOKED
//!DESC Frame Extend Ambient Mode

#define EXTEND_STEPS      $extendSteps
#define GLOW_SAMPLES      $glowSamples
#define ANCHOR_RADIUS     $anchorRadius
#define ORTHO_RADIUS      $orthoRadius
#define EXTEND_STRENGTH   ${spec.extendStrength}
#define DETAIL_PROTECT    ${spec.detailProtection}
#define GLOW_MIX          ${spec.glowMix}
#define DITHER_NOISE      ${spec.ditherNoise}
#define BEZEL_DEPTH       ${spec.shared.bezelDepth}
#define VIGNETTE_STR      ${spec.shared.vignetteStrength}
#define OPACITY           ${spec.shared.opacity}
#define SCALE_X           ${spec.context.scaleX}
#define SCALE_Y           ${spec.context.scaleY}

const float PI = 3.14159265358979;
${buildSpiralTapTable("FRAME_GLOW_TAPS", glowSamples) { _, indexNorm -> indexNorm }}

float rand(vec2 seed) {
    return fract(sin(dot(seed, vec2(12.9898, 78.233))) * 43758.5453);
}

float luma(vec3 rgb) {
    return dot(rgb, vec3(0.2126, 0.7152, 0.0722));
}

float noise_value(vec2 uv) {
    return rand(uv * HOOKED_size + vec2(11.0, 47.0)) - 0.5;
}

vec3 apply_dither(vec3 rgb, vec2 uv, float flatness) {
    if (DITHER_NOISE <= 0.0001) {
        return rgb;
    }
    float amount = DITHER_NOISE * mix(0.025, 0.15, flatness);
    return clamp(rgb + vec3(noise_value(uv)) * amount, 0.0, 1.0);
}

float edge_risk(vec2 edge_origin, vec3 edge, vec2 inward_dir, vec2 ortho_dir) {
    vec3 ortho_a = HOOKED_tex(clamp(edge_origin + ortho_dir * 0.008, 0.0, 1.0)).rgb;
    vec3 ortho_b = HOOKED_tex(clamp(edge_origin - ortho_dir * 0.008, 0.0, 1.0)).rgb;
    vec3 inward = HOOKED_tex(clamp(edge_origin + inward_dir * 0.014, 0.0, 1.0)).rgb;

    float ortho_contrast = clamp(length(ortho_a - ortho_b) * 1.9, 0.0, 1.0);
    float inward_contrast = clamp(length(inward - edge) * 2.1, 0.0, 1.0);
    return clamp(ortho_contrast * 0.65 + inward_contrast * 0.55, 0.0, 1.0);
}

vec3 sample_soft_glow(vec2 edge_origin, vec2 uv, float outside_norm) {
    float jitter = rand(uv * HOOKED_size) * (PI * 2.0);
    float jitter_s = sin(jitter);
    float jitter_c = cos(jitter);
    float radius = mix(0.016, 0.095, outside_norm);
    vec2 aspect_fix = vec2(HOOKED_size.y / HOOKED_size.x, 1.0);
    vec3 acc = vec3(0.0);
    float acc_weight = 0.0;

    for (int i = 0; i < GLOW_SAMPLES; i++) {
        vec3 tap = FRAME_GLOW_TAPS[i];
        float fi = tap.z;
        vec2 base_offset = tap.xy * radius;
        vec2 offset = vec2(
            base_offset.x * jitter_c - base_offset.y * jitter_s,
            base_offset.x * jitter_s + base_offset.y * jitter_c
        ) * aspect_fix;
        vec2 sample_uv = clamp(edge_origin + offset, 0.0, 1.0);
        vec3 sample_rgb = HOOKED_tex(sample_uv).rgb;
        float weight = (1.15 - fi) * (0.8 + luma(sample_rgb));
        acc += sample_rgb * weight;
        acc_weight += weight;
    }

    return acc / max(acc_weight, 1e-5);
}

vec4 trace_anchor_strip(vec2 anchor_uv, vec3 anchor_edge, vec2 inward_dir, vec2 ortho_dir, float outside_norm) {
    float extend_depth = mix(0.018, 0.34, outside_norm) * mix(0.80, 1.45, EXTEND_STRENGTH);
    float ortho_scale = mix(0.026, 0.005, DETAIL_PROTECT) * (0.45 + outside_norm * 0.85);

    vec3 acc = vec3(0.0);
    float acc_weight = 0.0;
    vec3 prev = anchor_edge;
    float coherence_acc = 0.0;

    for (int i = 0; i < EXTEND_STEPS; i++) {
        float fi = float(i + 1) / float(EXTEND_STEPS);
        vec2 base_uv = clamp(anchor_uv + inward_dir * (extend_depth * fi), 0.0, 1.0);

        vec3 strip_acc = vec3(0.0);
        float strip_weight = 0.0;
        for (int j = -ORTHO_RADIUS; j <= ORTHO_RADIUS; j++) {
            float fj = float(j);
            float denom = max(float(ORTHO_RADIUS), 1.0);
            float tap_pos = fj / denom;
            vec2 sample_uv = clamp(
                base_uv + ortho_dir * ortho_scale * tap_pos * (0.55 + fi * 0.90),
                0.0,
                1.0
            );
            vec3 sample_rgb = HOOKED_tex(sample_uv).rgb;
            float near_prev = 1.0 - clamp(length(sample_rgb - prev) * 2.0, 0.0, 1.0);
            float near_edge = 1.0 - clamp(length(sample_rgb - anchor_edge) * 1.6, 0.0, 1.0);
            float tap_weight = exp(-abs(tap_pos) * mix(1.2, 3.6, DETAIL_PROTECT));
            tap_weight *= mix(0.55, 1.0, near_prev);
            tap_weight *= mix(0.45, 1.0, near_edge);
            strip_acc += sample_rgb * tap_weight;
            strip_weight += tap_weight;
        }

        vec3 sample_rgb = strip_acc / max(strip_weight, 1e-5);
        float step_similarity = 1.0 - clamp(length(sample_rgb - prev) * 2.1, 0.0, 1.0);
        float edge_similarity = 1.0 - clamp(length(sample_rgb - anchor_edge) * 1.8, 0.0, 1.0);
        float weight = mix(1.35, 0.25, fi);
        weight *= mix(0.60, 1.0, edge_similarity);
        acc += sample_rgb * weight;
        acc_weight += weight;

        coherence_acc += step_similarity * edge_similarity;
        prev = sample_rgb;
    }

    vec3 extend_rgb = acc / max(acc_weight, 1e-5);
    float coherence = clamp(coherence_acc / float(EXTEND_STEPS), 0.0, 1.0);
    coherence = pow(coherence, mix(0.85, 2.8, DETAIL_PROTECT));
    return vec4(extend_rgb, coherence);
}

vec4 sample_predictive_fill(vec2 edge_origin, vec3 edge_rgb, vec2 inward_dir, vec2 ortho_dir, float outside_norm) {
    float anchor_span = mix(0.010, 0.070, outside_norm) * mix(0.55, 1.20, EXTEND_STRENGTH);
    vec3 acc = vec3(0.0);
    float acc_weight = 0.0;
    float confidence_acc = 0.0;

    for (int k = -ANCHOR_RADIUS; k <= ANCHOR_RADIUS; k++) {
        float fk = float(k);
        float anchor_norm = ANCHOR_RADIUS > 0 ? fk / float(ANCHOR_RADIUS) : 0.0;
        vec2 anchor_uv = clamp(edge_origin + ortho_dir * anchor_span * anchor_norm, 0.0, 1.0);
        vec3 anchor_edge = HOOKED_tex(anchor_uv).rgb;
        vec4 traced = trace_anchor_strip(anchor_uv, anchor_edge, inward_dir, ortho_dir, outside_norm);

        float center_weight = exp(-abs(anchor_norm) * mix(1.0, 3.2, DETAIL_PROTECT));
        float anchor_similarity = 1.0 - clamp(length(anchor_edge - edge_rgb) * 2.3, 0.0, 1.0);
        float confidence = mix(traced.a, traced.a * anchor_similarity, 0.6);
        float weight = center_weight * mix(0.35, 1.0, anchor_similarity) * mix(0.30, 1.0, confidence);

        acc += traced.rgb * weight;
        acc_weight += weight;
        confidence_acc += confidence * weight;
    }

    return vec4(acc / max(acc_weight, 1e-5), confidence_acc / max(acc_weight, 1e-5));
}

vec4 hook() {
    vec2 uv = HOOKED_pos;
    vec2 video_uv = (uv - 0.5) * vec2(SCALE_X, SCALE_Y) + 0.5;

    if (video_uv.x >= 0.0 && video_uv.x <= 1.0 &&
        video_uv.y >= 0.0 && video_uv.y <= 1.0) {
        return HOOKED_tex(video_uv);
    }

    vec2 edge_origin = clamp(video_uv, 0.0, 1.0);
    vec2 overflow = video_uv - edge_origin;
    bool horizontal = abs(overflow.x) >= abs(overflow.y);

    vec2 inward_dir = horizontal
        ? vec2(overflow.x < 0.0 ? 1.0 : -1.0, 0.0)
        : vec2(0.0, overflow.y < 0.0 ? 1.0 : -1.0);
    vec2 ortho_dir = horizontal ? vec2(0.0, 1.0) : vec2(1.0, 0.0);

    float bar_extent = horizontal
        ? max((SCALE_X - 1.0) * 0.5, 0.001)
        : max((SCALE_Y - 1.0) * 0.5, 0.001);
    float dist_to_edge = horizontal ? abs(overflow.x) : abs(overflow.y);
    float outside_norm = clamp(dist_to_edge / bar_extent, 0.0, 1.0);

    vec3 edge_rgb = HOOKED_tex(edge_origin).rgb;
    float risk = edge_risk(edge_origin, edge_rgb, inward_dir, ortho_dir);
    vec4 extend_result = sample_predictive_fill(edge_origin, edge_rgb, inward_dir, ortho_dir, outside_norm);
    vec3 glow_rgb = sample_soft_glow(edge_origin, uv, outside_norm);

    float confidence = clamp(
        extend_result.a * (1.0 - risk * mix(0.45, 0.88, DETAIL_PROTECT)),
        0.0,
        1.0
    );
    float fallback_mix = clamp(
        GLOW_MIX +
        (1.0 - confidence) * mix(0.18, 0.75, DETAIL_PROTECT) +
        risk * mix(0.05, 0.28, DETAIL_PROTECT),
        0.0,
        1.0
    );
    vec3 fill_rgb = mix(extend_result.rgb, glow_rgb, fallback_mix);
    fill_rgb = mix(edge_rgb, fill_rgb, smoothstep(0.18, 0.98, outside_norm));

    float vig_r = length(uv - 0.5) * 2.0;
    fill_rgb *= mix(1.0, smoothstep(1.3, 0.1, vig_r), VIGNETTE_STR);

    float flatness = clamp((1.0 - risk) * (0.55 + outside_norm * 0.45), 0.0, 1.0);
    fill_rgb = apply_dither(fill_rgb, uv, flatness);

    float bezel = max(BEZEL_DEPTH, 0.001);
    float bezel_alpha = smoothstep(0.0, bezel, dist_to_edge);
    vec4 ambient_out = vec4(fill_rgb * OPACITY, 1.0);
    return mix(vec4(edge_rgb, 1.0), ambient_out, bezel_alpha);
}
    """.trimIndent()
  }
}

