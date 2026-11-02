#version 300 es
precision highp float;

uniform highp sampler2D uState;
uniform float uAspect, uEnergy, uBeat, uBright, uViewportHeight;
uniform float uSubBass, uBass, uTreble, uFlux;
uniform vec3 uPrimaryColor, uSecondaryColor;
uniform int uSimSize;

out vec3 vCol;

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

  float colorMix = clamp(fract(seed * 7.91) * 0.65 + p.y * 0.08 + uTreble * 0.15, 0.0, 1.0);
  vec3 tint = mix(uPrimaryColor, uSecondaryColor, colorMix);
  float luminance = dot(tint, vec3(0.2126, 0.7152, 0.0722));
  // A quieter theme-derived palette, with highlights inside the brightness/lifetime envelope.
  tint = mix(vec3(luminance), tint, 0.78);
  tint = mix(tint, vec3(1.0), 0.12 + uBeat * 0.06);

  float brightness = uBright * (0.75 + 0.65 * uEnergy + 0.55 * uBeat + 0.3 * uSubBass);
  float fadeInOut = smoothstep(0.0, 0.4, life) * (1.0 - smoothstep(3.6, 5.2, life));
  brightness *= fadeInOut * (0.55 + 0.45 * fract(seed * 11.7));
  vCol = tint * brightness;
}
