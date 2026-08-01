#version 300 es
layout(location=0) in float aDummy;
out vec2 vUv;
void main(){
  vec2 p = vec2(float((gl_VertexID<<1)&2), float(gl_VertexID&2));
  vUv = p;
  gl_Position = vec4(p*2.0-1.0, aDummy*0.0, 1.0);
}
