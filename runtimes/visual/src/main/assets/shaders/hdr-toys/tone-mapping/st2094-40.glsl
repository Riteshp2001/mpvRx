// SMPTE ST 2094-40:2020 - Dynamic Metadata for Color Volume Transform - Application #4
// Scene-based tone mapping using the metadata-defined N-th order Bezier curve.
// This implementation applies the curve to ICtCp I instead of maxRGB, then
// corrects Ct/Cp for the change in intensity as in st2094-10.glsl.
//
// mpv automatically supplies scene_max_r/g/b in cd/m2 when HDR10+ metadata is
// available, but it does not expose the HDR10+ knee point or Bezier anchors to
// user shaders. By default, a curve is generated from the available scene
// statistics. Set use_metadata_curve=1 and provide decoded knee/anchor values
// with glsl-shader-opts to apply a metadata curve directly. Raw KneePoint
// values are divided by 4095 and raw BezierCurveAnchors values by 1023.

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
//!DESC Zero selects infinite contrast
1000.0

//!PARAM distribution_max_rgb
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC DistributionMaxRGBPercentiles[Omega]; zero means unavailable
0.0

//!PARAM use_metadata_curve
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
//!DESC Use explicitly supplied KneePoint and BezierCurveAnchors
0

//!PARAM knee_point_x
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC KneePoint Ks
0.0

//!PARAM knee_point_y
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC KneePoint KF
0.0

//!PARAM bezier_anchor_count
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 15
//!DESC Intermediate anchor count; Bezier order is count plus one
0

//!PARAM bezier_anchor_1
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_2
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_3
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_4
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_5
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_6
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_7
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_8
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_9
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_10
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_11
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_12
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_13
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_14
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM bezier_anchor_15
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM highlight_compression
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC Automatic shoulder strength; 0 is weakest and 1 is strongest
0.35

//!PARAM chroma_correction_scaling
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC ICtCp chroma correction strength
1.0

//!BUFFER METERED
//!VAR float metered_avg_i
//!STORAGE

//!HOOK OUTPUT
//!BIND HOOKED
//!SAVE AVG
//!COMPONENTS 1
//!WIDTH 128
//!HEIGHT 128
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 128)

const vec3 y_coef = vec3(0.2627002120112671, 0.6779980715188708, 0.05930171646986196);

const float m1 = 2610.0 / 4096.0 / 4.0;
const float m2 = 2523.0 / 4096.0 * 128.0;
const float c1 = 3424.0 / 4096.0;
const float c2 = 2413.0 / 4096.0 * 32.0;
const float c3 = 2392.0 / 4096.0 * 32.0;
const float pw = 10000.0;

float pq_eotf_inv(float x) {
    float t = pow(max(x, 0.0) / pw, m1);
    return pow((c1 + c2 * t) / (1.0 + c3 * t), m2);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    float l = dot(color.rgb, y_coef) * reference_white;
    return vec4(pq_eotf_inv(l), vec3(0.0));
}

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 64)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 32)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 16)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 8)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 4)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!SAVE AVG
//!WIDTH AVG.w 2 /
//!HEIGHT AVG.h 2 /
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 2)
vec4 hook() { return AVG_tex(AVG_pos); }

//!HOOK OUTPUT
//!BIND AVG
//!BIND METERED
//!SAVE AVG
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 1 1
//!WHEN use_metadata_curve 0 = avg_pq_y 0 = * scene_avg 0 = *
//!DESC tone mapping (st2094-40, average, 1)

