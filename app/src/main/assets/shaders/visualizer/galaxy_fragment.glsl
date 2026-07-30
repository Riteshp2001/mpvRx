#version 300 es
precision highp float;

uniform vec3 uPrimaryColor;
uniform vec3 uSecondaryColor;
uniform vec3 uTertiaryColor;

in vec3 vStarColorWeights;
in float vBrightness;
out vec4 fragColor;

void main() {
    vec2 centered = gl_PointCoord * 2.0 - 1.0;
    float radiusSquared = dot(centered, centered);
    if (radiusSquared > 1.0) {
        discard;
    }

    vec3 themeColor =
        uPrimaryColor * vStarColorWeights.x
        + uSecondaryColor * vStarColorWeights.y
        + uTertiaryColor * vStarColorWeights.z;
    // A luminous core with a coloured halo gives the particle field depth instead of
    // rendering as a flat cloud of equally bright dots.
    float softEdge = 1.0 - smoothstep(0.06, 1.0, radiusSquared);
    float hotCore = 1.0 - smoothstep(0.0, 0.16, radiusSquared);
    float halo = 1.0 - smoothstep(0.10, 0.72, radiusSquared);
    vec3 color = mix(themeColor * 0.78, uTertiaryColor, halo * 0.22);
    color = mix(color, vec3(1.0), hotCore * 0.46);
    float alpha = softEdge * clamp(vBrightness * (0.82 + halo * 0.24), 0.0, 1.45);
    fragColor = vec4(color * vBrightness, alpha);
}
