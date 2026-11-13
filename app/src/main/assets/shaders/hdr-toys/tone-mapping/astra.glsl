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
//!MINIMUM 0.0
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

//!PARAM exposure_value_offset
//!TYPE float
//!MINIMUM -64
//!MAXIMUM  64
0.0

//!PARAM shadow_weight
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.4

//!PARAM shadow_overshoot
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
0.0

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

//!PARAM spatial_stable_level
//!TYPE DEFINE
//!MINIMUM 0
//!MAXIMUM 5
3

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

//!PARAM force_metering
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM preview_metering
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!BUFFER METERED
//!VAR uint metered_max_rgb
//!VAR uint metered_max_i
//!VAR uint metered_min_i
//!VAR uint metered_avg_i
//!VAR uint metered_median_i
//!VAR uint metered_diffuse_white_i
//!VAR uint metered_histogram[1024]
//!VAR uint metered_histogram_count
//!VAR uint metered_scene_median_bin
//!VAR uint metered_scene_diffuse_white_bin
//!VAR float metered_zone_average[144]
//!VAR float metered_zone_spread[144]
//!VAR float metered_zone_preview_weight[144]
//!VAR float metered_histogram_average
//!VAR float metered_matrix_average
//!VAR float metered_matrix_blend
//!VAR uint metered_zones_valid
//!STORAGE

//!BUFFER SCENE_DSM
//!VAR float dsm_weights[64]
//!VAR float dsm_age[64]
//!VAR float dsm_precision[256]
//!VAR float dsm_information[256]
//!VAR uint dsm_bank
//!VAR uint dsm_count
//!VAR uint dsm_valid
//!VAR uint dsm_last_pts
//!VAR float dsm_accepted_age
//!STORAGE

//!BUFFER SCENE_RESPONSE
//!VAR float scene_new_probability
//!VAR float scene_short_probability
//!VAR float scene_confirmed_probability
//!VAR float scene_fast_strength
//!VAR float scene_discarded_mass
//!VAR float scene_outlier_probability
//!VAR float scene_change_log_bayes
//!VAR uint scene_hard_cut
//!VAR uint scene_bootstrap
//!VAR uint scene_observation_pts
//!VAR uint scene_observation_valid
//!VAR uint scene_state_recovered
//!STORAGE

//!BUFFER EXPOSURE_TEMPORAL
//!VAR float exposure_temporal_ev
//!VAR float exposure_temporal_variance
//!VAR uint exposure_temporal_pts
//!VAR uint exposure_temporal_valid
//!VAR uint exposure_protection_pts
//!VAR uint exposure_protection_valid
//!VAR uint exposure_protection_state
//!VAR uint exposure_protection_run
//!VAR float exposure_previous_target
//!VAR uint exposure_target_valid
//!STORAGE

//!BUFFER CURVE_TEMPORAL
//!VAR float curve_temporal_values[1024]
//!VAR float curve_display_values[1024]
//!VAR float curve_temporal_alpha
//!VAR uint curve_temporal_reset
//!VAR uint curve_temporal_bypass
//!VAR uint curve_temporal_pts
//!VAR uint curve_temporal_valid
//!STORAGE

//!BUFFER METADATA
//!VAR float input_max_i
//!VAR float input_min_i
//!VAR float input_avg_i
//!VAR float exposure_ev
//!VAR float exposure_scale
//!VAR float exposed_max_i
//!VAR float exposed_min_i
//!VAR float input_max_j
//!VAR float input_min_j
//!VAR float output_max_j
//!VAR float output_min_j
//!VAR float output_reverse_max_jhk
//!STORAGE

//!BUFFER PREVIEW_METRICS
//!VAR uint preview_metrics_count
//!VAR float preview_metrics_max_width
//!VAR float preview_metrics_widths[8]
//!VAR uint preview_metrics_glyphs[104]
//!STORAGE

//!BUFFER PREVIEW_HISTOGRAM
//!VAR float preview_histogram_current[64]
//!VAR float preview_histogram_curve[256]
//!STORAGE

//!BUFFER PREVIEW_VECTORSCOPE
//!VAR uint preview_vectorscope_bins[36864]
//!STORAGE

//!HOOK OUTPUT
//!BIND HOOKED
//!SAVE METERING
//!COMPONENTS 2
//!WHEN enable_metering 0 > max_pq_y 0 > ! scene_max_r 0 > scene_max_g 0 > + scene_max_b 0 > + ! * force_metering + * preview_metering +
//!DESC metering (intensity map)

// Keep the peak-metadata test aligned with resolve_metering_metrics().
// Preview runs the metering chain without selecting its results for normal output.
// BIND METERING gates consumers on the availability of the current map.
// WHEN is evaluated before BIND; do not query a conditionally absent texture.
// Keep the shader directive marker (two slashes followed by an exclamation
// mark) out of GLSL body comments to avoid it being parsed as a directive.

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

// Map NaN and values <= lower_bound to lower_bound; cap larger values.
// Requires finite bounds with lower_bound <= upper_bound.
float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

// Encode reference-white-relative linear light as normalized PQ.
float metering_code(float relative) {
    return pq_eotf_inv(
        sanitize_bounded(relative * reference_white, 0.0, pw)
    );
}

float metering_intensity(vec3 rgb) {
    return metering_code(RGB_to_Y(rgb));
}

float metering_max_rgb(vec3 rgb) {
    return metering_code(max(max(rgb.r, rgb.g), rgb.b));
}

// METERING is a 2-component texture: .x carries the metering intensity,
// .y the maximum RGB channel in PQ. Downstream passes read only .xy; the
// .zw written here and by the blur pair are dropped by the format.
vec4 hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    return vec4(
        metering_intensity(rgb),
        metering_max_rgb(rgb),
        0.0,
        1.0
    );
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 1024 > OUTPUT.h 1024 > + OUTPUT.w 576 > OUTPUT.h 576 > * +
//!DESC metering (spatial stabilization, halve 1)

// Halve progressively to reduce aliasing before the fixed-size analysis.
// Test both dimensions so the thresholds also apply to portrait input.
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 2048 > OUTPUT.h 2048 > + OUTPUT.w 1152 > OUTPUT.h 1152 > * +
//!DESC metering (spatial stabilization, halve 2)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 4096 > OUTPUT.h 4096 > + OUTPUT.w 2304 > OUTPUT.h 2304 > * +
//!DESC metering (spatial stabilization, halve 3)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w 2 /
//!HEIGHT METERING.h 2 /
//!WHEN OUTPUT.w 8192 > OUTPUT.h 8192 > + OUTPUT.w 4608 > OUTPUT.h 4608 > * +
//!DESC metering (spatial stabilization, halve 4)
vec4 hook() { return METERING_tex(METERING_pos); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH 512
//!HEIGHT 288
//!DESC metering (spatial stabilization, downscaling)

vec2 metering_source_position(vec2 position, bool portrait) {
    // Rotate portrait analysis clockwise into the landscape metering layout.
    return portrait
        ? vec2(position.y, 1.0 - position.x)
        : position;
}

vec2 sample_metering_oriented(vec2 position, bool portrait) {
    return (METERING_mul * textureLod(
        METERING_raw,
        metering_source_position(position, portrait),
        0.0
    )).xy;
}

vec3 metering_area_axis(float position, float scale, float size) {
    if (scale <= 1.0) {
        return vec3(position, position, 0.0);
    }

    float left = position * size - 0.5 * scale;
    float first = floor(left);
    float right = left + scale;
    float w0 = min(first + 1.0, right) - left;
    float w1 = max(min(first + 2.0, right) - (first + 1.0), 0.0);
    float w2 = max(right - (first + 2.0), 0.0);
    float pair = w0 + w1;

    return vec3(
        (first + w1 / pair + 0.5) / size,
        (first + 2.5) / size,
        w2 / scale
    );
}

vec2 sample_metering_downscaled() {
    const vec2 target_size = vec2(512.0, 288.0);
    bool portrait = METERING_size.y > METERING_size.x;
    vec2 oriented_size = portrait ? METERING_size.yx : METERING_size;
    vec2 scale = oriented_size / target_size;

    if (all(lessThanEqual(scale, vec2(1.0)))) {
        return sample_metering_oriented(METERING_pos, portrait);
    }

    if (any(greaterThan(scale, vec2(2.0)))) {
        vec2 offset = 0.5 * max(scale - vec2(1.0), vec2(0.0)) / oriented_size;
        return 0.25 * (
            sample_metering_oriented(METERING_pos + vec2(-offset.x, -offset.y), portrait) +
            sample_metering_oriented(METERING_pos + vec2( offset.x, -offset.y), portrait) +
            sample_metering_oriented(METERING_pos + vec2(-offset.x,  offset.y), portrait) +
            sample_metering_oriented(METERING_pos + vec2( offset.x,  offset.y), portrait)
        );
    }

    vec3 x = metering_area_axis(METERING_pos.x, scale.x, oriented_size.x);
    vec3 y = metering_area_axis(METERING_pos.y, scale.y, oriented_size.y);
    vec2 top = mix(
        sample_metering_oriented(vec2(x.x, y.x), portrait),
        sample_metering_oriented(vec2(x.y, y.x), portrait),
        x.z
    );
    vec2 bottom = mix(
        sample_metering_oriented(vec2(x.x, y.y), portrait),
        sample_metering_oriented(vec2(x.y, y.y), portrait),
        x.z
    );
    return mix(top, bottom, y.z);
}

vec4 hook() { return vec4(sample_metering_downscaled(), 0.0, 1.0); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_level 0 >
//!COMPUTE 16 16 16 16
//!DESC metering (spatial stabilization, blur)

// One workgroup produces a 16x16 tile, including both separable directions.
// Clamp the source halo before filtering and keep the horizontal intermediate
// in shared FP32 memory, avoiding a second dispatch and intermediate texture.
// Sigma is 1.25^(level - 1), truncated at ceil(3 * sigma). The normalized
// coefficients below are evaluated in FP64 and rounded to shader floats.
// DEFINE specializes the array extents and removes inactive kernel branches.
// Keep filter_horizontal and filter_vertical coefficients synchronized.

#if spatial_stable_level <= 1
const int radius = 3;
#elif spatial_stable_level == 2
const int radius = 4;
#elif spatial_stable_level == 3
const int radius = 5;
#elif spatial_stable_level == 4
const int radius = 6;
#else
const int radius = 8;
#endif


const int tile_width = 16 + 2 * radius;
shared vec2 tile[tile_width * tile_width];
shared vec2 horizontal_tile[16 * tile_width];

vec2 filter_horizontal(int center) {
    const int stride = 1;
#if spatial_stable_level <= 1
    vec2 sum = tile[center] * 0.39905027965245493;
    sum += (tile[center - 1 * stride] +
            tile[center + 1 * stride]) * 0.24203622937611435;
    sum += (tile[center - 2 * stride] +
            tile[center + 2 * stride]) * 0.054005582622414498;
    sum += (tile[center - 3 * stride] +
            tile[center + 3 * stride]) * 0.0044330481752437459;
#elif spatial_stable_level == 2
    vec2 sum = tile[center] * 0.31922423439085301;
    sum += (tile[center - 1 * stride] +
            tile[center + 1 * stride]) * 0.2318043704135041;
    sum += (tile[center - 2 * stride] +
            tile[center + 2 * stride]) * 0.088756244369270459;
    sum += (tile[center - 3 * stride] +
            tile[center + 3 * stride]) * 0.017919576688438448;
    sum += (tile[center - 4 * stride] +
            tile[center + 4 * stride]) * 0.0019076913333604809;
#elif spatial_stable_level == 3
    vec2 sum = tile[center] * 0.25541096969073107;
    sum += (tile[center - 1 * stride] +
            tile[center + 1 * stride]) * 0.20811147917447398;
    sum += (tile[center - 2 * stride] +
            tile[center + 2 * stride]) * 0.11258110482738368;
    sum += (tile[center - 3 * stride] +
            tile[center + 3 * stride]) * 0.040434116389630952;
    sum += (tile[center - 4 * stride] +
            tile[center + 4 * stride]) * 0.0096414729606384107;
    sum += (tile[center - 5 * stride] +
            tile[center + 5 * stride]) * 0.0015263418025074776;
#elif spatial_stable_level == 4
    vec2 sum = tile[center] * 0.20441532859407782;
    sum += (tile[center - 1 * stride] +
            tile[center + 1 * stride]) * 0.17930384925879744;
    sum += (tile[center - 2 * stride] +
            tile[center + 2 * stride]) * 0.12100911201124059;
    sum += (tile[center - 3 * stride] +
            tile[center + 3 * stride]) * 0.062834598207420683;
    sum += (tile[center - 4 * stride] +
            tile[center + 4 * stride]) * 0.025103363688158426;
    sum += (tile[center - 5 * stride] +
            tile[center + 5 * stride]) * 0.0077164456396147515;
    sum += (tile[center - 6 * stride] +
            tile[center + 6 * stride]) * 0.001824966897729268;
#else
    vec2 sum = tile[center] * 0.16348116029814891;
    sum += (tile[center - 1 * stride] +
            tile[center + 1 * stride]) * 0.15032681281591539;
    sum += (tile[center - 2 * stride] +
            tile[center + 2 * stride]) * 0.11688066256203568;
    sum += (tile[center - 3 * stride] +
            tile[center + 3 * stride]) * 0.076839823134770308;
    sum += (tile[center - 4 * stride] +
            tile[center + 4 * stride]) * 0.042713732645968346;
    sum += (tile[center - 5 * stride] +
            tile[center + 5 * stride]) * 0.020076415263730153;
    sum += (tile[center - 6 * stride] +
            tile[center + 6 * stride]) * 0.0079788865860050892;
    sum += (tile[center - 7 * stride] +
            tile[center + 7 * stride]) * 0.0026812412389485853;
    sum += (tile[center - 8 * stride] +
            tile[center + 8 * stride]) * 0.00076184560355203445;
#endif
    return sum;
}

vec2 filter_vertical(int center) {
    const int stride = 16;
#if spatial_stable_level <= 1
    vec2 sum = horizontal_tile[center] * 0.39905027965245493;
    sum += (horizontal_tile[center - 1 * stride] +
            horizontal_tile[center + 1 * stride]) * 0.24203622937611435;
    sum += (horizontal_tile[center - 2 * stride] +
            horizontal_tile[center + 2 * stride]) * 0.054005582622414498;
    sum += (horizontal_tile[center - 3 * stride] +
            horizontal_tile[center + 3 * stride]) * 0.0044330481752437459;
#elif spatial_stable_level == 2
    vec2 sum = horizontal_tile[center] * 0.31922423439085301;
    sum += (horizontal_tile[center - 1 * stride] +
            horizontal_tile[center + 1 * stride]) * 0.2318043704135041;
    sum += (horizontal_tile[center - 2 * stride] +
            horizontal_tile[center + 2 * stride]) * 0.088756244369270459;
    sum += (horizontal_tile[center - 3 * stride] +
            horizontal_tile[center + 3 * stride]) * 0.017919576688438448;
    sum += (horizontal_tile[center - 4 * stride] +
            horizontal_tile[center + 4 * stride]) * 0.0019076913333604809;
#elif spatial_stable_level == 3
    vec2 sum = horizontal_tile[center] * 0.25541096969073107;
    sum += (horizontal_tile[center - 1 * stride] +
            horizontal_tile[center + 1 * stride]) * 0.20811147917447398;
    sum += (horizontal_tile[center - 2 * stride] +
            horizontal_tile[center + 2 * stride]) * 0.11258110482738368;
    sum += (horizontal_tile[center - 3 * stride] +
            horizontal_tile[center + 3 * stride]) * 0.040434116389630952;
    sum += (horizontal_tile[center - 4 * stride] +
            horizontal_tile[center + 4 * stride]) * 0.0096414729606384107;
    sum += (horizontal_tile[center - 5 * stride] +
            horizontal_tile[center + 5 * stride]) * 0.0015263418025074776;
#elif spatial_stable_level == 4
    vec2 sum = horizontal_tile[center] * 0.20441532859407782;
    sum += (horizontal_tile[center - 1 * stride] +
            horizontal_tile[center + 1 * stride]) * 0.17930384925879744;
    sum += (horizontal_tile[center - 2 * stride] +
            horizontal_tile[center + 2 * stride]) * 0.12100911201124059;
    sum += (horizontal_tile[center - 3 * stride] +
            horizontal_tile[center + 3 * stride]) * 0.062834598207420683;
    sum += (horizontal_tile[center - 4 * stride] +
            horizontal_tile[center + 4 * stride]) * 0.025103363688158426;
    sum += (horizontal_tile[center - 5 * stride] +
            horizontal_tile[center + 5 * stride]) * 0.0077164456396147515;
    sum += (horizontal_tile[center - 6 * stride] +
            horizontal_tile[center + 6 * stride]) * 0.001824966897729268;
#else
    vec2 sum = horizontal_tile[center] * 0.16348116029814891;
    sum += (horizontal_tile[center - 1 * stride] +
            horizontal_tile[center + 1 * stride]) * 0.15032681281591539;
    sum += (horizontal_tile[center - 2 * stride] +
            horizontal_tile[center + 2 * stride]) * 0.11688066256203568;
    sum += (horizontal_tile[center - 3 * stride] +
            horizontal_tile[center + 3 * stride]) * 0.076839823134770308;
    sum += (horizontal_tile[center - 4 * stride] +
            horizontal_tile[center + 4 * stride]) * 0.042713732645968346;
    sum += (horizontal_tile[center - 5 * stride] +
            horizontal_tile[center + 5 * stride]) * 0.020076415263730153;
    sum += (horizontal_tile[center - 6 * stride] +
            horizontal_tile[center + 6 * stride]) * 0.0079788865860050892;
    sum += (horizontal_tile[center - 7 * stride] +
            horizontal_tile[center + 7 * stride]) * 0.0026812412389485853;
    sum += (horizontal_tile[center - 8 * stride] +
            horizontal_tile[center + 8 * stride]) * 0.00076184560355203445;
#endif
    return sum;
}

void hook() {
    uint tid = gl_LocalInvocationIndex;
    ivec2 origin = ivec2(gl_WorkGroupID.xy) * 16 - ivec2(radius);
    for (uint i = tid; i < uint(tile_width * tile_width); i += 256u) {
        ivec2 p = origin + ivec2(int(i) % tile_width, int(i) / tile_width);
        p = clamp(p, ivec2(0), ivec2(METERING_size) - 1);
        tile[i] = METERING_mul * texelFetch(METERING_raw, p, 0).xy;
    }
    barrier();
    for (uint i = tid; i < uint(16 * tile_width); i += 256u) {
        int center = (int(i) / 16) * tile_width + int(i) % 16 + radius;
        horizontal_tile[i] = filter_horizontal(center);
    }
    barrier();
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(p, ivec2(METERING_size)))) return;
    ivec2 local = ivec2(gl_LocalInvocationID.xy);
    int center = (local.y + radius) * 16 + local.x;
    imageStore(out_image, p, vec4(filter_vertical(center), 0.0, 1.0));
}

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH 1024
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!DESC metering (initialize)

