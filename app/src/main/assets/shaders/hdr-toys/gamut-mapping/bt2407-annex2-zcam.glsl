// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 2 combined hue/lightness/chroma gamut mapping re-based on
// ZCAM (Safdar, Hardeberg & Luo, Opt. Express 29(4) 6036, 2021).
// Input is linear BT.2020 RGB, output is linear BT.709 RGB, both D65 with
// unit white 1; alpha is passed through untouched.
//
// Pipeline (six OUTPUT-hook passes):
//
//   A2Z_HUES      16x1      six pure key colours and the 1.09 yellow/cyan
//                           key pairs
//   A2Z_BOUNDARY  4097x513  target and source first-crossing chroma
//   A2Z_FOCAL_RAW 1024x1    table cusp chord (j0, c0) per hue
//   A2Z_FOCAL     1024x2    smoothed focal profile, dips, ramp, indices
//   A2Z_KEYS      16x1      runtime A2.5 coefficients, thresholds, phi nodes
//   A2Z_MAP       screen    README 8 pixel pipeline
//
// Every saved texture is rgba16f. Each scalar is stored as two half
// components, high = floor(value), low = (value - high) * 1024, and is
// reconstructed as high + low / 1024. Both parts are linear in the stored
// value, so hardware bilinear filtering still interpolates the scalar; the
// integer part is exact in half over the table range, and the low part
// limits the reconstruction error to about 4.9e-4. No BUFFER block is
// used: every table is computed at runtime.
//
// The pixel pass binds exactly four textures (HOOKED, A2Z_BOUNDARY,
// A2Z_FOCAL, A2Z_KEYS). The README's first design kept the six phi source
// nodes in A2Z_HUES and bound that table too (five binds); this file moves
// the same nodes into A2Z_KEYS texels 8..13 so the pixel pass stays at four
// binds, after the D3D11 pass-limit failures recorded for this project.
// A2Z_KEYS is 16x1 instead of 8x1 for that reason; every value the README
// assigns to texels 0..7 is unchanged, texel 7 carries the reconstructed Izw
// and the phi fallback flag, texels 8..13 carry the six phi source nodes the
// pixel pass needs, and texel 15 carries the 1.09 yellow/cyan j. A2Z_HUES
// stores one 1.09 pair per texel (12 yellow source, 13 yellow target, 14 cyan
// source, 15 cyan target) instead of README row 1's two-pairs-per-texel
// layout; the same values are stored. These layout deviations are documented
// in README section 18; no formula, threshold or mapping decision changes.
//
// Provenance markers:
//   [report]        ITU-R BT.2407-0 Annex 2 (A2.3-A2.5 formulas and the
//                   A2.2 experiment)
//   [paper/xu.glsl] ZCAM equations and constants, checked against xu.glsl
//   [design choice] everything the report/paper leaves open; see the
//                   directory's README.md for the full list and rationale
//
// Domain policy [design choice; README 3.1/8.7]: the contract input is
// linear BT.2020 RGB in [0, 1.09] with alpha in [0,1]. Values outside that
// range (negative channels, over-white above 1.09) are not part of the
// contract; they take the same bypass/clamp guards and are not guaranteed
// to match the float64 reference. The early-out returns BT.709 in-gamut
// colours unchanged; the neutral span and near-white bypasses return
// clamp(rgb709); the mapped result is clamped to [0,1]. That final clamp
// absorbs float/table residue only; it is not the mapping. Non-finite
// intermediate results are replaced by 0 as a last-resort guard.
//
// The yellow/cyan path scan and both radial searches exit through their
// loop conditions rather than `break`: d3dcompiler_47 miscompiled a pixel
// pass `break` in this project (DXBC validation, uninitialized temp).

//!PARAM a2z_reference_white
//!DESC luminance in cd/m2 represented by linear RGB 1
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 1000.0
203.0

//!PARAM a2z_background
//!DESC background luminance as a percentage of the reference white
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 100.0
20.0

//!PARAM a2z_surround
//!DESC ZCAM surround: 0 = dark, 1 = dim, 2 = average
//!TYPE DYNAMIC int
//!MINIMUM 0
//!MAXIMUM 2
2

//!PARAM a2z_hue_mapping
//!DESC 1 = run the A2.5 yellow/cyan paths, 0 = radial mapping only
//!TYPE DYNAMIC int
//!MINIMUM 0
//!MAXIMUM 1
1

//!HOOK OUTPUT
//!SAVE A2Z_HUES
//!COMPONENTS 4
//!WIDTH 16
//!HEIGHT 1
//!COMPUTE 64 1
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, key colours)

// Pass 1: the six pure key colours and the 1.09 yellow/cyan pairs.
//
// Columns 0..5 hold the six pure BT.2020 keys S_k as pack(h) in .xy and
// pack(c) in .zw; columns 6..11 hold the six pure BT.709 keys T_k the same
// way; column 12 holds the 1.09 yellow source pair, column 13 the 1.09
// yellow target pair, column 14 the 1.09 cyan source pair and column 15
// the 1.09 cyan target pair, each as pack(h), pack(c). The ZCAM hues are
// runtime values and are never hard-coded [report A2.5; README 7.1].
// [report A2.5; construction design choice; values computed at runtime]

const float A2Z_N = 2610.0 / 16384.0;
const float A2Z_P = 1.7 * 2523.0 / 32.0;
const float A2Z_C1 = 3424.0 / 4096.0;
const float A2Z_C2 = 2413.0 / 128.0;
const float A2Z_C3 = 2392.0 / 128.0;
const float A2Z_EPSILON = 3.7035226210190005e-11;
const float A2Z_HUE_OFFSET = 89.038;

const vec3 A2Z_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 A2Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A2Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A2Z_BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);

const mat3 A2Z_BT709_TO_XYZ = mat3(
    0.41239079926595923, 0.357584339383878,   0.1804807884018343,
    0.21263900587151022, 0.715168678767756,   0.07219231536073371,
    0.01933081871559182, 0.11919477979462598, 0.9505321522496607
);

struct A2ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a2z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A2ZViewing a2z_make_viewing(float izw) {
    A2ZViewing viewing;
    float fs = a2z_surround_fs(a2z_surround);
    float fb = sqrt(a2z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a2z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A2Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A2Z_N));
    vec3 encoded = pow((vec3(A2Z_C1) + A2Z_C2 * lms) /
                       (vec3(1.0) + A2Z_C3 * lms), vec3(A2Z_P));
    vec3 iab = encoded * A2Z_ZCAM_A;
    return vec3(max(iab.x - A2Z_EPSILON, 0.0), iab.y, iab.z);
}

vec3 a2z_xyz_to_jch(vec3 xyz_abs, A2ZViewing viewing) {
    vec3 iab = a2z_xyz_to_iab(xyz_abs);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float j = pow(iab.x / viewing.izw, viewing.alpha);
    float c = viewing.chroma_factor * pow(radius, 0.74) *
              pow(max(eccentricity, 1e-12), 0.068);
    float h = mod(degrees(atan(iab.z, iab.y)), 360.0);
    return vec3(j, c, h);
}

vec2 a2z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

vec3 a2z_key_rgb(int index) {
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

    if (column >= 16) {
        return;
    }

    vec3 white_iab = a2z_xyz_to_iab(A2Z_D65 * a2z_reference_white);
    A2ZViewing viewing = a2z_make_viewing(max(white_iab.x, 0.0));
    vec4 value = vec4(0.0);

    if (column < 6) {
        vec3 key = a2z_key_rgb(column);
        vec3 source = a2z_xyz_to_jch(
            (key * A2Z_BT2020_TO_XYZ) * a2z_reference_white,
            viewing
        );
        value = vec4(a2z_pack(source.z), a2z_pack(source.y));
    } else if (column < 12) {
        vec3 key = a2z_key_rgb(column - 6);
        vec3 target = a2z_xyz_to_jch(
            (key * A2Z_BT709_TO_XYZ) * a2z_reference_white,
            viewing
        );
        value = vec4(a2z_pack(target.z), a2z_pack(target.y));
    } else if (column < 16) {
        vec3 key = column < 14
            ? vec3(1.09, 1.09, 0.0)
            : vec3(0.0, 1.09, 1.09);
        vec3 xyz;
        vec3 pair;

        if (column == 12 || column == 14) {
            xyz = key * A2Z_BT2020_TO_XYZ;
        } else {
            xyz = key * A2Z_BT709_TO_XYZ;
        }
        pair = a2z_xyz_to_jch(xyz * a2z_reference_white, viewing);
        value = vec4(a2z_pack(pair.z), a2z_pack(pair.y));
    }

    imageStore(out_image, ivec2(column, 0), value);
}

