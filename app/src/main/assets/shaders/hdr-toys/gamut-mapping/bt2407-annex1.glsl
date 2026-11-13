// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 1 modular hue / lightness / chroma mapping, implemented as a
// libplacebo user shader. Input is linear BT.2020, output is linear BT.709,
// D65, unit white. Alpha is passed through unchanged.
//
// The three stages are:
//   1. hue mapping      [report] A1.1, rotation = delta_h * cos(gamma / 2);
//                       [design] periodic cosine blend between the three
//                       primary anchors (C1, zero derivative at anchors).
//   2. lightness map    [report] A1.2 cusp-oriented direction;
//                       [patent] US10284750 B2 Eq. 1/2/3A/3B (f1) and f2.2
//                       with q = -0.5; [design] the simplest reading of f2.2,
//                       L2 = L1 + q * (L1 - L).
//   3. chroma map       [report] A1.3 40 % identity / 60 % middle / hard
//                       clip; [patent] US20100328341 A1 [0149].
//
// The source cusp uses the plane through the colour, the BT.2020 white and
// black in BT.2020 RGB [report] A1.2, [patent] US20160379595 A1. That plane
// generally meets the source cusp line twice; the patent labels the white-
// closer and black-closer points Ic1/Ic2 and prefers one by the source
// colour's distance to white/black. This shader instead takes the
// intersection on the edge that shares the colour's max/min channel ordering
// [design] (closed form, no extra distance test); README E4 documents the
// choice and its measured impact. The target cusp at the source cusp hue
// comes from a 4097-node BT.709 cusp table. The two boundary distances come
// from the same 513 x 256 scan-and-bisect table as bt2407-annex2.glsl; where
// that table overestimates and the result leaves BT.709, the BT.709 boundary
// is recomputed exactly in the pixel pass (bounded 256+12 steps).
//
// Every equation is tagged [report], [patent], [standard] or [design] in the
// comments. The normative description of all choices is README.md in
// bt2407-annex1/. Conventions follow bt2407-annex2.glsl: row-vector matrix
// products with mat3 constructed from the rows, unit white 1, and clamped
// output in [0, 1].

//!PARAM PRESERVE_IN_GAMUT
//!DESC 1 = colours inside BT.709 bypass the three mapping steps
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
1

//!PARAM ENABLE_HUE_ROTATION
//!DESC 1 = apply the Annex 1.1 hue rotation
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
1

//!PARAM ENABLE_SECOND_LIGHTNESS
//!DESC 1 = apply the optional opposite-direction f2 lightness stage
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
1

//!PARAM IDENTITY_FRACTION
//!DESC slope-one share of the source chroma in the three-segment map
//!TYPE float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.4

//!PARAM SECOND_LIGHTNESS_Q
//!DESC f2 lightness pull-back factor q in ]-1, 0[
//!TYPE float
//!MINIMUM -1.0
//!MAXIMUM 0.0
-0.5

//!PARAM CLIP_INPUT
//!DESC 1 = clamp the input RGB to [0, 1] before mapping
//!TYPE int
//!MINIMUM 0
//!MAXIMUM 1
1

//!HOOK OUTPUT
//!SAVE BT2407_A1_BOUNDARY_RAW
//!COMPONENTS 2
//!WIDTH 513
//!HEIGHT 256
//!COMPUTE 16 8
//!DESC gamut mapping (BT.2407 annex 1, boundary generation)

const uint BOUNDARY_HUE_SIZE = 512u;
const uint BOUNDARY_LIGHTNESS_SIZE = 256u;
const int BOUNDARY_SCAN_STEPS = 256;
const int BOUNDARY_REFINE_ITERATIONS = 12;
const float MAX_CHROMA = 256.0;
const float TAU = 6.28318530717958647692;
const float LAB_DELTA = 6.0 / 29.0;
const float LAB_LINEAR_SCALE = 108.0 / 841.0;
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

