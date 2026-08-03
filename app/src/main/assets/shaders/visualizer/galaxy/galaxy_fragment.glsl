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

    // Multi-stage anti-aliased radial falloff for stellar particles
    float softEdge = 1.0 - smoothstep(0.65, 1.0, radiusSquared);
    float hotCore = 1.0 - smoothstep(0.0, 0.14, radiusSquared);
    float halo = 1.0 - smoothstep(0.08, 0.75, radiusSquared);

    vec3 color = mix(themeColor * 0.85, uTertiaryColor, halo * 0.25);
    color = mix(color, vec3(1.0), hotCore * 0.55);
    float alpha = softEdge * clamp(vBrightness * (0.85 + halo * 0.28), 0.0, 1.5);
    fragColor = vec4(color * vBrightness, alpha);
}
