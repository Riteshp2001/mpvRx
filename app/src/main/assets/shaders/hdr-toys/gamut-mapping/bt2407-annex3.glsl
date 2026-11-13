// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 3: a simple, low-complexity parametric gamut conversion from
// linear BT.2020 to linear BT.709, operating in the simplified CIELAB space.
// D65, unit white 1, alpha preserved. See bt2407-annex3/README.md for the
// specification, the float64 reference and the validation.
//
// [report] Simplified CIELAB (Annex 3, p. 22), with real cube roots and no
// CIELAB linear segment and no -16 offset:
//     L = 100 (Y/Yn)^(1/3)
//     a = 500 [(X/Xn)^(1/3) - (Y/Yn)^(1/3)]
//     b = 200 [(Y/Yn)^(1/3) - (Z/Zn)^(1/3)]
//     h = atan2(b, a), C = hypot(a, b)
// [report] C_S,max and C_T,max are the maximum possible chroma at the same hue
// and lightness in the source and target gamut. The chroma mapping is
//     C_T = C_T,max * g(C / C_S,max)
// with the polynomial g left open by the report. Two concrete policies
// [design choice]:
//     a3_policy = 0 (clip):   g(x) = min(x / r, 1), r = C_T,max/C_S,max
//     a3_policy = 1 (linear): g(x) = x
// which give C_T = min(C, C_T,max) and min(C C_T,max / C_S,max, C_T,max).
// [report] The optional A1.2 cusp-to-cusp lightness mapping (p. 23) is
//     L' = L + (L_cusp^709 - L_cusp^2020) C / C_cusp^2020
// with C_cusp^2020 the cusp chroma of the source hue leaf. The order of the
// lightness and chroma mappings and the use of the original L in the source
// normalization are [design choice].
//
// The maximum-chroma table only stores the maximum. A constant-hue slice can
// be disconnected: below the cusp the outermost feasible zero ray is used,
// not the first boundary of the neutral-connected interval, and above the
// cusp the downward scan starts at that ray's cusp chroma. A source colour
// can land in a hole of the target slice; the final RGB clamp is mandatory
// [design choice] and can change L and h slightly. The pixel pass is
// closed-form: two table fetches and no per-pixel search.
//
// Matrices, white point, table layout, scan counts, the two policies and the
// final clamp are [design choice]; everything marked [report] is from
// BT.2407-0 (10/2017), Annex 3, pp. 22-24. US10742849B2 is background only.
//
// The boundary tables are rebuilt by the compute passes every frame, exactly
// like the local Annex 2 shader; the pixel pass is closed-form. The worst
// case is 2 * (2048 + 40) ray tests per lower-ray texel and, per boundary
// texel above the cusp, 2 * (1024 + 32) RGB-cube tests. Below the cusp the
// boundary pass is one multiply.

//!PARAM a3_policy
//!DESC Chroma policy: 0 = clip at the BT.709 boundary (default), 1 = linear compression
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM a3_lightness
//!DESC Optional A1.2 cusp-to-cusp lightness mapping: 0 = off (default), 1 = on
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
0

//!HOOK OUTPUT
//!SAVE A3_LOWER
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!DESC gamut mapping (BT.2407 annex 3, lower ray)

const float TAU = 6.28318530717958647692;
const int R_SCAN_STEPS = 2048;
const int R_REFINE_STEPS = 40;
const float R_MAX = 8.0;
const uint HUE_SIZE = 512u;
const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.355670783776392, -0.253366281373660,
    -0.666684351832489,  1.616481236634939,  0.0157685458139111,
     0.017639857445311, -0.042770613257809,  0.942103121235474
);

const mat3 XYZ_TO_BT709 = mat3(
     3.2409699419045226, -1.5373831775700940, -0.4986107602930034,
    -0.9692436362808796,  1.8759675015077202,  0.0415550574071756,
     0.0556300796969937, -0.2039769588889765,  1.0569715142428786
);