bool lab_in_unit_gamut(vec3 lab, mat3 xyz_to_rgb) {
    vec3 rgb = lab_to_xyz(lab) * xyz_to_rgb;
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

// [design] The first chroma where the constant-hue line leaves the RGB cube.
// A plain bisection would need the inside predicate to be monotonic, which
// fails where the cube cuts a notch into the slice, so the bracket comes
// from a scan and only the final interval is bisected. The L edge cases
// return zero exactly as the float64 reference does.
float find_boundary_chroma(
    float lightness,
    vec2 hue_direction,
    mat3 xyz_to_rgb
) {
    if (lightness <= 0.0 || lightness >= 100.0) {
        return 0.0;
    }

    float lower = 0.0;

    for (int i = 1; i <= BOUNDARY_SCAN_STEPS; i++) {
        float candidate = MAX_CHROMA * float(i) /
                          float(BOUNDARY_SCAN_STEPS);
        vec3 lab = vec3(lightness, candidate * hue_direction);

        if (!lab_in_unit_gamut(lab, xyz_to_rgb)) {
            float upper = candidate;

            for (int j = 0; j < BOUNDARY_REFINE_ITERATIONS; j++) {
                float middle = 0.5 * (lower + upper);
                lab = vec3(lightness, middle * hue_direction);

                if (lab_in_unit_gamut(lab, xyz_to_rgb)) {
                    lower = middle;
                } else {
                    upper = middle;
                }
            }

            return lower;
        }

        lower = candidate;
    }

    return MAX_CHROMA;
}

void hook() {
    ivec2 position = ivec2(gl_GlobalInvocationID.xy);

    // The last column repeats hue zero so the fragment can interpolate
    // across the hue seam with hardware filtering.
    if (position.x > int(BOUNDARY_HUE_SIZE) ||
        position.y >= int(BOUNDARY_LIGHTNESS_SIZE)) {
        return;
    }

    float hue = TAU * float(position.x) / float(BOUNDARY_HUE_SIZE);
    float lightness = 100.0 * float(position.y) /
                      float(BOUNDARY_LIGHTNESS_SIZE - 1u);
    vec2 hue_direction = vec2(cos(hue), sin(hue));
    float bt709 = find_boundary_chroma(
        lightness,
        hue_direction,
        XYZ_TO_BT709
    );
    float bt2020 = find_boundary_chroma(
        lightness,
        hue_direction,
        XYZ_TO_BT2020
    );

    imageStore(
        out_image,
        position,
        vec4(bt709, bt2020, 0.0, 1.0)
    );
}

//!HOOK OUTPUT
//!SAVE BT2407_A1_CUSP
//!COMPONENTS 2
//!WIDTH 4097
//!HEIGHT 1
//!COMPUTE 256 1
//!DESC gamut mapping (BT.2407 annex 1, cusp generation)

const uint CUSP_HUE_INTERVALS = 4096u;
const int CUSP_REFINE_ITERATIONS = 16;
const float LAB_DELTA = 6.0 / 29.0;
const float LAB_DELTA_CUBED = 216.0 / 24389.0;
const float LAB_LINEAR_SCALE = 108.0 / 841.0;
const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

const mat3 BT709_TO_XYZ = mat3(
    0.41239079926595934, 0.35758433938387801, 0.18048078840183426,
    0.21263900587151030, 0.71516867876775614, 0.07219231536073370,
    0.01933081871559180, 0.11919477979462594, 0.95053215224966048
);

// [standard] Lab hues of the six cube corners, unwrapped so that the cusp
// curve is strictly increasing from the red primary hue to red + 360.
const float CUSP_HUE_R = 39.99986515439814;
const float CUSP_HUE_Y = 102.85405969397786;
const float CUSP_HUE_G = 136.01306868501493;
const float CUSP_HUE_C = 196.37652647244022;
const float CUSP_HUE_B = 306.28880325729324;
const float CUSP_HUE_M = 328.23408825357720;
const float CUSP_HUE_R2 = 399.99986515439815;

float lab_forward(float value) {
    if (value > LAB_DELTA_CUBED) {
        return pow(value, 1.0 / 3.0);
    }

    return value / LAB_LINEAR_SCALE + 4.0 / 29.0;
}

vec3 xyz_to_lab(vec3 xyz) {
    vec3 f = xyz / D65_WHITE;
    f = vec3(
        lab_forward(f.x),
        lab_forward(f.y),
        lab_forward(f.z)
    );
    return vec3(
        116.0 * f.y - 16.0,
        500.0 * (f.x - f.y),
        200.0 * (f.y - f.z)
    );
}

vec3 edge_start(int edge) {
    if (edge == 0) {
        return vec3(1.0, 0.0, 0.0);
    }
    if (edge == 1) {
        return vec3(1.0, 1.0, 0.0);
    }
    if (edge == 2) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (edge == 3) {
        return vec3(0.0, 1.0, 1.0);
    }
    if (edge == 4) {
        return vec3(0.0, 0.0, 1.0);
    }

    return vec3(1.0, 0.0, 1.0);
}

vec3 edge_end(int edge) {
    if (edge == 0) {
        return vec3(1.0, 1.0, 0.0);
    }
    if (edge == 1) {
        return vec3(0.0, 1.0, 0.0);
    }
    if (edge == 2) {
        return vec3(0.0, 1.0, 1.0);
    }
    if (edge == 3) {
        return vec3(0.0, 0.0, 1.0);
    }
    if (edge == 4) {
        return vec3(1.0, 0.0, 1.0);
    }

    return vec3(1.0, 0.0, 0.0);
}

vec3 edge_lab(int edge, float t) {
    vec3 rgb = mix(edge_start(edge), edge_end(edge), t);
    return xyz_to_lab(rgb * BT709_TO_XYZ);
}

float edge_hue(int edge, float t) {
    vec3 lab = edge_lab(edge, t);
    return degrees(atan(lab.z, lab.y));
}

float unwrap_hue(float hue, float reference) {
    float result = hue;

    if (result < reference - 180.0) {
        result += 360.0;
    }

    if (result > reference + 180.0) {
        result -= 360.0;
    }

    return result;
}

void hook() {
    int index = int(gl_GlobalInvocationID.x);

    if (index >= int(CUSP_HUE_INTERVALS) + 1) {
        return;
    }

    // Last node repeats hue zero; atan2 makes that repeat automatic because
    // hue 360 and hue 0 select the same point of the magenta-to-red edge.
    float hue_degrees = 360.0 * float(index) /
                        float(CUSP_HUE_INTERVALS);
    float target_hue = hue_degrees;

    if (target_hue < CUSP_HUE_R) {
        target_hue += 360.0;
    }

    int edge = 0;
    float edge_hue_start = CUSP_HUE_R;

    if (target_hue >= CUSP_HUE_Y) {
        edge = 1;
        edge_hue_start = CUSP_HUE_Y;
    }

    if (target_hue >= CUSP_HUE_G) {
        edge = 2;
        edge_hue_start = CUSP_HUE_G;
    }

    if (target_hue >= CUSP_HUE_C) {
        edge = 3;
        edge_hue_start = CUSP_HUE_C;
    }

    if (target_hue >= CUSP_HUE_B) {
        edge = 4;
        edge_hue_start = CUSP_HUE_B;
    }

    if (target_hue >= CUSP_HUE_M) {
        edge = 5;
        edge_hue_start = CUSP_HUE_M;
    }

    float lower = 0.0;
    float upper = 1.0;

    for (int i = 0; i < CUSP_REFINE_ITERATIONS; i++) {
        float middle = 0.5 * (lower + upper);
        float hue = unwrap_hue(edge_hue(edge, middle), edge_hue_start);

        if (hue < target_hue) {
            lower = middle;
        } else {
            upper = middle;
        }
    }

    vec3 lab = edge_lab(edge, 0.5 * (lower + upper));

    imageStore(
        out_image,
        ivec2(index, 0),
        vec4(lab.x, length(lab.yz), 0.0, 1.0)
    );
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND BT2407_A1_CUSP
//!BIND BT2407_A1_BOUNDARY_RAW
//!DESC gamut mapping (BT.2407 annex 1, mapping)

const uint BOUNDARY_HUE_SIZE = 512u;
const uint BOUNDARY_LIGHTNESS_SIZE = 256u;
const uint CUSP_HUE_INTERVALS = 4096u;
const int BOUNDARY_SCAN_STEPS = 256;
const int BOUNDARY_REFINE_ITERATIONS = 12;
const float MAX_CHROMA = 256.0;
const float TAU = 6.28318530717958647692;
const float INV_TAU = 1.0 / TAU;
const float PI = 3.14159265358979323846;
const float LAB_DELTA = 6.0 / 29.0;
const float LAB_DELTA_CUBED = 216.0 / 24389.0;
const float LAB_LINEAR_SCALE = 108.0 / 841.0;
const vec3 D65_WHITE = vec3(
    0.9504559270516716,
    1.0,
    1.0890577507598784
);

// [design] Float32 guards. The float64 reference uses 1e-12 for both the
// input chroma and the source-cusp denominator; float32 Lab noise on the
// neutral axis is of order 1e-5, so both use the same larger threshold.
const float CHROMA_EPSILON = 1e-5;
const float IN_GAMUT_EPSILON = 1e-5;
const float RETRY_EPSILON = 1e-5;
const float F2_TRIGGER_EPSILON = 1e-6;
const float CHROMA_MAP_EPSILON = 1e-12;
const float LIGHTNESS_EPSILON = 1e-12;

// [standard] Hue anchors and [report] A1.1 rotations of the three primaries.
const float ANCHOR_RED = 40.584202651445864;
const float ANCHOR_GREEN = 145.91119552558246;
const float ANCHOR_BLUE = 305.60188765074435;
const float ROTATION_RED = -0.015451171828695417;
const float ROTATION_GREEN = -3.0298690397355936;
const float ROTATION_BLUE = 0.08160492916091969;

const mat3 BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0000000000000000, 0.0280726930490874, 1.0609850577107910
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

float lab_forward(float value) {
    if (value > LAB_DELTA_CUBED) {
        return pow(value, 1.0 / 3.0);
    }

    return value / LAB_LINEAR_SCALE + 4.0 / 29.0;
}

vec3 xyz_to_lab(vec3 xyz) {
    vec3 f = xyz / D65_WHITE;
    f = vec3(
        lab_forward(f.x),
        lab_forward(f.y),
        lab_forward(f.z)
    );
    return vec3(
        116.0 * f.y - 16.0,
        500.0 * (f.x - f.y),
        200.0 * (f.y - f.z)
    );
}

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

bool lab_in_unit_gamut(vec3 lab, mat3 xyz_to_rgb) {
    vec3 rgb = lab_to_xyz(lab) * xyz_to_rgb;
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float find_boundary_chroma(
    float lightness,
    vec2 hue_direction,
    mat3 xyz_to_rgb
) {
    if (lightness <= 0.0 || lightness >= 100.0) {
        return 0.0;
    }

    float lower = 0.0;

    for (int i = 1; i <= BOUNDARY_SCAN_STEPS; i++) {
        float candidate = MAX_CHROMA * float(i) /
                          float(BOUNDARY_SCAN_STEPS);
        vec3 lab = vec3(lightness, candidate * hue_direction);

        if (!lab_in_unit_gamut(lab, xyz_to_rgb)) {
            float upper = candidate;

            for (int j = 0; j < BOUNDARY_REFINE_ITERATIONS; j++) {
                float middle = 0.5 * (lower + upper);
                lab = vec3(lightness, middle * hue_direction);

                if (lab_in_unit_gamut(lab, xyz_to_rgb)) {
                    lower = middle;
                } else {
                    upper = middle;
                }
            }

            return lower;
        }

        lower = candidate;
    }

    return MAX_CHROMA;
}

// [report] A1.1 with the [design] periodic cosine blend of the reference.
float hue_rotation(float hue_degrees) {
    float hue = mod(hue_degrees, 360.0);

    if (hue < ANCHOR_RED) {
        hue += 360.0;
    }

    float weight;
    float rotation;

    if (hue < ANCHOR_GREEN) {
        float u = (hue - ANCHOR_RED) / (ANCHOR_GREEN - ANCHOR_RED);
        weight = 0.5 - 0.5 * cos(PI * u);
        rotation = mix(ROTATION_RED, ROTATION_GREEN, weight);
    } else if (hue < ANCHOR_BLUE) {
        float u = (hue - ANCHOR_GREEN) / (ANCHOR_BLUE - ANCHOR_GREEN);
        weight = 0.5 - 0.5 * cos(PI * u);
        rotation = mix(ROTATION_GREEN, ROTATION_BLUE, weight);
    } else {
        float u = (hue - ANCHOR_BLUE) /
                  (ANCHOR_RED + 360.0 - ANCHOR_BLUE);
        weight = 0.5 - 0.5 * cos(PI * u);
        rotation = mix(ROTATION_BLUE, ROTATION_RED, weight);
    }

    return rotation;
}

// [report] A1.3 three-segment function with the [patent] [0149] parameters
// and the [design] inflection clamp from the reference.
float chroma_map(
    float distance,
    float source_boundary,
    float target_boundary
) {
    if (source_boundary <= CHROMA_MAP_EPSILON) {
        return 0.0;
    }

    if (distance <= 0.0) {
        return 0.0;
    }

    if (target_boundary <= 0.0) {
        return 0.0;
    }

    float inflection = min(
        IDENTITY_FRACTION * source_boundary,
        target_boundary
    );

    if (distance <= inflection) {
        return distance;
    }

    if (distance <= source_boundary) {
        float span = source_boundary - inflection;

        if (span <= LIGHTNESS_EPSILON) {
            return target_boundary;
        }

        return inflection + (target_boundary - inflection) *
               (distance - inflection) / span;
    }

    return target_boundary;
}

vec2 sample_boundary(float hue_radians, float lightness) {
    float hue_position = fract(hue_radians * INV_TAU + 1.0) *
                         float(BOUNDARY_HUE_SIZE);
    float hue_coordinate = (hue_position + 0.5) /
                           float(BOUNDARY_HUE_SIZE + 1u);
    float lightness_position = clamp(lightness, 0.0, 100.0) * 0.01 *
                               float(BOUNDARY_LIGHTNESS_SIZE - 1u);
    float lightness_coordinate = (lightness_position + 0.5) /
                                 float(BOUNDARY_LIGHTNESS_SIZE);
    return BT2407_A1_BOUNDARY_RAW_mul * textureLod(
        BT2407_A1_BOUNDARY_RAW_raw,
        vec2(hue_coordinate, lightness_coordinate),
        0.0
    ).rg;
}

vec2 sample_cusp(float hue_radians) {
    float hue_position = fract(hue_radians * INV_TAU + 1.0) *
                         float(CUSP_HUE_INTERVALS);
    float hue_coordinate = (hue_position + 0.5) /
                           float(CUSP_HUE_INTERVALS + 1u);
    return BT2407_A1_CUSP_mul * textureLod(
        BT2407_A1_CUSP_raw,
        vec2(hue_coordinate, 0.5),
        0.0
    ).rg;
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb2020 = color.rgb;

    if (CLIP_INPUT != 0) {
        rgb2020 = clamp(rgb2020, 0.0, 1.0);
    }

    vec3 xyz = rgb2020 * BT2020_TO_XYZ;
    vec3 rgb709_direct = xyz * XYZ_TO_BT709;

    if (PRESERVE_IN_GAMUT != 0 &&
        all(greaterThanEqual(rgb709_direct, vec3(-IN_GAMUT_EPSILON))) &&
        all(lessThanEqual(rgb709_direct, vec3(1.0 + IN_GAMUT_EPSILON)))) {
        color.rgb = clamp(rgb709_direct, 0.0, 1.0);
        return color;
    }

    vec3 lab = xyz_to_lab(xyz);
    float lightness = lab.x;
    float chroma = length(lab.yz);
    float hue = 0.0;

    if (chroma > CHROMA_EPSILON) {
        hue = atan(lab.z, lab.y);
    }

    float hue_degrees = degrees(hue);

    if (hue_degrees < 0.0) {
        hue_degrees += 360.0;
    }

    float rotation = 0.0;

    if (ENABLE_HUE_ROTATION != 0 && chroma > CHROMA_EPSILON) {
        rotation = hue_rotation(hue_degrees);
    }

    float mapped_hue_degrees = mod(hue_degrees + rotation, 360.0);
    float mapped_hue = radians(mapped_hue_degrees);

    // [report] A1.2 + [patent] US20160379595 define the plane; it meets the
    // cusp line twice (patent Ic1/Ic2). [design] This implementation takes
    // the same-ordering intersection on the top edge (maximum channel = 1,
    // minimum channel = 0); README E4 documents the choice and its impact.
    float source_cusp_lightness = lightness;
    float source_cusp_chroma = chroma;
    float source_cusp_hue = mapped_hue;
    bool has_cusp = false;

    if (chroma > CHROMA_EPSILON) {
        vec3 rotated_lab = vec3(
            lightness,
            chroma * cos(mapped_hue),
            chroma * sin(mapped_hue)
        );
        vec3 rotated_rgb = lab_to_xyz(rotated_lab) * XYZ_TO_BT2020;
        int maximum = 0;
        float maximum_value = rotated_rgb.x;

        if (rotated_rgb.y > maximum_value) {
            maximum = 1;
            maximum_value = rotated_rgb.y;
        }

        if (rotated_rgb.z > maximum_value) {
            maximum = 2;
            maximum_value = rotated_rgb.z;
        }

        int minimum = 0;
        float minimum_value = rotated_rgb.x;

        if (rotated_rgb.y < minimum_value) {
            minimum = 1;
            minimum_value = rotated_rgb.y;
        }

        if (rotated_rgb.z < minimum_value) {
            minimum = 2;
            minimum_value = rotated_rgb.z;
        }

        float denominator = maximum_value - minimum_value;

        if (denominator > CHROMA_EPSILON) {
            int middle = 3 - maximum - minimum;
            float cusp_t = clamp(
                (rotated_rgb[middle] - minimum_value) / denominator,
                0.0,
                1.0
            );
            // [portability] Write the components explicitly instead of
            // through a dynamic vector l-value (cusp_rgb[maximum] = 1.0).
            // That is legal GLSL, but SPIRV-Cross lowers it to an HLSL
            // array l-value and D3DCompile rejects it (X3500), which breaks
            // vo=gpu-next on --gpu-api=d3d11. The maximum and middle
            // indices are distinct and the minimum component is zero.
            vec3 cusp_rgb = vec3(
                maximum == 0 ? 1.0 : 0.0,
                maximum == 1 ? 1.0 : 0.0,
                maximum == 2 ? 1.0 : 0.0
            ) + cusp_t * vec3(
                middle == 0 ? 1.0 : 0.0,
                middle == 1 ? 1.0 : 0.0,
                middle == 2 ? 1.0 : 0.0
            );
            vec3 cusp_lab = xyz_to_lab(cusp_rgb * BT2020_TO_XYZ);
            source_cusp_lightness = cusp_lab.x;
            source_cusp_chroma = length(cusp_lab.yz);
            source_cusp_hue = atan(cusp_lab.z, cusp_lab.y);
            has_cusp = true;
        }
    }

    // [patent] US10284750 f1 (Eq. 1/2/3A/3B) and the [design] f2.2 reading.
    float lightness_f1 = lightness;
    float lightness_f2 = lightness;

    if (has_cusp) {
        float target_cusp_lightness = sample_cusp(source_cusp_hue).x;
        float closeness;

        if (lightness <= source_cusp_lightness) {
            if (source_cusp_lightness > LIGHTNESS_EPSILON) {
                closeness = lightness / source_cusp_lightness;
            } else {
                closeness = 1.0;
            }
        } else {
            float denominator = 100.0 - source_cusp_lightness;

            if (denominator > LIGHTNESS_EPSILON) {
                closeness = (100.0 - lightness) / denominator;
            } else {
                closeness = 0.0;
            }
        }

        closeness = clamp(closeness, 0.0, 1.0);
        float reach = 0.0;

        if (source_cusp_chroma > LIGHTNESS_EPSILON) {
            reach = min(1.0, chroma / source_cusp_chroma);
        }

        float ratio = 1.0;

        if (source_cusp_lightness > LIGHTNESS_EPSILON) {
            ratio = target_cusp_lightness / source_cusp_lightness;
        }

        float scale = 1.0 + closeness * reach * (ratio - 1.0);
        lightness_f1 = clamp(scale * lightness, 0.0, 100.0);
        lightness_f2 = lightness_f1;

        if (ENABLE_SECOND_LIGHTNESS != 0) {
            float target_at_f1 = sample_boundary(
                mapped_hue,
                lightness_f1
            ).x;

            if (chroma > target_at_f1 + F2_TRIGGER_EPSILON) {
                lightness_f2 = clamp(
                    lightness_f1 + SECOND_LIGHTNESS_Q *
                    (lightness_f1 - lightness),
                    0.0,
                    100.0
                );
            }
        }
    }

    vec2 boundaries = sample_boundary(mapped_hue, lightness_f2);
    float mapped_chroma = chroma_map(
        chroma,
        boundaries.y,
        boundaries.x
    );
    vec3 output_lab = vec3(
        lightness_f2,
        mapped_chroma * cos(mapped_hue),
        mapped_chroma * sin(mapped_hue)
    );
    vec3 rgb_out = lab_to_xyz(output_lab) * XYZ_TO_BT709;

    // [design] If the bilinear target-boundary table overestimates and the
    // result leaves BT.709, recompute the boundary with the same scan and
    // bisection used to build the table and repeat the chroma map.
    if (any(lessThan(rgb_out, vec3(-RETRY_EPSILON))) ||
        any(greaterThan(rgb_out, vec3(1.0 + RETRY_EPSILON)))) {
        vec2 hue_direction = vec2(cos(mapped_hue), sin(mapped_hue));
        float exact_target = find_boundary_chroma(
            lightness_f2,
            hue_direction,
            XYZ_TO_BT709
        );
        mapped_chroma = chroma_map(
            chroma,
            boundaries.y,
            exact_target
        );
        output_lab = vec3(
            lightness_f2,
            mapped_chroma * cos(mapped_hue),
            mapped_chroma * sin(mapped_hue)
        );
        rgb_out = lab_to_xyz(output_lab) * XYZ_TO_BT709;
    }

    color.rgb = clamp(rgb_out, 0.0, 1.0);
    return color;
}
