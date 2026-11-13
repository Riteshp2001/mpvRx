// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 4 adaptive core-region gamut mapping re-based on ZCAM
// (Safdar, Hardeberg & Luo, Opt. Express 29(4) 6036, 2021).
// Input is linear BT.2020 RGB, output is linear BT.709 RGB, both D65 with
// unit white 1; alpha is passed through untouched.
//
// Pipeline (seven OUTPUT-hook passes):
//
//   A4Z_KEYS    513x1    the twelve runtime key hues and Izw
//   A4Z_CUSP_T 4097x1    target operational cusp (j_tc, c_tc)
//   A4Z_CUSP_S 4097x1    source operational cusp (j_sc, c_sc) at A^-1(h_a)
//   A4Z_BOUND 4097x513   target and source boundary chroma on target-cusp-
//                        relative lightness rows (both columns use one row set)
//   A4Z_LINE   4097x1    cusp line (m, j0) from the two cusps
//   A4Z_ANCHOR 4097x1    anchor (c_a, j_a) from the tau area solve
//   A4Z_MAP     screen   hue alignment, core test, three-region map, inverse
//
// The hue axis is 4097 = 8*512+1 columns: 513 columns span 0.703 degrees per
// cell, and linear interpolation across the narrow Ct(h) cusp at the BT.709
// blue corner misses it by up to 6.4e-3 c, which the RGB gain there turns
// into 0.04 RGB (README 8.1 and 15.6). Rows stay at 513.
//
// Every saved pass texture is rgba16f: libplacebo's find_fbo_format prefers
// PL_FMT_FLOAT/16 with linear filtering and get_hook_tex always requests four
// components. Each scalar is stored as two half components, high =
// floor(value), low = (value - high) * 1024, reconstructed as high + low/1024.
// Both parts are linear in the stored value, so hardware bilinear filtering
// still interpolates the scalar. The integer part is exact in half for the
// table range; the residual in [0, 1024) limits the reconstruction error near
// 4.9e-4. No BUFFER block is used anywhere: every table is computed.
//
// Provenance markers used below:
//   [report]      ITU-R BT.2407-0 Annex 4 (the qualitative method and the
//                 50% target-area experiment of A4.3.1)
//   [paper/xu.glsl]  ZCAM equations and constants, checked against xu.glsl
//   [design choice]  everything the report/paper leaves open; see the
//                    directory's README.md for the full list and rationale.
//
// The report specifies no equations for the core shape, the three-region
// scalar map, the hue-warp construction or the boundary semantics; those are
// design choices documented in README sections 4-8 and recorded, with the
// measured consequences, in README section 15.
//
// Domain policy [design choice; README 6.4]: non-finite components become 0;
// a4z_input_clip=1 clamps each channel to [0, 1], a4z_input_clip=0 clamps
// negatives to 0 and scales a peak above 1 down to 1 while preserving the
// channel ratios; RGB triplets whose span is <= 1e-4 take the colorimetric
// BT.2020->BT.709 path, as do colours whose forward ZCAM chroma is <= 1e-7;
// the inverse-ZCAM result is replaced by 0 if it is non-finite and the final
// output is clamped to [0, 1]. The clamp absorbs float/table error only.
//
// There is no global in-gamut early-out: target-inside colours outside the
// core are compressed too, which is the report method's "preserve relative
// differences" trade-off, not a bug (README 7.3).

//!PARAM a4z_tau
//!DESC core area as a fraction of the target gamut area
//!TYPE DYNAMIC float
//!MINIMUM 0.01
//!MAXIMUM 0.99
0.5

//!PARAM a4z_align_strength
//!DESC hue alignment strength, 1 = fully aligned, 0 = no warp
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM a4z_reference_white
//!DESC luminance in cd/m2 represented by linear RGB 1
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 1000.0
203.0

//!PARAM a4z_background
//!DESC background luminance as a percentage of the reference white
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 100.0
20.0

//!PARAM a4z_surround
//!DESC ZCAM surround: 0 = dark, 1 = dim, 2 = average
//!TYPE DYNAMIC int
//!MINIMUM 0
//!MAXIMUM 2
2

//!PARAM a4z_input_clip
//!DESC 1 = clamp channels to [0,1], 0 = clamp negatives and normalize peak
//!TYPE DYNAMIC int
//!MINIMUM 0
//!MAXIMUM 1
1

//!HOOK OUTPUT
//!SAVE A4Z_KEYS
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, key hues)

// Pass 1: runtime key hues and Izw.
//
// The twelve hues depend on the viewing conditions, so they cannot be
// hard-coded (README 3.5). Columns 0..5 hold S_k in .xy and T_k in .zw, where
// k = R, Y, G, C, B, M; column 6 holds Izw in .xy. All other columns are 0.
// [report A4.1.2; construction design choice; values computed at runtime]

const float A4Z_N = 2610.0 / 16384.0;
const float A4Z_P = 1.7 * 2523.0 / 32.0;
const float A4Z_C1 = 3424.0 / 4096.0;
const float A4Z_C2 = 2413.0 / 128.0;
const float A4Z_C3 = 2392.0 / 128.0;
const float A4Z_EPSILON = 3.7035226210190005e-11;
const float A4Z_HUE_OFFSET = 89.038;
const float A4Z_EXPONENT = 1.0 / 0.74;

const vec3 A4Z_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

// Rows of the reference matrices, written in column-major order so that
// vector * matrix equals the reference row-vector product.
const mat3 A4Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A4Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A4Z_BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);

const mat3 A4Z_BT709_TO_XYZ = mat3(
    0.41239079926595923, 0.357584339383878,   0.1804807884018343,
    0.21263900587151022, 0.715168678767756,   0.07219231536073371,
    0.01933081871559182, 0.11919477979462598, 0.9505321522496607
);

struct A4ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a4z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A4ZViewing a4z_make_viewing(float izw) {
    A4ZViewing viewing;
    float fs = a4z_surround_fs(a4z_surround);
    float fb = sqrt(a4z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

// Absolute XYZ -> (Iz, az, bz). The ZCAM paper's Eq. (4) typography swaps one
// cross coordinate [paper/xu.glsl]; the verified model uses X' = 1.15X - 0.15Z,
// Y' = 0.66Y + 0.34X, Z' = Z (README 3.2).
vec3 a4z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A4Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A4Z_N));
    vec3 encoded = pow((vec3(A4Z_C1) + A4Z_C2 * lms) /
                       (vec3(1.0) + A4Z_C3 * lms), vec3(A4Z_P));
    vec3 iab = encoded * A4Z_ZCAM_A;
    return vec3(max(iab.x - A4Z_EPSILON, 0.0), iab.y, iab.z);
}

// Absolute XYZ -> normalized (j, c, h) [paper/xu.glsl; README 3.2/3.4].
vec3 a4z_xyz_to_jch(vec3 xyz_abs, A4ZViewing viewing) {
    vec3 iab = a4z_xyz_to_iab(xyz_abs);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float j = pow(iab.x / viewing.izw, viewing.alpha);
    float c = viewing.chroma_factor * pow(radius, 0.74) *
              pow(max(eccentricity, 1e-12), 0.068);
    float h = mod(degrees(atan(iab.z, iab.y)), 360.0);
    return vec3(j, c, h);
}

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

