#version 300 es
precision mediump float;

uniform sampler2D uImage;
uniform vec2 uDirection;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 result = texture(uImage, vUv).rgb * 0.227027;
    result += texture(uImage, vUv + uDirection * 1.384615).rgb * 0.316216;
    result += texture(uImage, vUv - uDirection * 1.384615).rgb * 0.316216;
    result += texture(uImage, vUv + uDirection * 3.230769).rgb * 0.070270;
    result += texture(uImage, vUv - uDirection * 3.230769).rgb * 0.070270;
    fragColor = vec4(result, 1.0);
}
