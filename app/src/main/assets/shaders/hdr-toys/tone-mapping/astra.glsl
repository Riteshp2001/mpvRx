// Astra, a tone mapping operator designed to preserve the creator's intent

//!PARAM PTS
//!TYPE float
0.0

//!PARAM min_luma
//!TYPE float
0.0

//!PARAM max_luma
//!TYPE float
0.0

//!PARAM max_cll
//!TYPE float
0.0

//!PARAM max_fall
//!TYPE float
0.0

//!PARAM scene_max_r
//!TYPE float
0.0

//!PARAM scene_max_g
//!TYPE float
0.0

//!PARAM scene_max_b
//!TYPE float
0.0

//!PARAM scene_avg
//!TYPE float
0.0

//!PARAM max_pq_y
//!TYPE float
0.0

//!PARAM avg_pq_y
//!TYPE float
0.0

//!PARAM reference_white
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 1000.0
203.0

//!PARAM contrast_ratio
//!TYPE float
//!MINIMUM 10.0
//!MAXIMUM 100000000.0
1000.0

//!PARAM auto_exposure_anchor
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.6

//!PARAM auto_exposure_limit_negative
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 5.0
2.3

//!PARAM auto_exposure_limit_positive
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 5.0
0.0

//!PARAM auto_exposure_limit_input
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
1

//!PARAM exposure_value
//!TYPE float
//!MINIMUM -64
//!MAXIMUM  64
0.0

//!PARAM shadow_weight
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.4

//!PARAM highlight_weight
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.1

//!PARAM highlight_overshoot
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
1.0

//!PARAM contrast_bias
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM  1.0
0.0

//!PARAM hk_effect_compensate_scaling
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM chroma_correction_scaling
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 5.0
1.0

//!PARAM chroma_correction_rate
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 5.0
1.0

//!PARAM chroma_correction_threshold
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM spatial_stable_iterations
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 8
2

//!PARAM temporal_stable_duration
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
0.33

//!PARAM temporal_stable_scene_change
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
1

//!PARAM enable_metering
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 2
2

//!PARAM preview_metering
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!BUFFER METERED
//!VAR uint metered_max_i
//!VAR uint metered_max_rgb
//!VAR uint metered_min_i
//!VAR uint metered_avg_i
//!VAR uint metered_histogram[1024]
//!VAR uint metered_coarse_histogram[64]
//!VAR float metered_zone_average[144]
//!VAR float metered_zone_spread[144]
//!VAR uint metered_zone_valid
//!STORAGE

//!BUFFER METERED_TEMPORAL
//!VAR float metered_reference_histogram[64]
//!VAR float metered_previous_histogram[64]
//!VAR uint metered_histogram_valid
//!VAR uint metered_temporal_pts
//!VAR uint metered_scene_candidate_start_pts
//!VAR uint metered_scene_candidate_active
//!VAR uint metered_scene_adaptation_end_pts
//!VAR uint metered_scene_fast_response
//!STORAGE

//!BUFFER EXPOSURE_TEMPORAL
//!VAR float smoothed_ev
//!VAR uint smoothed_ev_pts
//!VAR uint smoothed_ev_valid
//!STORAGE

//!BUFFER CURVE_TEMPORAL
//!VAR float smoothed_curve[1024]
//!VAR float curve_temporal_alpha
//!VAR uint curve_temporal_reset
//!VAR uint curve_temporal_pts
//!VAR uint curve_temporal_valid
//!STORAGE

//!BUFFER METADATA
//!VAR float max_i
//!VAR float max_rgb
//!VAR float min_i
//!VAR float avg_i
//!VAR float input_max_i
//!VAR float input_min_i
//!VAR float input_avg_i
//!VAR float ev
//!VAR float exposure_scale
//!VAR float output_black_j
//!VAR float output_white_j
//!STORAGE

//!BUFFER VECTORSCOPE
//!VAR uint vectorscope_histogram[16384]
//!VAR uint vectorscope_color_r[16384]
//!VAR uint vectorscope_color_g[16384]
//!VAR uint vectorscope_color_b[16384]
//!STORAGE

//!BUFFER PREVIEW_HISTOGRAM
//!VAR float preview_histogram_current[64]
//!VAR float preview_histogram_reference[64]
//!VAR float preview_histogram_curve[256]
//!STORAGE

//!HOOK OUTPUT
//!BIND HOOKED
//!SAVE METERING
//!COMPONENTS 2
//!WHEN enable_metering 0 > max_pq_y 0 = * scene_max_r 0 = * scene_max_g 0 = * scene_max_b 0 = * preview_metering +
//!DESC metering (intensity map)

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

float pq_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

float RGB_to_Y(vec3 rgb) {
    const vec3 coefficients = vec3(0.2627002120112671, 0.6779980715188708, 0.05930171646986196);
    return dot(rgb, coefficients);
}

float metering_intensity(vec3 rgb) {
    float y = RGB_to_Y(rgb);
    float y_abs = clamp(y * reference_white, 0.0, pw);
    return pq_eotf_inv(y_abs);
}

float metering_max_rgb(vec3 rgb) {
    float maximum = max(max(rgb.r, rgb.g), rgb.b);
    float maximum_abs = clamp(maximum * reference_white, 0.0, pw);
    return pq_eotf_inv(maximum_abs);
}

vec4 hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    return vec4(
        metering_intensity(rgb),
        metering_max_rgb(rgb),
        0.0,
        1.0
    );
}

// The metering map used to be reduced to 512x288 in a single step. At 4K that
// is a factor of 7.5 per axis taken with one bilinear tap, i.e. point sampling
// with aliasing: which pixels survive depends on the subpixel alignment, so a
// small moving highlight makes the measured peak jump while nothing in the
// scene changes. Halving repeatedly instead averages exactly 2x2 per step before
// the fixed-size histogram and matrix analysis. The passes are conditional,
// so only as many run as the source resolution needs: two at 4K, one at 1080p.
// Testing both dimensions against both landscape thresholds makes the chain
// orientation-independent before portrait analysis is rotated below.

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 1024 > OUTPUT.h 1024 > + OUTPUT.w 576 > OUTPUT.h 576 > * +
//!DESC metering (spatial stabilization, halve 1)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 2048 > OUTPUT.h 2048 > + OUTPUT.w 1152 > OUTPUT.h 1152 > * +
//!DESC metering (spatial stabilization, halve 2)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 4096 > OUTPUT.h 4096 > + OUTPUT.w 2304 > OUTPUT.h 2304 > * +
//!DESC metering (spatial stabilization, halve 3)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 8192 > OUTPUT.h 8192 > + OUTPUT.w 4608 > OUTPUT.h 4608 > * +
//!DESC metering (spatial stabilization, halve 4)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH 512
//!HEIGHT 288
//!DESC metering (spatial stabilization, downscaling)

vec2 metering_source_position(vec2 position, bool portrait) {
    // Rotate portrait analysis clockwise into the landscape metering layout.
    return portrait
        ? vec2(position.y, 1.0 - position.x)
        : position;
}

vec4 sample_metering_oriented(vec2 position, bool portrait) {
    return METERING_mul * textureLod(
        METERING_raw,
        metering_source_position(position, portrait),
        0.0
    );
}

vec4 sample_metering_downscaled() {
    const vec2 target_size = vec2(512.0, 288.0);
    bool portrait = METERING_size.y > METERING_size.x;
    vec2 oriented_size = portrait ? METERING_size.yx : METERING_size;
    vec2 scale = oriented_size / target_size;

    if (all(lessThanEqual(scale, vec2(1.0)))) {
        return sample_metering_oriented(METERING_pos, portrait);
    }

    // Extend the bilinear footprint to approximate an area average. At 2x
    // downscaling the four taps land at the centers of the source 2x2 block.
    vec2 offset = 0.5 * max(scale - vec2(1.0), vec2(0.0));
    vec2 normalized_offset = offset / oriented_size;
    vec4 sum = sample_metering_oriented(
                   METERING_pos + vec2(-normalized_offset.x,
                                       -normalized_offset.y),
                   portrait
               )
             + sample_metering_oriented(
                   METERING_pos + vec2( normalized_offset.x,
                                       -normalized_offset.y),
                   portrait
               )
             + sample_metering_oriented(
                   METERING_pos + vec2(-normalized_offset.x,
                                        normalized_offset.y),
                   portrait
               )
             + sample_metering_oriented(
                   METERING_pos + vec2( normalized_offset.x,
                                        normalized_offset.y),
                   portrait
               );
    return sum * 0.25;
}

vec4 hook() { return sample_metering_downscaled(); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 0 >
//!DESC metering (spatial stabilization, blur, horizontal)

// Efficient Gaussian blur with linear sampling
// by Daniel Rákos
// https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 0 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 1 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 1 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 2 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 2 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 3 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 3 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 4 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 4 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 5 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 5 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 6 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 6 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 7 >
//!DESC metering (spatial stabilization, blur, horizontal)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(1.0, 0.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_iterations 7 >
//!DESC metering (spatial stabilization, blur, vertical)

const vec3 offset = vec3(0.0000000000, 1.3846153846, 3.2307692308);
const vec3 weight = vec3(0.2270270270, 0.3162162162, 0.0702702703);
const vec2 direction = vec2(0.0, 1.0);

vec4 hook() {
    vec4 c = METERING_tex(METERING_pos) * weight[0];
    for (uint i = 1; i < 3; i++) {
        c += METERING_texOff( direction * offset[i]) * weight[i];
        c += METERING_texOff(-direction * offset[i]) * weight[i];
    }
    return c;
}

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH 1024
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!DESC metering (histogram, init)

void clear_metering_histogram_bin(uint index) {
    if (index < 1024u)
        metered_histogram[index] = 0u;
    if (index == 0u) {
        metered_max_rgb = 0u;
        metered_zone_valid = 0u;
    }
}

void hook() {
    uint index = gl_GlobalInvocationID.x;
    clear_metering_histogram_bin(index);
}

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!COMPUTE 32 32 16 16
//!DESC metering (histogram)

shared uint shistogram[1024];
shared uint smax_rgb;

uint to_uint(float x) {
    return uint(x * 4095.0 + 0.5);
}

uint to_histogram_bin(float x) {
    return min(to_uint(x) >> 2u, 1023u);
}

vec2 fetch_metering(ivec2 position) {
    return (METERING_mul * texelFetch(METERING_raw, position, 0)).xy;
}

void fetch_metering_quad(
    ivec2 position,
    out vec4 intensities,
    out vec4 maxima
) {
    vec2 sample0 = fetch_metering(position);
    vec2 sample1 = fetch_metering(position + ivec2(1, 0));
    vec2 sample2 = fetch_metering(position + ivec2(0, 1));
    vec2 sample3 = fetch_metering(position + ivec2(1, 1));
    intensities = vec4(sample0.x, sample1.x, sample2.x, sample3.x);
    maxima = vec4(sample0.y, sample1.y, sample2.y, sample3.y);
}

void clear_workgroup_histogram(uint tid) {
    for (uint i = tid; i < 1024u; i += 256u)
        shistogram[i] = 0u;
    if (tid == 0u)
        smax_rgb = 0u;
}

void accumulate_workgroup_metering(vec4 intensities, vec4 maxima) {
    atomicAdd(shistogram[to_histogram_bin(intensities.x)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.y)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.z)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.w)], 1u);
    atomicMax(
        smax_rgb,
        max(max(to_uint(maxima.x), to_uint(maxima.y)),
            max(to_uint(maxima.z), to_uint(maxima.w)))
    );
}

void merge_workgroup_histogram(uint tid) {
    // Accumulate locally first. This replaces one contended global atomic per
    // metering pixel with at most one global merge per non-empty workgroup bin.
    for (uint i = tid; i < 1024u; i += 256u) {
        uint count = shistogram[i];
        if (count > 0u)
            atomicAdd(metered_histogram[i], count);
    }
    if (tid == 0u)
        atomicMax(metered_max_rgb, smax_rgb);
}

void hook() {
    ivec2 block_base = ivec2(gl_WorkGroupID.xy) * 32;
    ivec2 position = block_base + ivec2(gl_LocalInvocationID.xy) * 2;
    vec4 intensities;
    vec4 maxima;
    fetch_metering_quad(position, intensities, maxima);
    uint tid = gl_LocalInvocationIndex;

    clear_workgroup_histogram(tid);
    barrier();

    accumulate_workgroup_metering(intensities, maxima);
    barrier();

    merge_workgroup_histogram(tid);
}

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH 256
//!HEIGHT 144
//!COMPUTE 16 16 16 16
//!WHEN auto_exposure_anchor 0 > enable_metering 1 > * avg_pq_y 0 = * scene_avg 0 = * preview_metering enable_metering 1 > * +
//!DESC metering (matrix zones)

// A 256x144 analysis grid maps exactly to 16x9 workgroups. Each workgroup
// builds a compact histogram for one image zone, then publishes a robust mean
// and its P10-P90 spread for the matrix reduction below.
const uint MATRIX_ZONE_COLUMNS = 16u;
const uint MATRIX_ZONE_ROWS = 9u;
const uint MATRIX_ZONE_COUNT = MATRIX_ZONE_COLUMNS * MATRIX_ZONE_ROWS;
const uint MATRIX_ZONE_SAMPLE_COUNT = 16u * 16u;
const uint MATRIX_ZONE_HISTOGRAM_SIZE = 64u;
// Pack a 15-bit PQ sum and a 9-bit sample count into each histogram uint.
// A bin can contain all 256 samples without a count carry, while the maximum
// packed sum remains below uint overflow. This preserves sub-bin precision
// without adding a second shared atomic per sample.
const uint MATRIX_ZONE_COUNT_BITS = 9u;
const uint MATRIX_ZONE_COUNT_MASK =
    (1u << MATRIX_ZONE_COUNT_BITS) - 1u;
const uint MATRIX_ZONE_HISTOGRAM_SHIFT = 9u;
const float MATRIX_ZONE_VALUE_SCALE = 32767.0;
const float MATRIX_ZONE_TRIM_PERCENTILE = 0.05;
const float MATRIX_ZONE_LOW_PERCENTILE = 0.10;
const float MATRIX_ZONE_HIGH_PERCENTILE = 0.90;
const vec2 MATRIX_METERING_SIZE = vec2(256.0, 144.0);

// Each entry contains (sum_of_15_bit_values << 9) | sample_count.
shared uint zone_histogram[MATRIX_ZONE_HISTOGRAM_SIZE];

uint matrix_zone_value_code(float value) {
    return uint(clamp(value, 0.0, 1.0) *
                MATRIX_ZONE_VALUE_SCALE + 0.5);
}

uint matrix_zone_histogram_bin(uint value_code) {
    return value_code >> MATRIX_ZONE_HISTOGRAM_SHIFT;
}

