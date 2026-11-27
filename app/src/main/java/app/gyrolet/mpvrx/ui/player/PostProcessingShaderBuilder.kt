/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.util.Locale

// ── Helper ────────────────────────────────────────────────────────────────────

/** Format a Float as a GLSL-valid literal (always includes a decimal point). */
private fun f(v: Float): String {
  val s = String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
  return if (s.contains('.')) s else "$s.0"
}

private fun f(v: Int): String = "$v.0"

// ── PostProcessingParams ──────────────────────────────────────────────────────

/**
 * All tunable parameters for every post-processing shader, with their Eden defaults.
 * Parameters are baked into shader source via [f] at file-write time; mpv has no
 * runtime uniform injection mechanism for user shaders.
 */
data class PostProcessingParams(
  // NaturalColors
  val naturalLuma: Float = 1.2f,
  val naturalChroma: Float = 1.2f,
  // Levels
  val levelsInputBlack: Float = 0.0f,
  val levelsInputWhite: Float = 1.0f,
  val levelsGamma: Float = 1.0f,
  val levelsOutputBlack: Float = 0.0f,
  val levelsOutputWhite: Float = 1.0f,
  // Sharpen
  val sharpenAmount: Float = 0.6f,
  // Bloom
  val bloomRadius: Float = 1.0f,
  val bloomAmount: Float = 0.6f,
  // ColorGrade
  val cgSaturation: Float = 1.0f,
  val cgBrightness: Float = 1.0f,
  val cgContrast: Float = 1.0f,
  val cgGamma: Float = 1.0f,
  // Denoise (bilateral)
  val denoiseStrength: Float = 0.1f,
  val denoiseRadius: Float = 1.0f,
  val denoiseCurve: Float = 1.0f,
  // CelShading
  val celBands: Float = 4.0f,
  val celBandContrast: Float = 0.5f,
  val celBandEdge: Float = 0.15f,
  val celDetail: Float = 0.6f,
  val celOutlineStrength: Float = 0.8f,
  val celOutlineThreshold: Float = 0.18f,
  val celSaturation: Float = 1.25f,
  // FilmicCurve
  val filmicExposure: Float = 1.0f,
  val filmicToe: Float = 1.2f,
  val filmicShoulder: Float = 1.2f,
  val filmicAmount: Float = 0.7f,
  // Blur (spiral Poisson disc)
  val blurRadius: Float = 4.0f,
  val blurStrength: Float = 1.0f,
  val blurFocusSize: Float = 0.0f,
  val blurFocusSoftness: Float = 0.4f,
  // Cartoon (PPSSPP guest(r))
  val cartoonEdgeStrength: Float = 0.5f,
  val cartoonLevels: Float = 4.0f,
  // CartoonSoft
  val cartoonSoftEdgeStrength: Float = 0.45f,
  val cartoonSoftShadowGuard: Float = 0.8f,
  val cartoonSoftLevels: Float = 6.0f,
  val cartoonSoftSmoothing: Float = 0.75f,
  val cartoonSoftSaturation: Float = 1.15f,
  // ChromaticAberration
  val caStrength: Float = 1.5f,
  val caFalloff: Float = 2.0f,
  // Deband
  val debandThreshold: Float = 0.012f,
  val debandRadius: Float = 8.0f,
  val debandGrain: Float = 0.004f,
  // FilmGrain
  val grainIntensity: Float = 0.03f,
  val grainSize: Float = 1.0f,
  val grainColored: Float = 0.0f,
  // LensDistortion
  val lensDistortion: Float = 0.1f,
  val lensZoom: Float = 1.0f,
  // MotionBlur
  val motionLength: Float = 5.0f,
  val motionZoom: Float = 1.0f,
  val motionPan: Float = 0.0f,
  val motionAngle: Float = 0.0f,
  val motionSpin: Float = 0.0f,
  // Reflections
  val reflHorizon: Float = 0.55f,
  val reflAmount: Float = 0.35f,
  val reflFalloff: Float = 1.2f,
  val reflPerspective: Float = 1.0f,
  val reflRipple: Float = 0.0f,
  val reflRippleSpeed: Float = 1.0f,
  // Scanlines
  val scanlinesDensity: Float = 340.0f,
  val scanlinesIntensity: Float = 0.5f,
  val scanlinesTint: Float = 1.0f,
  // SplitToning
  val splitShadowHue: Float = 210.0f,
  val splitShadowStrength: Float = 0.0f,
  val splitHighlightHue: Float = 45.0f,
  val splitHighlightStrength: Float = 0.0f,
  val splitBalance: Float = 0.0f,
  // Vignette
  val vignetteStrength: Float = 0.6f,
  val vignetteAspect: Float = 1.0f,
  // WhiteBalance
  val wbTemperature: Float = 0.0f,
  val wbTint: Float = 0.0f,
  // CRT
  val crtDensity: Float = 272.0f,
  val crtRollSpeed: Float = 1.0f,
  val crtBleed: Float = 1.0f,
)

