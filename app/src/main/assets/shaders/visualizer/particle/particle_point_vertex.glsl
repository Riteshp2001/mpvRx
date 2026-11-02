#version 300 es
precision highp float;

uniform highp sampler2D uState;
uniform float uAspect, uEnergy, uBeat, uBright, uViewportHeight;
uniform float uSubBass, uBass, uFlux, uEmission;
uniform int uSimSize;

out float vGlow;
out float vHot;

void main() {
  int id = gl_VertexID;
  ivec2 tc = ivec2(id % uSimSize, id / uSimSize);
  vec4 s = texelFetch(uState, tc, 0);
  vec2 p = s.xy;
  float life = s.z;
  float seed = s.w;

  gl_Position = vec4(p.x / uAspect, p.y, 0.0, 1.0);

  float dpiScale = clamp(uViewportHeight / 850.0, 0.55, 1.25);
  float transientBoost = uBeat * 1.2 + uFlux * 0.7 + uBass * 0.5;
  float basePoint = 3.8 + 1.2 * uEnergy + transientBoost;
  float sizeMultiplier = 0.65 + 0.55 * fract(seed * 19.17);
  gl_PointSize = clamp(basePoint * sizeMultiplier * dpiScale, 1.5, 9.0);

  float fadeInOut = smoothstep(0.0, 0.4, life) * (1.0 - smoothstep(3.6, 5.2, life));
  // Occasional glints keep the field from reading as a uniform haze.
  float sparkle = 0.50 + 0.50 * fract(seed * 11.7) + step(0.92, fract(seed * 17.31)) * 1.6;
  vGlow = uBright * (0.65 + 0.95 * uEnergy + 1.2 * uBeat + 0.6 * uSubBass) * fadeInOut * sparkle;
  // White-hot core energy, kept inside the lifetime fade and refresh-rate emission envelope.
  vHot = 0.65 * clamp(0.6 + 0.8 * uBeat + 0.5 * uBass, 0.4, 1.8) * fadeInOut * uEmission;
}
