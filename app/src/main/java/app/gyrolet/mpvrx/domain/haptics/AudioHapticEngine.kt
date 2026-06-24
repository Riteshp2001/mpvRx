package app.gyrolet.mpvrx.domain.haptics

import app.gyrolet.mpvrx.preferences.HapticsPreset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure DSP for the haptics feature: it turns a frame of FFT data (as produced
 * by [android.media.audiofx.Visualizer.getFft]) into a vibration instruction.
 *
 * Deliberately free of any Android vibrator dependency so the math can be
 * reasoned about (and unit tested) in isolation. [HapticsManager] owns this
 * engine and is responsible for actually driving the motor.
 */
class AudioHapticEngine {
  /** Tuning parameters resolved from preferences + the active preset. */
  data class Params(
    val masterIntensity: Float, // 0..1
    val bassGain: Float, // 0..1
    val impactSensitivity: Float, // 0..1
    val noiseThreshold: Float, // 0..1
    val maxAmplitude: Float, // 0..1
    val preset: HapticsPreset,
  )

  /** One frame of output describing what the motor should do right now. */
  data class Frame(
    /** Continuous vibration strength, 0 (idle) .. 255 (max). */
    val amplitude: Int,
    /** True when a sudden transient (impact/hit) was detected this frame. */
    val onset: Boolean,
    /** Strength of the detected onset, 0..1. */
    val onsetStrength: Float,
  )

  // Smoothed envelope of the continuous "bed" vibration.
  private var envelope = 0f

  // Spectral-flux onset-detection state.
  private var prevMag: FloatArray? = null
  private var fluxAvg = 0f

  // Reusable magnitude buffer to avoid per-frame allocation.
  private var magBuf = FloatArray(0)

  fun reset() {
    envelope = 0f
    prevMag = null
    fluxAvg = 0f
  }

  /**
   * @param fft interleaved FFT buffer from Visualizer (length == captureSize).
   * @param samplingRateMilliHz Visualizer.getSamplingRate() (milli-Hz).
   */
  fun process(
    fft: ByteArray,
    samplingRateMilliHz: Int,
    params: Params,
  ): Frame {
    val captureSize = fft.size
    if (captureSize < 4) return Frame(0, false, 0f)

    val half = captureSize / 2
    if (magBuf.size != half) magBuf = FloatArray(half)
    val mag = magBuf

    // Decode magnitudes. fft[0]=DC real, fft[1]=Nyquist real,
    // fft[2k]=real, fft[2k+1]=imag for k in 1 until half.
    mag[0] = abs(fft[0].toInt()).toFloat()
    for (k in 1 until half) {
      val re = fft[2 * k].toInt().toFloat()
      val im = fft[2 * k + 1].toInt().toFloat()
      mag[k] = sqrt(re * re + im * im)
    }

    val sampleRate = (samplingRateMilliHz / 1000f).coerceAtLeast(8000f)
    val binHz = sampleRate / captureSize

    val tuning = tuningFor(params.preset)

    // Band energies (average magnitude per band), normalised to ~0..1.
    val bass = bandAvg(mag, binHz, 20f, 150f, half) / NORM
    val lowMid = bandAvg(mag, binHz, 150f, 600f, half) / NORM
    val mid = bandAvg(mag, binHz, 600f, 2500f, half) / NORM
    val high = bandAvg(mag, binHz, 2500f, 8000f, half) / NORM

    // Bass gain is a user-facing 0..1 mapped to a 0.5..2.0 multiplier.
    val bassMul = 0.5f + params.bassGain * 1.5f

    var level =
      bass * tuning.bassWeight * bassMul +
        lowMid * tuning.lowMidWeight +
        mid * tuning.midWeight +
        high * tuning.highWeight
    level = level.coerceIn(0f, 1f)

    // Noise gate: stay idle when the scene is quiet.
    if (level < params.noiseThreshold) {
      level = 0f
    }

    // Attack/decay envelope for a smooth, non-jittery bed.
    envelope =
      if (level > envelope) {
        envelope + (level - envelope) * tuning.attack
      } else {
        envelope + (level - envelope) * tuning.decay
      }

    // Perceptual curve + master intensity, then clamp to the user's ceiling.
    val shaped = envelope.pow(0.6f) * params.masterIntensity
    val ampF = (shaped).coerceIn(0f, params.maxAmplitude)
    val amplitude = (ampF * 255f).toInt().coerceIn(0, 255)

    // Spectral-flux onset detection across the impact-relevant bands.
    val onsetResult = detectOnset(mag, binHz, half, params.impactSensitivity, tuning)

    return Frame(
      amplitude = if (amplitude in 1..MIN_AMP) MIN_AMP else amplitude,
      onset = onsetResult.first,
      onsetStrength = onsetResult.second,
    )
  }

