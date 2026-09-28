// SMPTE ST 2094-50 committee draft 2026-02-23 - Application #5
//
// Implements the normative Reference White Adaptive Tone Mapping metadata
// computation from Annex C.3.8, the headroom interpolation from Clause 6.2.5,
// max-component gain-curve construction from Clause 6.4, and the piecewise
// cubic Hermite gain curve from Clause 6.5. The generated gain curve is
// intentionally applied to ICtCp I instead of maxRGB, with Ct/Cp correction
// for the change in intensity as in st2094-10.glsl.
//
// mpv does not currently expose Application #5 alternate-image gain curves to
// user shaders. Consequently this shader implements the standardized compact
// reference-white mode. Baseline HDR headroom is inferred from available
// content metadata unless baseline_hdr_headroom is explicitly supplied.
//
// The input shall be relative-linear BT.2020 with 1.0 equal to
// reference_white. This matches the output of pq_inv.glsl and hlg_inv.glsl.

//!PARAM min_luma
//!TYPE float
0.0

//!PARAM max_luma
//!TYPE float
0.0

//!PARAM max_cll
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

//!PARAM max_pq_y
//!TYPE float
0.0

//!PARAM reference_white
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 1000.0
//!DESC HdrReferenceWhite in cd/m2
203.0

//!PARAM contrast_ratio
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 100000000.0
//!DESC Zero selects infinite contrast
1000.0

//!PARAM baseline_hdr_headroom
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM 6.0
//!DESC BaselineHdrHeadroom; negative derives it from content metadata
-1.0

//!PARAM target_hdr_headroom
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 6.0
//!DESC Targeted HDR headroom; zero targets SDR/reference white
0.0

//!PARAM chroma_correction_scaling
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC ICtCp chroma correction strength
1.0

//!HOOK OUTPUT
//!BIND HOOKED
//!DESC tone mapping (st2094-50, reference-white mode)

const float epsilon = 1e-6;
const float ln2 = 0.6931471805599453;

const float pq_m1 = 2610.0 / 4096.0 / 4.0;
const float pq_m2 = 2523.0 / 4096.0 * 128.0;
const float pq_c1 = 3424.0 / 4096.0;
const float pq_c2 = 2413.0 / 4096.0 * 32.0;
const float pq_c3 = 2392.0 / 4096.0 * 32.0;
const float pq_peak = 10000.0;

float pq_eotf(float x) {
    float t = pow(clamp(x, 0.0, 1.0), 1.0 / pq_m2);
    return pow(max(t - pq_c1, 0.0) / (pq_c2 - pq_c3 * t), 1.0 / pq_m1) * pq_peak;
}

float pq_eotf_inv(float x) {
    float t = pow(max(x, 0.0) / pq_peak, pq_m1);
    return pow((pq_c1 + pq_c2 * t) / (1.0 + pq_c3 * t), pq_m2);
}

vec3 pq_eotf(vec3 color) {
    return vec3(
        pq_eotf(color.r),
        pq_eotf(color.g),
        pq_eotf(color.b)
    );
}

vec3 pq_eotf_inv(vec3 color) {
    return vec3(
        pq_eotf_inv(color.r),
        pq_eotf_inv(color.g),
        pq_eotf_inv(color.b)
    );
}

vec3 RGB_to_XYZ(vec3 rgb) {
    return rgb * mat3(
        0.6369580483012914, 0.14461690358620832,  0.1688809751641721,
        0.2627002120112671, 0.6779980715188708,   0.05930171646986196,
        0.0,                0.028072693049087428, 1.060985057710791
    );
}

vec3 XYZ_to_RGB(vec3 xyz) {
    return xyz * mat3(
         1.716651187971268, -0.355670783776392, -0.25336628137366,
        -0.666684351832489,  1.616481236634939,  0.0157685458139111,
         0.017639857445311, -0.042770613257809,  0.942103121235474
    );
}

vec3 XYZ_to_LMS(vec3 xyz) {
    return xyz * mat3(
         0.3592832590121217,  0.6976051147779502, -0.0358915932320290,
        -0.1920808463704993,  1.1004767970374321,  0.0753748658519118,
         0.0070797844607479,  0.0748396662186362,  0.8433265453898765
    );
}

vec3 LMS_to_XYZ(vec3 lms) {
    return lms * mat3(
         2.0701522183894223, -1.3263473389671563,  0.2066510476294053,
         0.3647385209748072,  0.6805660249472273, -0.0453045459220347,
        -0.0497472075358123, -0.0492609666966131,  1.1880659249923042
    );
}

