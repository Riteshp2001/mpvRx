#version 300 es
precision mediump float;

uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength;
uniform float uExposure;
uniform vec3 uBackground;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 scene = texture(uScene, vUv).rgb;
    vec3 bloom = texture(uBloom, vUv).rgb;
    vec3 hdr = scene + bloom * uBloomStrength;
    vec3 mapped = vec3(1.0) - exp(-hdr * uExposure);
    mapped = pow(mapped, vec3(1.0 / 2.2));
    float visualCoverage = clamp(max(max(hdr.r, hdr.g), hdr.b) * 1.8, 0.0, 1.0);
    fragColor = vec4(mix(uBackground, mapped, visualCoverage), 1.0);
}
