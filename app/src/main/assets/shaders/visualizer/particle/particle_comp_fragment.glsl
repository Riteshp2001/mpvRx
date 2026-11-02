#version 300 es
precision highp float;
uniform sampler2D uTrail;
uniform float uExposure;
in vec2 vUv;
out vec4 o;

void main() {
  vec3 trail = textureLod(uTrail, vUv, 0.0).rgb;
  trail += textureLod(uTrail, vUv, 2.0).rgb * 0.06;
  float peak = max(max(trail.r, trail.g), trail.b);
  float brightness = 1.0 - exp(-peak * uExposure);
  float bottomFade = smoothstep(0.0, 0.22, vUv.y);
  float alpha = brightness * 0.90 * bottomFade;
  vec3 color = trail / max(peak, 0.0001);
  o = vec4(color, alpha);
}