//!HOOK OUTPUT
//!SAVE A2Z_BOUNDARY
//!COMPONENTS 4
//!WIDTH 4097
//!HEIGHT 513
//!COMPUTE 16 16
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, boundary table)

// Pass 2: first-crossing boundary chroma [README 5].
//
// Column 4096 repeats hue 0 so one filtered lookup can cross the seam. Row
// r is j = r/512. .xy is pack(B_t) (BT.709) and .zw is pack(B_s)
// (BT.2020). A c = 0 axis point outside the cube stores 0; a point still
// inside at CMAX stores CMAX; otherwise 32 bisections keep the inside
// lower bound. The 32-bisection star-shaped argument is only claimed where
// c = 0 is inside; the near-white bypass in the pixel pass handles the
// documented exception [README 5.1-5.4].

const float A2Z_N = 2610.0 / 16384.0;
const float A2Z_P = 1.7 * 2523.0 / 32.0;
const float A2Z_C1 = 3424.0 / 4096.0;
const float A2Z_C2 = 2413.0 / 128.0;
const float A2Z_C3 = 2392.0 / 128.0;
const float A2Z_EPSILON = 3.7035226210190005e-11;
const float A2Z_HUE_OFFSET = 89.038;
const float A2Z_EXPONENT = 1.0 / 0.74;
const float A2Z_CMAX = 64.0;
const int A2Z_BISECTIONS = 32;
const int A2Z_HUES = 4097;
const int A2Z_ROWS = 513;

const vec3 A2Z_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 A2Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A2Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A2Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A2Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A2Z_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 A2Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A2ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a2z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A2ZViewing a2z_make_viewing(float izw) {
    A2ZViewing viewing;
    float fs = a2z_surround_fs(a2z_surround);
    float fb = sqrt(a2z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a2z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A2Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A2Z_N));
    vec3 encoded = pow((vec3(A2Z_C1) + A2Z_C2 * lms) /
                       (vec3(1.0) + A2Z_C3 * lms), vec3(A2Z_P));
    vec3 iab = encoded * A2Z_ZCAM_A;
    return vec3(max(iab.x - A2Z_EPSILON, 0.0), iab.y, iab.z);
}

vec3 a2z_jab_to_xyz(vec3 jab, A2ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A2Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A2Z_EPSILON, radius * direction) *
                   A2Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A2Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A2Z_C1), vec3(0.0)) /
        max(vec3(A2Z_C2) - A2Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A2Z_N)
    );
    vec3 xyzp = lms * A2Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a2z_reference_white;
}

bool a2z_inside_ab(float j, vec2 ab, bool source, A2ZViewing viewing) {
    vec3 xyz_rel = a2z_jab_to_xyz(vec3(j, ab), viewing);
    vec3 rgb;
    if (source) {
        rgb = xyz_rel * A2Z_XYZ_TO_BT2020;
    } else {
        rgb = xyz_rel * A2Z_XYZ_TO_BT709;
    }
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float a2z_boundary(float j, float hue_degrees, bool source,
                   A2ZViewing viewing) {
    float jc = clamp(j, 0.0, 1.0);
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));
    bool inside_zero = a2z_inside_ab(jc, vec2(0.0), source, viewing);
    bool inside_cmax = a2z_inside_ab(jc, A2Z_CMAX * direction, source,
                                     viewing);

    if (jc <= 0.0 || !inside_zero) {
        return 0.0;
    }
    if (inside_cmax) {
        return A2Z_CMAX;
    }

    float low = 0.0;
    float high = A2Z_CMAX;
    for (int i = 0; i < A2Z_BISECTIONS; i++) {
        float middle = 0.5 * (low + high);
        if (a2z_inside_ab(jc, middle * direction, source, viewing)) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return low;
}

vec2 a2z_pack(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

void hook() {
    ivec2 index = ivec2(gl_GlobalInvocationID.xy);

    if (index.x >= A2Z_HUES || index.y >= A2Z_ROWS) {
        return;
    }

    float hue = float(index.x) / float(A2Z_HUES - 1) * 360.0;
    float j = float(index.y) / float(A2Z_ROWS - 1);

    vec3 white_iab = a2z_xyz_to_iab(A2Z_D65 * a2z_reference_white);
    A2ZViewing viewing = a2z_make_viewing(max(white_iab.x, 0.0));

    float target = a2z_boundary(j, hue, false, viewing);
    float source = a2z_boundary(j, hue, true, viewing);
    imageStore(
        out_image,
        index,
        vec4(a2z_pack(target), a2z_pack(source))
    );
}

//!HOOK OUTPUT
//!SAVE A2Z_FOCAL_RAW
//!COMPONENTS 4
//!WIDTH 1024
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A2Z_BOUNDARY
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, focal raw)

// Pass 3: table cusp and focal chord per hue [README 6.1-6.2].
//
// Hue i reads boundary column 4*i (4096/1024), scans all 513 rows for the
// largest target and source chroma, and refines the row with the report's
// three-point parabola. .xy = pack(j0) (the c=0 intercept, L_cusp) and
// .zw = pack(c0) (the j=0 intercept, C_focal), both with the README 6.2
// guards. The |c_t - c_s| < 1e-6 branch sets m = 0, j0 to the cusp mean and
// c0 to CMAX. c0 is clipped to [1e-6, CMAX]: the ZCAM c scale is about
// 0.3-37, so the CIELAB floor of 1.0 must not be copied [README 4.5/6.2].

const float A2Z_CMAX = 64.0;
const float A2Z_CHROMA_EPSILON = 1e-6;
const int A2Z_ROWS = 513;
const int A2Z_FOCAL_SIZE = 1024;

vec4 a2z_pack_value(float j0, float c0) {
    float high_j = floor(j0);
    float high_c = floor(c0);
    return vec4(
        high_j, (j0 - high_j) * 1024.0,
        high_c, (c0 - high_c) * 1024.0
    );
}

vec2 a2z_refine_cusp(int index_value, float previous, float value,
                     float next) {
    float denominator = previous - 2.0 * value + next;
    float offset = 0.0;

    if (abs(denominator) > 1e-6) {
        offset = clamp(0.5 * (previous - next) / denominator, -1.0, 1.0);
    }
    return vec2(
        (float(index_value) + offset) / float(A2Z_ROWS - 1),
        value + 0.25 * (next - previous) * offset
    );
}

