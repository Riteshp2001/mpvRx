#version 300 es
precision highp float;

layout(location=0) in float aDummy;

uniform highp sampler2D uState;
uniform float uAspect, uHue, uEnergy, uBeat, uHigh, uBright, uViewportHeight;
uniform float uSubBass, uBass, uMid, uTreble, uFlux;
uniform int uSimSize;

out vec3 vCol;
out float vLife;
out float vCoreIntensity;

vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
  int id = gl_VertexID;
  ivec2 tc = ivec2(id % uSimSize, id / uSimSize);
  vec4 s = texelFetch(uState, tc, 0);
  vec2 p = s.xy;
  float life = s.z;
  float seed = s.w;

  gl_Position = vec4(p.x / uAspect, p.y, aDummy * 0.0, 1.0);

  float dpiScale = clamp(uViewportHeight / 850.0, 0.9, 2.2);
  float transientBoost = uBeat * 3.5 + uFlux * 2.0 + uBass * 1.5;
  float basePoint = 4.5 + 2.5 * uEnergy + transientBoost;
  float sizeMultiplier = 0.5 + 0.8 * fract(seed * 19.17);
  gl_PointSize = clamp(basePoint * sizeMultiplier * dpiScale, 3.0, 18.0);

  float hue = fract(uHue + (fract(seed * 7.91) - 0.5) * (0.45 + 0.35 * uTreble) + p.y * 0.06);
  float sat = clamp(0.55 + 0.40 * fract(seed * 3.313) + uBass * 0.15, 0.1, 0.95);
  vec3 tint = hsv2rgb(vec3(hue, sat, 1.0));

  float b = uBright * (0.65 + 0.95 * uEnergy + 1.2 * uBeat + 0.6 * uSubBass);
  float fadeInOut = smoothstep(0.0, 0.35, life) * (1.0 - smoothstep(3.5, 4.8, life));
  b *= fadeInOut;

  float lw = fract(seed * 17.31);
  b *= 0.50 + 0.50 * fract(seed * 11.7) + step(0.92, lw) * 1.6;

  vCol = tint * b;
  vLife = life;
  vCoreIntensity = clamp(0.6 + 0.8 * uBeat + 0.5 * uBass, 0.4, 1.8);
}
