#version 300 es
precision highp float;
in vec3 vCol;
out vec4 o;
void main(){ o = vec4(vCol, 1.0); }
