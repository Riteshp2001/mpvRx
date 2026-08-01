#version 300 es
precision highp float;
uniform sampler2D uTrail;
uniform vec2 uTexel;
uniform float uDecay, uDiff;
in vec2 vUv;
out vec4 o;
void main(){
  vec3 c = texture(uTrail, vUv).rgb * (1.0 - 4.0*uDiff);
  c += texture(uTrail, vUv + vec2( uTexel.x, 0.0)).rgb * uDiff;
  c += texture(uTrail, vUv + vec2(-uTexel.x, 0.0)).rgb * uDiff;
  c += texture(uTrail, vUv + vec2(0.0,  uTexel.y)).rgb * uDiff;
  c += texture(uTrail, vUv + vec2(0.0, -uTexel.y)).rgb * uDiff;
  o = vec4(c * uDecay, 1.0);
}
