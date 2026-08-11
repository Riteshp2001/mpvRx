// https://lut-to-texture.pages.dev/
// You can convert .cube format 3D LUTs to the desired texture format using the link above,
// then paste it to the last line.

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND LUT
//!DESC LUT

// Resource adapter for the read-only sampled LUT.
ivec3 lut_dimensions() {
    return textureSize(LUT, 0);
}

vec3 lut_fetch(ivec3 index) {
    return texelFetch(LUT, index, 0).rgb;
}

vec3 sample_lut_trilinear(vec3 color) {
    vec3 dimensions = vec3(lut_dimensions());
    vec3 texel_position = clamp(color, 0.0, 1.0)
                        * (dimensions - vec3(1.0));
    vec3 texture_coordinates = (texel_position + vec3(0.5)) / dimensions;
    return textureLod(LUT, texture_coordinates, 0.0).rgb;
}

// Select the two middle vertices of the tetrahedron and sort the fractional
// coordinates into the corresponding interpolation order.
void select_tetrahedron(
    vec3 fraction,
    out ivec3 second_offset,
    out ivec3 third_offset,
    out vec3 weights
) {
    if (fraction.x >= fraction.y) {
        if (fraction.y >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.xyz;
        } else if (fraction.x >= fraction.z) {
            second_offset = ivec3(1, 0, 0);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.xzy;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(1, 0, 1);
            weights = fraction.zxy;
        }
    } else {
        if (fraction.x >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(1, 1, 0);
            weights = fraction.yxz;
        } else if (fraction.y >= fraction.z) {
            second_offset = ivec3(0, 1, 0);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.yzx;
        } else {
            second_offset = ivec3(0, 0, 1);
            third_offset = ivec3(0, 1, 1);
            weights = fraction.zyx;
        }
    }
}

void lut_cell(vec3 color, out ivec3 base_texel, out vec3 fraction) {
    ivec3 last_texel = lut_dimensions() - ivec3(1);
    vec3 position = clamp(color, 0.0, 1.0) * vec3(last_texel);

    // Keep the base inside the final complete cell. At the upper boundary,
    // fraction becomes 1.0 and selects the last texel without extra clamps.
    base_texel = min(ivec3(floor(position)), last_texel - ivec3(1));
    fraction = position - vec3(base_texel);
}

vec3 sample_lut_tetrahedral(vec3 color) {
    ivec3 base_texel;
    vec3 fraction;
    lut_cell(color, base_texel, fraction);

    ivec3 second_offset;
    ivec3 third_offset;
    vec3 weights;
    select_tetrahedron(fraction, second_offset, third_offset, weights);

    ivec3 texel0 = base_texel;
    ivec3 texel1 = base_texel + second_offset;
    ivec3 texel2 = base_texel + third_offset;
    ivec3 texel3 = base_texel + ivec3(1);

    vec3 value0 = lut_fetch(texel0);
    vec3 value1 = lut_fetch(texel1);
    vec3 value2 = lut_fetch(texel2);
    vec3 value3 = lut_fetch(texel3);

    return value0
         + weights.x * (value1 - value0)
         + weights.y * (value2 - value1)
         + weights.z * (value3 - value2);
}

vec3 sample_lut(vec3 color) {
    // Select the interpolation method here.
    return sample_lut_tetrahedral(color);
}

vec4 hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);
    color.rgb = sample_lut(color.rgb);
    return color;
}

//!TEXTURE LUT
//!SIZE 65 65 65
//!FORMAT rgba16hf
//!FILTER LINEAR
//!BORDER CLAMP
