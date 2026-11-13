// Xu, Zhao & Luo (2018), Optics Express 26, 11481-11495, section 2.
// https://doi.org/10.1364/OE.26.011481
// VP / DP in ZCAM JCh (Safdar, Hardeberg & Luo, 2021).
// https://doi.org/10.1364/OE.413659
// Linear BT.2020 -> linear BT.709; D65 and common viewing conditions.
// Both gamuts use ideal black (0) and the same display white.

//!PARAM vp_mode
//!DESC Focal point: 0 = vividness preserved (black), 1 = depth preserved (white)
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
0

//!PARAM reference_white
//!DESC Luminance in cd/m2 represented by linear RGB 1
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 1000.0
203.0

//!PARAM vp_background
//!DESC Background luminance as a percentage of reference white
//!TYPE float
//!MINIMUM 1.0
//!MAXIMUM 100.0
20.0

//!PARAM vp_surround
//!DESC ZCAM surround: 0 = dark, 1 = dim, 2 = average
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 2
2

//!HOOK OUTPUT
//!SAVE VP_BOUNDARY
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 512
//!COMPUTE 16 8
//!DESC gamut mapping (VP/DP, chroma boundaries)

const float TAU = 6.283185307179586;
const float N = 2610.0 / 16384.0;
const float P = 1.7 * 2523.0 / 32.0;
const float C1 = 3424.0 / 4096.0;
const float C2 = 2413.0 / 128.0;
const float C3 = 2392.0 / 128.0;
const float EPSILON = 3.7035226210190005e-11;
const vec3 WHITE = vec3(0.9504559270516716, 1.0, 1.0890577507598784);
const mat3 XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);
const mat3 LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);
const mat3 IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);
const mat3 XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.355670783776392, -0.253366281373660,
    -0.666684351832489,  1.616481236634939,  0.015768545813911,
     0.017639857445311, -0.042770613257809,  0.942103121235474
);
const mat3 XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.537383177570094, -0.498610760293003,
    -0.969243636280880,  1.875967501507720,  0.041555057407176,
     0.055630079696994, -0.203976958888977,  1.056971514242879
);

// (Iz of white, lightness exponent, chroma scale). Equations (1), (2),
// (8), (11)-(15): FL^0.2 cancels in Q/Qw and M/Qw. Yw=100 and Yb is
// relative background luminance; absolute cd/m2 enter only the PQ transform.
vec3 zcam_viewing_conditions() {
    vec3 xyz = WHITE * reference_white;
    vec3 adapted = vec3(1.15 * xyz.x - 0.15 * xyz.z,
                        0.66 * xyz.y + 0.34 * xyz.x, xyz.z);
    vec3 lms = pow(max(adapted * XYZP_TO_LMS / 10000.0, 0.0), vec3(N));
    vec3 encoded = pow((C1 + C2 * lms) / (1.0 + C3 * lms), vec3(P));
    float izw = max(encoded.y - EPSILON, 1e-12);
    float fs = vp_surround == 0 ? 0.525 : (vp_surround == 1 ? 0.59 : 0.69);
    float fb = sqrt(vp_background / 100.0);
    float exponent = 1.6 * fs / pow(fb, 0.12);
    float chroma_scale = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                               pow(izw, 0.78 + exponent));
    return vec3(izw, exponent, chroma_scale);
}

// Cartesian (J, C cos(h), C sin(h))/100. Supplement S2, S8-S10:
// 1/0.74 is the exact inverse exponent; the printed 1.3514 is rounded.
vec3 zcam_to_xyz(vec3 jab, vec3 viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(direction,
        vec2(cos(radians(89.038)), -sin(radians(89.038))));
    float radius = pow(c / (viewing.z * pow(eccentricity, 0.068)), 1.0 / 0.74);
    float iz = viewing.x * pow(max(jab.x, 0.0), 1.0 / viewing.y);
    vec3 encoded = vec3(iz + EPSILON, radius * direction) * IAB_TO_LMSP;
    vec3 root = pow(max(encoded, 0.0), vec3(1.0 / P));
    vec3 lms = 10000.0 * pow(max(root - C1, 0.0) /
                            max(C2 - C3 * root, 1e-6), vec3(1.0 / N));
    vec3 xyzp = lms * LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) / reference_white;
}