vec3 a4z_key_rgb(int index) {
    if (index == 0) {
        return vec3(1.0, 0.0, 0.0);
    }
    if (index == 1) {
        return vec3(1.0, 1.0, 0.0);
    }
    if (index == 2) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (index == 3) {
        return vec3(0.0, 1.0, 1.0);
    }
    if (index == 4) {
        return vec3(0.0, 0.0, 1.0);
    }
    return vec3(1.0, 0.0, 1.0);
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column > 512) {
        return;
    }
    if (column > 6) {
        imageStore(out_image, ivec2(column, 0), vec4(0.0));
        return;
    }

    vec3 white_iab = a4z_xyz_to_iab(A4Z_D65 * a4z_reference_white);
    float izw = max(white_iab.x, 0.0);
    A4ZViewing viewing = a4z_make_viewing(izw);

    if (column == 6) {
        imageStore(out_image, ivec2(column, 0),
                   vec4(a4z_pack(izw), 0.0, 0.0));
        return;
    }

    vec3 key = a4z_key_rgb(column);
    vec3 source_jch = a4z_xyz_to_jch(
        (key * A4Z_BT2020_TO_XYZ) * a4z_reference_white, viewing
    );
    vec3 target_jch = a4z_xyz_to_jch(
        (key * A4Z_BT709_TO_XYZ) * a4z_reference_white, viewing
    );

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(a4z_pack(source_jch.z), a4z_pack(target_jch.z))
    );
}

// ---------------------------------------------------------------------------
// Pass 2: target operational cusp per target-hue column.
//
// The cusp is the maximum first-crossing chroma over j in [0, 1]: 257 uniform
// samples plus 40 golden-section refinements, matching the float64 reference
// (README 4.1). Constant-hue boundaries start at c = 0 (README 6.1).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4Z_CUSP_T
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4Z_KEYS
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, target cusp)

const float A4Z_N = 2610.0 / 16384.0;
const float A4Z_P = 1.7 * 2523.0 / 32.0;
const float A4Z_C1 = 3424.0 / 4096.0;
const float A4Z_C2 = 2413.0 / 128.0;
const float A4Z_C3 = 2392.0 / 128.0;
const float A4Z_EPSILON = 3.7035226210190005e-11;
const float A4Z_HUE_OFFSET = 89.038;
const float A4Z_EXPONENT = 1.0 / 0.74;
const float A4Z_CMAX = 64.0;
const int A4Z_BISECTIONS = 32;
const int A4Z_CUSP_SCAN = 257;
const int A4Z_CUSP_GOLDEN = 40;
const int A4Z_HUES = 4097;
const float A4Z_INVPHI = 0.6180339887498949;

const mat3 A4Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A4Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A4Z_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 A4Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A4ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a4z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A4ZViewing a4z_make_viewing(float izw) {
    A4ZViewing viewing;
    float fs = a4z_surround_fs(a4z_surround);
    float fb = sqrt(a4z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

// Cartesian normalized (j, c cos h, c sin h) -> relative XYZ (Y = 1 white).
// The inverse exponent is exactly 1/0.74; 1.3514 is a rounded printing
// [paper/xu.glsl; README 3.3].
vec3 a4z_jab_to_xyz(vec3 jab, A4ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A4Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A4Z_EPSILON, radius * direction) *
                   A4Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A4Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A4Z_C1), vec3(0.0)) /
        max(vec3(A4Z_C2) - A4Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A4Z_N)
    );
    vec3 xyzp = lms * A4Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a4z_reference_white;
}

bool a4z_inside_point(float j, float c, vec2 direction, bool source,
                      A4ZViewing viewing) {
    vec3 xyz_rel = a4z_jab_to_xyz(
        vec3(j, c * direction.x, c * direction.y), viewing
    );
    vec3 rgb;
    if (source) {
        rgb = xyz_rel * A4Z_XYZ_TO_BT2020;
    } else {
        rgb = xyz_rel * A4Z_XYZ_TO_BT709;
    }
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

// First c at which (j, c, h) leaves the gamut, starting from c = 0
// [design choice; README 6.1]. j is clamped first; j <= 0, a c = 0 axis
// outside the cube, and still-inside-at-CMAX have the documented degenerate
// values.
float a4z_boundary(float j, float hue_degrees, bool source,
                   A4ZViewing viewing) {
    float jc = clamp(j, 0.0, 1.0);
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));
    bool inside_zero = a4z_inside_point(jc, 0.0, direction, source, viewing);
    bool inside_cmax = a4z_inside_point(jc, A4Z_CMAX, direction, source,
                                        viewing);

    if (jc <= 0.0 || !inside_zero) {
        return 0.0;
    }
    if (inside_cmax) {
        return A4Z_CMAX;
    }

    float low = 0.0;
    float high = A4Z_CMAX;
    for (int i = 0; i < A4Z_BISECTIONS; i++) {
        float middle = 0.5 * (low + high);
        if (a4z_inside_point(jc, middle, direction, source, viewing)) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return low;
}

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_fetch_izw() {
    vec4 stored = A4Z_KEYS_mul * texelFetch(A4Z_KEYS_raw, ivec2(6, 0), 0);
    return stored.x + stored.y / 1024.0;
}

// 257-point scan then 40 golden-section refinements. The returned chroma is
// max(refined, the three sampled neighbours), as in the float64 reference.
vec2 a4z_find_cusp(float hue_degrees, bool source, A4ZViewing viewing) {
    float best_chroma = -1.0;
    float best_lightness = 0.0;
    float left_chroma = 0.0;
    float right_chroma = 0.0;
    float previous_chroma = 0.0;
    int best_index = 0;

    for (int i = 0; i < A4Z_CUSP_SCAN; i++) {
        float lightness = float(i) / 256.0;
        float chroma = a4z_boundary(
            lightness, hue_degrees, source, viewing
        );
        if (chroma > best_chroma) {
            best_chroma = chroma;
            best_lightness = lightness;
            best_index = i;
            if (i == 0) {
                left_chroma = chroma;
            } else {
                left_chroma = previous_chroma;
            }
            right_chroma = chroma;
        } else if (i == best_index + 1) {
            right_chroma = chroma;
        }
        previous_chroma = chroma;
    }

    int lower_index = max(best_index - 1, 0);
    int upper_index = min(best_index + 1, A4Z_CUSP_SCAN - 1);
    float a = float(lower_index) / 256.0;
    float b = float(upper_index) / 256.0;
    float c = b - A4Z_INVPHI * (b - a);
    float d = a + A4Z_INVPHI * (b - a);
    float fc = a4z_boundary(c, hue_degrees, source, viewing);
    float fd = a4z_boundary(d, hue_degrees, source, viewing);

    for (int i = 0; i < A4Z_CUSP_GOLDEN; i++) {
        if (fc >= fd) {
            b = d;
        } else {
            a = c;
        }
        float next_c = b - A4Z_INVPHI * (b - a);
        float next_d = a + A4Z_INVPHI * (b - a);
        fc = a4z_boundary(next_c, hue_degrees, source, viewing);
        fd = a4z_boundary(next_d, hue_degrees, source, viewing);
        c = next_c;
        d = next_d;
    }

    float golden_lightness = 0.5 * (a + b);
    float golden_chroma = a4z_boundary(
        golden_lightness, hue_degrees, source, viewing
    );
    float sampled = max(max(left_chroma, best_chroma), right_chroma);
    return vec2(golden_lightness, max(golden_chroma, sampled));
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column >= A4Z_HUES) {
        return;
    }

    A4ZViewing viewing = a4z_make_viewing(a4z_fetch_izw());
    float hue = 360.0 * float(column) / float(A4Z_HUES - 1);
    vec2 cusp = a4z_find_cusp(hue, false, viewing);

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(a4z_pack(cusp.x), a4z_pack(cusp.y))
    );
}

