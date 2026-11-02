#version 300 es
precision highp float;

in vec3 vCol;
out vec4 o;

void main() {
  vec2 pc = gl_PointCoord - vec2(0.5);
  float distSq = dot(pc, pc);
  if (distSq > 0.25) discard;

  float dist = sqrt(distSq) * 2.0;
  float core = 1.0 - smoothstep(0.0, 0.32, dist);
  float halo = 1.0 - smoothstep(0.1, 1.0, dist);
  float profile = core * 0.35 + halo * 0.65;
  // Never add an unscaled white core: it would bypass emission and lifetime fading.
  o = vec4(vCol * profile, 0.0);
}
