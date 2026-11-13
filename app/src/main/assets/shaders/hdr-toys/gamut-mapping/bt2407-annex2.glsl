// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
// https://trev16.hatenablog.com/entry/2020/06/07/094646
// https://github.com/toru-ver4/sample_code/blob/develop/2020/020_explain_BT2407/bt2047_gamut_mapping.py
//
// BT.2407 Annex 2 gamut mapping in CIELAB. Colours outside BT.709 are
// projected onto the BT.709 boundary; colours inside BT.709 are unchanged.
// The lightness mapping moves them along lines towards a focal point on the
// lightness axis when they sit above the line joining the focal points, or
// towards a focal point on the chroma axis when they sit below it. The hue
// mapping of A2.5 lets yellow highlights and cyan leave the radial a*b* path:
// they follow the sloped loci through the BT.2020 and BT.709 yellow (or cyan)
// colours, which keeps chroma in yellow highlights and corrects the greenish
// cast of out-of-gamut cyan.
//
// Both focal points come from the cusps of the BT.709 and BT.2020 gamuts. The
// lightness focal is clamped to a simple flat-ramp-flat profile so the mapping
// paths do not follow the noisy cusp curve from hue to hue; the chroma focal
// is the cusp line's chroma-axis intercept, capped where that intercept runs
// away. The lightness mapping follows Toru Yoshihara's implementation.
//
// Input is linear BT.2020 and output is linear BT.709, matching the other
// operators in this directory.

//!HOOK OUTPUT
//!SAVE TORU_BOUNDARY
//!COMPONENTS 2
//!WIDTH 513
//!HEIGHT 256
//!COMPUTE 16 8
//!DESC gamut mapping (BT.2407 annex 2, boundary generation)

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