// ---------------------------------------------------------------------------
// Pass 3: source operational cusp per aligned-hue column.
//
// Columns are indexed by h_a, exactly like the target table. The source cusp
// is evaluated at h_u = A_s^-1(h_a); evaluating it at h_a would rotate the
// source shell twice [report A4.1.2; alignment construction design choice].
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4Z_CUSP_S
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4Z_KEYS
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, source cusp)

const float A4Z_N = 2610.0 / 16384.0;
const float A4Z_P = 1.7 * 2523.0 / 32.0;
const float A4Z_C1 = 3424.0 / 4096.0;
const float A4Z_C2 = 2413.0 / 128.0;
const float A4Z_C3 = 2392.0 / 128.0;
const float A4Z_EPSILON = 3.7035226210190005e-11;
const float A4Z_HUE_OFFSET = 89.038;
const float A4Z_EXPONENT = 1.0 / 0.74;
const float A4Z_CMAX = 64.0;
const int A4Z_BISECTIONS = 32;
const int A4Z_CUSP_SCAN = 257;
const int A4Z_CUSP_GOLDEN = 40;
const int A4Z_HUES = 4097;
const float A4Z_INVPHI = 0.6180339887498949;

const mat3 A4Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A4Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A4Z_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 A4Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A4ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a4z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A4ZViewing a4z_make_viewing(float izw) {
    A4ZViewing viewing;
    float fs = a4z_surround_fs(a4z_surround);
    float fb = sqrt(a4z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a4z_jab_to_xyz(vec3 jab, A4ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A4Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A4Z_EPSILON, radius * direction) *
                   A4Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A4Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A4Z_C1), vec3(0.0)) /
        max(vec3(A4Z_C2) - A4Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A4Z_N)
    );
    vec3 xyzp = lms * A4Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a4z_reference_white;
}

bool a4z_inside_point(float j, float c, vec2 direction, bool source,
                      A4ZViewing viewing) {
    vec3 xyz_rel = a4z_jab_to_xyz(
        vec3(j, c * direction.x, c * direction.y), viewing
    );
    vec3 rgb;
    if (source) {
        rgb = xyz_rel * A4Z_XYZ_TO_BT2020;
    } else {
        rgb = xyz_rel * A4Z_XYZ_TO_BT709;
    }
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float a4z_boundary(float j, float hue_degrees, bool source,
                   A4ZViewing viewing) {
    float jc = clamp(j, 0.0, 1.0);
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));
    bool inside_zero = a4z_inside_point(jc, 0.0, direction, source, viewing);
    bool inside_cmax = a4z_inside_point(jc, A4Z_CMAX, direction, source,
                                        viewing);

    if (jc <= 0.0 || !inside_zero) {
        return 0.0;
    }
    if (inside_cmax) {
        return A4Z_CMAX;
    }

    float low = 0.0;
    float high = A4Z_CMAX;
    for (int i = 0; i < A4Z_BISECTIONS; i++) {
        float middle = 0.5 * (low + high);
        if (a4z_inside_point(jc, middle, direction, source, viewing)) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return low;
}

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float a4z_fetch_izw() {
    vec4 stored = A4Z_KEYS_mul * texelFetch(A4Z_KEYS_raw, ivec2(6, 0), 0);
    return stored.x + stored.y / 1024.0;
}

void a4z_fetch_key_hues(out float source[6], out float target[6]) {
    for (int k = 0; k < 6; k++) {
        vec4 stored = A4Z_KEYS_mul *
                      texelFetch(A4Z_KEYS_raw, ivec2(k, 0), 0);
        source[k] = a4z_unpack(stored.xy);
        target[k] = a4z_unpack(stored.zw);
    }
}

// The node list is periodic: index 6 means node 0 + 360 degrees.
float a4z_node(float values[6], int index) {
    if (index >= 6) {
        return values[0] + 360.0;
    }
    return values[index];
}

// Falls back to strength 0 for the whole condition if either six-hue set is
// not strictly increasing around the circle [design choice; README 5.2].
float a4z_effective_strength(float requested, float source[6],
                             float target[6]) {
    bool ordered = true;

    for (int i = 1; i < 6; i++) {
        if (!(source[i] > source[i - 1])) {
            ordered = false;
        }
        if (!(target[i] > target[i - 1])) {
            ordered = false;
        }
    }
    if (!(source[0] + 360.0 > source[5])) {
        ordered = false;
    }
    if (!(target[0] + 360.0 > target[5])) {
        ordered = false;
    }
    if (!ordered) {
        return 0.0;
    }
    return clamp(requested, 0.0, 1.0);
}

float a4z_unwrap_hue(float hue, float cut) {
    float wrapped = mod(hue, 360.0);
    if (wrapped < mod(cut, 360.0)) {
        return wrapped + 360.0;
    }
    return wrapped;
}

int a4z_image_segment(float u, float source[6], float target[6],
                      float strength) {
    int k = 0;

    for (int i = 1; i < 6; i++) {
        float node = mix(a4z_node(source, i), a4z_node(target, i), strength);
        if (u >= node) {
            k = i;
        }
    }
    return k;
}

// Analytic inverse of the piecewise-linear periodic warp [design choice].
float a4z_align_inverse(float hue, float strength, float source[6],
                        float target[6]) {
    if (strength <= 0.0) {
        return mod(hue, 360.0);
    }

    float image0 = mix(a4z_node(source, 0), a4z_node(target, 0), strength);
    float u = a4z_unwrap_hue(hue, image0);
    int k = a4z_image_segment(u, source, target, strength);
    float s0 = a4z_node(source, k);
    float s1 = a4z_node(source, k + 1);
    float t0 = a4z_node(target, k);
    float t1 = a4z_node(target, k + 1);
    float slope = (t1 - t0) / (s1 - s0);
    float b = 1.0 + strength * (slope - 1.0);
    float a = strength * (t0 - slope * s0);
    return mod((u - a) / b, 360.0);
}