uint matrix_zone_packed_sample(uint value_code) {
    return (value_code << MATRIX_ZONE_COUNT_BITS) + 1u;
}

uint matrix_zone_bin_count(uint packed_value) {
    return packed_value & MATRIX_ZONE_COUNT_MASK;
}

float matrix_zone_retained_sum(
    uint packed_value,
    uint sample_count,
    uint retained
) {
    if (retained == 0u || sample_count == 0u)
        return 0.0;

    uint value_sum = packed_value >> MATRIX_ZONE_COUNT_BITS;
    float retained_fraction = retained == sample_count
        ? 1.0
        : float(retained) / float(sample_count);
    return float(value_sum) / MATRIX_ZONE_VALUE_SCALE *
           retained_fraction;
}

float sample_matrix_metering(vec2 position) {
    return (
        METERING_mul * textureLod(METERING_raw, position, 0.0)
    ).x;
}

void clear_zone_histogram(uint tid) {
    if (tid < MATRIX_ZONE_HISTOGRAM_SIZE)
        zone_histogram[tid] = 0u;
}

uint retained_zone_count(
    uint cumulative_before,
    uint cumulative,
    uint lower_target,
    uint upper_target
) {
    uint first = max(cumulative_before, lower_target);
    uint last = min(cumulative, upper_target);
    return last > first ? last - first : 0u;
}

void publish_matrix_zone(uint zone_index) {
    uint trim = uint(floor(
        float(MATRIX_ZONE_SAMPLE_COUNT) * MATRIX_ZONE_TRIM_PERCENTILE
    ));
    uint lower_target = trim;
    uint upper_target = MATRIX_ZONE_SAMPLE_COUNT - trim;
    uint low_target = max(
        uint(ceil(float(MATRIX_ZONE_SAMPLE_COUNT) *
                  MATRIX_ZONE_LOW_PERCENTILE)),
        1u
    );
    uint high_target = max(
        uint(ceil(float(MATRIX_ZONE_SAMPLE_COUNT) *
                  MATRIX_ZONE_HIGH_PERCENTILE)),
        1u
    );

    uint cumulative = 0u;
    uint low_bin = 0u;
    uint high_bin = MATRIX_ZONE_HISTOGRAM_SIZE - 1u;
    float sum = 0.0;

    for (uint i = 0u; i < MATRIX_ZONE_HISTOGRAM_SIZE; i++) {
        uint packed_value = zone_histogram[i];
        uint sample_count = matrix_zone_bin_count(packed_value);
        uint next = cumulative + sample_count;
        uint retained = retained_zone_count(
            cumulative,
            next,
            lower_target,
            upper_target
        );
        sum += matrix_zone_retained_sum(
            packed_value,
            sample_count,
            retained
        );

        if (cumulative < low_target && next >= low_target)
            low_bin = i;
        if (cumulative < high_target && next >= high_target)
            high_bin = i;

        cumulative = next;
    }

    uint retained_total = MATRIX_ZONE_SAMPLE_COUNT - 2u * trim;
    metered_zone_average[zone_index] = sum /
                                       float(max(retained_total, 1u));
    metered_zone_spread[zone_index] =
        float(high_bin - low_bin) /
        float(MATRIX_ZONE_HISTOGRAM_SIZE - 1u);
}

void analyze_matrix_zone() {
    uint tid = gl_LocalInvocationIndex;
    vec2 position = (vec2(gl_GlobalInvocationID.xy) + 0.5) /
                    MATRIX_METERING_SIZE;

    clear_zone_histogram(tid);
    barrier();

    float value = sample_matrix_metering(position);
    uint value_code = matrix_zone_value_code(value);
    atomicAdd(
        zone_histogram[matrix_zone_histogram_bin(value_code)],
        matrix_zone_packed_sample(value_code)
    );
    barrier();

    if (tid == 0u) {
        uint zone_index = gl_WorkGroupID.y * MATRIX_ZONE_COLUMNS +
                          gl_WorkGroupID.x;
        if (zone_index < MATRIX_ZONE_COUNT) {
            publish_matrix_zone(zone_index);
            if (zone_index == 0u)
                metered_zone_valid = 1u;
        }
    }
}

void hook() { analyze_matrix_zone(); }

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH 256
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!DESC metering (histogram, reduction)

const uint METERING_HISTOGRAM_SIZE = 1024u;
const uint METERING_REDUCTION_SIZE = 256u;
const uint METERING_BINS_PER_THREAD = 4u;
const uint METERING_COARSE_HISTOGRAM_SIZE = 64u;
const uint METERING_BLOCKS_PER_COARSE_BIN = 4u;
const uint METERING_SAMPLE_COUNT = 512u * 288u;
const uint METERING_ZONE_COLUMNS = 16u;
const uint METERING_ZONE_ROWS = 9u;
const uint METERING_ZONE_COUNT = METERING_ZONE_COLUMNS * METERING_ZONE_ROWS;
const float METERING_BLACK_PERCENTILE = 0.005;
// A robust white point constrains automatic exposure without allowing one
// unstable highlight sample to move the whole frame. The maximum RGB channel
// is measured separately to define the tone-curve endpoint.
const float METERING_WHITE_PERCENTILE = 0.995;
const float METERING_AVERAGE_TRIM_PERCENTILE = 0.05;
const float METERING_MATRIX_WEIGHT_MIN = 0.50;
const float METERING_MATRIX_WEIGHT_MAX = 0.75;
const float METERING_MATRIX_DIFFERENCE_MIN = 0.05;
const float METERING_MATRIX_DIFFERENCE_MAX = 0.20;
const float METERING_MATRIX_SPATIAL_SCALE = 3.0;
const float METERING_MATRIX_SPREAD_MIN = 0.06;
const float METERING_MATRIX_SPREAD_MAX = 0.30;
const float METERING_MATRIX_COHERENCE_MIN = 0.03;
const float METERING_MATRIX_COHERENCE_MAX = 0.18;
const float METERING_BORDER_BLACK_MAX = 0.02;
const float METERING_BORDER_BLACK_RELATIVE_SCALE = 0.10;
const float METERING_BORDER_SPREAD_MAX = 0.015;
const float METERING_BORDER_GLOBAL_MIN = 0.02;
const float METERING_BORDER_OCCUPANCY_MIN = 0.80;
const uint METERING_ACTIVE_COLUMNS_MIN = 4u;
const uint METERING_ACTIVE_ROWS_MIN = 3u;
const float METERING_MATRIX_BORDER_WEIGHT = 0.95;

shared uint histogram_prefix[METERING_REDUCTION_SIZE];
shared float average_partial[METERING_REDUCTION_SIZE];
shared vec2 matrix_partial[METERING_REDUCTION_SIZE];
shared float robust_global_average;
shared uint black_bin;
shared uint white_bin;
shared uvec4 matrix_active_bounds;
shared float matrix_border_confidence;

uvec4 load_histogram_block(uint first) {
    return uvec4(
        metered_histogram[first],
        metered_histogram[first + 1u],
        metered_histogram[first + 2u],
        metered_histogram[first + 3u]
    );
}

uint sum_histogram_block(uvec4 counts) {
    return counts.x + counts.y + counts.z + counts.w;
}

void scan_histogram_blocks(uint tid, uint block_count) {
    histogram_prefix[tid] = block_count;
    barrier();

    for (uint offset = 1u; offset < METERING_REDUCTION_SIZE; offset <<= 1u) {
        uint inclusive = histogram_prefix[tid];
        if (tid >= offset)
            inclusive += histogram_prefix[tid - offset];
        barrier();
        histogram_prefix[tid] = inclusive;
        barrier();
    }
}

uint retained_histogram_count(
    uint cumulative_before,
    uint cumulative,
    uint lower_target,
    uint upper_target
) {
    uint first = max(cumulative_before, lower_target);
    uint last = min(cumulative, upper_target);
    return last > first ? last - first : 0u;
}

uvec2 average_trim_targets(uint total) {
    uint trim = uint(
        floor(float(total) * METERING_AVERAGE_TRIM_PERCENTILE)
    );
    return uvec2(trim, total - trim);
}

float global_histogram_average_partial(
    uvec4 counts,
    uint first,
    uint cumulative_before,
    uvec2 targets
) {
    float sum = 0.0;
    uint cumulative = cumulative_before;

    for (uint i = 0u; i < METERING_BINS_PER_THREAD; i++) {
        uint next = cumulative + counts[i];
        uint retained = retained_histogram_count(
            cumulative,
            next,
            targets.x,
            targets.y
        );
        float value = (float((first + i) << 2u) + 1.5) / 4095.0;
        sum += value * float(retained);
        cumulative = next;
    }

    return sum;
}

void reduce_average_partials(uint tid, float partial) {
    average_partial[tid] = partial;
    barrier();

    for (uint size = METERING_REDUCTION_SIZE >> 1u;
         size > 0u;
         size >>= 1u) {
        if (tid < size)
            average_partial[tid] += average_partial[tid + size];
        barrier();
    }
}

uint pq_to_uint(float value) {
    return uint(clamp(value, 0.0, 1.0) * 4095.0 + 0.5);
}

// Detect only near-zero, internally uniform zones. Requiring edge occupancy
// below makes the crop follow presentation bars instead of dark objects inside
// the picture; the whole-frame average guard avoids classifying a dark shot.
bool matrix_zone_looks_like_border(uint index) {
    float black_limit = min(
        METERING_BORDER_BLACK_MAX,
        robust_global_average * METERING_BORDER_BLACK_RELATIVE_SCALE
    );
    return robust_global_average > METERING_BORDER_GLOBAL_MIN &&
           metered_zone_average[index] <= black_limit &&
           metered_zone_spread[index] <= METERING_BORDER_SPREAD_MAX;
}

float matrix_column_black_fraction(uint x) {
    uint count = 0u;
    for (uint y = 0u; y < METERING_ZONE_ROWS; y++) {
        uint index = y * METERING_ZONE_COLUMNS + x;
        if (matrix_zone_looks_like_border(index))
            count++;
    }
    return float(count) / float(METERING_ZONE_ROWS);
}

float matrix_row_black_fraction(uint y, uint left, uint right) {
    uint count = 0u;
    for (uint x = left; x < right; x++) {
        uint index = y * METERING_ZONE_COLUMNS + x;
        if (matrix_zone_looks_like_border(index))
            count++;
    }
    return float(count) / float(max(right - left, 1u));
}

void prepare_matrix_active_region(uint tid) {
    if (tid == 0u) {
        uint left = 0u;
        uint right = METERING_ZONE_COLUMNS;
        uint top = 0u;
        uint bottom = METERING_ZONE_ROWS;

        if (metered_zone_valid > 0u) {
            for (uint x = 0u; x < METERING_ZONE_COLUMNS; x++) {
                if (matrix_column_black_fraction(x) <
                    METERING_BORDER_OCCUPANCY_MIN) {
                    break;
                }
                left = x + 1u;
            }
            for (int x = int(METERING_ZONE_COLUMNS) - 1; x >= 0; x--) {
                if (matrix_column_black_fraction(uint(x)) <
                    METERING_BORDER_OCCUPANCY_MIN) {
                    break;
                }
                right = uint(x);
            }

            if (right > left &&
                right - left >= METERING_ACTIVE_COLUMNS_MIN) {
                for (uint y = 0u; y < METERING_ZONE_ROWS; y++) {
                    if (matrix_row_black_fraction(y, left, right) <
                        METERING_BORDER_OCCUPANCY_MIN) {
                        break;
                    }
                    top = y + 1u;
                }
                for (int y = int(METERING_ZONE_ROWS) - 1; y >= 0; y--) {
                    if (matrix_row_black_fraction(
                            uint(y), left, right
                        ) < METERING_BORDER_OCCUPANCY_MIN) {
                        break;
                    }
                    bottom = uint(y);
                }
            }
        }

        bool valid_bounds = right > left && bottom > top &&
                            right - left >= METERING_ACTIVE_COLUMNS_MIN &&
                            bottom - top >= METERING_ACTIVE_ROWS_MIN;
        if (!valid_bounds) {
            left = 0u;
            right = METERING_ZONE_COLUMNS;
            top = 0u;
            bottom = METERING_ZONE_ROWS;
        }

        matrix_active_bounds = uvec4(left, right, top, bottom);
        float active_area = float((right - left) * (bottom - top));
        float removed_fraction = 1.0 - active_area /
                                 float(METERING_ZONE_COUNT);
        matrix_border_confidence = valid_bounds
            ? smoothstep(0.05, 0.30, removed_fraction)
            : 0.0;
    }
    barrier();
}

bool matrix_zone_inside_active_region(uint x, uint y) {
    return x >= matrix_active_bounds.x &&
           x < matrix_active_bounds.y &&
           y >= matrix_active_bounds.z &&
           y < matrix_active_bounds.w;
}

float matrix_zone_neighbor_difference(uint index, float value) {
    uint x = index % METERING_ZONE_COLUMNS;
    uint y = index / METERING_ZONE_COLUMNS;
    float difference = 0.0;
    uint count = 0u;

    if (x > matrix_active_bounds.x) {
        difference += abs(value - metered_zone_average[index - 1u]);
        count++;
    }
    if (x + 1u < matrix_active_bounds.y) {
        difference += abs(value - metered_zone_average[index + 1u]);
        count++;
    }
    if (y > matrix_active_bounds.z) {
        difference += abs(
            value - metered_zone_average[index - METERING_ZONE_COLUMNS]
        );
        count++;
    }
    if (y + 1u < matrix_active_bounds.w) {
        difference += abs(
            value - metered_zone_average[index + METERING_ZONE_COLUMNS]
        );
        count++;
    }

    return difference / float(max(count, 1u));
}