void hook() {
    int index = int(gl_GlobalInvocationID.x);

    if (index >= A2Z_FOCAL_SIZE) {
        return;
    }

    int column = index * 4;
    float maximum_target = -1.0;
    float maximum_source = -1.0;
    int target_index = 0;
    int source_index = 0;

    for (int row = 0; row < A2Z_ROWS; row++) {
        vec4 stored = A2Z_BOUNDARY_mul *
                      texelFetch(A2Z_BOUNDARY_raw, ivec2(column, row), 0);
        float target = stored.x + stored.y / 1024.0;
        float source = stored.z + stored.w / 1024.0;

        if (target > maximum_target) {
            maximum_target = target;
            target_index = row;
        }
        if (source > maximum_source) {
            maximum_source = source;
            source_index = row;
        }
    }

    int last = A2Z_ROWS - 1;
    int target_previous_index = max(target_index - 1, 0);
    int target_next_index = min(target_index + 1, last);
    vec4 target_previous_stored = A2Z_BOUNDARY_mul * texelFetch(
        A2Z_BOUNDARY_raw, ivec2(column, target_previous_index), 0
    );
    vec4 target_next_stored = A2Z_BOUNDARY_mul * texelFetch(
        A2Z_BOUNDARY_raw, ivec2(column, target_next_index), 0
    );
    vec2 target_cusp = a2z_refine_cusp(
        target_index,
        target_previous_stored.x + target_previous_stored.y / 1024.0,
        maximum_target,
        target_next_stored.x + target_next_stored.y / 1024.0
    );

    int source_previous_index = max(source_index - 1, 0);
    int source_next_index = min(source_index + 1, last);
    vec4 source_previous_stored = A2Z_BOUNDARY_mul * texelFetch(
        A2Z_BOUNDARY_raw, ivec2(column, source_previous_index), 0
    );
    vec4 source_next_stored = A2Z_BOUNDARY_mul * texelFetch(
        A2Z_BOUNDARY_raw, ivec2(column, source_next_index), 0
    );
    vec2 source_cusp = a2z_refine_cusp(
        source_index,
        source_previous_stored.z + source_previous_stored.w / 1024.0,
        maximum_source,
        source_next_stored.z + source_next_stored.w / 1024.0
    );

    float target_j = target_cusp.x;
    float target_c = target_cusp.y;
    float source_j = source_cusp.x;
    float source_c = source_cusp.y;
    float denominator = target_c - source_c;
    float slope;
    float j0;
    float c0;

    if (abs(denominator) < A2Z_CHROMA_EPSILON) {
        slope = 0.0;
        j0 = 0.5 * (target_j + source_j);
        c0 = A2Z_CMAX;
    } else {
        slope = (target_j - source_j) / denominator;
        j0 = clamp(target_j - slope * target_c, -1.0, 2.0);
        c0 = clamp(abs(j0) / max(abs(slope), A2Z_CHROMA_EPSILON),
                   A2Z_CHROMA_EPSILON, A2Z_CMAX);
    }

    imageStore(out_image, ivec2(index, 0), a2z_pack_value(j0, c0));
}

//!HOOK OUTPUT
//!SAVE A2Z_FOCAL
//!COMPONENTS 4
//!WIDTH 1024
//!HEIGHT 2
//!COMPUTE 1024 1
//!BIND A2Z_HUES
//!BIND A2Z_FOCAL_RAW
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, focal profile)

// Pass 4: the 1024-hue focal profile [README 6.3/6.5].
//
// Row 0 is (j_focal, c_focal); row 1 texel 0 is (j_upper, j_lower), row 1
// texel 1 is (ramp_start_index, lower_dip_index), and the rest of row 1 is
// zero. The report's 115-160 and 270-324 degree windows and the 240 degree
// ramp are in report hue coordinates, so every hue is mapped through the
// runtime phi first. The dip minima use all finite |j0| < 10 values: ZCAM's
// j0 may be negative; the CIELAB "value > 0" filter must not be copied.
// The profile follows report Figure A2-4: j_lower from the lower dip round
// the 0/360 seam to report 55, a rise to j_upper by report 90, j_upper to
// report 240, the ramp from phi_inverse(240) to the lower dip, and j_lower
// after it. The 25-tap periodic Gaussian (sigma 4, radius 12) is used only
// for c_focal; j_smoothed is diagnostic, not the profile. c_focal is used
// with a 1e-6 floor in the pixel pass.

const int A2Z_FOCAL_SIZE = 1024;
const int A2Z_SIGMA_RADIUS = 12;
const float A2Z_SIGMA_SQ = 16.0;

const float A2Z_CLAB_0 = 40.584203;
const float A2Z_CLAB_1 = 98.917710;
const float A2Z_CLAB_2 = 145.911196;
const float A2Z_CLAB_3 = 190.306826;
const float A2Z_CLAB_4 = 305.601888;
const float A2Z_CLAB_5 = 334.885358;

float a2z_report_node(int index) {
    if (index >= 6) {
        return A2Z_CLAB_0 + 360.0;
    }
    if (index == 0) {
        return A2Z_CLAB_0;
    }
    if (index == 1) {
        return A2Z_CLAB_1;
    }
    if (index == 2) {
        return A2Z_CLAB_2;
    }
    if (index == 3) {
        return A2Z_CLAB_3;
    }
    if (index == 4) {
        return A2Z_CLAB_4;
    }
    return A2Z_CLAB_5;
}

float a2z_source_node(float nodes[6], int index) {
    if (index >= 6) {
        return nodes[0] + 360.0;
    }
    return nodes[index];
}

bool a2z_nodes_ordered(float nodes[6]) {
    bool ordered = true;

    for (int i = 1; i < 6; i++) {
        if (!(nodes[i] > nodes[i - 1])) {
            ordered = false;
        }
    }
    if (!(nodes[0] + 360.0 > nodes[5])) {
        ordered = false;
    }
    return ordered;
}

float a2z_phi(float hue, float nodes[6], bool fallback) {
    if (fallback) {
        return mod(hue, 360.0);
    }

    float source0 = nodes[0];
    float wrapped = mod(hue, 360.0);
    float u = wrapped < source0 ? wrapped + 360.0 : wrapped;
    int segment = 0;

    for (int i = 1; i < 6; i++) {
        if (u >= a2z_source_node(nodes, i)) {
            segment = i;
        }
    }

    float source_low = a2z_source_node(nodes, segment);
    float source_high = a2z_source_node(nodes, segment + 1);
    float report_low = a2z_report_node(segment);
    float report_high = a2z_report_node(segment + 1);
    float t = (u - source_low) / (source_high - source_low);
    return mod(report_low + t * (report_high - report_low), 360.0);
}

float a2z_phi_inverse(float report_hue, float nodes[6], bool fallback) {
    if (fallback) {
        return mod(report_hue, 360.0);
    }

    float report0 = A2Z_CLAB_0;
    float wrapped = mod(report_hue, 360.0);
    float u = wrapped < report0 ? wrapped + 360.0 : wrapped;
    int segment = 0;

    for (int i = 1; i < 6; i++) {
        if (u >= a2z_report_node(i)) {
            segment = i;
        }
    }

    float report_low = a2z_report_node(segment);
    float report_high = a2z_report_node(segment + 1);
    float source_low = a2z_source_node(nodes, segment);
    float source_high = a2z_source_node(nodes, segment + 1);
    float t = (u - report_low) / (report_high - report_low);
    return mod(source_low + t * (source_high - source_low), 360.0);
}

float a2z_fetch_j0(int index) {
    vec4 stored = A2Z_FOCAL_RAW_mul *
                  texelFetch(A2Z_FOCAL_RAW_raw, ivec2(index, 0), 0);
    return stored.x + stored.y / 1024.0;
}

float a2z_fetch_c0(int index) {
    vec4 stored = A2Z_FOCAL_RAW_mul *
                  texelFetch(A2Z_FOCAL_RAW_raw, ivec2(index, 0), 0);
    return stored.z + stored.w / 1024.0;
}

vec2 a2z_pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

// The two window minima are one 1024-entry reduction each. All 1024 hues
// live in one workgroup (COMPUTE 1024 1), so a shared-memory tree replaces
// the serial 1024-hue scan: the serial form made d3dcompiler_47 spin on
// the FOCAL pass (the pass never reached DXBC), while this form compiles in
// a few ms on both backends. Strict less-than keeps the lowest index on
// ties, matching the reference's first-argmin.
shared float a2z_shared_value[1024];
shared int a2z_shared_index[1024];

