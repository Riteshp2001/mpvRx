// T/UWA 005.1-2024 (V1.3) HDR Vivid display adaptation
//
// Implements the single-window HDR and SDR curve construction in Clauses 9
// and 10: the PQ-domain base curve, linear/cubic spline transitions, and the
// optional YCbCr colour-saturation correction. The resulting scalar curve is
// deliberately applied to ICtCp I instead of the standard's maxRGB gain, with
// Ct/Cp correction for the intensity change. Clause 10.4 transfer encoding is
// left to the transfer-function shader that follows this file.
//
// mpv exposes HDR Vivid frame side data to neither user-shader parameters nor
// textures. Set use_metadata_statistics/use_metadata_curve and supply decoded
// metadata with glsl-shader-opts to reproduce a selected metadata parameter
// set. If a stream contains two target-display parameter sets, the caller must
// select the appropriate set before passing it to this shader.
//
// Parameters below are decoded values, not raw bitstream integers. Clause 7.4
// defines the conversions: MaxRGB/target/spline-th use raw/4095; m_p uses
// raw*10/16383; m_m and m_n use raw/10; m_a uses raw/1023; m_b and spline
// deltas use raw*0.25/1023; and base_delta uses raw/127. For spline mode 0,
// spline_mb is the high six bits divided by 63 and spline_1_base_offset is the
// low two bits times 0.1/3. Other spline_mb values use raw*1.1/255.
//
// Without decoded statistics, a 128x128 meter estimates AverageMaxRGB as
// specified by Annex A.4. MaximumMaxRGB comes from mpv's available scene/static
// metadata, while the unavailable P90-P10 range is conservatively estimated
// as maximum minus minimum. This statistics fallback is intentionally not a
// claim that HDR Vivid metadata has been reconstructed.
//
// Input shall be relative-linear BT.2020 with 1.0 equal to reference_white,
// matching pq_inv.glsl and hlg_inv.glsl.

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
//!DESC Target display maximum luminance in cd/m2
203.0

//!PARAM contrast_ratio
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 100000000.0
//!DESC Zero selects a zero target black level
1000.0

//!PARAM sdr_display
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
//!DESC Use Clause 10 HDR-to-SDR defaults instead of Clause 9 HDR defaults
1

//!PARAM use_metadata_statistics
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
//!DESC Use decoded Minimum/Average/Variance/MaximumMaxRGB values
0

//!PARAM minimum_maxrgb_pq
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM average_maxrgb_pq
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM variance_maxrgb_pq
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC P90 minus P10 maxRGB in the PQ domain
0.0

//!PARAM maximum_maxrgb_pq
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM use_metadata_curve
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
//!DESC Use one preselected decoded tone-mapping parameter set
0

//!PARAM targeted_display_pq
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC Metadata targeted_system_display_maximum_luminance
0.0

//!PARAM base_enable
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM base_m_p
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 10.0
4.0

//!PARAM base_m_m
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 6.3
2.4

//!PARAM base_m_a
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM base_m_b
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.25
0.0

//!PARAM base_m_n
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 6.3
1.0

//!PARAM base_k1
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM base_k2
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM base_k3
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM base_delta_mode
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 7
3

//!PARAM base_delta
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM spline_enable
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM spline_count
//!TYPE uint
//!MINIMUM 1
//!MAXIMUM 2
1

//!PARAM spline_1_mode
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 3
0

//!PARAM spline_1_mb
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.1
1.0

//!PARAM spline_1_th
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.25

//!PARAM spline_1_delta1
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.25
0.15

//!PARAM spline_1_delta2
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.25
0.075

//!PARAM spline_1_strength
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM 1.0
0.0

//!PARAM spline_1_base_offset
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.1
0.0

//!PARAM spline_2_mode
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 3
3

//!PARAM spline_2_mb
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.1
0.0

//!PARAM spline_2_th
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.6

//!PARAM spline_2_delta1
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.25
0.1

//!PARAM spline_2_delta2
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 0.25
0.1

//!PARAM spline_2_strength
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM 1.0
0.0

//!PARAM color_saturation_enable
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM color_saturation_count
//!TYPE uint
//!MINIMUM 0
//!MAXIMUM 7
0

