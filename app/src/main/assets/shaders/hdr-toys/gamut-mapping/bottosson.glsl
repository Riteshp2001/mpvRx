// https://github.com/w3c/csswg-drafts/issues/9449#issuecomment-1948512394

// Colors inside a protected part of the BT.709 gamut remain colorimetrically
// unchanged. Outside it, a hue and lightness-preserving first stage
// derives a smooth chroma target from an amphora-shaped guide. A second,
// outside-only RGB soft compression reaches the real BT.709 cube while
// accepting small hue shifts near its hard edges and corners.

//!HOOK OUTPUT
//!SAVE GAMUT_BOUNDARY
//!COMPONENTS 3
//!WIDTH 256
//!HEIGHT 128
//!COMPUTE 16 8
//!DESC gamut mapping (bottosson, boundary generation)

const uint BOUNDARY_HUE_SIZE = 256u;
const uint BOUNDARY_LIGHTNESS_SIZE = 128u;
const int BOUNDARY_SEARCH_ITERATIONS = 16;
const float TAU = 6.28318530717958647692;
const float INV_TAU = 1.0 / TAU;

// Hue-dependent natural-colour protection derived from the 140-patch X-Rite
// ColorChecker Digital SG reference data released in 2016 for charts made
// after November 2014 (D50 Lab -> Bradford D65 -> Oklab).
//
// Near-neutral samples (C < 0.02) have unstable hue and are excluded. For the
// remaining 81 patches, C is normalized by the BT.709 boundary at the same
// L/h. The median occupancy plus 2.5% forms the floor; more saturated patches
// add 12-degree Gaussian lobes combined with an eighth-order smooth norm.
// Values above one are intersected with BT.709. A smooth cap reserves the
// outermost 2% for a differentiable compression shell; neutral samples are
// protected separately by the radial construction and remain unchanged.
// The periodic table is sampled cubically so sparse chart hues do not become
// polygonal boundary corners.
// Reference data: https://doi.org/10.5281/zenodo.3245895
const int COLORCHECKER_PROTECTION_SIZE = 64;
const float COLORCHECKER_PROTECTION[COLORCHECKER_PROTECTION_SIZE] =
    float[COLORCHECKER_PROTECTION_SIZE](
        0.93244497, 0.95679049, 0.97544561, 0.98000000,
        0.98000000, 0.98000000, 0.97993395, 0.94650252,
        0.98000000, 0.98000000, 0.98000000, 0.98000000,
        0.96384304, 0.98000000, 0.98000000, 0.98000000,
        0.98000000, 0.98000000, 0.98000000, 0.98000000,
        0.98000000, 0.98000000, 0.98000000, 0.97999481,
        0.95142858, 0.98000000, 0.98000000, 0.97999989,
        0.98000000, 0.98000000, 0.98000000, 0.98000000,
        0.95707003, 0.97999993, 0.98000000, 0.98000000,
        0.98000000, 0.96628910, 0.98000000, 0.98000000,
        0.98000000, 0.97772026, 0.98000000, 0.98000000,
        0.98000000, 0.98000000, 0.98000000, 0.98000000,
        0.96843090, 0.91480939, 0.86717875, 0.83591858,
        0.82083819, 0.83113779, 0.85160185, 0.87822881,
        0.90113199, 0.90840840, 0.89578826, 0.87066070,
        0.87561894, 0.90416089, 0.91938827, 0.91446137
    );

float monotone_slope(float previous_delta, float next_delta) {
    if (previous_delta * next_delta <= 0.0)
        return 0.0;

    float magnitude = min(
        min(abs(previous_delta), abs(next_delta)),
        0.5 * abs(previous_delta + next_delta)
    );
    return sign(previous_delta) * magnitude;
}