// ── Shader Builders ───────────────────────────────────────────────────────────

/** Port of Eden NaturalColors.fx — YIQ luma curve + chroma gain (PPSSPP by ShadX/SimoneT). */
object NaturalColorsShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Natural Colours (mpvRx)

#define PP_LUMA   ${f(p.naturalLuma)}
#define PP_CHROMA ${f(p.naturalChroma)}

vec4 hook() {
    vec4 c = HOOKED_tex(HOOKED_pos);
    // RGB -> YIQ (row-major: columns are basis vectors)
    float Y = dot(c.rgb, vec3(0.299,  0.587,  0.114));
    float I = dot(c.rgb, vec3(0.596, -0.275, -0.321));
    float Q = dot(c.rgb, vec3(0.212, -0.523,  0.311));
    Y = pow(max(Y, 0.0), PP_LUMA);
    I *= PP_CHROMA;
    Q *= PP_CHROMA;
    // YIQ -> RGB
    vec3 rgb = clamp(vec3(
        Y + 0.95568806*I + 0.61985809*Q,
        Y - 0.27158180*I - 0.64687382*Q,
        Y - 1.10817733*I + 1.70506456*Q
    ), 0.0, 1.0);
    return vec4(rgb, c.a);
}
    """.trimIndent()
}

/** Port of Eden Levels.fx — input/output black+white points + gamma. */
object LevelsShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Levels (mpvRx)

#define PP_IN_BLACK   ${f(p.levelsInputBlack)}
#define PP_IN_WHITE   ${f(p.levelsInputWhite)}
#define PP_GAMMA      ${f(p.levelsGamma)}
#define PP_OUT_BLACK  ${f(p.levelsOutputBlack)}
#define PP_OUT_WHITE  ${f(p.levelsOutputWhite)}

vec4 hook() {
    vec4 c = HOOKED_tex(HOOKED_pos);
    vec3 rgb = clamp((c.rgb - PP_IN_BLACK) / max(PP_IN_WHITE - PP_IN_BLACK, 0.001), 0.0, 1.0);
    rgb = pow(max(rgb, 0.0), vec3(1.0 / max(PP_GAMMA, 0.001)));
    rgb = mix(vec3(PP_OUT_BLACK), vec3(PP_OUT_WHITE), rgb);
    return vec4(clamp(rgb, 0.0, 1.0), c.a);
}
    """.trimIndent()
}

/** Port of Eden Sharpen.fx — unsharp mask. */
object SharpenShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Sharpen (mpvRx)

