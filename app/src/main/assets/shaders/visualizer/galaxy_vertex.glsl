#version 300 es
precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aStarData;

uniform mat4 uMvp;
uniform float uTime;
uniform float uEnergy;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uBeat;
uniform float uViewportHeight;
uniform float uReducedMotion;

out vec3 vStarColorWeights;
out float vBrightness;

void main() {
    float baseSize = aStarData.x;
    float colorBand = aStarData.y;
    float phase = aStarData.z;
    float radius = length(aPosition.xy);
    float motion = 1.0 - uReducedMotion;

    vec3 position = aPosition;
    float spiralWave = sin(radius * 3.2 - uTime * 1.15 + phase);
    float spatialResponse = motion * (0.012 + uMid * 0.022 + uTreble * 0.018);
    position.z += spiralWave * spatialResponse * smoothstep(0.15, 3.2, radius);

    float fullPulse = uBass * 0.055 + uBeat * 0.042;
    float reducedPulse = uBass * 0.009 + uEnergy * 0.006;
    float radialScale = 1.0 + mix(fullPulse, reducedPulse, uReducedMotion);
    position.xy *= radialScale;

    vec4 clipPosition = uMvp * vec4(position, 1.0);
    gl_Position = clipPosition;

    float transient = uTreble * 0.52 + uBeat * 0.72;
    float twinkle = 0.5 + 0.5 * sin(uTime * motion * 3.45 + phase);
    float twinkleAmount = mix(0.16, 0.045, uReducedMotion);
    float audioSize = 1.0 + transient * mix(0.62, 0.16, uReducedMotion);
    float perspectiveScale = 7.2 / max(2.2, clipPosition.w);
    float densityScale = clamp(uViewportHeight / 900.0, 0.72, 1.45);
    gl_PointSize = clamp(
        baseSize * audioSize * perspectiveScale * densityScale,
        1.0,
        mix(8.5, 5.0, uReducedMotion)
    );

    float primaryWeight = 1.0 - step(0.5, colorBand);
    float secondaryWeight = step(0.5, colorBand) * (1.0 - step(1.5, colorBand));
    float tertiaryWeight = step(1.5, colorBand);
    vStarColorWeights = vec3(primaryWeight, secondaryWeight, tertiaryWeight);

    float coreLift = 1.0 - smoothstep(0.0, 1.15, radius);
    vBrightness = 0.48
        + coreLift * 0.42
        + uEnergy * 0.28
        + uTreble * 0.24
        + uBeat * mix(0.34, 0.09, uReducedMotion)
        + twinkle * twinkleAmount;
}