float monotone_cubic(
    float p0,
    float p1,
    float p2,
    float p3,
    float weight
) {
    float m1 = monotone_slope(p1 - p0, p2 - p1);
    float m2 = monotone_slope(p2 - p1, p3 - p2);
    float weight2 = weight * weight;
    float weight3 = weight2 * weight;
    return (2.0 * weight3 - 3.0 * weight2 + 1.0) * p1
         + (weight3 - 2.0 * weight2 + weight) * m1
         + (-2.0 * weight3 + 3.0 * weight2) * p2
         + (weight3 - weight2) * m2;
}

float sample_colorchecker_protection(float hue) {
    float position = fract(hue * INV_TAU + 1.0) *
                     float(COLORCHECKER_PROTECTION_SIZE);
    int index = int(floor(position));
    float weight = fract(position);
    int mask = COLORCHECKER_PROTECTION_SIZE - 1;
    float p0 = COLORCHECKER_PROTECTION[(index - 1) & mask];
    float p1 = COLORCHECKER_PROTECTION[index & mask];
    float p2 = COLORCHECKER_PROTECTION[(index + 1) & mask];
    float p3 = COLORCHECKER_PROTECTION[(index + 2) & mask];
    return monotone_cubic(p0, p1, p2, p3, weight);
}

const mat3 OKLAB_TO_LMS = mat3(
    1.0000000000000000,  0.3963377773761749,  0.2158037573099136,
    1.0000000000000000, -0.1055613458156586, -0.0638541728258133,
    1.0000000000000000, -0.0894841775298119, -1.2914855480194092
);