vec2 a4z_find_cusp(float hue_degrees, bool source, A4ZViewing viewing) {
    float best_chroma = -1.0;
    float best_lightness = 0.0;
    float left_chroma = 0.0;
    float right_chroma = 0.0;
    float previous_chroma = 0.0;
    int best_index = 0;

    for (int i = 0; i < A4Z_CUSP_SCAN; i++) {
        float lightness = float(i) / 256.0;
        float chroma = a4z_boundary(
            lightness, hue_degrees, source, viewing
        );
        if (chroma > best_chroma) {
            best_chroma = chroma;
            best_lightness = lightness;
            best_index = i;
            if (i == 0) {
                left_chroma = chroma;
            } else {
                left_chroma = previous_chroma;
            }
            right_chroma = chroma;
        } else if (i == best_index + 1) {
            right_chroma = chroma;
        }
        previous_chroma = chroma;
    }

    int lower_index = max(best_index - 1, 0);
    int upper_index = min(best_index + 1, A4Z_CUSP_SCAN - 1);
    float a = float(lower_index) / 256.0;
    float b = float(upper_index) / 256.0;
    float c = b - A4Z_INVPHI * (b - a);
    float d = a + A4Z_INVPHI * (b - a);
    float fc = a4z_boundary(c, hue_degrees, source, viewing);
    float fd = a4z_boundary(d, hue_degrees, source, viewing);

    for (int i = 0; i < A4Z_CUSP_GOLDEN; i++) {
        if (fc >= fd) {
            b = d;
        } else {
            a = c;
        }
        float next_c = b - A4Z_INVPHI * (b - a);
        float next_d = a + A4Z_INVPHI * (b - a);
        fc = a4z_boundary(next_c, hue_degrees, source, viewing);
        fd = a4z_boundary(next_d, hue_degrees, source, viewing);
        c = next_c;
        d = next_d;
    }

    float golden_lightness = 0.5 * (a + b);
    float golden_chroma = a4z_boundary(
        golden_lightness, hue_degrees, source, viewing
    );
    float sampled = max(max(left_chroma, best_chroma), right_chroma);
    return vec2(golden_lightness, max(golden_chroma, sampled));
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column >= A4Z_HUES) {
        return;
    }

    A4ZViewing viewing = a4z_make_viewing(a4z_fetch_izw());
    float source_hues[6];
    float target_hues[6];
    a4z_fetch_key_hues(source_hues, target_hues);
    float strength = a4z_effective_strength(
        a4z_align_strength, source_hues, target_hues
    );

    float hue = 360.0 * float(column) / float(A4Z_HUES - 1);
    float source_hue = a4z_align_inverse(
        hue, strength, source_hues, target_hues
    );
    vec2 cusp = a4z_find_cusp(source_hue, true, viewing);

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(a4z_pack(cusp.x), a4z_pack(cusp.y))
    );
}

// ---------------------------------------------------------------------------
// Pass 4: boundary chroma table.
//
// Both columns use the target cusp's row parameterisation (row 256 is the
// target cusp; 256 samples on each side). The source column is evaluated on
// the same j rows at h_u = A_s^-1(h_a); the source cusp is not row 256 and is
// stored separately in A4Z_CUSP_S (README 8.1).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4Z_BOUND
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 513
//!COMPUTE 16 16
//!BIND A4Z_KEYS
//!BIND A4Z_CUSP_T
//!BIND A4Z_CUSP_S
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, boundary table)

const int A4Z_HUES = 4097;

const float A4Z_N = 2610.0 / 16384.0;
const float A4Z_P = 1.7 * 2523.0 / 32.0;
const float A4Z_C1 = 3424.0 / 4096.0;
const float A4Z_C2 = 2413.0 / 128.0;
const float A4Z_C3 = 2392.0 / 128.0;
const float A4Z_EPSILON = 3.7035226210190005e-11;
const float A4Z_HUE_OFFSET = 89.038;
const float A4Z_EXPONENT = 1.0 / 0.74;
const float A4Z_CMAX = 64.0;
const int A4Z_BISECTIONS = 32;

const mat3 A4Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A4Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A4Z_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 A4Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A4ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a4z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A4ZViewing a4z_make_viewing(float izw) {
    A4ZViewing viewing;
    float fs = a4z_surround_fs(a4z_surround);
    float fb = sqrt(a4z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a4z_jab_to_xyz(vec3 jab, A4ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A4Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A4Z_EPSILON, radius * direction) *
                   A4Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A4Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A4Z_C1), vec3(0.0)) /
        max(vec3(A4Z_C2) - A4Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A4Z_N)
    );
    vec3 xyzp = lms * A4Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a4z_reference_white;
}

bool a4z_inside_point(float j, float c, vec2 direction, bool source,
                      A4ZViewing viewing) {
    vec3 xyz_rel = a4z_jab_to_xyz(
        vec3(j, c * direction.x, c * direction.y), viewing
    );
    vec3 rgb;
    if (source) {
        rgb = xyz_rel * A4Z_XYZ_TO_BT2020;
    } else {
        rgb = xyz_rel * A4Z_XYZ_TO_BT709;
    }
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float a4z_boundary(float j, float hue_degrees, bool source,
                   A4ZViewing viewing) {
    float jc = clamp(j, 0.0, 1.0);
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));
    bool inside_zero = a4z_inside_point(jc, 0.0, direction, source, viewing);
    bool inside_cmax = a4z_inside_point(jc, A4Z_CMAX, direction, source,
                                        viewing);

    if (jc <= 0.0 || !inside_zero) {
        return 0.0;
    }
    if (inside_cmax) {
        return A4Z_CMAX;
    }

    float low = 0.0;
    float high = A4Z_CMAX;
    for (int i = 0; i < A4Z_BISECTIONS; i++) {
        float middle = 0.5 * (low + high);
        if (a4z_inside_point(jc, middle, direction, source, viewing)) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return low;
}

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float a4z_fetch_izw() {
    vec4 stored = A4Z_KEYS_mul * texelFetch(A4Z_KEYS_raw, ivec2(6, 0), 0);
    return stored.x + stored.y / 1024.0;
}

void a4z_fetch_key_hues(out float source[6], out float target[6]) {
    for (int k = 0; k < 6; k++) {
        vec4 stored = A4Z_KEYS_mul *
                      texelFetch(A4Z_KEYS_raw, ivec2(k, 0), 0);
        source[k] = a4z_unpack(stored.xy);
        target[k] = a4z_unpack(stored.zw);
    }
}

float a4z_node(float values[6], int index) {
    if (index >= 6) {
        return values[0] + 360.0;
    }
    return values[index];
}

float a4z_effective_strength(float requested, float source[6],
                             float target[6]) {
    bool ordered = true;

    for (int i = 1; i < 6; i++) {
        if (!(source[i] > source[i - 1])) {
            ordered = false;
        }
        if (!(target[i] > target[i - 1])) {
            ordered = false;
        }
    }
    if (!(source[0] + 360.0 > source[5])) {
        ordered = false;
    }
    if (!(target[0] + 360.0 > target[5])) {
        ordered = false;
    }
    if (!ordered) {
        return 0.0;
    }
    return clamp(requested, 0.0, 1.0);
}