vec2 matrix_zone_partial(uint index, float global_average) {
    if (metered_zone_valid == 0u || index >= METERING_ZONE_COUNT)
        return vec2(0.0);

    uint x = index % METERING_ZONE_COLUMNS;
    uint y = index / METERING_ZONE_COLUMNS;
    if (!matrix_zone_inside_active_region(x, y))
        return vec2(0.0);

    float zone_average = metered_zone_average[index];
    float zone_spread = metered_zone_spread[index];
    vec2 active_origin = vec2(
        float(matrix_active_bounds.x),
        float(matrix_active_bounds.z)
    );
    vec2 active_size = vec2(
        float(matrix_active_bounds.y - matrix_active_bounds.x),
        float(matrix_active_bounds.w - matrix_active_bounds.z)
    );
    vec2 position = (vec2(float(x), float(y)) + 0.5 - active_origin) /
                    active_size;
    vec2 centered = 2.0 * position - vec2(1.0);
    float center_emphasis = exp2(-2.0 * dot(centered, centered));
    float difference = abs(zone_average - global_average);
    float subject_evidence = smoothstep(
        METERING_MATRIX_DIFFERENCE_MIN,
        METERING_MATRIX_DIFFERENCE_MAX,
        difference
    );
    float spatial_weight = 1.0 + METERING_MATRIX_SPATIAL_SCALE *
                           center_emphasis *
                           mix(0.5, 1.0, subject_evidence);

    // Mixed zones and isolated outliers are less reliable than coherent image
    // regions, but neither can be discarded completely.
    float spread_reliability = 1.0 - 0.5 * smoothstep(
        METERING_MATRIX_SPREAD_MIN,
        METERING_MATRIX_SPREAD_MAX,
        zone_spread
    );
    float neighbor_difference = matrix_zone_neighbor_difference(
        index,
        zone_average
    );
    float coherence_reliability = 1.0 - 0.5 * smoothstep(
        METERING_MATRIX_COHERENCE_MIN,
        METERING_MATRIX_COHERENCE_MAX,
        neighbor_difference
    );
    float weight = spatial_weight * spread_reliability *
                   coherence_reliability;
    return vec2(zone_average * weight, weight);
}

void reduce_matrix_partials(uint tid, vec2 partial) {
    matrix_partial[tid] = partial;
    barrier();

    for (uint size = METERING_REDUCTION_SIZE >> 1u;
         size > 0u;
         size >>= 1u) {
        if (tid < size)
            matrix_partial[tid] += matrix_partial[tid + size];
        barrier();
    }
}

void publish_matrix_average(uint tid) {
    if (tid != 0u)
        return;

    float matrix_average = matrix_partial[0].y > 0.0
        ? matrix_partial[0].x / matrix_partial[0].y
        : robust_global_average;

    // A stronger matrix/global disagreement suggests an intentionally framed
    // or backlit subject. Keep at least 25% of the whole-frame estimate so the
    // decision cannot collapse onto a small central region.
    float difference = abs(matrix_average - robust_global_average);
    float matrix_confidence = smoothstep(
        METERING_MATRIX_DIFFERENCE_MIN,
        METERING_MATRIX_DIFFERENCE_MAX,
        difference
    );
    float matrix_weight = mix(
        METERING_MATRIX_WEIGHT_MIN,
        METERING_MATRIX_WEIGHT_MAX,
        matrix_confidence
    );
    // Edge-connected, uniform black bars are presentation geometry rather
    // than scene content. When their evidence is strong, rely almost entirely
    // on the active-region matrix average while retaining a small whole-frame
    // contribution as a guard against false detection.
    matrix_weight = mix(
        matrix_weight,
        METERING_MATRIX_BORDER_WEIGHT,
        matrix_border_confidence
    );
    metered_avg_i = pq_to_uint(
        mix(robust_global_average, matrix_average, matrix_weight)
    );
}

uint find_percentile_bin(
    uvec4 counts,
    uint first,
    uint cumulative,
    uint target
) {
    for (uint i = 0u; i < METERING_BINS_PER_THREAD; i++) {
        cumulative += counts[i];
        if (cumulative >= target)
            return first + i;
    }
    return first + METERING_BINS_PER_THREAD - 1u;
}

void publish_coarse_histogram(uint tid) {
    if (tid >= METERING_COARSE_HISTOGRAM_SIZE)
        return;

    uint last_block = (tid + 1u) * METERING_BLOCKS_PER_COARSE_BIN - 1u;
    uint first_block = tid * METERING_BLOCKS_PER_COARSE_BIN;
    uint cumulative_before = first_block == 0u
        ? 0u
        : histogram_prefix[first_block - 1u];
    metered_coarse_histogram[tid] =
        histogram_prefix[last_block] - cumulative_before;
}

void locate_percentiles(uint tid, uint first, uvec4 counts) {
    uint cumulative_before = tid == 0u ? 0u : histogram_prefix[tid - 1u];
    uint cumulative = histogram_prefix[tid];
    uint black_target = max(
        uint(ceil(float(METERING_SAMPLE_COUNT) * METERING_BLACK_PERCENTILE)),
        1u
    );
    uint white_target = max(
        uint(ceil(float(METERING_SAMPLE_COUNT) * METERING_WHITE_PERCENTILE)),
        1u
    );
    if (cumulative_before < black_target && cumulative >= black_target) {
        black_bin = find_percentile_bin(
            counts,
            first,
            cumulative_before,
            black_target
        );
    }
    if (cumulative_before < white_target && cumulative >= white_target) {
        white_bin = find_percentile_bin(
            counts,
            first,
            cumulative_before,
            white_target
        );
    }
}

void reduce_metering_histogram() {
    uint tid = gl_LocalInvocationIndex;
    uint first = tid * METERING_BINS_PER_THREAD;
    uvec4 counts = load_histogram_block(first);

    if (tid == 0u) {
        black_bin = 0u;
        white_bin = METERING_HISTOGRAM_SIZE - 1u;
    }

    scan_histogram_blocks(tid, sum_histogram_block(counts));
    publish_coarse_histogram(tid);
    locate_percentiles(tid, first, counts);

    uint global_total = histogram_prefix[METERING_REDUCTION_SIZE - 1u];
    uvec2 global_targets = average_trim_targets(global_total);
    uint global_before = tid == 0u ? 0u : histogram_prefix[tid - 1u];

    reduce_average_partials(
        tid,
        global_histogram_average_partial(
            counts,
            first,
            global_before,
            global_targets
        )
    );

    if (tid == 0u) {
        uint global_retained = global_total - 2u * global_targets.x;
        robust_global_average = average_partial[0] /
                                float(max(global_retained, 1u));
    }
    barrier();

    prepare_matrix_active_region(tid);

    reduce_matrix_partials(
        tid,
        matrix_zone_partial(tid, robust_global_average)
    );

    if (tid == 0u) {
        metered_min_i = black_bin << 2u;
        metered_max_i = min((white_bin << 2u) + 3u, 4095u);
    }

    publish_matrix_average(tid);
}

void hook() { reduce_metering_histogram(); }

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!BIND METERED_TEMPORAL
//!SAVE EMPTY
//!WIDTH 64
//!HEIGHT 1
//!COMPUTE 64 1 64 1
//!WHEN temporal_stable_duration 0.0 >
//!DESC metering (temporal stabilization)

// Scene analysis is distribution-based. The current frame is compared both
// with a slowly moving shot reference and with the immediately previous frame:
// only an abrupt transition can start a cut candidate, so gradual ramps do not
// become cuts merely because they eventually move far from the old reference.

const uint TEMPORAL_HISTOGRAM_SIZE = 64u;
const uint TEMPORAL_HISTOGRAM_SAMPLE_COUNT = 512u * 288u;
const float TEMPORAL_HISTOGRAM_CUT_THRESHOLD = 0.20;
const float TEMPORAL_HISTOGRAM_FRAME_THRESHOLD = 0.10;
const float TEMPORAL_HISTOGRAM_TIME_SCALE = 0.50;
const float TEMPORAL_SCENE_CONFIRM_TIME_SCALE = 0.25;
const float TEMPORAL_SCENE_ADAPTATION_TIME_SCALE = 0.50;
const float TEMPORAL_MIN_TIME_CONSTANT = 1.0 / 240.0;
const float TEMPORAL_PTS_EPSILON = 1e-6;

const uint TEMPORAL_FRAME_SKIP = 0u;
const uint TEMPORAL_FRAME_INITIALIZE = 1u;
const uint TEMPORAL_FRAME_PROCESS = 2u;
const uint TEMPORAL_REFERENCE_KEEP = 0u;
const uint TEMPORAL_REFERENCE_BLEND = 1u;
const uint TEMPORAL_REFERENCE_REPLACE = 2u;

shared vec2 temporal_distance_partial[TEMPORAL_HISTOGRAM_SIZE];
shared uint temporal_frame_operation;
shared uint temporal_reference_operation;
shared float temporal_delta_time;
shared float temporal_reference_alpha;

float pts_to_float(uint x) {
    return uintBitsToFloat(x);
}

uint pts_to_uint(float x) {
    return floatBitsToUint(x);
}

float temporal_alpha(float delta_time, float time_constant) {
    return 1.0 - exp(
        -delta_time / max(time_constant, TEMPORAL_MIN_TIME_CONSTANT)
    );
}

float temporal_histogram_value(uint coarse_index) {
    return float(metered_coarse_histogram[coarse_index]) /
           float(TEMPORAL_HISTOGRAM_SAMPLE_COUNT);
}

void temporal_clear_scene_candidate() {
    metered_scene_candidate_start_pts = 0u;
    metered_scene_candidate_active = 0u;
}

void temporal_initialize_scalar_state() {
    temporal_clear_scene_candidate();
    metered_histogram_valid = 1u;
    metered_temporal_pts = pts_to_uint(PTS);
    metered_scene_adaptation_end_pts = 0u;
    metered_scene_fast_response = 0u;
}

vec2 temporal_measure_distance(uint index, float current) {
    vec2 distance = vec2(
        abs(current - metered_reference_histogram[index]),
        abs(current - metered_previous_histogram[index])
    );
    metered_previous_histogram[index] = current;
    return distance;
}

void temporal_reduce_distances(uint tid, vec2 distance) {
    temporal_distance_partial[tid] = distance;
    barrier();

    for (uint size = TEMPORAL_HISTOGRAM_SIZE >> 1u;
         size > 0u;
         size >>= 1u) {
        if (tid < size) {
            temporal_distance_partial[tid] +=
                temporal_distance_partial[tid + size];
        }
        barrier();
    }
}

float temporal_reference_blend_alpha(float delta_time) {
    float time_constant = temporal_stable_duration *
                          TEMPORAL_HISTOGRAM_TIME_SCALE;
    return temporal_alpha(delta_time, time_constant);
}

float temporal_scene_confirmation_time() {
    return max(
        temporal_stable_duration * TEMPORAL_SCENE_CONFIRM_TIME_SCALE,
        TEMPORAL_MIN_TIME_CONSTANT
    );
}

float temporal_scene_adaptation_time() {
    return max(
        temporal_stable_duration * TEMPORAL_SCENE_ADAPTATION_TIME_SCALE,
        TEMPORAL_MIN_TIME_CONSTANT
    );
}

void temporal_update_fast_response() {
    if (metered_scene_fast_response == 0u)
        return;

    float end_pts = pts_to_float(metered_scene_adaptation_end_pts);
    if (PTS >= end_pts)
        metered_scene_fast_response = 0u;
}

bool temporal_scene_candidate_active(
    float reference_distance,
    float frame_distance
) {
    bool far_from_reference = reference_distance >
                              TEMPORAL_HISTOGRAM_CUT_THRESHOLD;
    if (metered_scene_candidate_active > 0u)
        return far_from_reference;

    return far_from_reference &&
           frame_distance > TEMPORAL_HISTOGRAM_FRAME_THRESHOLD;
}

void temporal_confirm_scene_change() {
    temporal_clear_scene_candidate();
    metered_scene_adaptation_end_pts = pts_to_uint(
        PTS + temporal_scene_adaptation_time()
    );
    metered_scene_fast_response = 1u;
    temporal_reference_operation = TEMPORAL_REFERENCE_REPLACE;
}

void temporal_process_distances(vec2 distance) {
    bool candidate = temporal_stable_scene_change > 0 &&
                     temporal_scene_candidate_active(
                         distance.x,
                         distance.y
                     );

    if (!candidate) {
        temporal_clear_scene_candidate();
        temporal_reference_operation = TEMPORAL_REFERENCE_BLEND;
        temporal_reference_alpha = temporal_reference_blend_alpha(
            temporal_delta_time
        );
        return;
    }

    if (metered_scene_candidate_active == 0u) {
        metered_scene_candidate_start_pts = pts_to_uint(PTS);
        metered_scene_candidate_active = 1u;
    }

    float elapsed = PTS - pts_to_float(
        metered_scene_candidate_start_pts
    );
    if (elapsed >= temporal_scene_confirmation_time())
        temporal_confirm_scene_change();
}

void temporal_prepare_frame() {
    temporal_frame_operation = TEMPORAL_FRAME_SKIP;
    temporal_reference_operation = TEMPORAL_REFERENCE_KEEP;

    if (metered_histogram_valid == 0u) {
        temporal_initialize_scalar_state();
        temporal_frame_operation = TEMPORAL_FRAME_INITIALIZE;
        return;
    }

    float previous_pts = pts_to_float(metered_temporal_pts);
    float delta_time = PTS - previous_pts;

    // Redrawing the same video frame must not advance temporal state.
    if (abs(delta_time) <= TEMPORAL_PTS_EPSILON)
        return;

    if (delta_time < 0.0 || delta_time > temporal_stable_duration) {
        temporal_initialize_scalar_state();
        temporal_frame_operation = TEMPORAL_FRAME_INITIALIZE;
        return;
    }

    temporal_delta_time = delta_time;
    metered_temporal_pts = pts_to_uint(PTS);
    temporal_update_fast_response();
    temporal_frame_operation = TEMPORAL_FRAME_PROCESS;
}

void temporal_initialize_histogram(uint index, float current) {
    metered_reference_histogram[index] = current;
    metered_previous_histogram[index] = current;
}

void temporal_update_reference_bin(uint index, float current) {
    if (temporal_reference_operation == TEMPORAL_REFERENCE_BLEND) {
        metered_reference_histogram[index] = mix(
            metered_reference_histogram[index],
            current,
            temporal_reference_alpha
        );
    } else if (
        temporal_reference_operation == TEMPORAL_REFERENCE_REPLACE
    ) {
        metered_reference_histogram[index] = current;
    }
}

void analyze_metering_temporally() {
    uint index = gl_LocalInvocationIndex;

    if (index == 0u)
        temporal_prepare_frame();
    barrier();

    if (temporal_frame_operation == TEMPORAL_FRAME_SKIP)
        return;

    float current = temporal_histogram_value(index);

    if (temporal_frame_operation == TEMPORAL_FRAME_INITIALIZE) {
        temporal_initialize_histogram(index, current);
        return;
    }

    vec2 distance = temporal_measure_distance(index, current);
    temporal_reduce_distances(index, distance);

    if (index == 0u) {
        temporal_process_distances(
            0.5 * temporal_distance_partial[0]
        );
    }
    barrier();

    temporal_update_reference_bin(index, current);
}

void hook() { analyze_metering_temporally(); }

//!HOOK OUTPUT
//!BIND METERED
//!BIND METERED_TEMPORAL
//!BIND EXPOSURE_TEMPORAL
//!BIND CURVE_TEMPORAL
//!BIND METADATA
//!SAVE EMPTY
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 1 1
//!DESC metering (metadata)