float boundary(float j, vec2 direction, mat3 to_rgb, vec3 viewing) {
    float lo = 0.0;
    // ZCAM C/100 can be large at low white luminance and dark backgrounds.
    float hi = 64.0;
    for (int i = 0; i < 26; ++i) {
        float c = 0.5 * (lo + hi);
        vec3 rgb = zcam_to_xyz(vec3(j, c * direction), viewing) * to_rgb;
        if (all(greaterThanEqual(rgb, vec3(0.0))) &&
            all(lessThanEqual(rgb, vec3(1.0)))) lo = c;
        else hi = c;
    }
    return lo;
}

// gpu-next intermediate textures may be FP16 even with fbo-format=rgba32f.
// Splitting each scalar avoids quantizing away the cusp's boundary slope.
vec2 pack_scalar(float value) {
    // Clear 13 mantissa bits explicitly: hi is exactly representable in FP16
    // at every magnitude. Do not rely on a packHalf/unpackHalf round-trip.
    float hi = value < 0.00006103515625 ? 0.0 :
               uintBitsToFloat(floatBitsToUint(value) & 0xffffe000u);
    return vec2(hi, (value - hi) * 1024.0);
}

void hook() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= 513 || pos.y >= 512) return;
    float hue = TAU * float(pos.x % 512) / 512.0;
    float j = float(pos.y) / 511.0;
    vec2 direction = vec2(cos(hue), sin(hue));
    vec3 viewing = zcam_viewing_conditions();
    vec2 c = vec2(0.0);
    if (pos.y > 0 && pos.y < 511) {
        c.x = boundary(j, direction, XYZ_TO_BT2020, viewing);
        c.y = boundary(j, direction, XYZ_TO_BT709, viewing);
    }
    // The last hue column repeats zero, allowing seam-safe bilinear sampling.
    imageStore(out_image, pos, vec4(pack_scalar(c.x), pack_scalar(c.y)));
}

//!HOOK OUTPUT
//!BIND VP_BOUNDARY
//!SAVE VP_CUSP
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 2
//!COMPUTE 16 1
//!DESC gamut mapping (VP/DP, refined cusps)

float fetch_chroma(int hue, int row, int gamut) {
    vec4 packed = VP_BOUNDARY_mul * texelFetch(VP_BOUNDARY_raw, ivec2(hue, row), 0);
    return gamut == 0 ? packed.x + packed.y / 1024.0 : packed.z + packed.w / 1024.0;
}

void hook() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= 513 || pos.y >= 2) return;
    int index = 1;
    float c = 0.0;
    for (int i = 1; i < 511; ++i) {
        float candidate = fetch_chroma(pos.x, i, pos.y);
        if (candidate > c) { c = candidate; index = i; }
    }
    // Fit the two sides 4..8 rows away from the sampled maximum and intersect
    // them. Choosing a single maximum row creates a staircase in focal rays;
    // the immediately adjacent segment can also straddle the RGB corner.
    int ln = max(index - 4, 0);
    int lf = max(index - 8, 0);
    int un = min(index + 4, 511);
    int uf = min(index + 8, 511);
    float cl = fetch_chroma(pos.x, ln, pos.y);
    float cu = fetch_chroma(pos.x, un, pos.y);
    float ml = (cl - fetch_chroma(pos.x, lf, pos.y)) * 511.0 / float(max(ln - lf, 1));
    float mu = (fetch_chroma(pos.x, uf, pos.y) - cu) * 511.0 / float(max(uf - un, 1));
    float bl = cl - ml * float(ln) / 511.0;
    float bu = cu - mu * float(un) / 511.0;
    float j = clamp((bu - bl) / max(ml - mu, 1e-7), float(ln) / 511.0, float(un) / 511.0);
    c = max(bl + ml * j, c);
    float j_hi = j < 0.00006103515625 ? 0.0 :
                 uintBitsToFloat(floatBitsToUint(j) & 0xffffe000u);
    float c_hi = c < 0.00006103515625 ? 0.0 :
                 uintBitsToFloat(floatBitsToUint(c) & 0xffffe000u);
    imageStore(out_image, pos, vec4(j_hi, (j - j_hi) * 1024.0,
                                   c_hi, (c - c_hi) * 1024.0));
}

