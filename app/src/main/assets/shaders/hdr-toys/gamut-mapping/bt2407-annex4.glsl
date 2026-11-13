// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 4 adaptive core-region gamut mapping in CIELAB (D65).
// Input is linear BT.2020, output is linear BT.709, both linear-light with
// unit white 1; alpha is passed through. The reference implementation and
// the full provenance table live in bt2407-annex4/README.md; every constant
// below is either [report], [patent] or [design choice] there.
//
// Pipeline (six OUTPUT-hook passes, one static table chain):
//
//   A4_CUSP_T   513x1    target operational cusp (C_tc, L_tc)
//   A4_CUSP_S   513x1    source operational cusp (C_sc, L_sc)
//   A4_BOUND  513x513    target and source boundary chroma on cusp-relative
//                        lightness rows; two packed scalars per texel
//   A4_LINE     513x1    cusp line (m, L0) from the two cusps
//   A4_ANCHOR   513x1    anchor (C_a, L_a) from the 50% area solve
//   MAP                  hue alignment, core test, three-region scalar map
//
// Every saved pass texture is rgba16f: libplacebo's find_fbo_format prefers
// PL_FMT_FLOAT/16 with linear filtering and get_hook_tex always requests four
// components (verified against src/renderer.c). Each scalar is therefore
// stored as two half components, high = floor(value) and low = (value - high)
// * 1024, reconstructed as high + low / 1024. The integer part is exact in
// every table (their values are within +/-260, and half represents integers
// up to 2048 exactly); the residual is in [0, 1024) and its half quantization
// bounds the reconstruction error near 5e-4. Both parts are linear in the
// stored value, so hardware bilinear filtering still interpolates the scalar.
//
// An earlier revision used high = half(value) via unpackHalf2x16(
// packHalf2x16(...)) and low = (value - high) * 1024. On the Vulkan toolchain
// the roundtrip was measured to fold to the identity, so low was always zero
// and every saved scalar was truncated to the half grid (the cusp lightness
// 96.5518 became 96.5000); this is why the split is now plain arithmetic
// (README 7.1, 12.10). The pixel pass evaluates the reference equations on
// these tables; the tables are rebuilt every frame the hook runs.
//
// The pixel pass is bounded: at most three first-crossing searches of 40 scan
// samples plus 16 bisections, each evaluating one or two bilinear table
// fetches (233 fetches in the worst case, region 1 or 2; region 3 uses six).
// In-core colours issue five fetches before the early-out (target cusp,
// source cusp, cusp line, anchor, target boundary); the core test needs three.
//
// Known limitation (README 11.6): the BT.709 and BT.2020 constant-hue leaves
// are disconnected near yellow. The tables store the axis-connected
// first-crossing boundary, and hue interpolation across the resulting cliff
// leaves a measured RGB error of 0.015012 at a yellow highlight with the
// default parameters; 8 of 8192 samples exceed 1e-2, the next worst being the
// BT.2020 cyan and blue corners at 0.01226 and 0.01170 (hard bound 0.02). With
// tau=0.35 and align_strength=0.6 the cliff falls between table rows for two
// highlight samples and reaches 0.18431. Those samples are classified
// separately by validate.py --gpu (exact reference boundary versus what the
// table can represent, threshold 3 C) and reported in README 12.10, rather
// than hidden by a looser global tolerance.
//
// Domain policy [design choice], README 9.2: non-finite components become 0,
// negative components become 0, and a triplet whose peak exceeds 1 is scaled
// by its peak (so a coloured over-white keeps its ratios). The output is
// clamped to [0,1]. A neutral input (C < 1e-7) maps to neutral gray.

// Push-constant parameters (TYPE DYNAMIC marks them as dynamic uniforms, which
// libplacebo excludes from the pass signature, so changing them through
// --glsl-shader-opts updates the values without recompiling the shader):
//
//   tau             core area as a fraction of the target gamut area,
//                   default 0.5 (the report's experiments), range [0.01, 0.99]
//   align_strength  hue alignment strength, default 1.0, range [0, 1];
//                   strength 1 aligns the six source key hues exactly onto
//                   the target key hues, 0 disables the warp
//
// The parameter blocks must be contiguous: libplacebo treats every line up to
// the next directive marker as the initial value, so no comments may sit
// between an initial value and the following directive.

//!PARAM tau
//!DESC core area as a fraction of the target gamut area
//!TYPE DYNAMIC float
//!MINIMUM 0.01
//!MAXIMUM 0.99
0.5

//!PARAM align_strength
//!DESC hue alignment strength, 1 = fully aligned, 0 = no warp
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!HOOK OUTPUT
//!SAVE A4_CUSP_T
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!DESC gamut mapping (BT.2407 annex 4, target cusp)

const float LAB_DELTA = 6.0 / 29.0;
const float LAB_LINEAR_SCALE = 108.0 / 841.0;
const float CUSP_MAX_CHROMA = 260.0;
const int CUSP_SCAN_STEPS = 64;
const int CUSP_BISECTIONS = 40;
const int CUSP_LIGHTNESS_STEPS = 257;
const int CUSP_GOLDEN_ITERATIONS = 48;

const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

// Rows of the reference XYZ->RGB matrices (column-major mat3 constructor,
// used as row-vector * mat3).
const mat3 XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

vec3 lab_to_xyz(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    vec3 f = vec3(
        fy + lab.y / 500.0,
        fy,
        fy - lab.z / 200.0
    );
    vec3 f_cubed = f * f * f;
    vec3 f_linear = LAB_LINEAR_SCALE * (f - 4.0 / 29.0);
    return mix(f_linear, f_cubed, step(vec3(LAB_DELTA), f)) * D65_WHITE;
}

bool gamut_inside(vec3 lab, bool source) {
    vec3 xyz = lab_to_xyz(lab);
    vec3 rgb;

    if (source) {
        rgb = xyz * XYZ_TO_BT2020;
    } else {
        rgb = xyz * XYZ_TO_BT709;
    }

    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

// First chroma at which the ray from the achromatic axis leaves the gamut
// [design choice, README 5.1/7.3]. A scan brackets the first sign change;
// only that interval is bisected, so a non-convex slice cannot move the root.
float boundary_chroma(float lightness, float hue_degrees, bool source) {
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));

    if (!gamut_inside(vec3(lightness, 0.0, 0.0), source)) {
        return 0.0;
    }

    float lower = 0.0;
    float upper = CUSP_MAX_CHROMA;
    bool found = false;
    bool previous_inside = true;
    float previous_chroma = 0.0;

    for (int i = 1; i <= CUSP_SCAN_STEPS; i++) {
        float chroma = CUSP_MAX_CHROMA * float(i) / float(CUSP_SCAN_STEPS);
        vec2 ab = chroma * direction;
        bool inside = gamut_inside(vec3(lightness, ab.x, ab.y), source);

        if (!found && previous_inside && !inside) {
            lower = previous_chroma;
            upper = chroma;
            found = true;
        }

        previous_inside = inside;
        previous_chroma = chroma;
    }

    if (!found) {
        return CUSP_MAX_CHROMA;
    }

    for (int i = 0; i < CUSP_BISECTIONS; i++) {
        float middle = 0.5 * (lower + upper);
        vec2 ab = middle * direction;

        if (gamut_inside(vec3(lightness, ab.x, ab.y), source)) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    return lower;
}

