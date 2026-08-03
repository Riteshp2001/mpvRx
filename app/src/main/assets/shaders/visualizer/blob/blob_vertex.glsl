#version 300 es
precision highp float;

layout(location = 0) in vec3 aPosition;

uniform mat4 uMvp;
uniform float uTime;
uniform float uAudio;
uniform float uSubBass;
uniform float uBass;
uniform float uLowMid;
uniform float uMid;
uniform float uHighMid;
uniform float uTreble;
uniform float uBeat;
uniform float uFlux;
uniform sampler2D uSpectrum;

out float vEnergy;
out float vSpectrum;

vec4 permute(vec4 x) {
    return mod(((x * 34.0) + 1.0) * x, 289.0);
}

vec4 taylorInvSqrt(vec4 r) {
    return 1.79284291400159 - 0.85373472095314 * r;
}

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;

    i = mod(i, 289.0);
    vec4 p = permute(permute(permute(
        i.z + vec4(0.0, i1.z, i2.z, 1.0))
        + i.y + vec4(0.0, i1.y, i2.y, 1.0))
        + i.x + vec4(0.0, i1.x, i2.x, 1.0));

    float n_ = 1.0 / 7.0;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = taylorInvSqrt(vec4(
        dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)
    ));
    p0 *= norm.x;
    p1 *= norm.y;
    p2 *= norm.z;
    p3 *= norm.w;
    vec4 m = max(0.6 - vec4(
        dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)
    ), 0.0);
    m *= m;
    return 42.0 * dot(m * m, vec4(
        dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)
    ));
}

void main() {
    vec3 normal = normalize(aPosition);

    // Map spherical coordinates smoothly around equator to avoid polar artifacts
    float phi = atan(normal.z, normal.x); // -PI to PI
    float specCoord = clamp((phi / 3.14159265 + 1.0) * 0.5, 0.0, 1.0);
    float specValue = texture(uSpectrum, vec2(specCoord, 0.5)).r;

    float slowNoise = snoise(aPosition * (1.7 + uMid * 0.45) + vec3(uTime * 0.24));
    float detailNoise = snoise(aPosition * (4.2 + uTreble * 2.2) - vec3(uTime * 0.38));
    float breathing = 0.016 * sin(uTime * 1.1 + aPosition.y * 3.2);

    float specDisplacement = specValue * 0.22 * (1.0 + uBass * 0.7);
    float shockwave = sin(length(aPosition) * 5.0 - uTime * 6.0) * (uBeat * 0.08 + uFlux * 0.05);

    float reaction = 0.025 + uAudio * 0.52 + uBass * 0.26 + uSubBass * 0.18 + uBeat * 0.16;
    float displacement = (slowNoise * 0.75 + detailNoise * 0.25) * reaction + breathing + specDisplacement + shockwave;

    vec3 position = aPosition * (1.0 + uSubBass * 0.06 + uBass * 0.09 + uBeat * 0.05) + normal * displacement;

    vEnergy = clamp(0.38 + uAudio * 0.82 + abs(detailNoise) * uTreble * 0.42, 0.0, 1.6);
    vSpectrum = specValue;
    gl_Position = uMvp * vec4(position, 1.0);
}
