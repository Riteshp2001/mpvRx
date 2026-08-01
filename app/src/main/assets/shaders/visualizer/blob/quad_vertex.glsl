#version 300 es
precision mediump float;

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;
out vec2 vUv;

void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
