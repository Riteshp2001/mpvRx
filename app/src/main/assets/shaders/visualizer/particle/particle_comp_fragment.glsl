#version 300 es
precision highp float;
uniform sampler2D uTrail;
uniform float uAspect, uFlare, uHue, uFrame, uGrain, uCA, uExposure, uVig;
in vec2 vUv;
out vec4 o;

float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx)*.1031); p3 += dot(p3, p3.yzx+33.33); return fract((p3.x+p3.y)*p3.z); }

vec3 hsv2rgb(vec3 c){
  vec4 K = vec4(1.0, 2.0/3.0, 1.0/3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz)*6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

vec3 trailCol(vec2 uv){
  vec3 c = texture(uTrail, uv).rgb;
  c += textureLod(uTrail, uv, 2.0).rgb * 0.035;  /* soft bloom */
  c += textureLod(uTrail, uv, 4.0).rgb * 0.025;
  return c;
}

float streak(float a, float b, float s2, float fall){
  return exp(-b*b/s2) / (1.0 + abs(a)*fall);
}

void main(){
  vec2 uv = vUv;
  vec2 d  = vec2((uv.x-0.5)*uAspect, uv.y-0.5)*2.0;   /* world coords */
  /* chromatic aberration */
  vec2 cuv = uv - 0.5;
  vec2 ca  = cuv * dot(cuv,cuv) * uCA;
  vec3 col;
  col.r = trailCol(uv + ca).r;
  col.g = trailCol(uv).g;
  col.b = trailCol(uv - ca).b;
  /* procedural core flare + anamorphic streaks (toned down for balanced brightness) */
  float rr = dot(d,d);
  vec3 fl = vec3(0.0);
  fl += vec3(0.00015 / (rr + 0.002));                       /* balanced core */
  fl.r += streak(d.x*0.90, d.y, 4.0e-5, 9.0) * 0.18;        /* thin horizontal */
  fl.g += streak(d.x*1.00, d.y, 4.0e-5, 9.0) * 0.18;
  fl.b += streak(d.x*1.12, d.y, 4.0e-5, 9.0) * 0.18;
  fl += vec3(streak(d.x, d.y, 4.5e-3, 6.0) * 0.025);        /* wide soft band */
  fl += vec3(streak(d.y, d.x, 3.0e-5, 18.0) * 0.030);       /* faint vertical beam */
  vec3 ftint = mix(vec3(1.0), hsv2rgb(vec3(uHue, 0.55, 1.0)), 0.12);
  col += fl * uFlare * ftint;
  /* tonemap */
  col = 1.0 - exp(-col * uExposure);
  /* film grain */
  vec2 gs = gl_FragCoord.xy + vec2(fract(uFrame*0.7131)*311.7, fract(uFrame*0.3719)*173.3);
  vec3 g = vec3(hash12(gs), hash12(gs + 19.19), hash12(gs + 47.47)) - 0.5;
  float lum = dot(col, vec3(0.299, 0.587, 0.114));
  col += g * (uGrain * (0.10 + 0.35*lum));
  col = pow(max(col, vec3(0.0)), vec3(0.4545));
  
  /* Transparent background output */
  float alpha = clamp(lum * 2.2, 0.0, 1.0);
  o = vec4(col, alpha);
}