void hook() {
    metered_avg_i = AVG_tex(AVG_pos).x;
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND METERED
//!DESC tone mapping (st2094-40)

const float pq_m1 = 2610.0 / 4096.0 / 4.0;
const float pq_m2 = 2523.0 / 4096.0 * 128.0;
const float pq_c1 = 3424.0 / 4096.0;
const float pq_c2 = 2413.0 / 4096.0 * 32.0;
const float pq_c3 = 2392.0 / 4096.0 * 32.0;
const float pq_peak = 10000.0;
const float epsilon = 1e-6;

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

    if (distribution_max_rgb > 0.0)
        return distribution_max_rgb * pq_peak;

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

float get_source_average() {
    if (scene_avg > 0.0)
        return scene_avg;

    if (avg_pq_y > 0.0)
        return pq_eotf(avg_pq_y);

    if (metered_avg_i > 0.0)
        return pq_eotf(clamp(metered_avg_i, 0.1, 0.5));

    // MaxFALL is the maximum frame-average level of the whole stream, not
    // the average of the current scene. Using it here systematically places
    // the fallback knee too high for ordinary HDR10 sources.
    return 0.0;
}

float get_bezier_anchor(int index, int order, bool automatic, float auto_compression) {
    if (index <= 0)
        return 0.0;
    if (index >= order)
        return 1.0;

    if (automatic) {
        // Sample a monotonic rational shoulder into Bernstein control points.
        // With compression=0 all intermediate anchors are 1 (the old, weak
        // highlight compression); with compression=1 they follow the identity
        // line and distribute highlights most evenly across the output range.
        float x = float(index) / float(order);
        float denominator = mix(x, 1.0, auto_compression);
        return x / max(denominator, epsilon);
    }

    if (index ==  1) return bezier_anchor_1;
    if (index ==  2) return bezier_anchor_2;
    if (index ==  3) return bezier_anchor_3;
    if (index ==  4) return bezier_anchor_4;
    if (index ==  5) return bezier_anchor_5;
    if (index ==  6) return bezier_anchor_6;
    if (index ==  7) return bezier_anchor_7;
    if (index ==  8) return bezier_anchor_8;
    if (index ==  9) return bezier_anchor_9;
    if (index == 10) return bezier_anchor_10;
    if (index == 11) return bezier_anchor_11;
    if (index == 12) return bezier_anchor_12;
    if (index == 13) return bezier_anchor_13;
    if (index == 14) return bezier_anchor_14;
    return bezier_anchor_15;
}

// Equation (1), evaluated from the numerically safer end of the Bernstein
// basis. This avoids both 0^0 and loss of the endpoint for high-order curves.
float bezier_curve(float t, int order, bool automatic, float auto_compression) {
    t = clamp(t, 0.0, 1.0);
    if (t <= 0.0)
        return 0.0;
    if (t >= 1.0)
        return 1.0;

    float u = 1.0 - t;
    float value = 0.0;

    if (t <= 0.5) {
        float basis = pow(u, float(order));
        for (int i = 1; i <= 16; i++) {
            if (i > order)
                break;
            basis *= float(order - i + 1) / float(i) * t / u;
            value += basis * get_bezier_anchor(i, order, automatic, auto_compression);
        }
    } else {
        float basis = pow(t, float(order));
        value = basis;
        for (int i = 15; i >= 0; i--) {
            if (i >= order)
                continue;
            basis *= float(i + 1) / float(order - i) * u / t;
            value += basis * get_bezier_anchor(i, order, automatic, auto_compression);
        }
    }

    return clamp(value, 0.0, 1.0);
}

// Fallback used when mpv cannot expose the HDR10+ OOTF. The same absolute
// luminance is preserved below the knee; only highlights above it are
// compressed into the target display range. The knee is selected in the
// perceptually uniform PQ domain and then converted back to absolute cd/m2.
vec4 automatic_curve(float source_peak) {
    const float knee_default = 0.5;
    const float knee_maximum = 0.7;

    float source_average = get_source_average();
    float target_peak_pq = pq_eotf_inv(reference_white);
    float target_knee_pq = knee_default * target_peak_pq;
    if (source_average > 0.0)
        target_knee_pq = max(
            target_knee_pq,
            min(2.0 * pq_eotf_inv(source_average), knee_maximum * target_peak_pq)
        );
    float target_knee = pq_eotf(target_knee_pq);

    // kf / ks = source_peak / reference_white, therefore the complete
    // normalization and curve have unit gain in absolute cd/m2 below ks.
    float ks = clamp(target_knee / source_peak, epsilon, 1.0 - epsilon);
    float kf = clamp(target_knee / reference_white, epsilon, 1.0 - epsilon);
    // Ten is the highest order permitted by ApplicationVersion 1. Exact
    // tangent matching is intentionally traded for useful highlight
    // separation; matching the unit-gain linear slope made the old curve race
    // towards 1.0 and visually clip most values above the knee.
    const int order = 10;
    return vec4(ks, kf, float(order), clamp(highlight_compression, 0.0, 1.0));
}

// Equations (3), (4), and (5).
float tone_mapping_curve(float s, float source_peak) {
    bool automatic = use_metadata_curve == 0u;
    float ks;
    float kf;
    float auto_compression;
    int order;

    if (automatic) {
        vec4 curve = automatic_curve(source_peak);
        ks = curve.x;
        kf = curve.y;
        order = int(curve.z);
        auto_compression = curve.w;
    } else {
        ks = clamp(knee_point_x, 0.0, 1.0);
        kf = clamp(knee_point_y, 0.0, 1.0);
        order = int(bezier_anchor_count) + 1;
        auto_compression = 0.0;
    }

    s = clamp(s, 0.0, 1.0);

    if (ks <= 0.0)
        return kf + (1.0 - kf) * bezier_curve(s, order, automatic, auto_compression);

    if (ks >= 1.0)
        return kf * s;

    if (s < ks)
        return (kf / ks) * s;

    float t = (s - ks) / (1.0 - ks);
    return kf + (1.0 - kf) * bezier_curve(t, order, automatic, auto_compression);
}

vec2 chroma_correction(vec2 ctcp, float i1, float i2) {
    float r1 = i1 / max(i2, epsilon);
    float r2 = i2 / max(i1, epsilon);
    return ctcp * mix(1.0, min(r1, r2), chroma_correction_scaling);
}

// Match linear.glsl's perceptual black-point mapping: the source mastering
// black is lifted to the configured display black while the mapped peak is
// fixed.
float black_point_compensation(float i, float source_peak) {
    float minimum_s = clamp(get_source_minimum() / source_peak, 0.0, 1.0);
    float mapped_minimum = tone_mapping_curve(minimum_s, source_peak)
        * reference_white;
    float mapped_peak = tone_mapping_curve(1.0, source_peak) * reference_white;

    float ib = pq_eotf_inv(mapped_minimum);
    float ob = pq_eotf_inv(
        contrast_ratio > 0.0 ? reference_white / contrast_ratio : 0.0
    );
    float iw = pq_eotf_inv(mapped_peak);
    ib = min(ib, iw - epsilon);

    return ob + (i - ib) * (iw - ob) / (iw - ib);
}

// Apply the ST 2094-40 scalar curve to ICtCp intensity. This is intentionally
// different from the maxRGB application described in Annex B.4.
vec3 tone_mapping(vec3 ictcp, float source_peak) {
    float source_i = pq_eotf(ictcp.x);
    float s = clamp(source_i / source_peak, 0.0, 1.0);
    float mapped_i = tone_mapping_curve(s, source_peak) * reference_white;
    float i2 = pq_eotf_inv(mapped_i);
    i2 = black_point_compensation(i2, source_peak);
    vec2 ctcp2 = chroma_correction(ictcp.yz, ictcp.x, i2);
    return vec3(i2, ctcp2);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);

    float source_peak = max(get_source_peak(), reference_white);
    color.rgb = RGB_to_ICtCp(color.rgb);
    color.rgb = tone_mapping(color.rgb, source_peak);
    color.rgb = ICtCp_to_RGB(color.rgb);
    return color;
}