// For content with dynamic metadata, it will be provided by mpv
// https://github.com/mpv-player/mpv/pull/15239

// Filter automatic exposure in its final EV domain so observation noise cannot
// become a large nonlinear luminance change. Manual exposure remains direct.
const float EXPOSURE_RISE_TIME_SCALE = 0.50;
const float EXPOSURE_FALL_TIME_SCALE = 0.35;
const float EXPOSURE_MIN_TIME_CONSTANT = 1.0 / 240.0;
const float EXPOSURE_PTS_EPSILON = 1e-6;
const float OUTPUT_TEMPORAL_SCENE_TIME_SCALE = 0.125;
const float OUTPUT_TEMPORAL_SCENE_ADAPTATION_SCALE = 0.50;

// All curve samples use one PTS-derived coefficient. Compute it once in this
// single-invocation pass instead of repeating the timestamp state and exp()
// evaluation in every curve-LUT invocation.
const float CURVE_TEMPORAL_TIME_SCALE = 0.35;
const float CURVE_TEMPORAL_MIN_TIME_CONSTANT = 1.0 / 240.0;
const float CURVE_TEMPORAL_PTS_EPSILON = 1e-6;

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

float pq_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

float pq_eotf(float x) {
    float t = pow(x, 1.0 / m2);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

const float m2_z = 1.7 * m2;

float iz_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2_z);
}

float iz_eotf(float x) {
    float t = pow(x, 1.0 / m2_z);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

const float d = -0.56;
const float d0 = 1.6295499532821566e-11;

float I_to_J(float I) {
    return ((1.0 + d) * I) / (1.0 + (d * I)) - d0;
}

float J_to_I(float J) {
    return (J + d0) / (1.0 + d - d * (J + d0));
}

float RGB_to_Y(vec3 rgb) {
    const vec3 coefficients = vec3(0.2627002120112671, 0.6779980715188708, 0.05930171646986196);
    return dot(rgb, coefficients);
}

float to_float(uint x) {
    return float(x) / 4095.0;
}

struct MeteringMetrics {
    // maximum is the robust intensity white used by exposure constraints;
    // max_rgb is the maximum channel value used by the tone curve.
    float maximum;
    float max_rgb;
    float minimum;
    float average;
};

MeteringMetrics resolve_metering_metrics() {
    MeteringMetrics metrics;
    vec3 scene_max_rgb = vec3(scene_max_r, scene_max_g, scene_max_b);
    bool has_pq_peak = max_pq_y > 0.0;
    bool has_scene_peak = any(greaterThan(scene_max_rgb, vec3(0.0)));

    // This must match the peak-metadata conditions on the intensity-map pass.
    // A skipped pass leaves METERED unchanged, so its values are not current.
    bool use_measured = enable_metering > 0 &&
                        !has_pq_peak && !has_scene_peak;

    if (has_pq_peak)
        metrics.maximum = max_pq_y;
    else if (has_scene_peak)
        metrics.maximum = pq_eotf_inv(RGB_to_Y(scene_max_rgb));
    else if (use_measured)
        metrics.maximum = to_float(metered_max_i);
    else if (max_cll > 0.0)
        metrics.maximum = pq_eotf_inv(max_cll);
    else if (max_luma > 0.0)
        metrics.maximum = pq_eotf_inv(max_luma);
    else
        metrics.maximum = pq_eotf_inv(1000.0);

    if (has_scene_peak)
        metrics.max_rgb = pq_eotf_inv(
            max(max(scene_max_rgb.r, scene_max_rgb.g), scene_max_rgb.b)
        );
    else if (use_measured)
        metrics.max_rgb = to_float(metered_max_rgb);
    else
        metrics.max_rgb = metrics.maximum;

    if (use_measured)
        metrics.minimum = to_float(metered_min_i);
    else if (min_luma > 0.0)
        metrics.minimum = pq_eotf_inv(min_luma);
    else
        metrics.minimum = 0.0;

    if (avg_pq_y > 0.0)
        metrics.average = avg_pq_y;
    else if (scene_avg > 0.0)
        metrics.average = pq_eotf_inv(scene_avg);
    else if (use_measured && enable_metering > 1)
        metrics.average = to_float(metered_avg_i);
    // MaxFALL is the static-metadata fallback for average luminance, but using
    // it as the exposure anchor produced poor results in practice.
    // else if (max_fall > 0.0)
    //     metrics.average = pq_eotf_inv(max_fall);
    else
        metrics.average = 0.0;

    return metrics;
}

float calculate_auto_exposure(MeteringMetrics metrics) {
    float reference_iz = iz_eotf_inv(reference_white);
    float reference_j = I_to_J(reference_iz);
    float anchor_j = auto_exposure_anchor * reference_j;
    float anchor_iz = J_to_I(anchor_j);
    float anchor = iz_eotf(anchor_iz);

    float average = max(pq_eotf(metrics.average), 1e-6);
    float maximum = max(pq_eotf(metrics.maximum), 1e-6);
    float minimum = max(pq_eotf(metrics.minimum), 1e-6);

    float exposure = log2(anchor / average);

    float ev_limit_neg = auto_exposure_limit_negative;
    float ev_limit_pos = auto_exposure_limit_positive;

    if (auto_exposure_limit_input > 0) {
        ev_limit_neg = min(ev_limit_neg, log2(maximum / average));
        ev_limit_pos = min(ev_limit_pos, log2(average / minimum));
    }

    return clamp(exposure, -ev_limit_neg, ev_limit_pos);
}

float resolve_exposure(MeteringMetrics metrics) {
    // A non-zero external value replaces automatic exposure entirely.
    if (exposure_value != 0.0)
        return exposure_value;

    if (metrics.average <= 0.0 || auto_exposure_anchor <= 0.0)
        return 0.0;

    return calculate_auto_exposure(metrics);
}

float output_temporal_time_scale(float normal_scale) {
    float adaptation_end = uintBitsToFloat(
        metered_scene_adaptation_end_pts
    );
    float adaptation_duration = max(
        temporal_stable_duration * OUTPUT_TEMPORAL_SCENE_ADAPTATION_SCALE,
        EXPOSURE_MIN_TIME_CONSTANT
    );
    bool fast_response = metered_scene_fast_response > 0u &&
                         PTS < adaptation_end &&
                         PTS >= adaptation_end - adaptation_duration;
    return fast_response
        ? min(normal_scale, OUTPUT_TEMPORAL_SCENE_TIME_SCALE)
        : normal_scale;
}

float stabilize_auto_exposure(float target, bool automatic) {
    if (!automatic || temporal_stable_duration <= 0.0) {
        smoothed_ev = target;
        smoothed_ev_pts = floatBitsToUint(PTS);
        smoothed_ev_valid = 1u;
        return target;
    }

    if (smoothed_ev_valid == 0u) {
        smoothed_ev = target;
        smoothed_ev_pts = floatBitsToUint(PTS);
        smoothed_ev_valid = 1u;
        return target;
    }

    float delta_time = PTS - uintBitsToFloat(smoothed_ev_pts);
    if (abs(delta_time) <= EXPOSURE_PTS_EPSILON)
        return smoothed_ev;

    if (delta_time < 0.0 || delta_time > temporal_stable_duration) {
        smoothed_ev = target;
        smoothed_ev_pts = floatBitsToUint(PTS);
        return target;
    }

    float time_scale = target > smoothed_ev
        ? EXPOSURE_RISE_TIME_SCALE
        : EXPOSURE_FALL_TIME_SCALE;
    time_scale = output_temporal_time_scale(time_scale);
    float time_constant = max(
        temporal_stable_duration * time_scale,
        EXPOSURE_MIN_TIME_CONSTANT
    );
    float alpha = 1.0 - exp(-delta_time / time_constant);
    smoothed_ev = mix(smoothed_ev, target, alpha);
    smoothed_ev_pts = floatBitsToUint(PTS);
    return smoothed_ev;
}

void prepare_curve_temporal() {
    curve_temporal_alpha = 1.0;
    curve_temporal_reset = 1u;

    if (temporal_stable_duration <= 0.0) {
        curve_temporal_pts = floatBitsToUint(PTS);
        curve_temporal_valid = 1u;
        return;
    }

    if (curve_temporal_valid == 0u) {
        curve_temporal_pts = floatBitsToUint(PTS);
        curve_temporal_valid = 1u;
        return;
    }

    float delta_time = PTS - uintBitsToFloat(curve_temporal_pts);
    if (abs(delta_time) <= CURVE_TEMPORAL_PTS_EPSILON) {
        curve_temporal_alpha = 0.0;
        curve_temporal_reset = 0u;
        return;
    }

    curve_temporal_pts = floatBitsToUint(PTS);
    if (delta_time < 0.0 || delta_time > temporal_stable_duration)
        return;

    float time_scale = output_temporal_time_scale(
        CURVE_TEMPORAL_TIME_SCALE
    );
    float time_constant = max(
        temporal_stable_duration * time_scale,
        CURVE_TEMPORAL_MIN_TIME_CONSTANT
    );
    curve_temporal_alpha = 1.0 - exp(-delta_time / time_constant);
    curve_temporal_reset = 0u;
}

void apply_exposure_to_range(inout MeteringMetrics metrics, float scale) {
    if (scale == 1.0)
        return;

    metrics.maximum = pq_eotf_inv(pq_eotf(metrics.maximum) * scale);
    metrics.max_rgb = pq_eotf_inv(pq_eotf(metrics.max_rgb) * scale);
    metrics.minimum = pq_eotf_inv(pq_eotf(metrics.minimum) * scale);
}

bool automatic_exposure_enabled(MeteringMetrics metrics) {
    return exposure_value == 0.0 &&
           metrics.average > 0.0 &&
           auto_exposure_anchor > 0.0;
}

void publish_metering_metadata(MeteringMetrics metrics) {
    max_i = metrics.maximum;
    max_rgb = metrics.max_rgb;
    min_i = metrics.minimum;
    avg_i = metrics.average;
}

void publish_input_metering_metadata(MeteringMetrics metrics) {
    input_max_i = metrics.maximum;
    input_min_i = metrics.minimum;
    input_avg_i = metrics.average;
}

void publish_output_lightness_range() {
    output_black_j = I_to_J(
        iz_eotf_inv(reference_white / contrast_ratio)
    );
    output_white_j = I_to_J(iz_eotf_inv(reference_white));
}

void update_metering_metadata() {
    prepare_curve_temporal();
    publish_output_lightness_range();

    MeteringMetrics metrics = resolve_metering_metrics();
    publish_input_metering_metadata(metrics);
    float target_ev = resolve_exposure(metrics);
    ev = stabilize_auto_exposure(
        target_ev,
        automatic_exposure_enabled(metrics)
    );
    exposure_scale = exp2(ev);
    apply_exposure_to_range(metrics, exposure_scale);
    publish_metering_metadata(metrics);
}

void hook() { update_metering_metadata(); }

//!HOOK OUTPUT
//!BIND METADATA
//!BIND CURVE_TEMPORAL
//!SAVE LUTS
//!WIDTH 4225
//!HEIGHT 195
//!COMPUTE 32 8
//!DESC tone mapping (LUT generation, astra)

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

float pq_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

vec3 pq_eotf_inv(vec3 x) {
    vec3 t = pow(x / pw, vec3(m1));
    return pow((c1 + c2 * t) / (1.0 + c3 * t), vec3(m2));
}

float pq_eotf(float x) {
    float t = pow(x, 1.0 / m2);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

vec3 pq_eotf(vec3 x) {
    vec3 t = pow(x, vec3(1.0 / m2));
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), vec3(1.0 / m1)) * pw;
}

// Jzazbz added a factor to m2, which differs from the original PQ equation.
const float m2_z = 1.7 * m2;

float iz_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2_z);
}

vec3 iz_eotf_inv(vec3 x) {
    vec3 t = pow(x / pw, vec3(m1));
    return pow((c1 + c2 * t) / (1.0 + c3 * t), vec3(m2_z));
}

float iz_eotf(float x) {
    float t = pow(x, 1.0 / m2_z);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

vec3 iz_eotf(vec3 x) {
    vec3 t = pow(x, vec3(1.0 / m2_z));
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), vec3(1.0 / m1)) * pw;
}

vec3 RGB_to_XYZ(vec3 RGB) {
    const mat3 M = mat3(
        0.6369580483012914, 0.14461690358620832,  0.1688809751641721,
        0.2627002120112671, 0.6779980715188708,   0.05930171646986196,
        0.0               , 0.028072693049087428, 1.060985057710791
    );
    return RGB * M;
}

vec3 XYZ_to_RGB(vec3 XYZ) {
    const mat3 M = mat3(
         1.716651187971268, -0.355670783776392, -0.25336628137366,
        -0.666684351832489,  1.616481236634939,  0.0157685458139111,
         0.017639857445311, -0.042770613257809,  0.942103121235474
    );
    return XYZ * M;
}

const float b = 1.15;
const float g = 0.66;

vec3 XYZ_to_XYZm(vec3 XYZ) {
    float Xm = (b * XYZ.x) - ((b - 1.0) * XYZ.z);
    float Ym = (g * XYZ.y) - ((g - 1.0) * XYZ.x);
    return vec3(Xm, Ym, XYZ.z);
}

vec3 XYZm_to_XYZ(vec3 XYZm) {
    float Xa = (XYZm.x + ((b - 1.0) * XYZm.z)) / b;
    float Ya = (XYZm.y + ((g - 1.0) * Xa)) / g;
    return vec3(Xa, Ya, XYZm.z);
}

vec3 XYZ_to_LMS(vec3 XYZ) {
    const mat3 M =mat3(
         0.41478972, 0.579999, 0.0146480,
        -0.2015100,  1.120649, 0.0531008,
        -0.0166008,  0.264800, 0.6684799
    );
    return XYZ * M;
}

vec3 LMS_to_XYZ(vec3 LMS) {
    const mat3 M = mat3(
         1.9242264357876067,  -1.0047923125953657,  0.037651404030618,
         0.35031676209499907,  0.7264811939316552, -0.06538442294808501,
        -0.09098281098284752, -0.3127282905230739,  1.5227665613052603
    );
    return LMS * M;
}

vec3 LMS_to_Iab(vec3 LMS) {
    const mat3 M = mat3(
        0.0,       0.5,       0.5,
        3.524000, -4.066708,  0.542708,
        0.199076,  1.096799, -1.295875
    );
    return LMS * M;
}

vec3 Iab_to_LMS(vec3 Iab) {
    const mat3 M = mat3(
        1.0,  0.13860504327153927,  0.05804731615611883,
        1.0, -0.1386050432715393,  -0.058047316156118904,
        1.0, -0.09601924202631895, -0.81189189605603900
    );
    return Iab * M;
}