// RGB at L = 1 on the ray C = radius * L [design choice]. Scaling RGB by k
// scales XYZ by k and the cube roots (L, a, b) by k^(1/3), so fixed hue and
// fixed C/L is a straight line through the simplified-CIELAB origin [report].
vec3 lower_ray_rgb(float hue, float radius, mat3 xyz_to_rgb) {
    float fx = 0.01 + radius * cos(hue) / 500.0;
    float fy = 0.01;
    float fz = 0.01 - radius * sin(hue) / 200.0;
    vec3 xyz = vec3(fx * fx * fx, fy * fy * fy, fz * fz * fz) * D65_WHITE;
    return xyz * xyz_to_rgb;
}

float lower_ray_min_channel(float hue, float radius, mat3 xyz_to_rgb) {
    vec3 rgb = lower_ray_rgb(hue, radius, xyz_to_rgb);
    return min(rgb.x, min(rgb.y, rgb.z));
}

// Outermost radius where a channel of the L = 1 ray reaches zero while the
// other channels are non-negative [design choice]. A constant-hue slice can
// be disconnected, so several radii reach zero: the first bounds only the
// neutral-connected interval, the last bounds the outermost interval and
// carries the maximum chroma at every L below its cusp [report]. The scan
// keeps the last non-negative -> negative crossing of the minimum channel;
// the bisection refines it. The reference uses the same 2048 + 40
// convention. r_max = 8 exceeds the measured maximum outer slope (about
// 3.80).
float lower_slope(float hue, mat3 xyz_to_rgb) {
    float lower = 0.0;
    float upper = 0.0;
    bool found = false;
    bool previous_nonnegative =
        lower_ray_min_channel(hue, 0.0, xyz_to_rgb) >= 0.0;

    for (int i = 1; i <= R_SCAN_STEPS; i++) {
        float candidate = R_MAX * float(i) / float(R_SCAN_STEPS);
        bool nonnegative =
            lower_ray_min_channel(hue, candidate, xyz_to_rgb) >= 0.0;

        if (previous_nonnegative && !nonnegative) {
            lower = R_MAX * float(i - 1) / float(R_SCAN_STEPS);
            upper = candidate;
            found = true;
        }

        previous_nonnegative = nonnegative;
    }

    // Not reached by either gamut; keep the shader finite on a damaged
    // matrix instead of returning an uninitialized bracket.
    if (!found) {
        return 0.0;
    }

    for (int i = 0; i < R_REFINE_STEPS; i++) {
        float middle = 0.5 * (lower + upper);

        if (lower_ray_min_channel(hue, middle, xyz_to_rgb) > 0.0) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    return 0.5 * (lower + upper);
}

// GLSL pow is only specified to a few ULP, and the optional A1.2 shift
// differentiates the two cusp lightnesses; near the top of the gamut the
// mapped output moves by about 1.5 RGB units per L unit. Two Newton steps
// take the cube root to float32 precision independently of the pow
// implementation [design choice]. The float64 reference uses the same
// mathematical value.
float refined_cbrt(float x) {
    float y = pow(x, 1.0 / 3.0);

    for (int i = 0; i < 2; i++) {
        y = y - (y * y * y - x) / (3.0 * y * y);
    }

    return y;
}

// Cusp lightness: the first positive channel to reach one along the outer
// ray gives L_cusp = min_i v_i(r0)^(-1/3) [design choice], C_cusp = r0 *
// L_cusp. This is the cusp of the outermost interval, i.e. the maximum
// chroma of the hue leaf [report]. The guard is the float32 analogue of the
// reference's 1e-300; it is not reached by either gamut (the measured cusp
// lightness is 39..98).
float cusp_lightness(float hue, float radius, mat3 xyz_to_rgb) {
    vec3 rgb = lower_ray_rgb(hue, radius, xyz_to_rgb);
    float maximum = max(rgb.x, max(rgb.y, rgb.z));
    return refined_cbrt(1.0 / max(maximum, 1e-30));
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);

    // The last column repeats hue zero so one hardware-filtered sample is
    // continuous across the hue seam [design choice].
    if (position.x > int(HUE_SIZE) || position.y > 0) {
        return;
    }

    float hue = TAU * float(position.x & int(HUE_SIZE - 1u)) /
                float(HUE_SIZE);
    float slope_709 = lower_slope(hue, XYZ_TO_BT709);
    float lightness_709 = cusp_lightness(hue, slope_709, XYZ_TO_BT709);
    float slope_2020 = lower_slope(hue, XYZ_TO_BT2020);
    float lightness_2020 = cusp_lightness(hue, slope_2020, XYZ_TO_BT2020);

    // Layout: (L_cusp_709 - L_cusp_2020, r0_709, r0_2020, L_cusp_2020),
    // where r0 is the outermost feasible zero ray of each gamut. The A1.2
    // shift needs only the difference and C_cusp_2020 = r0_2020 *
    // L_cusp_2020. Storing the difference keeps its F16 quantum near 0.004 L
    // instead of the 0.03 L quantum of either cusp lightness; the shift is
    // amplified about 1.5 RGB units per L unit at the top of the gamut, so
    // the smaller quantum matters [design choice].
    imageStore(
        out_image,
        position,
        vec4(
            lightness_709 - lightness_2020,
            slope_709,
            slope_2020,
            lightness_2020
        )
    );
}