void hook() {
    ivec2 index = ivec2(gl_GlobalInvocationID.xy);

    // The dispatch is exactly 1024 x 2, so every invocation is valid and
    // every invocation reaches the barriers below; no early return here:
    // d3dcompiler treats barriers after a conditional return as varying
    // flow control (X3663) and refuses the shader.
    float nodes[6];
    for (int k = 0; k < 6; k++) {
        vec4 stored = A2Z_HUES_mul *
                      texelFetch(A2Z_HUES_raw, ivec2(k, 0), 0);
        nodes[k] = stored.x + stored.y / 1024.0;
    }
    bool fallback = !a2z_nodes_ordered(nodes);

    int hue_index = int(index.x);
    float j0 = a2z_fetch_j0(hue_index);
    float report = a2z_phi(
        float(hue_index) * 360.0 / float(A2Z_FOCAL_SIZE), nodes, fallback
    );
    bool finite = abs(j0) < 10.0;

    a2z_shared_value[hue_index] =
        (report >= 115.0 && report <= 160.0 && finite) ? j0 : 1e30;
    a2z_shared_index[hue_index] = hue_index;
    barrier();

    for (int stride = 512; stride > 0; stride /= 2) {
        if (hue_index < stride &&
            a2z_shared_value[hue_index + stride] <
                a2z_shared_value[hue_index]) {
            a2z_shared_value[hue_index] =
                a2z_shared_value[hue_index + stride];
            a2z_shared_index[hue_index] =
                a2z_shared_index[hue_index + stride];
        }
        barrier();
    }

    bool upper_valid = a2z_shared_value[0] < 1e29;
    float j_upper = upper_valid ? a2z_shared_value[0] : 0.0;
    barrier();

    a2z_shared_value[hue_index] =
        (report >= 270.0 && report <= 324.0 && finite) ? j0 : 1e30;
    a2z_shared_index[hue_index] = hue_index;
    barrier();

    for (int stride = 512; stride > 0; stride /= 2) {
        if (hue_index < stride &&
            a2z_shared_value[hue_index + stride] <
                a2z_shared_value[hue_index]) {
            a2z_shared_value[hue_index] =
                a2z_shared_value[hue_index + stride];
            a2z_shared_index[hue_index] =
                a2z_shared_index[hue_index + stride];
        }
        barrier();
    }

    bool lower_valid = a2z_shared_value[0] < 1e29;
    float j_lower = lower_valid ? a2z_shared_value[0] : 0.0;
    int lower_index = a2z_shared_index[0];

    if (index.y == 1) {
        vec4 value = vec4(0.0);

        if (index.x == 0) {
            value = vec4(
                a2z_pack_scalar(j_upper), a2z_pack_scalar(j_lower)
            );
        } else if (index.x == 1) {
            float ramp_start = a2z_phi_inverse(240.0, nodes, fallback) /
                               360.0 * float(A2Z_FOCAL_SIZE);
            int start = int(ramp_start);
            start = min(max(start, 0), A2Z_FOCAL_SIZE - 1);
            value = vec4(
                a2z_pack_scalar(float(start)),
                a2z_pack_scalar(float(lower_index))
            );
        }
        imageStore(out_image, index, value);
        return;
    }

    float ramp_start = a2z_phi_inverse(240.0, nodes, fallback) /
                       360.0 * float(A2Z_FOCAL_SIZE);
    int start = int(ramp_start);
    start = min(max(start, 0), A2Z_FOCAL_SIZE - 1);
    int ramp_length = max(lower_index - start, 1);

    // Report Figure A2-4 is the simple profile: lower flat from the lower
    // dip round the seam to report 55, a rise to the upper limit by report
    // 90, upper flat to the report-240 fall, the fall to the lower dip, and
    // lower flat again.  Clamping the smoothed j0 only on the non-wrapping
    // side made texel 0 (~0.32) disagree with texel 1023 (j_lower) and
    // produced a visible step at the ZCAM 0/360 seam.
    int rise_start = int(a2z_phi_inverse(55.0, nodes, fallback) /
                         360.0 * float(A2Z_FOCAL_SIZE));
    int rise_end = int(a2z_phi_inverse(90.0, nodes, fallback) /
                       360.0 * float(A2Z_FOCAL_SIZE));
    rise_start = min(max(rise_start, 0), A2Z_FOCAL_SIZE - 1);
    rise_end = min(max(rise_end, 0), A2Z_FOCAL_SIZE - 1);
    bool geometry_ok = 0 <= rise_start && rise_start < rise_end &&
                       rise_end < start && start <= lower_index &&
                       lower_index < A2Z_FOCAL_SIZE;

    float weight_sum = 0.0;
    float c_sum = 0.0;

    for (int k = -A2Z_SIGMA_RADIUS; k <= A2Z_SIGMA_RADIUS; k++) {
        int sample_index = (int(index.x) + k) & (A2Z_FOCAL_SIZE - 1);
        float weight = exp(-0.5 * float(k * k) / A2Z_SIGMA_SQ);
        weight_sum += weight;
        c_sum += weight * a2z_fetch_c0(sample_index);
    }

    float c_smoothed = c_sum / weight_sum;
    float j_focal = j_lower;

    if (int(index.x) >= start && int(index.x) <= lower_index) {
        j_focal = j_upper + (j_lower - j_upper) *
                  float(int(index.x) - start) / float(ramp_length);
    } else if (geometry_ok) {
        if (int(index.x) >= rise_end && int(index.x) < start) {
            j_focal = j_upper;
        } else if (int(index.x) >= rise_start &&
                   int(index.x) < rise_end) {
            j_focal = j_lower + (j_upper - j_lower) *
                      float(int(index.x) - rise_start) /
                      float(max(rise_end - rise_start, 1));
        }
    }

    imageStore(
        out_image,
        index,
        vec4(a2z_pack_scalar(j_focal), a2z_pack_scalar(c_smoothed))
    );
}

//!HOOK OUTPUT
//!SAVE A2Z_KEYS
//!COMPONENTS 4
//!WIDTH 16
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A2Z_HUES
//!BIND A2Z_FOCAL
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, runtime keys)

// Pass 5: runtime A2.5 coefficients and thresholds [README 7.3/7.4/8.3].
//
// Texels 0..7 keep the README 9 layout: 0 = delta_y_r/delta_c_r,
// 1 = yellow_start/yellow_end, 2 = cyan_end/j_peak, 3 = j_bypass_t/_s,
// 4 = literal-sine coefficients, 5 = theta_y/theta_c, 6 = j_upper/j_lower,
// 7 = pack(izw) in .xy plus the phi fallback flag in .z. Texels 8..13 hold
// pack(zs_k) in .xy and pack(zt_k) in .zw for the six pure key colours;
// that is the 4-bind deviation explained at the top of the file. Texel 14
// is reserved; texel 15 holds pack(j_sy) in .xy and pack(j_sc) in .zw.
//
// The line angle uses the undirected a*b* line, taking the branch within
// +/-90 degrees of the source hue: the opposite branch gives rotations of
// -217.7/+236.5 degrees and is rejected [README 7.3]. The report-coordinate
// coefficients use the fixed report nodes, so the key deflection is the
// same as the CIELAB formulas at the six pure CIELAB key hues. j_bypass_t
// and j_bypass_s are the c=0 axis exit j minus 5e-4, found with 40
// bisections on the exact inverse ZCAM axis.

const float A2Z_N = 2610.0 / 16384.0;
const float A2Z_P = 1.7 * 2523.0 / 32.0;
const float A2Z_C1 = 3424.0 / 4096.0;
const float A2Z_C2 = 2413.0 / 128.0;
const float A2Z_C3 = 2392.0 / 128.0;
const float A2Z_EPSILON = 3.7035226210190005e-11;
const float A2Z_HUE_OFFSET = 89.038;
const float A2Z_EXPONENT = 1.0 / 0.74;
const float A2Z_BYPASS_MARGIN = 5e-4;
const float A2Z_PEAK_Y = 1.189273;
const int A2Z_AXIS_ITERATIONS = 40;

const float A2Z_CLAB_0 = 40.584203;
const float A2Z_CLAB_1 = 98.917710;
const float A2Z_CLAB_2 = 145.911196;
const float A2Z_CLAB_3 = 190.306826;
const float A2Z_CLAB_4 = 305.601888;
const float A2Z_CLAB_5 = 334.885358;

const vec3 A2Z_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 A2Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A2Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A2Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A2Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A2Z_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 A2Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

const mat3 A2Z_BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);