const mat3 LMS_TO_XYZ = mat3(
     1.2268798758459243, -0.5578149944602171,  0.2813910456659647,
    -0.0405757452148008,  1.1122868032803170, -0.0717110580655164,
    -0.0763729366746601, -0.4214933324022432,  1.5869240198367816
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

vec3 oklch_to_xyz(float lightness, float chroma, vec2 hue_direction) {
    vec3 lab = vec3(lightness, chroma * hue_direction);
    vec3 lms_root = lab * OKLAB_TO_LMS;
    vec3 lms = lms_root * lms_root * lms_root;
    return lms * LMS_TO_XYZ;
}

bool rgb_in_unit_gamut(vec3 rgb) {
    return all(greaterThanEqual(rgb, vec3(0.0))) &&
           all(lessThanEqual(rgb, vec3(1.0)));
}

float find_chroma_boundary(
    float lightness,
    vec2 hue_direction,
    mat3 xyz_to_rgb
) {
    float lower = 0.0;
    float upper = 1.0;

    for (int i = 0; i < BOUNDARY_SEARCH_ITERATIONS; i++) {
        float candidate = 0.5 * (lower + upper);
        vec3 xyz = oklch_to_xyz(
            lightness,
            candidate,
            hue_direction
        );
        vec3 rgb = xyz * xyz_to_rgb;

        if (rgb_in_unit_gamut(rgb))
            lower = candidate;
        else
            upper = candidate;
    }

    return lower;
}

void generate_gamut_boundary(ivec2 position) {
    if (position.x >= int(BOUNDARY_HUE_SIZE) ||
        position.y >= int(BOUNDARY_LIGHTNESS_SIZE)) {
        return;
    }

    float hue = TAU * float(position.x) / float(BOUNDARY_HUE_SIZE);
    float lightness = float(position.y) /
                      float(BOUNDARY_LIGHTNESS_SIZE - 1u);
    vec2 hue_direction = vec2(cos(hue), sin(hue));
    float input_chroma = find_chroma_boundary(
        lightness,
        hue_direction,
        XYZ_TO_BT2020
    );
    float output_chroma = find_chroma_boundary(
        lightness,
        hue_direction,
        XYZ_TO_BT709
    );
    float protection_scale = sample_colorchecker_protection(hue);

    imageStore(
        out_image,
        position,
        vec4(input_chroma, output_chroma, protection_scale, 1.0)
    );
}

void hook() {
    generate_gamut_boundary(ivec2(gl_GlobalInvocationID.xy));
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND GAMUT_BOUNDARY
//!DESC gamut mapping (bottosson)

// GAMUT_BOUNDARY stores input/output chroma and the ColorChecker-derived
// protection scale. Keeping the scale separate guarantees that interpolated
// protection never exceeds the correspondingly interpolated output boundary.
// Their remaining shell provides room to turn gradually towards the smooth
// guide gamut before RGB fitting.
const float CHROMA_EPSILON = 1e-6;
// Avoid an arbitrarily steep shell where both gamut boundaries converge at
// black and white. Those nearly neutral endpoints need no full guide pull.
const float SHELL_MIN_CHROMA_SPAN = 0.01;
const float GUIDE_ANCHOR_LIGHTNESS = 0.70;
const float GUIDE_KNEE_SCALE = 0.85;
const float SOFT_CLIP_GREY = 0.20;
const float TAU = 6.28318530717958647692;
const float INV_TAU = 1.0 / TAU;
const int BOUNDARY_HUE_SIZE = 256;
const int BOUNDARY_LIGHTNESS_SIZE = 128;

const mat3 BT2020_TO_XYZ = mat3(
    0.6369580483012914, 0.1446169035862083, 0.1688809751641721,
    0.2627002120112671, 0.6779980715188708, 0.0593017164698620,
    0.0000000000000000, 0.0280726930490874, 1.0609850577107910
);

const mat3 XYZ_TO_BT709 = mat3(
     3.2409699419045226, -1.5373831775700940, -0.4986107602930034,
    -0.9692436362808796,  1.8759675015077202,  0.0415550574071756,
     0.0556300796969937, -0.2039769588889765,  1.0569715142428786
);

const mat3 XYZ_TO_LMS = mat3(
    0.8190224379967030, 0.3619062600528904, -0.1288737815209879,
    0.0329836539323885, 0.9292868615863434,  0.0361446663506424,
    0.0481771893596242, 0.2642395317527308,  0.6335478284694309
);

const mat3 LMS_TO_XYZ = mat3(
     1.2268798758459243, -0.5578149944602171,  0.2813910456659647,
    -0.0405757452148008,  1.1122868032803170, -0.0717110580655164,
    -0.0763729366746601, -0.4214933324022432,  1.5869240198367816
);

const mat3 LMS_TO_OKLAB = mat3(
    0.2104542683093140,  0.7936177747023054, -0.0040720430116193,
    1.9779985324311684, -2.4285922420485799,  0.4505937096174110,
    0.0259040424655478,  0.7827717124575296, -0.8086757549230774
);

const mat3 OKLAB_TO_LMS = mat3(
    1.0000000000000000,  0.3963377773761749,  0.2158037573099136,
    1.0000000000000000, -0.1055613458156586, -0.0638541728258133,
    1.0000000000000000, -0.0894841775298119, -1.2914855480194092
);

float signed_cuberoot(float value) {
    return sign(value) * pow(abs(value), 1.0 / 3.0);
}

vec3 xyz_to_oklab(vec3 xyz) {
    vec3 lms = xyz * XYZ_TO_LMS;
    vec3 lms_root = vec3(
        signed_cuberoot(lms.x),
        signed_cuberoot(lms.y),
        signed_cuberoot(lms.z)
    );
    return lms_root * LMS_TO_OKLAB;
}

vec3 oklab_to_xyz(vec3 lab) {
    vec3 lms_root = lab * OKLAB_TO_LMS;
    vec3 lms = lms_root * lms_root * lms_root;
    return lms * LMS_TO_XYZ;
}

vec3 oklab_to_oklch(vec3 lab) {
    float chroma = length(lab.yz);
    float hue = chroma > CHROMA_EPSILON ? atan(lab.z, lab.y) : 0.0;
    return vec3(lab.x, chroma, hue);
}

float normalized_hue(float hue) {
    return fract(hue * INV_TAU + 1.0);
}

vec3 fetch_boundary_texel(int hue_index, int lightness_index) {
    vec3 value = texelFetch(
        GAMUT_BOUNDARY_raw,
        ivec2(hue_index, lightness_index),
        0
    ).rgb;
    return GAMUT_BOUNDARY_mul * value;
}

vec3 monotone_slope(vec3 previous_delta, vec3 next_delta) {
    vec3 magnitude = min(
        min(abs(previous_delta), abs(next_delta)),
        0.5 * abs(previous_delta + next_delta)
    );
    vec3 same_direction = step(
        vec3(0.0),
        previous_delta * next_delta
    );
    return sign(previous_delta) * magnitude * same_direction;
}

vec3 monotone_cubic(
    vec3 p0,
    vec3 p1,
    vec3 p2,
    vec3 p3,
    float weight
) {
    vec3 m1 = monotone_slope(p1 - p0, p2 - p1);
    vec3 m2 = monotone_slope(p2 - p1, p3 - p2);
    float weight2 = weight * weight;
    float weight3 = weight2 * weight;
    return (2.0 * weight3 - 3.0 * weight2 + 1.0) * p1
         + (weight3 - 2.0 * weight2 + weight) * m1
         + (-2.0 * weight3 + 3.0 * weight2) * p2
         + (weight3 - weight2) * m2;
}

vec3 sample_gamut_boundary_hue(
    int hue_index,
    int lightness_index,
    float weight
) {
    int hue_previous = (hue_index - 1 + BOUNDARY_HUE_SIZE) %
                       BOUNDARY_HUE_SIZE;
    int hue_next = (hue_index + 1) % BOUNDARY_HUE_SIZE;
    int hue_following = (hue_index + 2) % BOUNDARY_HUE_SIZE;
    return monotone_cubic(
        fetch_boundary_texel(hue_previous, lightness_index),
        fetch_boundary_texel(hue_index, lightness_index),
        fetch_boundary_texel(hue_next, lightness_index),
        fetch_boundary_texel(hue_following, lightness_index),
        weight
    );
}

vec3 sample_gamut_boundary(float lightness, float hue) {
    float hue_position = normalized_hue(hue) *
                         float(BOUNDARY_HUE_SIZE);
    int hue_lower = int(floor(hue_position)) % BOUNDARY_HUE_SIZE;
    float hue_weight = fract(hue_position);

    float lightness_position = clamp(lightness, 0.0, 1.0) *
                               float(BOUNDARY_LIGHTNESS_SIZE - 1);
    int lightness_lower = int(floor(lightness_position));
    int lightness_upper = min(
        lightness_lower + 1,
        BOUNDARY_LIGHTNESS_SIZE - 1
    );
    float lightness_weight = fract(lightness_position);

    vec3 lower = sample_gamut_boundary_hue(
        hue_lower,
        lightness_lower,
        hue_weight
    );
    vec3 upper = sample_gamut_boundary_hue(
        hue_lower,
        lightness_upper,
        hue_weight
    );
    return mix(lower, upper, lightness_weight);
}

float smooth_shell_weight(float position) {
    return position * position * (3.0 - 2.0 * position);
}

// A rotationally symmetric, rounded chroma guide inspired by Bjorn Ottosson's
// Extended Oklab and two-stage gamut-reduction experiment. It provides a
// smooth target without importing the RGB cube's hue-dependent cusp.
float guide_gamut_radius(vec2 direction) {
    return max(
        0.5 - 0.2 * direction.x
            + 0.2 * direction.x * direction.y
            - 0.15 * direction.y,
        CHROMA_EPSILON
    );
}

float compress_guide_radius(float radius, float limit) {
    float knee = GUIDE_KNEE_SCALE * limit;
    if (radius <= knee)
        return radius;

    float span = max(limit - knee, CHROMA_EPSILON);
    float excess = (radius - knee) / span;
    return knee + span * excess / (1.0 + excess);
}

vec3 reduce_to_guide_gamut(vec3 lab, float weight) {
    float chroma = length(lab.yz);
    vec2 radial = vec2(
        lab.x - GUIDE_ANCHOR_LIGHTNESS,
        chroma
    );
    float radius = length(radial);

    if (radius <= CHROMA_EPSILON)
        return lab;

    vec2 direction = radial / radius;
    float limit = guide_gamut_radius(direction);
    float mapped_radius = compress_guide_radius(radius, limit);
    float mapped_chroma = direction.y * mapped_radius;
    float chroma_scale = mapped_chroma /
                         max(chroma, CHROMA_EPSILON);

    // Moving the radial lightness component towards the 0.7 guide anchor can
    // make a more saturated highlight darker than its surround. Keep Oklab L
    // fixed and use the guide only to derive a smooth chroma target; the
    // outside-only RGB stage handles the remaining cube fit.
    vec3 target = vec3(
        lab.x,
        lab.yz * chroma_scale
    );
    return mix(lab, target, weight);
}

vec3 map_to_guide_gamut(vec3 lab) {
    vec3 lch = oklab_to_oklch(lab);
    float lightness = clamp(lch.x, 0.0, 1.0);
    vec3 boundary = sample_gamut_boundary(lightness, lch.z);
    float protection_boundary = boundary.y * boundary.z;

    if (lch.y <= protection_boundary)
        return lab;

    float input_span = max(
        boundary.x - protection_boundary,
        SHELL_MIN_CHROMA_SPAN
    );
    float input_position = clamp(
        (lch.y - protection_boundary) / input_span,
        0.0,
        1.0
    );
    return reduce_to_guide_gamut(
        lab,
        smooth_shell_weight(input_position)
    );
}

vec3 soft_saturate(vec3 value, float softness) {
    float a = 1.0 + softness;
    value = min(value, vec3(a));
    float b = (a - 1.0) * sqrt(
        a / max(2.0 - a, CHROMA_EPSILON)
    );
    float denominator = max(
        sqrt(a * a + b * b) - b,
        CHROMA_EPSILON
    );
    return vec3(1.0) -
           (sqrt((value - a) * (value - a) + b * b) - b) /
           denominator;
}

// Identity for RGB values already inside [0, 1]. When any channel is outside,
// all channels share one softness so edges and corners are approached without
// a hue-preserving snap.
// A fourth-order norm blends simultaneous channel excursions smoothly; a
// hard maximum leaves a faint crease where the limiting RGB channel changes.
// Based on "soft clipping scaled to only have an effect if the color is
// outside the gamut":
vec3 soft_clip_rgb_outside_only(vec3 color) {
    vec3 centered = color - SOFT_CLIP_GREY;
    vec3 direction = sign(centered);
    vec3 scale = 0.5 + direction * (0.5 - SOFT_CLIP_GREY);
    vec3 magnitude = abs(centered / scale);
    vec3 outside = max(magnitude - vec3(1.0), vec3(0.0));
    vec3 outside2 = outside * outside;
    float outside_norm = pow(dot(outside2, outside2), 0.25);
    float softness = outside_norm / (1.0 + outside_norm);
    vec3 mapped = soft_saturate(magnitude, softness);
    return SOFT_CLIP_GREY + scale * direction * mapped;
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 xyz = color.rgb * BT2020_TO_XYZ;
    vec3 lab = xyz_to_oklab(xyz);
    lab = map_to_guide_gamut(lab);

    vec3 mapped_xyz = oklab_to_xyz(lab);
    vec3 mapped_rgb = mapped_xyz * XYZ_TO_BT709;
    color.rgb = clamp(
        soft_clip_rgb_outside_only(mapped_rgb),
        0.0,
        1.0
    );
    return color;
}