float a4z_unwrap_hue(float hue, float cut) {
    float wrapped = mod(hue, 360.0);
    if (wrapped < mod(cut, 360.0)) {
        return wrapped + 360.0;
    }
    return wrapped;
}

int a4z_image_segment(float u, float source[6], float target[6],
                      float strength) {
    int k = 0;

    for (int i = 1; i < 6; i++) {
        float node = mix(a4z_node(source, i), a4z_node(target, i), strength);
        if (u >= node) {
            k = i;
        }
    }
    return k;
}

float a4z_align_inverse(float hue, float strength, float source[6],
                        float target[6]) {
    if (strength <= 0.0) {
        return mod(hue, 360.0);
    }

    float image0 = mix(a4z_node(source, 0), a4z_node(target, 0), strength);
    float u = a4z_unwrap_hue(hue, image0);
    int k = a4z_image_segment(u, source, target, strength);
    float s0 = a4z_node(source, k);
    float s1 = a4z_node(source, k + 1);
    float t0 = a4z_node(target, k);
    float t1 = a4z_node(target, k + 1);
    float slope = (t1 - t0) / (s1 - s0);
    float b = 1.0 + strength * (slope - 1.0);
    float a = strength * (t0 - slope * s0);
    return mod((u - a) / b, 360.0);
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);

    if (position.x >= A4Z_HUES || position.y > 512) {
        return;
    }

    A4ZViewing viewing = a4z_make_viewing(a4z_fetch_izw());
    float source_hues[6];
    float target_hues[6];
    a4z_fetch_key_hues(source_hues, target_hues);
    float strength = a4z_effective_strength(
        a4z_align_strength, source_hues, target_hues
    );

    float hue = 360.0 * float(position.x) / float(A4Z_HUES - 1);
    float source_hue = a4z_align_inverse(
        hue, strength, source_hues, target_hues
    );

    vec4 stored = A4Z_CUSP_T_mul *
                  texelFetch(A4Z_CUSP_T_raw, ivec2(position.x, 0), 0);
    float cusp_lightness = a4z_unpack(stored.xy);
    float lightness;
    if (position.y <= 256) {
        lightness = cusp_lightness * float(position.y) / 256.0;
    } else {
        lightness = cusp_lightness +
                    (1.0 - cusp_lightness) *
                    float(position.y - 256) / 256.0;
    }

    float target_chroma = a4z_boundary(
        lightness, hue, false, viewing
    );
    float source_chroma = a4z_boundary(
        lightness, source_hue, true, viewing
    );

    imageStore(
        out_image,
        position,
        vec4(a4z_pack(target_chroma), a4z_pack(source_chroma))
    );
}

// ---------------------------------------------------------------------------
// Pass 5: cusp line.
//
// m = (j_sc - j_tc) / max(c_sc - c_tc, 1e-6), j0 = j_tc - m*c_tc. m may be
// positive or negative and the denominator floor is the reference's
// (README 4.2).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4Z_LINE
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4Z_CUSP_T
//!BIND A4Z_CUSP_S
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, cusp line)

const int A4Z_HUES = 4097;
const float A4Z_SHELL_FLOOR = 1e-6;

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column >= A4Z_HUES) {
        return;
    }

    vec4 target_cusp = A4Z_CUSP_T_mul *
                       texelFetch(A4Z_CUSP_T_raw, ivec2(column, 0), 0);
    vec4 source_cusp = A4Z_CUSP_S_mul *
                       texelFetch(A4Z_CUSP_S_raw, ivec2(column, 0), 0);
    float target_lightness = a4z_unpack(target_cusp.xy);
    float target_chroma = a4z_unpack(target_cusp.zw);
    float source_lightness = a4z_unpack(source_cusp.xy);
    float source_chroma = a4z_unpack(source_cusp.zw);

    float denominator = max(source_chroma - target_chroma, A4Z_SHELL_FLOOR);
    float slope = (source_lightness - target_lightness) / denominator;
    float intercept = target_lightness - slope * target_chroma;

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(a4z_pack(slope), a4z_pack(intercept))
    );
}

// ---------------------------------------------------------------------------
// Pass 6: anchor from the tau area solve.
//
// The triangle (0,0)-(0,1)-(c_a,j_a) is clipped by the stored target
// boundary; its area is integrated on the same 513 rows with 512 trapezoids
// and solved to tau * A_t by 32 bisections (README 4.4; 60 in the float64
// reference).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4Z_ANCHOR
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4Z_CUSP_T
//!BIND A4Z_LINE
//!BIND A4Z_BOUND
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, anchor)

const int A4Z_HUES = 4097;
const int A4Z_AREA_STEPS = 512;
const int A4Z_ANCHOR_BISECTIONS = 32;

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

// Exact reference triangle with its degenerate guards (README 4.3).
float a4z_triangle_chroma(float j, float ca, float ja) {
    float value;
    if (j <= ja) {
        value = ca * j / max(ja, 1e-12);
    } else {
        value = ca * (1.0 - j) / max(1.0 - ja, 1e-12);
    }
    if (ja <= 1e-6) {
        value = ca * (1.0 - j);
    }
    if (1.0 - ja <= 1e-6) {
        value = ca * j;
    }
    if (ca <= 0.0 || j < 0.0 || j > 1.0) {
        return 0.0;
    }
    return value;
}

float a4z_row_lightness(int row, float cusp_lightness) {
    if (row <= 256) {
        return cusp_lightness * float(row) / 256.0;
    }
    return cusp_lightness +
           (1.0 - cusp_lightness) * float(row - 256) / 256.0;
}

float a4z_target_boundary(int column, int row) {
    vec4 stored = A4Z_BOUND_mul *
                  texelFetch(A4Z_BOUND_raw, ivec2(column, row), 0);
    return stored.x + stored.y / 1024.0;
}

float a4z_gamut_area(float cusp_lightness, int column) {
    float previous_lightness = a4z_row_lightness(0, cusp_lightness);
    float previous_value = a4z_target_boundary(column, 0);
    float total = 0.0;

    for (int row = 1; row <= A4Z_AREA_STEPS; row++) {
        float lightness = a4z_row_lightness(row, cusp_lightness);
        float value = a4z_target_boundary(column, row);
        total += 0.5 * (previous_value + value) *
                 (lightness - previous_lightness);
        previous_lightness = lightness;
        previous_value = value;
    }
    return total;
}

