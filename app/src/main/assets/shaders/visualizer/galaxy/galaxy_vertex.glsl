#version 300 es
precision highp float;

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aStarData;

uniform mat4 uMvp;
uniform float uTime;
uniform float uEnergy;
uniform float uSubBass;
uniform float uBass;
uniform float uLowMid;
uniform float uMid;
uniform float uHighMid;
uniform float uTreble;
uniform float uBeat;
uniform float uFlux;
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
    float spiralWave = sin(radius * 3.2 - uTime * 1.35 + phase);
    float spatialResponse = motion * (0.015 + uMid * 0.028 + uLowMid * 0.020 + uTreble * 0.022);
    position.z += spiralWave * spatialResponse * smoothstep(0.12, 3.2, radius);

    float fullPulse = uSubBass * 0.065 + uBass * 0.050 + uBeat * 0.055 + uFlux * 0.035;
    float reducedPulse = uBass * 0.009 + uEnergy * 0.006;
    float radialScale = 1.0 + mix(fullPulse, reducedPulse, uReducedMotion);
    position.xy *= radialScale;

    vec4 clipPosition = uMvp * vec4(position, 1.0);
    gl_Position = clipPosition;

    float transient = uTreble * 0.55 + uBeat * 0.85 + uFlux * 0.50;
    float twinkle = 0.5 + 0.5 * sin(uTime * motion * 3.8 + phase);
    float twinkleAmount = mix(0.18, 0.05, uReducedMotion);
    float audioSize = 1.0 + transient * mix(0.68, 0.18, uReducedMotion) + uSubBass * 0.25;
    float perspectiveScale = 7.5 / max(2.0, clipPosition.w);
    float densityScale = clamp(uViewportHeight / 850.0, 0.85, 1.8);
    gl_PointSize = clamp(
        baseSize * audioSize * perspectiveScale * densityScale,
        2.0,
        mix(12.0, 6.0, uReducedMotion)
    );

    float primaryWeight = 1.0 - step(0.5, colorBand);
    float secondaryWeight = step(0.5, colorBand) * (1.0 - step(1.5, colorBand));
    float tertiaryWeight = step(1.5, colorBand);
    vStarColorWeights = vec3(primaryWeight, secondaryWeight, tertiaryWeight);

    float coreLift = 1.0 - smoothstep(0.0, 1.15, radius);
    vBrightness = 0.48
        + coreLift * 0.45
        + uEnergy * 0.32
        + uSubBass * 0.25
        + uTreble * 0.25
        + uBeat * mix(0.42, 0.10, uReducedMotion)
        + twinkle * twinkleAmount;
}
