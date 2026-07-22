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
    float softEdge = 1.0 - smoothstep(0.08, 1.0, radiusSquared);
    float hotCore = 1.0 - smoothstep(0.0, 0.20, radiusSquared);
    vec3 color = mix(themeColor, vec3(1.0), hotCore * 0.28);
    float alpha = softEdge * clamp(vBrightness, 0.0, 1.45);
    fragColor = vec4(color * vBrightness, alpha);
}