#define PP_AMOUNT ${f(p.sharpenAmount)}

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec3 centre = HOOKED_tex(HOOKED_pos).rgb;
    vec3 blur = HOOKED_tex(HOOKED_pos + vec2(-texel.x, 0.0)).rgb;
    blur += HOOKED_tex(HOOKED_pos + vec2( texel.x, 0.0)).rgb;
    blur += HOOKED_tex(HOOKED_pos + vec2(0.0, -texel.y)).rgb;
    blur += HOOKED_tex(HOOKED_pos + vec2(0.0,  texel.y)).rgb;
    blur *= 0.25;
    return vec4(clamp(centre + (centre - blur) * PP_AMOUNT, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden Bloom.fx — additive luminance-weighted glow (PPSSPP bloomnoblur). */
object BloomShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Bloom (mpvRx)

#define PP_RADIUS ${f(p.bloomRadius)}
#define PP_AMOUNT ${f(p.bloomAmount)}

float bloomWeight(vec3 color) {
    float gray = (color.r + color.g + color.b) / 3.0;
    float sat  = (abs(color.r - gray) + abs(color.g - gray) + abs(color.b - gray)) / 3.0;
    return gray * gray / max(sat, 0.25);
}

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec3 color = HOOKED_tex(HOOKED_pos).rgb;
    float gray = (color.r + color.g + color.b) / 3.0;
    float sat  = (abs(color.r - gray) + abs(color.g - gray) + abs(color.b - gray)) / 3.0;
    float spread = 0.002 * gray / max(sat, 0.25) * PP_RADIUS;
    vec3 sum = vec3(0.0);
    for (int x = -3; x <= 3; x += 2)
        for (int y = -3; y <= 3; y += 2) {
            vec3 tap = HOOKED_tex(HOOKED_pos + vec2(x, y) * spread).rgb;
            sum += tap * bloomWeight(tap);
        }
    sum /= 16.0;
    return vec4(clamp(color + sum * PP_AMOUNT, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden Denoise.fx — bilateral edge-preserving filter (Anime4K-derived). */
object DenoiseShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Denoise (mpvRx)

#define PP_STRENGTH ${f(p.denoiseStrength)}
#define PP_RADIUS   ${f(p.denoiseRadius)}
#define PP_CURVE    ${f(p.denoiseCurve)}

vec3 intensityWeight(vec3 value, vec3 sigma, vec3 centre) {
    vec3 scaled = (value - centre) / sigma;
    return exp(-0.5 * scaled * scaled);
}
float spatialWeight(float distance, float sigma) {
    float scaled = distance / sigma;
    return exp(-0.5 * scaled * scaled);
}

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec3 centre = HOOKED_tex(HOOKED_pos).rgb;
    vec3 iSigma = max(pow(centre + 0.0001, vec3(PP_CURVE)) * PP_STRENGTH, 0.0001);
    float sSigma = max(PP_RADIUS, 0.05);
    vec3 sum = vec3(0.0);
    vec3 total = vec3(0.0);
    for (int y = -2; y <= 2; ++y)
        for (int x = -2; x <= 2; ++x) {
            vec2 offset = vec2(x, y);
            vec3 tap = HOOKED_tex(HOOKED_pos + offset * texel).rgb;
            vec3 w = intensityWeight(tap, iSigma, centre) * spatialWeight(length(offset), sSigma);
            sum += w * tap;
            total += w;
        }
    return vec4(sum / total, 1.0);
}
    """.trimIndent()
}

/** Port of Eden CelShading.fx — quantised lighting bands + Sobel ink outlines. */
object CelShadingShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Cel Shading (mpvRx)

#define PP_BANDS             ${f(p.celBands)}
#define PP_BAND_CONTRAST     ${f(p.celBandContrast)}
#define PP_BAND_EDGE         ${f(p.celBandEdge)}
#define PP_DETAIL            ${f(p.celDetail)}
#define PP_OUTLINE_STRENGTH  ${f(p.celOutlineStrength)}
#define PP_OUTLINE_THRESHOLD ${f(p.celOutlineThreshold)}
#define PP_SATURATION        ${f(p.celSaturation)}

vec4 hook() {
    const vec3 lw = vec3(0.2126, 0.7152, 0.0722);
    vec2 texel = 1.0 / HOOKED_size;
    vec3 centre = HOOKED_tex(HOOKED_pos).rgb;
    float luma = dot(centre, lw);

    vec2 near = texel * 1.5;
    vec2 far  = texel * 3.5;
    float a00 = dot(HOOKED_tex(HOOKED_pos + near * vec2(-1.0,-1.0)).rgb, lw);
    float a20 = dot(HOOKED_tex(HOOKED_pos + near * vec2( 1.0,-1.0)).rgb, lw);
    float a02 = dot(HOOKED_tex(HOOKED_pos + near * vec2(-1.0, 1.0)).rgb, lw);
    float a22 = dot(HOOKED_tex(HOOKED_pos + near * vec2( 1.0, 1.0)).rgb, lw);
    float b00 = dot(HOOKED_tex(HOOKED_pos + far  * vec2(-1.0,-1.0)).rgb, lw);
    float b20 = dot(HOOKED_tex(HOOKED_pos + far  * vec2( 1.0,-1.0)).rgb, lw);
    float b02 = dot(HOOKED_tex(HOOKED_pos + far  * vec2(-1.0, 1.0)).rgb, lw);
    float b22 = dot(HOOKED_tex(HOOKED_pos + far  * vec2( 1.0, 1.0)).rgb, lw);

    float gx = (a00 + a02) - (a20 + a22);
    float gy = (a00 + a20) - (a02 + a22);
    float gradient = sqrt(gx*gx + gy*gy);
    float ink = smoothstep(PP_OUTLINE_THRESHOLD, PP_OUTLINE_THRESHOLD + 0.04, gradient);

    float lighting = (a00+a20+a02+a22+b00+b20+b02+b22) * 0.125;
    float detail = luma - lighting;

    float softness = max(PP_BAND_EDGE, 0.001);
    float coord = lighting * PP_BANDS - softness * 0.5;
    float index = floor(coord) + smoothstep(1.0 - softness, 1.0, fract(coord));

    float centred  = (index + 0.5) / PP_BANDS;
    float stretched = index / max(PP_BANDS - 1.0, 1.0);
    float banded = clamp(mix(centred, stretched, PP_BAND_CONTRAST), 0.0, 1.0);
    float target = clamp(banded + detail * PP_DETAIL, 0.0, 1.0);
    float gain = min(target / max(luma, 0.001), 4.0);
    vec3 shaded = centre * gain;
    vec3 grey = vec3(dot(shaded, lw));
    vec3 color = mix(grey, shaded, PP_SATURATION);
    color *= 1.0 - ink * PP_OUTLINE_STRENGTH;
    return vec4(clamp(color, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden FilmicCurve.fx — toe/shoulder S-curve tone mapping. */
object FilmicCurveShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Filmic Curve (mpvRx)

#define PP_EXPOSURE ${f(p.filmicExposure)}
#define PP_TOE      ${f(p.filmicToe)}
#define PP_SHOULDER ${f(p.filmicShoulder)}
#define PP_AMOUNT   ${f(p.filmicAmount)}

vec4 hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    vec3 curved = clamp(rgb * PP_EXPOSURE, 0.0, 1.0);
    curved = pow(max(curved, 0.0), vec3(PP_TOE));
    curved = 1.0 - pow(max(1.0 - curved, 0.0), vec3(PP_SHOULDER));
    return vec4(clamp(mix(rgb, curved, PP_AMOUNT), 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden Blur.fx — spiral Poisson-disc blur with optional sharp centre. */
object BlurShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Blur (mpvRx)

#define PP_RADIUS       ${f(p.blurRadius)}
#define PP_STRENGTH     ${f(p.blurStrength)}
#define PP_FOCUS_SIZE   ${f(p.blurFocusSize)}
#define PP_FOCUS_SOFT   ${f(p.blurFocusSoftness)}
#define TAPS 17

const float TURN_C = cos(2.39996323);
const float TURN_S = sin(2.39996323);
const float DECAY  = exp(-2.0 / float(TAPS));

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec3 original = HOOKED_tex(HOOKED_pos).rgb;

    // Aspect-correct centred distance for focus mask
    vec2 centred = (HOOKED_pos - 0.5) * vec2(HOOKED_size.x / HOOKED_size.y, 1.0) * 2.0;
    float dist = length(centred);
    float noFocus = 1.0 - step(0.001, PP_FOCUS_SIZE);
    float focus = max(smoothstep(PP_FOCUS_SIZE, PP_FOCUS_SIZE + PP_FOCUS_SOFT, dist), noFocus);
    float amount = PP_STRENGTH * focus;

    // Deterministic per-pixel rotation seed
    float seed = fract(sin(dot(HOOKED_pos * HOOKED_size, vec2(12.9898, 78.233))) * 43758.5453);
    float angle = seed * 6.2831853;
    vec2 spoke = vec2(cos(angle), sin(angle));

    float weight = exp(-1.0 / float(TAPS));
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < TAPS; ++i) {
        float reach = sqrt((float(i) + 0.5) / float(TAPS));
        sum += HOOKED_tex(HOOKED_pos + spoke * reach * texel * PP_RADIUS).rgb * weight;
        total += weight;
        weight *= DECAY;
        vec2 newSpoke = vec2(spoke.x * TURN_C - spoke.y * TURN_S,
                             spoke.x * TURN_S + spoke.y * TURN_C);
        spoke = newSpoke;
    }
    vec3 blurred = sum / total;
    return vec4(clamp(mix(original, blurred, amount), 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden CartoonSoft.fx — shadow-aware ink outlines + smooth banding. */
object CartoonSoftShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Cartoon Soft (mpvRx)

#define PP_EDGE       ${f(p.cartoonSoftEdgeStrength)}
#define PP_SHADOW     ${f(p.cartoonSoftShadowGuard)}
#define PP_LEVELS     ${f(p.cartoonSoftLevels)}
#define PP_SMOOTHING  ${f(p.cartoonSoftSmoothing)}
#define PP_SAT        ${f(p.cartoonSoftSaturation)}

vec4 hook() {
    const vec3 lw = vec3(0.299, 0.587, 0.114);
    vec2 t = 1.0 / HOOKED_size;
    vec3 c00=HOOKED_tex(HOOKED_pos+t*vec2(-1,-1)).rgb;
    vec3 c10=HOOKED_tex(HOOKED_pos+t*vec2( 0,-1)).rgb;
    vec3 c20=HOOKED_tex(HOOKED_pos+t*vec2( 1,-1)).rgb;
    vec3 c01=HOOKED_tex(HOOKED_pos+t*vec2(-1, 0)).rgb;
    vec3 c11=HOOKED_tex(HOOKED_pos).rgb;
    vec3 c21=HOOKED_tex(HOOKED_pos+t*vec2( 1, 0)).rgb;
    vec3 c02=HOOKED_tex(HOOKED_pos+t*vec2(-1, 1)).rgb;
    vec3 c12=HOOKED_tex(HOOKED_pos+t*vec2( 0, 1)).rgb;
    vec3 c22=HOOKED_tex(HOOKED_pos+t*vec2( 1, 1)).rgb;
    const vec3 dt = vec3(1.0);
    float d1=dot(abs(c00-c22),dt); float d2=dot(abs(c20-c02),dt);
    float hl=dot(abs(c01-c21),dt); float vl=dot(abs(c10-c12),dt);
    float luma = dot(c11, lw);
    float response = (d1+d2+hl+vl) / (luma*2.0 + PP_SHADOW);
    float ink = 1.0 - clamp(response * PP_EDGE, 0.0, 1.0);
    float scaled = luma * PP_LEVELS;
    float sp = fract(scaled);
    float eased = sp*sp*(3.0-2.0*sp);
    float banded = (floor(scaled) + eased) / PP_LEVELS;
    float target = mix(luma, banded, PP_SMOOTHING);
    vec3 tinted = c11 * (target / max(luma, 0.001));
    vec3 grey = vec3(dot(tinted, lw));
    vec3 color = mix(grey, tinted, PP_SAT) * ink;
    return vec4(clamp(color, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden ChromaticAberration.fx — radial RGB channel separation. */
object ChromaticAberrationShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Chromatic Aberration (mpvRx)

#define PP_STRENGTH ${f(p.caStrength)}
#define PP_FALLOFF  ${f(p.caFalloff)}

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec2 dir = HOOKED_pos - vec2(0.5);
    float radius = length(dir);
    vec2 unit = dir / max(radius, 0.0001);
    vec2 offset = unit * PP_STRENGTH * pow(radius * 2.0, PP_FALLOFF) * texel;
    float r = HOOKED_tex(HOOKED_pos + offset).r;
    float g = HOOKED_tex(HOOKED_pos).g;
    float b = HOOKED_tex(HOOKED_pos - offset).b;
    return vec4(r, g, b, 1.0);
}
    """.trimIndent()
}

/** Port of Eden Deband.fx — Niklas Haas ring-sample + dither (MIT). */
object DebandShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Deband (mpvRx)

#define PP_THRESHOLD ${f(p.debandThreshold)}
#define PP_RADIUS    ${f(p.debandRadius)}
#define PP_GRAIN     ${f(p.debandGrain)}

float debandHash(vec2 p) {
    vec3 s = fract(vec3(p.x, p.y, p.x) * 0.1031);
    s += dot(s, s.yzx + 33.33);
    return fract((s.x + s.y) * s.z);
}

vec4 hook() {
    vec2 texel = 1.0 / HOOKED_size;
    vec3 centre = HOOKED_tex(HOOKED_pos).rgb;
    float base = debandHash(HOOKED_pos * HOOKED_size) * 6.2831853;
    vec3 total = vec3(0.0), deviation = vec3(0.0);
    for (int ring = 1; ring <= 2; ++ring) {
        float angle = base + float(ring) * 2.3999632;
        float reach  = PP_RADIUS * float(ring) * 0.5;
        vec2 along  = vec2(cos(angle), sin(angle)) * reach;
        vec2 across = vec2(-along.y, along.x);
        vec3 s0=HOOKED_tex(HOOKED_pos + along *texel).rgb;
        vec3 s1=HOOKED_tex(HOOKED_pos - along *texel).rgb;
        vec3 s2=HOOKED_tex(HOOKED_pos + across*texel).rgb;
        vec3 s3=HOOKED_tex(HOOKED_pos - across*texel).rgb;
        total += s0+s1+s2+s3;
        deviation = max(deviation, max(max(abs(s0-centre),abs(s1-centre)),
                                       max(abs(s2-centre),abs(s3-centre))));
    }
    vec3 average = total * 0.125;
    vec3 flatness = 1.0 - smoothstep(PP_THRESHOLD*0.5, PP_THRESHOLD, deviation);
    vec3 result = mix(centre, average, flatness);
    float dither = (debandHash(HOOKED_pos * HOOKED_size + vec2(71.3, 41.7)) - 0.5) * PP_GRAIN;
    return vec4(clamp(result + dither, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden FilmGrain.fx — midtone-weighted photographic grain. */
object FilmGrainShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Film Grain (mpvRx)

#define PP_INTENSITY ${f(p.grainIntensity)}
#define PP_SIZE      ${f(p.grainSize)}
#define PP_COLORED   ${f(p.grainColored)}

float grainHash(vec2 p) {
    vec3 s = fract(vec3(p.x, p.y, p.x) * 0.1031);
    s += dot(s, s.yzx + 33.33);
    return fract((s.x + s.y) * s.z);
}

vec4 hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    vec2 cell = floor(HOOKED_pos * HOOKED_size / max(PP_SIZE, 1.0));
    float mono = grainHash(cell) - 0.5;
    vec3 chroma = vec3(grainHash(cell+11.7), grainHash(cell+23.1), grainHash(cell+37.5)) - 0.5;
    vec3 noise = mix(vec3(mono), chroma, PP_COLORED);
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    float response = 1.0 - abs(luma * 2.0 - 1.0);
    return vec4(clamp(rgb + noise * PP_INTENSITY * response, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden Scanlines.fx — alternating row darkening with phosphor tint (PPSSPP). */
object ScanlinesShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Scanlines (mpvRx)

#define PP_DENSITY    ${f(p.scanlinesDensity)}
#define PP_INTENSITY  ${f(p.scanlinesIntensity)}
#define PP_TINT       ${f(p.scanlinesTint)}

vec4 hook() {
    float linePos = HOOKED_pos.y * PP_DENSITY * 0.5;
    float gate = cos((fract(linePos) - 0.5) * 3.1415926 * PP_INTENSITY) * 1.5;
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    vec3 color = rgb * 0.5 + 0.5 * rgb * rgb * 1.2;
    vec3 phosphor = mix(vec3(1.0), vec3(0.9, 1.0, 0.7), PP_TINT);
    color *= phosphor;
    vec2 diff = HOOKED_pos - 0.5;
    color *= 1.1 - 0.6 * (dot(diff, diff) * 2.0);
    return vec4(clamp(color * clamp(gate, 0.0, 1.0), 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden SplitToning.fx — tints shadows and highlights independently. */
object SplitToningShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Split Toning (mpvRx)

#define PP_SHADOW_HUE          ${f(p.splitShadowHue)}
#define PP_SHADOW_STRENGTH     ${f(p.splitShadowStrength)}
#define PP_HIGHLIGHT_HUE       ${f(p.splitHighlightHue)}
#define PP_HIGHLIGHT_STRENGTH  ${f(p.splitHighlightStrength)}
#define PP_BALANCE             ${f(p.splitBalance)}

vec3 hueToRGB(float hue) {
    float h = fract(hue / 360.0) * 6.0;
    return clamp(vec3(abs(h - 3.0) - 1.0, 2.0 - abs(h - 2.0), 2.0 - abs(h - 4.0)), 0.0, 1.0);
}

vec4 hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    float luma = clamp(dot(rgb, vec3(0.2126, 0.7152, 0.0722)) + PP_BALANCE, 0.0, 1.0);
    vec3 shadowTint    = hueToRGB(PP_SHADOW_HUE) - 0.5;
    vec3 highlightTint = hueToRGB(PP_HIGHLIGHT_HUE) - 0.5;
    rgb += shadowTint    * PP_SHADOW_STRENGTH    * 0.25 * (1.0 - luma);
    rgb += highlightTint * PP_HIGHLIGHT_STRENGTH * 0.25 * luma;
    return vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden Vignette.fx — corner darkening (PPSSPP by Henrik Rydgard). */
object VignetteShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC Vignette (mpvRx)

#define PP_STRENGTH ${f(p.vignetteStrength)}
#define PP_ASPECT   ${f(p.vignetteAspect)}

vec4 hook() {
    vec2 diff = HOOKED_pos - 0.5;
    diff.x *= PP_ASPECT;
    diff.y /= max(PP_ASPECT, 0.0001);
    float falloff = 1.0 - min(1.0, PP_STRENGTH * dot(diff, diff) * 2.0);
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    return vec4(rgb * falloff, 1.0);
}
    """.trimIndent()
}

/** Port of Eden WhiteBalance.fx — Bradford LMS chromatic adaptation. */
object WhiteBalanceShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC White Balance (mpvRx)

#define PP_TEMPERATURE ${f(p.wbTemperature)}
#define PP_TINT        ${f(p.wbTint)}

vec3 whitePointLMS(float t1, float t2) {
    float shift = (t1 < 0.0) ? 0.10 : 0.05;
    float x = 0.31271 - t1 * shift;
    float y = 2.87*x - 3.0*x*x - 0.27509507 + t2 * 0.05;
    float Y = 1.0, X = Y * x / y, Z = Y * (1.0 - x - y) / y;
    return vec3( 0.7328*X + 0.4296*Y - 0.1624*Z,
                -0.7036*X + 1.6975*Y + 0.0061*Z,
                 0.0030*X + 0.0136*Y + 0.9834*Z);
}

vec4 hook() {
    vec3 rgb = pow(max(HOOKED_tex(HOOKED_pos).rgb, 0.0), vec3(2.2));
    vec3 balance = vec3(0.949237, 1.03542, 1.08728) /
                   whitePointLMS(PP_TEMPERATURE / 65.0, PP_TINT / 65.0);
    // RGB <-> LMS (Hunt-Pointer-Estevez D65)
    mat3 rgb2lms = mat3(0.390405, 0.070841, 0.023108,
                        0.549941, 0.963172, 0.128021,
                        0.008926, 0.001358, 0.936245);
    mat3 lms2rgb = mat3( 2.858470, -0.210182, -0.041812,
                        -1.628790,  1.158200, -0.118169,
                        -0.024891,  0.000324,  1.068670);
    vec3 lms = rgb2lms * rgb * balance;
    rgb = lms2rgb * lms;
    return vec4(clamp(pow(max(rgb, 0.0), vec3(1.0/2.2)), 0.0, 1.0), 1.0);
}
    """.trimIndent()
}

/** Port of Eden CRT.fx — scanlines + colour bleed + optional rolling bar (PPSSPP by KillaMaaki). Uses HOOKED_time. */
object CRTShaderBuilder {
  fun build(p: PostProcessingParams): String =
    """
//!HOOK MAIN
//!BIND HOOKED
//!DESC CRT (mpvRx)

#define PP_DENSITY    ${f(p.crtDensity)}
#define PP_ROLL_SPEED ${f(p.crtRollSpeed)}
#define PP_BLEED      ${f(p.crtBleed)}

vec4 hook() {
    float seconds = HOOKED_time;
    vec2 texel = 1.0 / HOOKED_size;
    float scan = floor((HOOKED_pos.y + seconds * PP_ROLL_SPEED * 0.5) * PP_DENSITY);
    float lineIntensity = fract(scan * 0.5) * 2.0;
    vec2 shift = vec2(lineIntensity * 0.0005, 0.0);
    vec2 bleed = vec2(texel.x * PP_BLEED, 0.0);
    float r = HOOKED_tex(HOOKED_pos + bleed + shift).r;
    float g = HOOKED_tex(HOOKED_pos - bleed + shift).g;
    float b = HOOKED_tex(HOOKED_pos).b;
    vec3 color = vec3(r, g * 0.99, b) * clamp(lineIntensity, 0.85, 1.0);
    if (PP_ROLL_SPEED > 0.0) {
        float rollbar = sin((HOOKED_pos.y + seconds * PP_ROLL_SPEED) * 4.0);
        color += rollbar * 0.02;
    }
    return vec4(clamp(color, 0.0, 1.0), 1.0);
}
    """.trimIndent()
}
