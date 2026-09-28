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

//!PARAM auto_exposure_white_constraint
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.25

//!PARAM auto_exposure_headroom_retention
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.5

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

//!PARAM spatial_stable_level
//!TYPE uint
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
//!VAR uint metered_coarse_histogram[64]
//!VAR float metered_zone_average[144]
//!VAR float metered_zone_spread[144]
//!VAR float metered_zone_preview_weight[144]
//!VAR float metered_histogram_average
//!VAR float metered_matrix_average
//!VAR float metered_matrix_blend
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
//!VAR float input_max_i
//!VAR float input_min_i
//!VAR float input_avg_i
//!VAR float exposure_ev
//!VAR float exposure_scale
//!VAR float exposed_max_i
//!VAR float exposed_min_i
//!VAR float output_max_j
//!VAR float output_min_j
//!VAR float output_reverse_max_jhk
//!STORAGE

//!BUFFER VECTORSCOPE
//!VAR uint vectorscope_bins[36864]
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
//!WHEN enable_metering 0 > max_pq_y 0 > ! scene_max_r 0 > scene_max_g 0 > + scene_max_b 0 > + ! * force_metering + * preview_metering +
//!DESC metering (intensity map)

// The peak conditions above must stay aligned with resolve_metering_metrics'
// has_pq_peak/has_scene_peak: both treat NaN and negative metadata as
// absent, so the resolver never consumes METERED while this pass is gated
// off, and force_metering waives the absence test identically on both
// sides. The two expressions cannot share code - change both sides together.
//
// The alignment covers only the resolver: the histogram, statistics,
// temporal, and preview passes consume METERING/METERED unconditionally,
// but in every configuration that gates this pass off their results are
// discarded or derived from a constant map.
//
// WHEN conditions must reference only parameters or the built-in OUTPUT
// size variable: libplacebo evaluates WHEN before resolving BIND, so a texture
// reference (e.g. METERING.w) errors out when the producing pass is gated
// off (libplacebo issue 376). Width/height expressions are safe because
// they are evaluated only after the binds resolve.
//
// Body comments must never contain the header-line marker (two slashes
// plus an exclamation mark): the parser splits pass bodies at it anywhere
// in the text.

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

// Ordered-comparison sanitizer: NaN and values at or below the lower bound
// map to the lower bound, values above the upper bound map to it.
//
// Deliberately a ternary, not clamp: GLSL clamp/min/max do not specify NaN
// propagation, and callers rely on the ordered comparison rejecting NaN
// before pow(). Redefined per pass because each shader pass is a separate
// compilation unit.
float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

float metering_intensity(vec3 rgb) {
    float y = RGB_to_Y(rgb);
    // The ordered comparison rejects NaN as well as non-positive values
    // before the fractional PQ power can turn -0.0 into NaN.
    float y_abs = sanitize_bounded(y * reference_white, 0.0, pw);
    return pq_eotf_inv(y_abs);
}