//!HOOK OUTPUT
//!BIND VP_BOUNDARY
//!BIND VP_CUSP
//!SAVE VP_RAYS
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 512
//!COMPUTE 16 8
//!DESC gamut mapping (VP/DP, focal ray boundaries)

float chroma_at(int hue, float j, int gamut) {
    float y = clamp(j, 0.0, 1.0) * 511.0;
    int row = int(floor(y));
    vec4 packed = VP_BOUNDARY_mul * mix(
        texelFetch(VP_BOUNDARY_raw, ivec2(hue, row), 0),
        texelFetch(VP_BOUNDARY_raw, ivec2(hue, min(row + 1, 511)), 0), fract(y));
    return gamut == 0 ? packed.x + packed.y / 1024.0 : packed.z + packed.w / 1024.0;
}

float ray_boundary(int hue, vec2 direction, int gamut) {
    vec4 cusp = VP_CUSP_mul * texelFetch(VP_CUSP_raw, ivec2(hue, gamut), 0);
    float cusp_j = cusp.x + cusp.y / 1024.0;
    float cusp_c = cusp.z + cusp.w / 1024.0;
    bool dp = vp_mode == 1;
    float focal = dp ? 1.0 : 0.0;
    float sign_j = dp ? -1.0 : 1.0;
    float cusp_distance = abs(cusp_j - focal);
    float j_near = clamp(cusp_j + sign_j * 4.0 / 511.0, 0.0, 1.0);
    float c_near = chroma_at(hue, j_near, gamut);
    float near_distance = abs(j_near - focal);
    // direction = (distance along lightness axis, chroma), unit length.
    if (direction.x * cusp_c < direction.y * cusp_distance) {
        // Fig. 3 continuation: a one-sided secant anchored at the cusp.
        float j_far = clamp(cusp_j + sign_j * 8.0 / 511.0, 0.0, 1.0);
        float slope = (chroma_at(hue, j_far, gamut) - c_near) /
                      (j_far - j_near);
        return max((cusp_c + slope * (focal - cusp_j)) /
                   max(direction.y - slope * sign_j * direction.x, 1e-7), 0.0);
    }
    float lo = cusp_distance;
    float hi = 1.0;
    for (int i = 0; i < 16; ++i) {
        float t = 0.5 * (lo + hi);
        float c = chroma_at(hue, focal + sign_j * t, gamut);
        // The refined cusp is not necessarily on the sampled contour. Join it
        // to the one-sided contour explicitly so every bracket is continuous.
        if (t < near_distance)
            c = mix(cusp_c, c_near, (t - cusp_distance) /
                    max(near_distance - cusp_distance, 1e-7));
        if (c * direction.x > t * direction.y) lo = t;
        else hi = t;
    }
    return 0.5 * (lo + hi) / max(direction.x, 1e-7);
}

vec2 pack_scalar(float value) {
    float hi = value < 0.00006103515625 ? 0.0 :
               uintBitsToFloat(floatBitsToUint(value) & 0xffffe000u);
    return vec2(hi, (value - hi) * 1024.0);
}

