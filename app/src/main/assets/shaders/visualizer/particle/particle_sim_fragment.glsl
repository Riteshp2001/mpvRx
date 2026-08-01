#version 300 es
precision highp float;

float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx)*.1031); p3 += dot(p3, p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
float gaussrnd(float u1, float u2){ return clamp(sqrt(-2.0*log(max(u1,1e-6)))*cos(6.28318530*u2), -3.5, 3.5); }
vec3 mod289(vec3 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 mod289(vec4 x){ return x - floor(x*(1.0/289.0))*289.0; }
vec4 permute(vec4 x){ return mod289(((x*34.0)+1.0)*x); }
vec4 taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314*r; }
float snoise(vec3 v){
  const vec2 C = vec2(1.0/6.0, 1.0/3.0);
  const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
  vec3 i  = floor(v + dot(v, C.yyy));
  vec3 x0 = v - i + dot(i, C.xxx);
  vec3 g  = step(x0.yzx, x0.xyz);
  vec3 l  = 1.0 - g;
  vec3 i1 = min(g.xyz, l.zxy);
  vec3 i2 = max(g.xyz, l.zxy);
  vec3 x1 = x0 - i1 + C.xxx;
  vec3 x2 = x0 - i2 + C.yyy;
  vec3 x3 = x0 - D.yyy;
  i = mod289(i);
  vec4 p = permute(permute(permute(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));
  float n_ = 0.142857142857;
  vec3 ns = n_ * D.wyz - D.xzx;
  vec4 j = p - 49.0*floor(p*ns.z*ns.z);
  vec4 x_ = floor(j*ns.z);
  vec4 y_ = floor(j - 7.0*x_);
  vec4 x = x_*ns.x + ns.yyyy;
  vec4 y = y_*ns.x + ns.yyyy;
  vec4 h = 1.0 - abs(x) - abs(y);
  vec4 b0 = vec4(x.xy, y.xy);
  vec4 b1 = vec4(x.zw, y.zw);
  vec4 s0 = floor(b0)*2.0 + 1.0;
  vec4 s1 = floor(b1)*2.0 + 1.0;
  vec4 sq = -step(h, vec4(0.0));
  vec4 a0 = b0.xzyw + s0.xzyw*sq.xxyy;
  vec4 a1 = b1.xzyw + s1.xzyw*sq.zzww;
  vec3 p0 = vec3(a0.xy, h.x);
  vec3 p1 = vec3(a0.zw, h.y);
  vec3 p2 = vec3(a1.xy, h.z);
  vec3 p3 = vec3(a1.zw, h.w);
  vec4 norm = taylorInvSqrt(vec4(dot(p0,p0), dot(p1,p1), dot(p2,p2), dot(p3,p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
  vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
  m = m*m;
  return 42.0 * dot(m*m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));
}
vec2 curl(vec2 p, float t){
  float e = 0.04;
  float n1 = snoise(vec3(p.x, p.y+e, t));
  float n2 = snoise(vec3(p.x, p.y-e, t));
  float n3 = snoise(vec3(p.x+e, p.y, t));
  float n4 = snoise(vec3(p.x-e, p.y, t));
  return vec2(n1-n2, n4-n3) / (2.0*e);
}
vec4 spawn(vec2 fc, float t, float seed){
  float r1 = hash12(fc*1.373 + fract(t*0.731)*77.7 + seed*91.7);
  float r2 = hash12(fc*2.719 + fract(t*0.377)*55.5 + seed*17.3);
  float r3 = hash12(fc*3.117 + fract(t*0.913)*33.3 + seed*53.1);
  float r4 = hash12(fc*4.971 + fract(t*0.171)*99.9 + seed*29.9);
  float g1 = gaussrnd(r1, r3);
  float g2 = gaussrnd(r2, r4);
  float fam = fract(seed*13.37);
  vec2 p;
  if (fam < 0.70){
    float sy = (r3 < 0.42) ? 0.085 : 0.52;
    p = vec2(g1*0.0055, g2*sy);
  } else {
    float sx = (r3 < 0.5) ? 0.10 : 0.48;
    p = vec2(g1*sx, g2*(0.006 + 0.045*r4*r4));
  }
  float life = 1.2 + 3.6*hash12(fc*5.137 + seed*7.7);
  return vec4(p, life, seed);
}

uniform highp sampler2D uState;
uniform float uTime, uDt, uBass, uMid, uHigh, uBeat, uEnergy;
out vec4 o;

void main(){
  ivec2 tc = ivec2(gl_FragCoord.xy);
  vec4 s = texelFetch(uState, tc, 0);
  vec2 p = s.xy; float life = s.z; float seed = s.w;
  life -= uDt;
  if (life <= 0.0 || abs(p.x) > 2.6 || abs(p.y) > 1.7 || any(isnan(p))){
    o = spawn(gl_FragCoord.xy, uTime, seed);
    return;
  }
  float fam = fract(seed*13.37);
  float bandK = (fam < 0.70) ? 1.0 : 0.30;
  vec2 v = vec2(0.0);
  /* large slow arcs */
  v += curl(p*1.7 + vec2(seed*4.7, seed*2.9), uTime*0.05 + seed*3.1) * 0.0011 * bandK;
  /* fine feathering, mids raise turbulence */
  v += curl(p*6.0 + vec2(seed*9.1, -seed*6.3), uTime*0.15) * 0.0013 * (0.5 + 1.4*uMid) * bandK;
  if (fam < 0.70){
    v.x += sign(p.x + 1e-5) * 0.0005 * (0.25 + uEnergy);
    v.y += sign(p.y + 1e-5) * 0.0007 * (0.25 + 0.9*uBass);
  } else {
    v.x += sign(p.x + 1e-5) * (0.0014 + 0.008*uBass);
  }
  /* beat: radial burst from the core */
  float r = length(p) + 1e-4;
  v += (p/r) * (uBeat*uBeat) * 0.028 * exp(-r*3.0);
  float sp = 0.55 + 0.9*fract(seed*5.19);
  p += v * sp * (uDt*60.0);
  o = vec4(p, life, seed);
}