// https://doi.org/10.2352/ISSN.2169-2629.2017.25.264
// Optimized matrices for Jzazbz about LMS to I conversion.
// https://doi.org/10.1364/OE.413659
// ZCAM defines Iz = G' - ε, where ε = 3.7035226210190005e-11.
// However, it appears we do not need it.
vec3 LMS_to_Iab_optimized(vec3 LMS) {
    const mat3 M = mat3(
        0.0,       1.0,       0.0,
        3.524000, -4.066708,  0.542708,
        0.199076,  1.096799, -1.295875
    );
    return LMS * M;
}

vec3 Iab_to_LMS_optimized(vec3 Iab) {
    const mat3 M = mat3(
        1.0, 0.2772100865430786,  0.1160946323122377,
        1.0, 0.0,                 0.0,
        1.0, 0.0425858012452203, -0.75384457989992
    );
    return Iab * M;
}

const float d = -0.56;
const float d0 = 1.6295499532821566e-11;

float I_to_J(float I) {
    return ((1.0 + d) * I) / (1.0 + (d * I)) - d0;
}

float J_to_I(float J) {
    return (J + d0) / (1.0 + d - d * (J + d0));
}

// CIELUV: -0.01585, -0.03017, -0.04556, -0.02667, -0.00295, 0.14592, 0.05084, -0.01900, -0.00764
float hke_fh_nayatani(
    float h, float k1,
    float k2, float k3, float k4, float k5,
    float k6, float k7, float k8, float k9
) {
    float q = k1 +
        k2 * cos(h) + k3 * cos(2.0 * h) + k4 * cos(3.0 * h) + k5 * cos(4.0 * h) +
        k6 * sin(h) + k7 * sin(2.0 * h) + k8 * sin(3.0 * h) + k9 * sin(4.0 * h);
    // flipped
    return -q;
}

// CIECAM02: -0.218, 0.167, -0.500, 0.032, 0.887
// CAM16: -0.160, 0.132, -0.405, 0.080, 0.792
float hke_fh_hellwig(float h, float a1, float a2, float a3, float a4, float a5) {
    return a1 * cos(h) + a2 * cos(2.0 * h) + a3 * sin(h) + a4 * sin(2.0 * h) + a5;
}

// CIELAB: 0.1644, 0.0603, 0.1307, 0.0060
float hke_fh_high(float h, float k1, float k2, float k3, float k4) {
    h = mod(mod(degrees(h), 360.0) + 360.0, 360.0);
    float by = k1 * abs(sin(radians((h - 90.0)/ 2.0))) + k2;
    float r  = h <= 90.0 || h >= 270.0 ? k3 * abs(cos(radians(h))) + k4 : 0.0;
    return by + r;
}

// CIECAM16: 1.5940, 45.0, 2.6518
// CIELAB: 0.1644, 45.0, 0.1024
float hke_fh_liao(float h, float k3, float k4, float k5) {
    h = mod(mod(degrees(h), 360.0) + 360.0, 360.0);
    return k3 * abs(log(((h + k4) / (90.0 + k4)))) + k5;
}

float hke_fh(float h) {
    float result = hke_fh_liao(h, 0.1351, 45.0, 0.1439);
    return result * hk_effect_compensate_scaling;
}

// Lightness modifications of the CIECAM16 and CIELAB based
// on the Helmholtz-Kohlrausch effect
// by Liao et al.
// https://doi.org/10.1364/OE.534073
float J_to_Jhk(vec3 JCh) {
    float J = JCh.x;
    float C = JCh.y;
    float h = JCh.z;
    return J + C * hke_fh(h);
}

float Jhk_to_J(vec3 JCh) {
    float J = JCh.x;
    float C = JCh.y;
    float h = JCh.z;
    return J - C * hke_fh(h);
}

// https://www.itu.int/rec/R-REC-BT.2124
// ΔE_ITP_JND = 1 / 720
// 0.0001 of Cz is much smaller than it
const float epsilon = 0.0001;

vec3 Lab_to_LCh(vec3 Lab) {
    float L = Lab.x;
    float a = Lab.y;
    float b = Lab.z;

    float C = length(vec2(a, b));
    float h = C < epsilon ? 0.0 : atan(b, a);

    return vec3(L, C, h);
}

vec3 LCh_to_Lab(vec3 LCh) {
    float L = LCh.x;
    float C = LCh.y;
    float h = LCh.z;

    C = max(C, 0.0);
    float a = C * cos(h);
    float b = C * sin(h);

    return vec3(L, a, b);
}

// Perceptually uniform color space for image signals including
// high dynamic range and wide gamut
// by Safdar et al.
// https://doi.org/10.1364/OE.25.015131
//
// an optimized version of the LMS to Iab matrix was used,
// and H-K effect compensation was added.
vec3 RGB_to_Jab(vec3 color) {
    color *= reference_white;
    color = RGB_to_XYZ(color);
    color = XYZ_to_XYZm(color);
    color = XYZ_to_LMS(color);
    color = iz_eotf_inv(max(color, vec3(0.0)));
    color = LMS_to_Iab_optimized(color);
    color.x = I_to_J(color.x);
    color.x = J_to_Jhk(Lab_to_LCh(color));
    return color;
}

vec3 Jab_to_RGB(vec3 color) {
    color.x = Jhk_to_J(Lab_to_LCh(color));
    color.x = J_to_I(color.x);
    color = Iab_to_LMS_optimized(color);
    color = iz_eotf(max(color, vec3(0.0)));
    color = LMS_to_XYZ(color);
    color = XYZm_to_XYZ(color);
    color = XYZ_to_RGB(color);
    color /= max(reference_white, 1e-6);
    return color;
}

float f_slope(float x0, float y0, float x1, float y1) {
    float num = (y1 - y0);
    float den = (x1 - x0);
    return abs(den) < 1e-6 ? 1.0 : num / den;
}

float f_intercept(float slope, float x0, float y0) {
    return y0 - slope * x0;
}

float f_linear(float x, float slope, float intercept) {
    return slope * x + intercept;
}

// Linear relationship between angle and parameter c
// c = 0: angle = 45° (slope = 1)
// c = ±N: angle = 45° ± k*N
float f_contrast(float c) {
    float range = 40.0; // 40° per unit of c
    float angle = radians(45.0 + range * c);
    float slope = tan(angle);
    return 1.0 - 1.0 / slope;
}

// Hyperbola tone mapping
// by suzuki et al.
// https://technorgb.blogspot.com/2018/02/hyperbola-tone-mapping.html
float f_toe_suzuki(float x, float slope, float x0, float y0, float x1, float y1) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float dt = x - x0;
    float k = dy - slope * dx;
    float scale = dy * dy;
    float base = slope * dx * dx;

    return y0 + scale * dt / (dt * k + base);
}

float f_shoulder_suzuki(float x, float slope, float x0, float y0, float x1, float y1) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float dt = x - x0;
    float k = slope * dx - dy;
    float scale = slope * dx * dy;
    float base = dx * dy;

    return y0 + scale * dt / (dt * k + base);
}

// Filmic Tonemapping with Piecewise Power Curves
// by John Hable
// http://filmicworlds.com/blog/filmic-tonemapping-with-piecewise-power-curves/
float f_toe_hable(float x, float slope, float x0, float y0, float x1, float y1) {
    float dx = x1 - x0;
    float dy = y1 - y0;

    float b = slope * dx / dy;
    float a = log(dy) - b * log(dx);
    float s = 1.0;

    float v = max((x - x0) * s, 1e-6);
    float o = y0;

    return exp(a + b * log(v)) * s + o;
}

float f_shoulder_hable(float x, float slope, float x0, float y0, float x1, float y1) {
    float dx = x1 - x0;
    float dy = y1 - y0;

    float b = slope * dx / dy;
    float a = log(dy) - b * log(dx);
    float s = -1.0;

    float v = max((x - x1) * s, 1e-6);
    float o = y1;

    return exp(a + b * log(v)) * s + o;
}

// Hable shoulder with overshoot: extends the virtual white point to
// (x1 + overshoot * dx, y1 + overshoot * dy), so the curve still has
// non-zero slope at x1.  Accepts a slight slope discontinuity at x0.
// overshoot = 0 recovers f_shoulder_hable.
float f_shoulder_hable_overshoot(
    float x, float slope,
    float x0, float y0, float x1, float y1,
    float overshoot
) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float vx = x1 + overshoot * dx;
    float vy = y1 + overshoot * dy;

    float y = f_shoulder_hable(x, slope, x0, y0, vx, vy);
    float yw = f_shoulder_hable(x1, slope, x0, y0, vx, vy);
    float t = (y - y0) / (yw - y0);
    return mix(y0, y1, t);
}

// Generalized rational shoulder. In normalized coordinates t and g(t):
//
//   g(t) = 1 - (1 - t) / (1 + a*t)^p
//
// It passes through both anchors, matches the incoming slope at t = 0,
// remains increasing beyond t = 1, and has a monotonically decreasing slope.
// overshoot = 0 gives the Suzuki rational shoulder exactly; larger values
// retain more slope beyond the white point without adding a separate tail.
float f_shoulder_rational(
    float x, float slope,
    float x0, float y0, float x1, float y1,
    float overshoot
) {
    float dx = x1 - x0;
    float dy = y1 - y0;
    float t = (x - x0) / dx;
    float normalized_slope = slope * dx / dy;
    float power = 1.0 / (1.0 + max(overshoot, 0.0));
    float curvature = (normalized_slope - 1.0) / power;
    float denominator = pow(1.0 + curvature * t, power);
    float mapped = 1.0 - (1.0 - t) / denominator;
    return y0 + dy * mapped;
}

float f(
    float x, float iw, float ib, float ow, float ob,
    float sw, float hw, float cb
) {
    float midgray   = 0.5 * ow;
    float shadow    = mix(midgray, ob, sw);
    float highlight = mix(midgray, ow, hw);
    float contrast  = f_contrast(cb);

    float x0 = ib;
    float y0 = ob;
    float x1 = mix(shadow, midgray, contrast);
    float y1 = shadow;
    float x2 = mix(highlight, midgray, contrast);
    float y2 = highlight;
    float x3 = iw;
    float y3 = ow;

    float slope = f_slope(x1, y1, x2, y2);
    float intercept = f_intercept(slope, x1, y1);

    if (x >= x1 && x <= x2) {
        return f_linear(x, slope, intercept);
    }

    if (x < x1) {
        float slope_toe = f_slope(x0, y0, x1, y1);
        if (slope_toe >= slope) {
            return f_linear(x, slope, intercept);
        }

        return f_toe_suzuki(x, slope, x0, y0, x1, y1);
    }

    if (x > x2) {
        float slope_shoulder = f_slope(x2, y2, x3, y3);
        if (slope_shoulder >= slope) {
            return f_linear(x, slope, intercept);
        }

        return f_shoulder_rational(
            x, slope,
            x2, y2, x3, y3,
            highlight_overshoot
        );
    }

    return x;
}

float f(float x, float iw, float ib, float ow, float ob) {
    return f(
        x, iw, ib, ow, ob,
        shadow_weight, highlight_weight, contrast_bias
    );
}

float curve(float x) {
    float ow = output_white_j;
    float ob = output_black_j;
    float iw = I_to_J(iz_eotf_inv(pq_eotf(max_rgb)));
    float ib = I_to_J(iz_eotf_inv(pq_eotf(min_i)));

    iw = max(iw, ow);
    ib = min(ib, ob);

    float y = f(x, iw, ib, ow, ob);

    return y;
}

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT, and one 1024-point curve row. The reverse LUT stores its
// higher-resolution J axis in atlas rows and spans the tone-mapped output
// range, while a/b share the flattened axis to keep the atlas 65^2 texels wide.
const int FORWARD_LUT_SIZE = 65;
const int FORWARD_LUT_LAST = FORWARD_LUT_SIZE - 1;
const int REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int REVERSE_LIGHTNESS_LUT_LAST = REVERSE_LIGHTNESS_LUT_SIZE - 1;
const int REVERSE_CHROMA_LUT_SIZE = 65;
const int REVERSE_CHROMA_LUT_LAST = REVERSE_CHROMA_LUT_SIZE - 1;
const int LUT_ATLAS_WIDTH = FORWARD_LUT_SIZE * FORWARD_LUT_SIZE;
const int RGB_TO_LAB_ROW = 0;
const int LAB_TO_RGB_ROW = RGB_TO_LAB_ROW + FORWARD_LUT_SIZE;
const int CURVE_ROW = LAB_TO_RGB_ROW + REVERSE_LIGHTNESS_LUT_SIZE;
const int CURVE_SIZE = 1024;

// LAB chroma-coordinate shaper: the scale concentrates precision near neutral,
// while the limits define the representable ranges of the a/L and b/L ratios.
const float AB_RATIO_SCALE = 0.25;
const float A_RATIO_LIMIT = 2.5;
const float B_RATIO_LIMIT = 3.0;

vec3 lut_coordinates_to_RGB(vec3 coordinates) {
    vec3 absolute_rgb = pq_eotf(clamp(coordinates, 0.0, 1.0));
    return absolute_rgb / max(reference_white, 1e-6);
}

float decode_signed_coordinate(float coordinate, float limit) {
    float qmax = limit / (limit + AB_RATIO_SCALE);
    float signed_coordinate = 2.0 * clamp(coordinate, 0.0, 1.0) - 1.0;
    float q = abs(signed_coordinate) * qmax;
    float value = AB_RATIO_SCALE * q / max(1.0 - q, 1e-6);
    return sign(signed_coordinate) * value;
}

vec3 lut_coordinates_to_LAB(vec3 coordinates) {
    float L = mix(
        output_black_j,
        output_white_j,
        clamp(coordinates.x, 0.0, 1.0)
    );
    float a_ratio = decode_signed_coordinate(coordinates.y, A_RATIO_LIMIT);
    float b_ratio = decode_signed_coordinate(coordinates.z, B_RATIO_LIMIT);
    return vec3(L, a_ratio * L, b_ratio * L);
}

vec3 atlas_to_RGB_lut_coordinates(ivec2 atlas_position) {
    ivec3 lut_texel = ivec3(
        atlas_position.x % FORWARD_LUT_SIZE,
        atlas_position.x / FORWARD_LUT_SIZE,
        atlas_position.y - RGB_TO_LAB_ROW
    );
    return vec3(lut_texel) / float(FORWARD_LUT_LAST);
}