void clear_metering_histogram_bin(uint index) {
    if (index < 1024u) {
        metered_histogram[index] = 0u;
    }
    if (index == 0u) {
        metered_max_rgb = 0u;
        metered_zones_valid = 0u;
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
//!DESC metering (histogram and matrix zones)

shared uint shistogram[1024];
shared uint smax_rgb;

float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

// The sanitizer is part of the conversion rather than a precondition checked at
// the call site: NaN and out-of-range values must not reach the float-to-uint
// conversion, whose result is undefined there and differs by backend (D3D
// converts NaN to zero, Vulkan leaves it to the driver). Same shape as
// pq_to_uint, matrix_zone_value_code and histogram_interval_bounds.
// Quantization scale for PQ12 intensity statistics.
const float METERING_CODE_SCALE = 4095.0;

uint to_uint(float x) {
    return uint(sanitize_bounded(x, 0.0, 1.0) * METERING_CODE_SCALE + 0.5);
}

uint to_histogram_bin(float x) {
    return min(to_uint(x) >> 2u, 1023u);
}

// Clamp integer coordinates explicitly; texelFetch bypasses sampler addressing.
// The caller computes the upper bound once for each sampled quad.
vec2 fetch_metering(ivec2 position, ivec2 last) {
    return (METERING_mul * texelFetch(
        METERING_raw,
        clamp(position, ivec2(0), last),
        0
    )).xy;
}

void fetch_metering_quad(
    ivec2 position,
    out vec4 intensities,
    out vec4 maxima
) {
    ivec2 last = ivec2(METERING_size) - 1;
    vec2 sample0 = fetch_metering(position, last);
    vec2 sample1 = fetch_metering(position + ivec2(1, 0), last);
    vec2 sample2 = fetch_metering(position + ivec2(0, 1), last);
    vec2 sample3 = fetch_metering(position + ivec2(1, 1), last);
    intensities = vec4(sample0.x, sample1.x, sample2.x, sample3.x);
    maxima = vec4(sample0.y, sample1.y, sample2.y, sample3.y);
}

void clear_workgroup_histogram(uint tid) {
    for (uint i = tid; i < 1024u; i += 256u) {
        shistogram[i] = 0u;
    }
    if (tid == 0u) {
        smax_rgb = 0u;
    }
}

void accumulate_workgroup_metering(vec4 intensities, vec4 maxima) {
    atomicAdd(shistogram[to_histogram_bin(intensities.x)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.y)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.z)], 1u);
    atomicAdd(shistogram[to_histogram_bin(intensities.w)], 1u);
    float maximum = max(max(maxima.x, maxima.y), max(maxima.z, maxima.w));
    // Keep NaN and negative zero out of the integer ordering: their patterns
    // sort above every finite value in [0, 1]. Non-negative finite floats have
    // the same ordering as their uint bit patterns, retaining full precision.
    maximum = sanitize_bounded(maximum, 0.0, 1.0);
    atomicMax(
        smax_rgb,
        floatBitsToUint(maximum)
    );
}

void merge_workgroup_histogram(uint tid) {
    // Merge each non-empty workgroup bin with one global atomic.
    for (uint i = tid; i < 1024u; i += 256u) {
        uint count = shistogram[i];
        if (count > 0u) {
            atomicAdd(metered_histogram[i], count);
        }
    }
    if (tid == 0u) {
        atomicMax(metered_max_rgb, smax_rgb);
    }
}


// Each 32x32 source tile also supplies one 16x16 matrix zone. Keep the
// filtered zone sample: averaging the histogram's FP32 texelFetch results
// differs from hardware FP16 filtering near histogram thresholds.
// The uniform gate below preserves the former matrix pass's scheduling.

// A 256x144 analysis grid maps exactly to 16x9 workgroups. Each workgroup
// builds a compact histogram for one image zone, then publishes a robust mean
// and its P10-P90 spread for the matrix reduction below.
const uint MATRIX_ZONE_COLUMNS = 16u;
const uint MATRIX_ZONE_ROWS = 9u;
const uint MATRIX_ZONE_COUNT = MATRIX_ZONE_COLUMNS * MATRIX_ZONE_ROWS;
const uint MATRIX_ZONE_SAMPLE_COUNT = 16u * 16u;
const uint MATRIX_ZONE_HISTOGRAM_SIZE = 64u;

// Use 9 low bits for the count and 23 high bits for the sum.
// Each sample contributes a 15-bit PQ value; a bin holds at most 256 samples.
const uint MATRIX_ZONE_COUNT_BITS = 9u;
const uint MATRIX_ZONE_COUNT_MASK =
    (1u << MATRIX_ZONE_COUNT_BITS) - 1u;

// Map 15-bit values to 64 bins independently of the packed count field.
const uint MATRIX_ZONE_VALUE_BITS = 15u;
const uint MATRIX_ZONE_HISTOGRAM_BITS = 6u;
const uint MATRIX_ZONE_HISTOGRAM_SHIFT =
    MATRIX_ZONE_VALUE_BITS - MATRIX_ZONE_HISTOGRAM_BITS;

// Full scale of the 15-bit code one sample contributes.
const float MATRIX_ZONE_VALUE_SCALE = 32767.0;
const float MATRIX_ZONE_TRIM_PERCENTILE = 0.05;
const float MATRIX_ZONE_LOW_PERCENTILE = 0.10;
const float MATRIX_ZONE_HIGH_PERCENTILE = 0.90;
const vec2 MATRIX_METERING_SIZE = vec2(256.0, 144.0);

// Each entry contains (sum_of_15_bit_values << MATRIX_ZONE_COUNT_BITS)
// | sample_count.
shared uint zone_histogram[MATRIX_ZONE_HISTOGRAM_SIZE];
shared uint zone_prefix[2][MATRIX_ZONE_HISTOGRAM_SIZE];
shared uint zone_retained_count[MATRIX_ZONE_HISTOGRAM_SIZE];
shared uint zone_low_bin;
shared uint zone_high_bin;

uint matrix_zone_value_code(float value) {
    // Reject NaN before the float-to-uint conversion per the file-wide
    // sanitizer contract: a NaN here is undefined in the uint domain.
    return uint(sanitize_bounded(value, 0.0, 1.0) *
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
    if (retained == 0u || sample_count == 0u) {
        return 0.0;
    }

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
    if (tid < MATRIX_ZONE_HISTOGRAM_SIZE) {
        zone_histogram[tid] = 0u;
    }
}

uint retained_count(
    uint cumulative_before,
    uint cumulative,
    uint lower_target,
    uint upper_target
) {
    uint first = max(cumulative_before, lower_target);
    uint last = min(cumulative, upper_target);
    return last > first ? last - first : 0u;
}

// A percentile of the declared sample count, floored at one sample so a
// percentile that rounds to zero still selects a bin instead of none.
uint percentile_target(uint sample_count, float percentile) {
    return max(uint(ceil(float(sample_count) * percentile)), 1u);
}

void publish_matrix_zone(uint tid, uint zone_index) {
    uint trim = uint(floor(
        float(MATRIX_ZONE_SAMPLE_COUNT) * MATRIX_ZONE_TRIM_PERCENTILE
    ));
    uint upper_target = MATRIX_ZONE_SAMPLE_COUNT - trim;
    uint low_target = percentile_target(
        MATRIX_ZONE_SAMPLE_COUNT, MATRIX_ZONE_LOW_PERCENTILE
    );
    uint high_target = percentile_target(
        MATRIX_ZONE_SAMPLE_COUNT, MATRIX_ZONE_HIGH_PERCENTILE
    );

    uint packed_value = 0u;
    uint sample_count = 0u;
    if (tid < MATRIX_ZONE_HISTOGRAM_SIZE) {
        packed_value = zone_histogram[tid];
        sample_count = matrix_zone_bin_count(packed_value);
        zone_prefix[0][tid] = sample_count;
    }
    if (tid == 0u) {
        zone_low_bin = 0u;
        zone_high_bin = MATRIX_ZONE_HISTOGRAM_SIZE - 1u;
    }
    barrier();

    // Separate read/write banks need only one workgroup barrier per step.
    uint bank = 0u;
    for (uint offset = 1u; offset < MATRIX_ZONE_HISTOGRAM_SIZE; offset <<= 1u) {
        if (tid < MATRIX_ZONE_HISTOGRAM_SIZE) {
            uint inclusive = zone_prefix[bank][tid];
            if (tid >= offset) {
                inclusive += zone_prefix[bank][tid - offset];
            }
            zone_prefix[1u - bank][tid] = inclusive;
        }
        barrier();
        bank = 1u - bank;
    }

    if (tid < MATRIX_ZONE_HISTOGRAM_SIZE) {
        uint cumulative = zone_prefix[bank][tid];
        uint before = cumulative - sample_count;
        uint retained = retained_count(before, cumulative, trim, upper_target);
        zone_retained_count[tid] = retained;
        if (before < low_target && cumulative >= low_target) {
            zone_low_bin = tid;
        }
        if (before < high_target && cumulative >= high_target) {
            zone_high_bin = tid;
        }
    }
    barrier();

    if (tid == 0u) {
        // Keep the original contribution expression and accumulation order:
        // moving float intermediates into shared memory changes contraction
        // differently in Vulkan and D3D11 shader compilers.
        float sum = 0.0;
        for (uint i = 0u; i < MATRIX_ZONE_HISTOGRAM_SIZE; i++) {
            uint packed = zone_histogram[i];
            sum += matrix_zone_retained_sum(
                packed, matrix_zone_bin_count(packed), zone_retained_count[i]
            );
        }
        metered_zone_average[zone_index] = sum /
            float(max(upper_target - trim, 1u));
        metered_zone_spread[zone_index] = float(zone_high_bin - zone_low_bin) /
            float(MATRIX_ZONE_HISTOGRAM_SIZE - 1u);
        if (zone_index == 0u) {
            metered_zones_valid = 1u;
        }
    }
}

void hook() {
    uint tid = gl_LocalInvocationIndex;
    ivec2 position = ivec2(gl_WorkGroupID.xy) * 32 +
                     ivec2(gl_LocalInvocationID.xy) * 2;
    vec4 intensities;
    vec4 maxima;
    fetch_metering_quad(position, intensities, maxima);
    bool matrix_enabled = enable_metering > 1u &&
                          (auto_exposure_anchor > 0.0 || preview_metering > 0u);

    clear_workgroup_histogram(tid);
    if (matrix_enabled) {
        clear_zone_histogram(tid);
    }
    barrier();

    accumulate_workgroup_metering(intensities, maxima);
    if (matrix_enabled) {
        vec2 zone_position = (vec2(gl_GlobalInvocationID.xy) + 0.5) /
                             MATRIX_METERING_SIZE;
        float value = sample_matrix_metering(zone_position);
        uint value_code = matrix_zone_value_code(value);
        atomicAdd(
            zone_histogram[matrix_zone_histogram_bin(value_code)],
            matrix_zone_packed_sample(value_code)
        );
    }
    barrier();

    merge_workgroup_histogram(tid);
    // This condition depends only on uniform parameters, so every invocation
    // in the workgroup participates in the zone scan's barriers together.
    if (matrix_enabled) {
        uint zone_index = gl_WorkGroupID.y * MATRIX_ZONE_COLUMNS +
                          gl_WorkGroupID.x;
        publish_matrix_zone(tid, zone_index);
    }
}

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!SAVE EMPTY
//!WIDTH 256
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!DESC metering (statistics reduction)

// One just-noticeable difference step on the PQ scale.
const float JND = 1.0 / 720.0;

// Quantization scale for PQ12 intensity statistics.
const float METERING_CODE_SCALE = 4095.0;

// Histogram statistics.
const uint METERING_HISTOGRAM_SIZE = 1024u;
const uint METERING_REDUCTION_SIZE = 256u;
const uint METERING_BINS_PER_THREAD = 4u;
const uint METERING_SAMPLE_COUNT = 512u * 288u;

// The percentile family: BLACK and WHITE locate the extrema histogram
// positions, MEDIAN and DIFFUSE_WHITE characterize the content-white range,
// and the average trim removes the same fraction from each tail.
// A robust white point constrains automatic exposure without allowing one
// unstable highlight sample to move the whole frame. The maximum RGB channel
// is measured separately to define the tone-curve endpoint.
const float METERING_BLACK_PERCENTILE = 0.005;
const float METERING_WHITE_PERCENTILE = 0.995;
const float METERING_MEDIAN_PERCENTILE = 0.50;
const float METERING_DIFFUSE_WHITE_PERCENTILE = 0.80;
const float METERING_AVERAGE_TRIM_PERCENTILE = 0.05;

// Matrix refinement of the histogram-derived average.
const uint MATRIX_ZONE_COLUMNS = 16u;
const uint MATRIX_ZONE_ROWS = 9u;
const uint MATRIX_ZONE_COUNT = MATRIX_ZONE_COLUMNS * MATRIX_ZONE_ROWS;
const float METERING_MATRIX_WEIGHT_MIN = 0.50;
const float METERING_MATRIX_WEIGHT_MAX = 0.75;
const float METERING_MATRIX_SPATIAL_SCALE = 3.0;
const float METERING_MATRIX_DIFFERENCE_MIN = 36 * JND;
const float METERING_MATRIX_DIFFERENCE_MAX = 144 * JND;
const float METERING_MATRIX_SPREAD_MIN = 48 * JND;
const float METERING_MATRIX_SPREAD_MAX = 216 * JND;
const float METERING_MATRIX_COHERENCE_MIN = 24 * JND;
const float METERING_MATRIX_COHERENCE_MAX = 132 * JND;
const float METERING_BORDER_BLACK_MAX = 12 * JND;
const float METERING_BORDER_BLACK_RELATIVE_SCALE = 0.10;
const float METERING_BORDER_SPREAD_MAX = 52 * JND;
const float METERING_BORDER_GLOBAL_MIN = 12 * JND;
const float METERING_BORDER_OCCUPANCY_MIN = 0.80;
const uint METERING_ACTIVE_COLUMNS_MIN = 4u;
const uint METERING_ACTIVE_ROWS_MIN = 3u;
const float METERING_BORDER_MATRIX_WEIGHT = 0.95;

shared uint histogram_scan[2][METERING_REDUCTION_SIZE];
shared float average_partial[METERING_REDUCTION_SIZE];
shared float histogram_average;
shared uint black_bin;
shared uint white_bin;
shared uint median_bin;
shared uint diffuse_white_bin;
shared uint scene_median_bin;
shared uint scene_diffuse_white_bin;

shared vec2 matrix_partial[METERING_REDUCTION_SIZE];
shared uvec4 matrix_active_bounds;
shared float matrix_border_confidence;
shared uint matrix_border_columns[MATRIX_ZONE_COLUMNS];
shared float matrix_border_rows[MATRIX_ZONE_ROWS];

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
    histogram_scan[0][tid] = block_count;
    barrier();

    // Ping-pong storage preserves the add order without a pre-write barrier.
    // The fixed power-of-four size has an even number of rounds: bank 0 wins.
    uint bank = 0u;
    for (uint offset = 1u; offset < METERING_REDUCTION_SIZE; offset <<= 1u) {
        uint inclusive = histogram_scan[bank][tid];
        if (tid >= offset) {
            inclusive += histogram_scan[bank][tid - offset];
        }
        histogram_scan[1u - bank][tid] = inclusive;
        barrier();
        bank = 1u - bank;
    }
}

uint retained_count(
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
        uint retained = retained_count(
            cumulative,
            next,
            targets.x,
            targets.y
        );
        float value = (float((first + i) << 2u) + 1.5) / METERING_CODE_SCALE;
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
        if (tid < size) {
            average_partial[tid] += average_partial[tid + size];
        }
        barrier();
    }
}

// Ordered-comparison sanitizer; see the metering intensity pass.
float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

uint pq_to_uint(float value) {
    // Reject NaN before the float-to-uint conversion per the file-wide
    // sanitizer contract: a NaN here is undefined in the uint domain.
    return uint(sanitize_bounded(value, 0.0, 1.0) * METERING_CODE_SCALE + 0.5);
}

// Classify near-black, low-spread zones as border candidates.
// A global-brightness guard limits detection in dark scenes.
bool matrix_zone_looks_like_border(uint index) {
    float black_limit = min(
        METERING_BORDER_BLACK_MAX,
        histogram_average * METERING_BORDER_BLACK_RELATIVE_SCALE
    );
    return histogram_average > METERING_BORDER_GLOBAL_MIN &&
           metered_zone_average[index] <= black_limit &&
           metered_zone_spread[index] <= METERING_BORDER_SPREAD_MAX;
}

// Each column packs its nine border classifications into a bit mask.
// Both horizontal and vertical occupancy reuse those exact classifications.
float matrix_column_black_fraction(uint x) {
    return float(bitCount(matrix_border_columns[x])) / float(MATRIX_ZONE_ROWS);
}

uvec2 matrix_horizontal_bounds() {
    uint left = 0u;
    uint right = MATRIX_ZONE_COLUMNS;
    for (uint x = 0u; x < MATRIX_ZONE_COLUMNS; x++) {
        if (matrix_column_black_fraction(x) < METERING_BORDER_OCCUPANCY_MIN) {
            break;
        }
        left = x + 1u;
    }
    for (int x = int(MATRIX_ZONE_COLUMNS) - 1; x >= 0; x--) {
        if (matrix_column_black_fraction(uint(x)) < METERING_BORDER_OCCUPANCY_MIN) {
            break;
        }
        right = uint(x);
    }
    return uvec2(left, right);
}

struct MatrixActiveRegion {
    uvec4 bounds;
    float border_confidence;
};

