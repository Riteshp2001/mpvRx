#version 300 es
precision highp float;
uniform highp sampler2D uTrail;
uniform float uExposure;
uniform vec3 uPrimaryColor, uSecondaryColor;
in vec2 vUv;
out vec4 o;

void main() {
  vec3 trail = textureLod(uTrail, vUv, 0.0).rgb;
  trail += textureLod(uTrail, vUv, 2.0).rgb * 0.06;
  float brightness = 1.0 - exp(-trail.r * uExposure);
  float bottomFade = smoothstep(0.0, 0.22, vUv.y);
  float alpha = clamp(brightness * 3.5, 0.0, 1.0) * bottomFade;
  if (alpha < 0.005) {
    o = vec4(0.0);
    return;
  }
  // Hot cores shift to the secondary theme color; fading trails keep the primary color.
  o = vec4(mix(uPrimaryColor, uSecondaryColor, clamp(trail.g * 2.0, 0.0, 1.0)), alpha);
}