struct A2ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a2z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A2ZViewing a2z_make_viewing(float izw) {
    A2ZViewing viewing;
    float fs = a2z_surround_fs(a2z_surround);
    float fb = sqrt(a2z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a2z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A2Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A2Z_N));
    vec3 encoded = pow((vec3(A2Z_C1) + A2Z_C2 * lms) /
                       (vec3(1.0) + A2Z_C3 * lms), vec3(A2Z_P));
    vec3 iab = encoded * A2Z_ZCAM_A;
    return vec3(max(iab.x - A2Z_EPSILON, 0.0), iab.y, iab.z);
}

vec3 a2z_xyz_to_jch(vec3 xyz_abs, A2ZViewing viewing) {
    vec3 iab = a2z_xyz_to_iab(xyz_abs);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float j = pow(iab.x / viewing.izw, viewing.alpha);
    float c = viewing.chroma_factor * pow(radius, 0.74) *
              pow(max(eccentricity, 1e-12), 0.068);
    float h = mod(degrees(atan(iab.z, iab.y)), 360.0);
    return vec3(j, c, h);
}

vec3 a2z_jab_to_xyz(vec3 jab, A2ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A2Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A2Z_EPSILON, radius * direction) *
                   A2Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A2Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A2Z_C1), vec3(0.0)) /
        max(vec3(A2Z_C2) - A2Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A2Z_N)
    );
    vec3 xyzp = lms * A2Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a2z_reference_white;
}