// (L_c, C_c): maximum of the axis-connected boundary, found by a 257-point
// lightness scan followed by a golden-section refinement of the bracketing
// interval [design choice, README 5.1].
vec2 find_cusp(float hue_degrees, bool source) {
    float best_chroma = -1.0;
    float best_lightness = 0.0;
    int best_index = 0;

    for (int i = 0; i < CUSP_LIGHTNESS_STEPS; i++) {
        float lightness = 100.0 * float(i) /
                          float(CUSP_LIGHTNESS_STEPS - 1);
        float chroma = boundary_chroma(lightness, hue_degrees, source);

        if (chroma > best_chroma) {
            best_chroma = chroma;
            best_lightness = lightness;
            best_index = i;
        }
    }

    float step = 100.0 / float(CUSP_LIGHTNESS_STEPS - 1);
    float lower = float(max(best_index - 1, 0)) * step;
    float upper = float(min(best_index + 1, CUSP_LIGHTNESS_STEPS - 1)) * step;
    float invphi = 0.6180339887498949;
    float a = lower;
    float b = upper;
    float c = b - invphi * (b - a);
    float d = a + invphi * (b - a);
    float fc = boundary_chroma(c, hue_degrees, source);
    float fd = boundary_chroma(d, hue_degrees, source);

    for (int i = 0; i < CUSP_GOLDEN_ITERATIONS; i++) {
        if (fc >= fd) {
            b = d;
            d = c;
            fd = fc;
            c = b - invphi * (b - a);
            fc = boundary_chroma(c, hue_degrees, source);
        } else {
            a = c;
            c = d;
            fc = fd;
            d = a + invphi * (b - a);
            fd = boundary_chroma(d, hue_degrees, source);
        }
    }

    float lightness = 0.5 * (a + b);
    float chroma = boundary_chroma(lightness, hue_degrees, source);
    float sampled = max(
        best_chroma,
        max(
            boundary_chroma(lower, hue_degrees, source),
            boundary_chroma(upper, hue_degrees, source)
        )
    );

    return vec2(lightness, max(chroma, sampled));
}

// Two components per scalar; floor(value) is exact in half for the table
// range (|value| <= 260) and the residual in [0, 1024) carries about ten
// more bits, so bilinear filtering of saved tables reconstructs the
// interpolated scalar to roughly 5e-4.
vec2 pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column > 512) {
        return;
    }

    float hue = 360.0 * float(column) / 512.0;
    vec2 cusp = find_cusp(hue, false);

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(pack_scalar(cusp.y), pack_scalar(cusp.x))
    );
}

// ---------------------------------------------------------------------------
// Pass 2: source operational cusp per aligned hue column.
//
// The columns are indexed by the aligned hue h_a, exactly like the target
// table; the source cusp is evaluated at h_u = A_s^-1(h_a) [report A4.1.2;
// piecewise-linear alignment is a design choice, README 4].
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4_CUSP_S
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!DESC gamut mapping (BT.2407 annex 4, source cusp)

const float CUSP_S_LAB_DELTA = 6.0 / 29.0;
const float CUSP_S_LINEAR_SCALE = 108.0 / 841.0;
const float CUSP_S_MAX_CHROMA = 260.0;
const int CUSP_S_SCAN_STEPS = 64;
const int CUSP_S_BISECTIONS = 40;
const int CUSP_S_LIGHTNESS_STEPS = 257;
const int CUSP_S_GOLDEN_ITERATIONS = 48;

const vec3 CUSP_S_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 CUSP_S_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 CUSP_S_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

const float CUSP_S_SOURCE_HUE_0 = 40.58420265144585;
const float CUSP_S_SOURCE_HUE_1 = 98.91771034302026;
const float CUSP_S_SOURCE_HUE_2 = 145.91119552558246;
const float CUSP_S_SOURCE_HUE_3 = 190.30682592307048;
const float CUSP_S_SOURCE_HUE_4 = 305.6018876507444;
const float CUSP_S_SOURCE_HUE_5 = 334.885357838239;
const float CUSP_S_TARGET_HUE_0 = 39.9998651543981;
const float CUSP_S_TARGET_HUE_1 = 102.85405969397792;
const float CUSP_S_TARGET_HUE_2 = 136.01306868501493;
const float CUSP_S_TARGET_HUE_3 = 196.3765264724403;
const float CUSP_S_TARGET_HUE_4 = 306.28880325729324;
const float CUSP_S_TARGET_HUE_5 = 328.23408825357717;

void cusp_s_image_segment(
    float unwrapped,
    float strength,
    out float source_low,
    out float source_high,
    out float target_low,
    out float target_high
) {
    float image_1 = CUSP_S_SOURCE_HUE_1 +
                    strength * (CUSP_S_TARGET_HUE_1 - CUSP_S_SOURCE_HUE_1);
    float image_2 = CUSP_S_SOURCE_HUE_2 +
                    strength * (CUSP_S_TARGET_HUE_2 - CUSP_S_SOURCE_HUE_2);
    float image_3 = CUSP_S_SOURCE_HUE_3 +
                    strength * (CUSP_S_TARGET_HUE_3 - CUSP_S_SOURCE_HUE_3);
    float image_4 = CUSP_S_SOURCE_HUE_4 +
                    strength * (CUSP_S_TARGET_HUE_4 - CUSP_S_SOURCE_HUE_4);
    float image_5 = CUSP_S_SOURCE_HUE_5 +
                    strength * (CUSP_S_TARGET_HUE_5 - CUSP_S_SOURCE_HUE_5);

    if (unwrapped < image_1) {
        source_low = CUSP_S_SOURCE_HUE_0;
        source_high = CUSP_S_SOURCE_HUE_1;
        target_low = CUSP_S_TARGET_HUE_0;
        target_high = CUSP_S_TARGET_HUE_1;
    } else if (unwrapped < image_2) {
        source_low = CUSP_S_SOURCE_HUE_1;
        source_high = CUSP_S_SOURCE_HUE_2;
        target_low = CUSP_S_TARGET_HUE_1;
        target_high = CUSP_S_TARGET_HUE_2;
    } else if (unwrapped < image_3) {
        source_low = CUSP_S_SOURCE_HUE_2;
        source_high = CUSP_S_SOURCE_HUE_3;
        target_low = CUSP_S_TARGET_HUE_2;
        target_high = CUSP_S_TARGET_HUE_3;
    } else if (unwrapped < image_4) {
        source_low = CUSP_S_SOURCE_HUE_3;
        source_high = CUSP_S_SOURCE_HUE_4;
        target_low = CUSP_S_TARGET_HUE_3;
        target_high = CUSP_S_TARGET_HUE_4;
    } else if (unwrapped < image_5) {
        source_low = CUSP_S_SOURCE_HUE_4;
        source_high = CUSP_S_SOURCE_HUE_5;
        target_low = CUSP_S_TARGET_HUE_4;
        target_high = CUSP_S_TARGET_HUE_5;
    } else {
        source_low = CUSP_S_SOURCE_HUE_5;
        source_high = CUSP_S_SOURCE_HUE_0 + 360.0;
        target_low = CUSP_S_TARGET_HUE_5;
        target_high = CUSP_S_TARGET_HUE_0 + 360.0;
    }
}