float a4z_core_area(float anchor_chroma, float slope, float intercept,
                    float cusp_lightness, int column) {
    float anchor_lightness = intercept + slope * anchor_chroma;
    float previous_lightness = a4z_row_lightness(0, cusp_lightness);
    float previous_value = min(
        a4z_triangle_chroma(previous_lightness, anchor_chroma,
                            anchor_lightness),
        a4z_target_boundary(column, 0)
    );
    float total = 0.0;

    for (int row = 1; row <= A4Z_AREA_STEPS; row++) {
        float lightness = a4z_row_lightness(row, cusp_lightness);
        float value = min(
            a4z_triangle_chroma(lightness, anchor_chroma, anchor_lightness),
            a4z_target_boundary(column, row)
        );
        total += 0.5 * (previous_value + value) *
                 (lightness - previous_lightness);
        previous_lightness = lightness;
        previous_value = value;
    }
    return total;
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column >= A4Z_HUES) {
        return;
    }

    vec4 target_cusp = A4Z_CUSP_T_mul *
                       texelFetch(A4Z_CUSP_T_raw, ivec2(column, 0), 0);
    vec4 line_data = A4Z_LINE_mul *
                     texelFetch(A4Z_LINE_raw, ivec2(column, 0), 0);
    float cusp_lightness = a4z_unpack(target_cusp.xy);
    float cusp_chroma = a4z_unpack(target_cusp.zw);
    float slope = a4z_unpack(line_data.xy);
    float intercept = a4z_unpack(line_data.zw);

    float gamut_area = a4z_gamut_area(cusp_lightness, column);
    float maximum_area = a4z_core_area(
        cusp_chroma, slope, intercept, cusp_lightness, column
    );
    float target_area = min(a4z_tau * gamut_area, maximum_area);
    float lower = 0.0;
    float upper = cusp_chroma;

    for (int i = 0; i < A4Z_ANCHOR_BISECTIONS; i++) {
        float middle = 0.5 * (lower + upper);
        float area = a4z_core_area(
            middle, slope, intercept, cusp_lightness, column
        );
        if (area < target_area) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    float anchor_chroma = 0.5 * (lower + upper);
    float anchor_lightness = intercept + slope * anchor_chroma;

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(a4z_pack(anchor_chroma), a4z_pack(anchor_lightness))
    );
}

// ---------------------------------------------------------------------------
// Pass 7: pixel map.
//
// Forward ZCAM, alignment h -> h_a, core test, the three-region scalar map
// evaluated on the stored boundary table, then inverse ZCAM with h_a and the
// BT.709 matrix. Only neutral, c <= 1e-7 and in-core colours return early;
// there is no global in-gamut early-out (README 7.3, 8.4).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND A4Z_KEYS
//!BIND A4Z_CUSP_T
//!BIND A4Z_CUSP_S
//!BIND A4Z_LINE
//!BIND A4Z_ANCHOR
//!BIND A4Z_BOUND
//!DESC gamut mapping (BT.2407 annex 4 ZCAM, pixel map)

const float A4Z_N = 2610.0 / 16384.0;
const float A4Z_P = 1.7 * 2523.0 / 32.0;
const float A4Z_C1 = 3424.0 / 4096.0;
const float A4Z_C2 = 2413.0 / 128.0;
const float A4Z_C3 = 2392.0 / 128.0;
const float A4Z_EPSILON = 3.7035226210190005e-11;
const float A4Z_HUE_OFFSET = 89.038;
const float A4Z_EXPONENT = 1.0 / 0.74;
const float A4Z_CMAX = 64.0;
const float A4Z_NEUTRAL_SPAN = 1e-4;
const float A4Z_CHROMA_EPSILON = 1e-7;
const float A4Z_CORE_TOLERANCE = 1e-7;
const float A4Z_SHELL_FLOOR = 1e-6;
const float A4Z_CROSS_TOLERANCE = 1e-7;
const float A4Z_CROSS_PAD = 0.02;
const int A4Z_HUES = 4097;
const int A4Z_SCAN_SEGMENTS = 40;
const int A4Z_ROOT_ITERATIONS = 16;
const int A4Z_MODE_CORE = 0;
const int A4Z_MODE_TARGET = 1;
const int A4Z_MODE_OUTER = 2;

const vec3 A4Z_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 A4Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A4Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A4Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A4Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A4Z_BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);

const mat3 A4Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A4ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

struct A4ZTrace {
    float j;
    float c;
    float m;
    float j0;
    float ca;
    float ja;
    float jtc;
    float ctc;
    float csc;
    float ha;
    float b;
    bool ray;
};

float a4z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A4ZViewing a4z_make_viewing(float izw) {
    A4ZViewing viewing;
    float fs = a4z_surround_fs(a4z_surround);
    float fb = sqrt(a4z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a4z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A4Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A4Z_N));
    vec3 encoded = pow((vec3(A4Z_C1) + A4Z_C2 * lms) /
                       (vec3(1.0) + A4Z_C3 * lms), vec3(A4Z_P));
    vec3 iab = encoded * A4Z_ZCAM_A;
    return vec3(max(iab.x - A4Z_EPSILON, 0.0), iab.y, iab.z);
}

vec3 a4z_xyz_to_jch(vec3 xyz_abs, A4ZViewing viewing) {
    vec3 iab = a4z_xyz_to_iab(xyz_abs);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float j = pow(iab.x / viewing.izw, viewing.alpha);
    float c = viewing.chroma_factor * pow(radius, 0.74) *
              pow(max(eccentricity, 1e-12), 0.068);
    float h = mod(degrees(atan(iab.z, iab.y)), 360.0);
    return vec3(j, c, h);
}

vec3 a4z_jab_to_xyz(vec3 jab, A4ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A4Z_HUE_OFFSET)), -sin(radians(A4Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A4Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A4Z_EPSILON, radius * direction) *
                   A4Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A4Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A4Z_C1), vec3(0.0)) /
        max(vec3(A4Z_C2) - A4Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A4Z_N)
    );
    vec3 xyzp = lms * A4Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a4z_reference_white;
}

vec2 a4z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a4z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float a4z_fetch_izw() {
    vec4 stored = A4Z_KEYS_mul * texelFetch(A4Z_KEYS_raw, ivec2(6, 0), 0);
    return stored.x + stored.y / 1024.0;
}

void a4z_fetch_key_hues(out float source[6], out float target[6]) {
    for (int k = 0; k < 6; k++) {
        vec4 stored = A4Z_KEYS_mul *
                      texelFetch(A4Z_KEYS_raw, ivec2(k, 0), 0);
        source[k] = a4z_unpack(stored.xy);
        target[k] = a4z_unpack(stored.zw);
    }
}

float a4z_node(float values[6], int index) {
    if (index >= 6) {
        return values[0] + 360.0;
    }
    return values[index];
}

float a4z_effective_strength(float requested, float source[6],
                             float target[6]) {
    bool ordered = true;

    for (int i = 1; i < 6; i++) {
        if (!(source[i] > source[i - 1])) {
            ordered = false;
        }
        if (!(target[i] > target[i - 1])) {
            ordered = false;
        }
    }
    if (!(source[0] + 360.0 > source[5])) {
        ordered = false;
    }
    if (!(target[0] + 360.0 > target[5])) {
        ordered = false;
    }
    if (!ordered) {
        return 0.0;
    }
    return clamp(requested, 0.0, 1.0);
}

