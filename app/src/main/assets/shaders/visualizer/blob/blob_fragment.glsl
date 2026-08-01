#version 300 es
precision mediump float;

uniform vec3 uColor;
uniform float uIntensity;
in float vEnergy;
out vec4 fragColor;

void main() {
    vec3 emissive = uColor * uIntensity * (0.62 + vEnergy * 0.32);
    fragColor = vec4(emissive, 0.72);
}