vec3 atlas_to_LAB_lut_coordinates(ivec2 atlas_position) {
    ivec3 lut_texel = ivec3(
        atlas_position.y - LAB_TO_RGB_ROW,
        atlas_position.x % REVERSE_CHROMA_LUT_SIZE,
        atlas_position.x / REVERSE_CHROMA_LUT_SIZE
    );
    return vec3(
        float(lut_texel.x) / float(REVERSE_LIGHTNESS_LUT_LAST),
        float(lut_texel.y) / float(REVERSE_CHROMA_LUT_LAST),
        float(lut_texel.z) / float(REVERSE_CHROMA_LUT_LAST)
    );
}

void store_atlas(ivec2 atlas_position, vec3 value) {
    imageStore(out_image, atlas_position, vec4(value, 1.0));
}

void generate_RGB_to_LAB_lut(ivec2 atlas_position) {
    vec3 coordinates = atlas_to_RGB_lut_coordinates(atlas_position);
    vec3 rgb = lut_coordinates_to_RGB(coordinates);
    store_atlas(atlas_position, RGB_to_Jab(rgb));
}

void generate_LAB_to_RGB_lut(ivec2 atlas_position) {
    vec3 coordinates = atlas_to_LAB_lut_coordinates(atlas_position);
    vec3 lab = lut_coordinates_to_LAB(coordinates);
    store_atlas(atlas_position, Jab_to_RGB(lab));
}

float stabilize_curve_value(int index, float target) {
    if (curve_temporal_reset > 0u) {
        smoothed_curve[index] = target;
        return target;
    }

    smoothed_curve[index] = mix(
        smoothed_curve[index],
        target,
        curve_temporal_alpha
    );
    return smoothed_curve[index];
}

void generate_curve_lut(ivec2 atlas_position) {
    int index = atlas_position.x;
    float coordinate = float(index) / float(CURVE_SIZE - 1);
    float value = stabilize_curve_value(index, curve(coordinate));
    store_atlas(atlas_position, vec3(value, 0.0, 0.0));
}

void generate_lut_atlas_texel(ivec2 atlas_position) {
    if (atlas_position.x >= LUT_ATLAS_WIDTH ||
        atlas_position.y > CURVE_ROW) {
        return;
    }

    if (atlas_position.y < LAB_TO_RGB_ROW) {
        generate_RGB_to_LAB_lut(atlas_position);
    } else if (atlas_position.y < CURVE_ROW) {
        generate_LAB_to_RGB_lut(atlas_position);
    } else if (atlas_position.x < CURVE_SIZE) {
        generate_curve_lut(atlas_position);
    }
}

void hook() {
    generate_lut_atlas_texel(ivec2(gl_GlobalInvocationID.xy));
}

//!HOOK OUTPUT
//!BIND VECTORSCOPE
//!SAVE EMPTY
//!COMPONENTS 1
//!WIDTH 128
//!HEIGHT 128
//!COMPUTE 16 16 16 16
//!WHEN preview_metering
//!DESC metering (vectorscope, init)

const uint VECTORSCOPE_SIZE = 128u;