//!HOOK OUTPUT
//!SAVE A3_BOUNDARY
//!COMPONENTS 2
//!WIDTH 513
//!HEIGHT 256
//!COMPUTE 16 8
//!BIND A3_LOWER
//!DESC gamut mapping (BT.2407 annex 3, boundary generation)

const float TAU = 6.28318530717958647692;
const uint HUE_SIZE = 512u;
const uint LIGHTNESS_SIZE = 256u;
const int BOUNDARY_SCAN_STEPS = 1024;
const int BOUNDARY_REFINE_STEPS = 32;
const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.355670783776392, -0.253366281373660,
    -0.666684351832489,  1.616481236634939,  0.0157685458139111,
     0.017639857445311, -0.042770613257809,  0.942103121235474
);

const mat3 XYZ_TO_BT709 = mat3(
     3.2409699419045226, -1.5373831775700940, -0.4986107602930034,
    -0.9692436362808796,  1.8759675015077202,  0.0415550574071756,
     0.0556300796969937, -0.2039769588889765,  1.0569715142428786
);

vec3 simplified_lab_to_xyz(vec3 lab) {
    float fy = lab.x / 100.0;
    float fx = fy + lab.y / 500.0;
    float fz = fy - lab.z / 200.0;
    return vec3(fx * fx * fx, fy * fy * fy, fz * fz * fz) * D65_WHITE;
}

