#version 300 es
layout(location=0) in float aDummy;
uniform highp sampler2D uState;
uniform float uAspect, uHue, uEnergy, uBeat, uHigh, uBright;
uniform int uSimSize;
out vec3 vCol;
vec3 hsv2rgb(vec3 c){
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
void main(){
  int id = gl_VertexID;
  ivec2 tc = ivec2(id % uSimSize, id / uSimSize);
  vec4 s = texelFetch(uState, tc, 0);
  vec2 p = s.xy; float seed = s.w;
  gl_Position = vec4(p.x/uAspect, p.y, aDummy*0.0, 1.0);
  gl_PointSize = 1.0;
  float core = exp(-dot(p,p)*20.0);
  float hue  = fract(uHue + (fract(seed*7.91)-0.5)*(0.55+0.30*uHigh) + p.y*0.05);
  float sat  = clamp(0.38 + 0.50*fract(seed*3.313) - core*0.45, 0.0, 0.92);
  vec3 tint  = hsv2rgb(vec3(hue, sat, 1.0));
  float b = uBright * (0.40 + 0.85*uEnergy + 0.90*uBeat) * (1.0 + 2.2*core);
  b *= exp(-length(p)*2.1);                  /* light concentrates near the core */
  b *= smoothstep(0.0, 0.5, s.z);            /* fade out at end of life */
  float lw = fract(seed*17.31);              /* sparse bright "leader" hairs over a dim mass */
  b *= 0.35 + 0.45*fract(seed*11.7) + step(0.90, lw)*3.2;
  vCol = tint * b;
}