bool a2z_inside_ab(float j, vec2 ab, bool source, A2ZViewing viewing) {
    vec3 xyz_rel = a2z_jab_to_xyz(vec3(j, ab), viewing);
    vec3 rgb;
    if (source) {
        rgb = xyz_rel * A2Z_XYZ_TO_BT2020;
    } else {
        rgb = xyz_rel * A2Z_XYZ_TO_BT709;
    }
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

bool a2z_inside_jch(float j, float c, float hue_degrees, bool source,
                    A2ZViewing viewing) {
    float angle = radians(hue_degrees);
    return a2z_inside_ab(
        j, vec2(c * cos(angle), c * sin(angle)), source, viewing
    );
}

vec2 a2z_pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float a2z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float a2z_line_angle(float source_hue, float source_chroma,
                     float target_hue, float target_chroma) {
    vec2 source = source_chroma *
                  vec2(cos(radians(source_hue)), sin(radians(source_hue)));
    vec2 target = target_chroma *
                  vec2(cos(radians(target_hue)), sin(radians(target_hue)));
    vec2 delta = source - target;

    if (length(delta) < 1e-12) {
        return source_hue;
    }

    float theta = mod(degrees(atan(delta.y, delta.x)), 360.0);
    float difference = mod(theta - source_hue + 180.0, 360.0) - 180.0;

    if (difference > 90.0) {
        theta -= 180.0;
    } else if (difference < -90.0) {
        theta += 180.0;
    }
    return mod(theta, 360.0);
}

float a2z_wrap180(float value) {
    return mod(value + 180.0, 360.0) - 180.0;
}

float a2z_safe_divide(float numerator, float denominator) {
    if (abs(denominator) < 1e-12) {
        return 0.0;
    }
    return numerator / denominator;
}

float a2z_peak_j(A2ZViewing viewing) {
    vec3 peak = a2z_xyz_to_jch(
        A2Z_D65 * A2Z_PEAK_Y * a2z_reference_white,
        viewing
    );
    return peak.x;
}

float a2z_axis_exit_j(bool source, A2ZViewing viewing) {
    float low = 0.0;
    float high = 1.0;

    for (int i = 0; i < A2Z_AXIS_ITERATIONS; i++) {
        float middle = 0.5 * (low + high);
        if (a2z_inside_jch(middle, 0.0, 0.0, source, viewing)) {
            low = middle;
        } else {
            high = middle;
        }
    }
    return low;
}

float a2z_report_node(int index) {
    if (index >= 6) {
        return A2Z_CLAB_0 + 360.0;
    }
    if (index == 0) {
        return A2Z_CLAB_0;
    }
    if (index == 1) {
        return A2Z_CLAB_1;
    }
    if (index == 2) {
        return A2Z_CLAB_2;
    }
    if (index == 3) {
        return A2Z_CLAB_3;
    }
    if (index == 4) {
        return A2Z_CLAB_4;
    }
    return A2Z_CLAB_5;
}

float a2z_source_node(float nodes[6], int index) {
    if (index >= 6) {
        return nodes[0] + 360.0;
    }
    return nodes[index];
}

float a2z_phi_inverse(float report_hue, float nodes[6], bool fallback) {
    if (fallback) {
        return mod(report_hue, 360.0);
    }

    float report0 = a2z_report_node(0);
    float wrapped = mod(report_hue, 360.0);
    float u = wrapped < report0 ? wrapped + 360.0 : wrapped;
    int segment = 0;

    for (int i = 1; i < 6; i++) {
        if (u >= a2z_report_node(i)) {
            segment = i;
        }
    }

    float report_low = a2z_report_node(segment);
    float report_high = a2z_report_node(segment + 1);
    float source_low = a2z_source_node(nodes, segment);
    float source_high = a2z_source_node(nodes, segment + 1);
    float t = (u - report_low) / (report_high - report_low);
    return mod(source_low + t * (source_high - source_low), 360.0);
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column >= 16) {
        return;
    }

    float source_hue[6];
    float target_hue[6];

    for (int k = 0; k < 6; k++) {
        vec4 source_stored = A2Z_HUES_mul *
                             texelFetch(A2Z_HUES_raw, ivec2(k, 0), 0);
        vec4 target_stored = A2Z_HUES_mul *
                             texelFetch(A2Z_HUES_raw, ivec2(6 + k, 0), 0);
        source_hue[k] = a2z_unpack(source_stored.xy);
        target_hue[k] = a2z_unpack(target_stored.xy);
    }

    vec4 yellow_source = A2Z_HUES_mul *
                         texelFetch(A2Z_HUES_raw, ivec2(12, 0), 0);
    vec4 yellow_target = A2Z_HUES_mul *
                         texelFetch(A2Z_HUES_raw, ivec2(13, 0), 0);
    vec4 cyan_source = A2Z_HUES_mul *
                       texelFetch(A2Z_HUES_raw, ivec2(14, 0), 0);
    vec4 cyan_target = A2Z_HUES_mul *
                       texelFetch(A2Z_HUES_raw, ivec2(15, 0), 0);

    float yellow_hue = a2z_unpack(yellow_source.xy);
    float yellow_chroma = a2z_unpack(yellow_source.zw);
    float yellow_target_hue = a2z_unpack(yellow_target.xy);
    float yellow_target_chroma = a2z_unpack(yellow_target.zw);
    float cyan_hue = a2z_unpack(cyan_source.xy);
    float cyan_chroma = a2z_unpack(cyan_source.zw);
    float cyan_target_hue = a2z_unpack(cyan_target.xy);
    float cyan_target_chroma = a2z_unpack(cyan_target.zw);

    vec4 focal_metadata = A2Z_FOCAL_mul *
                          texelFetch(A2Z_FOCAL_raw, ivec2(0, 1), 0);
    float j_upper = a2z_unpack(focal_metadata.xy);
    float j_lower = a2z_unpack(focal_metadata.zw);

    vec3 white_iab = a2z_xyz_to_iab(A2Z_D65 * a2z_reference_white);
    float izw = max(white_iab.x, 0.0);
    A2ZViewing viewing = a2z_make_viewing(izw);

    vec3 yellow_forward = a2z_xyz_to_jch(
        (vec3(1.09, 1.09, 0.0) * A2Z_BT2020_TO_XYZ) * a2z_reference_white,
        viewing
    );
    vec3 cyan_forward = a2z_xyz_to_jch(
        (vec3(0.0, 1.09, 1.09) * A2Z_BT2020_TO_XYZ) * a2z_reference_white,
        viewing
    );
    float yellow_j = yellow_forward.x;
    float cyan_j = cyan_forward.x;

    float theta_y = a2z_line_angle(
        yellow_hue, yellow_chroma, yellow_target_hue, yellow_target_chroma
    );
    float theta_c = a2z_line_angle(
        cyan_hue, cyan_chroma, cyan_target_hue, cyan_target_chroma
    );
    float rotation_y = a2z_wrap180(yellow_hue - theta_y);
    float rotation_c = a2z_wrap180(cyan_hue - theta_c);
    float k_sy = clamp((yellow_j - j_upper) / (1.0 - j_upper), 0.0, 1.0);

    float delta_y = a2z_safe_divide(
        rotation_y, k_sy * sin(radians(A2Z_CLAB_1))
    );
    float delta_c = a2z_safe_divide(
        rotation_c, -sin(radians(3.0 * A2Z_CLAB_3))
    );
    float delta_y_literal = a2z_safe_divide(
        rotation_y, k_sy * sin(radians(yellow_hue))
    );
    float delta_c_literal = a2z_safe_divide(
        rotation_c, -sin(radians(3.0 * cyan_hue))
    );

    float j_peak = a2z_peak_j(viewing);
    float j_bypass_t = a2z_axis_exit_j(false, viewing) - A2Z_BYPASS_MARGIN;
    float j_bypass_s = a2z_axis_exit_j(true, viewing) - A2Z_BYPASS_MARGIN;

    bool ordered = true;
    for (int i = 1; i < 6; i++) {
        if (!(source_hue[i] > source_hue[i - 1])) {
            ordered = false;
        }
        if (!(target_hue[i] > target_hue[i - 1])) {
            ordered = false;
        }
    }
    if (!(source_hue[0] + 360.0 > source_hue[5])) {
        ordered = false;
    }
    if (!(target_hue[0] + 360.0 > target_hue[5])) {
        ordered = false;
    }

    float yellow_start = a2z_phi_inverse(0.0, source_hue, !ordered);
    float yellow_end = a2z_phi_inverse(180.0, source_hue, !ordered);
    float cyan_end = a2z_phi_inverse(240.0, source_hue, !ordered);

    vec4 value = vec4(0.0);

    if (column == 0) {
        value = vec4(
            a2z_pack_scalar(delta_y), a2z_pack_scalar(delta_c)
        );
    } else if (column == 1) {
        value = vec4(
            a2z_pack_scalar(yellow_start), a2z_pack_scalar(yellow_end)
        );
    } else if (column == 2) {
        value = vec4(
            a2z_pack_scalar(cyan_end), a2z_pack_scalar(j_peak)
        );
    } else if (column == 3) {
        value = vec4(
            a2z_pack_scalar(j_bypass_t), a2z_pack_scalar(j_bypass_s)
        );
    } else if (column == 4) {
        value = vec4(
            a2z_pack_scalar(delta_y_literal),
            a2z_pack_scalar(delta_c_literal)
        );
    } else if (column == 5) {
        value = vec4(
            a2z_pack_scalar(theta_y), a2z_pack_scalar(theta_c)
        );
    } else if (column == 6) {
        value = vec4(
            a2z_pack_scalar(j_upper), a2z_pack_scalar(j_lower)
        );
    } else if (column == 7) {
        value = vec4(
            a2z_pack_scalar(izw),
            ordered ? 0.0 : 1.0,
            0.0
        );
    } else if (column >= 8 && column <= 13) {
        int k = column - 8;
        vec4 source_stored = A2Z_HUES_mul *
                             texelFetch(A2Z_HUES_raw, ivec2(k, 0), 0);
        vec4 target_stored = A2Z_HUES_mul *
                             texelFetch(A2Z_HUES_raw, ivec2(6 + k, 0), 0);
        value = vec4(
            a2z_pack_scalar(a2z_unpack(source_stored.xy)),
            a2z_pack_scalar(a2z_unpack(target_stored.xy))
        );
    } else if (column == 15) {
        value = vec4(
            a2z_pack_scalar(yellow_j), a2z_pack_scalar(cyan_j)
        );
    }

    imageStore(out_image, ivec2(column, 0), value);
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND A2Z_BOUNDARY
//!BIND A2Z_FOCAL
//!BIND A2Z_KEYS
//!DESC gamut mapping (BT.2407 annex 2 ZCAM, pixel map)

// Pass 6: the README 8 pixel pipeline.
//
// 1 early-out BT.709 in [0,1]^3; 2 neutral span bypass; 3 ZCAM forward;
// 4 runtime phi; 5 near-white bypass; 6 focal sample; 7 A2.5 path search
// (32 + 16 + 12 exact tests) or radial search (12 table lookups); 8 inverse
// ZCAM and clamp. The boundary lookup uses the input hue and the clamped
// j row; the lightness mapping always uses the true j. Alpha is returned
// unchanged. The path and radial searches exit through their loop
// conditions, not break (D3D11 compiler history at the top of the file).

const float A2Z_N = 2610.0 / 16384.0;
const float A2Z_P = 1.7 * 2523.0 / 32.0;
const float A2Z_C1 = 3424.0 / 4096.0;
const float A2Z_C2 = 2413.0 / 128.0;
const float A2Z_C3 = 2392.0 / 128.0;
const float A2Z_EPSILON = 3.7035226210190005e-11;
const float A2Z_HUE_OFFSET = 89.038;
const float A2Z_EXPONENT = 1.0 / 0.74;
const float A2Z_CHROMA_EPSILON = 1e-6;
const float A2Z_NEUTRAL_SPAN = 1e-5;
const float A2Z_NEAR_WHITE_CHROMA = 0.01;
const int A2Z_HUES = 4097;
const int A2Z_ROWS = 513;
const int A2Z_FOCAL_SIZE = 1024;
const int A2Z_RAY_ITERATIONS = 12;
const int A2Z_PATH_STEPS = 32;
const int A2Z_PATH_FINE = 16;
const int A2Z_PATH_ITERATIONS = 12;

const float A2Z_CLAB_0 = 40.584203;
const float A2Z_CLAB_1 = 98.917710;
const float A2Z_CLAB_2 = 145.911196;
const float A2Z_CLAB_3 = 190.306826;
const float A2Z_CLAB_4 = 305.601888;
const float A2Z_CLAB_5 = 334.885358;

const mat3 A2Z_XYZP_TO_LMS = mat3(
     0.41478972, 0.579999, 0.0146480,
    -0.2015100,  1.120649, 0.0531008,
    -0.0166008,  0.264800, 0.6684799
);

const mat3 A2Z_ZCAM_A = mat3(
    0.0,      1.0,       0.0,
    3.524,   -4.066708,  0.542708,
    0.199076, 1.096799, -1.295875
);

const mat3 A2Z_LMS_TO_XYZP = mat3(
     1.924226435787607, -1.004792312595366,  0.037651404030618,
     0.350316762094999,  0.726481193931655, -0.065384422948085,
    -0.090982810982848, -0.312728290523074,  1.522766561305260
);

const mat3 A2Z_IAB_TO_LMSP = mat3(
    1.0, 0.2772100865430786,  0.1160946323122377,
    1.0, 0.0,                 0.0,
    1.0, 0.0425858012452203, -0.7538445798999200
);

const mat3 A2Z_BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0,                0.0280726930490874, 1.0609850577107910
);

const mat3 A2Z_BT2020_TO_BT709 = mat3(
     1.660491002108434, -0.5876411387885491,  -0.072849863319885,
    -0.1245504745215906, 1.1328998971259596,  -0.0083494226043695,
    -0.0181507633549052, -0.1005788980080074,  1.118729661362913
);

const mat3 A2Z_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

struct A2ZViewing {
    float izw;
    float alpha;
    float chroma_factor;
};

float a2z_surround_fs(int surround) {
    if (surround == 0) {
        return 0.525;
    }
    if (surround == 1) {
        return 0.59;
    }
    return 0.69;
}

A2ZViewing a2z_make_viewing(float izw) {
    A2ZViewing viewing;
    float fs = a2z_surround_fs(a2z_surround);
    float fb = sqrt(a2z_background / 100.0);
    viewing.izw = max(izw, 1e-12);
    viewing.alpha = 1.6 * fs / pow(fb, 0.12);
    viewing.chroma_factor = 1.0 / (27.0 * pow(fs, 2.2) * pow(fb, 0.6) *
                                   pow(viewing.izw, 0.78 + viewing.alpha));
    return viewing;
}