float cusp_s_align_inverse(float hue_degrees, float strength) {
    float wrapped = mod(hue_degrees, 360.0);
    float image_0 = CUSP_S_SOURCE_HUE_0 +
                    strength * (CUSP_S_TARGET_HUE_0 - CUSP_S_SOURCE_HUE_0);
    float cut = mod(image_0, 360.0);
    float unwrapped = wrapped < cut ? wrapped + 360.0 : wrapped;

    float source_low;
    float source_high;
    float target_low;
    float target_high;
    cusp_s_image_segment(
        unwrapped,
        strength,
        source_low,
        source_high,
        target_low,
        target_high
    );

    float slope = (target_high - target_low) / (source_high - source_low);
    float scale = 1.0 + strength * (slope - 1.0);
    float offset = strength * (target_low - slope * source_low);
    return mod((unwrapped - offset) / scale, 360.0);
}

vec3 cusp_s_lab_to_xyz(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    vec3 f = vec3(
        fy + lab.y / 500.0,
        fy,
        fy - lab.z / 200.0
    );
    vec3 f_cubed = f * f * f;
    vec3 f_linear = CUSP_S_LINEAR_SCALE * (f - 4.0 / 29.0);
    return mix(f_linear, f_cubed, step(vec3(CUSP_S_LAB_DELTA), f)) *
           CUSP_S_D65;
}

bool cusp_s_gamut_inside(vec3 lab, bool source) {
    vec3 xyz = cusp_s_lab_to_xyz(lab);
    vec3 rgb;

    if (source) {
        rgb = xyz * CUSP_S_XYZ_TO_BT2020;
    } else {
        rgb = xyz * CUSP_S_XYZ_TO_BT709;
    }

    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float cusp_s_boundary_chroma(float lightness, float hue_degrees, bool source) {
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));

    if (!cusp_s_gamut_inside(vec3(lightness, 0.0, 0.0), source)) {
        return 0.0;
    }

    float lower = 0.0;
    float upper = CUSP_S_MAX_CHROMA;
    bool found = false;
    bool previous_inside = true;
    float previous_chroma = 0.0;

    for (int i = 1; i <= CUSP_S_SCAN_STEPS; i++) {
        float chroma = CUSP_S_MAX_CHROMA * float(i) /
                       float(CUSP_S_SCAN_STEPS);
        vec2 ab = chroma * direction;
        bool inside = cusp_s_gamut_inside(
            vec3(lightness, ab.x, ab.y),
            source
        );

        if (!found && previous_inside && !inside) {
            lower = previous_chroma;
            upper = chroma;
            found = true;
        }

        previous_inside = inside;
        previous_chroma = chroma;
    }

    if (!found) {
        return CUSP_S_MAX_CHROMA;
    }

    for (int i = 0; i < CUSP_S_BISECTIONS; i++) {
        float middle = 0.5 * (lower + upper);
        vec2 ab = middle * direction;

        if (cusp_s_gamut_inside(vec3(lightness, ab.x, ab.y), source)) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    return lower;
}

vec2 cusp_s_find_cusp(float hue_degrees, bool source) {
    float best_chroma = -1.0;
    float best_lightness = 0.0;
    int best_index = 0;

    for (int i = 0; i < CUSP_S_LIGHTNESS_STEPS; i++) {
        float lightness = 100.0 * float(i) /
                          float(CUSP_S_LIGHTNESS_STEPS - 1);
        float chroma = cusp_s_boundary_chroma(lightness, hue_degrees, source);

        if (chroma > best_chroma) {
            best_chroma = chroma;
            best_lightness = lightness;
            best_index = i;
        }
    }

    float step = 100.0 / float(CUSP_S_LIGHTNESS_STEPS - 1);
    float lower = float(max(best_index - 1, 0)) * step;
    float upper = float(min(best_index + 1, CUSP_S_LIGHTNESS_STEPS - 1)) * step;
    float invphi = 0.6180339887498949;
    float a = lower;
    float b = upper;
    float c = b - invphi * (b - a);
    float d = a + invphi * (b - a);
    float fc = cusp_s_boundary_chroma(c, hue_degrees, source);
    float fd = cusp_s_boundary_chroma(d, hue_degrees, source);

    for (int i = 0; i < CUSP_S_GOLDEN_ITERATIONS; i++) {
        if (fc >= fd) {
            b = d;
            d = c;
            fd = fc;
            c = b - invphi * (b - a);
            fc = cusp_s_boundary_chroma(c, hue_degrees, source);
        } else {
            a = c;
            c = d;
            fc = fd;
            d = a + invphi * (b - a);
            fd = cusp_s_boundary_chroma(d, hue_degrees, source);
        }
    }

    float lightness = 0.5 * (a + b);
    float chroma = cusp_s_boundary_chroma(lightness, hue_degrees, source);
    float sampled = max(
        best_chroma,
        max(
            cusp_s_boundary_chroma(lower, hue_degrees, source),
            cusp_s_boundary_chroma(upper, hue_degrees, source)
        )
    );

    return vec2(lightness, max(chroma, sampled));
}

vec2 cusp_s_pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column > 512) {
        return;
    }

    float hue = 360.0 * float(column) / 512.0;
    float source_hue = cusp_s_align_inverse(hue, align_strength);
    vec2 cusp = cusp_s_find_cusp(source_hue, true);

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(cusp_s_pack_scalar(cusp.y), cusp_s_pack_scalar(cusp.x))
    );
}

// ---------------------------------------------------------------------------
// Pass 3: boundary table on cusp-relative lightness rows.
//
// Row 256 is exactly the cusp; rows 0..256 cover [0, L_c] and rows 256..512
// cover [L_c, 100]. This avoids interpolating across the cusp corner, which
// a uniform lightness table cannot resolve (README 7.2). The target side is
// parameterized by L_tc and the source side by L_sc, both per hue column.
// The four components are two half-split scalars: rg = C_t, ba = C_o.
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4_BOUND
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 513
//!COMPUTE 16 16
//!BIND A4_CUSP_T
//!BIND A4_CUSP_S
//!DESC gamut mapping (BT.2407 annex 4, boundary table)