MatrixActiveRegion detect_matrix_active_region() {
    uint left = 0u;
    uint right = MATRIX_ZONE_COLUMNS;
    uint top = 0u;
    uint bottom = MATRIX_ZONE_ROWS;

    if (metered_zones_valid > 0u) {
        uvec2 horizontal = matrix_horizontal_bounds();
        left = horizontal.x;
        right = horizontal.y;

        if (right > left &&
            right - left >= METERING_ACTIVE_COLUMNS_MIN) {
            for (uint y = 0u; y < MATRIX_ZONE_ROWS; y++) {
                if (matrix_border_rows[y] <
                    METERING_BORDER_OCCUPANCY_MIN) {
                    break;
                }
                top = y + 1u;
            }
            for (int y = int(MATRIX_ZONE_ROWS) - 1; y >= 0; y--) {
                if (matrix_border_rows[y] < METERING_BORDER_OCCUPANCY_MIN) {
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
        right = MATRIX_ZONE_COLUMNS;
        top = 0u;
        bottom = MATRIX_ZONE_ROWS;
    }

    MatrixActiveRegion region;
    region.bounds = uvec4(left, right, top, bottom);
    float active_area = float((right - left) * (bottom - top));
    float removed_fraction = 1.0 - active_area /
                             float(MATRIX_ZONE_COUNT);
    region.border_confidence = valid_bounds
        ? smoothstep(0.05, 0.30, removed_fraction)
        : 0.0;
    return region;
}

void prepare_matrix_active_region(uint tid) {
    if (tid < MATRIX_ZONE_COLUMNS) {
        uint mask = 0u;
        for (uint y = 0u; y < MATRIX_ZONE_ROWS; y++) {
            if (metered_zones_valid > 0u &&
                matrix_zone_looks_like_border(y * MATRIX_ZONE_COLUMNS + tid)) {
                mask |= 1u << y;
            }
        }
        matrix_border_columns[tid] = mask;
    }
    // Keep synchronization unconditional for the D3D11 compiler.
    barrier();

    if (tid < MATRIX_ZONE_ROWS) {
        // Rows depend on the detected horizontal crop. Repeating this
        // small scan in nine lanes avoids another scalar handoff/barrier.
        uvec2 horizontal = matrix_horizontal_bounds();
        uint count = 0u;
        uint width = horizontal.y > horizontal.x
            ? horizontal.y - horizontal.x : 0u;
        if (width >= METERING_ACTIVE_COLUMNS_MIN) {
            for (uint x = horizontal.x; x < horizontal.y; x++) {
                count += (matrix_border_columns[x] >> tid) & 1u;
            }
        }
        matrix_border_rows[tid] = float(count) / float(max(width, 1u));
    }
    barrier();

    if (tid == 0u) {
        MatrixActiveRegion region = detect_matrix_active_region();
        matrix_active_bounds = region.bounds;
        matrix_border_confidence = region.border_confidence;
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
    uint x = index % MATRIX_ZONE_COLUMNS;
    uint y = index / MATRIX_ZONE_COLUMNS;
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
            value - metered_zone_average[index - MATRIX_ZONE_COLUMNS]
        );
        count++;
    }
    if (y + 1u < matrix_active_bounds.w) {
        difference += abs(
            value - metered_zone_average[index + MATRIX_ZONE_COLUMNS]
        );
        count++;
    }

    return difference / float(max(count, 1u));
}

float matrix_difference_confidence(float difference) {
    return smoothstep(
        METERING_MATRIX_DIFFERENCE_MIN,
        METERING_MATRIX_DIFFERENCE_MAX,
        difference
    );
}

vec2 matrix_zone_partial(uint index, float reference_average) {
    if (metered_zones_valid == 0u || index >= MATRIX_ZONE_COUNT) {
        return vec2(0.0);
    }

    uint x = index % MATRIX_ZONE_COLUMNS;
    uint y = index / MATRIX_ZONE_COLUMNS;
    if (!matrix_zone_inside_active_region(x, y)) {
        return vec2(0.0);
    }

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
    float difference = abs(zone_average - reference_average);
    float subject_evidence = matrix_difference_confidence(difference);
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
        if (tid < size) {
            matrix_partial[tid] += matrix_partial[tid + size];
        }
        barrier();
    }
}

void publish_matrix_average(uint tid) {
    if (tid != 0u) {
        return;
    }

    bool matrix_available = matrix_partial[0].y > 0.0;
    float matrix_average = matrix_available
        ? matrix_partial[0].x / matrix_partial[0].y
        : histogram_average;

    // Increase matrix weight with matrix/histogram disagreement.
    // Retain at least 25% histogram weight before the border adjustment.
    float difference = abs(matrix_average - histogram_average);
    float matrix_confidence = matrix_difference_confidence(difference);
    float matrix_weight = mix(
        METERING_MATRIX_WEIGHT_MIN,
        METERING_MATRIX_WEIGHT_MAX,
        matrix_confidence
    );
    // Detected borders can raise matrix weight to 95%.
    matrix_weight = mix(
        matrix_weight,
        METERING_BORDER_MATRIX_WEIGHT,
        matrix_border_confidence
    );
    if (!matrix_available) {
        matrix_weight = 0.0;
    }
    // Exposure uses the separate histogram and matrix averages below: each
    // branch solves and constrains its own EV before the EVs are blended.
    // The blended PQ average is published for reporting and the auto-exposure
    // enable check; it is not the average used to solve either exposure branch.
    metered_histogram_average = histogram_average;
    metered_matrix_average = matrix_average;
    metered_matrix_blend = matrix_weight;
    metered_avg_i = pq_to_uint(
        mix(histogram_average, matrix_average, matrix_weight)
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
        if (cumulative >= target) {
            return first + i;
        }
    }
    return first + METERING_BINS_PER_THREAD - 1u;
}

// A percentile of the declared sample count, floored at one sample so a
// percentile that rounds to zero still selects a bin instead of none.
uint percentile_target(uint sample_count, float percentile) {
    return max(uint(ceil(float(sample_count) * percentile)), 1u);
}

void locate_percentiles(uint tid, uint first, uvec4 counts) {
    uint cumulative_before = tid == 0u ? 0u : histogram_scan[0][tid - 1u];
    uint cumulative = histogram_scan[0][tid];
    uint black_target = percentile_target(
        METERING_SAMPLE_COUNT,
        METERING_BLACK_PERCENTILE
    );
    uint white_target = percentile_target(
        METERING_SAMPLE_COUNT,
        METERING_WHITE_PERCENTILE
    );
    uint median_target = percentile_target(
        METERING_SAMPLE_COUNT,
        METERING_MEDIAN_PERCENTILE
    );
    uint diffuse_white_target = percentile_target(
        METERING_SAMPLE_COUNT,
        METERING_DIFFUSE_WHITE_PERCENTILE
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
    if (cumulative_before < median_target && cumulative >= median_target) {
        median_bin = find_percentile_bin(
            counts,
            first,
            cumulative_before,
            median_target
        );
    }
    if (cumulative_before < diffuse_white_target &&
        cumulative >= diffuse_white_target) {
        diffuse_white_bin = find_percentile_bin(
            counts,
            first,
            cumulative_before,
            diffuse_white_target
        );
    }

    // DSM uses the actual histogram count and bin centers on a 1024-step
    // scale. Reuse this prefix scan without changing the exposure quantiles.
    uint total = histogram_scan[0][METERING_REDUCTION_SIZE - 1u];
    uint scene_median_target = percentile_target(total, 0.5);
    uint scene_diffuse_white_target = percentile_target(total, 0.8);
    if (cumulative_before < scene_median_target &&
        cumulative >= scene_median_target) {
        scene_median_bin = find_percentile_bin(
            counts, first, cumulative_before, scene_median_target
        );
    }
    if (cumulative_before < scene_diffuse_white_target &&
        cumulative >= scene_diffuse_white_target) {
        scene_diffuse_white_bin = find_percentile_bin(
            counts, first, cumulative_before, scene_diffuse_white_target
        );
    }
}

void reduce_histogram_statistics(
    uint tid,
    uint first,
    uvec4 counts
) {
    if (tid == 0u) {
        black_bin = 0u;
        white_bin = METERING_HISTOGRAM_SIZE - 1u;
        median_bin = 0u;
        diffuse_white_bin = METERING_HISTOGRAM_SIZE - 1u;
        scene_median_bin = 0u;
        scene_diffuse_white_bin = 0u;
    }

    scan_histogram_blocks(tid, sum_histogram_block(counts));
    locate_percentiles(tid, first, counts);

    uint global_total = histogram_scan[0][METERING_REDUCTION_SIZE - 1u];
    uvec2 global_targets = average_trim_targets(global_total);
    uint global_before = tid == 0u ? 0u : histogram_scan[0][tid - 1u];

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
        uint global_retained = global_targets.y - global_targets.x;
        metered_histogram_count = global_total;
        metered_scene_median_bin = scene_median_bin;
        metered_scene_diffuse_white_bin = scene_diffuse_white_bin;
        histogram_average = average_partial[0] /
                            float(max(global_retained, 1u));
        metered_min_i = black_bin << 2u;
        metered_max_i = min(
            (white_bin << 2u) + 3u,
            uint(METERING_CODE_SCALE)
        );
        metered_median_i = min(
            (median_bin << 2u) + 2u,
            uint(METERING_CODE_SCALE)
        );
        metered_diffuse_white_i = min(
            (diffuse_white_bin << 2u) + 2u,
            uint(METERING_CODE_SCALE)
        );
    }
    barrier();
}

void refine_average_with_matrix(uint tid) {
    // All invocations must participate in the shared-memory barriers.
    prepare_matrix_active_region(tid);

    vec2 partial = matrix_zone_partial(tid, histogram_average);
    if (preview_metering > 0u && tid < MATRIX_ZONE_COUNT) {
        // Publish weights separately; zone spreads remain read-only in this pass.
        metered_zone_preview_weight[tid] = partial.y;
    }

    reduce_matrix_partials(tid, partial);

    publish_matrix_average(tid);
}

void reduce_metering_statistics() {
    uint tid = gl_LocalInvocationIndex;
    uint first = tid * METERING_BINS_PER_THREAD;
    uvec4 counts = load_histogram_block(first);

    reduce_histogram_statistics(tid, first, counts);
    refine_average_with_matrix(tid);
}

void hook() { reduce_metering_statistics(); }

//!HOOK OUTPUT
//!BIND METERING
//!BIND METERED
//!BIND SCENE_DSM
//!BIND SCENE_RESPONSE
//!SAVE EMPTY
//!WIDTH 64
//!HEIGHT 1
//!COMPUTE 64 1 64 1
//!WHEN temporal_stable_duration 0 >
//!DESC metering (scene adaptation)

// Before-observation changepoints, diagonal known-variance Gaussian DSM.
// https://proceedings.mlr.press/v202/altamirano23a.html
//
// The new-shot prior is fitted to shot-mean statistics in the eigenbasis of
// their covariance: mu_w = U^T mu and precision = 1/eigenvalue. The rotation
// keeps the observation noise isotropic, so the DSM update stays diagonal.
// These constants are a first calibration from the first half of the
// validation film; recalibrate on a multi-film corpus before treating them
// as content-independent.
const uint DSM_CAPACITY = 32u;
const float DSM_SIGMA_SQUARED = 0.0625;
// Diffusion scale in the rotated observation coordinates; independent of
// the broad prior variance even though their current values coincide.
const float DSM_DIFFUSION_SCALE_SQUARED = 9.0;
const vec4 DSM_PRIOR_PRECISION = vec4(
    20.0, 20.0, 12.07577751, 0.28347464
);
const vec4 DSM_PRIOR_INFORMATION = vec4(
    -0.48453845, -4.1910885, -9.1981883, 0.45147989
);
const mat4 DSM_PRIOR_ROTATION = mat4(
    vec4(0.7886, 0.3150, 0.2151, -0.4823),
    vec4(-0.1829, -0.6728, 0.4965, -0.5170),
    vec4(-0.0119, -0.2056, -0.8273, -0.5226),
    vec4(-0.5870, 0.6370, 0.1510, -0.4763)
);
const float DSM_MATRIX_IMPUTATION_Z = 0.02433227;
// A broad N(0,9) component keeps cuts to unusual shots detectable; the
// empirical core alone would saturate both hypotheses at the outlier floor.
const float DSM_PRIOR_BROAD_WEIGHT = 0.1;
const float DSM_PRIOR_BROAD_VARIANCE = 9.0;
const float DSM_OUTLIER_VARIANCE = 64.0;
const float DSM_CONTAMINATION = 0.02;
const float DSM_SHOT_SECONDS = 5.0;
const float DSM_SHORT_SECONDS = 0.15;
const float DSM_MAX_GAP = 1.0;
const float DSM_PTS_EPSILON = 1e-6;
const float DSM_CUT_PROBABILITY = 0.95;

shared vec4 dsm_z;
shared vec4 dsm_precision_step;
shared vec4 dsm_information_step;
shared float dsm_logits[DSM_CAPACITY + 1u];
shared float dsm_ages[DSM_CAPACITY + 1u];
shared uint dsm_selected[DSM_CAPACITY];
shared uint dsm_operation;
shared uint dsm_old_count;
shared uint dsm_next_count;
shared uint dsm_read_bank;
shared uint dsm_bad_history;
shared float dsm_dt;
shared float dsm_log_hazard;
shared float dsm_log_kept;
shared float dsm_log_outlier;
shared float dsm_prior_normal;
shared vec4 dsm_new_prior_precision;
shared vec4 dsm_new_prior_information;

bool dsm_finite(float x) {
    return !isnan(x) && !isinf(x);
}

float dsm_log_add_exp(float a, float b) {
    float largest = max(a, b);
    return largest + log(exp(a - largest) + exp(b - largest));
}

void dsm_clear_response() {
    scene_new_probability = 0.0;
    scene_short_probability = 0.0;
    scene_confirmed_probability = 0.0;
    scene_fast_strength = 0.0;
    scene_discarded_mass = 0.0;
    scene_outlier_probability = 0.0;
    scene_change_log_bayes = 0.0;
    scene_hard_cut = 0u;
    scene_bootstrap = 0u;
    scene_observation_valid = 0u;
    scene_state_recovered = 0u;
    scene_observation_pts = floatBitsToUint(PTS);
}

void dsm_prepare() {
    dsm_operation = 0u;
    dsm_bad_history = 0u;
    if (!dsm_finite(PTS)) {
        dsm_valid = 0u;
        dsm_clear_response();
        return;
    }
    if (dsm_valid > 0u &&
        abs(PTS - uintBitsToFloat(dsm_last_pts)) <= DSM_PTS_EPSILON) {
        return;
    }
    dsm_clear_response();

    // BIND supplies current measurements, including those requested by preview.
    // Metadata invalidates dormant history whenever this pass is disabled.
    if (metered_histogram_count == 0u || !dsm_finite(metered_histogram_average) ||
        metered_histogram_average < 0.0 || metered_histogram_average > 1.0) {
        return;
    }
    uint p50 = metered_scene_median_bin;
    uint p80 = metered_scene_diffuse_white_bin;
    bool matrix_valid = metered_zones_valid > 0u &&
                        metered_matrix_blend > 0.0 &&
                        dsm_finite(metered_matrix_average) &&
                        metered_matrix_average >= 0.0 && metered_matrix_average <= 1.0;
    dsm_z = (vec4(metered_histogram_average,
                  (float(p50) + 0.5) / 1024.0,
                  (float(p80) + 0.5) / 1024.0,
                  matrix_valid ? metered_matrix_average : 0.5) - 0.5) * 10.0;
    // The matrix feature is highly correlated with the histogram average.
    // When it is unavailable, use the conditional mean instead of zero-fill;
    // the rotation mixes all four dimensions and cannot mask one of them.
    if (!matrix_valid) {
        dsm_z.w = dsm_z.x + DSM_MATRIX_IMPUTATION_Z;
    }
    dsm_z = DSM_PRIOR_ROTATION * dsm_z;
    dsm_log_outlier = log(DSM_CONTAMINATION) - 0.5 * dot(vec4(1.0),
        vec4(log(6.283185307179586 * DSM_OUTLIER_VARIANCE)) +
        dsm_z * dsm_z / DSM_OUTLIER_VARIANCE
    );
    vec4 a = 1.0 / (1.0 + dsm_z * dsm_z / DSM_DIFFUSION_SCALE_SQUARED);
    vec4 derivative = -2.0 * dsm_z * a * a / DSM_DIFFUSION_SCALE_SQUARED;
    // omega=sigma²/2: prec += a/sigma², information += a*z/sigma²-a'.
    dsm_precision_step = a / DSM_SIGMA_SQUARED;
    dsm_information_step = a * dsm_z / DSM_SIGMA_SQUARED - derivative;
    dsm_dt = PTS - uintBitsToFloat(dsm_last_pts);
    bool corrupt = dsm_valid > 0u && (dsm_count == 0u ||
                   dsm_count > DSM_CAPACITY || dsm_bank > 1u ||
                   !dsm_finite(dsm_dt) || !dsm_finite(dsm_accepted_age) ||
                   dsm_accepted_age < 0.0);
    bool reset = dsm_valid == 0u || corrupt || dsm_dt < 0.0 || dsm_dt > DSM_MAX_GAP;
    scene_state_recovered = corrupt ? 1u : 0u;
    dsm_operation = reset ? 1u : 2u;
    dsm_old_count = reset ? 0u : dsm_count;
    dsm_read_bank = reset ? 0u : dsm_bank;
    if (!reset) {
        float h = dsm_dt / DSM_SHOT_SECONDS;
        float hazard = h < 0.001 ? h * (1.0 - h * 0.5 + h * h / 6.0)
                                   : 1.0 - exp(-h);
        dsm_log_hazard = log(max(hazard, 1e-12));
    }
}

float dsm_predictive(vec4 prec, vec4 information) {
    vec4 variance = DSM_SIGMA_SQUARED + 1.0 / prec;
    vec4 residual = dsm_z - information / prec;
    float normal = -0.5 * dot(vec4(1.0),
        log(6.283185307179586 * variance) + residual * residual / variance
    ) + log(1.0 - DSM_CONTAMINATION);
    return dsm_log_add_exp(normal, dsm_log_outlier);
}

// Predictive normal for a new shot: empirical core plus a broad component.
// The broad component preserves the old N(0,9) tail for unusual shots; the
// core lowers the normalization cost for typical shot changes.
float dsm_prior_log_normal(vec4 w, out bool core) {
    vec4 core_variance = 1.0 / DSM_PRIOR_PRECISION + DSM_SIGMA_SQUARED;
    vec4 core_residual = w - DSM_PRIOR_INFORMATION / DSM_PRIOR_PRECISION;
    float log_core = log(1.0 - DSM_PRIOR_BROAD_WEIGHT) - 0.5 * dot(vec4(1.0),
        log(6.283185307179586 * core_variance) +
        core_residual * core_residual / core_variance
    );
    float broad_variance = DSM_PRIOR_BROAD_VARIANCE + DSM_SIGMA_SQUARED;
    float log_broad = log(DSM_PRIOR_BROAD_WEIGHT) - 0.5 * dot(vec4(1.0),
        vec4(log(6.283185307179586 * broad_variance)) +
        w * w / broad_variance
    );
    core = log_core >= log_broad;
    return dsm_log_add_exp(log_core, log_broad);
}

// Choose the new-shot state prior by the normal-component responsibility.
// This is a hard assignment, not a full posterior mixture; the state stays
// one Gaussian per hypothesis.
void dsm_set_prior() {
    bool core;
    dsm_prior_normal = dsm_prior_log_normal(dsm_z, core) +
                       log(1.0 - DSM_CONTAMINATION);
    dsm_new_prior_precision = core
        ? DSM_PRIOR_PRECISION : vec4(1.0 / DSM_PRIOR_BROAD_VARIANCE);
    dsm_new_prior_information = core
        ? DSM_PRIOR_INFORMATION : vec4(0.0);
}

void dsm_candidate(uint index) {
    if (index > dsm_old_count) {
        return;
    }
    if (index == dsm_old_count) {
        float q_prior = dsm_log_add_exp(dsm_prior_normal, dsm_log_outlier);
        dsm_logits[index] = dsm_log_hazard + q_prior;
        dsm_ages[index] = 0.0;
        return;
    }
    uint slot = dsm_read_bank * DSM_CAPACITY + index;
    vec4 prec;
    vec4 information;
    bool valid = dsm_finite(dsm_weights[slot]) &&
                 dsm_finite(dsm_age[slot]) && dsm_age[slot] >= 0.0;
    for (uint d = 0u; d < 4u; d++) {
        prec[d] = dsm_precision[slot * 4u + d];
        information[d] = dsm_information[slot * 4u + d];
        valid = valid && dsm_finite(prec[d]) && prec[d] > 0.0 &&
                dsm_finite(information[d]);
    }
    if (!valid) {
        atomicOr(dsm_bad_history, 1u);
        return;
    }
    dsm_logits[index] = dsm_weights[slot] - dsm_dt / DSM_SHOT_SECONDS +
                        dsm_predictive(prec, information);
    // Ages avoid subtracting two large absolute timestamps for short runs.
    dsm_ages[index] = dsm_age[slot] + dsm_dt;
    if (!dsm_finite(dsm_logits[index]) || !dsm_finite(dsm_ages[index])) {
        atomicOr(dsm_bad_history, 1u);
    }
}

void dsm_rank(uint index) {
    if (index == 0u) {
        dsm_next_count = min(dsm_old_count + 1u, DSM_CAPACITY);
        dsm_selected[0] = dsm_old_count;
    }
    if (index >= dsm_old_count) {
        return;
    }
    // Each old hypothesis owns one unique rank; ties retain index order.
    // Slot zero always retains the new hypothesis, regardless of its weight.
    uint rank = 1u;
    for (uint j = 0u; j < dsm_old_count; j++) {
        if (dsm_logits[j] > dsm_logits[index] ||
            (dsm_logits[j] == dsm_logits[index] && j < index)) {
            rank++;
        }
    }
    if (rank < DSM_CAPACITY) {
        dsm_selected[rank] = index;
    }
}

void dsm_select() {
    // Responsibility of the shared contamination component under the OLD
    // mixture, before this observation is learned. This is not a cut probability.
    float old_largest = dsm_logits[0];
    for (uint i = 1u; i < dsm_old_count; i++) {
        old_largest = max(old_largest, dsm_logits[i]);
    }
    float old_total = 0.0;
    for (uint i = 0u; i < dsm_old_count; i++) {
        old_total += exp(dsm_logits[i] - old_largest);
    }
    float log_old_predictive = old_largest + log(old_total) +
                               dsm_dt / DSM_SHOT_SECONDS;
    scene_outlier_probability = clamp(
        exp(dsm_log_outlier - log_old_predictive), 0.0, 1.0
    );
    // Compare predictive explanations before the time-based cut prior. This
    // evidence can drive temporary exposure protection without confirming a cut.
    scene_change_log_bayes = dsm_logits[dsm_old_count] - dsm_log_hazard -
                             log_old_predictive;

    // The old candidates were already reduced for predictive evidence.
    // Rescale that sum and add the new hypothesis instead of reducing again.
    float new_logit = dsm_logits[dsm_old_count];
    float largest = max(old_largest, new_logit);
    float total = old_total * exp(old_largest - largest) +
                  exp(new_logit - largest);
    float log_total = largest + log(total);
    float accepted_age = dsm_accepted_age + dsm_dt;
    float short_mass = 0.0;
    float confirmed_mass = 0.0;
    for (uint i = 0u; i <= dsm_old_count; i++) {
        float mass = exp(dsm_logits[i] - log_total);
        if (dsm_ages[i] <= DSM_SHORT_SECONDS + DSM_PTS_EPSILON) {
            short_mass += mass;
            // Require a second observation and exclude every hypothesis
            // already present when the last event (or bootstrap) was accepted.
            if (dsm_ages[i] > DSM_PTS_EPSILON &&
                dsm_ages[i] < accepted_age - DSM_PTS_EPSILON) {
                confirmed_mass += mass;
            }
        }
    }
    scene_new_probability = clamp(exp(dsm_logits[dsm_old_count] - log_total), 0.0, 1.0);
    scene_short_probability = clamp(short_mass, 0.0, 1.0);
    scene_confirmed_probability = clamp(confirmed_mass, 0.0, 1.0);
    scene_fast_strength = smoothstep(0.20, 0.80, scene_short_probability);
    // A single-frame new-shot posterior cannot separate a cut from a
    // one-frame flash once the prior is calibrated to shots rather than to
    // arbitrary values. Require the second observation; the response layer
    // already bridges the first new-scene frame.
    scene_hard_cut = scene_confirmed_probability >= DSM_CUT_PROBABILITY ? 1u : 0u;
    dsm_accepted_age = scene_hard_cut > 0u ? 0.0 : accepted_age;

    float kept = 0.0;
    for (uint i = 0u; i < dsm_next_count; i++) {
        kept += exp(dsm_logits[dsm_selected[i]] - largest);
    }
    dsm_log_kept = largest + log(kept);
    scene_discarded_mass = clamp(1.0 - kept / total, 0.0, 1.0);
}

void dsm_write(uint index) {
    uint count = dsm_next_count;
    if (index >= count) {
        return;
    }
    uint selected = dsm_operation == 1u ? 0u : dsm_selected[index];
    bool prior = dsm_operation == 1u || selected == dsm_old_count;
    uint source = dsm_read_bank * DSM_CAPACITY + selected;
    uint target = (1u - dsm_read_bank) * DSM_CAPACITY + index;
    dsm_weights[target] = dsm_operation == 1u ? 0.0 :
                          dsm_logits[selected] - dsm_log_kept;
    dsm_age[target] = prior ? 0.0 : dsm_ages[selected];
    for (uint d = 0u; d < 4u; d++) {
        float prec = prior ? dsm_new_prior_precision[d]
                           : dsm_precision[source * 4u + d];
        float information = prior ? dsm_new_prior_information[d]
                                  : dsm_information[source * 4u + d];
        dsm_precision[target * 4u + d] = prec + dsm_precision_step[d];
        dsm_information[target * 4u + d] = information + dsm_information_step[d];
    }
}

void hook() {
    uint tid = gl_LocalInvocationIndex;
    if (tid == 0u) {
        dsm_prepare();
    }
    barrier();
    if (dsm_operation == 0u) {
        return;
    }
    if (tid == 0u) {
        dsm_set_prior();
    }
    barrier();
    if (dsm_operation == 2u) {
        dsm_candidate(tid);
    }
    barrier();
    if (dsm_operation == 2u && dsm_bad_history == 0u) {
        dsm_rank(tid);
    }
    barrier();
    if (tid == 0u) {
        if (dsm_bad_history > 0u) {
            dsm_operation = 1u;
            scene_state_recovered = 1u;
        }
        if (dsm_operation == 1u) {
            dsm_next_count = 1u;
            dsm_accepted_age = 0.0;
            scene_bootstrap = 1u;
        } else {
            dsm_select();
        }
    }
    barrier();
    dsm_write(tid);
    memoryBarrierBuffer();
    barrier();
    if (tid == 0u) {
        dsm_bank = 1u - dsm_read_bank;
        dsm_count = dsm_next_count;
        dsm_last_pts = floatBitsToUint(PTS);
        dsm_valid = 1u;
        scene_observation_valid = 1u;
    }
}


//!HOOK OUTPUT
//!BIND METERED
//!BIND SCENE_DSM
//!BIND SCENE_RESPONSE
//!BIND EXPOSURE_TEMPORAL
//!BIND CURVE_TEMPORAL
//!BIND METADATA
//!SAVE EMPTY
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 1 1
//!DESC metering (metadata)

// CURVE_TEMPORAL ownership:
// Metadata owns pts/valid and publishes reset/alpha for the current dispatch.
// The curve LUT pass owns values and consumes those instructions: reset
// overwrites history without reading alpha; otherwise alpha blends history.
// With smoothing enabled and valid history, repeated PTS publishes
// reset=0, alpha=0 to hold the existing LUT.

// The dynamic-metadata parameters at the top of the file are provided by mpv
// [vo_gpu_next: add chroma location to shader parameters](https://github.com/mpv-player/mpv/pull/15239)

// Fixed histogram-branch policy: blend toward the content-white exposure
// constraint and retain half of the highlight headroom when limiting darkening.
const float AUTO_EXPOSURE_WHITE_CONSTRAINT = 0.25;
const float AUTO_EXPOSURE_HEADROOM_RETENTION = 0.5;

// Shared minimum time constant for the exposure integrator and curve LUT ramp.
const float TEMPORAL_MIN_TIME_CONSTANT = 1.0 / 240.0;

// Filter automatic exposure in its final EV domain so observation noise cannot
// become a large nonlinear luminance change. Manual exposure remains direct.
const float EXPOSURE_RISE_TIME_SCALE = 0.50;
const float EXPOSURE_FALL_TIME_SCALE = 0.35;
const float EXPOSURE_PTS_EPSILON = 1e-6;
// IMQ soft threshold in EV. Covariance is dimensionless, normalized so its
// unweighted continuous-time equilibrium is one; there is no reference fps.
const float EXPOSURE_WOLF_OUTLIER_EV = 0.5;
const float EXPOSURE_WOLF_VARIANCE_MIN = 1e-6;
const float EXPOSURE_WOLF_VARIANCE_MAX = 1e6;

// Protect a large EV mismatch when new-scene prediction is at least 4x likelier.
const float EXPOSURE_PROTECTION_LOG_BAYES = 1.3862943611198906;
const float EXPOSURE_PROTECTION_TRIGGER_EV = 0.5;
// A target step distinguishes a new shot from a filter still catching up.
const float EXPOSURE_PROTECTION_TARGET_STEP_EV = 0.2;
// Hold a first-frame bridge while the filter is still at least this far,
// for at most this many consecutive frames.
const float EXPOSURE_PROTECTION_HOLD_EV = 0.1;
const uint EXPOSURE_PROTECTION_HOLD_FRAMES = 3u;

// Scene probability selects the faster exposure and curve response scales.
const float OUTPUT_TEMPORAL_SCENE_TIME_SCALE = 0.125;
const float CURVE_TEMPORAL_RESPONSE_TIME_SCALE = 0.0625;

// All curve samples use one PTS-derived coefficient. Compute it once in this
// single-invocation pass instead of repeating the timestamp state and exp()
// evaluation in every curve-LUT invocation.
const float CURVE_TEMPORAL_TIME_SCALE = 0.35;
const float CURVE_TEMPORAL_PTS_EPSILON = 1e-6;

bool finite_float(float value) {
    return !isnan(value) && !isinf(value);
}

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

float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

float sanitize_metadata_pq(float value) {
    return sanitize_bounded(value, 0.0, 1.0);
}

float sanitize_metadata_nits(float value) {
    // Deliberately caps metadata at the PQ mastering range. Values above
    // 10000 nits cannot reach tone mapping, and leaving them extrapolated
    // would diverge from the per-pixel LUT path, which is clamped at pw.
    return sanitize_bounded(value, 0.0, pw);
}

vec3 sanitize_metadata_nits(vec3 value) {
    return vec3(
        sanitize_metadata_nits(value.r),
        sanitize_metadata_nits(value.g),
        sanitize_metadata_nits(value.b)
    );
}

float metadata_nits_to_pq(float value) {
    float luminance = sanitize_metadata_nits(value);
    return luminance > 0.0 ? pq_eotf_inv(luminance) : 0.0;
}

// Quantization scale for PQ12 intensity statistics.
const float METERING_CODE_SCALE = 4095.0;

float to_float(uint x) {
    return float(x) / METERING_CODE_SCALE;
}

struct MeteringMetrics {
    // maximum is the robust intensity white used by exposure constraints;
    // max_rgb is the maximum channel value used by the tone curve.
    float maximum;
    float max_rgb;
    float minimum;
    float average;
    float histogram_average;
    float matrix_average;
    float matrix_weight;
    float median;
    float diffuse_white;
};

// Clamp positive measurements; preserve zero as the missing-value sentinel.
float clamp_measured(float value, float lower, float upper) {
    return value > 0.0 ? clamp(value, lower, upper) : value;
}

// Bound measured averages and percentiles by the robust intensity range.
// In level-1 mixed mode, the average may come from metadata.
void normalize_metering_metrics(inout MeteringMetrics metrics) {
    metrics.max_rgb = max(metrics.max_rgb, metrics.maximum);
    metrics.minimum = min(metrics.minimum, metrics.maximum);
    metrics.average = clamp_measured(
        metrics.average,
        metrics.minimum,
        metrics.maximum
    );
    metrics.histogram_average = clamp_measured(
        metrics.histogram_average,
        metrics.minimum,
        metrics.maximum
    );
    metrics.matrix_average = clamp_measured(
        metrics.matrix_average,
        metrics.minimum,
        metrics.maximum
    );
    metrics.median = clamp_measured(
        metrics.median,
        metrics.minimum,
        metrics.maximum
    );
    // Keep P80 at or above P50.
    metrics.diffuse_white = clamp_measured(
        metrics.diffuse_white,
        metrics.median,
        metrics.maximum
    );
}

bool measured_metering_enabled() {
    bool has_pq_peak = max_pq_y > 0.0;
    bool has_scene_peak = scene_max_r > 0.0 || scene_max_g > 0.0 ||
                          scene_max_b > 0.0;
    return enable_metering > 0 &&
           (force_metering > 0 || (!has_pq_peak && !has_scene_peak));
}

bool full_metering_enabled() {
    return enable_metering > 1 && measured_metering_enabled();
}

MeteringMetrics resolve_metering_metrics() {
    MeteringMetrics metrics;
    float pq_peak = sanitize_metadata_pq(max_pq_y);
    float pq_average = sanitize_metadata_pq(avg_pq_y);
    vec3 scene_max_rgb = sanitize_metadata_nits(
        vec3(scene_max_r, scene_max_g, scene_max_b)
    );
    float scene_average = sanitize_metadata_nits(scene_avg);
    float static_max_cll = sanitize_metadata_nits(max_cll);
    float static_max_luma = sanitize_metadata_nits(max_luma);
    float static_min_luma = sanitize_metadata_nits(min_luma);
    bool has_pq_peak = pq_peak > 0.0;
    bool has_scene_peak = any(greaterThan(scene_max_rgb, vec3(0.0)));

    // Match the intensity-map pass's peak-metadata test.
    // Preview computes measurements without changing this selection.
    bool use_measured = measured_metering_enabled();
    bool use_full_measured = full_metering_enabled();

    if (use_measured) {
        metrics.maximum = to_float(metered_max_i);
    } else if (has_pq_peak) {
        metrics.maximum = pq_peak;
    } else if (has_scene_peak) {
        metrics.maximum = metadata_nits_to_pq(RGB_to_Y(scene_max_rgb));
    } else if (static_max_cll > 0.0) {
        metrics.maximum = metadata_nits_to_pq(static_max_cll);
    } else if (static_max_luma > 0.0) {
        metrics.maximum = metadata_nits_to_pq(static_max_luma);
    } else {
        metrics.maximum = pq_eotf_inv(1000.0);
    }

    if (use_measured) {
        metrics.max_rgb = uintBitsToFloat(metered_max_rgb);
    } else if (has_scene_peak) {
        metrics.max_rgb = metadata_nits_to_pq(
            max(max(scene_max_rgb.r, scene_max_rgb.g), scene_max_rgb.b)
        );
    } else {
        metrics.max_rgb = metrics.maximum;
    }

    if (use_measured) {
        metrics.minimum = to_float(metered_min_i);
    } else if (static_min_luma > 0.0) {
        metrics.minimum = metadata_nits_to_pq(static_min_luma);
    } else {
        metrics.minimum = 0.0;
    }

    if (use_full_measured) {
        metrics.average = to_float(metered_avg_i);
    } else if (pq_average > 0.0) {
        metrics.average = pq_average;
    } else if (scene_average > 0.0) {
        metrics.average = metadata_nits_to_pq(scene_average);
    // MaxFALL is the static-metadata fallback for average luminance, but using
    // it as the exposure anchor produced poor results in practice.
    // } else if (max_fall > 0.0) {
    //     metrics.average = pq_eotf_inv(max_fall);
    } else {
        metrics.average = 0.0;
    }

    // The separate averages and percentile statistics only exist in the full
    // measurement path. Metadata paths retain the original single-exposure
    // behavior by assigning the resolved average to both branches and giving
    // the matrix branch zero weight.
    if (use_full_measured) {
        metrics.histogram_average = sanitize_metadata_pq(
            metered_histogram_average
        );
        metrics.matrix_average = sanitize_metadata_pq(
            metered_matrix_average
        );
        metrics.matrix_weight = sanitize_bounded(
            metered_matrix_blend,
            0.0,
            1.0
        );
        metrics.median = to_float(metered_median_i);
        metrics.diffuse_white = to_float(metered_diffuse_white_i);
    } else {
        metrics.histogram_average = metrics.average;
        metrics.matrix_average = metrics.average;
        metrics.matrix_weight = 0.0;
        metrics.median = 0.0;
        metrics.diffuse_white = 0.0;
    }

    normalize_metering_metrics(metrics);

    return metrics;
}

float calculate_content_white_exposure(MeteringMetrics metrics) {
    float median = max(pq_eotf(metrics.median), 0.0);
    float diffuse_white = max(pq_eotf(metrics.diffuse_white), 1e-6);

    // P80 represents the frame's bright content without chasing specular
    // outliers. Cap it at 1.5 times P50 in perceptual Jz lightness so a sparse
    // bright tail cannot masquerade as the content white.
    float median_j = I_to_J(iz_eotf_inv(median));
    float maximum_j = I_to_J(iz_eotf_inv(pw));
    float content_white_j = min(
        I_to_J(iz_eotf_inv(diffuse_white)),
        min(1.5 * median_j, maximum_j)
    );
    float content_white = max(
        iz_eotf(J_to_I(max(content_white_j, 0.0))),
        1e-6
    );

    return log2(reference_white / content_white);
}

float constrain_exposure_to_content_white(
    float exposure,
    MeteringMetrics metrics
) {
    if (AUTO_EXPOSURE_WHITE_CONSTRAINT <= 0.0 ||
        metrics.median <= 0.0 ||
        metrics.diffuse_white <= 0.0) {
        return exposure;
    }

    float content_white_exposure = calculate_content_white_exposure(metrics);
    bool constrains_negative = exposure < 0.0 &&
                               content_white_exposure <= 0.0 &&
                               content_white_exposure > exposure;
    bool constrains_positive = exposure > 0.0 &&
                               content_white_exposure >= 0.0 &&
                               content_white_exposure < exposure;
    if (!constrains_negative && !constrains_positive) {
        return exposure;
    }

    return mix(
        exposure,
        content_white_exposure,
        clamp(AUTO_EXPOSURE_WHITE_CONSTRAINT, 0.0, 1.0)
    );
}

float limit_average_exposure(
    float exposure,
    float average,
    float maximum,
    float minimum,
    float negative_limit
) {
    float ev_limit_neg = negative_limit;
    float ev_limit_pos = auto_exposure_limit_positive;

    if (auto_exposure_limit_input > 0) {
        ev_limit_neg = min(ev_limit_neg, log2(maximum / average));
        ev_limit_pos = min(ev_limit_pos, log2(average / minimum));
    }

    return clamp(exposure, -ev_limit_neg, ev_limit_pos);
}

// Limit histogram-branch darkening by the retained highlight headroom.
float histogram_negative_exposure_limit(
    MeteringMetrics metrics,
    float maximum
) {
    float histogram_negative_limit = auto_exposure_limit_negative;
    bool has_content_white = metrics.median > 0.0 &&
                             metrics.diffuse_white > 0.0;
    if (AUTO_EXPOSURE_HEADROOM_RETENTION > 0.0 && has_content_white) {
        float highlight_headroom = max(
            log2(maximum / max(reference_white, 1e-6)),
            0.0
        );
        float retained_headroom = clamp(
            AUTO_EXPOSURE_HEADROOM_RETENTION,
            0.0,
            1.0
        );
        histogram_negative_limit = min(
            histogram_negative_limit,
            (1.0 - retained_headroom) * highlight_headroom
        );
    }

    return histogram_negative_limit;
}

// Solve and constrain the two exposures separately, then blend in EV space.
// Using metrics.average instead would blend PQ values before exposure solving
// and would bypass the distinct constraints of the two branches.
float calculate_auto_exposure(MeteringMetrics metrics) {
    float reference_iz = iz_eotf_inv(reference_white);
    float reference_j = I_to_J(reference_iz);
    float anchor_j = auto_exposure_anchor * reference_j;
    float anchor_iz = J_to_I(anchor_j);
    float anchor = iz_eotf(anchor_iz);

    float histogram_average = max(
        pq_eotf(metrics.histogram_average),
        1e-6
    );
    float matrix_average = max(pq_eotf(metrics.matrix_average), 1e-6);
    float maximum = max(pq_eotf(metrics.maximum), 1e-6);
    float minimum = max(pq_eotf(metrics.minimum), 1e-6);

    float histogram_exposure = log2(anchor / histogram_average);
    float matrix_exposure = log2(anchor / matrix_average);

    // Content-distribution strategies belong to the histogram branch. The
    // matrix branch remains free to expose the selected subject or active
    // picture region, particularly when black bars have raised its weight.
    histogram_exposure = constrain_exposure_to_content_white(
        histogram_exposure,
        metrics
    );

    float histogram_negative_limit = histogram_negative_exposure_limit(
        metrics,
        maximum
    );

    histogram_exposure = limit_average_exposure(
        histogram_exposure,
        histogram_average,
        maximum,
        minimum,
        histogram_negative_limit
    );
    matrix_exposure = limit_average_exposure(
        matrix_exposure,
        matrix_average,
        maximum,
        minimum,
        auto_exposure_limit_negative
    );

    return mix(
        histogram_exposure,
        matrix_exposure,
        clamp(metrics.matrix_weight, 0.0, 1.0)
    );
}

// Automatic exposure requires zero manual override and positive average/anchor.
bool automatic_exposure_enabled(MeteringMetrics metrics) {
    return exposure_value == 0.0 &&
           metrics.average > 0.0 &&
           auto_exposure_anchor > 0.0;
}

float resolve_exposure(MeteringMetrics metrics) {
    // A non-zero external value replaces automatic exposure entirely.
    if (exposure_value != 0.0) {
        return exposure_value;
    }

    if (!automatic_exposure_enabled(metrics)) {
        return 0.0;
    }

    return calculate_auto_exposure(metrics);
}

// Resolve once per metadata pass. Preview may maintain scene diagnostics;
// only selected measurements may drive normal output.
float output_response_weight;
bool output_scene_cut;
bool output_scene_current;
float output_change_log_bayes;
bool output_exposure_protected;
float exposure_pre_step_ev;
bool exposure_pre_step_valid;

void dsm_clear_response() {
    scene_new_probability = 0.0;
    scene_short_probability = 0.0;
    scene_confirmed_probability = 0.0;
    scene_fast_strength = 0.0;
    scene_discarded_mass = 0.0;
    scene_outlier_probability = 0.0;
    scene_change_log_bayes = 0.0;
    scene_hard_cut = 0u;
    scene_bootstrap = 0u;
    scene_observation_valid = 0u;
    scene_state_recovered = 0u;
    scene_observation_pts = floatBitsToUint(PTS);
}

void invalidate_exposure_protection() {
    exposure_protection_valid = 0u;
    exposure_protection_state = 0u;
    exposure_protection_run = 0u;
}

void prepare_output_temporal_response() {
    output_response_weight = 0.0;
    output_scene_cut = false;
    output_scene_current = false;
    output_change_log_bayes = 0.0;
    output_exposure_protected = false;
    bool temporal = temporal_stable_duration > 0.0;
    bool measured = measured_metering_enabled();
    if (!temporal || (!measured && preview_metering == 0u)) {
        dsm_valid = 0u;
        dsm_clear_response();
    }
    bool current = temporal && measured && temporal_stable_scene_change > 0u &&
                   scene_observation_valid > 0u && finite_float(PTS) &&
                   abs(PTS - uintBitsToFloat(scene_observation_pts)) <=
                       EXPOSURE_PTS_EPSILON;
    if (!current) {
        // Policy/source changes may redraw the same PTS. Release the bridge
        // before protect_auto_exposure() can reuse that frame's decision.
        invalidate_exposure_protection();
        return;
    }
    // Events stay latched for redraws; exposure/curve consume each PTS once.
    output_scene_current = true;
    output_scene_cut = scene_hard_cut > 0u;
    output_response_weight = finite_float(scene_fast_strength)
        ? clamp(scene_fast_strength, 0.0, 1.0) : 0.0;
    output_change_log_bayes = finite_float(scene_change_log_bayes)
        ? scene_change_log_bayes : 0.0;
}

float protect_auto_exposure(float filtered, float target, bool automatic) {
    output_exposure_protected = false;
    if (!automatic || !finite_float(target) || !finite_float(PTS)) {
        invalidate_exposure_protection();
        return filtered;
    }
    uint pts_bits = floatBitsToUint(PTS);
    // A repeated PTS must consume the same decision. The pre-step state is
    // gone after the first dispatch updated the filter, so latch the result.
    if (exposure_protection_valid > 0u && exposure_protection_pts == pts_bits) {
        output_exposure_protected = exposure_protection_state > 0u;
        return output_exposure_protected ? clamp(target, -64.0, 64.0) : filtered;
    }
    target = clamp(target, -64.0, 64.0);
    // Compare the target with the exposure the filter held before this
    // frame's WoLF update. The post-step value depends on the residual-
    // dependent gain and on the cut direction, so it under-reports a jump
    // whose ordinary step already closed half of the gap.
    float residual = exposure_pre_step_valid
        ? abs(exposure_pre_step_ev - target)
        : abs(filtered - target);
    // A target that did not step is a filter still catching up, not a new
    // shot: do not bypass WoLF merely because the lag exceeds the trigger.
    bool target_step = exposure_target_valid == 0u ||
                       abs(target - exposure_previous_target) >=
                           EXPOSURE_PROTECTION_TARGET_STEP_EV;
    bool base = !output_scene_cut &&
                target_step &&
                output_change_log_bayes >= EXPOSURE_PROTECTION_LOG_BAYES &&
                residual >= EXPOSURE_PROTECTION_TRIGGER_EV;
    // Hold a first-frame bridge while the filter is still settling. The
    // target is re-read every frame, so a returning flash follows its own
    // target instead of staying at the transient level. The hold never
    // starts on its own: a target that did not step stays filtered.
    bool hold = !output_scene_cut &&
                output_scene_current &&
                exposure_pre_step_valid &&
                exposure_protection_run > 0u &&
                exposure_protection_run < EXPOSURE_PROTECTION_HOLD_FRAMES &&
                residual >= EXPOSURE_PROTECTION_HOLD_EV;
    output_exposure_protected = base || hold;
    exposure_protection_run = output_exposure_protected
        ? exposure_protection_run + 1u : 0u;
    exposure_protection_pts = pts_bits;
    exposure_protection_valid = 1u;
    exposure_protection_state = output_exposure_protected ? 1u : 0u;
    exposure_previous_target = target;
    exposure_target_valid = 1u;
    // Use the same EV as a confirmed cut, without writing the WoLF state.
    return output_exposure_protected ? target : filtered;
}

float output_temporal_time_scale(float normal_scale, float response_scale) {
    return mix(normal_scale, min(normal_scale, response_scale),
               output_response_weight);
}

// PTS-derived exponential blend coefficient for the curve LUT.
float ramp_alpha(float delta_time, float time_scale) {
    float time_constant = max(
        temporal_stable_duration * time_scale,
        TEMPORAL_MIN_TIME_CONSTANT
    );
    return 1.0 - exp(-delta_time / time_constant);
}

float reset_auto_exposure(float target) {
    exposure_temporal_ev = target;
    exposure_temporal_variance = 1.0;
    exposure_temporal_pts = floatBitsToUint(PTS);
    exposure_temporal_valid = 1u;
    // Zero is a valid PTS. Track latch validity independently so every reset
    // starts a new decision, including a seek back to the beginning.
    invalidate_exposure_protection();
    exposure_previous_target = target;
    exposure_target_valid = 1u;
    return target;
}

// Continuous-time adaptation of WoLF's IMQ observation weighting:
// https://arxiv.org/html/2405.05646v2 (equation 17).
// With s = elapsed PTS / tau, integrate dP/ds = 1 - w^2 P^2 and
// dx/ds = w^2 P (target - x), holding target, w and tau over this interval.
// The closed-form update composes exactly for a fixed weight and time scale.
// Residual-dependent weights are sampled per observation, so different input
// PTS sequences need not give identical nonlinear trajectories.
// Advance the variance history and return the corresponding EV blend gain.
float update_exposure_wolf_gain(float delta_time, float time_scale, float residual) {
    float tau = max(temporal_stable_duration * time_scale,
                    TEMPORAL_MIN_TIME_CONSTANT);
    float scaled_residual = residual / EXPOSURE_WOLF_OUTLIER_EV;
    float weight = inversesqrt(1.0 + scaled_residual * scaled_residual);
    float variance = clamp(exposure_temporal_variance,
                           EXPOSURE_WOLF_VARIANCE_MIN,
                           EXPOSURE_WOLF_VARIANCE_MAX);
    float u = weight * delta_time / tau;
    float decay = exp(-u);
    // Avoid cancellation in 1-exp(-u) for very short PTS intervals.
    float one_minus_decay = u < 0.001
        ? u * (1.0 - 0.5 * u + u * u / 6.0)
        : 1.0 - decay;
    float one_minus_decay_squared = one_minus_decay * (1.0 + decay);
    float one_plus_decay_squared = 1.0 + decay * decay;
    float b = variance * weight;
    float denominator = one_plus_decay_squared + b * one_minus_decay_squared;
    float gain = one_minus_decay * (one_minus_decay + b * (1.0 + decay)) /
                 denominator;
    float hyperbolic_tangent = one_minus_decay_squared / one_plus_decay_squared;
    exposure_temporal_variance = clamp(
        (variance + hyperbolic_tangent / weight) /
            (1.0 + b * hyperbolic_tangent),
        EXPOSURE_WOLF_VARIANCE_MIN, EXPOSURE_WOLF_VARIANCE_MAX
    );
    return clamp(gain, 0.0, 1.0);
}

float stabilize_auto_exposure(float target, bool automatic) {
    // Clamp EV to the declared range; use neutral EV for a non-finite target.
    target = finite_float(target) ? clamp(target, -64.0, 64.0) : 0.0;
    exposure_pre_step_valid = false;
    // Discard non-finite exposure history or timestamps.
    if (exposure_temporal_valid > 0u &&
        (!finite_float(exposure_temporal_ev) ||
         !finite_float(uintBitsToFloat(exposure_temporal_pts)))) {
        exposure_temporal_valid = 0u;
    }

    if (!finite_float(PTS)) {
        // Bypass smoothing on invalid PTS. The next finite PTS sets a new baseline.
        exposure_temporal_valid = 0u;
        return target;
    }

    if (!automatic || temporal_stable_duration <= 0.0) {
        return reset_auto_exposure(target);
    }

    if (exposure_temporal_valid == 0u ||
        !finite_float(exposure_temporal_variance) ||
        exposure_temporal_variance <= 0.0) {
        return reset_auto_exposure(target);
    }

    float delta_time = PTS - uintBitsToFloat(exposure_temporal_pts);
    if (abs(delta_time) <= EXPOSURE_PTS_EPSILON) {
        exposure_pre_step_ev = exposure_temporal_ev;
        exposure_pre_step_valid = true;
        return exposure_temporal_ev;
    }

    if (delta_time < 0.0 || delta_time > temporal_stable_duration ||
        output_scene_cut) {
        return reset_auto_exposure(target);
    }

    float time_scale = target > exposure_temporal_ev
        ? EXPOSURE_RISE_TIME_SCALE
        : EXPOSURE_FALL_TIME_SCALE;
    time_scale = output_temporal_time_scale(
        time_scale, OUTPUT_TEMPORAL_SCENE_TIME_SCALE
    );
    exposure_pre_step_ev = exposure_temporal_ev;
    exposure_pre_step_valid = true;
    float alpha = update_exposure_wolf_gain(
        delta_time, time_scale, target - exposure_temporal_ev
    );
    exposure_temporal_ev = mix(exposure_temporal_ev, target, alpha);
    exposure_temporal_pts = floatBitsToUint(PTS);
    return exposure_temporal_ev;
}

void record_curve_temporal_pts() {
    curve_temporal_pts = floatBitsToUint(PTS);
    curve_temporal_valid = 1u;
}

void prepare_curve_temporal() {
    curve_temporal_reset = 1u;
    curve_temporal_bypass = 0u;

    if (!finite_float(PTS)) {
        // Keep reset active on invalid PTS and on the next finite-PTS frame.
        // Reset paths overwrite curve values without reading alpha.
        curve_temporal_valid = 0u;
        return;
    }

    if (temporal_stable_duration <= 0.0) {
        record_curve_temporal_pts();
        return;
    }

    if (output_exposure_protected) {
        // Render the protected frame with its own curve. Keep both the old
        // LUT history and its timestamp for a flash return or a confirmed cut.
        curve_temporal_bypass = 1u;
        curve_temporal_reset = 0u;
        curve_temporal_alpha = 0.0;
        return;
    }

    if (curve_temporal_valid == 0u) {
        record_curve_temporal_pts();
        return;
    }

    float delta_time = PTS - uintBitsToFloat(curve_temporal_pts);
    if (abs(delta_time) <= CURVE_TEMPORAL_PTS_EPSILON) {
        curve_temporal_alpha = 0.0;
        curve_temporal_reset = 0u;
        return;
    }

    record_curve_temporal_pts();
    if (delta_time < 0.0 || delta_time > temporal_stable_duration ||
        output_scene_cut) {
        return;
    }

    // Exposure is already filtered: shorten the second ramp during changes.
    // One coefficient for the entire LUT preserves monotonicity.
    float time_scale = output_temporal_time_scale(
        CURVE_TEMPORAL_TIME_SCALE, CURVE_TEMPORAL_RESPONSE_TIME_SCALE
    );
    curve_temporal_alpha = ramp_alpha(delta_time, time_scale);
    curve_temporal_reset = 0u;
}

float apply_exposure_to_pq(float value, float scale) {
    // Apply exposure in nits, cap at the PQ peak, and preserve the zero sentinel.
    return metadata_nits_to_pq(pq_eotf(value) * scale);
}

void apply_exposure_to_range(inout MeteringMetrics metrics, float scale) {
    if (scale == 1.0) {
        return;
    }

    metrics.maximum = apply_exposure_to_pq(metrics.maximum, scale);
    metrics.max_rgb = apply_exposure_to_pq(metrics.max_rgb, scale);
    metrics.minimum = apply_exposure_to_pq(metrics.minimum, scale);
}

void publish_metering_metadata(MeteringMetrics metrics) {
    exposed_max_i = metrics.max_rgb;
    exposed_min_i = metrics.minimum;
    // All 1024 curve samples share these exposed, output-bounded endpoints.
    // Convert PQ to J once in this single-invocation metadata pass.
    input_max_j = max(
        I_to_J(iz_eotf_inv(pq_eotf(exposed_max_i))), output_max_j
    );
    input_min_j = min(
        I_to_J(iz_eotf_inv(pq_eotf(exposed_min_i))), output_min_j
    );
}

void publish_input_metering_metadata(MeteringMetrics metrics) {
    input_max_i = metrics.maximum;
    input_min_i = metrics.minimum;
    input_avg_i = metrics.average;
}

// Extend the reverse LUT above neutral white for H-K-compensated lightness.
// The margin scales with hk_effect_compensate_scaling.
const float REVERSE_LUT_HK_LIGHTNESS_MARGIN = 0.0096;

void publish_output_lightness_range() {
    output_max_j = I_to_J(iz_eotf_inv(reference_white));
    output_min_j = I_to_J(
        iz_eotf_inv(
            contrast_ratio > 0.0 ? reference_white / contrast_ratio : 0.0
        )
    );
    output_reverse_max_jhk = output_max_j +
        hk_effect_compensate_scaling * REVERSE_LUT_HK_LIGHTNESS_MARGIN;
}

void update_metering_metadata() {
    prepare_output_temporal_response();
    publish_output_lightness_range();

    MeteringMetrics metrics = resolve_metering_metrics();
    publish_input_metering_metadata(metrics);
    float target_ev = resolve_exposure(metrics);
    bool automatic = automatic_exposure_enabled(metrics);
    float filtered_ev = stabilize_auto_exposure(target_ev, automatic);
    float protected_ev = protect_auto_exposure(
        filtered_ev,
        target_ev,
        automatic
    );
    prepare_curve_temporal();
    // Apply manual EV compensation after smoothing so it stays immediate and
    // does not enter automatic-exposure history. Bound the combined exposure.
    exposure_ev = clamp(
        protected_ev + exposure_value_offset,
        -64.0,
        64.0
    );
    exposure_scale = exp2(exposure_ev);
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
//!DESC tone mapping (astra, lut generation)

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

// Optimized matrices for the Jzazbz LMS-to-I conversion.
// [A Uniform and Hue Linear Color Space for Perceptual Image Processing Including HDR and Wide Gamut Image Signal](https://doi.org/10.2352/ISSN.2169-2629.2017.25.264)
// ZCAM defines I_z = G' - ε, where ε = 3.7035226210190005e-11.
// However, it appears we do not need it.
// [ZCAM, a colour appearance model based on a high dynamic range uniform colour space](https://doi.org/10.1364/OE.413659)
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
// [Prediction of the Helmholtz-Kohlrausch effect using the CIELUV formula](https://doi.org/10.1002/(SICI)1520-6378(199608)21:4%3C252::AID-COL1%3E3.0.CO;2-P)
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
// [Extending CIECAM02 and CAM16 for the Helmholtz–Kohlrausch effect](https://doi.org/10.1002/col.22793)
float hke_fh_hellwig(float h, float a1, float a2, float a3, float a4, float a5) {
    return a1 * cos(h) + a2 * cos(2.0 * h) + a3 * sin(h) + a4 * sin(2.0 * h) + a5;
}

// CIELAB: 0.1644, 0.0603, 0.1307, 0.0060
// [The Helmholtz–Kohlrausch effect on display-based light colors and simulated substrate colors](https://doi.org/10.1002/col.22839)
float hke_fh_high(float h, float k1, float k2, float k3, float k4) {
    h = mod(mod(degrees(h), 360.0) + 360.0, 360.0);
    float by = k1 * abs(sin(radians((h - 90.0)/ 2.0))) + k2;
    float r  = h <= 90.0 || h >= 270.0 ? k3 * abs(cos(radians(h))) + k4 : 0.0;
    return by + r;
}

// CIECAM16: 1.5940, 45.0, 2.6518
// CIELAB: 0.1644, 45.0, 0.1024
// [Lightness modifications of the CIECAM16 and CIELAB based on the Helmholtz-Kohlrausch effect](https://doi.org/10.1364/OE.534073)
float hke_fh_liao(float h, float k3, float k4, float k5) {
    h = mod(mod(degrees(h), 360.0) + 360.0, 360.0);
    return k3 * abs(log(((h + k4) / (90.0 + k4)))) + k5;
}

float hke_fh(float h) {
    float result = hke_fh_liao(h, 0.1351, 45.0, 0.1439);
    return result * hk_effect_compensate_scaling;
}

// The H-K lightness index of the form J + C * f(h).
// [Predicting the lightness of chromatic object colors using CIELAB](https://doi.org/10.1002/col.5080160608)
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

vec3 Lab_to_LCh(vec3 Lab) {
    float L = Lab.x;
    float a = Lab.y;
    float b = Lab.z;

    float C = length(vec2(a, b));
    float h = C == 0.0 ? 0.0 : atan(b, a);

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

// Jzazbz serves as the working space, with two deviations from the
// paper's definition: the LMS-to-Iab matrix is the optimized variant
// (see LMS_to_Iab_optimized, CIC 2017), and J is the H-K-compensated
// lightness from J_to_Jhk.
// [Perceptually uniform color space for image signals including high dynamic range and wide gamut](https://doi.org/10.1364/OE.25.015131)
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

float f_contrast_slope(float c) {
    return exp2(c);
}

// Generalized rational toe/shoulder inspired by:
// - Suzuki's hyperbolic segments, recovered here when overshoot = 0 (p = 1).
//   [Hyperbola tone mapping](https://technorgb.blogspot.com/2018/02/hyperbola-tone-mapping.html)
// - Hable's piecewise construction, slope matching, and shoulder overshoot.
//   [Filmic Tonemapping with Piecewise Power Curves](https://filmicworlds.com/blog/filmic-tonemapping-with-piecewise-power-curves/)
// This extension keeps both anchors and the join slope fixed while varying
// the denominator exponent; it does not use Hable's power-curve formula or
// his virtual-white-point construction. The toe reflects the shoulder.
// "rational" names the base family: for noninteger p the generalized form
// is generally not a rational function (a ratio of polynomials).
//
// Generalized rational toe, the reflection of f_shoulder_rational:
//
//   g(t) = t / (1 + a * (1 - t))^p
//
// For a positive span with normalized_slope >= 1, it passes through both
// anchors, matches the outgoing slope at t = 1, and stays increasing below
// the black point. Larger overshoot retains more slope near and below black.
float f_toe_rational(
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
    float denominator = pow(1.0 + curvature * (1.0 - t), power);
    return y0 + dy * t / denominator;
}

// Generalized rational shoulder. In normalized coordinates t and g(t):
//
//   g(t) = 1 - (1 - t) / (1 + a * t)^p
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

float evaluate_tone_curve(float x) {
    float iw = input_max_j;
    float ib = input_min_j;
    float ow = output_max_j;
    float ob = output_min_j;
    float midgray   = 0.5 * ow;
    float shadow    = mix(midgray, ob, shadow_weight);
    float highlight = mix(midgray, ow, highlight_weight);
    float target_slope = f_contrast_slope(contrast_bias);

    float x0 = ib;
    float y0 = ob;
    float x1 = shadow;
    float y1 = shadow;
    float x2 = highlight;
    float y2 = highlight;
    float x3 = iw;
    float y3 = ow;

    // Pivot the middle segment around mid-gray. Move junctions along
    // the same line when their output values cross an endpoint.
    y1 = midgray + target_slope * (x1 - midgray);
    if (y1 < y0) {
        y1 = y0;
        x1 = midgray + (y1 - midgray) / target_slope;
    }
    y2 = midgray + target_slope * (x2 - midgray);
    if (y2 > y3) {
        y2 = y3;
        x2 = midgray + (y2 - midgray) / target_slope;
    }

    // Preserve the requested slope even when both junctions reach mid-gray.
    float slope = target_slope;
    float intercept = f_intercept(slope, x1, y1);

    if (x >= x1 && x <= x2) {
        return f_linear(x, slope, intercept);
    }

    // Clip collapsed output spans. Use a curved segment only when its
    // endpoint slope is below the middle-segment slope.
    if (x < x1) {
        // Flat clip at the black endpoint; the steep segment begins at x_1.
        if (y1 <= y0) {
            return y0;
        }

        float slope_toe = f_slope(x0, y0, x1, y1);
        if (slope_toe >= slope) {
            return f_linear(x, slope, intercept);
        }

        return f_toe_rational(
            x, slope,
            x0, y0, x1, y1,
            shadow_overshoot
        );
    }

    if (x > x2) {
        if (y2 >= y3) {
            return y3;
        }

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

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT, and one 1024-point curve row. The reverse LUT stores its
// higher-resolution Jhk axis in atlas rows and spans the output Rec.2020 cube,
// while a/b share the flattened axis to keep the atlas 65^2 texels wide. The
// neutral tone curve retains its narrower J range.
// Each pass owns its GLSL constants. Keep the same names and derivations in
// the consumers; astra-lab checks their agreement and the header allocations.
// Both flattened color LUTs share a row width.
const int FORWARD_LUT_SIZE = 65;
const int FORWARD_LUT_LAST = FORWARD_LUT_SIZE - 1;
const int REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int REVERSE_LIGHTNESS_LUT_LAST = REVERSE_LIGHTNESS_LUT_SIZE - 1;
const int REVERSE_CHROMA_LUT_SIZE = FORWARD_LUT_SIZE;
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
        output_min_j,
        output_reverse_max_jhk,
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

bool finite_float(float value) {
    return !isnan(value) && !isinf(value);
}

float stabilize_curve_value(int index, float target) {
    if (curve_temporal_bypass > 0u) {
        float value = finite_float(target) ? target : 0.0;
        // Publish the curve actually displayed without overwriting the
        // recoverable history used after a flash or a confirmed cut.
        curve_display_values[index] = value;
        return value;
    }
    // Read history only when reset is inactive.
    bool history_valid = curve_temporal_reset == 0u &&
                         finite_float(curve_temporal_values[index]);
    // On a non-finite target, hold valid history or use zero.
    if (!finite_float(target)) {
        target = history_valid ? curve_temporal_values[index] : 0.0;
    }

    if (curve_temporal_reset > 0u || !history_valid) {
        curve_temporal_values[index] = target;
        curve_display_values[index] = target;
        return target;
    }

    float value = mix(
        curve_temporal_values[index],
        target,
        curve_temporal_alpha
    );
    // Fall back to the target if interpolation produces a non-finite value.
    value = finite_float(value) ? value : target;
    curve_temporal_values[index] = value;
    curve_display_values[index] = value;
    return value;
}

void generate_curve_lut(ivec2 atlas_position) {
    int index = atlas_position.x;
    float coordinate = float(index) / float(CURVE_SIZE - 1);
    float value = stabilize_curve_value(index, evaluate_tone_curve(coordinate));
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
//!BIND LUTS
//!BIND METADATA
//!SAVE CHROMA_BOUNDARY
//!COMPONENTS 1
//!WIDTH 361
//!HEIGHT 129
//!COMPUTE 16 8
//!DESC tone mapping (astra, chroma boundary)

// Search once per Jhk/hue grid point, not once per output pixel. The final
// column repeats hue zero so interpolation across the hue seam is explicit.
// Store C/Jhk, which also behaves well near black. Regenerate after LUTS so
// H-K settings, output range and the inverse texture's precision all agree.
// Layout and shaper definitions match the LUT generation and application passes.
const int FORWARD_LUT_SIZE = 65;
const int REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int REVERSE_LIGHTNESS_LUT_LAST = REVERSE_LIGHTNESS_LUT_SIZE - 1;
const int REVERSE_CHROMA_LUT_SIZE = FORWARD_LUT_SIZE;
const int REVERSE_CHROMA_LUT_LAST = REVERSE_CHROMA_LUT_SIZE - 1;
const int RGB_TO_LAB_ROW = 0;
const int LAB_TO_RGB_ROW = RGB_TO_LAB_ROW + FORWARD_LUT_SIZE;
const float AB_RATIO_SCALE = 0.25;
const float A_RATIO_LIMIT = 2.5;
const float B_RATIO_LIMIT = 3.0;

const int BOUNDARY_HUE_INTERVALS = 360;
const int BOUNDARY_LIGHTNESS_INTERVALS = REVERSE_LIGHTNESS_LUT_LAST;

float encode_signed_coordinate(float value, float limit) {
    float maximum_magnitude = limit / (limit + AB_RATIO_SCALE);
    float encoded_magnitude = abs(value) / (abs(value) + AB_RATIO_SCALE);
    float signed_magnitude = sign(value) * encoded_magnitude / maximum_magnitude;
    return clamp(0.5 + 0.5 * signed_magnitude, 0.0, 1.0);
}

vec3 boundary_fetch_rgb(ivec3 texel) {
    return texelFetch(LUTS_raw, ivec2(
        texel.y + texel.z * REVERSE_CHROMA_LUT_SIZE,
        LAB_TO_RGB_ROW + texel.x
    ), 0).rgb;
}

vec3 boundary_inverse_rgb(vec2 ratio, int lightness_index) {
    vec2 encoded = vec2(
        encode_signed_coordinate(ratio.x, A_RATIO_LIMIT),
        encode_signed_coordinate(ratio.y, B_RATIO_LIMIT)
    );
    vec2 position = encoded * float(REVERSE_CHROMA_LUT_LAST);
    ivec2 lower = ivec2(floor(position));
    ivec2 upper = min(lower + ivec2(1), ivec2(REVERSE_CHROMA_LUT_LAST));
    vec2 weight = fract(position);

    // At an exact Jhk row, the final pass's tetrahedral interpolation reduces
    // to these two triangles. Bilinear interpolation here would measure a
    // different RGB boundary, especially around highly saturated blues.
    vec3 v0 = boundary_fetch_rgb(ivec3(lightness_index, lower));
    vec3 v2 = boundary_fetch_rgb(ivec3(lightness_index, upper));
    if (weight.x >= weight.y) {
        vec3 v1 = boundary_fetch_rgb(ivec3(lightness_index, upper.x, lower.y));
        return LUTS_mul * (v0 + weight.x * (v1 - v0) + weight.y * (v2 - v1));
    }
    vec3 v1 = boundary_fetch_rgb(ivec3(lightness_index, lower.x, upper.y));
    return LUTS_mul * (v0 + weight.y * (v1 - v0) + weight.x * (v2 - v1));
}

bool boundary_ratio_is_valid(vec2 ratio, int lightness_index) {
    vec3 rgb = boundary_inverse_rgb(ratio, lightness_index);
    return !any(isnan(rgb)) && !any(isinf(rgb)) &&
           all(greaterThanEqual(rgb, vec3(0.0)));
}

float find_chroma_boundary(vec2 direction, int lightness_index) {
    vec2 bounds = vec2(A_RATIO_LIMIT, B_RATIO_LIMIT) /
                  max(abs(direction), vec2(1e-6));
    float ratio_max = min(bounds.x, bounds.y);

    float lower = 0.0;
    float upper = ratio_max;
    bool found_boundary = false;
    // Find the first sampled exit from the neutral-connected region instead
    // of assuming that a potentially nonconvex slice has only one crossing.
    for (int step_index = 1; step_index <= 8; step_index++) {
        float probe = ratio_max * (float(step_index) / 8.0);
        if (!boundary_ratio_is_valid(direction * probe, lightness_index)) {
            upper = probe;
            found_boundary = true;
            break;
        }
        lower = probe;
    }
    if (found_boundary) {
        for (int iteration = 0; iteration < 12; iteration++) {
            float middle = 0.5 * (lower + upper);
            if (boundary_ratio_is_valid(direction * middle, lightness_index)) {
                lower = middle;
            } else {
                upper = middle;
            }
        }
    }
    return lower;
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);
    if (position.x > BOUNDARY_HUE_INTERVALS ||
        position.y > BOUNDARY_LIGHTNESS_INTERVALS) {
        return;
    }
    int hue_index = position.x % BOUNDARY_HUE_INTERVALS;
    float hue = 6.283185307179586 * float(hue_index) /
                float(BOUNDARY_HUE_INTERVALS);
    vec2 direction = vec2(cos(hue), sin(hue));

    // A zero-lightness row contains only black regardless of its a/b ratio.
    // Use the next row's ratio there; reconstructed chroma still tends to 0.
    int lightness_index = position.y;
    if (lightness_index == 0 && output_min_j <= 0.0) {
        lightness_index = 1;
    }
    float boundary = find_chroma_boundary(direction, lightness_index);

    // Leave room for rounding when the scalar boundary texture is half-float.
    // Interpolation can still overestimate a curved boundary; the final pass
    // validates its actual RGB result and handles that case separately.
    imageStore(out_image, position, vec4(boundary * 0.999, 0.0, 0.0, 1.0));
}

//!HOOK OUTPUT
//!BIND PREVIEW_VECTORSCOPE
//!SAVE EMPTY
//!COMPONENTS 1
//!WIDTH 96
//!HEIGHT 96
//!COMPUTE 16 16 16 16
//!WHEN preview_metering
//!DESC metering (vectorscope, init)

const uint VECTORSCOPE_SIZE = 96u;
const uint VECTORSCOPE_CHANNEL_COUNT = 4u;

void hook() {
    uvec2 position = gl_GlobalInvocationID.xy;
    if (any(greaterThanEqual(position, uvec2(VECTORSCOPE_SIZE)))) {
        return;
    }

    uint index = position.y * VECTORSCOPE_SIZE + position.x;
    uint base = index * VECTORSCOPE_CHANNEL_COUNT;
    // Interleave count and RGB sums so clearing, scattering, and reading one
    // bin touch adjacent addresses instead of four distant cache lines.
    preview_vectorscope_bins[base + 0u] = 0u;
    preview_vectorscope_bins[base + 1u] = 0u;
    preview_vectorscope_bins[base + 2u] = 0u;
    preview_vectorscope_bins[base + 3u] = 0u;
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUTS
//!BIND PREVIEW_VECTORSCOPE
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
const uint VECTORSCOPE_SIZE = 96u;
const uint VECTORSCOPE_CHANNEL_COUNT = 4u;
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

float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

vec2 sanitize_bounded(vec2 value, float lower_bound, float upper_bound) {
    return vec2(
        sanitize_bounded(value.x, lower_bound, upper_bound),
        sanitize_bounded(value.y, lower_bound, upper_bound)
    );
}

vec3 sanitize_bounded(vec3 value, float lower_bound, float upper_bound) {
    return vec3(
        sanitize_bounded(value.r, lower_bound, upper_bound),
        sanitize_bounded(value.g, lower_bound, upper_bound),
        sanitize_bounded(value.b, lower_bound, upper_bound)
    );
}

vec3 sanitize_vectorscope_rgb(
    vec3 rgb,
    out vec3 positive_rgb
) {
    float safe_reference_white = sanitize_bounded(reference_white, 0.0, pw);
    // Reject NaN and negatives first. Out-of-range input (effective
    // luminance above the PQ mastering range, only reachable with non-PQ
    // transfer formats) is clamped proportionally so the vectorscope hue
    // is preserved; a per-channel clamp collapses the largest component
    // and shifts the trace hue.
    //
    // With a degenerate reference_white the positive form still reflects
    // the content peak.
    positive_rgb = sanitize_bounded(rgb, 0.0, pw);
    float peak = max(max(positive_rgb.r, positive_rgb.g), positive_rgb.b);
    float limit = pw / max(safe_reference_white, 1e-6);
    if (peak > limit) {
        positive_rgb *= limit / peak;
    }
    return positive_rgb * safe_reference_white;
}

vec3 fetch_atlas_raw(ivec2 position) {
    return texelFetch(LUTS_raw, position, 0).rgb;
}

vec3 fetch_vectorscope_lut(ivec3 texel) {
    ivec2 atlas_position = ivec2(
        texel.x + texel.y * VECTORSCOPE_LUT_SIZE,
        VECTORSCOPE_RGB_TO_LAB_ROW + texel.z
    );
    return fetch_atlas_raw(atlas_position);
}

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

vec3 sample_vectorscope_rgb_to_jab(vec3 absolute_rgb) {
    vec3 position = pq_eotf_inv(absolute_rgb) *
                    float(VECTORSCOPE_LUT_LAST);
    ivec3 base_texel = ivec3(floor(position));
    vec3 fraction = fract(position);

    ivec3 second_offset;
    ivec3 third_offset;
    vec3 weights;
    select_tetrahedron(
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
        uvec2(sanitize_bounded(coordinate, 0.0, 1.0) *
              float(VECTORSCOPE_SIZE)),
        uvec2(VECTORSCOPE_SIZE - 1u)
    );
    return bin.y * VECTORSCOPE_SIZE + bin.x;
}

void hook() {
    vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
    vec3 positive_rgb;
    vec3 absolute_rgb = sanitize_vectorscope_rgb(
        rgb,
        positive_rgb
    );
    vec3 jab = sample_vectorscope_rgb_to_jab(absolute_rgb);
    uint index = vectorscope_bin(jab.yz);
    uint base = index * VECTORSCOPE_CHANNEL_COUNT;
    float encoding_peak = max(
        max(max(positive_rgb.r, positive_rgb.g), positive_rgb.b),
        1.0
    );
    uvec3 encoded_rgb = uvec3(
        positive_rgb / encoding_peak * VECTORSCOPE_COLOR_SCALE + 0.5
    );

    // The 256x144 sampling grid bounds each 16-bit channel sum below uint
    // overflow, even if every sample lands in the same vectorscope bin.
    atomicAdd(preview_vectorscope_bins[base + 0u], 1u);
    atomicAdd(preview_vectorscope_bins[base + 1u], encoded_rgb.r);
    atomicAdd(preview_vectorscope_bins[base + 2u], encoded_rgb.g);
    atomicAdd(preview_vectorscope_bins[base + 3u], encoded_rgb.b);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUTS
//!BIND CHROMA_BOUNDARY
//!BIND METADATA
//!BIND CURVE_TEMPORAL
//!DESC tone mapping (astra)

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT with its output-cube Jhk range stored in rows, and one
// 1024-point neutral-J curve row.
const int FORWARD_LUT_SIZE = 65;
const int FORWARD_LUT_LAST = FORWARD_LUT_SIZE - 1;
const int REVERSE_LIGHTNESS_LUT_SIZE = 129;
const int REVERSE_LIGHTNESS_LUT_LAST = REVERSE_LIGHTNESS_LUT_SIZE - 1;
const int REVERSE_CHROMA_LUT_SIZE = FORWARD_LUT_SIZE;
const int REVERSE_CHROMA_LUT_LAST = REVERSE_CHROMA_LUT_SIZE - 1;
const int RGB_TO_LAB_ROW = 0;
const int LAB_TO_RGB_ROW = RGB_TO_LAB_ROW + FORWARD_LUT_SIZE;
const int CURVE_ROW = LAB_TO_RGB_ROW + REVERSE_LIGHTNESS_LUT_SIZE;
const int CURVE_SIZE = 1024;
const int RGB_TO_LAB_LUT = 0;
const int LAB_TO_RGB_LUT = 1;

const int BOUNDARY_HUE_INTERVALS = 360;
const int BOUNDARY_LIGHTNESS_INTERVALS = REVERSE_LIGHTNESS_LUT_LAST;

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

float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

vec3 sanitize_bounded(vec3 value, float lower_bound, float upper_bound) {
    return vec3(
        sanitize_bounded(value.r, lower_bound, upper_bound),
        sanitize_bounded(value.g, lower_bound, upper_bound),
        sanitize_bounded(value.b, lower_bound, upper_bound)
    );
}

vec3 sanitize_absolute_rgb(vec3 rgb) {
    float safe_reference_white = sanitize_bounded(
        reference_white,
        0.0,
        pw
    );
    vec3 positive_rgb = sanitize_bounded(rgb, 0.0, pw);
    // Reject NaN and negatives first, then clamp out-of-range input
    // (effective luminance above the PQ mastering range, only reachable
    // with non-PQ transfer formats) proportionally: a per-channel clamp
    // collapses the largest component and shifts the hue, disagreeing with
    // the vectorscope's hue-preserving form of the same policy.
    float peak = max(max(positive_rgb.r, positive_rgb.g), positive_rgb.b);
    float limit = pw / max(safe_reference_white, 1e-6);
    if (peak > limit) {
        positive_rgb *= limit / peak;
    }
    return positive_rgb * safe_reference_white;
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
    vec3 position = sanitize_bounded(lut_coordinates, 0.0, 1.0) *
                    vec3(last_texel);
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
    float range = max(output_reverse_max_jhk - output_min_j, 1e-6);
    return clamp((lightness - output_min_j) / range, 0.0, 1.0);
}

vec3 RGB_to_lut_coordinates(vec3 rgb) {
    return pq_eotf_inv(sanitize_absolute_rgb(rgb));
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

float sample_tone_curve_lut(float x) {
    float position = sanitize_bounded(x, 0.0, 1.0) *
                     float(CURVE_SIZE - 1);
    // Stay on the curve row's texel centers; the sampler interpolates only X.
    vec2 coordinates = (vec2(position, float(CURVE_ROW)) + 0.5) / LUTS_size;
    return LUTS_mul * textureLod(LUTS_raw, coordinates, 0.0).x;
}

float sample_tone_curve_slope(float x) {
    float position = sanitize_bounded(x, 0.0, 1.0) *
                     float(CURVE_SIZE - 1);
    int lower_index = min(int(floor(position)), CURVE_SIZE - 2);
    int upper_index = lower_index + 1;
    float weight = position - float(lower_index);

    // Use the full-precision curve actually displayed this frame. On a
    // protected frame the LUT is generated from the target while the
    // recoverable history stays at the previous shot, so differencing the
    // history would weight chroma for a curve that is not displayed.
    float lower_value = curve_display_values[lower_index];
    float upper_value = curve_display_values[upper_index];
    float previous_value = lower_index > 0
        ? curve_display_values[lower_index - 1]
        : 2.0 * lower_value - upper_value;
    float next_value = upper_index < CURVE_SIZE - 1
        ? curve_display_values[upper_index + 1]
        : 2.0 * upper_value - lower_value;

    // Derivative of a cubic B-spline reconstruction: blend three secant
    // slopes with nonnegative quadratic weights. The slope estimate is
    // C1 across LUT cells, without changing the linear lightness lookup.
    // Linear endpoint extrapolation preserves a constant curve slope.
    float left_weight = 0.5 * (1.0 - weight) * (1.0 - weight);
    float right_weight = 0.5 * weight * weight;
    float middle_weight = 1.0 - left_weight - right_weight;
    return max(0.0, float(CURVE_SIZE - 1) * (
        left_weight * (lower_value - previous_value) +
        middle_weight * (upper_value - lower_value) +
        right_weight * (next_value - upper_value)
    ));
}

float chroma_correction_attenuation(float x, float rate, float threshold) {
    // Preserve the identity endpoint; threshold == 1 remains a hard switch.
    if (x >= 1.0) {
        return 1.0;
    }
    if (threshold >= 1.0) {
        return 0.0;
    }

    float norm = clamp(
        (x - threshold) / (1.0 - threshold),
        0.0,
        1.0
    );
    // Flat endpoint slopes smooth both the cutoff and the l1 == l2 crossing.
    // Rate accelerates fading; rate == 0 retains the smoothstep baseline.
    float smooth_ratio = norm * norm * (3.0 - 2.0 * norm);
    return pow(smooth_ratio, 1.0 + rate);
}

float chroma_correction_contrast(float bias, float lightness) {
    if (bias == 0.0) {
        return 1.0;
    }

    // Keep the full middle-segment gain; relax toward unity as toe/shoulder
    // compression flattens the curve. This weights the contrast adjustment,
    // not the chroma itself, so a flat segment does not force extra fading.
    float middle_slope = exp2(bias);
    float local_slope = sample_tone_curve_slope(lightness);
    float weight = smoothstep(0.0, 1.0, local_slope / middle_slope);
    return exp2(bias * weight);
}

bool chroma_correction_is_valid(vec2 ab, float lightness, vec3 rgb) {
    // Reject coordinates that would be silently clipped by the LUT shapers.
    // Otherwise independent a/b clipping could change the hue of the result.
    vec2 ratio_limit = vec2(A_RATIO_LIMIT, B_RATIO_LIMIT) *
                       max(lightness, 1e-6);
    if (any(greaterThan(abs(ab), ratio_limit))) {
        return false;
    }

    // Reuse the final conversion, including H-K and LUT interpolation.
    // Display peak limiting remains the responsibility of the next stage.
    return !any(isnan(rgb)) && !any(isinf(rgb)) &&
           all(greaterThanEqual(rgb, vec3(0.0)));
}

float sample_chroma_boundary(vec2 ab, float lightness) {
    float hue = fract(atan(ab.y, ab.x) / 6.283185307179586 + 1.0);
    ivec2 last_texel = ivec2(BOUNDARY_HUE_INTERVALS, BOUNDARY_LIGHTNESS_INTERVALS);
    vec2 position = vec2(hue, encode_output_lightness(lightness)) * vec2(last_texel);
    // The duplicated hue-zero column preserves interpolation across the seam.
    vec2 coordinates = (position + 0.5) / CHROMA_BOUNDARY_size;
    float ratio = CHROMA_BOUNDARY_mul *
                  textureLod(CHROMA_BOUNDARY_raw, coordinates, 0.0).x;
    // Match LAB_to_lut_coordinates even if the Jhk lookup clamps at an end.
    return max(ratio, 0.0) * max(lightness, 1e-6);
}

vec2 chroma_correction_gamut(vec2 ab, float lightness) {
    if (lightness <= 0.0) {
        return vec2(0.0);
    }
    if (all(equal(ab, vec2(0.0)))) {
        return ab;
    }

    // Preserve the same 80% rational soft knee, now using the precomputed
    // C/Jhk boundary rather than searching the inverse LUT for every pixel.
    const float knee = 0.8;
    float chroma = length(ab);
    float limit = sample_chroma_boundary(ab, lightness);
    float knee_chroma = knee * limit;
    if (chroma <= knee_chroma) {
        return ab;
    }
    float span = limit - knee_chroma;
    float excess = chroma - knee_chroma;
    float mapped_chroma = knee_chroma + span * excess / (span + excess);
    return ab * (mapped_chroma / chroma);
}

vec3 ensure_valid_rgb(vec3 lab, vec3 rgb) {
    if (chroma_correction_is_valid(lab.yz, lab.x, rgb)) {
        return rgb;
    }

    // Only interpolation errors or nonconvex slices reach this fallback.
    // Keep the RGB of each proven-valid sample; do not repeat the conversion
    // after searching. The normal path has no inverse-LUT probes or loops.
    vec3 neutral = LAB_to_RGB(vec3(lab.x, 0.0, 0.0));
    vec3 valid_rgb = chroma_correction_is_valid(vec2(0.0), lab.x, neutral)
        ? neutral : vec3(0.0);
    float lower_scale = 0.0;
    float upper_scale = 1.0;
    for (int iteration = 0; iteration < 12; iteration++) {
        float middle_scale = 0.5 * (lower_scale + upper_scale);
        vec2 candidate_ab = lab.yz * middle_scale;
        vec3 candidate_rgb = LAB_to_RGB(vec3(lab.x, candidate_ab));
        if (chroma_correction_is_valid(candidate_ab, lab.x, candidate_rgb)) {
            lower_scale = middle_scale;
            valid_rgb = candidate_rgb;
        } else {
            upper_scale = middle_scale;
        }
    }
    return valid_rgb;
}

// Based on the chroma correction method for ICtCp in BT.2390/BT.2408
// [BT.2408: Guidance for operational practices in HDR television production](https://www.itu.int/pub/R-REP-BT.2408)
//
// a power factor is added to increase correction rate.
//
// this is a correction in generic vividness and depth.
// V = sqrt(J^2 + C^2)
// D = sqrt((J_{max} - J)^2 + C^2)
//
// More specific definitions of V and D for Jzazbz,
// see the following links:
// [A Colour Appearance Model based on Jzazbz](https://doi.org/10.2352/ISSN.2169-2629.2018.26.96)
// [Colour Image Enhancement using Perceptual Saturation and Vividness](https://doi.org/10.2352/ISSN.2169-2629.2019.27.43)
vec2 chroma_correction(vec2 ab, float l1, float l2) {
    float ratio_min = min(l1, l2) / max(max(l1, l2), 1e-6);
    float ratio_scaled = mix(1.0, ratio_min, chroma_correction_scaling);
    float ratio_safe = max(ratio_scaled, 0.0);
    float contrast_gain = chroma_correction_contrast(contrast_bias, l1);
    float attenuation = chroma_correction_attenuation(
        ratio_safe,
        chroma_correction_rate,
        chroma_correction_threshold
    );
    return chroma_correction_gamut(ab * contrast_gain * attenuation, l2);
}

vec3 tone_mapping(vec3 lab) {
    float l2 = sample_tone_curve_lut(lab.x);
    vec2 ab2 = chroma_correction(lab.yz, lab.x, l2);
    return vec3(l2, ab2);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    color.rgb = apply_exposure(color.rgb);
    color.rgb = RGB_to_LAB(color.rgb);
    color.rgb = tone_mapping(color.rgb);
    vec3 lab = color.rgb;
    color.rgb = LAB_to_RGB(color.rgb);
    color.rgb = ensure_valid_rgb(lab, color.rgb);
    return color;
}


//!HOOK OUTPUT
//!BIND METERED
//!BIND METADATA
//!BIND LUTS
//!BIND PREVIEW_HISTOGRAM
//!BIND PREVIEW_METRICS
//!SAVE EMPTY
//!WIDTH 256
//!HEIGHT 1
//!COMPUTE 256 1 256 1
//!WHEN preview_metering
//!DESC metering (preview, histogram and metrics preparation)

// Histogram heights are constant across each four-pixel bin, while the tone
// curve varies once per plot column. Compute both here instead of repeating
// histogram integration and colour-space conversion along all 256 rows.
// Prepare metric glyphs and row widths in the same dispatch; synchronize the
// width writes before reducing the panel width. The preview drawing pass
// reads these prepared buffers instead of formatting values per pixel.

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

// These inverse transfer primitives assume finite, non-negative input: a
// negative or NaN argument reaches pow() and produces NaN. They deliberately
// impose no upper bound; values above pw encode above 1.0, and callers that
// require normalized PQ must clamp the result. Every call site in this pass
// feeds clamped or eotf-derived non-negative values. A new call site must
// preserve that lower-bound contract.
float pq_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

float iz_eotf_inv(float x) {
    float t = pow(x / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2_z);
}

float iz_eotf(float x) {
    float t = pow(x, 1.0 / m2_z);
    return pow(max(t - c1, 0.0) / (c2 - c3 * t), 1.0 / m1) * pw;
}

float I_to_J(float I) {
    return ((1.0 + d) * I) / (1.0 + (d * I)) - d0;
}

float J_to_I(float J) {
    return (J + d0) / (1.0 + d - d * (J + d0));
}

// The 1D LUT is stored in Astra's J domain. For a neutral stimulus the H-K
// compensation is zero, so J ↔ I_z ↔ absolute luminance gives its PQ-domain
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
    if (absolute_exposed <= 0.0) {
        return 0.0;
    }

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

float histogram_bin_overlap(vec2 interval, uint index) {
    float bin_start = float(index);
    return max(
        min(interval.y, bin_start + 1.0) - max(interval.x, bin_start),
        0.0
    );
}

// Ordered-comparison sanitizer; see the metering intensity pass.
float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

uvec2 histogram_interval_bounds(vec2 interval, uint bin_count) {
    // Reject NaN before the float-to-uint conversion per the file-wide
    // sanitizer contract: a NaN here is undefined in the uint domain.
    interval = vec2(
        sanitize_bounded(interval.x, 0.0, float(bin_count)),
        sanitize_bounded(interval.y, 0.0, float(bin_count))
    );
    return uvec2(
        min(uint(floor(interval.x)), bin_count - 1u),
        min(uint(ceil(interval.y)), bin_count)
    );
}

float preview_histogram_count(vec2 source_interval) {
    vec2 interval = source_interval * float(PREVIEW_HISTOGRAM_RAW_SIZE);
    uvec2 bounds = histogram_interval_bounds(
        interval,
        PREVIEW_HISTOGRAM_RAW_SIZE
    );
    float count = 0.0;

    for (uint i = bounds.x; i < bounds.y; i++) {
        float overlap = histogram_bin_overlap(interval, i);
        count += float(metered_histogram[i]) * overlap;
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
    preview_histogram_current[index] = preview_histogram_height(
        current_count,
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

const float CHAR_W = 3.0;
const float SPACING = 1.0;

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
const int CH_H = 72;
const int CH_I = 73;
const int CH_M = 77;
const int CH_N = 78;
const int CH_S = 83;
const int CH_T = 84;
const int CH_V = 86;
const int CH_X = 88;
const int CH_Z = 90;

uint get_glyph(int ch) {
    if (ch >= CH_0 && ch <= CH_9) {
        return FONT_DIGITS[ch - CH_0];
    }
    if (ch >= CH_A && ch <= CH_Z) {
        return FONT_LETTERS[ch - CH_A];
    }

    if (ch == CH_SPACE) return FONT_SPACE;
    if (ch == CH_MINUS) return FONT_MINUS;
    if (ch == CH_DOT)   return FONT_DOT;
    if (ch == CH_COLON) return FONT_COLON;
    return FONT_TOFU;
}


uint integer_digit_count(uint int_part) {
    uint digits = 1u;
    if      (int_part >= 10000u) digits = 5u;
    else if (int_part >= 1000u)  digits = 4u;
    else if (int_part >= 100u)   digits = 3u;
    else if (int_part >= 10u)    digits = 2u;
    return digits;
}

const uint NUMBER_FIXED_MAX = 9999999u;

uint number_fixed_value(float value) {
    float magnitude = abs(value);
    // Convert to hundredths with saturation; map NaN to zero.
    float scaled = magnitude >= 0.0
        ? min(magnitude * 100.0 + 0.5, float(NUMBER_FIXED_MAX))
        : 0.0;
    return uint(scaled);
}

// Number format: a three-character label, colon, optional sign,
// up to five integer digits, and two decimal places.
const float LABEL_CHARACTERS = 4.0;
const float NUMBER_SIGN_CHARACTERS = 1.0;
const float NUMBER_DECIMAL_CHARACTERS = 3.0;

float number_advance(float characters) {
    return characters * (CHAR_W + SPACING);
}

float number_width(float value) {
    uint int_part = number_fixed_value(value) / 100u;
    uint digits = integer_digit_count(int_part);

    float characters = float(digits) + NUMBER_DECIMAL_CHARACTERS +
                       (value < 0.0 ? NUMBER_SIGN_CHARACTERS : 0.0);
    return number_advance(characters);
}

uint decimal_divisor(uint position_from_right) {
    if (position_from_right == 4u) return 10000u;
    if (position_from_right == 3u) return 1000u;
    if (position_from_right == 2u) return 100u;
    if (position_from_right == 1u) return 10u;
    return 1u;
}

// Resolve one numeric character.
int number_character(float value, int index) {
    bool negative = value < 0.0;
    uint fixed_value = number_fixed_value(value);
    uint int_part = fixed_value / 100u;
    uint dec_part = fixed_value - int_part * 100u;
    uint digits = integer_digit_count(int_part);

    if (negative) {
        if (index == 0) {
            return CH_MINUS;
        }
        index--;
    }

    if (index < int(digits)) {
        uint position_from_right = digits - 1u - uint(index);
        uint digit = (int_part / decimal_divisor(position_from_right)) % 10u;
        return CH_0 + int(digit);
    }

    index -= int(digits);
    if (index == 0) return CH_DOT;
    if (index == 1) return CH_0 + int(dec_part / 10u);
    if (index == 2) return CH_0 + int(dec_part % 10u);
    return CH_SPACE;
}

// Metrics table entry: label, value source, and minimum metering level.
struct MetricsRow {
    ivec3 label;
    int value;
    int metering;
};

// Minimum enable_metering level required to display a row.
const int METERING_NONE = 0;
const int METERING_MINMAX = 1;
const int METERING_FULL = 2;

// Value-source tags resolved by metrics_row_value().
const int VAL_INPUT_MAX = 0;
const int VAL_INPUT_MIN = 1;
const int VAL_INPUT_AVG = 2;
const int VAL_HISTOGRAM_AVG = 3;
const int VAL_MATRIX_AVG = 4;
const int VAL_MATRIX_MIX = 5;
const int VAL_EXPOSURE_EV = 6;
const int VAL_EXPOSED_MAX = 7;

// Table order is panel order.
const MetricsRow METRICS_ROWS[] = MetricsRow[](
    MetricsRow(ivec3(CH_M, CH_A, CH_X), VAL_INPUT_MAX, METERING_NONE),
    MetricsRow(ivec3(CH_M, CH_I, CH_N), VAL_INPUT_MIN, METERING_NONE),
    MetricsRow(ivec3(CH_A, CH_V, CH_G), VAL_INPUT_AVG, METERING_NONE),
    MetricsRow(ivec3(CH_H, CH_S, CH_T), VAL_HISTOGRAM_AVG, METERING_FULL),
    MetricsRow(ivec3(CH_M, CH_A, CH_T), VAL_MATRIX_AVG, METERING_FULL),
    MetricsRow(ivec3(CH_M, CH_I, CH_X), VAL_MATRIX_MIX, METERING_FULL),
    MetricsRow(ivec3(CH_E, CH_V, CH_SPACE), VAL_EXPOSURE_EV, METERING_NONE),
    MetricsRow(ivec3(CH_E, CH_M, CH_X), VAL_EXPOSED_MAX, METERING_NONE)
);

const int METRICS_ROW_SLOTS = METRICS_ROWS.length();

// Resolve a value in display units: nits, EV, or matrix blend fraction.
float metrics_row_value(int value) {
    if (value == VAL_INPUT_MAX) return pq_eotf(input_max_i);
    if (value == VAL_INPUT_MIN) return pq_eotf(input_min_i);
    if (value == VAL_INPUT_AVG) return pq_eotf(input_avg_i);
    if (value == VAL_HISTOGRAM_AVG) return pq_eotf(metered_histogram_average);
    if (value == VAL_MATRIX_AVG) return pq_eotf(metered_matrix_average);
    if (value == VAL_MATRIX_MIX) return metered_matrix_blend;
    if (value == VAL_EXPOSURE_EV) return exposure_ev;
    if (value == VAL_EXPOSED_MAX) return pq_eotf(exposed_max_i);
    return 0.0;
}

bool metrics_row_visible(int slot, int metering) {
    return metering >= METRICS_ROWS[slot].metering;
}

// Count visible entries in table order.
int metrics_row_count(int metering) {
    int count = 0;
    for (int slot = 0; slot < METRICS_ROW_SLOTS; slot++) {
        if (metrics_row_visible(slot, metering)) {
            count++;
        }
    }
    return count;
}

// Map a valid visible-row position to its table slot.
int metrics_row_slot(int position, int metering) {
    int visible = 0;
    for (int slot = 0; slot < METRICS_ROW_SLOTS; slot++) {
        if (!metrics_row_visible(slot, metering)) {
            continue;
        }
        if (visible == position) {
            return slot;
        }
        visible++;
    }
    return 0;
}

const uint PREVIEW_METRICS_CHARACTERS = 13u;

void prepare_preview_metrics(uint index) {
    int metering = int(enable_metering);
    int count = metrics_row_count(metering);
    if (index < uint(METRICS_ROW_SLOTS) * PREVIEW_METRICS_CHARACTERS) {
        uint row = index / PREVIEW_METRICS_CHARACTERS;
        uint character = index % PREVIEW_METRICS_CHARACTERS;
        uint glyph = FONT_SPACE;
        float width = 0.0;
        if (int(row) < count) {
            MetricsRow entry = METRICS_ROWS[metrics_row_slot(int(row), metering)];
            float value = metrics_row_value(entry.value);
            width = number_advance(LABEL_CHARACTERS) + number_width(value);
            int ch;
            if (character < 3u) {
                ch = entry.label[int(character)];
            } else if (character == 3u) {
                ch = CH_COLON;
            } else {
                ch = number_character(value, int(character) - 4);
            }
            glyph = get_glyph(ch);
        }
        preview_metrics_glyphs[index] = glyph;
        if (character == 0u) {
            preview_metrics_widths[row] = width;
        }
    }
    memoryBarrierBuffer();
    barrier();
    if (index == 0u) {
        preview_metrics_count = uint(count);
        float width = 0.0;
        for (int row = 0; row < count; row++) {
            width = max(width, preview_metrics_widths[row]);
        }
        preview_metrics_max_width = width;
    }
}

void hook() {
    uint index = gl_GlobalInvocationID.x;
    if (index < PREVIEW_HISTOGRAM_SIZE) {
        prepare_preview_histogram_bin(index);
    }
    if (index < PREVIEW_HISTOGRAM_COLUMN_COUNT) {
        prepare_preview_curve_column(index);
    }
    prepare_preview_metrics(index);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND METERING
//!BIND METERED
//!BIND METADATA
//!BIND SCENE_RESPONSE
//!BIND PREVIEW_VECTORSCOPE
//!BIND PREVIEW_HISTOGRAM
//!BIND PREVIEW_METRICS
//!WHEN preview_metering
//!DESC metering (preview)

// One just-noticeable difference step on the PQ scale.
const float JND = 1.0 / 720.0;

// Quantization scale for PQ12 intensity statistics.
const float METERING_CODE_SCALE = 4095.0;

float to_float(uint x) {
    return float(x) / METERING_CODE_SCALE;
}

// 3x5 bitmap font rendering
const float CHAR_W = 3.0;
const float CHAR_H = 5.0;
const float SPACING = 1.0;
const float MARGIN = 8.0;
const float PAD = 2.0;
const float SCALE = 4.0;
const float LINE_H = CHAR_H + 2.0;
const float GAP = 6.0 * SCALE;

// Cyan bars show the current frame in 64 bins. The yellow curve is the
// J-domain LUT transformed onto the same PQ horizontal and vertical axes.
const uint PREVIEW_HISTOGRAM_SIZE = 64u;
const float PREVIEW_HISTOGRAM_BIN_WIDTH = 4.0;
const float PREVIEW_HISTOGRAM_EXTENT = 256.0;

// A confirmed shot cut marks the histogram's upper-left corner.
const float PREVIEW_CUT_MARKER_SIZE = 4.0 * SCALE;
const float PREVIEW_CUT_MARKER_INSET = SCALE;

// Normalize density to a fixed 128x128 reference grid.
const uint PREVIEW_VECTORSCOPE_SIZE = 96u;
const uint PREVIEW_VECTORSCOPE_CHANNEL_COUNT = 4u;
const float PREVIEW_VECTORSCOPE_DENSITY_REFERENCE_SIZE = 128.0;
const float PREVIEW_VECTORSCOPE_EXTENT = 256.0;
const float PREVIEW_VECTORSCOPE_AB_RANGE = 0.36;
const float PREVIEW_VECTORSCOPE_COLOR_SCALE = 65535.0;

const uvec2 PREVIEW_MATRIX_SIZE = uvec2(16u, 9u);
const float PREVIEW_MATRIX_DIFFERENCE_RANGE = 144 * JND;
const float PREVIEW_ZONE_WEIGHT_MAX = 4.0;

// The panel overlays, in the order render_metering_preview composites them.
vec4 draw_highlights(float value) {
    vec3 metrics = vec3(
        to_float(metered_max_i),
        to_float(metered_avg_i),
        to_float(metered_min_i)
    );
    // Mark values beyond the percentile bounds with a 5 * JND PQ tolerance.
    vec3 matches = vec3(
        step(metrics.x - 5.0 * JND, value),
        1.0 - step(5.0 * JND, abs(metrics.y - value)),
        step(value, metrics.z + 5.0 * JND)
    );

    if (!(enable_metering > 1)) {
        matches.y = 0.0;
    }

    float opacity = 0.75 * max(max(matches.x, matches.y), matches.z);
    return vec4(matches, opacity);
}

// Overlay the actual matrix inputs and weights on their source regions. Blue
// zones pull the matrix estimate below the histogram average, orange zones
// pull it above. Active zones tint a small centered rectangle outline, so
// the video stays visible and neighboring frames never merge.
// Draw zones excluded by the border heuristic.
vec4 draw_excluded_matrix_zone(vec2 position, vec2 size, vec2 edge_distance) {
    if (min(edge_distance.x, edge_distance.y) < 1.0) {
        return vec4(vec3(0.82), 0.55);
    }

    vec2 oriented_px = position * size;
    float phase = fract((oriented_px.x + oriented_px.y) / 16.0);
    float aa = 1.0 / 11.3137085; // 1 px perpendicular to the stripe
    float stripe = smoothstep(0.5 - aa, 0.5 + aa, phase) *
                   (1.0 - smoothstep(1.0 - aa, 1.0, phase));
    return vec4(mix(vec3(0.04), vec3(1.0), stripe), 0.80);
}

// Striped cells are excluded by the border heuristic.
vec4 draw_matrix_metering(vec2 position) {
    if (metered_zones_valid == 0u) {
        return vec4(0.0);
    }

    vec2 matrix_size = vec2(PREVIEW_MATRIX_SIZE);
    vec2 clamped_position = clamp(
        position,
        vec2(0.0),
        vec2(1.0 - 1e-6)
    );
    uvec2 zone = min(
        uvec2(clamped_position * matrix_size),
        PREVIEW_MATRIX_SIZE - uvec2(1u)
    );
    uint index = zone.y * PREVIEW_MATRIX_SIZE.x + zone.x;

    vec2 oriented_size = HOOKED_size.y > HOOKED_size.x
        ? HOOKED_size.yx
        : HOOKED_size;
    vec2 cell_position = fract(clamped_position * matrix_size);
    vec2 cell_size = oriented_size / matrix_size;
    vec2 edge_distance = min(cell_position, 1.0 - cell_position) *
                         cell_size;

    // The statistics pass publishes the resolved zone weight separately from
    // the spread used by active-region and reliability calculations.
    float zone_weight = metered_zone_preview_weight[index];
    float relative_weight = clamp(
        zone_weight / PREVIEW_ZONE_WEIGHT_MAX,
        0.0,
        1.0
    );
    float blend_visibility = mix(
        0.90,
        1.0,
        metered_matrix_blend
    );

    if (zone_weight <= 0.0) {
        return draw_excluded_matrix_zone(
            clamped_position,
            oriented_size,
            edge_distance
        );
    }

    // Draw a centered outline at 30% of the cell size; clip each side
    // to the rectangle span so neighboring outlines remain separate.
    vec2 frame_half_extent = 0.15 * cell_size;
    vec2 center_offset = abs(cell_position - 0.5) * cell_size;
    vec2 perimeter_distance = abs(center_offset - frame_half_extent);
    bool on_vertical_side = perimeter_distance.x < 1.0 &&
                            center_offset.y <= frame_half_extent.y;
    bool on_horizontal_side = perimeter_distance.y < 1.0 &&
                              center_offset.x <= frame_half_extent.x;
    if (!on_vertical_side && !on_horizontal_side) {
        return vec4(0.0);
    }

    float zone_average = metered_zone_average[index];
    float signed_difference = clamp(
        (zone_average - metered_histogram_average) /
        PREVIEW_MATRIX_DIFFERENCE_RANGE,
        -1.0,
        1.0
    );
    vec3 neutral = vec3(0.24);
    vec3 low = vec3(0.05, 0.30, 1.0);
    vec3 high = vec3(1.0, 0.28, 0.04);
    vec3 tint = mix(
        neutral,
        signed_difference < 0.0 ? low : high,
        abs(signed_difference)
    );
    float opacity = mix(0.40, 0.72, relative_weight) *
                    blend_visibility;
    return vec4(tint, opacity);
}

bool outside_panel_bounds(vec2 position, vec2 lower_bound, vec2 upper_bound) {
    return any(lessThan(position, lower_bound)) ||
           any(greaterThan(position, upper_bound));
}

// Return false with transparent color outside the padded bounds,
// or opaque black in the padding. Return true inside the panel.
bool preview_panel_point(
    vec2 px,
    vec2 origin,
    float extent,
    out vec2 local,
    out vec4 colour
) {
    vec2 padding = vec2(PAD * SCALE);
    local = px - origin;

    if (outside_panel_bounds(
        px,
        origin - padding,
        origin + vec2(extent) + padding
    )) {
        colour = vec4(0.0);
        return false;
    }

    if (any(lessThan(local, vec2(0.0))) ||
        any(greaterThanEqual(local, vec2(extent)))) {
        colour = vec4(0.0, 0.0, 0.0, 1.0);
        return false;
    }

    return true;
}

// Paint the histogram's upper-left corner while the current frame is a
// confirmed shot cut.
bool preview_cut_marker(vec2 local) {
    return scene_hard_cut > 0u &&
           all(greaterThanEqual(
               local,
               vec2(PREVIEW_CUT_MARKER_INSET)
           )) &&
           all(lessThan(
               local,
               vec2(PREVIEW_CUT_MARKER_INSET + PREVIEW_CUT_MARKER_SIZE)
           ));
}

vec4 draw_histogram(vec2 px) {
    vec2 origin = vec2(MARGIN * SCALE);
    vec2 local;
    vec4 colour;

    if (!preview_panel_point(
        px,
        origin,
        PREVIEW_HISTOGRAM_EXTENT,
        local,
        colour
    )) {
        return colour;
    }

    uint index = min(
        uint(local.x / PREVIEW_HISTOGRAM_BIN_WIDTH),
        PREVIEW_HISTOGRAM_SIZE - 1u
    );
    uint column = min(
        uint(floor(local.x)),
        uint(PREVIEW_HISTOGRAM_EXTENT) - 1u
    );
    float current_height = preview_histogram_current[index];
    float plot_width = PREVIEW_HISTOGRAM_EXTENT - 2.0;
    float plot_height = PREVIEW_HISTOGRAM_EXTENT - 2.0;
    float pq_input = clamp((local.x - 1.0) / plot_width, 0.0, 1.0);
    float pq_output = preview_histogram_curve[column];
    float level = 1.0 - clamp((local.y - 1.0) / plot_height, 0.0, 1.0);

    vec3 tint = vec3(0.0);

    float grid_distance = abs(fract(level * 4.0 + 0.5) - 0.5);
    if (grid_distance < 0.012) {
        tint = vec3(0.10);
    }

    if (level <= current_height) {
        tint = vec3(0.12, 0.72, 0.92);
    }

    if (abs(level - pq_input) <= 0.75 / plot_height) {
        tint = vec3(0.48);
    }

    if (abs(level - pq_output) <= 1.5 / plot_height) {
        tint = vec3(1.0, 0.78, 0.12);
    }

    if (preview_cut_marker(local)) {
        tint = vec3(1.0, 0.0, 0.0);
    }

    return vec4(tint, 1.0);
}

// ITU-R BT.2525-0 HLG reference for Fitzpatrick skin types 1-4. Saturation
// is C / C_{max}, where C_{max} is the largest Jzazbz chroma of the Rec. 2020
// primaries at the 1000-nit HLG nominal peak. The report's H-K-independent
// a/b coordinates match this vectorscope even when J compensation is active.
// [BT.2525: A method of skin tone analysis for programme production](https://www.itu.int/pub/R-REP-BT.2525)
const vec2 BT2525_SKIN_HUE_RANGE = vec2(35.4, 70.6);
const vec2 BT2525_SKIN_SATURATION_RANGE = vec2(0.085, 0.281);
const float BT2525_HLG_MAX_PRIMARY_CHROMA = 0.34074623;

float cross_2d(vec2 a, vec2 b) {
    return a.x * b.y - a.y * b.x;
}

float distance_to_ray(vec2 point, vec2 direction) {
    if (dot(point, direction) < 0.0) {
        return 1e6;
    }
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

vec4 draw_vectorscope(vec2 px) {
    vec2 origin = vec2(
        MARGIN * SCALE,
        MARGIN * SCALE + PREVIEW_HISTOGRAM_EXTENT + GAP
    );
    vec2 local;
    vec4 colour;

    if (!preview_panel_point(
        px,
        origin,
        PREVIEW_VECTORSCOPE_EXTENT,
        local,
        colour
    )) {
        return colour;
    }

    vec2 unit = (local + 0.5) / PREVIEW_VECTORSCOPE_EXTENT;
    uvec2 bin = min(
        uvec2(unit * float(PREVIEW_VECTORSCOPE_SIZE)),
        uvec2(PREVIEW_VECTORSCOPE_SIZE - 1u)
    );
    uint index = bin.y * PREVIEW_VECTORSCOPE_SIZE + bin.x;
    uint base = index * PREVIEW_VECTORSCOPE_CHANNEL_COUNT;
    float count = float(preview_vectorscope_bins[base + 0u]);
    // The density reference is the same fixed 128-bin scale: the (grid / 128)^2
    // normalization keeps the rendered density at that appearance, so retuning
    // the grid changes resolution only, never trace brightness.
    float resolution_scale = float(PREVIEW_VECTORSCOPE_SIZE) /
                             PREVIEW_VECTORSCOPE_DENSITY_REFERENCE_SIZE;
    float normalized_count = count * resolution_scale * resolution_scale;
    float density = clamp(
        log2(1.0 + normalized_count) / 8.0,
        0.0,
        1.0
    );
    vec3 color_sum = vec3(
        preview_vectorscope_bins[base + 1u],
        preview_vectorscope_bins[base + 2u],
        preview_vectorscope_bins[base + 3u]
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
    if (axis_distance < line_width || ring_distance < line_width) {
        tint = vec3(0.10);
    }

    tint = max(tint, draw_skin_tone_reference(plane, line_width));
    tint = max(tint, trace_color * sqrt(density));
    return vec4(tint, 1.0);
}

bool glyph_pixel(uint glyph, vec2 p) {
    if (p.x < 0.0 || p.x >= CHAR_W || p.y < 0.0 || p.y >= CHAR_H) return false;
    uint bit = uint(p.y) * 3u + uint(p.x);
    return (glyph & (1u << bit)) != 0u;
}

const int PREVIEW_METRICS_CHARACTERS = 13;

vec4 draw_metrics_row(int row, vec2 origin, vec2 px) {
    vec2 local = (px - origin) / SCALE;
    if (local.x < 0.0 || local.x >= preview_metrics_widths[row] ||
        local.y < 0.0 || local.y >= CHAR_H) {
        return vec4(0.0);
    }

    float advance = CHAR_W + SPACING;
    int character = int(floor(local.x / advance));
    uint glyph = preview_metrics_glyphs[row * PREVIEW_METRICS_CHARACTERS + character];
    vec2 position = vec2(mod(local.x, advance), local.y);
    return glyph_pixel(glyph, position) ? vec4(1.0) : vec4(0.0);
}

vec4 draw_metrics_panel(vec2 px) {
    int row_count = int(preview_metrics_count);
    float metrics_bottom = HOOKED_size.y - MARGIN * SCALE - CHAR_H * SCALE;
    float metrics_top = metrics_bottom -
                        float(row_count - 1) * LINE_H * SCALE;
    float chart_stack_bottom = MARGIN * SCALE +
                               PREVIEW_HISTOGRAM_EXTENT +
                               GAP +
                               PREVIEW_VECTORSCOPE_EXTENT + PAD * SCALE;
    float metrics_x = metrics_top - PAD * SCALE < chart_stack_bottom
        ? MARGIN * SCALE + PREVIEW_VECTORSCOPE_EXTENT + GAP
        : MARGIN * SCALE;
    vec2 o0 = vec2(metrics_x, metrics_top);
    vec2 panel_min = o0 - vec2(PAD * SCALE);
    vec2 panel_max = vec2(
        o0.x + (preview_metrics_max_width + PAD) * SCALE,
        metrics_bottom + (CHAR_H + PAD) * SCALE
    );

    if (outside_panel_bounds(px, panel_min, panel_max)) {
        return vec4(0.0);
    }

    vec4 r = vec4(0.0, 0.0, 0.0, 1.0);
    float row_stride = LINE_H * SCALE;
    int row = int(floor((px.y - o0.y) / row_stride));

    if (row >= 0 && row < row_count) {
        vec2 origin = o0 + vec2(0.0, float(row) * row_stride);
        r = max(
            r,
            draw_metrics_row(row, origin, px)
        );
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

vec4 render_metering_preview() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec2 px = HOOKED_pos * HOOKED_size;
    vec2 mp = preview_metering_position(HOOKED_pos);
    float value = METERING_tex(mp).x;

    color.rgb = composite_preview_layer(color.rgb, draw_highlights(value));
    color.rgb = composite_preview_layer(color.rgb, draw_matrix_metering(mp));
    color.rgb = composite_preview_layer(color.rgb, draw_histogram(px));
    color.rgb = composite_preview_layer(color.rgb, draw_vectorscope(px));
    color.rgb = composite_preview_layer(color.rgb, draw_metrics_panel(px));

    return color;
}

vec4 hook() { return render_metering_preview(); }