vec3 a2z_xyz_to_iab(vec3 xyz) {
    vec3 adapted = vec3(
        1.15 * xyz.x - 0.15 * xyz.z,
        0.66 * xyz.y + 0.34 * xyz.x,
        xyz.z
    );
    vec3 lms = pow(max(adapted * A2Z_XYZP_TO_LMS / 10000.0, vec3(0.0)),
                   vec3(A2Z_N));
    vec3 encoded = pow((vec3(A2Z_C1) + A2Z_C2 * lms) /
                       (vec3(1.0) + A2Z_C3 * lms), vec3(A2Z_P));
    vec3 iab = encoded * A2Z_ZCAM_A;
    return vec3(max(iab.x - A2Z_EPSILON, 0.0), iab.y, iab.z);
}

vec3 a2z_xyz_to_jch(vec3 xyz_abs, A2ZViewing viewing) {
    vec3 iab = a2z_xyz_to_iab(xyz_abs);
    float radius = length(iab.yz);
    vec2 direction = radius > 0.0 ? iab.yz / radius : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float j = pow(iab.x / viewing.izw, viewing.alpha);
    float c = viewing.chroma_factor * pow(radius, 0.74) *
              pow(max(eccentricity, 1e-12), 0.068);
    float h = mod(degrees(atan(iab.z, iab.y)), 360.0);
    return vec3(j, c, h);
}

vec3 a2z_jab_to_xyz(vec3 jab, A2ZViewing viewing) {
    float c = length(jab.yz);
    vec2 direction = c > 0.0 ? jab.yz / c : vec2(1.0, 0.0);
    float eccentricity = 1.015 + dot(
        direction,
        vec2(cos(radians(A2Z_HUE_OFFSET)), -sin(radians(A2Z_HUE_OFFSET)))
    );
    float radius = pow(
        max(c, 0.0) / (viewing.chroma_factor *
                       pow(max(eccentricity, 1e-12), 0.068)),
        A2Z_EXPONENT
    );
    float iz = viewing.izw * pow(max(jab.x, 0.0), 1.0 / viewing.alpha);
    vec3 encoded = vec3(iz + A2Z_EPSILON, radius * direction) *
                   A2Z_IAB_TO_LMSP;
    vec3 root = pow(max(encoded, vec3(0.0)), vec3(1.0 / A2Z_P));
    vec3 lms = 10000.0 * pow(
        max(root - vec3(A2Z_C1), vec3(0.0)) /
        max(vec3(A2Z_C2) - A2Z_C3 * root, vec3(1e-6)),
        vec3(1.0 / A2Z_N)
    );
    vec3 xyzp = lms * A2Z_LMS_TO_XYZP;
    float x = (xyzp.x + 0.15 * xyzp.z) / 1.15;
    return vec3(x, (xyzp.y - 0.34 * x) / 0.66, xyzp.z) /
           a2z_reference_white;
}