float a4z_unwrap_hue(float hue, float cut) {
    float wrapped = mod(hue, 360.0);
    if (wrapped < mod(cut, 360.0)) {
        return wrapped + 360.0;
    }
    return wrapped;
}

int a4z_source_segment(float u, float source[6]) {
    int k = 0;

    for (int i = 1; i < 6; i++) {
        if (u >= a4z_node(source, i)) {
            k = i;
        }
    }
    return k;
}

// h_a = A_s(h): source hue -> aligned target hue, degrees [0, 360).
float a4z_align_forward(float hue, float strength, float source[6],
                        float target[6]) {
    if (strength <= 0.0) {
        return mod(hue, 360.0);
    }

    float u = a4z_unwrap_hue(hue, a4z_node(source, 0));
    int k = a4z_source_segment(u, source);
    float s0 = a4z_node(source, k);
    float s1 = a4z_node(source, k + 1);
    float t0 = a4z_node(target, k);
    float t1 = a4z_node(target, k + 1);
    float slope = (t1 - t0) / (s1 - s0);
    float base = t0 + slope * (u - s0);
    return mod(u + strength * (base - u), 360.0);
}

// Hue tables use A4Z_HUES = 8*512+1 columns. Column 0 and column 4096 hold
// 0 and 360 degrees, so the seam is a real column pair and clamp filtering
// never reaches a wrong column (README 8.1).
float a4z_hue_coordinate(float hue_degrees) {
    float position = mod(hue_degrees, 360.0) / 360.0 * float(A4Z_HUES - 1);
    return (position + 0.5) / float(A4Z_HUES);
}

float a4z_row_coordinate(float j, float cusp_j) {
    float row;
    if (j <= cusp_j) {
        row = 256.0 * j / max(cusp_j, 1e-6);
    } else {
        row = 256.0 + 256.0 * (j - cusp_j) / max(1.0 - cusp_j, 1e-6);
    }
    return (clamp(row, 0.0, 512.0) + 0.5) / 513.0;
}

vec4 a4z_cusp_target_at(float hue_degrees) {
    return A4Z_CUSP_T_mul *
           textureLod(
               A4Z_CUSP_T_raw,
               vec2(a4z_hue_coordinate(hue_degrees), 0.5),
               0.0
           );
}

vec4 a4z_cusp_source_at(float hue_degrees) {
    return A4Z_CUSP_S_mul *
           textureLod(
               A4Z_CUSP_S_raw,
               vec2(a4z_hue_coordinate(hue_degrees), 0.5),
               0.0
           );
}

vec4 a4z_line_at(float hue_degrees) {
    return A4Z_LINE_mul *
           textureLod(
               A4Z_LINE_raw,
               vec2(a4z_hue_coordinate(hue_degrees), 0.5),
               0.0
           );
}

vec4 a4z_anchor_at(float hue_degrees) {
    return A4Z_ANCHOR_mul *
           textureLod(
               A4Z_ANCHOR_raw,
               vec2(a4z_hue_coordinate(hue_degrees), 0.5),
               0.0
           );
}

vec4 a4z_bound_sample(float j, float cusp_j, float hue_degrees) {
    vec2 coordinate = vec2(
        a4z_hue_coordinate(hue_degrees),
        a4z_row_coordinate(j, cusp_j)
    );
    return A4Z_BOUND_mul * textureLod(A4Z_BOUND_raw, coordinate, 0.0);
}

float a4z_target_chroma(float j, float cusp_j, float hue_degrees) {
    vec4 stored = a4z_bound_sample(j, cusp_j, hue_degrees);
    return a4z_unpack(stored.xy);
}

float a4z_triangle_chroma(float j, float ca, float ja) {
    float value;
    if (j <= ja) {
        value = ca * j / max(ja, 1e-12);
    } else {
        value = ca * (1.0 - j) / max(1.0 - ja, 1e-12);
    }
    if (ja <= 1e-6) {
        value = ca * (1.0 - j);
    }
    if (1.0 - ja <= 1e-6) {
        value = ca * j;
    }
    if (ca <= 0.0 || j < 0.0 || j > 1.0) {
        return 0.0;
    }
    return value;
}

// numpy's clip(value, low, high) returns high when high < low; GLSL's clamp
// is undefined in that case, so mirror numpy explicitly.
float a4z_clip_between(float value, float low, float high) {
    if (high < low) {
        return high;
    }
    return clamp(value, low, high);
}

float a4z_trace_lightness(A4ZTrace trace, float x) {
    if (trace.ray) {
        return trace.j0 + (trace.j - trace.j0) * x / max(trace.c, 1e-7);
    }
    return trace.j0 + trace.m * x + trace.b;
}

float a4z_trace_value(int mode, A4ZTrace trace, float x) {
    float j = a4z_trace_lightness(trace, x);
    vec4 stored = a4z_bound_sample(j, trace.jtc, trace.ha);
    float target = a4z_unpack(stored.xy);

    if (mode == A4Z_MODE_CORE) {
        return x - min(a4z_triangle_chroma(j, trace.ca, trace.ja), target);
    }
    if (mode == A4Z_MODE_TARGET) {
        return x - target;
    }
    float source = a4z_unpack(stored.zw);
    return x - max(source, target);
}

// First x in [low, high] with trace value going from <= tol to > tol:
// 40 scan segments (41 points) then 16 bisections [design choice; README
// 7.2]. Already outside at low returns low; no crossing returns high.
float a4z_first_cross(int mode, A4ZTrace trace, float low, float high) {
    float previous = low;
    float previous_value = a4z_trace_value(mode, trace, low);

    if (previous_value > A4Z_CROSS_TOLERANCE) {
        return low;
    }

    float root_low = low;
    float root_high = high;
    bool found = false;

    // Exit through the loop condition, not break: d3dcompiler_47's optimizer
    // miscompiles a break from this loop in the pixel pass (DXBC validation,
    // uninitialized temp), while the same early exit by condition compiles.
    // Both forms scan the same points and stop at the same iteration.
    for (int i = 1; i <= A4Z_SCAN_SEGMENTS && !found; i++) {
        float x = low + (high - low) * (float(i) / float(A4Z_SCAN_SEGMENTS));
        float value = a4z_trace_value(mode, trace, x);
        if (!found && previous_value <= A4Z_CROSS_TOLERANCE &&
            value > A4Z_CROSS_TOLERANCE) {
            found = true;
            root_low = previous;
            root_high = x;
        }
        previous = x;
        previous_value = value;
    }

    if (!found) {
        return high;
    }

    for (int i = 0; i < A4Z_ROOT_ITERATIONS; i++) {
        float middle = 0.5 * (root_low + root_high);
        if (a4z_trace_value(mode, trace, middle) <= A4Z_CROSS_TOLERANCE) {
            root_low = middle;
        } else {
            root_high = middle;
        }
    }
    return 0.5 * (root_low + root_high);
}

