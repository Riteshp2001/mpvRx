#version 300 es
precision highp float;

in float vGlow;
in float vHot;
out vec4 o;

void main() {
  vec2 pc = gl_PointCoord * 2.0 - 1.0;
  float distSq = dot(pc, pc);
  // Evaluate derivatives before any clipping so subpixel edges remain smooth as points move.
  float edgeWidth = max(fwidth(distSq), 0.001);
  float coverage = 1.0 - smoothstep(max(0.0, 1.0 - edgeWidth), 1.0, distSq);
  float core = exp(-distSq * 18.0);
  float halo = exp(-distSq * 4.5);
  float glow = vGlow * (core * 0.55 + halo * 0.45) * coverage;
  float hot = vHot * core * coverage;
  // R carries total light for opacity; G carries core heat for the two-tone theme tint.
  o = vec4(glow + hot, hot, 0.0, 0.0);
}
