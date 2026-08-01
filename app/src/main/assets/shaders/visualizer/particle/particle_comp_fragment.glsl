#version 300 es
precision highp float;
uniform sampler2D uTrail;
uniform float uAspect, uFlare, uHue, uFrame, uGrain, uCA, uExposure, uVig, uIsDarkTheme;
uniform vec3 uPrimaryColor;
uniform vec3 uSecondaryColor;
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
  c += textureLod(uTrail, uv, 2.0).rgb * 0.04;
  return c;
}

void main(){
  vec2 uv = vUv;
  vec2 cuv = uv - 0.5;
  vec2 ca  = cuv * dot(cuv,cuv) * uCA;

  vec3 rawCol;
  rawCol.r = trailCol(uv + ca).r;
  rawCol.g = trailCol(uv).g;
  rawCol.b = trailCol(uv - ca).b;

  // Dynamic theme tinting using Primary and Secondary theme palette colors
  vec3 themeTint = mix(uPrimaryColor, uSecondaryColor, clamp(rawCol.r * 2.0, 0.0, 1.0));
  vec3 col = mix(rawCol, rawCol * themeTint * 2.5, 0.70);

  // Tonemap particle brightness
  col = 1.0 - exp(-col * uExposure);

  // Calculate particle alpha based on brightness (0.0 = completely transparent background)
  float lum = dot(col, vec3(0.299, 0.587, 0.114));
  float alpha = clamp(lum * 3.5, 0.0, 1.0);

  if (alpha < 0.005) {
    // 100% transparent background everywhere there are no particles
    o = vec4(0.0, 0.0, 0.0, 0.0);
    return;
  }

  if (uIsDarkTheme < 0.5) {
    // Light Theme: render vivid particles tinted with theme colors for contrast against light backgrounds
    vec3 lightParticleCol = mix(themeTint * 0.85, col, 0.5);
    o = vec4(lightParticleCol, alpha * 0.90);
  } else {
    // Dark Theme: render vibrant glowing particles
    o = vec4(col, alpha);
  }
}