vec3 LMS_to_ICtCp(vec3 lms) {
    return lms * mat3(
         2048.0 / 4096.0,   2048.0 / 4096.0,    0.0 / 4096.0,
         6610.0 / 4096.0, -13613.0 / 4096.0, 7003.0 / 4096.0,
        17933.0 / 4096.0, -17390.0 / 4096.0, -543.0 / 4096.0
    );
}

vec3 ICtCp_to_LMS(vec3 ictcp) {
    return ictcp * mat3(
        1.0,  0.0086090370379328,  0.1110296250030260,
        1.0, -0.0086090370379328, -0.1110296250030260,
        1.0,  0.5600313357106791, -0.3206271749873189
    );
}

vec3 RGB_to_ICtCp(vec3 color) {
    color *= reference_white;
    color = RGB_to_XYZ(color);
    color = XYZ_to_LMS(color);
    color = pq_eotf_inv(color);
    return LMS_to_ICtCp(color);
}

vec3 ICtCp_to_RGB(vec3 color) {
    color = ICtCp_to_LMS(color);
    color = pq_eotf(color);
    color = LMS_to_XYZ(color);
    color = XYZ_to_RGB(color);
    return color / reference_white;
}

float get_source_peak() {
    float max_scl = max(max(scene_max_r, scene_max_g), scene_max_b);
    if (max_scl > 0.0)
        return max_scl;

    if (max_pq_y > 0.0)
        return pq_eotf(max_pq_y);

    if (max_cll > 0.0)
        return max_cll;

    if (max_luma > 0.0)
        return max_luma;

    return 1000.0;
}

float get_source_minimum() {
    if (min_luma > 0.0)
        return min_luma;

    return 0.001;
}

float get_baseline_headroom() {
    if (baseline_hdr_headroom >= 0.0)
        return clamp(baseline_hdr_headroom, 0.0, 6.0);

    float relative_peak = max(get_source_peak() / reference_white, 1.0);
    return clamp(log2(relative_peak), 0.0, 6.0);
}

// Annex C.3.8, Formulae (C.3) through (C.6). The returned vector is
// [control-point x, log2 gain y, derivative of log2 gain with respect to x].
vec3 reference_control_point(
    int index,
    float baseline_headroom,
    float alternate_headroom,
    float y_knee
) {
    const float highlight_compression = 0.65;
    const float control_point_count_minus_one = 7.0;

    float x_knee = 1.0;
    float x_max = exp2(baseline_headroom);
    float y_max = exp2(alternate_headroom);

    float x_mid = mix(x_knee, x_knee * y_max / y_knee, highlight_compression);
    float y_mid = mix(y_knee, y_max, highlight_compression);

    float ax = x_knee - 2.0 * x_mid + x_max;
    float bx = 2.0 * x_mid - 2.0 * x_knee;
    float cx = x_knee;

    float ay = y_knee - 2.0 * y_mid + y_max;
    float by = 2.0 * y_mid - 2.0 * y_knee;
    float cy = y_knee;

    float t = float(index) / control_point_count_minus_one;
    float x = ax * t * t + bx * t + cx;
    float y = ay * t * t + by * t + cy;
    float dx = 2.0 * ax * t + bx;
    float dy = 2.0 * ay * t + by;
    float tone_slope = dy / max(dx, epsilon);

    x = max(x, epsilon);
    y = max(y, epsilon);
    float gain = log2(y / x);
    float gain_slope = (x * tone_slope - y) / (ln2 * x * y);
    return vec3(x, gain, gain_slope);
}

// Clause 6.5, Formulae (11) and (12), for the eight control points generated
// by Annex C.3.8. The right-hand extension keeps the tone-mapped value fixed.
float reference_gain_curve(
    float x,
    float baseline_headroom,
    float alternate_headroom,
    float y_knee
) {
    vec3 left = reference_control_point(
        0,
        baseline_headroom,
        alternate_headroom,
        y_knee
    );

    if (x <= left.x)
        return left.y;

    for (int i = 0; i < 7; i++) {
        vec3 right = reference_control_point(
            i + 1,
            baseline_headroom,
            alternate_headroom,
            y_knee
        );

        if (x <= right.x) {
            float width = right.x - left.x;
            if (width <= epsilon)
                return right.y;

            float t = clamp((x - left.x) / width, 0.0, 1.0);
            float m0 = width * left.z;
            float m1 = width * right.z;
            float c3 = 2.0 * left.y + m0 - 2.0 * right.y + m1;
            float c2 = -3.0 * left.y + 3.0 * right.y - 2.0 * m0 - m1;
            return left.y + t * (m0 + t * (c2 + t * c3));
        }

        left = right;
    }

    return left.y + log2(left.x / max(x, epsilon));
}