void hook() {
    ivec2 pos = ivec2(gl_GlobalInvocationID.xy);
    if (pos.x >= 513 || pos.y >= 512) return;
    float angle = 1.5707963267948966 * float(pos.y) / 511.0;
    vec2 direction = max(vec2(cos(angle), sin(angle)), 0.0);
    vec2 radius = vec2(ray_boundary(pos.x, direction, 0),
                       ray_boundary(pos.x, direction, 1));
    imageStore(out_image, pos, vec4(pack_scalar(radius.x), pack_scalar(radius.y)));
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND VP_BOUNDARY
//!BIND VP_RAYS
//!DESC gamut mapping (Xu 2018 VP/DP ZCAM)

const float TAU = 6.283185307179586;
const float N = 2610.0 / 16384.0;
const float P = 1.7 * 2523.0 / 32.0;
const float C1 = 3424.0 / 4096.0;
const float C2 = 2413.0 / 128.0;
const float C3 = 2392.0 / 128.0;
const float EPSILON = 3.7035226210190005e-11;
const vec3 WHITE = vec3(0.9504559270516716, 1.0, 1.0890577507598784);
const mat3 BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);
const mat3 XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.537383177570094, -0.498610760293003,
    -0.969243636280880,  1.875967501507720,  0.041555057407176,
     0.055630079696994, -0.203976958888977,  1.056971514242879
);
const mat3 XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);
const mat3 LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);
const mat3 LMSP_TO_IAB = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);
const mat3 IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

vec3 xyz_to_iab(vec3 xyz) {
    xyz *= reference_white;
    vec3 adapted = vec3(1.15 * xyz.x - 0.15 * xyz.z,
                        0.66 * xyz.y + 0.34 * xyz.x, xyz.z);
    vec3 lms = pow(max(adapted * XYZP_TO_LMS / 10000.0, 0.0), vec3(N));
    vec3 encoded = pow((C1 + C2 * lms) / (1.0 + C3 * lms), vec3(P));
    vec3 iab = encoded * LMSP_TO_IAB;
    return vec3(max(iab.x - EPSILON, 0.0), iab.yz);
}

vec3 zcam_viewing_conditions() {
    float izw = max(xyz_to_iab(WHITE).x, 1e-12);
    float fs = vp_surround == 0 ? 0.525 : (vp_surround == 1 ? 0.59 : 0.69);
    float fb = sqrt(vp_background / 100.0);
    float exponent = 1.6 * fs / pow(fb, 0.12);
    float chroma_scale = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                               pow(izw, 0.78 + exponent));
    return vec3(izw, exponent, chroma_scale);
}

vec3 xyz_to_zcam(vec3 xyz, vec3 viewing) {
    vec3 iab = xyz_to_iab(xyz);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(direction,
        vec2(cos(radians(89.038)), -sin(radians(89.038))));
    float j = pow(iab.x / viewing.x, viewing.y);
    float c = viewing.z * pow(radius, 0.74) * pow(eccentricity, 0.068);
    return vec3(j, c * direction);
}

vec3 zcam_to_xyz(vec3 jab, vec3 viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(direction,
        vec2(cos(radians(89.038)), -sin(radians(89.038))));
    float radius = pow(c / (viewing.z * pow(eccentricity, 0.068)), 1.0 / 0.74);
    float iz = viewing.x * pow(max(jab.x, 0.0), 1.0 / viewing.y);
    vec3 encoded = vec3(iz + EPSILON, radius * direction) * IAB_TO_LMSP;
    vec3 root = pow(max(encoded, 0.0), vec3(1.0 / P));
    vec3 lms = 10000.0 * pow(max(root - C1, 0.0) /
                            max(C2 - C3 * root, 1e-6), vec3(1.0 / N));
    vec3 xyzp = lms * LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) / reference_white;
}

// Equation (2): r, rs and rd are distances from the SAME focal point.
// When no reduction is needed, identity also avoids the rs == rd == 0 case.
float knee(float r, float rs, float rd) {
    if (rs <= rd || r <= 0.9 * rd) return r;
    return 0.9 * rd + 0.1 * rd *
           clamp((r - 0.9 * rd) / max(rs - 0.9 * rd, 1e-7), 0.0, 1.0);
}

vec2 table_position(float hue, float ordinate) {
    return vec2(fract(hue / TAU + 1.0) * 512.0,
                clamp(ordinate, 0.0, 1.0) * 511.0);
}

