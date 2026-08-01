#version 300 es
precision mediump float;

uniform sampler2D uScene;
uniform float uThreshold;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 color = texture(uScene, vUv).rgb;
    float brightness = max(max(color.r, color.g), color.b);
    float contribution = smoothstep(uThreshold, uThreshold + 0.32, brightness);
    fragColor = vec4(color * contribution, 1.0);
}
