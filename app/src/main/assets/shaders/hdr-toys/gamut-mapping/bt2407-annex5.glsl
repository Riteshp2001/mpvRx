// https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2407-2017-PDF-E.pdf
//
// BT.2407 Annex 5: luminance-preserving conversion with reversible soft
// clipping. Input and output have the same linear luminance. In the RGB
// space of each gamut, the constant-luminance ray through the input leaves
// the unit cube at an analytically known scale: the first channel to reach
// zero or one. The target crossing is t_target, the source crossing is
// t_source, which gives both boundary chromaticities on that ray.
//
// The roll-off works on distances from white along the ray, measured in
// CIE 1976 u'v'. The input is at d_input, the target boundary at d_target
// and the source boundary at d_source. Equation (5-4)'s f is evaluated at
// x = d_input / d_target with alpha = d_source / d_target - 1. The output
// chromaticity is white + f(x) * (target boundary - white), rebuilt at the
// input luminance. The report fixes beta at 0.2, the margin reserved inside
// the target for out-of-gamut source colours; colours with x at or below
// 1 - beta pass through unchanged. Because f reaches one at the source
// boundary, that boundary lands exactly on the target boundary, which is
// what makes the conversion reversible.
//
// Luminance is preserved because every point of the ray, including both
// boundary crossings, has the input's luminance. Over-white and negative
// luminance cannot be represented at the same luminance in a unit RGB cube,
// so they map to white and black.

//!HOOK OUTPUT
//!BIND HOOKED
//!DESC gamut mapping (BT.2407 annex 5)

const float BETA = 0.2;
const vec3 BT2020_LUMA = vec3(
    0.2627002120112671,
    0.6779980715188708,
    0.0593017164698620
);
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

const mat3 XYZ_TO_BT709 = mat3(
     3.2409699419045226, -1.5373831775700940, -0.4986107602930034,
    -0.9692436362808796,  1.8759675015077202,  0.0415550574071756,
     0.0556300796969937, -0.2039769588889765,  1.0569715142428786
);

// First scale where mix(vec3(luminance), rgb, scale) reaches zero or one in
// any channel. The neutral point is inside for luminance in (0, 1).
float ray_boundary(vec3 rgb, float luminance) {
    vec3 delta = rgb - vec3(luminance);
    vec3 direction = mix(vec3(-1.0), vec3(1.0), step(vec3(0.0), delta));
    vec3 safe_delta = direction * max(abs(delta), vec3(1e-7));
    vec3 target = mix(
        vec3(-luminance),
        vec3(1.0 - luminance),
        step(vec3(0.0), delta)
    );
    vec3 bounds = target / safe_delta;
    return min(min(bounds.x, bounds.y), bounds.z);
}

vec2 uv_of_xyz(vec3 xyz) {
    float denominator = xyz.x + 15.0 * xyz.y + 3.0 * xyz.z;
    return vec2(
        4.0 * xyz.x / denominator,
        9.0 * xyz.y / denominator
    );
}

vec3 xyz_of_uv(vec2 uv, float luminance) {
    float x = 2.25 * uv.x / uv.y;
    float z = (9.0 / uv.y - x - 15.0) / 3.0;
    return vec3(x, 1.0, z) * luminance;
}

// Equation (5-4). The bracket is squared: that is the quadratic Bezier
// written out, and it is what makes f(1 + alpha) equal one. The square is
// missing from the printed equation.
float soft_clip(float value, float alpha) {
    if (value <= 1.0 - BETA) {
        return value;
    }

    if (value > 1.0 + alpha) {
        return 1.0;
    }

    // Both the numerator and denominator vanish when alpha equals beta;
    // take the limit of the Bezier instead.
    if (abs(BETA - alpha) < 1e-4) {
        float term = (value + BETA - 1.0) / (2.0 * BETA);
        return value - BETA * term * term;
    }

    float root = sqrt(max(
        BETA * BETA + (alpha - BETA) * (value + BETA - 1.0),
        0.0
    ));
    float term = (BETA - root) / (BETA - alpha);
    return value - alpha * term * term;
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    vec3 rgb2020 = color.rgb;
    float luminance = dot(rgb2020, BT2020_LUMA);

    if (luminance <= 0.0) {
        color.rgb = vec3(0.0);
        return color;
    }

    if (luminance >= 1.0) {
        color.rgb = vec3(1.0);
        return color;
    }

    vec3 xyz_input = rgb2020 * BT2020_TO_XYZ;
    vec3 rgb709 = xyz_input * XYZ_TO_BT709;
    vec3 white_xyz = D65_WHITE * luminance;
    vec2 white_uv = uv_of_xyz(white_xyz);
    vec2 input_uv = uv_of_xyz(xyz_input);
    float input_distance = length(input_uv - white_uv);

    if (input_distance < 1e-9) {
        color.rgb = clamp(rgb709, 0.0, 1.0);
        return color;
    }

    float t_target = ray_boundary(rgb709, luminance);
    vec2 target_uv = uv_of_xyz(
        white_xyz + t_target * (xyz_input - white_xyz)
    );
    float target_distance = length(target_uv - white_uv);
    float x = input_distance / target_distance;

    // The inner (1 - beta) part of the target radius is left untouched.
    if (x <= 1.0 - BETA) {
        color.rgb = clamp(rgb709, 0.0, 1.0);
        return color;
    }

    float t_source = ray_boundary(rgb2020, luminance);
    vec2 source_uv = uv_of_xyz(
        white_xyz + t_source * (xyz_input - white_xyz)
    );
    float source_distance = length(source_uv - white_uv);
    float alpha = max(source_distance / target_distance - 1.0, 0.0);
    float rolloff = soft_clip(x, alpha);
    vec2 output_uv = white_uv + rolloff * (target_uv - white_uv);
    vec3 output_xyz = xyz_of_uv(output_uv, luminance);

    color.rgb = clamp(output_xyz * XYZ_TO_BT709, 0.0, 1.0);

    return color;
}