vec2 unpack_limits(vec4 packed) {
    return packed.xz + packed.yw / 1024.0;
}

// Explicit interpolation also avoids relying on the host's sampler filter.
vec2 sample_limits(sampler2D table, float multiplier, vec2 position) {
    vec2 p = position;
    ivec2 base = ivec2(floor(p));
    vec2 weight = fract(p);
    base = clamp(base, ivec2(0), ivec2(511));
    ivec2 next = min(base + 1, ivec2(512, 511));
    vec4 low = mix(texelFetch(table, base, 0),
                   texelFetch(table, ivec2(next.x, base.y), 0), weight.x);
    vec4 high = mix(texelFetch(table, ivec2(base.x, next.y), 0),
                    texelFetch(table, next, 0), weight.x);
    return unpack_limits(multiplier * mix(low, high, weight.y));
}

bool in_gamut(vec3 rgb) {
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    // This is a gamut compressor after tone mapping. Negative and super-white
    // source channels have no defined source-gamut radius in the paper.
    vec3 source = clamp(color.rgb, 0.0, 1.0);
    vec3 xyz = source * BT2020_TO_XYZ;
    vec3 rgb = xyz * XYZ_TO_BT709;
    // ZCAM's opponent response has a small residual D65 chroma. Preserve RGB
    // neutrals explicitly instead of treating their unstable hue as a colour.
    if (max(source.r, max(source.g, source.b)) -
        min(source.r, min(source.g, source.b)) <= 1e-7) {
        color.rgb = source;
        return color;
    }
    vec3 viewing = zcam_viewing_conditions();
    vec3 jab = xyz_to_zcam(xyz, viewing);
    float chroma = length(jab.yz);
    if (chroma <= 1e-7) { color.rgb = clamp(rgb, 0.0, 1.0); return color; }
    float hue = atan(jab.z, jab.y);
    // Step 1: equation (1) and its knee are identity for the identical black
    // and white lightness ranges of these two ideal, D65 RGB gamuts.
    float focal = vp_mode == 1 ? 1.0 : 0.0;
    float axial = abs(jab.x - focal);
    float radius = length(vec2(axial, chroma));
    float angle = atan(chroma, axial) / (0.25 * TAU);
    vec2 limits = sample_limits(VP_RAYS_raw, VP_RAYS_mul, table_position(hue, angle));
    float mapped_radius = knee(radius, limits.x, limits.y);
    float ratio = mapped_radius / max(radius, 1e-7);
    // Step 2, including the continued boundary for rays below/above the cusp.
    jab = vec3(mix(focal, jab.x, ratio), jab.yz * ratio);
    float c2 = chroma * ratio;
    // Step 3 / Fig. 4 uses the LIGHTNESS-MAPPED SOURCE boundary, not a
    // boundary reconstructed from the output of Step 2.
    limits = sample_limits(VP_BOUNDARY_raw, VP_BOUNDARY_mul, table_position(hue, jab.x));
    float c3 = knee(c2, limits.x, limits.y);
    if (mapped_radius == radius && c3 == c2 && in_gamut(rgb)) {
        color.rgb = rgb;
        return color;
    }
    jab.yz *= c3 / max(c2, 1e-7);
    rgb = zcam_to_xyz(jab, viewing) * XYZ_TO_BT709;
    // Bilinear LUTs do not exactly reproduce sharp RGB cusps. Correct only
    // an actual overshoot along the same constant-J, constant-hue path.
    if (!in_gamut(rgb)) {
        float lo = 0.0;
        float hi = 1.0;
        for (int i = 0; i < 14; ++i) {
            float t = 0.5 * (lo + hi);
            vec3 candidate = zcam_to_xyz(vec3(jab.x, jab.yz * t), viewing) * XYZ_TO_BT709;
            if (in_gamut(candidate)) lo = t;
            else hi = t;
        }
        rgb = zcam_to_xyz(vec3(jab.x, jab.yz * lo), viewing) * XYZ_TO_BT709;
    }
    color.rgb = clamp(rgb, 0.0, 1.0);
    return color;
}