void hook() {
    uvec2 position = gl_GlobalInvocationID.xy;
    if (any(greaterThanEqual(position, uvec2(VECTORSCOPE_SIZE))))
        return;

    uint index = position.y * VECTORSCOPE_SIZE + position.x;
    vectorscope_histogram[index] = 0u;
    vectorscope_color_r[index] = 0u;
    vectorscope_color_g[index] = 0u;
    vectorscope_color_b[index] = 0u;
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUTS
//!BIND VECTORSCOPE
//!SAVE EMPTY
//!COMPONENTS 1
//!WIDTH 256
//!HEIGHT 144
//!COMPUTE 16 16 16 16
//!WHEN preview_metering
//!DESC metering (vectorscope, ab projection)

// Sample Astra's original linear-RGB input sparsely, convert it with the
// generated RGB-to-Jab LUT, and scatter its a/b projection into a compact
// density map. The fixed range covers the Rec. 2020 gamut up to PQ peak while
// retaining useful resolution around ordinary display colours.
const int VECTORSCOPE_LUT_SIZE = 65;
const int VECTORSCOPE_LUT_LAST = VECTORSCOPE_LUT_SIZE - 1;
const int VECTORSCOPE_RGB_TO_LAB_ROW = 0;
const uint VECTORSCOPE_SIZE = 128u;
const float VECTORSCOPE_AB_RANGE = 0.36;
const float VECTORSCOPE_COLOR_SCALE = 65535.0;

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

vec3 pq_eotf_inv(vec3 x) {
    vec3 t = pow(x / pw, vec3(m1));
    return pow((c1 + c2 * t) / (1.0 + c3 * t), vec3(m2));
}

vec3 fetch_vectorscope_atlas(ivec2 position) {
    return texelFetch(LUTS_raw, position, 0).rgb;
}

vec3 fetch_vectorscope_lut(ivec3 texel) {
    ivec2 atlas_position = ivec2(
        texel.x + texel.y * VECTORSCOPE_LUT_SIZE,
        VECTORSCOPE_RGB_TO_LAB_ROW + texel.z
    );
    return fetch_vectorscope_atlas(atlas_position);
}

void select_vectorscope_tetrahedron(
    vec3 fraction,
    out ivec3 second_offset,
    out ivec3 third_offset,
    out vec3 weights
) {
    if (fraction.x >= fraction.y) {
        if (fraction.y >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.xyz;
        } else if (fraction.x >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.xzy;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.zxy;
        }
    } else {
        if (fraction.x >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.yxz;
        } else if (fraction.y >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.yzx;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.zyx;
        }
    }
}

vec3 sample_vectorscope_rgb_to_jab(vec3 rgb) {
    vec3 absolute_rgb = clamp(
        max(rgb, vec3(0.0)) * max(reference_white, 0.0),
        0.0,
        pw
    );
    vec3 position = pq_eotf_inv(absolute_rgb) *
                    float(VECTORSCOPE_LUT_LAST);
    ivec3 base_texel = ivec3(floor(position));
    vec3 fraction = fract(position);

    ivec3 second_offset;
    ivec3 third_offset;
    vec3 weights;
    select_vectorscope_tetrahedron(
        fraction,
        second_offset,
        third_offset,
        weights
    );

    ivec3 last_texel = ivec3(VECTORSCOPE_LUT_LAST);
    vec3 value0 = fetch_vectorscope_lut(base_texel);
    vec3 value1 = fetch_vectorscope_lut(
        min(base_texel + second_offset, last_texel)
    );
    vec3 value2 = fetch_vectorscope_lut(
        min(base_texel + third_offset, last_texel)
    );
    vec3 value3 = fetch_vectorscope_lut(
        min(base_texel + ivec3(1), last_texel)
    );

    return LUTS_mul * (
        value0
        + weights.x * (value1 - value0)
        + weights.y * (value2 - value1)
        + weights.z * (value3 - value2)
    );
}

uint vectorscope_bin(vec2 ab) {
    vec2 coordinate = vec2(
        0.5 + 0.5 * ab.x / VECTORSCOPE_AB_RANGE,
        0.5 - 0.5 * ab.y / VECTORSCOPE_AB_RANGE
    );
    uvec2 bin = min(
        uvec2(clamp(coordinate, 0.0, 1.0) *
              float(VECTORSCOPE_SIZE)),
        uvec2(VECTORSCOPE_SIZE - 1u)
    );
    return bin.y * VECTORSCOPE_SIZE + bin.x;
}

void hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    vec3 jab = sample_vectorscope_rgb_to_jab(rgb);
    uint index = vectorscope_bin(jab.yz);
    vec3 positive_rgb = max(rgb, vec3(0.0));
    float encoding_peak = max(
        max(max(positive_rgb.r, positive_rgb.g), positive_rgb.b),
        1.0
    );
    uvec3 encoded_rgb = uvec3(
        positive_rgb / encoding_peak * VECTORSCOPE_COLOR_SCALE + 0.5
    );

    // At 256x144 samples, even a single fully occupied bin remains below the
    // uint limit with 16-bit channel sums.
    atomicAdd(vectorscope_histogram[index], 1u);
    atomicAdd(vectorscope_color_r[index], encoded_rgb.r);
    atomicAdd(vectorscope_color_g[index], encoded_rgb.g);
    atomicAdd(vectorscope_color_b[index], encoded_rgb.b);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUTS
//!BIND METADATA
//!DESC tone mapping (LUT application)

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT with its tone-mapped J range stored in rows, and one
// 1024-point curve row.
const int FORWARD_LUT_SIZE = 65;
const int FORWARD_LUT_LAST = FORWARD_LUT_SIZE - 1;
const int REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int REVERSE_LIGHTNESS_LUT_LAST = REVERSE_LIGHTNESS_LUT_SIZE - 1;
const int REVERSE_CHROMA_LUT_SIZE = 65;
const int REVERSE_CHROMA_LUT_LAST = REVERSE_CHROMA_LUT_SIZE - 1;
const int RGB_TO_LAB_ROW = 0;
const int LAB_TO_RGB_ROW = RGB_TO_LAB_ROW + FORWARD_LUT_SIZE;
const int CURVE_ROW = LAB_TO_RGB_ROW + REVERSE_LIGHTNESS_LUT_SIZE;
const int CURVE_SIZE = 1024;
const int RGB_TO_LAB_LUT = 0;
const int LAB_TO_RGB_LUT = 1;

// LAB chroma-coordinate shaper: the scale concentrates precision near neutral,
// while the limits define the representable ranges of the a/L and b/L ratios.
const float AB_RATIO_SCALE = 0.25;
const float A_RATIO_LIMIT = 2.5;
const float B_RATIO_LIMIT = 3.0;

// SMPTE ST 2084 (PQ), converting absolute luminance in nit to code values.
const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

vec3 pq_eotf_inv(vec3 x) {
    vec3 t = pow(x / pw, vec3(m1));
    return pow((c1 + c2 * t) / (1.0 + c3 * t), vec3(m2));
}

vec3 fetch_atlas_raw(ivec2 position) {
    return texelFetch(LUTS_raw, position, 0).rgb;
}

vec3 fetch_lut3d_raw(int lut, ivec3 texel) {
    ivec2 atlas_position;
    if (lut == RGB_TO_LAB_LUT) {
        atlas_position = ivec2(
            texel.x + texel.y * FORWARD_LUT_SIZE,
            RGB_TO_LAB_ROW + texel.z
        );
    } else {
        atlas_position = ivec2(
            texel.y + texel.z * REVERSE_CHROMA_LUT_SIZE,
            LAB_TO_RGB_ROW + texel.x
        );
    }
    return fetch_atlas_raw(atlas_position);
}

// Select the two middle vertices of the tetrahedron and sort the fractional
// coordinates into the corresponding interpolation order.
void select_tetrahedron(
    vec3 fraction,
    out ivec3 second_offset,
    out ivec3 third_offset,
    out vec3 weights
) {
    if (fraction.x >= fraction.y) {
        if (fraction.y >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.xyz;
        } else if (fraction.x >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.xzy;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.zxy;
        }
    } else {
        if (fraction.x >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.yxz;
        } else if (fraction.y >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.yzx;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.zyx;
        }
    }
}

vec3 sample_lut_tetrahedral(vec3 lut_coordinates, int lut) {
    ivec3 last_texel = lut == RGB_TO_LAB_LUT
        ? ivec3(FORWARD_LUT_LAST)
        : ivec3(
            REVERSE_LIGHTNESS_LUT_LAST,
            REVERSE_CHROMA_LUT_LAST,
            REVERSE_CHROMA_LUT_LAST
        );
    vec3 position = clamp(lut_coordinates, 0.0, 1.0) * vec3(last_texel);
    ivec3 base_texel = ivec3(floor(position));
    vec3 fraction = fract(position);

    ivec3 second_offset;
    ivec3 third_offset;
    vec3 weights;
    select_tetrahedron(fraction, second_offset, third_offset, weights);

    ivec3 texel0 = base_texel;
    ivec3 texel1 = min(base_texel + second_offset, last_texel);
    ivec3 texel2 = min(base_texel + third_offset, last_texel);
    ivec3 texel3 = min(base_texel + ivec3(1), last_texel);

    vec3 value0 = fetch_lut3d_raw(lut, texel0);
    vec3 value1 = fetch_lut3d_raw(lut, texel1);
    vec3 value2 = fetch_lut3d_raw(lut, texel2);
    vec3 value3 = fetch_lut3d_raw(lut, texel3);

    vec3 interpolated = value0
                      + weights.x * (value1 - value0)
                      + weights.y * (value2 - value1)
                      + weights.z * (value3 - value2);
    return LUTS_mul * interpolated;
}

// Rational signed shaper mapping [-limit, limit] to [0, 1], with additional
// precision around zero where most a/b values are concentrated.
float encode_signed_coordinate(float value, float limit) {
    float maximum_magnitude = limit / (limit + AB_RATIO_SCALE);
    float encoded_magnitude = abs(value) / (abs(value) + AB_RATIO_SCALE);
    float signed_magnitude = sign(value) * encoded_magnitude / maximum_magnitude;
    return clamp(0.5 + 0.5 * signed_magnitude, 0.0, 1.0);
}

float encode_output_lightness(float lightness) {
    float range = max(output_white_j - output_black_j, 1e-6);
    return clamp((lightness - output_black_j) / range, 0.0, 1.0);
}

vec3 RGB_to_lut_coordinates(vec3 rgb) {
    vec3 absolute_rgb = clamp(
        max(rgb, vec3(0.0)) * max(reference_white, 0.0),
        0.0,
        pw
    );
    return pq_eotf_inv(absolute_rgb);
}

vec3 LAB_to_lut_coordinates(vec3 lab) {
    float L = max(lab.x, 0.0);
    vec2 chroma_ratio = lab.yz / max(L, 1e-6);
    return vec3(
        encode_output_lightness(L),
        encode_signed_coordinate(chroma_ratio.x, A_RATIO_LIMIT),
        encode_signed_coordinate(chroma_ratio.y, B_RATIO_LIMIT)
    );
}

vec3 RGB_to_LAB(vec3 rgb) {
    vec3 coordinates = RGB_to_lut_coordinates(rgb);
    return sample_lut_tetrahedral(coordinates, RGB_TO_LAB_LUT);
}

vec3 apply_exposure(vec3 rgb) {
    return rgb * exposure_scale;
}

vec3 LAB_to_RGB(vec3 lab) {
    vec3 coordinates = LAB_to_lut_coordinates(lab);
    return sample_lut_tetrahedral(coordinates, LAB_TO_RGB_LUT);
}

float curve(float x) {
    float position = clamp(x, 0.0, 1.0) * float(CURVE_SIZE - 1);
    int lower_index = int(floor(position));
    int upper_index = min(lower_index + 1, CURVE_SIZE - 1);
    float weight = fract(position);
    float lower_value = fetch_atlas_raw(ivec2(lower_index, CURVE_ROW)).x;
    float upper_value = fetch_atlas_raw(ivec2(upper_index, CURVE_ROW)).x;
    return LUTS_mul * mix(lower_value, upper_value, weight);
}

float chroma_correction_attenuation(float x, float rate, float threshold) {
    float range = max(1.0 - threshold, 1e-6);
    float norm = clamp((x - threshold) / range, 0.0, 1.0);
    return pow(norm, 1.0 + rate * (1.0 - norm));
}

// based on the chroma correction method for ICtCp in BT.2390/BT.2408
// https://www.itu.int/pub/R-REP-BT.2408
//
// a power factor is added to increase correction rate.
//
// this is a correction in generic vividness and depth.
// V = sqrt(J^2 + C^2)
// D = sqrt((J_max - J)^2 + C^2)
//
// more specific definitions of V and D for Jzazbz,
// see the following links:
// https://doi.org/10.2352/ISSN.2169-2629.2018.26.96
// https://doi.org/10.2352/issn.2169-2629.2019.27.43
vec2 chroma_correction(vec2 ab, float l1, float l2) {
    float ratio_min = min(l1, l2) / max(max(l1, l2), 1e-6);
    float ratio_scaled = mix(1.0, ratio_min, chroma_correction_scaling);
    float ratio_safe = max(ratio_scaled, 0.0);
    return ab * chroma_correction_attenuation(
        ratio_safe,
        chroma_correction_rate,
        chroma_correction_threshold
    );
}

vec3 tone_mapping(vec3 lab) {
    float l2 = curve(lab.x);
    vec2 ab2 = chroma_correction(lab.yz, lab.x, l2);
    return vec3(l2, ab2);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    color.rgb = apply_exposure(color.rgb);
    color.rgb = RGB_to_LAB(color.rgb);
    color.rgb = tone_mapping(color.rgb);
    color.rgb = LAB_to_RGB(color.rgb);
    return color;
}


//!HOOK OUTPUT
//!BIND METERED
//!BIND METERED_TEMPORAL
//!BIND METADATA
//!BIND LUTS
//!BIND PREVIEW_HISTOGRAM
//!SAVE EMPTY
//!WIDTH 256
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!WHEN preview_metering
//!DESC metering (preview, histogram preparation)

// Histogram heights are constant across each four-pixel bin, while the tone
// curve varies once per plot column. Compute both here instead of repeating
// histogram integration and colour-space conversion along all 256 rows.

const uint PREVIEW_HISTOGRAM_SIZE = 64u;
const uint PREVIEW_HISTOGRAM_RAW_SIZE = 1024u;
const uint PREVIEW_HISTOGRAM_COLUMN_COUNT = 256u;
const uint PREVIEW_HISTOGRAM_SAMPLE_COUNT = 512u * 288u;
const float PREVIEW_HISTOGRAM_PLOT_WIDTH = 254.0;

const int PREVIEW_FORWARD_LUT_SIZE = 65;
const int PREVIEW_REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int PREVIEW_CURVE_ROW = PREVIEW_FORWARD_LUT_SIZE +
                              PREVIEW_REVERSE_LIGHTNESS_LUT_SIZE;
const int PREVIEW_CURVE_SIZE = 1024;

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;
const float m2_z = 1.7 * m2;
const float d = -0.56;
const float d0 = 1.6295499532821566e-11;

float pq_eotf(float x) {
    float t = pow(x, 1.0 / m2);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

float pq_eotf_inv(float x) {
    float t = pow(max(x, 0.0) / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

float iz_eotf_inv(float x) {
    float t = pow(max(x, 0.0) / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2_z);
}

float iz_eotf(float x) {
    float t = pow(max(x, 0.0), 1.0 / m2_z);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

float I_to_J(float I) {
    return ((1.0 + d) * I) / (1.0 + d * I) - d0;
}

float J_to_I(float J) {
    return (J + d0) / (1.0 + d - d * (J + d0));
}

// The 1D LUT is stored in Astra's J domain. For a neutral stimulus the H-K
// compensation is zero, so J <-> Iz <-> absolute luminance gives its PQ-domain
// transfer curve for direct comparison with the metering histogram.
float sample_preview_curve_j(float coordinate) {
    float position = clamp(coordinate, 0.0, 1.0) *
                     float(PREVIEW_CURVE_SIZE - 1);
    int lower_index = int(floor(position));
    int upper_index = min(lower_index + 1, PREVIEW_CURVE_SIZE - 1);
    float weight = fract(position);
    float lower_value = texelFetch(
        LUTS_raw,
        ivec2(lower_index, PREVIEW_CURVE_ROW),
        0
    ).x;
    float upper_value = texelFetch(
        LUTS_raw,
        ivec2(upper_index, PREVIEW_CURVE_ROW),
        0
    ).x;
    return LUTS_mul * mix(lower_value, upper_value, weight);
}

float preview_curve_pq(float pq_coordinate) {
    float absolute_input = pq_eotf(clamp(pq_coordinate, 0.0, 1.0));
    float input_j = I_to_J(iz_eotf_inv(absolute_input));
    float output_j = sample_preview_curve_j(input_j);
    float absolute_output = iz_eotf(J_to_I(output_j));
    return clamp(pq_eotf_inv(absolute_output), 0.0, 1.0);
}

float preview_unexposed_pq(float exposed_pq, float inverse_exposure) {
    float absolute_exposed = pq_eotf(clamp(exposed_pq, 0.0, 1.0));
    if (absolute_exposed <= 0.0)
        return 0.0;

    float absolute_unexposed = absolute_exposed * inverse_exposure;
    return clamp(pq_eotf_inv(min(absolute_unexposed, pw)), 0.0, 1.0);
}

// Convert one displayed, post-exposure histogram bin back to its source-PQ
// interval. Values clipped above PQ 1 after positive exposure belong to the
// final displayed bin, so that bin deliberately extends to source PQ 1.
vec2 preview_histogram_source_interval(uint displayed_index) {
    float inverse_size = 1.0 / float(PREVIEW_HISTOGRAM_SIZE);
    float inverse_exposure = 1.0 / exposure_scale;
    float exposed_lower = float(displayed_index) * inverse_size;
    float exposed_upper = float(displayed_index + 1u) * inverse_size;
    float source_lower = preview_unexposed_pq(
        exposed_lower,
        inverse_exposure
    );
    float source_upper = displayed_index + 1u == PREVIEW_HISTOGRAM_SIZE
        ? 1.0
        : preview_unexposed_pq(exposed_upper, inverse_exposure);
    return vec2(source_lower, max(source_upper, source_lower));
}

float preview_histogram_count(vec2 source_interval) {
    vec2 interval = source_interval * float(PREVIEW_HISTOGRAM_RAW_SIZE);
    uint first = min(uint(floor(interval.x)),
                     PREVIEW_HISTOGRAM_RAW_SIZE - 1u);
    uint end = min(uint(ceil(interval.y)), PREVIEW_HISTOGRAM_RAW_SIZE);
    float count = 0.0;

    for (uint i = first; i < end; i++) {
        float overlap = max(
            min(interval.y, float(i + 1u)) - max(interval.x, float(i)),
            0.0
        );
        count += float(metered_histogram[i]) * overlap;
    }

    return count;
}

float preview_reference_histogram_count(
    vec2 source_interval,
    float total
) {
    vec2 interval = source_interval * float(PREVIEW_HISTOGRAM_SIZE);
    uint first = min(uint(floor(interval.x)), PREVIEW_HISTOGRAM_SIZE - 1u);
    uint end = min(uint(ceil(interval.y)), PREVIEW_HISTOGRAM_SIZE);
    float count = 0.0;

    for (uint i = first; i < end; i++) {
        float overlap = max(
            min(interval.y, float(i + 1u)) - max(interval.x, float(i)),
            0.0
        );
        count += metered_reference_histogram[i] * total * overlap;
    }

    return count;
}

float preview_histogram_height(float count, float total) {
    return log2(1.0 + count) / max(log2(1.0 + total), 1e-6);
}

void prepare_preview_histogram_bin(uint index) {
    float total = float(PREVIEW_HISTOGRAM_SAMPLE_COUNT);
    vec2 source_interval = preview_histogram_source_interval(index);
    float current_count = preview_histogram_count(source_interval);
    float reference_count = metered_histogram_valid > 0u
        ? preview_reference_histogram_count(source_interval, total)
        : current_count;
    preview_histogram_current[index] = preview_histogram_height(
        current_count,
        total
    );
    preview_histogram_reference[index] = preview_histogram_height(
        reference_count,
        total
    );
}

void prepare_preview_curve_column(uint index) {
    float local_x = float(index) + 0.5;
    float pq_input = clamp(
        (local_x - 1.0) / PREVIEW_HISTOGRAM_PLOT_WIDTH,
        0.0,
        1.0
    );
    preview_histogram_curve[index] = preview_curve_pq(pq_input);
}

void hook() {
    uint index = gl_GlobalInvocationID.x;
    if (index < PREVIEW_HISTOGRAM_SIZE)
        prepare_preview_histogram_bin(index);
    if (index < PREVIEW_HISTOGRAM_COLUMN_COUNT)
        prepare_preview_curve_column(index);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND METERING
//!BIND METERED
//!BIND METADATA
//!BIND VECTORSCOPE
//!BIND PREVIEW_HISTOGRAM
//!WHEN preview_metering
//!DESC metering (preview)

const float JND = 1.0 / 720.0;

float to_float(uint x) {
    return float(x) / 4095.0;
}

vec4 draw_highlights(float value) {
    vec3 metrics = vec3(
        to_float(metered_max_i),
        to_float(metered_avg_i),
        to_float(metered_min_i)
    );
    vec3 matches = 1.0 - step(vec3(5.0 * JND), abs(metrics - value));

    if (enable_metering <= 1)
        matches.y = 0.0;

    float opacity = 0.75 * max(max(matches.x, matches.y), matches.z);
    return vec4(matches, opacity);
}

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

float pq_eotf(float x) {
    float t = pow(x, 1.0 / m2);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

// 3x5 bitmap font rendering
const float CHAR_W = 3.0;
const float CHAR_H = 5.0;
const float SPACING = 1.0;
const float MARGIN = 8.0;
const float PAD = 2.0;
const float SCALE = 4.0;
const float LINE_H = CHAR_H + 2.0;

// The preview histogram uses the same 64-bin grouping as scene-change
// detection. Cyan bars are the current frame, the orange trace is the
// timestamp-smoothed reference, and the yellow curve is the J-domain LUT
// transformed onto the same PQ horizontal and vertical axes.
const uint PREVIEW_HISTOGRAM_SIZE = 64u;
const float PREVIEW_HISTOGRAM_BIN_WIDTH = 4.0;
const float PREVIEW_HISTOGRAM_EXTENT = 256.0;
const uint PREVIEW_VECTORSCOPE_SIZE = 128u;
const float PREVIEW_VECTORSCOPE_EXTENT = 256.0;
const float PREVIEW_VECTORSCOPE_AB_RANGE = 0.36;
const float PREVIEW_PANEL_GAP = 6.0 * SCALE;
const float PREVIEW_VECTORSCOPE_COLOR_SCALE = 65535.0;

// ITU-R BT.2525-0 HLG reference for Fitzpatrick skin types 1-4. Saturation
// is C / Cmax, where Cmax is the largest Jzazbz chroma of the Rec. 2020
// primaries at the 1000-nit HLG nominal peak. The report's H-K-independent
// a/b coordinates match this vectorscope even when J compensation is active.
const vec2 BT2525_SKIN_HUE_RANGE = vec2(35.4, 70.6);
const vec2 BT2525_SKIN_SATURATION_RANGE = vec2(0.085, 0.281);
const float BT2525_HLG_MAX_PRIMARY_CHROMA = 0.34074623;

float cross_2d(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}

float distance_to_ray(vec2 point, vec2 direction) {
    if (dot(point, direction) < 0.0)
        return 1e6;
    return abs(cross_2d(direction, point));
}

vec3 draw_skin_tone_reference(vec2 plane, float line_width) {
    vec2 hue_range = radians(BT2525_SKIN_HUE_RANGE);
    vec2 radius_range = BT2525_SKIN_SATURATION_RANGE *
                        BT2525_HLG_MAX_PRIMARY_CHROMA /
                        PREVIEW_VECTORSCOPE_AB_RANGE;
    float radius = length(plane);
    vec2 minimum_hue_direction = vec2(
        cos(hue_range.x),
        sin(hue_range.x)
    );
    vec2 maximum_hue_direction = vec2(
        cos(hue_range.y),
        sin(hue_range.y)
    );
    bool inside_hue = cross_2d(minimum_hue_direction, plane) >= 0.0 &&
                      cross_2d(maximum_hue_direction, plane) <= 0.0;
    bool inside_radius = radius >= radius_range.x &&
                         radius <= radius_range.y;
    float hue_edge_distance = min(
        distance_to_ray(plane, minimum_hue_direction),
        distance_to_ray(plane, maximum_hue_direction)
    );
    float radius_edge_distance = min(
        abs(radius - radius_range.x),
        abs(radius - radius_range.y)
    );

    vec3 tint = inside_hue && inside_radius
        ? vec3(0.10, 0.04, 0.01)
        : vec3(0.0);
    if ((inside_radius && hue_edge_distance < line_width) ||
        (inside_hue && radius_edge_distance < line_width)) {
        tint = max(tint, vec3(0.62, 0.28, 0.07));
    }

    float center_hue = 0.5 * (hue_range.x + hue_range.y);
    vec2 center_direction = vec2(cos(center_hue), sin(center_hue));
    float center_distance = distance_to_ray(plane, center_direction);
    float pixel_radius = radius * 0.5 * PREVIEW_VECTORSCOPE_EXTENT;
    bool center_dash = fract(pixel_radius / 8.0) < 0.5;
    if (radius <= 1.0 && center_dash &&
        center_distance < line_width) {
        tint = max(tint, vec3(0.82, 0.46, 0.12));
    }

    return tint;
}

vec4 draw_histogram(vec2 px) {
    vec2 origin = vec2(MARGIN * SCALE);
    vec2 padding = vec2(PAD * SCALE);
    vec2 panel_min = origin - padding;
    vec2 panel_max = origin + vec2(PREVIEW_HISTOGRAM_EXTENT) + padding;

    if (any(lessThan(px, panel_min)) || any(greaterThan(px, panel_max)))
        return vec4(0.0);

    vec2 local = px - origin;

    if (local.x < 0.0 || local.x >= PREVIEW_HISTOGRAM_EXTENT ||
        local.y < 0.0 || local.y >= PREVIEW_HISTOGRAM_EXTENT)
        return vec4(0.0, 0.0, 0.0, 1.0);

    uint index = min(
        uint(local.x / PREVIEW_HISTOGRAM_BIN_WIDTH),
        PREVIEW_HISTOGRAM_SIZE - 1u
    );
    uint column = min(
        uint(floor(local.x)),
        uint(PREVIEW_HISTOGRAM_EXTENT) - 1u
    );
    float current_height = preview_histogram_current[index];
    float reference_height = preview_histogram_reference[index];
    float plot_width = PREVIEW_HISTOGRAM_EXTENT - 2.0;
    float plot_height = PREVIEW_HISTOGRAM_EXTENT - 2.0;
    float pq_input = clamp((local.x - 1.0) / plot_width, 0.0, 1.0);
    float pq_output = preview_histogram_curve[column];
    float level = 1.0 - clamp((local.y - 1.0) / plot_height, 0.0, 1.0);

    vec3 tint = vec3(0.0);

    float grid_distance = abs(fract(level * 4.0 + 0.5) - 0.5);
    if (grid_distance < 0.012)
        tint = vec3(0.10);

    if (level <= current_height)
        tint = vec3(0.12, 0.72, 0.92);

    if (abs(level - reference_height) <= 1.5 / plot_height)
        tint = vec3(1.0, 0.55, 0.12);

    if (abs(level - pq_input) <= 0.75 / plot_height)
        tint = vec3(0.48);

    if (abs(level - pq_output) <= 1.5 / plot_height)
        tint = vec3(1.0, 0.78, 0.12);

    return vec4(tint, 1.0);
}

vec4 draw_vectorscope(vec2 px) {
    vec2 origin = vec2(
        MARGIN * SCALE,
        MARGIN * SCALE + PREVIEW_HISTOGRAM_EXTENT + PREVIEW_PANEL_GAP
    );
    vec2 padding = vec2(PAD * SCALE);
    vec2 panel_min = origin - padding;
    vec2 panel_max = origin + vec2(PREVIEW_VECTORSCOPE_EXTENT) + padding;

    if (any(lessThan(px, panel_min)) || any(greaterThan(px, panel_max)))
        return vec4(0.0);

    vec2 local = px - origin;
    if (local.x < 0.0 || local.x >= PREVIEW_VECTORSCOPE_EXTENT ||
        local.y < 0.0 || local.y >= PREVIEW_VECTORSCOPE_EXTENT)
        return vec4(0.0, 0.0, 0.0, 1.0);

    vec2 unit = (local + 0.5) / PREVIEW_VECTORSCOPE_EXTENT;
    uvec2 bin = min(
        uvec2(unit * float(PREVIEW_VECTORSCOPE_SIZE)),
        uvec2(PREVIEW_VECTORSCOPE_SIZE - 1u)
    );
    uint index = bin.y * PREVIEW_VECTORSCOPE_SIZE + bin.x;
    float count = float(vectorscope_histogram[index]);
    float density = clamp(log2(1.0 + count) / 8.0, 0.0, 1.0);
    vec3 color_sum = vec3(
        vectorscope_color_r[index],
        vectorscope_color_g[index],
        vectorscope_color_b[index]
    );
    vec3 average_color = color_sum / max(
        count * PREVIEW_VECTORSCOPE_COLOR_SCALE,
        1.0
    );
    float color_peak = max(
        max(average_color.r, average_color.g),
        average_color.b
    );
    vec3 trace_color = color_peak > 1e-4
        ? average_color / color_peak
        : vec3(1.0);

    vec2 plane = vec2(2.0 * unit.x - 1.0, 1.0 - 2.0 * unit.y);
    float radius = length(plane);
    float line_width = 2.0 / PREVIEW_VECTORSCOPE_EXTENT;
    float axis_distance = min(abs(plane.x), abs(plane.y));
    float ring_distance = min(abs(radius - 0.5), abs(radius - 1.0));

    vec3 tint = vec3(0.0);
    if (axis_distance < line_width || ring_distance < line_width)
        tint = vec3(0.10);

    tint = max(tint, draw_skin_tone_reference(plane, line_width));
    tint = max(tint, trace_color * sqrt(density));
    return vec4(tint, 1.0);
}

const uint FONT_DIGITS[10] = uint[10](
    0x7B6Fu, 0x749Au, 0x73E7u, 0x79E7u, 0x49EDu,
    0x79CFu, 0x7BCFu, 0x4927u, 0x7BEFu, 0x79EFu
);
const uint FONT_LETTERS[26] = uint[26](
    0x5BEFu, 0x3AEBu, 0x724Fu, 0x3B6Bu, 0x72CFu, 0x12CFu, 0x7B4Fu,
    0x5BEDu, 0x7497u, 0x7B24u, 0x5AEDu, 0x7249u, 0x5BFDu, 0x5B6Fu,
    0x7B6Fu, 0x13EFu, 0x49EFu, 0x5AEFu, 0x388Eu, 0x2497u, 0x7B6Du,
    0x256Du, 0x5FEDu, 0x5AADu, 0x24ADu, 0x72A7u
);
const uint FONT_COLON = 0x0410u;
const uint FONT_DOT   = 0x2000u;
const uint FONT_MINUS = 0x01C0u;
const uint FONT_SPACE = 0x0000u;
const uint FONT_TOFU  = 0x7FFFu;

const int CH_SPACE = 32;
const int CH_MINUS = 45;
const int CH_DOT   = 46;
const int CH_0 = 48;
const int CH_9 = 57;
const int CH_COLON = 58;
const int CH_A = 65;
const int CH_E = 69;
const int CH_G = 71;
const int CH_I = 73;
const int CH_M = 77;
const int CH_N = 78;
const int CH_V = 86;
const int CH_X = 88;
const int CH_Z = 90;

uint get_glyph(int ch) {
    if (ch >= CH_0 && ch <= CH_9)
        return FONT_DIGITS[ch - CH_0];
    if (ch >= CH_A && ch <= CH_Z)
        return FONT_LETTERS[ch - CH_A];

    if (ch == CH_SPACE) return FONT_SPACE;
    if (ch == CH_MINUS) return FONT_MINUS;
    if (ch == CH_DOT)   return FONT_DOT;
    if (ch == CH_COLON) return FONT_COLON;
    return FONT_TOFU;
}

bool glyph_pixel(uint glyph, vec2 p) {
    if (p.x < 0.0 || p.x >= CHAR_W || p.y < 0.0 || p.y >= CHAR_H) return false;
    uint bit = uint(p.y) * 3u + uint(p.x);
    return (glyph & (1u << bit)) != 0u;
}

vec4 draw_char(int ch, vec2 local, inout float cx) {
    vec2 cp = local - vec2(cx, 0.0);
    cx += CHAR_W + SPACING;
    if (cp.x >= 0.0 && cp.x < CHAR_W && cp.y >= 0.0 && cp.y < CHAR_H) {
        if (glyph_pixel(get_glyph(ch), cp))
            return vec4(1.0, 1.0, 1.0, 1.0);
    }
    return vec4(0.0);
}

float number_width(float value) {
    float abs_val = min(abs(value), 99999.99);
    uint int_part = uint(abs_val * 100.0 + 0.5) / 100u;

    uint digits = 1u;
    if      (int_part >= 10000u) digits = 5u;
    else if (int_part >= 1000u)  digits = 4u;
    else if (int_part >= 100u)   digits = 3u;
    else if (int_part >= 10u)    digits = 2u;

    float characters = float(digits + 3u) + (value < 0.0 ? 1.0 : 0.0);
    return characters * (CHAR_W + SPACING);
}

float pq_number_width(float value) {
    // PQ codes where two-decimal formatting rounds up to 10, 100, 1000,
    // and 10000 nits respectively.
    const vec4 digit_thresholds = vec4(
        0.299659661,
        0.508073403,
        0.751826551,
        0.999999948
    );
    float digits = 1.0 + dot(step(digit_thresholds, vec4(value)), vec4(1.0));
    return (digits + 3.0) * (CHAR_W + SPACING);
}

vec4 draw_number(float value, vec2 local, inout float cx) {
    bool negative = value < 0.0;
    float abs_val = min(abs(value), 99999.99);

    uint fixed_value = uint(abs_val * 100.0 + 0.5);
    uint int_part = fixed_value / 100u;
    uint dec_part = fixed_value - int_part * 100u;

    uint d0 = (int_part / 10000u) % 10u;
    uint d1 = (int_part / 1000u) % 10u;
    uint d2 = (int_part / 100u) % 10u;
    uint d3 = (int_part / 10u) % 10u;
    uint d4 = int_part % 10u;
    uint d5 = dec_part / 10u;
    uint d6 = dec_part % 10u;

    uint first = 4u;
    if (d0 > 0u) first = 0u;
    else if (d1 > 0u) first = 1u;
    else if (d2 > 0u) first = 2u;
    else if (d3 > 0u) first = 3u;

    vec4 r = vec4(0.0);

    if (negative)    r = max(r, draw_char(CH_MINUS, local, cx));
    if (first <= 0u) r = max(r, draw_char(int(d0) + CH_0, local, cx));
    if (first <= 1u) r = max(r, draw_char(int(d1) + CH_0, local, cx));
    if (first <= 2u) r = max(r, draw_char(int(d2) + CH_0, local, cx));
    if (first <= 3u) r = max(r, draw_char(int(d3) + CH_0, local, cx));
    r = max(r, draw_char(int(d4) + CH_0, local, cx));
    r = max(r, draw_char(CH_DOT, local, cx));
    r = max(r, draw_char(int(d5) + CH_0, local, cx));
    r = max(r, draw_char(int(d6) + CH_0, local, cx));

    return r;
}

// Draw a labeled row: "LABEL:value".
vec4 draw_row(float value, vec2 origin, vec2 px, int c0, int c1, int c2) {
    float label_width = 4.0 * (CHAR_W + SPACING);
    float width = label_width + number_width(value);
    vec2 local = (px - origin) / SCALE;

    if (local.x < 0.0 || local.x >= width ||
        local.y < 0.0 || local.y >= CHAR_H)
        return vec4(0.0);

    vec4 r = vec4(0.0);
    float cx = 0.0;

    if (local.x < label_width) {
        r = max(r, draw_char(c0, local, cx));
        r = max(r, draw_char(c1, local, cx));
        r = max(r, draw_char(c2, local, cx));
        r = max(r, draw_char(CH_COLON, local, cx));
    } else {
        cx = label_width;
        r = max(r, draw_number(value, local, cx));
    }

    return r;
}

vec4 draw_metrics_panel(vec2 px) {
    // The longest row contains four label characters and a signed 5.2 number.
    const float MAX_ROW_WIDTH = 13.0 * (CHAR_W + SPACING);
    float metrics_bottom = HOOKED_size.y - MARGIN * SCALE - CHAR_H * SCALE;
    float metrics_top = metrics_bottom - 3.0 * LINE_H * SCALE;
    float chart_stack_bottom = MARGIN * SCALE +
                               PREVIEW_HISTOGRAM_EXTENT +
                               PREVIEW_PANEL_GAP +
                               PREVIEW_VECTORSCOPE_EXTENT + PAD * SCALE;
    float metrics_x = metrics_top - PAD * SCALE < chart_stack_bottom
        ? MARGIN * SCALE + PREVIEW_VECTORSCOPE_EXTENT + PREVIEW_PANEL_GAP
        : MARGIN * SCALE;
    vec2 o3 = vec2(metrics_x, metrics_bottom);
    vec2 o0 = o3 - vec2(0.0, 3.0 * LINE_H * SCALE);
    vec2 panel_min = o0 - vec2(PAD * SCALE);
    vec2 panel_max = vec2(
        o0.x + (MAX_ROW_WIDTH + PAD) * SCALE,
        o3.y + (CHAR_H + PAD) * SCALE
    );

    if (any(lessThan(px, panel_min)) || any(greaterThan(px, panel_max)))
        return vec4(0.0);

    float label_width = 4.0 * (CHAR_W + SPACING);
    vec4 row_widths = label_width + vec4(
        pq_number_width(input_max_i),
        pq_number_width(input_min_i),
        pq_number_width(input_avg_i),
        number_width(ev)
    );
    float max_w = max(max(row_widths.x, row_widths.y),
                      max(row_widths.z, row_widths.w));

    if (px.x > o0.x + (max_w + PAD) * SCALE)
        return vec4(0.0);

    vec4 r = vec4(0.0, 0.0, 0.0, 1.0);
    float row_stride = LINE_H * SCALE;
    int row = int(floor((px.y - o0.y) / row_stride));

    if (row >= 0 && row < 4) {
        vec2 origin = o0 + vec2(0.0, float(row) * row_stride);
        vec2 local = px - origin;

        if (local.x >= 0.0 && local.x < row_widths[row] * SCALE &&
            local.y >= 0.0 && local.y < CHAR_H * SCALE) {
            if (row == 0)
                r = max(r, draw_row(pq_eotf(input_max_i), origin, px, CH_M, CH_A, CH_X));
            else if (row == 1)
                r = max(r, draw_row(pq_eotf(input_min_i), origin, px, CH_M, CH_I, CH_N));
            else if (row == 2)
                r = max(r, draw_row(pq_eotf(input_avg_i), origin, px, CH_A, CH_V, CH_G));
            else
                r = max(r, draw_row(ev, origin, px, CH_E, CH_V, CH_SPACE));
        }
    }

    return r;
}

vec3 composite_preview_layer(vec3 color, vec4 layer) {
    return mix(color, layer.rgb, layer.a);
}

vec2 preview_metering_position(vec2 position) {
    // Map the current output position back into the landscape analysis map.
    // The caller must provide HOOKED_pos: METERING_pos is already local to
    // the bound landscape texture and is not a portrait output coordinate.
    return HOOKED_size.y > HOOKED_size.x
        ? vec2(1.0 - position.y, position.x)
        : position;
}

vec2 preview_ui_position(vec2 position) {
    // Landscape output already matches the preview layout. Portrait output
    // needs only a vertical UI flip; its metering-map rotation is handled
    // independently by preview_metering_position().
    return HOOKED_size.y > HOOKED_size.x
        ? vec2(position.x, 1.0 - position.y)
        : position;
}

vec4 render_metering_preview() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec2 px = preview_ui_position(HOOKED_pos) * HOOKED_size;
    float value = METERING_tex(
        preview_metering_position(HOOKED_pos)
    ).x;

    color.rgb = composite_preview_layer(color.rgb, draw_highlights(value));
    color.rgb = composite_preview_layer(color.rgb, draw_histogram(px));
    color.rgb = composite_preview_layer(color.rgb, draw_vectorscope(px));
    color.rgb = composite_preview_layer(color.rgb, draw_metrics_panel(px));

    return color;
}

vec4 hook() { return render_metering_preview(); }