vec2 a4z_region1(A4ZTrace trace) {
    float core = a4z_first_cross(
        A4Z_MODE_CORE, trace, 0.0, min(trace.c, trace.ctc + A4Z_CROSS_PAD)
    );
    float target = a4z_first_cross(
        A4Z_MODE_TARGET, trace, core, trace.ctc + A4Z_CROSS_PAD
    );
    float source = a4z_first_cross(
        A4Z_MODE_OUTER, trace, target, trace.csc + A4Z_CROSS_PAD
    );
    source = max(source, trace.c);
    float shell = max(source - core, A4Z_SHELL_FLOOR);
    float scale = (target - core) / shell;
    float chroma = a4z_clip_between(
        core + (trace.c - core) * scale, core, target
    );
    float lightness = trace.j0 + trace.m * chroma + trace.b;
    return vec2(lightness, chroma);
}

vec2 a4z_region2(A4ZTrace trace) {
    float denominator = max(trace.c, 1e-7);
    float core = a4z_first_cross(
        A4Z_MODE_CORE, trace, 0.0, min(trace.c, trace.ctc + A4Z_CROSS_PAD)
    );
    float target = a4z_first_cross(
        A4Z_MODE_TARGET, trace, core, trace.ctc + A4Z_CROSS_PAD
    );
    float source = a4z_first_cross(
        A4Z_MODE_OUTER, trace, target, trace.csc + A4Z_CROSS_PAD
    );
    source = max(source, trace.c);
    float shell = max(source - core, A4Z_SHELL_FLOOR);
    float scale = (target - core) / shell;
    float chroma = a4z_clip_between(
        core + (trace.c - core) * scale, core, target
    );
    float lightness = trace.j0 +
                      (trace.j - trace.j0) * chroma / denominator;
    return vec2(lightness, chroma);
}

vec2 a4z_region3(A4ZTrace trace) {
    vec4 stored = a4z_bound_sample(trace.j, trace.jtc, trace.ha);
    float target = a4z_unpack(stored.xy);
    float source = a4z_unpack(stored.zw);
    float core = min(
        a4z_triangle_chroma(trace.j, trace.ca, trace.ja), target
    );
    float outer = max(source, target);
    float shell = max(outer - core, A4Z_SHELL_FLOOR);
    float scale = (target - core) / shell;
    float chroma = a4z_clip_between(
        core + (trace.c - core) * scale, core, target
    );
    return vec2(trace.j, chroma);
}

vec3 a4z_encode(A4ZViewing viewing, float j, float c, float hue_degrees) {
    float angle = radians(hue_degrees);
    return a4z_jab_to_xyz(
        vec3(j, c * cos(angle), c * sin(angle)), viewing
    ) * A4Z_XYZ_TO_BT709;
}

vec3 a4z_sanitize(vec3 rgb) {
    vec3 safe = rgb;

    if (any(isnan(safe)) || any(isinf(safe))) {
        safe = vec3(
            (isnan(rgb.x) || isinf(rgb.x)) ? 0.0 : rgb.x,
            (isnan(rgb.y) || isinf(rgb.y)) ? 0.0 : rgb.y,
            (isnan(rgb.z) || isinf(rgb.z)) ? 0.0 : rgb.z
        );
    }

    if (a4z_input_clip == 1) {
        return clamp(safe, 0.0, 1.0);
    }

    safe = max(safe, vec3(0.0));
    float peak = max(max(safe.r, safe.g), safe.b);
    if (peak > 1.0) {
        safe = safe / peak;
    }
    return safe;
}

vec3 a4z_map_rgb(vec3 rgb) {
    float low = min(min(rgb.r, rgb.g), rgb.b);
    float high = max(max(rgb.r, rgb.g), rgb.b);
    vec3 passthrough = rgb * A4Z_BT2020_TO_XYZ * A4Z_XYZ_TO_BT709;

    if (high - low <= A4Z_NEUTRAL_SPAN) {
        return passthrough;
    }

    A4ZViewing viewing = a4z_make_viewing(a4z_fetch_izw());
    vec3 xyz_rel = rgb * A4Z_BT2020_TO_XYZ;
    vec3 jch = a4z_xyz_to_jch(
        xyz_rel * a4z_reference_white, viewing
    );
    float j = clamp(jch.x, 0.0, 1.0);
    float c = jch.y;
    float h = jch.z;

    if (c <= A4Z_CHROMA_EPSILON) {
        return passthrough;
    }

    float source_hues[6];
    float target_hues[6];
    a4z_fetch_key_hues(source_hues, target_hues);
    float strength = a4z_effective_strength(
        a4z_align_strength, source_hues, target_hues
    );
    float aligned_hue = a4z_align_forward(
        h, strength, source_hues, target_hues
    );

    vec4 target_cusp = a4z_cusp_target_at(aligned_hue);
    vec4 source_cusp = a4z_cusp_source_at(aligned_hue);
    vec4 line_data = a4z_line_at(aligned_hue);
    vec4 anchor_data = a4z_anchor_at(aligned_hue);
    float cusp_lightness = a4z_unpack(target_cusp.xy);
    float cusp_chroma = a4z_unpack(target_cusp.zw);
    float source_cusp_chroma = a4z_unpack(source_cusp.zw);
    float slope = a4z_unpack(line_data.xy);
    float intercept = a4z_unpack(line_data.zw);
    float anchor_chroma = a4z_unpack(anchor_data.xy);
    float anchor_lightness = a4z_unpack(anchor_data.zw);

    float target = a4z_target_chroma(j, cusp_lightness, aligned_hue);
    float core = min(
        a4z_triangle_chroma(j, anchor_chroma, anchor_lightness), target
    );

    if (c <= core + A4Z_CORE_TOLERANCE || c <= 1e-12) {
        return a4z_encode(viewing, j, c, aligned_hue);
    }

    A4ZTrace trace;
    trace.j = j;
    trace.c = c;
    trace.m = slope;
    trace.j0 = intercept;
    trace.ca = anchor_chroma;
    trace.ja = anchor_lightness;
    trace.jtc = cusp_lightness;
    trace.ctc = cusp_chroma;
    trace.csc = source_cusp_chroma;
    trace.ha = aligned_hue;
    trace.b = 0.0;
    trace.ray = false;

    float region1 = (j - (intercept + slope * c)) * slope;
    float region2 = (j - intercept) * slope;
    vec2 mapped;

    if (region1 > 0.0) {
        trace.b = j - intercept - slope * c;
        trace.ray = false;
        mapped = a4z_region1(trace);
    } else if (region2 > 0.0) {
        trace.ray = true;
        mapped = a4z_region2(trace);
    } else {
        trace.ray = false;
        mapped = a4z_region3(trace);
    }

    return a4z_encode(viewing, mapped.x, mapped.y, aligned_hue);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb = a4z_sanitize(color.rgb);
    vec3 mapped = a4z_map_rgb(rgb);

    if (any(isnan(mapped)) || any(isinf(mapped))) {
        mapped = vec3(0.0);
    }

    return vec4(clamp(mapped, 0.0, 1.0), color.a);
}