  private fun detectOnset(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    sensitivity: Float,
    tuning: Tuning,
  ): Pair<Boolean, Float> {
    val prev = prevMag
    // Snapshot current spectrum for next frame's diff.
    val snapshot = FloatArray(half)
    System.arraycopy(mag, 0, snapshot, 0, half)

    if (prev == null || prev.size != half) {
      prevMag = snapshot
      return false to 0f
    }

    // Sum of positive changes over ~20Hz..1.5kHz (where hits live).
    val loBin = max(1, (20f / binHz).toInt())
    val hiBin = min(half - 1, (1500f / binHz).toInt())
    var flux = 0f
    for (i in loBin..hiBin) {
      val d = mag[i] - prev[i]
      if (d > 0f) flux += d
    }
    flux /= (hiBin - loBin + 1).coerceAtLeast(1)
    flux /= NORM

    prevMag = snapshot

    // Adaptive threshold: react to flux above the recent average.
    val threshold = fluxAvg * (1f + (1f - sensitivity) * 3f) + 0.02f
    fluxAvg = fluxAvg * 0.9f + flux * 0.1f

    val isOnset = flux > threshold && flux > tuning.onsetFloor
    val strength = if (isOnset) ((flux - threshold) / 0.3f).coerceIn(0f, 1f) else 0f
    return isOnset to strength
  }

  private fun bandAvg(
    mag: FloatArray,
    binHz: Float,
    loHz: Float,
    hiHz: Float,
    half: Int,
  ): Float {
    val loBin = max(1, (loHz / binHz).toInt())
    val hiBin = min(half - 1, (hiHz / binHz).toInt())
    if (hiBin < loBin) return 0f
    var s = 0f
    for (i in loBin..hiBin) s += mag[i]
    return s / (hiBin - loBin + 1)
  }

  private data class Tuning(
    val bassWeight: Float,
    val lowMidWeight: Float,
    val midWeight: Float,
    val highWeight: Float,
    val attack: Float,
    val decay: Float,
    val onsetFloor: Float,
  )

  private fun tuningFor(preset: HapticsPreset): Tuning =
    when (preset) {
      // Films/series: emphasise rumble + big impacts, gentle on dialogue.
      HapticsPreset.Movie ->
        Tuning(
          bassWeight = 1.4f, lowMidWeight = 0.5f, midWeight = 0.15f, highWeight = 0.05f,
          attack = 0.5f, decay = 0.08f, onsetFloor = 0.06f,
        )
      // Music: fuller range, snappier to the beat.
      HapticsPreset.Music ->
        Tuning(
          bassWeight = 1.1f, lowMidWeight = 0.7f, midWeight = 0.4f, highWeight = 0.15f,
          attack = 0.6f, decay = 0.12f, onsetFloor = 0.05f,
        )
      // Games: fast and transient-led.
      HapticsPreset.Game ->
        Tuning(
          bassWeight = 1.0f, lowMidWeight = 0.5f, midWeight = 0.35f, highWeight = 0.2f,
          attack = 0.85f, decay = 0.2f, onsetFloor = 0.04f,
        )
      // Custom uses a neutral curve; the user's sliders do the shaping.
      HapticsPreset.Custom ->
        Tuning(
          bassWeight = 1.2f, lowMidWeight = 0.6f, midWeight = 0.3f, highWeight = 0.12f,
          attack = 0.6f, decay = 0.12f, onsetFloor = 0.05f,
        )
    }

  private companion object {
    // Byte FFT magnitudes are ~0..181; normalise toward 0..1 with headroom.
    const val NORM = 90f
    const val MIN_AMP = 18
  }
}
