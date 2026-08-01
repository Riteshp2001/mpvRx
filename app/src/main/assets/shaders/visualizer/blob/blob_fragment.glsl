#version 300 es
precision mediump float;

uniform vec3 uColor;
uniform float uIntensity;
in float vEnergy;
in float vSpectrum;
out vec4 fragColor;

void main() {
    // Shift color hue based on local spectral content: low bins (bass) stay warm, high bins (treble) shift cool
    vec3 warmShift = uColor * (1.0 + vSpectrum * 0.35);
    vec3 coolShift = vec3(uColor.r * 0.6 + vSpectrum * 0.2, uColor.g * 0.85 + vSpectrum * 0.15, uColor.b * 1.15 + vSpectrum * 0.25);
    vec3 spectralColor = mix(warmShift, coolShift, vSpectrum);

    vec3 emissive = spectralColor * uIntensity * (0.62 + vEnergy * 0.32);
    fragColor = vec4(emissive, 0.72);
}