//!PARAM color_saturation_gain_0
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
//!DESC Decoded color_saturation_enable_gain[0] divided by 128
1.0

//!PARAM color_saturation_gain_1
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 2.0
//!DESC High six bits of color_saturation_enable_gain[1] divided by 128
0.0

//!PARAM color_saturation_power
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 8.0
//!DESC Two to the low two bits of color_saturation_enable_gain[1]
1.0

//!PARAM chroma_correction_scaling
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
//!DESC ICtCp Ct/Cp correction strength
1.0

//!BUFFER UWA_METERED
//!VAR float metered_average_maxrgb
//!STORAGE

//!HOOK OUTPUT
//!BIND HOOKED
//!SAVE UWA_AVG
//!COMPONENTS 1
//!WIDTH 128
//!HEIGHT 128
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 128)

vec4 hook() {
    vec3 rgb = max(HOOKED_tex(HOOKED_pos).rgb, 0.0);
    float maxrgb = max(max(rgb.r, rgb.g), rgb.b) * reference_white;
    return vec4(maxrgb, vec3(0.0));
}

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 64)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 32)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 16)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 8)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 4)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!SAVE UWA_AVG
//!WIDTH UWA_AVG.w 2 /
//!HEIGHT UWA_AVG.h 2 /
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 2)
vec4 hook() { return UWA_AVG_tex(UWA_AVG_pos); }

//!HOOK OUTPUT
//!BIND UWA_AVG
//!BIND UWA_METERED
//!SAVE UWA_AVG
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 1 1
//!WHEN use_metadata_statistics 0 =
//!DESC tone mapping (UWA 005.1, AverageMaxRGB, 1)

