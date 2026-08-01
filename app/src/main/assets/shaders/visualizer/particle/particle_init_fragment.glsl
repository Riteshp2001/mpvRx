#version 300 es
precision highp float;

float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx)*.1031); p3 += dot(p3, p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }
float gaussrnd(float u1, float u2){ return clamp(sqrt(-2.0*log(max(u1,1e-6)))*cos(6.28318530*u2), -3.5, 3.5); }

/* emitter: 70% vertical spine, 30% horizontal dust band, both centered on origin */
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
    float sy = (r3 < 0.42) ? 0.12 : 0.65;   /* cluster near core + full spine */
    p = vec2(g1*0.008, g2*sy);
  } else {
    float sx = (r3 < 0.5) ? 0.14 : 0.60;     /* horizontal dust band */
    p = vec2(g1*sx, g2*(0.008 + 0.06*r4*r4));
  }
  float life = 1.2 + 3.6*hash12(fc*5.137 + seed*7.7);
  return vec4(p, life, seed);
}

out vec4 o;
void main(){
  vec2 fc = gl_FragCoord.xy;
  float seed = hash12(fc*0.6180339887) + hash12(fc.yx*2.236067977)*0.001;
  vec4 s = spawn(fc, 0.0, seed);
  s.z *= hash12(fc*9.87);       /* stagger initial lifetimes */
  o = s;
}