const float BOUND_LAB_DELTA = 6.0 / 29.0;
const float BOUND_LINEAR_SCALE = 108.0 / 841.0;
const float BOUND_MAX_CHROMA = 260.0;
const int BOUND_SCAN_STEPS = 64;
const int BOUND_BISECTIONS = 40;

const vec3 BOUND_D65 = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 BOUND_XYZ_TO_BT2020 = mat3(
     1.716651187971268, -0.3556707837763925, -0.25336628137365985,
    -0.666684351832489,  1.6164812366349388,  0.01576854581391114,
     0.01763985744531079, -0.04277061325780852, 0.9421031212354739
);

const mat3 BOUND_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

const float BOUND_SOURCE_HUE_0 = 40.58420265144585;
const float BOUND_SOURCE_HUE_1 = 98.91771034302026;
const float BOUND_SOURCE_HUE_2 = 145.91119552558246;
const float BOUND_SOURCE_HUE_3 = 190.30682592307048;
const float BOUND_SOURCE_HUE_4 = 305.6018876507444;
const float BOUND_SOURCE_HUE_5 = 334.885357838239;
const float BOUND_TARGET_HUE_0 = 39.9998651543981;
const float BOUND_TARGET_HUE_1 = 102.85405969397792;
const float BOUND_TARGET_HUE_2 = 136.01306868501493;
const float BOUND_TARGET_HUE_3 = 196.3765264724403;
const float BOUND_TARGET_HUE_4 = 306.28880325729324;
const float BOUND_TARGET_HUE_5 = 328.23408825357717;

void bound_image_segment(
    float unwrapped,
    float strength,
    out float source_low,
    out float source_high,
    out float target_low,
    out float target_high
) {
    float image_1 = BOUND_SOURCE_HUE_1 +
                    strength * (BOUND_TARGET_HUE_1 - BOUND_SOURCE_HUE_1);
    float image_2 = BOUND_SOURCE_HUE_2 +
                    strength * (BOUND_TARGET_HUE_2 - BOUND_SOURCE_HUE_2);
    float image_3 = BOUND_SOURCE_HUE_3 +
                    strength * (BOUND_TARGET_HUE_3 - BOUND_SOURCE_HUE_3);
    float image_4 = BOUND_SOURCE_HUE_4 +
                    strength * (BOUND_TARGET_HUE_4 - BOUND_SOURCE_HUE_4);
    float image_5 = BOUND_SOURCE_HUE_5 +
                    strength * (BOUND_TARGET_HUE_5 - BOUND_SOURCE_HUE_5);

    if (unwrapped < image_1) {
        source_low = BOUND_SOURCE_HUE_0;
        source_high = BOUND_SOURCE_HUE_1;
        target_low = BOUND_TARGET_HUE_0;
        target_high = BOUND_TARGET_HUE_1;
    } else if (unwrapped < image_2) {
        source_low = BOUND_SOURCE_HUE_1;
        source_high = BOUND_SOURCE_HUE_2;
        target_low = BOUND_TARGET_HUE_1;
        target_high = BOUND_TARGET_HUE_2;
    } else if (unwrapped < image_3) {
        source_low = BOUND_SOURCE_HUE_2;
        source_high = BOUND_SOURCE_HUE_3;
        target_low = BOUND_TARGET_HUE_2;
        target_high = BOUND_TARGET_HUE_3;
    } else if (unwrapped < image_4) {
        source_low = BOUND_SOURCE_HUE_3;
        source_high = BOUND_SOURCE_HUE_4;
        target_low = BOUND_TARGET_HUE_3;
        target_high = BOUND_TARGET_HUE_4;
    } else if (unwrapped < image_5) {
        source_low = BOUND_SOURCE_HUE_4;
        source_high = BOUND_SOURCE_HUE_5;
        target_low = BOUND_TARGET_HUE_4;
        target_high = BOUND_TARGET_HUE_5;
    } else {
        source_low = BOUND_SOURCE_HUE_5;
        source_high = BOUND_SOURCE_HUE_0 + 360.0;
        target_low = BOUND_TARGET_HUE_5;
        target_high = BOUND_TARGET_HUE_0 + 360.0;
    }
}

float bound_align_inverse(float hue_degrees, float strength) {
    float wrapped = mod(hue_degrees, 360.0);
    float image_0 = BOUND_SOURCE_HUE_0 +
                    strength * (BOUND_TARGET_HUE_0 - BOUND_SOURCE_HUE_0);
    float cut = mod(image_0, 360.0);
    float unwrapped = wrapped < cut ? wrapped + 360.0 : wrapped;

    float source_low;
    float source_high;
    float target_low;
    float target_high;
    bound_image_segment(
        unwrapped,
        strength,
        source_low,
        source_high,
        target_low,
        target_high
    );

    float slope = (target_high - target_low) / (source_high - source_low);
    float scale = 1.0 + strength * (slope - 1.0);
    float offset = strength * (target_low - slope * source_low);
    return mod((unwrapped - offset) / scale, 360.0);
}

vec3 bound_lab_to_xyz(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    vec3 f = vec3(
        fy + lab.y / 500.0,
        fy,
        fy - lab.z / 200.0
    );
    vec3 f_cubed = f * f * f;
    vec3 f_linear = BOUND_LINEAR_SCALE * (f - 4.0 / 29.0);
    return mix(f_linear, f_cubed, step(vec3(BOUND_LAB_DELTA), f)) * BOUND_D65;
}