void hook() {
    metered_average_maxrgb = UWA_AVG_tex(UWA_AVG_pos).x;
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND UWA_METERED
//!DESC tone mapping (T/UWA 005.1-2024 HDR Vivid)

const float epsilon = 1e-6;
const float pq_m1 = 2610.0 / 4096.0 / 4.0;
const float pq_m2 = 2523.0 / 4096.0 * 128.0;
const float pq_c1 = 3424.0 / 4096.0;
const float pq_c2 = 2413.0 / 4096.0 * 32.0;
const float pq_c3 = 2392.0 / 4096.0 * 32.0;
const float pq_peak = 10000.0;

struct BaseCurve {
    float p;
    float m;
    float a;
    float b;
    float n;
    float k1;
    float k2;
    float k3;
};

struct CubicSpline {
    float x1;
    float x2;
    float x3;
    float y1;
    float y2;
    float y3;
    float d1;
    float d2;
    float d3;
    uint mode;
    bool valid;
};

struct ToneCurve {
    BaseCurve base;
    float toe_end;
    float toe_slope;
    float toe_offset;
    CubicSpline low;
    CubicSpline high;
};

float pq_eotf(float x) {
    float t = pow(clamp(x, 0.0, 1.0), 1.0 / pq_m2);
    return pow(max(t - pq_c1, 0.0) / (pq_c2 - pq_c3 * t), 1.0 / pq_m1) * pq_peak;
}

float pq_eotf_inv(float x) {
    float t = pow(max(x, 0.0) / pq_peak, pq_m1);
    return pow((pq_c1 + pq_c2 * t) / (1.0 + pq_c3 * t), pq_m2);
}

vec3 pq_eotf(vec3 x) {
    return vec3(pq_eotf(x.r), pq_eotf(x.g), pq_eotf(x.b));
}

vec3 pq_eotf_inv(vec3 x) {
    return vec3(pq_eotf_inv(x.r), pq_eotf_inv(x.g), pq_eotf_inv(x.b));
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

vec2 chroma_correction(vec2 ctcp, float source_i, float mapped_i) {
    float compression = min(
        source_i / max(mapped_i, epsilon),
        mapped_i / max(source_i, epsilon)
    );
    return ctcp * mix(
        1.0, clamp(compression, 0.0, 1.0), chroma_correction_scaling
    );
}

float metadata_target(float target_max) {
    return targeted_display_pq > 0.0 ? targeted_display_pq : target_max;
}

float mastering_peak() {
    if (max_luma > 0.0)
        return max_luma;
    if (max_cll > 0.0)
        return max_cll;
    return 4000.0;
}

float statistics_minimum() {
    if (use_metadata_statistics != 0u)
        return clamp(minimum_maxrgb_pq, 0.0, 1.0);
    return min_luma > 0.0 ? pq_eotf_inv(min_luma) : 0.0;
}

float statistics_average() {
    if (use_metadata_statistics != 0u)
        return clamp(average_maxrgb_pq, 0.0, 1.0);
    return pq_eotf_inv(max(metered_average_maxrgb, 0.0));
}

float statistics_maximum() {
    if (use_metadata_statistics != 0u)
        return clamp(maximum_maxrgb_pq, 0.0, 1.0);

    float scene_peak = max(max(scene_max_r, scene_max_g), scene_max_b);
    if (scene_peak > 0.0)
        return pq_eotf_inv(scene_peak);
    if (max_pq_y > 0.0)
        return clamp(max_pq_y, 0.0, 1.0);
    if (max_cll > 0.0)
        return pq_eotf_inv(max_cll);
    if (max_luma > 0.0)
        return pq_eotf_inv(max_luma);
    return pq_eotf_inv(1000.0);
}

float statistics_variance(float minimum_value, float maximum_value) {
    if (use_metadata_statistics != 0u)
        return clamp(variance_maxrgb_pq, 0.0, 1.0);
    return max(maximum_value - minimum_value, 0.0);
}

float maximum_luminance_correction(
    float target_max, float minimum_value, float average_value,
    float maximum_value
) {
    float variance_value = statistics_variance(minimum_value, maximum_value);
    float max_ref_display = pq_eotf_inv(mastering_peak());
    float max1 = 0.2 * maximum_value + 0.8 * average_value
        + 0.4 * variance_value;
    float lower = min(0.5081, max_ref_display);
    return max(clamp(max1, lower, max_ref_display), target_max);
}

float base_shape(float x, BaseCurve curve) {
    float xn = pow(max(x, 0.0), max(curve.n, epsilon));
    float denominator = (curve.k1 * curve.p - curve.k2) * xn + curve.k3;
    float q = curve.p * xn / max(denominator, epsilon);
    return pow(max(q, 0.0), max(curve.m, epsilon));
}

float base_value(float x, BaseCurve curve) {
    return curve.a * base_shape(x, curve) + curve.b;
}

float base_slope(float x, BaseCurve curve) {
    float safe_x = max(x, epsilon);
    float n = max(curve.n, epsilon);
    float m = max(curve.m, epsilon);
    float xn = pow(safe_x, n);
    float denominator = (curve.k1 * curve.p - curve.k2) * xn + curve.k3;
    denominator = max(denominator, epsilon);
    float q = max(curve.p * xn / denominator, epsilon);
    float q_slope = curve.p * n * pow(safe_x, n - 1.0) * curve.k3
        / (denominator * denominator);
    return max(curve.a * m * pow(q, m - 1.0) * q_slope, 0.0);
}

BaseCurve default_base_curve(
    float target_min, float target_max, float max_lum, float average_value
) {
    BaseCurve curve;
    float average_weight;
    float p0;
    float maximum_delta;
    if (sdr_display != 0u) {
        average_weight = clamp((average_value - 0.1) / 0.5, 0.0, 1.0);
        p0 = mix(6.0, 3.5, average_weight);
        float maximum_weight = clamp((max_lum - 0.67) / 0.08, 0.0, 1.0);
        maximum_delta = mix(0.3, 0.6, maximum_weight);
    } else {
        average_weight = clamp((average_value - 0.3) / 0.3, 0.0, 1.0);
        p0 = mix(4.0, 3.5, average_weight);
        float maximum_weight = clamp((max_lum - 0.75) / 0.15, 0.0, 1.0);
        maximum_delta = 0.6 * maximum_weight;
    }

    curve.p = p0 + maximum_delta;
    curve.m = 2.4;
    curve.a = 1.0;
    curve.b = target_min;
    curve.n = 1.0;
    curve.k1 = 1.0;
    curve.k2 = 1.0;
    curve.k3 = 1.0;
    curve.a = (target_max - target_min)
        / max(base_shape(max_lum, curve), epsilon);
    return curve;
}

float adaptation_threshold(float p) {
    if (p < 2.5)
        return 0.990;
    if (p < 3.5)
        return 0.990 - (p - 2.5) * 0.111;
    if (p < 4.5)
        return 0.879 - (p - 3.5) * 0.102;
    if (p < 7.5)
        return 0.777 - (p - 4.5) * 0.079;
    return 0.540;
}

float adaptation_weight(BaseCurve curve, float target_max, float max_lum) {
    BaseCurve threshold_curve = curve;
    threshold_curve.a = adaptation_threshold(curve.p);
    threshold_curve.b = 0.0;
    float h = base_value(max_lum, threshold_curve) / max(max_lum, epsilon);
    float weight = (target_max / max(max_lum, epsilon) - h)
        / max(1.0 - h, epsilon);
    return clamp(weight, 0.0, 1.0);
}

bool adapt_metadata_curve(float target_max) {
    if (use_metadata_curve == 0u || base_enable == 0u)
        return false;
    if (base_delta_mode == 3u)
        return false;
    return abs(metadata_target(target_max) - target_max) > 0.5 / 4095.0;
}

BaseCurve selected_base_curve(
    float target_min, float target_max, float max_lum, float average_value
) {
    BaseCurve generated = default_base_curve(
        target_min, target_max, max_lum, average_value
    );
    if (use_metadata_curve == 0u || base_enable == 0u)
        return generated;

    BaseCurve curve;
    curve.p = base_m_p;
    curve.m = base_m_m;
    curve.a = base_m_a;
    curve.b = base_m_b;
    curve.n = base_m_n;
    curve.k1 = base_k1;
    curve.k2 = base_k2;
    curve.k3 = base_k3;

    if (!adapt_metadata_curve(target_max))
        return curve;

    float target = max(metadata_target(target_max), epsilon);
    float luminance_difference = abs(pq_eotf(target_max) - pq_eotf(target));
    float delta_weight = clamp(base_delta * sqrt(luminance_difference / 100.0), 0.0, 1.0);

    if (base_delta_mode == 1u || base_delta_mode == 5u) {
        curve.p = mix(curve.p, generated.p, delta_weight);
        curve.m = mix(curve.m, generated.m, delta_weight);
        curve.n = mix(curve.n, generated.n, delta_weight);
        curve.k1 = mix(curve.k1, generated.k1, delta_weight);
        curve.k2 = mix(curve.k2, generated.k2, delta_weight);
        curve.k3 = mix(curve.k3, generated.k3, delta_weight);
        curve.b = target_min;
        curve.a = 1.0;
        curve.a = (target_max - target_min)
            / max(base_shape(max_lum, curve), epsilon);
    } else {
        float signed_delta = (base_delta_mode == 2u || base_delta_mode == 6u)
            ? -base_delta : base_delta;
        curve.p = clamp(
            curve.p + signed_delta * sqrt(luminance_difference / 100.0),
            3.0, 7.5
        );
        float scale = (target_max - target_min) / target;
        curve.a *= scale;
        curve.b *= scale;
    }
    return curve;
}

uint spline_mode(int index) {
    return index == 0 ? spline_1_mode : spline_2_mode;
}

float spline_mb(int index) {
    return index == 0 ? spline_1_mb : spline_2_mb;
}

float spline_th(int index) {
    return index == 0 ? spline_1_th : spline_2_th;
}

float spline_delta1(int index) {
    return index == 0 ? spline_1_delta1 : spline_2_delta1;
}

float spline_delta2(int index) {
    return index == 0 ? spline_1_delta2 : spline_2_delta2;
}

float spline_strength(int index) {
    return index == 0 ? spline_1_strength : spline_2_strength;
}

CubicSpline invalid_spline() {
    CubicSpline spline;
    spline.x1 = 0.0;
    spline.x2 = 0.0;
    spline.x3 = 0.0;
    spline.y1 = 0.0;
    spline.y2 = 0.0;
    spline.y3 = 0.0;
    spline.d1 = 0.0;
    spline.d2 = 0.0;
    spline.d3 = 0.0;
    spline.mode = 3u;
    spline.valid = false;
    return spline;
}

CubicSpline make_spline(
    float x1, float x2, float x3,
    float y1, float y2, float y3,
    float d1, float d3, uint mode
) {
    CubicSpline spline = invalid_spline();
    float h1 = x2 - x1;
    float h2 = x3 - x2;
    if (h1 <= epsilon || h2 <= epsilon || y3 <= y1)
        return spline;

    spline.x1 = x1;
    spline.x2 = x2;
    spline.x3 = x3;
    spline.y1 = y1;
    spline.y2 = clamp(y2, y1, y3);
    spline.y3 = y3;
    spline.d1 = d1;
    spline.d3 = d3;
    // Equations (49)/(67), expressed as the shared middle derivative of
    // two C2-continuous cubic Hermite intervals.
    spline.d2 = (
        3.0 * (h2 * h2 * (spline.y2 - y1)
            + h1 * h1 * (y3 - spline.y2))
        - h1 * h2 * (h1 * spline.d3 + h2 * spline.d1)
    ) / (2.0 * h1 * h2 * (h1 + h2));
    spline.mode = mode;
    spline.valid = true;
    return spline;
}

float hermite(
    float x, float x0, float x1,
    float y0, float y1, float d0, float d1
) {
    float h = max(x1 - x0, epsilon);
    float t = clamp((x - x0) / h, 0.0, 1.0);
    float t2 = t * t;
    float t3 = t2 * t;
    return (2.0 * t3 - 3.0 * t2 + 1.0) * y0
        + (t3 - 2.0 * t2 + t) * h * d0
        + (-2.0 * t3 + 3.0 * t2) * y1
        + (t3 - t2) * h * d1;
}

float evaluate_spline(float x, CubicSpline spline) {
    if (x < spline.x2)
        return hermite(
            x, spline.x1, spline.x2,
            spline.y1, spline.y2, spline.d1, spline.d2
        );
    return hermite(
        x, spline.x2, spline.x3,
        spline.y2, spline.y3, spline.d2, spline.d3
    );
}

void default_toe(float average_value, out float threshold, out float slope) {
    float weight = clamp((average_value - 0.3) / 0.3, 0.0, 1.0);
    threshold = sdr_display != 0u ? 0.0 : mix(0.25, 0.10, weight);
    slope = sdr_display != 0u
        ? mix(1.0, 0.9, weight)
        : mix(1.0, 0.96, weight);
}

BaseCurve adjust_base_offset(
    BaseCurve curve, float target_max, float max_lum
) {
    if (!adapt_metadata_curve(target_max) || spline_enable == 0u
        || spline_1_mode != 0u)
        return curve;

    float threshold = adaptation_threshold(curve.p);
    if (curve.a > threshold) {
        float weight = adaptation_weight(curve, target_max, max_lum);
        curve.b *= 1.0 - weight;
    }

    float x3 = clamp(
        spline_1_th + spline_1_delta1 + spline_1_delta2, 0.0, 1.0
    );
    float y3 = base_value(x3, curve);
    if (y3 > x3 && y3 > 0.0 && base_delta_mode != 2u
        && base_delta_mode != 3u && base_delta_mode != 6u)
        curve.b -= y3 - x3;
    return curve;
}

CubicSpline build_low_spline(
    BaseCurve curve, float target_max, float max_lum, float average_value,
    out float toe_end, out float toe_slope, out float toe_offset
) {
    bool metadata_low = use_metadata_curve != 0u && spline_enable != 0u
        && spline_1_mode == 0u;
    bool make_low = use_metadata_curve == 0u || spline_enable == 0u
        || metadata_low;

    default_toe(average_value, toe_end, toe_slope);
    // Preserve the base curve's target black through the toe.  Anchoring the
    // toe at zero would otherwise cancel contrast_ratio at exactly x = 0.
    toe_offset = max(curve.b, 0.0);
    if (metadata_low) {
        toe_end = spline_1_th;
        toe_slope = spline_1_mb;
        toe_offset = max(spline_1_base_offset, toe_offset);
    }

    if (adapt_metadata_curve(target_max)) {
        float threshold = adaptation_threshold(curve.p);
        if (curve.a > threshold) {
            float weight = adaptation_weight(curve, target_max, max_lum);
            toe_slope = clamp(
                toe_slope + (1.0 - toe_slope) * weight,
                toe_slope, 1.0
            );
            toe_end = clamp(
                toe_end + (max_lum - toe_end) * weight,
                toe_end, 1.0
            );
        }
    }

    toe_end = clamp(toe_end, 0.0, 1.0);
    if (!make_low)
        return invalid_spline();

    float delta1 = metadata_low ? spline_1_delta1 : 0.15;
    float delta2 = metadata_low ? spline_1_delta2 : 0.075;
    float strength = metadata_low ? spline_1_strength : 0.0;
    float x1 = toe_end;
    float x2 = min(x1 + delta1, 1.0 - epsilon);
    float x3 = min(x2 + delta2, 1.0);
    float y1 = toe_slope * x1 + toe_offset;
    float y3 = base_value(x3, curve);

    if (metadata_low && y3 > x3 && base_delta_mode != 2u
        && base_delta_mode != 3u && base_delta_mode != 6u)
        y3 = x3;

    float position = (x2 - x1) / max(x3 - x1, epsilon);
    float y2 = sdr_display != 0u && !metadata_low
        ? base_value(x2, curve)
        : mix(y1, y3, position) + (y3 - y1) * strength * 0.5;
    if (metadata_low && y2 > x2 && base_delta_mode != 2u
        && base_delta_mode != 3u && base_delta_mode != 6u)
        y2 = x2;

    return make_spline(
        x1, x2, x3, y1, y2, y3,
        toe_slope, base_slope(x3, curve), 0u
    );
}

CubicSpline build_high_spline(
    BaseCurve curve, CubicSpline low, float target_max, int index
) {
    if (index < 0)
        return invalid_spline();

    uint mode = spline_mode(index);
    float x1 = spline_th(index);
    float x2 = x1 + spline_delta1(index);
    float x3 = x2 + spline_delta2(index);
    if (low.valid && x3 < low.x3)
        return invalid_spline();
    if (low.valid && x1 < low.x3) {
        x1 = low.x3;
        x2 = 0.5 * (x1 + x3);
    }
    x1 = clamp(x1, 0.0, 1.0);
    x2 = clamp(x2, x1, 1.0);
    x3 = clamp(x3, x2, 1.0);

    float y1 = base_value(x1, curve);
    float y3 = base_value(x3, curve);
    if (mode == 1u || mode == 2u) {
        y3 = base_delta_mode == 3u ? metadata_target(target_max) : target_max;
        if (y3 > x3 && base_delta_mode != 2u && base_delta_mode != 6u) {
            x3 = min(y3, 1.0);
            x2 = 0.5 * (x1 + x3);
        }
    }

    float position = (x2 - x1) / max(x3 - x1, epsilon);
    float strength = spline_strength(index);
    float y2 = mix(y1, y3, position) + (y3 - y1) * strength * 0.5;
    if ((mode == 1u || mode == 2u) && y2 > x2
        && base_delta_mode != 2u && base_delta_mode != 3u
        && base_delta_mode != 6u)
        y2 = x2;

    float d1 = base_slope(x1, curve);
    float base_d3 = base_slope(x3, curve);
    float d3 = base_d3;
    if ((mode == 1u || mode == 2u) && abs(y3 - x3) <= epsilon
        && base_delta_mode != 2u && base_delta_mode != 3u
        && base_delta_mode != 6u) {
        d3 = 1.0;
    } else if (mode == 1u) {
        float span = max(x3 - x1, epsilon);
        float middle = (y3 - y1) / span;
        float down = max(d1, 0.1 * middle);
        float up = max(d1, (y3 - y1) / max(x3 - x2, epsilon));
        d3 = strength < 0.0
            ? mix(middle, down, -strength)
            : mix(middle, up, strength);
        d3 = min(d3, 1.0);
    } else if (mode == 2u) {
        d3 = base_d3 - spline_mb(index);
    }

    return make_spline(x1, x2, x3, y1, y2, y3, d1, d3, mode);
}

ToneCurve build_tone_curve(
    float target_min, float target_max, float max_lum, float average_value
) {
    ToneCurve tone;
    tone.base = selected_base_curve(
        target_min, target_max, max_lum, average_value
    );
    tone.base = adjust_base_offset(tone.base, target_max, max_lum);
    tone.low = build_low_spline(
        tone.base, target_max, max_lum, average_value,
        tone.toe_end, tone.toe_slope, tone.toe_offset
    );

    int high_index = -1;
    if (use_metadata_curve != 0u && spline_enable != 0u) {
        if (spline_1_mode != 0u)
            high_index = 0;
        else if (spline_count >= 2u && spline_2_mode != 0u)
            high_index = 1;
    }
    tone.high = build_high_spline(tone.base, tone.low, target_max, high_index);
    return tone;
}

float tone_curve_value(float x, ToneCurve tone) {
    x = clamp(x, 0.0, 1.0);
    if (x < tone.toe_end)
        return tone.toe_slope * x + tone.toe_offset;
    if (tone.low.valid && x < tone.low.x3)
        return evaluate_spline(x, tone.low);
    if (tone.high.valid) {
        if (x < tone.high.x1)
            return base_value(x, tone.base);
        if (x < tone.high.x3)
            return evaluate_spline(x, tone.high);
        if (tone.high.mode == 1u || tone.high.mode == 2u)
            return tone.high.y3 + tone.high.d3 * (x - tone.high.x3);
    }
    return base_value(x, tone.base);
}

vec3 RGB_to_YCbCr(vec3 rgb) {
    return rgb * mat3(
         0.2627,  0.6780,  0.0593,
        -0.1396, -0.3604,  0.5000,
         0.5000, -0.4598, -0.0402
    );
}

vec3 YCbCr_to_RGB(vec3 ycbcr) {
    return ycbcr * mat3(
        1.0000,  0.0000,  1.4746,
        1.0000, -0.1645, -0.5713,
        1.0000,  1.8814, -0.0001
    );
}

float saturation_scale(
    float source_max_pq, float mapped_max_pq,
    float target_max, ToneCurve tone
) {
    float c0 = color_saturation_gain_0;
    if (source_max_pq > target_max && color_saturation_count >= 2u) {
        float rml = pq_eotf_inv(mastering_peak());
        float target_mapped = tone_curve_value(target_max, tone);
        float base = clamp(
            pow(max(target_mapped / max(target_max, epsilon), 0.0), c0),
            0.8, 1.0
        );
        float range = max(rml - target_max, epsilon);
        float position = clamp((source_max_pq - target_max) / range, 0.0, 1.0);
        return clamp(
            base - color_saturation_gain_1 * 0.4
                * pow(position, color_saturation_power),
            0.0, 1.0
        );
    }

    float ratio = mapped_max_pq / max(source_max_pq, epsilon);
    return clamp(pow(max(ratio, 0.0), c0), 0.8, 1.0);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 source = max(color.rgb, 0.0) * reference_white;
    float source_max = max(max(source.r, source.g), source.b);
    float source_max_pq = pq_eotf_inv(source_max);

    float target_max = pq_eotf_inv(reference_white);
    float target_min = pq_eotf_inv(
        contrast_ratio > 0.0 ? reference_white / contrast_ratio : 0.0
    );
    float minimum_value = statistics_minimum();
    float average_value = statistics_average();
    float maximum_value = statistics_maximum();
    float max_lum = maximum_luminance_correction(
        target_max, minimum_value, average_value, maximum_value
    );
    ToneCurve tone = build_tone_curve(
        target_min, target_max, max_lum, average_value
    );

    vec3 ictcp = RGB_to_ICtCp(color.rgb);
    float source_i = ictcp.x;
    float mapped_i = clamp(tone_curve_value(source_i, tone), 0.0, 1.0);
    ictcp.yz = chroma_correction(ictcp.yz, source_i, mapped_i);
    ictcp.x = mapped_i;
    vec3 mapped = ICtCp_to_RGB(ictcp) * reference_white;

    if (color_saturation_enable != 0u) {
        vec3 mapped_pq = pq_eotf_inv(max(mapped, 0.0));
        float mapped_max_pq = max(
            max(mapped_pq.r, mapped_pq.g), mapped_pq.b
        );
        vec3 ycbcr = RGB_to_YCbCr(mapped_pq);
        float scale = saturation_scale(
            source_max_pq, mapped_max_pq, target_max, tone
        );
        ycbcr.yz *= scale;
        mapped = pq_eotf(YCbCr_to_RGB(ycbcr));
    }

    color.rgb = mapped / reference_white;
    return color;
}
