#version 300 es
precision highp float;

in vec3 vCol;
in float vLife;
in float vCoreIntensity;

out vec4 o;

void main() {
  vec2 pc = gl_PointCoord - vec2(0.5);
  float distSq = dot(pc, pc);
  if (distSq > 0.25) {
    discard;
  }
  float dist = sqrt(distSq) * 2.0; // 0.0 at center, 1.0 at outer edge

  // Crisp hot core at center + smooth anti-aliased edge + luminous glow halo
  float core = 1.0 - smoothstep(0.0, 0.22, dist);
  float halo = 1.0 - smoothstep(0.12, 0.88, dist);
  float alphaEdge = 1.0 - smoothstep(0.82, 1.0, dist);

  // Combine crisp white-hot center core with rich colored halo
  vec3 particleColor = mix(vCol, vec3(1.0), core * 0.65 * vCoreIntensity);
  float finalAlpha = (halo * 0.80 + core * 0.20) * alphaEdge;

  o = vec4(particleColor * finalAlpha, finalAlpha);
}