bool a2z_inside_ab(float j, vec2 ab, A2ZViewing viewing) {
    vec3 xyz_rel = a2z_jab_to_xyz(vec3(j, ab), viewing);
    vec3 rgb = xyz_rel * A2Z_XYZ_TO_BT709;
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

bool a2z_inside_jch(float j, float c, float hue_degrees,
                    A2ZViewing viewing) {
    float angle = radians(hue_degrees);
    return a2z_inside_ab(
        j, vec2(c * cos(angle), c * sin(angle)), viewing
    );
}

float a2z_unpack(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float a2z_report_node(int index) {
    if (index >= 6) {
        return A2Z_CLAB_0 + 360.0;
    }
    if (index == 0) {
        return A2Z_CLAB_0;
    }
    if (index == 1) {
        return A2Z_CLAB_1;
    }
    if (index == 2) {
        return A2Z_CLAB_2;
    }
    if (index == 3) {
        return A2Z_CLAB_3;
    }
    if (index == 4) {
        return A2Z_CLAB_4;
    }
    return A2Z_CLAB_5;
}

float a2z_source_node(float nodes[6], int index) {
    if (index >= 6) {
        return nodes[0] + 360.0;
    }
    return nodes[index];
}

float a2z_phi(float hue, float nodes[6], bool fallback) {
    if (fallback) {
        return mod(hue, 360.0);
    }

    float source0 = nodes[0];
    float wrapped = mod(hue, 360.0);
    float u = wrapped < source0 ? wrapped + 360.0 : wrapped;
    int segment = 0;

    for (int i = 1; i < 6; i++) {
        if (u >= a2z_source_node(nodes, i)) {
            segment = i;
        }
    }

    float source_low = a2z_source_node(nodes, segment);
    float source_high = a2z_source_node(nodes, segment + 1);
    float report_low = a2z_report_node(segment);
    float report_high = a2z_report_node(segment + 1);
    float t = (u - source_low) / (source_high - source_low);
    return mod(report_low + t * (report_high - report_low), 360.0);
}

vec2 a2z_fetch_focal(int index) {
    vec4 stored = A2Z_FOCAL_mul *
                  texelFetch(A2Z_FOCAL_raw, ivec2(index, 0), 0);
    return vec2(a2z_unpack(stored.xy), a2z_unpack(stored.zw));
}

vec2 a2z_sample_focal(float hue) {
    float position = fract(hue / 360.0) * float(A2Z_FOCAL_SIZE);
    int lower = int(floor(position)) & (A2Z_FOCAL_SIZE - 1);
    int upper = (lower + 1) & (A2Z_FOCAL_SIZE - 1);
    float weight = fract(position);
    return mix(a2z_fetch_focal(lower), a2z_fetch_focal(upper), weight);
}

float a2z_sample_boundary(float hue, float j) {
    float hue_position = fract(hue / 360.0) * float(A2Z_HUES - 1);
    float hue_coordinate = (hue_position + 0.5) / float(A2Z_HUES);
    float row_position = clamp(j, 0.0, 1.0) * float(A2Z_ROWS - 1);
    float row_coordinate = (row_position + 0.5) / float(A2Z_ROWS);
    vec4 stored = A2Z_BOUNDARY_mul * textureLod(
        A2Z_BOUNDARY_raw,
        vec2(hue_coordinate, row_coordinate),
        0.0
    );
    return a2z_unpack(stored.xy);
}

float a2z_map_lightness(float j, float c, float mapped_c, float j_focal,
                        float c_focal, bool above) {
    if (above) {
        return j_focal + (j - j_focal) * mapped_c / max(c, 1e-12);
    }
    return j * (c_focal - mapped_c) /
           max(c_focal - c, A2Z_CHROMA_EPSILON);
}

float a2z_path_k(float j, float j_upper, float j_peak) {
    float k = 0.0;

    if (j > j_upper && j <= 1.0) {
        k = (j - j_upper) / (1.0 - j_upper);
    } else if (j > 1.0 && j <= j_peak) {
        k = (j_peak - j) / (j_peak - 1.0);
    } else {
        k = 0.0;
    }
    return clamp(k, 0.0, 1.0);
}

bool a2z_path_test(float j, float c, vec2 ab, float theta, float distance,
                   float j_focal, float c_focal, bool above,
                   A2ZViewing viewing, out vec2 mapped_ab,
                   out float mapped_j) {
    float angle = radians(theta);
    vec2 direction = vec2(cos(angle), sin(angle));
    mapped_ab = ab - distance * direction;
    float mapped_c = length(mapped_ab);
    float mapped_hue = mod(degrees(atan(mapped_ab.y, mapped_ab.x)), 360.0);
    mapped_j = a2z_map_lightness(
        j, c, mapped_c, j_focal, c_focal, above
    );
    return a2z_inside_jch(mapped_j, mapped_c, mapped_hue, viewing);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb2020 = color.rgb;
    vec3 rgb709 = rgb2020 * A2Z_BT2020_TO_BT709;

    if (all(greaterThanEqual(rgb709, vec3(0.0))) &&
        all(lessThanEqual(rgb709, vec3(1.0)))) {
        color.rgb = rgb709;
        return color;
    }

    float high = max(max(rgb2020.r, rgb2020.g), rgb2020.b);
    float low = min(min(rgb2020.r, rgb2020.g), rgb2020.b);

    if (high - low <= A2Z_NEUTRAL_SPAN) {
        color.rgb = clamp(rgb709, vec3(0.0), vec3(1.0));
        return color;
    }

    vec4 key_data = A2Z_KEYS_mul *
                    texelFetch(A2Z_KEYS_raw, ivec2(7, 0), 0);
    float izw = a2z_unpack(key_data.xy);
    bool fallback = key_data.z > 0.5;
    A2ZViewing viewing = a2z_make_viewing(izw);

    float nodes[6];
    for (int k = 0; k < 6; k++) {
        vec4 stored = A2Z_KEYS_mul *
                      texelFetch(A2Z_KEYS_raw, ivec2(8 + k, 0), 0);
        nodes[k] = a2z_unpack(stored.xy);
    }

    vec4 delta_data = A2Z_KEYS_mul *
                      texelFetch(A2Z_KEYS_raw, ivec2(0, 0), 0);
    float delta_y = a2z_unpack(delta_data.xy);
    float delta_c = a2z_unpack(delta_data.zw);

    vec4 threshold_data = A2Z_KEYS_mul *
                          texelFetch(A2Z_KEYS_raw, ivec2(2, 0), 0);
    float j_peak = a2z_unpack(threshold_data.zw);
    vec4 bypass_data = A2Z_KEYS_mul *
                       texelFetch(A2Z_KEYS_raw, ivec2(3, 0), 0);
    float j_bypass_t = a2z_unpack(bypass_data.xy);
    vec4 upper_data = A2Z_KEYS_mul *
                      texelFetch(A2Z_KEYS_raw, ivec2(6, 0), 0);
    float j_upper = a2z_unpack(upper_data.xy);

    vec3 xyz_rel = rgb2020 * A2Z_BT2020_TO_XYZ;
    vec3 jch = a2z_xyz_to_jch(
        xyz_rel * a2z_reference_white, viewing
    );
    float j = jch.x;
    float c = jch.y;
    float h = jch.z;

    if (j > j_bypass_t && c < A2Z_NEAR_WHITE_CHROMA) {
        color.rgb = clamp(rgb709, vec3(0.0), vec3(1.0));
        return color;
    }

    float phi = a2z_phi(h, nodes, fallback);
    float angle = radians(h);
    vec2 ab = c * vec2(cos(angle), sin(angle));
    vec2 hue_direction = c > 0.0 ? ab / c : vec2(1.0, 0.0);

    vec2 focal = a2z_sample_focal(h);
    float j_focal_raw = focal.x;
    // The report assumes a positive L_focal and c_focal > c_out.  ZCAM at
    // extreme viewing conditions can make j_focal negative and can produce
    // c > c_focal; the below-line formula (2-2) then has no positive
    // c_focal/(c_focal-c_out) intersection.  Clamp the focal to the
    // non-negative lightness axis and send that pixel to the above path,
    // whose [0,1] bracket always exists.  This is a documented guard, not a
    // silent change to the report's geometry.
    float j_focal = max(j_focal_raw, 0.0);
    float c_focal = max(focal.y, A2Z_CHROMA_EPSILON);
    bool below_selected = j < j_focal - j_focal * c / c_focal;
    bool below_valid = (c_focal > c + A2Z_CHROMA_EPSILON) &&
                       (j_focal_raw >= 0.0);
    bool above = !below_selected || !below_valid;

    bool yellow = a2z_hue_mapping == 1 && phi > 0.0 && phi < 180.0 &&
                  j > j_upper;
    bool cyan = a2z_hue_mapping == 1 && phi > 180.0 && phi < 240.0;
    bool mapped = false;
    float mapped_j = j;
    vec2 mapped_ab = ab;

    if (yellow || cyan) {
        float theta;

        if (yellow) {
            float k = a2z_path_k(j, j_upper, j_peak);
            theta = h - delta_y * k * sin(radians(phi));
        } else {
            theta = h + delta_c * sin(radians(3.0 * phi));
        }

        float maximum = 2.0 * c + 1.0;
        float first_step = maximum / float(A2Z_PATH_STEPS);
        float lower = 0.0;
        float upper = 0.0;
        float previous = 0.0;
        bool found = false;

        for (int i = 1; i <= A2Z_PATH_FINE && !found; i++) {
            float distance = first_step * float(i) / float(A2Z_PATH_FINE);
            vec2 test_ab;
            float test_j;
            bool inside = a2z_path_test(
                j, c, ab, theta, distance, j_focal, c_focal, above,
                viewing, test_ab, test_j
            );

            if (!found && inside) {
                found = true;
                lower = previous;
                upper = distance;
            }
            if (!found) {
                previous = distance;
            }
        }

        for (int i = 2; i <= A2Z_PATH_STEPS && !found; i++) {
            float distance = maximum * float(i) / float(A2Z_PATH_STEPS);
            vec2 test_ab;
            float test_j;
            bool inside = a2z_path_test(
                j, c, ab, theta, distance, j_focal, c_focal, above,
                viewing, test_ab, test_j
            );

            if (!found && inside) {
                found = true;
                lower = previous;
                upper = distance;
            }
            if (!found) {
                previous = distance;
            }
        }

        for (int i = 0; i < A2Z_PATH_ITERATIONS && found; i++) {
            float middle = 0.5 * (lower + upper);
            vec2 test_ab;
            float test_j;
            bool inside = a2z_path_test(
                j, c, ab, theta, middle, j_focal, c_focal, above,
                viewing, test_ab, test_j
            );

            if (inside) {
                upper = middle;
            } else {
                lower = middle;
            }
        }

        if (found) {
            float theta_radians = radians(theta);
            mapped_ab = ab - upper *
                        vec2(cos(theta_radians), sin(theta_radians));
            mapped_j = a2z_map_lightness(
                j, c, length(mapped_ab), j_focal, c_focal, above
            );
            mapped = true;
        }
    }

    if (!mapped && above) {
        float lower = 0.0;
        float upper = 1.0;

        for (int i = 0; i < A2Z_RAY_ITERATIONS; i++) {
            float weight = 0.5 * (lower + upper);
            float sample_j = mix(j_focal, j, weight);
            float sample_c = weight * c;
            sample_j = min(sample_j, j_bypass_t);

            if (sample_c <= a2z_sample_boundary(h, sample_j)) {
                lower = weight;
            } else {
                upper = weight;
            }
        }

        mapped_j = mix(j_focal, j, lower);
        mapped_ab = (lower * c) * hue_direction;
    } else if (!mapped) {
        float maximum = c_focal / max(c_focal - c, A2Z_CHROMA_EPSILON);
        float lower = 0.0;
        float upper = maximum;

        for (int i = 0; i < A2Z_RAY_ITERATIONS; i++) {
            float weight = 0.5 * (lower + upper);
            float sample_j = weight * j;
            float sample_c = c_focal + weight * (c - c_focal);
            sample_j = min(sample_j, j_bypass_t);

            if (sample_c <= a2z_sample_boundary(h, sample_j)) {
                upper = weight;
            } else {
                lower = weight;
            }
        }

        mapped_j = upper * j;
        mapped_ab = (c_focal + upper * (c - c_focal)) * hue_direction;
    }

    vec3 mapped_xyz = a2z_jab_to_xyz(vec3(mapped_j, mapped_ab), viewing);
    vec3 mapped_rgb = mapped_xyz * A2Z_XYZ_TO_BT709;

    if (any(isnan(mapped_rgb)) || any(isinf(mapped_rgb))) {
        mapped_rgb = vec3(0.0);
    }

    color.rgb = clamp(mapped_rgb, vec3(0.0), vec3(1.0));
    return color;
}