bool bound_gamut_inside(vec3 lab, bool source) {
    vec3 xyz = bound_lab_to_xyz(lab);
    vec3 rgb;

    if (source) {
        rgb = xyz * BOUND_XYZ_TO_BT2020;
    } else {
        rgb = xyz * BOUND_XYZ_TO_BT709;
    }

    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float bound_boundary_chroma(float lightness, float hue_degrees, bool source) {
    float angle = radians(hue_degrees);
    vec2 direction = vec2(cos(angle), sin(angle));

    if (!bound_gamut_inside(vec3(lightness, 0.0, 0.0), source)) {
        return 0.0;
    }

    float lower = 0.0;
    float upper = BOUND_MAX_CHROMA;
    bool found = false;
    bool previous_inside = true;
    float previous_chroma = 0.0;

    for (int i = 1; i <= BOUND_SCAN_STEPS; i++) {
        float chroma = BOUND_MAX_CHROMA * float(i) / float(BOUND_SCAN_STEPS);
        vec2 ab = chroma * direction;
        bool inside = bound_gamut_inside(vec3(lightness, ab.x, ab.y), source);

        if (!found && previous_inside && !inside) {
            lower = previous_chroma;
            upper = chroma;
            found = true;
        }

        previous_inside = inside;
        previous_chroma = chroma;
    }

    if (!found) {
        return BOUND_MAX_CHROMA;
    }

    for (int i = 0; i < BOUND_BISECTIONS; i++) {
        float middle = 0.5 * (lower + upper);
        vec2 ab = middle * direction;

        if (bound_gamut_inside(vec3(lightness, ab.x, ab.y), source)) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    return lower;
}

vec2 pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float unpack_scalar(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float cusp_relative_lightness(int row, float cusp_lightness) {
    if (row <= 256) {
        return cusp_lightness * float(row) / 256.0;
    }

    return cusp_lightness +
           (100.0 - cusp_lightness) * float(row - 256) / 256.0;
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);

    if (position.x > 512 || position.y > 512) {
        return;
    }

    vec4 target_cusp = A4_CUSP_T_mul *
                       texelFetch(A4_CUSP_T_raw, ivec2(position.x, 0), 0);
    vec4 source_cusp = A4_CUSP_S_mul *
                       texelFetch(A4_CUSP_S_raw, ivec2(position.x, 0), 0);
    float target_cusp_chroma = unpack_scalar(target_cusp.xy);
    float target_cusp_lightness = unpack_scalar(target_cusp.zw);
    float source_cusp_chroma = unpack_scalar(source_cusp.xy);
    float source_cusp_lightness = unpack_scalar(source_cusp.zw);
    float hue = 360.0 * float(position.x) / 512.0;
    float source_hue = bound_align_inverse(hue, align_strength);
    float target_lightness = cusp_relative_lightness(
        position.y,
        target_cusp_lightness
    );
    float source_lightness = cusp_relative_lightness(
        position.y,
        source_cusp_lightness
    );
    float target_chroma = bound_boundary_chroma(
        target_lightness,
        hue,
        false
    );
    float source_chroma = bound_boundary_chroma(
        source_lightness,
        source_hue,
        true
    );

    imageStore(
        out_image,
        position,
        vec4(
            pack_scalar(target_chroma),
            pack_scalar(source_chroma)
        )
    );
}

// ---------------------------------------------------------------------------
// Pass 4: cusp line per hue column.
//
// m and L0 define the cusp line [report A4.1.3.1]: the line through the two
// operational cusps. It is stored packed and sampled by the anchor and pixel
// passes; both cusps are reconstructed with the same half-split scheme, so
// the table is consistent with the boundary table.
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4_LINE
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4_CUSP_T
//!BIND A4_CUSP_S
//!DESC gamut mapping (BT.2407 annex 4, cusp line)

vec2 line_pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float line_unpack_scalar(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

void hook() {
    int column = int(gl_GlobalInvocationID.x);

    if (column > 512) {
        return;
    }

    vec4 target_cusp = A4_CUSP_T_mul *
                       texelFetch(A4_CUSP_T_raw, ivec2(column, 0), 0);
    vec4 source_cusp = A4_CUSP_S_mul *
                       texelFetch(A4_CUSP_S_raw, ivec2(column, 0), 0);
    float target_cusp_chroma = line_unpack_scalar(target_cusp.xy);
    float target_cusp_lightness = line_unpack_scalar(target_cusp.zw);
    float source_cusp_chroma = line_unpack_scalar(source_cusp.xy);
    float source_cusp_lightness = line_unpack_scalar(source_cusp.zw);
    float denominator = source_cusp_chroma - target_cusp_chroma;
    float slope = 0.0;

    if (abs(denominator) > 1e-9) {
        slope = (source_cusp_lightness - target_cusp_lightness) /
                denominator;
    }

    float intercept = target_cusp_lightness - slope * target_cusp_chroma;

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(line_pack_scalar(slope), line_pack_scalar(intercept))
    );
}

// ---------------------------------------------------------------------------
// Pass 5: anchor chroma from the core-area fraction.
//
// [report A4.1.3.1 (core region, cusp-to-cusp line); patent US10841627B2
// corroborates the triangle and cusp intercept]. [design choice, README 5]
// The triangle is clipped by the target boundary and its area is solved for
// tau of the target gamut area. The area
// integral uses the table's 513 cusp-relative lightness nodes, which are
// exactly the reference's area nodes, and the table's stored target boundary,
// so the solve reproduces the reference anchor (README 7.2).
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!SAVE A4_ANCHOR
//!COMPONENTS 4
//!WIDTH 513
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND A4_CUSP_T
//!BIND A4_LINE
//!BIND A4_BOUND
//!DESC gamut mapping (BT.2407 annex 4, anchor)

const int ANCHOR_AREA_STEPS = 512;
const int ANCHOR_BISECTIONS = 32;

vec2 anchor_pack_scalar(float value) {
    float high = floor(value);
    return vec2(high, (value - high) * 1024.0);
}

float anchor_unpack_scalar(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

float anchor_triangle_chroma(float lightness, float anchor_chroma,
                             float anchor_lightness) {
    float low = anchor_chroma * lightness / max(anchor_lightness, 1e-12);
    float high = anchor_chroma * (100.0 - lightness) /
                 max(100.0 - anchor_lightness, 1e-12);
    float value = lightness <= anchor_lightness ? low : high;

    if (lightness < 0.0 || lightness > 100.0) {
        return 0.0;
    }

    return value;
}

float anchor_row_lightness(int row, float cusp_lightness) {
    if (row <= 256) {
        return cusp_lightness * float(row) / 256.0;
    }

    return cusp_lightness +
           (100.0 - cusp_lightness) * float(row - 256) / 256.0;
}

float anchor_boundary(int column, int row) {
    vec4 stored = A4_BOUND_mul *
                  texelFetch(A4_BOUND_raw, ivec2(column, row), 0);
    return stored.r + stored.g / 1024.0;
}

// Target gamut area over the same cusp-relative nodes: the integral of the
// stored target boundary, with no triangle clipping. The reference normalizes
// the core fraction tau by this area [report A4.3.1 (50% experimental
// setting); design choice, README 5].
float anchor_gamut_area(float cusp_lightness, int column) {
    float previous_lightness = anchor_row_lightness(0, cusp_lightness);
    float previous_value = anchor_boundary(column, 0);
    float total = 0.0;

    for (int row = 1; row <= ANCHOR_AREA_STEPS; row++) {
        float lightness = anchor_row_lightness(row, cusp_lightness);
        float value = anchor_boundary(column, row);
        total += 0.5 * (previous_value + value) *
                 (lightness - previous_lightness);
        previous_lightness = lightness;
        previous_value = value;
    }

    return total;
}

float anchor_area(float anchor_chroma, float slope, float intercept,
                  float cusp_lightness, int column) {
    float anchor_lightness = intercept + slope * anchor_chroma;
    float previous_lightness = anchor_row_lightness(0, cusp_lightness);
    float previous_value = min(
        anchor_triangle_chroma(
            previous_lightness,
            anchor_chroma,
            anchor_lightness
        ),
        anchor_boundary(column, 0)
    );
    float total = 0.0;

    for (int row = 1; row <= ANCHOR_AREA_STEPS; row++) {
        float lightness = anchor_row_lightness(row, cusp_lightness);
        float value = min(
            anchor_triangle_chroma(
                lightness,
                anchor_chroma,
                anchor_lightness
            ),
            anchor_boundary(column, row)
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

    if (column > 512) {
        return;
    }

    vec4 target_cusp = A4_CUSP_T_mul *
                       texelFetch(A4_CUSP_T_raw, ivec2(column, 0), 0);
    vec4 line_data = A4_LINE_mul *
                     texelFetch(A4_LINE_raw, ivec2(column, 0), 0);
    float target_cusp_chroma = anchor_unpack_scalar(target_cusp.xy);
    float target_cusp_lightness = anchor_unpack_scalar(target_cusp.zw);
    float slope = anchor_unpack_scalar(line_data.xy);
    float intercept = anchor_unpack_scalar(line_data.zw);
    float gamut_area = anchor_gamut_area(target_cusp_lightness, column);
    float maximum_area = anchor_area(
        target_cusp_chroma,
        slope,
        intercept,
        target_cusp_lightness,
        column
    );
    // tau of the target gamut area, bounded by the largest reachable core
    // (the reference clamps to the triangle at the target cusp).
    float target_area = min(tau * gamut_area, maximum_area);
    float lower = 0.0;
    float upper = target_cusp_chroma;

    for (int i = 0; i < ANCHOR_BISECTIONS; i++) {
        float middle = 0.5 * (lower + upper);
        float area = anchor_area(
            middle,
            slope,
            intercept,
            target_cusp_lightness,
            column
        );

        if (area < target_area) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    float result_chroma = 0.5 * (lower + upper);
    float result_lightness = intercept + slope * result_chroma;

    imageStore(
        out_image,
        ivec2(column, 0),
        vec4(
            anchor_pack_scalar(result_chroma),
            anchor_pack_scalar(result_lightness)
        )
    );
}

// ---------------------------------------------------------------------------
// Pass 6: pixel mapping.
//
// Hue is aligned first [report A4.1.2]. The core is the triangle clipped by
// the target boundary; colours inside it keep L and C and the aligned hue.
// Outside it, the colour is projected along one of the report's three region
// paths onto the same region at the target boundary [report A4.1.3.2;
// classification and the linear scalar map are design choices, README 6].
// The outer endpoint is max(source, target) boundary chroma [design choice,
// README 6.2], evaluated from the A4_BOUND table. After mapping the colour is
// converted back to linear BT.709 and clamped to [0,1].
// ---------------------------------------------------------------------------

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND A4_CUSP_T
//!BIND A4_CUSP_S
//!BIND A4_LINE
//!BIND A4_ANCHOR
//!BIND A4_BOUND
//!DESC gamut mapping (BT.2407 annex 4)

const float MAP_LAB_DELTA = 6.0 / 29.0;
const float MAP_LAB_DELTA_CUBED = 216.0 / 24389.0;
const float MAP_LINEAR_SCALE = 108.0 / 841.0;
const int MAP_SCAN_STEPS = 40;
const int MAP_ROOT_ITERATIONS = 16;
const float MAP_ROOT_TOLERANCE = 1e-5;
const float MAP_CHROMA_EPSILON = 1e-7;
const float MAP_SHELL_EPSILON = 1e-7;

const vec3 MAP_D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 MAP_BT2020_TO_XYZ = mat3(
    0.6369580483012911, 0.14461690358620838, 0.16888097516417208,
    0.26270021201126698, 0.67799807151887104, 0.059301716469861952,
    0.0, 0.028072693049087438, 1.0609850577107909
);

const mat3 MAP_XYZ_TO_BT709 = mat3(
     3.240969941904523, -1.5373831775700941, -0.49861076029300355,
    -0.9692436362808796, 1.8759675015077202, 0.04155505740717562,
     0.05563007969699364, -0.2039769588889765, 1.0569715142428784
);

const float MAP_SOURCE_HUE_0 = 40.58420265144585;
const float MAP_SOURCE_HUE_1 = 98.91771034302026;
const float MAP_SOURCE_HUE_2 = 145.91119552558246;
const float MAP_SOURCE_HUE_3 = 190.30682592307048;
const float MAP_SOURCE_HUE_4 = 305.6018876507444;
const float MAP_SOURCE_HUE_5 = 334.885357838239;
const float MAP_TARGET_HUE_0 = 39.9998651543981;
const float MAP_TARGET_HUE_1 = 102.85405969397792;
const float MAP_TARGET_HUE_2 = 136.01306868501493;
const float MAP_TARGET_HUE_3 = 196.3765264724403;
const float MAP_TARGET_HUE_4 = 306.28880325729324;
const float MAP_TARGET_HUE_5 = 328.23408825357717;

const int MODE_CORE = 0;
const int MODE_TARGET = 1;
const int MODE_OUTER = 2;
const int MODE_CORE_2 = 3;
const int MODE_TARGET_2 = 4;
const int MODE_OUTER_2 = 5;

struct Trace {
    float lightness;
    float chroma;
    float slope;
    float intercept;
    float anchor_chroma;
    float anchor_lightness;
    float target_cusp_lightness;
    float source_cusp_lightness;
    float aligned_hue;
    float offset;
};

float map_lab_forward(float value) {
    if (value > MAP_LAB_DELTA_CUBED) {
        return pow(value, 1.0 / 3.0);
    }

    return value / MAP_LINEAR_SCALE + 4.0 / 29.0;
}

vec3 map_xyz_to_lab(vec3 xyz) {
    vec3 f = xyz / MAP_D65_WHITE;
    f = vec3(
        map_lab_forward(f.x),
        map_lab_forward(f.y),
        map_lab_forward(f.z)
    );
    return vec3(
        116.0 * f.y - 16.0,
        500.0 * (f.x - f.y),
        200.0 * (f.y - f.z)
    );
}

vec3 map_lab_to_xyz(vec3 lab) {
    float fy = (lab.x + 16.0) / 116.0;
    vec3 f = vec3(
        fy + lab.y / 500.0,
        fy,
        fy - lab.z / 200.0
    );
    vec3 f_cubed = f * f * f;
    vec3 f_linear = MAP_LINEAR_SCALE * (f - 4.0 / 29.0);
    return mix(f_linear, f_cubed, step(vec3(MAP_LAB_DELTA), f)) *
           MAP_D65_WHITE;
}

void map_source_segment(
    float unwrapped,
    out float source_low,
    out float source_high,
    out float target_low,
    out float target_high
) {
    if (unwrapped < MAP_SOURCE_HUE_1) {
        source_low = MAP_SOURCE_HUE_0;
        source_high = MAP_SOURCE_HUE_1;
        target_low = MAP_TARGET_HUE_0;
        target_high = MAP_TARGET_HUE_1;
    } else if (unwrapped < MAP_SOURCE_HUE_2) {
        source_low = MAP_SOURCE_HUE_1;
        source_high = MAP_SOURCE_HUE_2;
        target_low = MAP_TARGET_HUE_1;
        target_high = MAP_TARGET_HUE_2;
    } else if (unwrapped < MAP_SOURCE_HUE_3) {
        source_low = MAP_SOURCE_HUE_2;
        source_high = MAP_SOURCE_HUE_3;
        target_low = MAP_TARGET_HUE_2;
        target_high = MAP_TARGET_HUE_3;
    } else if (unwrapped < MAP_SOURCE_HUE_4) {
        source_low = MAP_SOURCE_HUE_3;
        source_high = MAP_SOURCE_HUE_4;
        target_low = MAP_TARGET_HUE_3;
        target_high = MAP_TARGET_HUE_4;
    } else if (unwrapped < MAP_SOURCE_HUE_5) {
        source_low = MAP_SOURCE_HUE_4;
        source_high = MAP_SOURCE_HUE_5;
        target_low = MAP_TARGET_HUE_4;
        target_high = MAP_TARGET_HUE_5;
    } else {
        source_low = MAP_SOURCE_HUE_5;
        source_high = MAP_SOURCE_HUE_0 + 360.0;
        target_low = MAP_TARGET_HUE_5;
        target_high = MAP_TARGET_HUE_0 + 360.0;
    }
}

// A_s: source hue -> aligned hue [report A4.1.2; piecewise-linear
// interpolation is a design choice, README 4].
float map_align_forward(float hue_degrees, float strength) {
    float wrapped = mod(hue_degrees, 360.0);
    float unwrapped = wrapped < MAP_SOURCE_HUE_0 ? wrapped + 360.0 : wrapped;

    float source_low;
    float source_high;
    float target_low;
    float target_high;
    map_source_segment(
        unwrapped,
        source_low,
        source_high,
        target_low,
        target_high
    );

    float slope = (target_high - target_low) / (source_high - source_low);
    float base = target_low + slope * (unwrapped - source_low);
    return mod(unwrapped + strength * (base - unwrapped), 360.0);
}

float map_triangle_chroma(float lightness, float anchor_chroma,
                          float anchor_lightness) {
    float low = anchor_chroma * lightness / max(anchor_lightness, 1e-12);
    float high = anchor_chroma * (100.0 - lightness) /
                 max(100.0 - anchor_lightness, 1e-12);
    float value = lightness <= anchor_lightness ? low : high;

    if (lightness < 0.0 || lightness > 100.0) {
        return 0.0;
    }

    return value;
}

float map_hue_coordinate(float hue_degrees) {
    float position = mod(hue_degrees, 360.0) / 360.0 * 512.0;
    return (position + 0.5) / 513.0;
}

float map_row_coordinate(float lightness, float cusp_lightness) {
    float row;

    if (lightness <= cusp_lightness) {
        row = 256.0 * lightness / max(cusp_lightness, 1e-6);
    } else {
        row = 256.0 + 256.0 * (lightness - cusp_lightness) /
              max(100.0 - cusp_lightness, 1e-6);
    }

    return (clamp(row, 0.0, 512.0) + 0.5) / 513.0;
}

float map_unpack_scalar(vec2 packed) {
    return packed.x + packed.y / 1024.0;
}

vec4 map_cusp_target(float coordinate) {
    return A4_CUSP_T_mul *
           textureLod(A4_CUSP_T_raw, vec2(coordinate, 0.5), 0.0);
}

vec4 map_cusp_source(float coordinate) {
    return A4_CUSP_S_mul *
           textureLod(A4_CUSP_S_raw, vec2(coordinate, 0.5), 0.0);
}

vec4 map_line(float coordinate) {
    return A4_LINE_mul *
           textureLod(A4_LINE_raw, vec2(coordinate, 0.5), 0.0);
}

vec4 map_anchor(float coordinate) {
    return A4_ANCHOR_mul *
           textureLod(A4_ANCHOR_raw, vec2(coordinate, 0.5), 0.0);
}

vec4 map_bound_sample(float lightness, float cusp_lightness,
                      float aligned_hue) {
    vec2 coordinate = vec2(
        map_hue_coordinate(aligned_hue),
        map_row_coordinate(lightness, cusp_lightness)
    );
    return A4_BOUND_mul * textureLod(A4_BOUND_raw, coordinate, 0.0);
}

float map_target_chroma(float lightness, float cusp_lightness,
                        float aligned_hue) {
    vec4 stored = map_bound_sample(lightness, cusp_lightness, aligned_hue);
    return stored.r + stored.g / 1024.0;
}

float map_source_chroma(float lightness, float cusp_lightness,
                        float aligned_hue) {
    vec4 stored = map_bound_sample(lightness, cusp_lightness, aligned_hue);
    return stored.b + stored.a / 1024.0;
}

float trace_value(int mode, float position, Trace trace) {
    bool second = mode >= MODE_CORE_2;
    float lightness;
    float left;
    int kind;

    if (second) {
        lightness = trace.intercept +
                    position * (trace.lightness - trace.intercept);
        left = position * trace.chroma;
        kind = mode - MODE_CORE_2;
    } else {
        lightness = trace.intercept + trace.slope * position + trace.offset;
        left = position;
        kind = mode;
    }

    float target = map_target_chroma(
        lightness,
        trace.target_cusp_lightness,
        trace.aligned_hue
    );

    if (kind == 0) {
        return left - min(
            map_triangle_chroma(
                lightness,
                trace.anchor_chroma,
                trace.anchor_lightness
            ),
            target
        );
    }

    if (kind == 1) {
        return left - target;
    }

    float source = map_source_chroma(
        lightness,
        trace.source_cusp_lightness,
        trace.aligned_hue
    );
    return left - max(target, source);
}

// First position in [low, high] where the trace function changes from inside
// to outside. A scan finds the first sign change (the reference does not
// assume monotonicity); only that bracket is bisected [design choice, 7.3].
float first_cross(int mode, float low, float high, Trace trace) {
    float previous_position = low;
    float previous_value = trace_value(mode, low, trace);
    bool found = previous_value > MAP_ROOT_TOLERANCE;
    float root_low = low;
    float root_high = found ? low : high;

    for (int i = 1; i <= MAP_SCAN_STEPS; i++) {
        float position = low + (high - low) * float(i) /
                         float(MAP_SCAN_STEPS);
        float value = trace_value(mode, position, trace);

        if (!found && previous_value <= MAP_ROOT_TOLERANCE &&
            value > MAP_ROOT_TOLERANCE) {
            root_low = previous_position;
            root_high = position;
            found = true;
        }

        previous_position = position;
        previous_value = value;
    }

    if (!found) {
        root_low = high;
        root_high = high;
    }

    for (int i = 0; i < MAP_ROOT_ITERATIONS; i++) {
        float middle = 0.5 * (root_low + root_high);

        if (trace_value(mode, middle, trace) <= MAP_ROOT_TOLERANCE) {
            root_low = middle;
        } else {
            root_high = middle;
        }
    }

    return found ? 0.5 * (root_low + root_high) : high;
}

vec3 sanitize_rgb(vec3 rgb) {
    vec3 safe = rgb;

    if (any(isnan(safe)) || any(isinf(safe))) {
        safe = vec3(
            (isnan(rgb.x) || isinf(rgb.x)) ? 0.0 : rgb.x,
            (isnan(rgb.y) || isinf(rgb.y)) ? 0.0 : rgb.y,
            (isnan(rgb.z) || isinf(rgb.z)) ? 0.0 : rgb.z
        );
    }

    safe = max(safe, vec3(0.0));
    float peak = max(max(safe.r, safe.g), safe.b);

    if (peak > 1.0) {
        safe = safe / peak;
    }

    return safe;
}

vec3 map_color(vec3 rgb2020) {
    vec3 lab = map_xyz_to_lab(rgb2020 * MAP_BT2020_TO_XYZ);
    float lightness = clamp(lab.x, 0.0, 100.0);
    float chroma = length(lab.yz);

    if (chroma <= MAP_CHROMA_EPSILON) {
        return map_lab_to_xyz(vec3(lightness, 0.0, 0.0)) * MAP_XYZ_TO_BT709;
    }

    float hue = mod(degrees(atan(lab.z, lab.y)), 360.0);
    float aligned_hue = map_align_forward(hue, align_strength);
    float coordinate = map_hue_coordinate(aligned_hue);
    vec4 target_cusp_data = map_cusp_target(coordinate);
    vec4 source_cusp_data = map_cusp_source(coordinate);
    vec4 line_data = map_line(coordinate);
    vec4 anchor_data = map_anchor(coordinate);
    float target_cusp_chroma = map_unpack_scalar(target_cusp_data.xy);
    float target_cusp_lightness = map_unpack_scalar(target_cusp_data.zw);
    float source_cusp_chroma = map_unpack_scalar(source_cusp_data.xy);
    float source_cusp_lightness = map_unpack_scalar(source_cusp_data.zw);
    float slope = map_unpack_scalar(line_data.xy);
    float intercept = map_unpack_scalar(line_data.zw);
    float anchor_chroma = map_unpack_scalar(anchor_data.xy);
    float anchor_lightness = map_unpack_scalar(anchor_data.zw);
    float target = map_target_chroma(
        lightness,
        target_cusp_lightness,
        aligned_hue
    );
    float core = min(
        map_triangle_chroma(lightness, anchor_chroma, anchor_lightness),
        target
    );

    if (chroma <= core + 1e-9) {
        float core_angle = radians(aligned_hue);
        return map_lab_to_xyz(
            vec3(
                lightness,
                chroma * cos(core_angle),
                chroma * sin(core_angle)
            )
        ) * MAP_XYZ_TO_BT709;
    }

    Trace trace;
    trace.lightness = lightness;
    trace.chroma = chroma;
    trace.slope = slope;
    trace.intercept = intercept;
    trace.anchor_chroma = anchor_chroma;
    trace.anchor_lightness = anchor_lightness;
    trace.target_cusp_lightness = target_cusp_lightness;
    trace.source_cusp_lightness = source_cusp_lightness;
    trace.aligned_hue = aligned_hue;
    trace.offset = lightness - intercept - slope * chroma;

    float mapped_lightness = lightness;
    float mapped_chroma = chroma;

    if ((lightness - (intercept + slope * chroma)) * slope > 0.0) {
        float core_cross = first_cross(MODE_CORE, 0.0, chroma, trace);
        float target_cross = first_cross(
            MODE_TARGET,
            core_cross,
            target_cusp_chroma + 2.0,
            trace
        );
        float outer_cross = first_cross(
            MODE_OUTER,
            target_cross,
            source_cusp_chroma + 2.0,
            trace
        );
        outer_cross = max(outer_cross, chroma);
        float shell = outer_cross - core_cross;
        float scale = 0.0;

        if (shell > MAP_SHELL_EPSILON) {
            scale = (target_cross - core_cross) / shell;
        }

        mapped_chroma = core_cross + (chroma - core_cross) * scale;
        mapped_lightness = intercept + slope * mapped_chroma + trace.offset;
    } else if ((lightness - intercept) * slope > 0.0) {
        float core_cross = first_cross(MODE_CORE_2, 0.0, 1.0, trace);
        float target_limit = min(
            max(1.5, 1.5 * target_cusp_chroma / max(chroma, 1e-9)),
            64.0
        );
        float target_cross = first_cross(
            MODE_TARGET_2,
            core_cross,
            target_limit,
            trace
        );
        float source_limit = min(
            max(target_limit, 1.5 * source_cusp_chroma / max(chroma, 1e-9)),
            128.0
        );
        float outer_cross = first_cross(
            MODE_OUTER_2,
            target_cross,
            source_limit,
            trace
        );
        outer_cross = max(outer_cross, 1.0);
        float shell = outer_cross - core_cross;
        float scale = 0.0;

        if (shell > MAP_SHELL_EPSILON) {
            scale = (target_cross - core_cross) / shell;
        }

        float mapped = core_cross + (1.0 - core_cross) * scale;
        mapped_chroma = mapped * chroma;
        mapped_lightness = intercept + mapped * (lightness - intercept);
    } else {
        float source = map_source_chroma(
            lightness,
            source_cusp_lightness,
            aligned_hue
        );
        float core_chroma = min(
            map_triangle_chroma(lightness, anchor_chroma, anchor_lightness),
            target
        );
        float outer = max(target, source);
        float shell = outer - core_chroma;
        float scale = 0.0;

        if (shell > MAP_SHELL_EPSILON) {
            scale = (target - core_chroma) / shell;
        }

        mapped_chroma = core_chroma + (chroma - core_chroma) * scale;
        mapped_lightness = lightness;
    }

    float mapped_angle = radians(aligned_hue);
    return map_lab_to_xyz(
        vec3(
            mapped_lightness,
            mapped_chroma * cos(mapped_angle),
            mapped_chroma * sin(mapped_angle)
        )
    ) * MAP_XYZ_TO_BT709;
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb = sanitize_rgb(color.rgb);
    vec3 mapped = map_color(rgb);
    return vec4(clamp(mapped, 0.0, 1.0), color.a);
}