bool lab_in_unit_gamut(vec3 lab, mat3 xyz_to_rgb) {
    vec3 rgb = simplified_lab_to_xyz(lab) * xyz_to_rgb;
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

// Maximum chroma above the cusp [design choice]. The cusp chroma is the
// maximum of the hue leaf, so it is outside at this lightness; walk down
// until the first inside point, which brackets the outermost boundary.
// That first crossing from above is the maximum even when the slice also
// has inner holes [README 3.2]. Breaking at the first inside point is
// equivalent to the reference's full scan, whose later iterations cannot
// change the bracket once found. The neutral point is inside for
// 0 <= L <= 100, so the fallback is only a guard against a damaged table.
float max_chroma_above_cusp(
    float hue,
    float lightness,
    float cusp_chroma,
    mat3 xyz_to_rgb
) {
    vec2 direction = vec2(cos(hue), sin(hue));
    float lower = 0.0;
    float upper = 0.0;
    float previous = cusp_chroma;
    bool found = false;

    for (int i = 1; i <= BOUNDARY_SCAN_STEPS; i++) {
        float candidate = cusp_chroma *
                          (1.0 - float(i) / float(BOUNDARY_SCAN_STEPS));
        vec3 lab = vec3(lightness, candidate * direction);

        if (!found && lab_in_unit_gamut(lab, xyz_to_rgb)) {
            lower = candidate;
            upper = previous;
            found = true;
            break;
        }

        previous = candidate;
    }

    if (!found) {
        return 0.0;
    }

    for (int i = 0; i < BOUNDARY_REFINE_STEPS; i++) {
        float middle = 0.5 * (lower + upper);
        vec3 lab = vec3(lightness, middle * direction);

        if (lab_in_unit_gamut(lab, xyz_to_rgb)) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    return lower;
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);

    if (position.x > int(HUE_SIZE) ||
        position.y >= int(LIGHTNESS_SIZE)) {
        return;
    }

    vec4 lower = A3_LOWER_mul * texelFetch(
        A3_LOWER_raw,
        ivec2(position.x, 0),
        0
    );
    float hue = TAU * float(position.x & int(HUE_SIZE - 1u)) /
                float(HUE_SIZE);
    float lightness = 100.0 * float(position.y) /
                      float(LIGHTNESS_SIZE - 1u);
    float boundary_709;
    float boundary_2020;
    float cusp_lightness_709 = lower.x + lower.w;

    // Below the cusp the maximum chroma is exactly r0 * L, where r0 is the
    // outermost feasible zero ray [report][design choice for the ray
    // derivation].
    if (lightness <= cusp_lightness_709) {
        boundary_709 = lower.y * lightness;
    } else {
        boundary_709 = max_chroma_above_cusp(
            hue,
            lightness,
            lower.y * cusp_lightness_709,
            XYZ_TO_BT709
        );
    }

    if (lightness <= lower.w) {
        boundary_2020 = lower.z * lightness;
    } else {
        boundary_2020 = max_chroma_above_cusp(
            hue,
            lightness,
            lower.z * lower.w,
            XYZ_TO_BT2020
        );
    }

    imageStore(
        out_image,
        position,
        vec4(boundary_709, boundary_2020, 0.0, 1.0)
    );
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND A3_BOUNDARY
//!BIND A3_LOWER
//!DESC gamut mapping (BT.2407 annex 3)

const float TAU = 6.28318530717958647692;
const float INV_TAU = 1.0 / TAU;
const float CHROMA_EPSILON = 1e-9;
const uint HUE_SIZE = 512u;
const uint LIGHTNESS_SIZE = 256u;
const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0000000000000000, 0.0280726930490874, 1.0609850577107910
);

const mat3 BT2020_TO_BT709 = mat3(
     1.660491002108434, -0.5876411387885491,  -0.072849863319885,
    -0.1245504745215906, 1.1328998971259596,  -0.0083494226043695,
    -0.0181507633549052, -0.1005788980080074,  1.118729661362913
);

const mat3 XYZ_TO_BT709 = mat3(
     3.2409699419045226, -1.5373831775700940, -0.4986107602930034,
    -0.9692436362808796,  1.8759675015077202,  0.0415550574071756,
     0.0556300796969937, -0.2039769588889765,  1.0569715142428786
);

// Real cube root, so the transform also inverts outside the RGB cube. The
// pixel pass only feeds it nonnegative XYZ, but sign-safe keeps the function
// the exact inverse of simplified_lab_to_xyz.
vec3 xyz_to_simplified_lab(vec3 xyz) {
    vec3 f = xyz / D65_WHITE;
    f = sign(f) * pow(abs(f), vec3(1.0 / 3.0));
    return vec3(
        100.0 * f.y,
        500.0 * (f.x - f.y),
        200.0 * (f.y - f.z)
    );
}

vec3 simplified_lab_to_xyz(vec3 lab) {
    float fy = lab.x / 100.0;
    float fx = fy + lab.y / 500.0;
    float fz = fy - lab.z / 200.0;
    return vec3(fx * fx * fx, fy * fy * fy, fz * fz * fz) * D65_WHITE;
}

// The normalized coordinates and the +0.5 texel offset are the local Annex 2
// convention [design choice]; the last hue column repeats hue zero. The
// table stores (C_T,max, C_S,max) in R and G.
vec2 sample_boundary(float hue, float lightness) {
    float hue_position = fract(hue * INV_TAU + 1.0) * float(HUE_SIZE);
    float hue_coordinate = (hue_position + 0.5) / float(HUE_SIZE + 1u);
    float lightness_position = clamp(lightness, 0.0, 100.0) /
                               100.0 * float(LIGHTNESS_SIZE - 1u);
    float lightness_coordinate = (lightness_position + 0.5) /
                                 float(LIGHTNESS_SIZE);
    return A3_BOUNDARY_mul * textureLod(
        A3_BOUNDARY_raw,
        vec2(hue_coordinate, lightness_coordinate),
        0.0
    ).rg;
}

// The lower-ray table stores (L_cusp_709 - L_cusp_2020, r0_709, r0_2020,
// L_cusp_2020) and repeats its last column like the boundary table [design
// choice]. The difference is stored instead of L_cusp_709 because the A1.2
// shift uses only the difference and the smaller F16 quantum (~0.004 L)
// keeps the shift accurate near the top of the gamut [design choice].
vec4 sample_lower(float hue) {
    float hue_position = fract(hue * INV_TAU + 1.0) * float(HUE_SIZE);
    float hue_coordinate = (hue_position + 0.5) / float(HUE_SIZE + 1u);
    return A3_LOWER_mul * textureLod(
        A3_LOWER_raw,
        vec2(hue_coordinate, 0.5),
        0.0
    );
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);

    // Annex 3 is defined on the BT.2020 RGB cube. Finite values outside it
    // are clipped per channel as a domain guard, not as a tone mapper
    // [design choice].
    vec3 rgb2020 = clamp(color.rgb, 0.0, 1.0);
    vec3 rgb709 = rgb2020 * BT2020_TO_BT709;

    // Clip early-out: colours already inside BT.709 are returned exactly,
    // before table interpolation can shave them at the boundary [design
    // choice]. The linear policy deliberately does not early-out.
    if (a3_policy == 0) {
        if (all(greaterThanEqual(rgb709, vec3(0.0))) &&
            all(lessThanEqual(rgb709, vec3(1.0)))) {
            color.rgb = rgb709;
            return color;
        }
    }

    vec3 lab = xyz_to_simplified_lab(rgb2020 * BT2020_TO_XYZ);
    float chroma = length(lab.yz);

    // Hue is undefined on the neutral axis. A neutral can only be out of
    // gamut by lying beyond black or white, so clamping is the whole answer.
    if (chroma <= CHROMA_EPSILON) {
        color.rgb = clamp(rgb709, 0.0, 1.0);
        return color;
    }

    float hue = atan(lab.z, lab.y);
    float lightness = lab.x;
    vec2 cmax_source = sample_boundary(hue, lightness);

    // Optional A1.2 cusp-to-cusp lightness mapping [report]. The source
    // normalization uses the original L and C_S,max [design choice].
    if (a3_lightness == 1) {
        vec4 lower = sample_lower(hue);
        float source_cusp_chroma = lower.z * lower.w;
        lightness = lab.x +
                    lower.x * chroma /
                    max(source_cusp_chroma, CHROMA_EPSILON);
    }

    float cmax_target = sample_boundary(hue, lightness).r;
    float mapped_chroma;

    if (a3_policy == 0) {
        mapped_chroma = min(chroma, cmax_target);
    } else {
        mapped_chroma = min(
            chroma * cmax_target / max(cmax_source.g, CHROMA_EPSILON),
            cmax_target
        );
    }

    vec3 mapped_lab = vec3(
        lightness,
        mapped_chroma * cos(hue),
        mapped_chroma * sin(hue)
    );

    // Mandatory final RGB clamp [design choice]: the table has no hole
    // information and can leave a colour outside the target cube. It may
    // change L and h; the README quantifies the change.
    color.rgb = clamp(
        simplified_lab_to_xyz(mapped_lab) * XYZ_TO_BT709,
        0.0,
        1.0
    );

    return color;
}