// The first chroma where the constant-hue line leaves the RGB cube. A plain
// bisection would need the inside predicate to be monotonic, which fails
// where the cube cuts a notch into the slice, so the bracket comes from a
// scan and only the final interval is bisected.
float find_boundary_chroma(
    float lightness,
    vec2 hue_direction,
    mat3 xyz_to_rgb
) {
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
//!SAVE TORU_FOCAL_RAW
//!COMPONENTS 2
//!WIDTH 1024
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND TORU_BOUNDARY
//!DESC gamut mapping (BT.2407 annex 2, focal generation)

const uint BOUNDARY_HUE_SIZE = 512u;
const uint BOUNDARY_LIGHTNESS_SIZE = 256u;
const uint FOCAL_HUE_SIZE = 1024u;
const float C_FOCAL_MAX = 5000.0;

vec2 fetch_boundary(int hue_index, int lightness_index) {
    return TORU_BOUNDARY_mul * texelFetch(
        TORU_BOUNDARY_raw,
        ivec2(hue_index, lightness_index),
        0
    ).rg;
}

vec2 sample_boundary_column(float hue_position, int lightness_index) {
    int hue_lower = int(floor(hue_position)) & int(BOUNDARY_HUE_SIZE - 1u);
    int hue_upper = (hue_lower + 1) & int(BOUNDARY_HUE_SIZE - 1u);
    float weight = fract(hue_position);
    return mix(
        fetch_boundary(hue_lower, lightness_index),
        fetch_boundary(hue_upper, lightness_index),
        weight
    );
}

// The cusp is the largest chroma in a boundary column. The three samples
// around that maximum give a sub-sample position through their parabola; the
// column is fine enough that the sharp tip of a cusp is resolved.
vec2 refine_cusp(int index, float previous, float value, float next) {
    float denominator = previous - 2.0 * value + next;
    float offset = 0.0;

    if (abs(denominator) > 1e-6) {
        offset = clamp(
            0.5 * (previous - next) / denominator,
            -1.0,
            1.0
        );
    }

    float lightness_step = 100.0 /
                           float(BOUNDARY_LIGHTNESS_SIZE - 1u);
    return vec2(
        (float(index) + offset) * lightness_step,
        value + 0.25 * (next - previous) * offset
    );
}

vec4 find_cusps(float hue_position) {
    float maximum_709 = -1.0;
    float maximum_2020 = -1.0;
    int index_709 = 0;
    int index_2020 = 0;

    for (int i = 0; i < int(BOUNDARY_LIGHTNESS_SIZE); i++) {
        vec2 column = sample_boundary_column(hue_position, i);

        if (column.x > maximum_709) {
            maximum_709 = column.x;
            index_709 = i;
        }

        if (column.y > maximum_2020) {
            maximum_2020 = column.y;
            index_2020 = i;
        }
    }

    int last = int(BOUNDARY_LIGHTNESS_SIZE - 1u);
    vec2 previous = sample_boundary_column(
        hue_position,
        max(index_709 - 1, 0)
    );
    vec2 next = sample_boundary_column(
        hue_position,
        min(index_709 + 1, last)
    );
    vec2 cusp_709 = refine_cusp(
        index_709,
        previous.x,
        maximum_709,
        next.x
    );

    previous = sample_boundary_column(
        hue_position,
        max(index_2020 - 1, 0)
    );
    next = sample_boundary_column(
        hue_position,
        min(index_2020 + 1, last)
    );
    vec2 cusp_2020 = refine_cusp(
        index_2020,
        previous.y,
        maximum_2020,
        next.y
    );

    return vec4(cusp_709, cusp_2020);
}

void hook() {
    int index = int(gl_GlobalInvocationID.x);

    if (index >= int(FOCAL_HUE_SIZE)) {
        return;
    }

    float hue_position = float(index) / float(FOCAL_HUE_SIZE) *
                         float(BOUNDARY_HUE_SIZE);
    vec4 cusps = find_cusps(hue_position);
    vec2 cusp_709 = cusps.xy;
    vec2 cusp_2020 = cusps.zw;
    float chroma_denominator = cusp_2020.y - cusp_709.y;
    float lightness_denominator = cusp_2020.x - cusp_709.x;
    float l_cusp = 50.0;
    float c_focal = C_FOCAL_MAX;

    // The line through the two cusps meets the lightness axis at L_cusp and
    // the chroma axis at C_focal.
    if (abs(chroma_denominator) > 1e-4) {
        l_cusp = cusp_2020.x -
                 (cusp_2020.x - cusp_709.x) / chroma_denominator *
                 cusp_2020.y;
    }

    if (abs(lightness_denominator) > 1e-4) {
        float value = abs(
            cusp_2020.y -
            (cusp_2020.y - cusp_709.y) / lightness_denominator *
            cusp_2020.x
        );

        // A NaN fails the comparison and keeps the cap.
        if (value < C_FOCAL_MAX) {
            c_focal = max(value, 1.0);
        }
    }

    imageStore(
        out_image,
        ivec2(index, 0),
        vec4(l_cusp, c_focal, 0.0, 1.0)
    );
}

//!HOOK OUTPUT
//!SAVE TORU_FOCAL
//!COMPONENTS 2
//!WIDTH 1024
//!HEIGHT 1
//!COMPUTE 64 1
//!BIND TORU_FOCAL_RAW
//!DESC gamut mapping (BT.2407 annex 2, focal profile)

const uint FOCAL_HUE_SIZE = 1024u;
const float DIPS_150_START = 115.0 / 360.0;
const float DIPS_150_END = 160.0 / 360.0;
const float DIPS_300_START = 270.0 / 360.0;
const float DIPS_300_END = 324.0 / 360.0;
const float RAMP_START = 240.0 / 360.0;
const float SMOOTH_SIGMA = 4.0;
const int SMOOTH_RADIUS = 12;

vec2 fetch_raw(int index) {
    return TORU_FOCAL_RAW_mul * texelFetch(
        TORU_FOCAL_RAW_raw,
        ivec2(index, 0),
        0
    ).rg;
}

void hook() {
    int index = int(gl_GlobalInvocationID.x);

    if (index >= int(FOCAL_HUE_SIZE)) {
        return;
    }

    int size = int(FOCAL_HUE_SIZE);
    int dips_150_start = int(DIPS_150_START * float(FOCAL_HUE_SIZE));
    int dips_150_end = int(DIPS_150_END * float(FOCAL_HUE_SIZE));
    int dips_300_start = int(DIPS_300_START * float(FOCAL_HUE_SIZE));
    int dips_300_end = int(DIPS_300_END * float(FOCAL_HUE_SIZE));
    float dips_150 = 1e9;
    float dips_300 = 1e9;
    int dips_300_index = int(DIPS_300_START * float(FOCAL_HUE_SIZE));

    // The two dips are the local minima of the L_cusp curve that bound the
    // simple profile: the upper one near 136 degrees, the lower near 306.
    for (int i = dips_150_start; i < dips_150_end; i++) {
        float value = fetch_raw(i).x;

        if (value > 0.0 && value < dips_150) {
            dips_150 = value;
        }
    }

    for (int i = dips_300_start; i < dips_300_end; i++) {
        float value = fetch_raw(i).x;

        if (value > 0.0 && value < dips_300) {
            dips_300 = value;
            dips_300_index = i;
        }
    }

    // Both ranges always contain valid samples; these keep a damaged table
    // from turning the clamp and ramp into infinities.
    if (dips_150 > 1e8) {
        dips_150 = 90.0;
    }

    if (dips_300 > 1e8) {
        dips_300 = 50.0;
    }

    float lightness_sum = 0.0;
    float chroma_sum = 0.0;
    float weight_sum = 0.0;

    for (int offset = -SMOOTH_RADIUS; offset <= SMOOTH_RADIUS; offset++) {
        int sample_index = (index + offset) & (size - 1);
        vec2 raw = fetch_raw(sample_index);
        float weight = exp(
            -0.5 * float(offset * offset) /
            (SMOOTH_SIGMA * SMOOTH_SIGMA)
        );
        lightness_sum += weight * raw.x;
        chroma_sum += weight * raw.y;
        weight_sum += weight;
    }

    float l_focal = clamp(
        lightness_sum / weight_sum,
        dips_300,
        dips_150
    );
    float c_focal = chroma_sum / weight_sum;
    int ramp_start = int(RAMP_START * float(FOCAL_HUE_SIZE));
    int ramp_length = max(dips_300_index - ramp_start, 1);

    // Replace the cusp-derived lightness over the blue-to-magenta side with
    // the straight fall shown in BT.2407 Figure A2-4, then hold the lower
    // limit for the rest of the circle.
    if (index >= ramp_start && index <= dips_300_index) {
        l_focal = mix(
            dips_150,
            dips_300,
            float(index - ramp_start) / float(ramp_length)
        );
    } else if (index > dips_300_index) {
        l_focal = dips_300;
    }

    imageStore(
        out_image,
        ivec2(index, 0),
        vec4(l_focal, c_focal, 0.0, 1.0)
    );
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND TORU_FOCAL
//!BIND TORU_BOUNDARY
//!DESC gamut mapping (BT.2407 annex 2)

const uint BOUNDARY_HUE_SIZE = 512u;
const uint BOUNDARY_LIGHTNESS_SIZE = 256u;
const uint FOCAL_HUE_SIZE = 1024u;
const int RAY_ITERATIONS = 12;
const int HUE_PATH_SCAN_STEPS = 32;
const int HUE_PATH_FINE_STEPS = 16;
const int HUE_PATH_ITERATIONS = 12;
const float CHROMA_EPSILON = 1e-6;
const float YELLOW_LIGHTNESS = 92.74;
const float WHITE_LIGHTNESS = 100.0;
const float PEAK_LIGHTNESS = 106.9;
const float YELLOW_PATH_ROTATION = 9.137;
const float CYAN_PATH_ROTATION = 10.13;
const float TAU = 6.28318530717958647692;
const float INV_TAU = 1.0 / TAU;
const float LAB_DELTA = 6.0 / 29.0;
const float LAB_DELTA_CUBED = 216.0 / 24389.0;
const float LAB_LINEAR_SCALE = 108.0 / 841.0;
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

bool lab_in_unit_gamut(vec3 lab) {
    vec3 rgb = lab_to_xyz(lab) * XYZ_TO_BT709;
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float map_lightness(
    float lightness,
    float chroma,
    float mapped_chroma,
    float l_focal,
    float c_focal,
    bool above
) {
    if (above) {
        return l_focal +
               (lightness - l_focal) * mapped_chroma / chroma;
    }

    return lightness * (c_focal - mapped_chroma) /
           max(c_focal - chroma, CHROMA_EPSILON);
}

// A2.5: yellow highlights and cyan follow a sloped line in a*b* instead of
// the radial line through the origin. The direction points away from the
// gamut, so the search walks along its negative.
bool hue_path_direction(
    float hue_degrees,
    float lightness,
    out vec2 direction
) {
    if (hue_degrees > 0.0 && hue_degrees < 180.0 &&
        lightness > YELLOW_LIGHTNESS) {
        float k;

        // The rotation peaks at white and fades to zero at both the yellow
        // lightness limit and peak white (equation 2-6).
        if (lightness <= WHITE_LIGHTNESS) {
            k = (lightness - YELLOW_LIGHTNESS) /
                (WHITE_LIGHTNESS - YELLOW_LIGHTNESS);
        } else {
            k = (PEAK_LIGHTNESS - lightness) /
                (PEAK_LIGHTNESS - WHITE_LIGHTNESS);
        }

        k = clamp(k, 0.0, 1.0);
        float theta = radians(
            hue_degrees -
            YELLOW_PATH_ROTATION * k * sin(radians(hue_degrees))
        );
        direction = vec2(cos(theta), sin(theta));
        return true;
    }

    if (hue_degrees > 180.0 && hue_degrees < 240.0) {
        float theta = radians(
            hue_degrees +
            CYAN_PATH_ROTATION * sin(radians(3.0 * hue_degrees))
        );
        direction = vec2(cos(theta), sin(theta));
        return true;
    }

    direction = vec2(0.0);
    return false;
}

vec2 fetch_focal(int index) {
    return TORU_FOCAL_mul * texelFetch(
        TORU_FOCAL_raw,
        ivec2(index, 0),
        0
    ).rg;
}

vec2 sample_focal(float hue) {
    float position = fract(hue * INV_TAU + 1.0) *
                     float(FOCAL_HUE_SIZE);
    int lower = int(floor(position)) & int(FOCAL_HUE_SIZE - 1u);
    int upper = (lower + 1) & int(FOCAL_HUE_SIZE - 1u);
    return mix(fetch_focal(lower), fetch_focal(upper), fract(position));
}

// The boundary table has one extra hue column repeating hue zero, so a
// single hardware-filtered sample is enough even across the hue seam.
float sample_boundary(float hue, float lightness) {
    float hue_position = fract(hue * INV_TAU + 1.0) *
                         float(BOUNDARY_HUE_SIZE);
    float hue_coordinate = (hue_position + 0.5) /
                           float(BOUNDARY_HUE_SIZE + 1u);
    float lightness_position = clamp(lightness, 0.0, 100.0) /
                               100.0 *
                               float(BOUNDARY_LIGHTNESS_SIZE - 1u);
    float lightness_coordinate = (lightness_position + 0.5) /
                                 float(BOUNDARY_LIGHTNESS_SIZE);
    return TORU_BOUNDARY_mul * textureLod(
        TORU_BOUNDARY_raw,
        vec2(hue_coordinate, lightness_coordinate),
        0.0
    ).r;
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb2020 = color.rgb;
    vec3 rgb709 = rgb2020 * BT2020_TO_BT709;

    if (all(greaterThanEqual(rgb709, vec3(0.0))) &&
        all(lessThanEqual(rgb709, vec3(1.0)))) {
        color.rgb = rgb709;
        return color;
    }

    vec3 lab = xyz_to_lab(rgb2020 * BT2020_TO_XYZ);
    float chroma = length(lab.yz);

    // Hue is meaningless on the neutral axis, and a neutral can only be out
    // of gamut by being beyond black or white. Clamping is the whole answer.
    if (chroma <= CHROMA_EPSILON) {
        color.rgb = clamp(rgb709, 0.0, 1.0);
        return color;
    }

    float hue = atan(lab.z, lab.y);
    vec2 hue_direction = lab.yz / chroma;
    vec2 focal = sample_focal(hue);
    float l_focal = focal.x;
    float c_focal = max(focal.y, CHROMA_EPSILON);
    bool above = lab.x >= l_focal - l_focal * chroma / c_focal;
    float hue_degrees = degrees(hue);

    if (hue_degrees < 0.0) {
        hue_degrees += 360.0;
    }

    vec2 path_direction;
    bool hue_mapped = hue_path_direction(
        hue_degrees,
        lab.x,
        path_direction
    );
    float mapped_lightness;
    vec2 mapped_ab;
    bool mapped = false;

    if (hue_mapped) {
        float maximum = chroma + 160.0;
        float first_step = maximum / float(HUE_PATH_SCAN_STEPS);
        float lower = 0.0;
        float upper = 0.0;
        float previous = 0.0;
        bool found = false;

        // The report parameterizes the line by r = b_map / b_out. Walking
        // along the line's direction is the same path and stays stable when
        // the line is nearly vertical. The boundary table is indexed by hue,
        // which changes along this path, so the crossing is bracketed with
        // the exact BT.709 test instead.
        // The first coarse step is subdivided. A near-neutral input can have
        // its whole in-gamut interval inside that step, which made the old
        // scan miss it and fall back to the radial path instead of executing
        // the reported hue mapping.
        for (int i = 1; i <= HUE_PATH_FINE_STEPS; i++) {
            float distance = first_step * float(i) /
                             float(HUE_PATH_FINE_STEPS);
            vec2 ab = lab.yz - distance * path_direction;
            float mapped_chroma = length(ab);
            float test_lightness = map_lightness(
                lab.x,
                chroma,
                mapped_chroma,
                l_focal,
                c_focal,
                above
            );

            if (lab_in_unit_gamut(vec3(test_lightness, ab))) {
                lower = previous;
                upper = distance;
                found = true;
                break;
            }

            previous = distance;
        }

        // Continue with the original coarse scan from its second step. This
        // leaves the far part of the path unchanged; only the first coarse
        // step is refined.
        if (!found) {
            for (int i = 2; i <= HUE_PATH_SCAN_STEPS; i++) {
                float distance = maximum * float(i) /
                                 float(HUE_PATH_SCAN_STEPS);
                vec2 ab = lab.yz - distance * path_direction;
                float mapped_chroma = length(ab);
                float test_lightness = map_lightness(
                    lab.x,
                    chroma,
                    mapped_chroma,
                    l_focal,
                    c_focal,
                    above
                );

                if (lab_in_unit_gamut(vec3(test_lightness, ab))) {
                    lower = previous;
                    upper = distance;
                    found = true;
                    break;
                }

                previous = distance;
            }
        }

        if (found) {
            for (int i = 0; i < HUE_PATH_ITERATIONS; i++) {
                float distance = 0.5 * (lower + upper);
                vec2 ab = lab.yz - distance * path_direction;
                float mapped_chroma = length(ab);
                float test_lightness = map_lightness(
                    lab.x,
                    chroma,
                    mapped_chroma,
                    l_focal,
                    c_focal,
                    above
                );

                if (lab_in_unit_gamut(vec3(test_lightness, ab))) {
                    upper = distance;
                } else {
                    lower = distance;
                }
            }

            mapped_ab = lab.yz - upper * path_direction;
            mapped_lightness = map_lightness(
                lab.x,
                chroma,
                length(mapped_ab),
                l_focal,
                c_focal,
                above
            );
            mapped = true;
        }
    }

    if (!mapped && above) {
        float lower = 0.0;
        float upper = 1.0;

        for (int i = 0; i < RAY_ITERATIONS; i++) {
            float weight = 0.5 * (lower + upper);
            float sample_lightness = mix(
                l_focal,
                lab.x,
                weight
            );
            float sample_chroma = weight * chroma;

            if (sample_chroma <= sample_boundary(hue, sample_lightness)) {
                lower = weight;
            } else {
                upper = weight;
            }
        }

        mapped_lightness = mix(l_focal, lab.x, lower);
        mapped_ab = (lower * chroma) * hue_direction;
    } else if (!mapped) {
        float maximum = c_focal / max(c_focal - chroma, CHROMA_EPSILON);
        float lower = 0.0;
        float upper = maximum;

        for (int i = 0; i < RAY_ITERATIONS; i++) {
            float weight = 0.5 * (lower + upper);
            float sample_lightness = weight * lab.x;
            float sample_chroma = c_focal +
                                  weight * (chroma - c_focal);

            if (sample_chroma <= sample_boundary(hue, sample_lightness)) {
                upper = weight;
            } else {
                lower = weight;
            }
        }

        mapped_lightness = upper * lab.x;
        mapped_ab = (c_focal + upper * (chroma - c_focal)) *
                    hue_direction;
    }

    vec3 mapped_lab = vec3(mapped_lightness, mapped_ab);
    color.rgb = clamp(
        lab_to_xyz(mapped_lab) * XYZ_TO_BT709,
        0.0,
        1.0
    );

    return color;
}