vec2 chroma_correction(vec2 ctcp, float i1, float i2) {
    float r1 = i1 / max(i2, epsilon);
    float r2 = i2 / max(i1, epsilon);
    return ctcp * mix(1.0, min(r1, r2), chroma_correction_scaling);
}

// Clause 6.2.5, Formulae (2) through (5), specialized to the two alternate
// images generated by Annex C.3.8.
float reference_white_gain(
    float x,
    float baseline_headroom,
    float targeted_headroom
) {
    if (baseline_headroom <= epsilon)
        return 0.0;

    const float reference_headroom = 2.3004483674769109; // log2(1000 / 203)
    const float intermediate_headroom = 1.415037499278844; // log2(8 / 3)

    float adaptation = min(baseline_headroom / reference_headroom, 1.0);
    float alternate_headroom_0 = 0.0;
    float alternate_headroom_1 = intermediate_headroom * adaptation;
    float y_white_0 = 1.0 - 0.5 * adaptation;
    float y_white_1 = 1.0;

    float target = clamp(targeted_headroom, 0.0, baseline_headroom);
    float gain;

    if (target <= alternate_headroom_1) {
        float gain_0 = reference_gain_curve(
            x,
            baseline_headroom,
            alternate_headroom_0,
            y_white_0
        );
        float gain_1 = reference_gain_curve(
            x,
            baseline_headroom,
            alternate_headroom_1,
            y_white_1
        );
        float weight_1 = alternate_headroom_1 > epsilon
            ? target / alternate_headroom_1
            : 0.0;
        gain = mix(gain_0, gain_1, weight_1);
    } else {
        float gain_1 = reference_gain_curve(
            x,
            baseline_headroom,
            alternate_headroom_1,
            y_white_1
        );
        float weight_baseline = (target - alternate_headroom_1)
            / max(baseline_headroom - alternate_headroom_1, epsilon);
        gain = mix(gain_1, 0.0, clamp(weight_baseline, 0.0, 1.0));
    }

    return gain;
}

// Match linear.glsl's perceptual black-point mapping: the tone-mapped source
// black is lifted to the configured display black while the mapped peak is
// fixed.
float black_point_compensation(
    float i,
    float baseline_headroom,
    float targeted_headroom
) {
    float minimum_x = get_source_minimum() / reference_white;
    float minimum_gain = reference_white_gain(
        minimum_x,
        baseline_headroom,
        targeted_headroom
    );
    float mapped_minimum = minimum_x * exp2(minimum_gain) * reference_white;

    float target = clamp(targeted_headroom, 0.0, baseline_headroom);
    float mapped_peak = exp2(target) * reference_white;

    float ib = pq_eotf_inv(mapped_minimum);
    float ob = pq_eotf_inv(
        contrast_ratio > 0.0 ? reference_white / contrast_ratio : 0.0
    );
    float iw = pq_eotf_inv(mapped_peak);
    ib = min(ib, iw - epsilon);

    return ob + (i - ib) * (iw - ob) / (iw - ib);
}

// In this non-normative ICtCp variant, the max-component gain curve is driven
// by decoded ICtCp intensity and the mapped intensity is encoded back to PQ
// before correcting Ct/Cp.
vec3 reference_white_tone_map(
    vec3 ictcp,
    float baseline_headroom,
    float targeted_headroom
) {
    float x = pq_eotf(ictcp.x) / reference_white;
    float gain = reference_white_gain(x, baseline_headroom, targeted_headroom);
    float mapped_x = x * exp2(gain);
    float i2 = pq_eotf_inv(mapped_x * reference_white);
    i2 = black_point_compensation(
        i2,
        baseline_headroom,
        targeted_headroom
    );
    vec2 ctcp2 = chroma_correction(ictcp.yz, ictcp.x, i2);
    return vec3(i2, ctcp2);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    float baseline = get_baseline_headroom();
    color.rgb = RGB_to_ICtCp(color.rgb);
    color.rgb = reference_white_tone_map(
        color.rgb,
        baseline,
        target_hdr_headroom
    );
    color.rgb = ICtCp_to_RGB(color.rgb);
    return color;
}