float metering_max_rgb(vec3 rgb) {
    float maximum = max(max(rgb.r, rgb.g), rgb.b);
    float maximum_abs = sanitize_bounded(
        maximum * reference_white,
        0.0,
        pw
    );
    return pq_eotf_inv(maximum_abs);
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

// The metering map is reduced to 512x288 by halving rather than in one step: at
// 4K a single step is a factor of 7.5 per axis taken with one bilinear tap, i.e.
// point sampling with aliasing. Which pixels survive then depends on the
// subpixel alignment, so a small moving highlight makes the measured peak jump
// while nothing in the scene changes. Each halving averages exactly 2×2 before
// the fixed-size histogram and matrix analysis. The passes are conditional, so
// only as many run as the source resolution needs: two at 4K, one at 1080p.
// Testing both dimensions against both landscape thresholds makes the chain
// orientation-independent before portrait analysis is rotated below.
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

vec2 sample_metering_downscaled() {
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
    vec2 sum = sample_metering_oriented(
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

vec4 hook() { return vec4(sample_metering_downscaled(), 0.0, 1.0); }

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_level 0 >
//!DESC metering (spatial stabilization, blur, horizontal)

// One pass per direction, sized by spatial_stable_level. The kernel is sized
// here rather than selected by WHEN: a parameter is an ordinary variable in a
// hook body, as reference_white and enable_metering already are.
//
// The levels are spaced by ratio, not by difference. Perceived blur tracks the
// ratio of the radius, which is why mip levels and image-processing octaves
// are geometric. Equal differences over this range would double the radius on
// the first step and add 25% on the last, which is the opposite of what a
// strength control should do. Level 1 is one texel of the fixed 512x288
// metering map and every further level multiplies that by 1.25, so the scale
// runs from 1.0 to 2.441 texels and the default of 3 sits at 1.562.
//
// Three sigma caps the reach at 8 texels and nine bilinear fetches per
// direction at the maximum, five at the lightest level, and the pairing below
// reproduces the discrete kernel exactly rather than approximating it.
//
// The directions stay separate passes, and the result must still be
// materialised as METERING: the matrix zones and statistics passes read the
// same map the histogram does, so folding the vertical half into the histogram
// pass would leave them reading an unblurred one.
//
// The kernel below is duplicated in the vertical pass. Each pass is a separate
// compilation unit, so it cannot be shared; offset, weight and direction must
// stay identical across the two or the blur stops being separable and the
// measured peak starts depending on orientation.
//
// [Efficient Gaussian blur with linear sampling](https://www.rastergrid.com/blog/2010/09/efficient-gaussian-blur-with-linear-sampling/)

const float spatial_stable_sigma_base = 1.0;
const float spatial_stable_sigma_ratio = 1.25;
const float spatial_stable_reach = 3.0;
const vec2 direction = vec2(1.0, 0.0);

float spatial_stable_weight(float tap, float variance) {
    return exp(-0.5 * tap * tap / variance);
}

vec4 hook() {
    // WHEN gates this pass off at zero, so the exponent below never leaves the
    // level range the header documents.
    float sigma = spatial_stable_sigma_base * pow(
        spatial_stable_sigma_ratio,
        float(spatial_stable_level) - 1.0
    );
    float variance = sigma * sigma;
    float last_tap = ceil(spatial_stable_reach * sigma);

    // A bilinear fetch at a fractional offset reproduces the two discrete taps
    // it straddles, so one fetch carries a pair and the loop runs at half the
    // tap count. The pair's offset is its centre of mass and its weight the
    // pair sum, which is what makes the substitution exact.
    vec2 sum = METERING_tex(METERING_pos).xy;
    float weight_sum = 1.0;
    for (uint pair = 1u; 2.0 * float(pair) <= last_tap + 1.0; pair++) {
        float near_tap = 2.0 * float(pair) - 1.0;
        float far_tap = near_tap + 1.0;
        float near_weight = spatial_stable_weight(near_tap, variance);
        float far_weight = far_tap <= last_tap
            ? spatial_stable_weight(far_tap, variance)
            : 0.0;

        float pair_weight = near_weight + far_weight;
        float pair_offset =
            (near_tap * near_weight + far_tap * far_weight) / pair_weight;

        sum += METERING_texOff( direction * pair_offset).xy * pair_weight;
        sum += METERING_texOff(-direction * pair_offset).xy * pair_weight;
        weight_sum += 2.0 * pair_weight;
    }

    // Dividing by the accumulated weight preserves the mean of the map; the
    // unnormalised Gaussian weights sum to more than one. texOff clamps at the
    // borders, so an edge texel is counted once per tap that reaches it and
    // the ratio still cannot leave the input range.
    return vec4(sum / weight_sum, 0.0, 1.0);
}

//!HOOK OUTPUT
//!BIND METERING
//!SAVE METERING
//!COMPONENTS 2
//!WIDTH METERING.w
//!HEIGHT METERING.h
//!WHEN spatial_stable_level 0 >
//!DESC metering (spatial stabilization, blur, vertical)

// Same kernel as the horizontal pass above, with direction swapped. Only these
// two blocks exist now, but they are still a copy: see the note above.

const float spatial_stable_sigma_base = 1.0;
const float spatial_stable_sigma_ratio = 1.25;
const float spatial_stable_reach = 3.0;
const vec2 direction = vec2(0.0, 1.0);

float spatial_stable_weight(float tap, float variance) {
    return exp(-0.5 * tap * tap / variance);
}

vec4 hook() {
    // WHEN gates this pass off at zero, so the exponent below never leaves the
    // level range the header documents.
    float sigma = spatial_stable_sigma_base * pow(
        spatial_stable_sigma_ratio,
        float(spatial_stable_level) - 1.0
    );
    float variance = sigma * sigma;
    float last_tap = ceil(spatial_stable_reach * sigma);

    // A bilinear fetch at a fractional offset reproduces the two discrete taps
    // it straddles, so one fetch carries a pair and the loop runs at half the
    // tap count. The pair's offset is its centre of mass and its weight the
    // pair sum, which is what makes the substitution exact.
    vec2 sum = METERING_tex(METERING_pos).xy;
    float weight_sum = 1.0;
    for (uint pair = 1u; 2.0 * float(pair) <= last_tap + 1.0; pair++) {
        float near_tap = 2.0 * float(pair) - 1.0;
        float far_tap = near_tap + 1.0;
        float near_weight = spatial_stable_weight(near_tap, variance);
        float far_weight = far_tap <= last_tap
            ? spatial_stable_weight(far_tap, variance)
            : 0.0;

        float pair_weight = near_weight + far_weight;
        float pair_offset =
            (near_tap * near_weight + far_tap * far_weight) / pair_weight;

        sum += METERING_texOff( direction * pair_offset).xy * pair_weight;
        sum += METERING_texOff(-direction * pair_offset).xy * pair_weight;
        weight_sum += 2.0 * pair_weight;
    }

    // Dividing by the accumulated weight preserves the mean of the map; the
    // unnormalised Gaussian weights sum to more than one. texOff clamps at the
    // borders, so an edge texel is counted once per tap that reaches it and
    // the ratio still cannot leave the input range.
    return vec4(sum / weight_sum, 0.0, 1.0);
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
    if (index < 1024u) {
        metered_histogram[index] = 0u;
    }
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

float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

// The sanitizer is part of the conversion rather than a precondition checked at
// the call site: NaN and out-of-range values must not reach the float-to-uint
// conversion, whose result is undefined there and differs by backend (D3D
// converts NaN to zero, Vulkan leaves it to the driver). Same shape as
// pq_to_uint, matrix_zone_value_code and histogram_interval_bounds.
uint to_uint(float x) {
    return uint(sanitize_bounded(x, 0.0, 1.0) * 4095.0 + 0.5);
}

uint to_histogram_bin(float x) {
    return min(to_uint(x) >> 2u, 1023u);
}

// texelFetch is not a sampler read, so nothing clamps it: outside the image it
// is undefined in SPIR-V and reads as zero under D3D, which would land those
// samples in the black bin without saying so. The 32x32 block below only tiles
// the metering map exactly while the downscaling pass pins it to 512x288, and
// this keeps that coincidence from being load-bearing. Clamping to the edge is
// what the sampler does for every other read of this map.
//
// The extent is queried once per invocation and passed in. glslang keeps one
// OpImageQuerySizeLod for it; SPIRV-Cross copies it back out per fetch site,
// because an HLSL GetDimensions has no expression form.
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
    // Accumulate locally first. This replaces one contended global atomic per
    // metering pixel with at most one global merge per non-empty workgroup bin.
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
//!WHEN auto_exposure_anchor 0 > preview_metering + enable_metering 1 > *
//!DESC metering (matrix zones)

// No metadata-absence conditions here, on either side. The max side rides
// on this pass's METERING binding: the intensity-map pass gates itself off
// whenever max_pq_y or scene_max is present and force_metering is off, so
// this pass never runs while peak metadata exists unless force_metering
// keeps the metering chain active. The average side needs no condition
// because it can
// never occur without the max side: max_pq_y/avg_pq_y are written only
// together by libplacebo's peak detection (pl_get_detected_hdr_metadata
// fills both from one buffer and writes nothing when the average is zero),
// and scene_max/scene_avg both come from mandatory HDR10+ payload fields
// (maxscl and average_maxrgb). An average-without-maximum state is not
// representable in either source, so avg absence conditions here would
// only ever be true in configurations the max conditions already gate
// off.

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

// Ordered-comparison sanitizer; see the metering intensity pass.
float sanitize_bounded(float value, float lower_bound, float upper_bound) {
    return value > lower_bound ? min(value, upper_bound) : lower_bound;
}

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
        uint retained = retained_count(
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

        if (cumulative < low_target && next >= low_target) {
            low_bin = i;
        }
        if (cumulative < high_target && next >= high_target) {
            high_bin = i;
        }

        cumulative = next;
    }

    uint retained_total = upper_target - lower_target;
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
            if (zone_index == 0u) {
                metered_zone_valid = 1u;
            }
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
//!DESC metering (statistics reduction)

// One just-noticeable difference step on the PQ scale.
const float JND = 1.0 / 720.0;

// Histogram statistics.
const uint METERING_HISTOGRAM_SIZE = 1024u;
const uint METERING_REDUCTION_SIZE = 256u;
const uint METERING_BINS_PER_THREAD = 4u;
const uint METERING_COARSE_HISTOGRAM_SIZE = 64u;
const uint METERING_BLOCKS_PER_COARSE_BIN = 4u;
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

shared uint histogram_prefix[METERING_REDUCTION_SIZE];
shared float average_partial[METERING_REDUCTION_SIZE];
shared float histogram_average;
shared uint black_bin;
shared uint white_bin;
shared uint median_bin;
shared uint diffuse_white_bin;

shared vec2 matrix_partial[METERING_REDUCTION_SIZE];
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
        if (tid >= offset) {
            inclusive += histogram_prefix[tid - offset];
        }
        barrier();
        histogram_prefix[tid] = inclusive;
        barrier();
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
    return uint(sanitize_bounded(value, 0.0, 1.0) * 4095.0 + 0.5);
}

// Detect only near-zero, internally uniform zones. Requiring the black
// fraction to reach the occupancy threshold before a column counts as
// border makes the crop follow presentation bars instead of dark objects
// inside the picture; the whole-frame average guard avoids classifying a
// dark shot.
bool matrix_zone_looks_like_border(uint index) {
    float black_limit = min(
        METERING_BORDER_BLACK_MAX,
        histogram_average * METERING_BORDER_BLACK_RELATIVE_SCALE
    );
    return histogram_average > METERING_BORDER_GLOBAL_MIN &&
           metered_zone_average[index] <= black_limit &&
           metered_zone_spread[index] <= METERING_BORDER_SPREAD_MAX;
}

float matrix_column_black_fraction(uint x) {
    uint count = 0u;
    for (uint y = 0u; y < MATRIX_ZONE_ROWS; y++) {
        uint index = y * MATRIX_ZONE_COLUMNS + x;
        if (matrix_zone_looks_like_border(index)) {
            count++;
        }
    }
    return float(count) / float(MATRIX_ZONE_ROWS);
}

float matrix_row_black_fraction(uint y, uint left, uint right) {
    uint count = 0u;
    for (uint x = left; x < right; x++) {
        uint index = y * MATRIX_ZONE_COLUMNS + x;
        if (matrix_zone_looks_like_border(index)) {
            count++;
        }
    }
    return float(count) / float(max(right - left, 1u));
}

void prepare_matrix_active_region(uint tid) {
    if (tid == 0u) {
        uint left = 0u;
        uint right = MATRIX_ZONE_COLUMNS;
        uint top = 0u;
        uint bottom = MATRIX_ZONE_ROWS;

        if (metered_zone_valid > 0u) {
            for (uint x = 0u; x < MATRIX_ZONE_COLUMNS; x++) {
                if (matrix_column_black_fraction(x) <
                    METERING_BORDER_OCCUPANCY_MIN) {
                    break;
                }
                left = x + 1u;
            }
            for (int x = int(MATRIX_ZONE_COLUMNS) - 1; x >= 0; x--) {
                if (matrix_column_black_fraction(uint(x)) <
                    METERING_BORDER_OCCUPANCY_MIN) {
                    break;
                }
                right = uint(x);
            }

            if (right > left &&
                right - left >= METERING_ACTIVE_COLUMNS_MIN) {
                for (uint y = 0u; y < MATRIX_ZONE_ROWS; y++) {
                    if (matrix_row_black_fraction(y, left, right) <
                        METERING_BORDER_OCCUPANCY_MIN) {
                        break;
                    }
                    top = y + 1u;
                }
                for (int y = int(MATRIX_ZONE_ROWS) - 1; y >= 0; y--) {
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
            right = MATRIX_ZONE_COLUMNS;
            top = 0u;
            bottom = MATRIX_ZONE_ROWS;
        }

        matrix_active_bounds = uvec4(left, right, top, bottom);
        float active_area = float((right - left) * (bottom - top));
        float removed_fraction = 1.0 - active_area /
                                 float(MATRIX_ZONE_COUNT);
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
    if (metered_zone_valid == 0u || index >= MATRIX_ZONE_COUNT) {
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

    // A stronger matrix/global disagreement suggests an intentionally framed
    // or backlit subject. Keep at least 25% of the whole-frame estimate so the
    // decision cannot collapse onto a small central region.
    float difference = abs(matrix_average - histogram_average);
    float matrix_confidence = matrix_difference_confidence(difference);
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
        METERING_BORDER_MATRIX_WEIGHT,
        matrix_border_confidence
    );
    if (!matrix_available) {
        matrix_weight = 0.0;
    }
    // These values feed the two independent exposure branches as well as the
    // optional preview. Publish them unconditionally whenever metering runs.
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

void publish_coarse_histogram(uint tid) {
    if (tid >= METERING_COARSE_HISTOGRAM_SIZE) {
        return;
    }

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
    uint median_target = max(
        uint(ceil(float(METERING_SAMPLE_COUNT) * METERING_MEDIAN_PERCENTILE)),
        1u
    );
    uint diffuse_white_target = max(
        uint(ceil(
            float(METERING_SAMPLE_COUNT) *
            METERING_DIFFUSE_WHITE_PERCENTILE
        )),
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
        uint global_retained = global_targets.y - global_targets.x;
        histogram_average = average_partial[0] /
                            float(max(global_retained, 1u));
        metered_min_i = black_bin << 2u;
        metered_max_i = min((white_bin << 2u) + 3u, 4095u);
        metered_median_i = min((median_bin << 2u) + 2u, 4095u);
        metered_diffuse_white_i = min(
            (diffuse_white_bin << 2u) + 2u,
            4095u
        );
    }
    barrier();
}

void refine_average_with_matrix(uint tid) {
    // Keep synchronization unconditional. D3DCompile cannot prove that an
    // SSBO-backed validity flag is uniform across the workgroup and rejects
    // barriers placed after an early return controlled by that flag.
    prepare_matrix_active_region(tid);

    vec2 partial = matrix_zone_partial(tid, histogram_average);
    if (preview_metering > 0u && tid < MATRIX_ZONE_COUNT) {
        // Keep the spread feeding the preview weight immutable throughout
        // this pass. A separate preview slot avoids a cross-invocation SSBO
        // read/write dependency; barrier() alone only orders shared
        // workgroup state.
        //
        // Publishing only while the preview is on cannot expose stale
        // weights: metered_zone_valid is cleared every frame and set only
        // by the matrix-zones pass, whose WHEN already includes
        // preview_metering - the frame the preview toggles on re-runs the
        // zones pass and republishes fresh weights before any preview read.
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
//!BIND METERED_TEMPORAL
//!SAVE EMPTY
//!WIDTH 64
//!HEIGHT 1
//!COMPUTE 64 1 64 1
//!WHEN temporal_stable_duration 0.0 >
//!DESC metering (temporal stabilization)

// One just-noticeable difference step on the PQ scale.
const float JND = 1.0 / 720.0;

// Scene analysis uses one-dimensional Wasserstein distance on the 64-bin PQ
// distributions. The current frame is compared both with a slowly moving shot
// reference and with the immediately previous frame: only an abrupt transition
// can start a cut candidate, so gradual ramps do not become cuts merely because
// they eventually move far from the old reference.

const uint TEMPORAL_HISTOGRAM_SIZE = 64u;
const uint TEMPORAL_HISTOGRAM_SAMPLE_COUNT = 512u * 288u;
const float TEMPORAL_HISTOGRAM_CUT_THRESHOLD = 144 * JND;
const float TEMPORAL_HISTOGRAM_FRAME_THRESHOLD = 72 * JND;
const float TEMPORAL_HISTOGRAM_TIME_SCALE = 0.50;
const float TEMPORAL_SCENE_CONFIRM_TIME_SCALE = 0.25;
const float TEMPORAL_SCENE_ADAPTATION_TIME_SCALE = 0.50;
// The floor shared by the reference blend and both scene ramps: even the
// fastest stabilization setting keeps one 240-fps frame of smoothing.
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

bool finite_float(float value) {
    return !isnan(value) && !isinf(value);
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

void temporal_set_scalar_state(uint histogram_valid) {
    temporal_clear_scene_candidate();
    metered_histogram_valid = histogram_valid;
    metered_scene_adaptation_end_pts = 0u;
    metered_scene_fast_response = 0u;
}

void temporal_initialize_scalar_state() {
    temporal_set_scalar_state(1u);
    metered_temporal_pts = pts_to_uint(PTS);
}

void temporal_invalidate_state() {
    // Only the validity flag is load-bearing here: the next finite-PTS
    // frame takes temporal_initialize_frame, which rewrites pts and the
    // scene flags via temporal_set_scalar_state before any read. Keep pts
    // out of the shared reset so invalidation does not perform a dead write.
    temporal_set_scalar_state(0u);
}

void temporal_initialize_frame() {
    temporal_initialize_scalar_state();
    temporal_frame_operation = TEMPORAL_FRAME_INITIALIZE;
}

vec2 temporal_measure_distribution_delta(uint index, float current) {
    vec2 delta = vec2(
        current - metered_reference_histogram[index],
        current - metered_previous_histogram[index]
    );
    metered_previous_histogram[index] = current;
    return delta;
}

void temporal_scan_distribution_delta(uint tid, vec2 delta) {
    temporal_distance_partial[tid] = delta;
    barrier();

    // Inclusive Hillis-Steele scan. The barrier before each write keeps every
    // read on the previous iteration's shared-memory snapshot.
    for (uint offset = 1u;
         offset < TEMPORAL_HISTOGRAM_SIZE;
         offset <<= 1u) {
        vec2 inclusive = temporal_distance_partial[tid];
        if (tid >= offset) {
            inclusive += temporal_distance_partial[tid - offset];
        }
        barrier();
        temporal_distance_partial[tid] = inclusive;
        barrier();
    }
}

vec2 temporal_cdf_distance(uint tid) {
    // Both normalized CDFs end at total probability one, so their last
    // inclusive difference is zero. Wasserstein-1 therefore integrates only
    // the first 63 boundaries.
    // Each uniform PQ bin spans 1/64 of the normalized code-value axis.
    return tid + 1u < TEMPORAL_HISTOGRAM_SIZE
        ? abs(temporal_distance_partial[tid]) /
          float(TEMPORAL_HISTOGRAM_SIZE)
        : vec2(0.0);
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
    if (metered_scene_fast_response == 0u) {
        return;
    }

    float end_pts = pts_to_float(metered_scene_adaptation_end_pts);
    if (PTS >= end_pts) {
        metered_scene_fast_response = 0u;
    }
}

bool temporal_scene_candidate_active(
    float reference_distance,
    float frame_distance
) {
    bool far_from_reference = reference_distance >
                              TEMPORAL_HISTOGRAM_CUT_THRESHOLD;
    if (metered_scene_candidate_active > 0u) {
        return far_from_reference;
    }

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
    if (elapsed >= temporal_scene_confirmation_time()) {
        temporal_confirm_scene_change();
    }
}

void temporal_prepare_frame() {
    temporal_frame_operation = TEMPORAL_FRAME_SKIP;
    temporal_reference_operation = TEMPORAL_REFERENCE_KEEP;

    if (!finite_float(PTS)) {
        // A discontinuous frame may carry unrelated content. Invalidate and
        // skip histogram learning entirely (frame operation stays SKIP):
        // initializing the reference from it could seed a false scene-cut
        // candidate. The next finite-PTS frame re-initializes from fresh
        // data via temporal_initialize_frame.
        temporal_invalidate_state();
        return;
    }

    if (metered_histogram_valid == 0u) {
        temporal_initialize_frame();
        return;
    }

    float previous_pts = pts_to_float(metered_temporal_pts);
    float delta_time = PTS - previous_pts;

    // Redrawing the same video frame must not advance temporal state.
    if (abs(delta_time) <= TEMPORAL_PTS_EPSILON) {
        return;
    }

    if (delta_time < 0.0 || delta_time > temporal_stable_duration) {
        temporal_initialize_frame();
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
        // A non-finite sample has to be replaced rather than blended: mix()
        // propagates NaN forever, and a NaN reference makes every distance
        // comparison false, which would also keep the REPLACE path below out
        // of reach. Every other piece of temporal state either sits behind a
        // validity flag or a timestamp window, or converges on a value derived
        // from the current frame - that is what lets the state go undeclared,
        // and this is the one writer that needed help holding it up.
        float reference = metered_reference_histogram[index];
        metered_reference_histogram[index] = finite_float(reference)
            ? mix(reference, current, temporal_reference_alpha)
            : current;
    } else if (
        temporal_reference_operation == TEMPORAL_REFERENCE_REPLACE
    ) {
        metered_reference_histogram[index] = current;
    }
}

void analyze_metering_temporally() {
    uint index = gl_LocalInvocationIndex;

    if (index == 0u) {
        temporal_prepare_frame();
    }
    barrier();

    if (temporal_frame_operation == TEMPORAL_FRAME_SKIP) {
        return;
    }

    float current = temporal_histogram_value(index);

    if (temporal_frame_operation == TEMPORAL_FRAME_INITIALIZE) {
        temporal_initialize_histogram(index, current);
        return;
    }

    vec2 delta = temporal_measure_distribution_delta(index, current);
    temporal_scan_distribution_delta(index, delta);
    vec2 distance = temporal_cdf_distance(index);
    temporal_reduce_distances(index, distance);

    if (index == 0u) {
        temporal_process_distances(temporal_distance_partial[0]);
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

// The dynamic-metadata parameters at the top of the file are provided by mpv
// [vo_gpu_next: add chroma location to shader parameters](https://github.com/mpv-player/mpv/pull/15239)

// The same minimum time constant the temporal pass uses: the auto-exposure
// and curve LUT ramps share it via ramp_alpha below, so neither copy can
// drift away from the smoothing floor.
const float TEMPORAL_MIN_TIME_CONSTANT = 1.0 / 240.0;

// Filter automatic exposure in its final EV domain so observation noise cannot
// become a large nonlinear luminance change. Manual exposure remains direct.
const float EXPOSURE_RISE_TIME_SCALE = 0.50;
const float EXPOSURE_FALL_TIME_SCALE = 0.35;
const float EXPOSURE_PTS_EPSILON = 1e-6;

// Fast-response window after a scene change: the duration scales the
// stabilization interval and the time scale clamps the ramp.
const float OUTPUT_TEMPORAL_SCENE_TIME_SCALE = 0.125;
const float OUTPUT_TEMPORAL_SCENE_ADAPTATION_TIME_SCALE = 0.50;

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
    float histogram_average;
    float matrix_average;
    float matrix_weight;
    float median;
    float diffuse_white;
};

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

    // This must match the peak-metadata conditions on the intensity-map pass
    // (its WHEN header with the max_pq_y 0 > ! ... expression). Both sides
    // treat NaN and negative metadata as absent, so a skipped pass can never
    // make the resolver consume stale METERED values, and force_metering
    // waives the absence test identically on both sides. The two
    // expressions cannot share code; any change to one side must update the
    // other.
    bool use_measured = enable_metering > 0 &&
                        (force_metering > 0 ||
                         (!has_pq_peak && !has_scene_peak));

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

    if (use_measured && enable_metering > 1) {
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
    if (use_measured && enable_metering > 1) {
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

    // Enforce the physical ordering assumed by the exposure-limit logarithms.
    // The max/min ordering lines are no-ops for consistent inputs. The
    // average clamp is not: it rewrites the metadata average into the
    // measured band in mixed metadata+measured configurations, and pins the
    // matrix-refined measured average inside the robust [minimum, maximum]
    // band in pure measured configurations. force_metering at enable_metering
    // 1 deliberately lands in the mixed configuration: the extrema come from
    // measurement while the average (which level 1 does not measure) falls
    // back to the metadata source.
    //
    // This is deliberate: without it, a mixed-path average above the
    // measured maximum would invert the negative exposure limit
    // (ev_limit_neg < 0) and force auto exposure to sit at the inverted
    // bound.
    metrics.max_rgb = max(metrics.max_rgb, metrics.maximum);
    metrics.minimum = min(metrics.minimum, metrics.maximum);
    if (metrics.average > 0.0) {
        metrics.average = clamp(
            metrics.average,
            metrics.minimum,
            metrics.maximum
        );
    }
    if (metrics.histogram_average > 0.0) {
        metrics.histogram_average = clamp(
            metrics.histogram_average,
            metrics.minimum,
            metrics.maximum
        );
    }
    if (metrics.matrix_average > 0.0) {
        metrics.matrix_average = clamp(
            metrics.matrix_average,
            metrics.minimum,
            metrics.maximum
        );
    }
    if (metrics.median > 0.0) {
        metrics.median = clamp(
            metrics.median,
            metrics.minimum,
            metrics.maximum
        );
    }
    if (metrics.diffuse_white > 0.0) {
        metrics.diffuse_white = clamp(
            metrics.diffuse_white,
            metrics.median,
            metrics.maximum
        );
    }

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
    if (auto_exposure_white_constraint <= 0.0 ||
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
        clamp(auto_exposure_white_constraint, 0.0, 1.0)
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

    float histogram_negative_limit = auto_exposure_limit_negative;
    bool has_content_white = metrics.median > 0.0 &&
                             metrics.diffuse_white > 0.0;
    if (auto_exposure_headroom_retention > 0.0 && has_content_white) {
        float highlight_headroom = max(
            log2(maximum / max(reference_white, 1e-6)),
            0.0
        );
        float retained_headroom = clamp(
            auto_exposure_headroom_retention,
            0.0,
            1.0
        );
        histogram_negative_limit = min(
            histogram_negative_limit,
            (1.0 - retained_headroom) * highlight_headroom
        );
    }

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

float resolve_exposure(MeteringMetrics metrics) {
    // A non-zero external value replaces automatic exposure entirely.
    if (exposure_value != 0.0) {
        return exposure_value;
    }

    if (metrics.average <= 0.0 || auto_exposure_anchor <= 0.0) {
        return 0.0;
    }

    return calculate_auto_exposure(metrics);
}

float output_temporal_time_scale(float normal_scale) {
    float adaptation_end = uintBitsToFloat(
        metered_scene_adaptation_end_pts
    );
    float adaptation_duration = max(
        temporal_stable_duration * OUTPUT_TEMPORAL_SCENE_ADAPTATION_TIME_SCALE,
        TEMPORAL_MIN_TIME_CONSTANT
    );
    bool fast_response = metered_scene_fast_response > 0u &&
                         PTS < adaptation_end &&
                         PTS >= adaptation_end - adaptation_duration;
    return fast_response
        ? min(normal_scale, OUTPUT_TEMPORAL_SCENE_TIME_SCALE)
        : normal_scale;
}

// The exponential ramp form shared by auto-exposure and the curve LUT.
float ramp_alpha(float delta_time, float time_scale) {
    float time_constant = max(
        temporal_stable_duration * time_scale,
        TEMPORAL_MIN_TIME_CONSTANT
    );
    return 1.0 - exp(-delta_time / time_constant);
}

float reset_auto_exposure(float target) {
    smoothed_ev = target;
    smoothed_ev_pts = floatBitsToUint(PTS);
    smoothed_ev_valid = 1u;
    return target;
}

float stabilize_auto_exposure(float target, bool automatic) {
    // Manual exposure is clamped to the declared [-64, 64] range, and a
    // non-finite target snaps to neutral EV instead of poisoning the ramp.
    //
    // The curve path uses the complementary policy (hold last valid value,
    // see stabilize_curve_value): a broken frame must not re-baseline the
    // whole LUT, while exposure re-baselines at neutral. In practice the
    // asymmetry is unreachable: a non-finite target with a finite PTS
    // requires a non-finite user parameter, and on the reachable broken
    // frame (non-finite PTS) both paths render the raw target and recover
    // with the same two-frame contract below.
    target = finite_float(target) ? clamp(target, -64.0, 64.0) : 0.0;
    // Self-healing guard: VAR-backed state can hold NaN across shader
    // reloads. Treat such state as uninitialized rather than mixing NaN into
    // the ramp. The pts field needs its own check: a NaN there fails every
    // range comparison below and re-poisons smoothed_ev through the mix.
    //
    // Likely unreachable from in-shader writes (every write site produces a
    // finite value), kept as defense.
    if (smoothed_ev_valid > 0u &&
        (!finite_float(smoothed_ev) ||
         !finite_float(uintBitsToFloat(smoothed_ev_pts)))) {
        smoothed_ev_valid = 0u;
    }

    if (!finite_float(PTS)) {
        // Only the valid flag matters here: every smoothed_ev read is gated
        // on smoothed_ev_valid, so the value and pts writes are dead. The
        // next finite frame re-baselines via reset_auto_exposure.
        //
        // Recovery contract: an unknown-PTS frame (seek/preroll redraw)
        // renders the raw target directly for two frames - this one, then
        // one more through reset_auto_exposure - before the ramp resumes.
        // A scene change coinciding with such a frame therefore snaps
        // instead of ramping. This replaces the pre-diff behavior of
        // permanently poisoning smoothed_ev with NaN.
        smoothed_ev_valid = 0u;
        return target;
    }

    if (!automatic || temporal_stable_duration <= 0.0) {
        return reset_auto_exposure(target);
    }

    if (smoothed_ev_valid == 0u) {
        return reset_auto_exposure(target);
    }

    float delta_time = PTS - uintBitsToFloat(smoothed_ev_pts);
    if (abs(delta_time) <= EXPOSURE_PTS_EPSILON) {
        return smoothed_ev;
    }

    if (delta_time < 0.0 || delta_time > temporal_stable_duration) {
        return reset_auto_exposure(target);
    }

    float time_scale = target > smoothed_ev
        ? EXPOSURE_RISE_TIME_SCALE
        : EXPOSURE_FALL_TIME_SCALE;
    time_scale = output_temporal_time_scale(time_scale);
    float alpha = ramp_alpha(delta_time, time_scale);
    smoothed_ev = mix(smoothed_ev, target, alpha);
    smoothed_ev_pts = floatBitsToUint(PTS);
    return smoothed_ev;
}

void record_curve_temporal_pts() {
    curve_temporal_pts = floatBitsToUint(PTS);
    curve_temporal_valid = 1u;
}

void invalidate_curve_temporal() {
    // Only the valid flag is load-bearing: the pts read in
    // prepare_curve_temporal sits behind the valid == 1 gate, and
    // record_curve_temporal_pts rewrites pts before any such read.
    curve_temporal_valid = 0u;
}

void prepare_curve_temporal() {
    curve_temporal_reset = 1u;

    if (!finite_float(PTS)) {
        // Reset stays armed through the invalidation below, so an
        // unknown-PTS frame hard-writes the whole 1024-point curve LUT
        // for two frames (this one and the next) before the normal ramp
        // resumes. The alpha write is deliberately omitted: every path
        // that clears reset rewrites alpha in the same invocation, and
        // every reset == 1 path hard-writes without reading it.
        //
        // This recovery replaces the pre-diff behavior of permanently
        // poisoning smoothed_curve with NaN.
        invalidate_curve_temporal();
        return;
    }

    if (temporal_stable_duration <= 0.0) {
        record_curve_temporal_pts();
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
    if (delta_time < 0.0 || delta_time > temporal_stable_duration) {
        return;
    }

    float time_scale = output_temporal_time_scale(
        CURVE_TEMPORAL_TIME_SCALE
    );
    curve_temporal_alpha = ramp_alpha(delta_time, time_scale);
    curve_temporal_reset = 0u;
}

float apply_exposure_to_pq(float value, float scale) {
    // Compose with metadata_nits_to_pq rather than repeating the
    // sanitize-then-convert chain: the helper guards non-positive input with
    // 0.0, where an inline form would leak pq_eotf_inv(0) = 7.3e-7 into the
    // published black point.
    //
    // The sanitize deliberately caps the curve white point at 10000 nits
    // even under positive exposure: content at the mastering peak maps to
    // full output white instead of rolling off below an extrapolated white
    // point. Values above the PQ mastering range cannot reach tone mapping
    // and would cross the J transform's pole.
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

bool automatic_exposure_enabled(MeteringMetrics metrics) {
    return exposure_value == 0.0 &&
           metrics.average > 0.0 &&
           auto_exposure_anchor > 0.0;
}

void publish_metering_metadata(MeteringMetrics metrics) {
    exposed_max_i = metrics.max_rgb;
    exposed_min_i = metrics.minimum;
}

void publish_input_metering_metadata(MeteringMetrics metrics) {
    input_max_i = metrics.maximum;
    input_min_i = metrics.minimum;
    input_avg_i = metrics.average;
}

// The reverse LUT's lightness axis spans the tone curve's output envelope,
// which H-K compensation lifts above the neutral white point that output_max_j
// names: the most chromatic in-gamut colour at the reference white, the
// Rec.2020 yellow corner, reaches 0.00903 J past it at its worst over the
// declared 1-1000 nit range. That lift is linear in
// hk_effect_compensate_scaling, so one slope covers the whole 0-1 range; the
// remainder pays for LUT sampling and FP16 rounding. tests/test_lut_domain.py
// recomputes the worst case from this shader's own constants and fails if the
// margin stops covering it.
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
    prepare_curve_temporal();
    publish_output_lightness_range();

    MeteringMetrics metrics = resolve_metering_metrics();
    publish_input_metering_metadata(metrics);
    float target_ev = resolve_exposure(metrics);
    exposure_ev = stabilize_auto_exposure(
        target_ev,
        automatic_exposure_enabled(metrics)
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

// The JND coefficient of ΔE_{ITP} is 1 / 720
// 0.0001 C_z is much smaller than a JND
// [BT.2124: Objective metric for the assessment of the potential visibility of colour differences in television](https://www.itu.int/rec/R-REC-BT.2124)
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

// [Hyperbola tone mapping](https://technorgb.blogspot.com/2018/02/hyperbola-tone-mapping.html)
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

// [Filmic Tonemapping with Piecewise Power Curves](https://filmicworlds.com/blog/filmic-tonemapping-with-piecewise-power-curves/)
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
// (x_1 + overshoot * dx, y_1 + overshoot * dy), so the curve still has
// non-zero slope at x_1. Accepts a slight slope discontinuity at x_0.
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

float f(
    float x, float iw, float ib, float ow, float ob,
    float sw, float hw, float cb
) {
    float midgray   = 0.5 * ow;
    float shadow    = mix(midgray, ob, sw);
    float highlight = mix(midgray, ow, hw);
    float target_slope = f_contrast_slope(cb);

    float x0 = ib;
    float y0 = ob;
    float x1 = shadow;
    float y1 = shadow;
    float x2 = highlight;
    float y2 = highlight;
    float x3 = iw;
    float y3 = ow;

    // Pivot the middle line around mid-gray with slope 2^{cb}. Prefer the
    // configured x junctions; if their y values cross an output endpoint,
    // move the junction inward along the same line instead of flattening the
    // requested contrast with an independent y clamp. Note the moved
    // junction lands exactly on the endpoint, so the toe/shoulder region
    // between it and x_0/x_3 collapses to a flat clip at that endpoint: the
    // steep middle line starts at the moved x instead of extending past the
    // output range. A high contrast_bias can
    // therefore override the configured junction positions, e.g. with
    // shadow_weight 1 and cb = 1 at contrast_ratio 1000 the junction moves
    // to (mid-gray + ob) / 2. At the default reference white this is
    // J ~= 0.059. The flat clip covers [ib, x1); when ib == ob, that span
    // occupies about 24.3% of the configured [ob, ow] output interval. A
    // darker input black extends the clipped span further.
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

    // The clamped pivots keep both junctions on the midgray line, so the
    // middle-segment slope is exactly target_slope.
    //
    // The f_slope recompute could only differ in the collapsed
    // both-junctions-at-midgray case, where its zero-denominator guard
    // wrongly returns 1.0.
    float slope = target_slope;
    float intercept = f_intercept(slope, x1, y1);

    if (x >= x1 && x <= x2) {
        return f_linear(x, slope, intercept);
    }

    // The branch conditions guard the segment denominators: the toe is
    // reached only with y1 > y0 (otherwise the flat clip above returns y0)
    // and slope_toe < slope, which forces k = dy - slope*dx < 0 and a
    // positive Suzuki denominator over the whole [x0, x1] span; the shoulder
    // is reached only with y2 < y3 and slope_shoulder < slope, which forces
    // normalized_slope > 1, curvature > 0, and a denominator >= 1. A
    // degenerate dx == 0 (shadow_weight 1 with ib == ob, or highlight_weight
    // 1 with iw == ow) is deflected by f_slope's zero-denominator guard
    // returning exactly 1.0, which sends the branch to the linear fallback -
    // keep that guard value literal and the strict '<' comparisons above, or
    // a NaN path reopens here.
    if (x < x1) {
        // Flat clip at the black endpoint; the steep segment begins at x_1.
        if (y1 <= y0) {
            return y0;
        }

        float slope_toe = f_slope(x0, y0, x1, y1);
        if (slope_toe >= slope) {
            return f_linear(x, slope, intercept);
        }

        return f_toe_suzuki(x, slope, x0, y0, x1, y1);
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

float f(float x, float iw, float ib, float ow, float ob) {
    return f(
        x, iw, ib, ow, ob,
        shadow_weight, highlight_weight, contrast_bias
    );
}

float evaluate_tone_curve(float x) {
    float ow = output_max_j;
    float ob = output_min_j;
    float iw = I_to_J(iz_eotf_inv(pq_eotf(exposed_max_i)));
    float ib = I_to_J(iz_eotf_inv(pq_eotf(exposed_min_i)));

    iw = max(iw, ow);
    ib = min(ib, ob);

    float y = f(x, iw, ib, ow, ob);

    return y;
}

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT, and one 1024-point curve row. The reverse LUT stores its
// higher-resolution Jhk axis in atlas rows and spans the output Rec.2020 cube,
// while a/b share the flattened axis to keep the atlas 65^2 texels wide. The
// neutral tone curve retains its narrower J range.
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
    // curve_temporal_reset stays armed until a frame has hard-written the
    // whole row, so reset > 0 implies the SSBO is either uninitialized or
    // being re-baselined: never read the curve history in that case.
    bool history_valid = curve_temporal_reset == 0u &&
                         finite_float(smoothed_curve[index]);
    // Hold the last valid value on a non-finite target. This is deliberate:
    // unlike exposure, which snaps to neutral (see stabilize_auto_exposure),
    // a broken frame must not re-baseline the whole curve LUT. The snap/hold
    // asymmetry is unreachable in practice: a non-finite target with a
    // finite PTS requires a non-finite user parameter, and the reachable
    // broken frame (non-finite PTS) hard-writes the raw target on both
    // paths.
    if (!finite_float(target)) {
        target = history_valid ? smoothed_curve[index] : 0.0;
    }

    if (curve_temporal_reset > 0u || !history_valid) {
        smoothed_curve[index] = target;
        return target;
    }

    float value = mix(
        smoothed_curve[index],
        target,
        curve_temporal_alpha
    );
    // Likely unreachable from in-shader writes: both mix operands are
    // finite here and curve_temporal_alpha is in [0, 1]. Kept as defense
    // against non-finite state crossing a shader reload.
    value = finite_float(value) ? value : target;
    smoothed_curve[index] = value;
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
//!BIND VECTORSCOPE
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
    vectorscope_bins[base + 0u] = 0u;
    vectorscope_bins[base + 1u] = 0u;
    vectorscope_bins[base + 2u] = 0u;
    vectorscope_bins[base + 3u] = 0u;
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
    atomicAdd(vectorscope_bins[base + 0u], 1u);
    atomicAdd(vectorscope_bins[base + 1u], encoded_rgb.r);
    atomicAdd(vectorscope_bins[base + 2u], encoded_rgb.g);
    atomicAdd(vectorscope_bins[base + 3u], encoded_rgb.b);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUTS
//!BIND METADATA
//!DESC tone mapping (astra)

// LUT atlas layout: a flattened 65^3 RGB-to-Jab LUT, a 129x65x65
// Jab-to-RGB LUT with its output-cube Jhk range stored in rows, and one
// 1024-point neutral-J curve row.
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
    int lower_index = int(floor(position));
    int upper_index = min(lower_index + 1, CURVE_SIZE - 1);
    float weight = fract(position);
    float lower_value = fetch_atlas_raw(ivec2(lower_index, CURVE_ROW)).x;
    float upper_value = fetch_atlas_raw(ivec2(upper_index, CURVE_ROW)).x;
    return LUTS_mul * mix(lower_value, upper_value, weight);
}

float chroma_correction_attenuation(float x, float rate, float threshold) {
    // Preserve the identity endpoint when threshold collapses the interval.
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
    return pow(norm, 1.0 + rate * (1.0 - norm));
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
    return ab * chroma_correction_attenuation(
        ratio_safe,
        chroma_correction_rate,
        chroma_correction_threshold
    );
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

float preview_reference_histogram_count(
    vec2 source_interval,
    float total
) {
    vec2 interval = source_interval * float(PREVIEW_HISTOGRAM_SIZE);
    uvec2 bounds = histogram_interval_bounds(
        interval,
        PREVIEW_HISTOGRAM_SIZE
    );
    float count = 0.0;

    for (uint i = bounds.x; i < bounds.y; i++) {
        float overlap = histogram_bin_overlap(interval, i);
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
    bool temporal_reference_valid = temporal_stable_duration > 0.0 &&
                                    metered_histogram_valid > 0u;
    float reference_count = temporal_reference_valid
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
    if (index < PREVIEW_HISTOGRAM_SIZE) {
        prepare_preview_histogram_bin(index);
    }
    if (index < PREVIEW_HISTOGRAM_COLUMN_COUNT) {
        prepare_preview_curve_column(index);
    }
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

// One just-noticeable difference step on the PQ scale.
const float JND = 1.0 / 720.0;

float to_float(uint x) {
    return float(x) / 4095.0;
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
const float GAP = 6.0 * SCALE;

// The preview histogram uses the same 64-bin grouping as scene-change
// detection. Cyan bars are the current frame, the orange trace is the
// timestamp-smoothed reference, and the yellow curve is the J-domain LUT
// transformed onto the same PQ horizontal and vertical axes.
const uint PREVIEW_HISTOGRAM_SIZE = 64u;
const float PREVIEW_HISTOGRAM_BIN_WIDTH = 4.0;
const float PREVIEW_HISTOGRAM_EXTENT = 256.0;

// The density reference is a fixed 128-bin display scale, deliberately not the
// current grid size: normalizing by (grid / 128)^2 keeps the trace
// resolution-invariant, so the grid can be retuned without dimming or
// brightening it. Deriving the reference from the current grid would cancel the
// grid out of the ratio and silently defeat that invariance.
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
    // The extrema tints use directional bounds - the maximum marks every
    // pixel at or above it, the minimum every pixel at or below - and
    // carry a 5-JND approximation toward the midtones: the extrema metrics
    // are percentile-bin edge codes, not per-pixel values, so a strict
    // bound left the minimum side permanently empty.
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
// Striped cells have been excluded as presentation borders.
vec4 draw_matrix_metering(vec2 position) {
    if (metered_zone_valid == 0u) {
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
        if (min(edge_distance.x, edge_distance.y) < 1.0) {
            return vec4(vec3(0.82), 0.55);
        }
        // Excluded cells stay filled: their content is presentation bars, so
        // the fill hides nothing meaningful. The high-contrast stripes make
        // the exclusion unmistakable. Anti-alias each edge over ~1 px so
        // the 45-degree pixel staircase stays visually straight.
        vec2 oriented_px = clamped_position * oriented_size;
        float phase = fract((oriented_px.x + oriented_px.y) / 16.0);
        float aa = 1.0 / 11.3137085;   // 1 px perpendicular to the stripe
        float stripe = smoothstep(0.5 - aa, 0.5 + aa, phase) *
                       (1.0 - smoothstep(1.0 - aa, 1.0, phase));
        return vec4(mix(vec3(0.04), vec3(1.0), stripe), 0.80);
    }

    // Centered rectangle outline at 30% of the cell with a fixed width.
    // Each side is clipped to the opposite span so only the rectangle
    // itself draws: the naive min-union of both axes leaked the sides past
    // the corners and joined neighboring cells into one continuous lattice.
    // The difference-driven tint and the weight-driven opacity match the
    // original fill.
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

vec4 draw_histogram(vec2 px) {
    vec2 origin = vec2(MARGIN * SCALE);
    vec2 padding = vec2(PAD * SCALE);
    vec2 panel_min = origin - padding;
    vec2 panel_max = origin + vec2(PREVIEW_HISTOGRAM_EXTENT) + padding;

    if (outside_panel_bounds(px, panel_min, panel_max)) {
        return vec4(0.0);
    }

    vec2 local = px - origin;

    if (local.x < 0.0 || local.x >= PREVIEW_HISTOGRAM_EXTENT ||
        local.y < 0.0 || local.y >= PREVIEW_HISTOGRAM_EXTENT) {
        return vec4(0.0, 0.0, 0.0, 1.0);
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
    float reference_height = preview_histogram_reference[index];
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

    if (abs(level - reference_height) <= 1.5 / plot_height) {
        tint = vec3(1.0, 0.55, 0.12);
    }

    if (abs(level - pq_input) <= 0.75 / plot_height) {
        tint = vec3(0.48);
    }

    if (abs(level - pq_output) <= 1.5 / plot_height) {
        tint = vec3(1.0, 0.78, 0.12);
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
    vec2 padding = vec2(PAD * SCALE);
    vec2 panel_min = origin - padding;
    vec2 panel_max = origin + vec2(PREVIEW_VECTORSCOPE_EXTENT) + padding;

    if (outside_panel_bounds(px, panel_min, panel_max)) {
        return vec4(0.0);
    }

    vec2 local = px - origin;
    if (local.x < 0.0 || local.x >= PREVIEW_VECTORSCOPE_EXTENT ||
        local.y < 0.0 || local.y >= PREVIEW_VECTORSCOPE_EXTENT) {
        return vec4(0.0, 0.0, 0.0, 1.0);
    }

    vec2 unit = (local + 0.5) / PREVIEW_VECTORSCOPE_EXTENT;
    uvec2 bin = min(
        uvec2(unit * float(PREVIEW_VECTORSCOPE_SIZE)),
        uvec2(PREVIEW_VECTORSCOPE_SIZE - 1u)
    );
    uint index = bin.y * PREVIEW_VECTORSCOPE_SIZE + bin.x;
    uint base = index * PREVIEW_VECTORSCOPE_CHANNEL_COUNT;
    float count = float(vectorscope_bins[base + 0u]);
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
        vectorscope_bins[base + 1u],
        vectorscope_bins[base + 2u],
        vectorscope_bins[base + 3u]
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

bool glyph_pixel(uint glyph, vec2 p) {
    if (p.x < 0.0 || p.x >= CHAR_W || p.y < 0.0 || p.y >= CHAR_H) return false;
    uint bit = uint(p.y) * 3u + uint(p.x);
    return (glyph & (1u << bit)) != 0u;
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
    // abs(NaN) >= 0.0 is false, so the ternary is a NaN guard, not a
    // tautology: it renders NaN as 0.00 and keeps uint(NaN), whose value is
    // undefined, out of the conversion.
    //
    // Current callers only pass finite values (EV is clamped to ±64, PQ
    // rows to [0, 1]), kept as defense.
    float scaled = magnitude >= 0.0
        ? min(magnitude * 100.0 + 0.5, float(NUMBER_FIXED_MAX))
        : 0.0;
    return uint(scaled);
}

// A row is drawn as "LABEL:[-]<integer digits>.<two decimals>": four label
// characters including the colon, one for the minus sign when the number is
// negative, and three for the decimal point and the two decimals. Keeping them
// in one place prevents a format or label change from drifting between the
// width estimate and the glyphs actually drawn.
//
// The integer budget is a cap rather than a fixed count: the panel's bounds and
// its early-out are sized from the widest row they allow, so a longer number
// would be cut off rather than widen the panel. The largest row is a PQ code at
// 10000 nits.
const float LABEL_CHARACTERS = 4.0;
const float NUMBER_SIGN_CHARACTERS = 1.0;
const float NUMBER_INTEGER_CHARACTERS = 5.0;
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

// Resolve only the character covered by this fragment: each fragment pays for
// one glyph lookup instead of a whole row, so the width estimation cannot force
// per-pixel character loops. Drawing the row per fragment would repeat those
// lookups, which grows D3DCompiler's inliner exponentially.
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

// Draw a labeled row: "LABEL:value".
vec4 draw_row(float value, vec2 origin, vec2 px, ivec3 label) {
    float advance = CHAR_W + SPACING;
    float label_width = number_advance(LABEL_CHARACTERS);
    float width = label_width + number_width(value);
    vec2 local = (px - origin) / SCALE;

    if (local.x < 0.0 || local.x >= width ||
        local.y < 0.0 || local.y >= CHAR_H) {
        return vec4(0.0);
    }

    int character_index = int(floor(local.x / advance));
    int character;
    if (character_index == 0) {
        character = label.x;
    } else if (character_index == 1) {
        character = label.y;
    } else if (character_index == 2) {
        character = label.z;
    } else if (character_index == 3) {
        character = CH_COLON;
    } else {
        character = number_character(value, character_index - 4);
    }

    vec2 character_position = vec2(mod(local.x, advance), local.y);
    return glyph_pixel(get_glyph(character), character_position)
        ? vec4(1.0)
        : vec4(0.0);
}

// A row of the metrics panel. The table below is the single definition of the
// panel's contents: order, label, value and metering level. The row count, the
// panel's rows and the widest of them are all read back from it, so a row
// cannot be drawn without also being measured.
struct MetricsRow {
    ivec3 label;
    int value;
    int metering;
};

// The enable_metering level a row appears at: 1 measures the minimum and
// maximum, 2 adds the histogram and matrix zone statistics on top of them. The
// panel draws a row once the level has been reached, so this column is also
// what makes rows come and go.
//
// The matrix rows carry no separate "zone data has arrived" level: the pass
// that fills them is dispatched by the same enable_metering already required
// here, under the preview_metering that brings the panel out at all, so a
// drawn row always has data behind it.
const int METERING_NONE = 0;
const int METERING_MINMAX = 1;
const int METERING_FULL = 2;

// Where a row's number comes from. GLSL has no function pointers, so this is
// the one thing the table cannot carry: metrics_row_value turns the value tag
// back into the field it names. Keeping the number out of the table is also
// what keeps the reads lazy, so a hidden row is never read.
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

// A row's number in display units. A PQ row converts its code here rather than
// carrying a format flag out to the callers, so the glyphs and the width
// estimate both measure the number that is actually drawn. Together with the
// table and the VAL tags above, this is everything adding a row needs.
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

// The rest is machinery over the table: which rows are on screen, and where.
bool metrics_row_visible(int slot, int metering) {
    return metering >= METRICS_ROWS[slot].metering;
}

// The panel's rows are the table's rows whose level has been reached, in table
// order. Counting and indexing them instead of deriving both from the table's
// shape means the geometry, the drawing and the width estimate cannot disagree
// about which rows are on screen, wherever a row is inserted.
int metrics_row_count(int metering) {
    int count = 0;
    for (int slot = 0; slot < METRICS_ROW_SLOTS; slot++) {
        if (metrics_row_visible(slot, metering)) {
            count++;
        }
    }
    return count;
}

// Panel position to table slot.
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

// Draw one panel row, or nothing when its level has not been reached.
vec4 draw_metrics_row(int position, vec2 origin, vec2 px, int metering) {
    int slot = metrics_row_slot(position, metering);
    if (!metrics_row_visible(slot, metering)) {
        return vec4(0.0);
    }

    MetricsRow entry = METRICS_ROWS[slot];
    return draw_row(metrics_row_value(entry.value), origin, px, entry.label);
}

vec4 draw_metrics_panel(vec2 px) {
    // The widest row the panel is sized for: a full label and the widest
    // number the format allows.
    const float MAX_ROW_WIDTH =
        (LABEL_CHARACTERS + NUMBER_SIGN_CHARACTERS +
         NUMBER_INTEGER_CHARACTERS + NUMBER_DECIMAL_CHARACTERS) *
        (CHAR_W + SPACING);
    // The row count follows the metering level, which is a setting rather than
    // a per-frame quantity, so the panel's height and top only move when the
    // level does.
    int metering = int(enable_metering);
    int row_count = metrics_row_count(metering);
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
        o0.x + (MAX_ROW_WIDTH + PAD) * SCALE,
        metrics_bottom + (CHAR_H + PAD) * SCALE
    );

    if (outside_panel_bounds(px, panel_min, panel_max)) {
        return vec4(0.0);
    }

    // The panel's black backing hugs the widest row it is drawing, so the
    // estimate walks the same table through the same gate as the drawing. The
    // slot order does not matter here, only which rows are visible.
    float number_w = 0.0;
    for (int slot = 0; slot < METRICS_ROW_SLOTS; slot++) {
        if (!metrics_row_visible(slot, metering)) {
            continue;
        }

        MetricsRow entry = METRICS_ROWS[slot];
        number_w = max(number_w, number_width(metrics_row_value(entry.value)));
    }
    float max_w = number_advance(LABEL_CHARACTERS) + number_w;
    if (px.x > o0.x + (max_w + PAD) * SCALE) {
        return vec4(0.0);
    }

    vec4 r = vec4(0.0, 0.0, 0.0, 1.0);
    float row_stride = LINE_H * SCALE;
    int row = int(floor((px.y - o0.y) / row_stride));

    if (row >= 0 && row < row_count) {
        vec2 origin = o0 + vec2(0.0, float(row) * row_stride);
        r = max(
            r,
            draw_metrics_row(row, origin, px, metering)
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
