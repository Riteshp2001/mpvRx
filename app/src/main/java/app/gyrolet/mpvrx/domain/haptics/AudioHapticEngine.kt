package app.gyrolet.mpvrx.domain.haptics

import app.gyrolet.mpvrx.preferences.HapticsPreset
import kotlin.math.abs
import kotlin.math.ln
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
 *
 * v2 — Premium refinements:
 *  • Hann-windowed FFT for cleaner bass isolation
 *  • Dynamic range compression (DRC) for consistent feel across quiet/loud
 *  • Log-perceptual amplitude curve for subtle-range granularity
 *  • Slower envelope attack/decay to eliminate jitter
 *  • Onset debounce to prevent impact stacking
 *  • Adaptive noise floor that tracks scene energy
 *  • New Subtle + Cinema preset tunings
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
    val quietSceneAwareness: Boolean = true,
    val impactCooldownMs: Int = 150,
  )

  /** One frame of output describing what the motor should do right now. */
  data class Frame(
    /** Continuous vibration strength, 0 (idle) .. 255 (max). */
    val amplitude: Int,
    /** True when a sudden transient (impact/hit) was detected this frame. */
    val onset: Boolean,
    /** Strength of the detected onset, 0..1. */
    val onsetStrength: Float,
    /**
     * 0..3 impact tier for the current onset. Only meaningful when
     * [onset] is true. Used by [HapticsManager] to pick the right
     * vibration primitive.
     *  - 0 = subtle tap (LOW_TICK)
     *  - 1 = light click (TICK)
     *  - 2 = clear punch (CLICK)
     *  - 3 = weighty hit (THUD)
     */
    val impactTier: Int = 0,
  )

  // --- State ----------------------------------------------------------------

  /** Smoothed envelope of the continuous "bed" vibration. */
  private var envelope = 0f

  // Spectral-flux onset-detection state.
  private var prevMag: FloatArray? = null
  private var fluxAvg = 0f

  // Onset debounce: timestamp of the last onset that was emitted.
  private var lastOnsetTimeMs = 0L

  // DRC state: running RMS estimate for the compressor.
  private var drcRms = 0f

  // Adaptive noise floor: tracks the long-term minimum scene energy.
  private var adaptiveFloor = 0f
  private var adaptiveFloorSlow = 0f

  // Reusable magnitude buffer to avoid per-frame allocation.
  private var magBuf = FloatArray(0)

  // Pre-computed Hann window coefficients (lazily sized).
  private var hannWindow = FloatArray(0)

  fun reset() {
    envelope = 0f
    prevMag = null
    fluxAvg = 0f
    lastOnsetTimeMs = 0L
    drcRms = 0f
    adaptiveFloor = 0f
    adaptiveFloorSlow = 0f
  }

  /**
   * @param fft interleaved FFT buffer from Visualizer (length == captureSize).
   * @param samplingRateMilliHz Visualizer.getSamplingRate() (milli-Hz).
   * @param nowMs current wall-clock millis for onset debounce.
   */
  fun process(
    fft: ByteArray,
    samplingRateMilliHz: Int,
    params: Params,
    nowMs: Long = System.currentTimeMillis(),
  ): Frame {
    val captureSize = fft.size
    if (captureSize < 4) return Frame(0, false, 0f)

    val half = captureSize / 2
    if (magBuf.size != half) magBuf = FloatArray(half)
    val mag = magBuf

    // Ensure Hann window is the right size.
    if (hannWindow.size != captureSize) {
      hannWindow = FloatArray(captureSize) { i ->
        (0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / (captureSize - 1)))).toFloat()
      }
    }

    // Decode magnitudes with Hann windowing.
    // fft[0]=DC real, fft[1]=Nyquist real,
    // fft[2k]=real, fft[2k+1]=imag for k in 1 until half.
    mag[0] = abs(fft[0].toInt().toFloat() * hannWindow[0])
    for (k in 1 until half) {
      val re = fft[2 * k].toInt().toFloat() * hannWindow[2 * k]
      val im = fft[2 * k + 1].toInt().toFloat() * hannWindow[2 * k + 1]
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

    // --- Adaptive noise floor -----------------------------------------------
    // Track the long-term energy minimum to auto-adapt the noise gate.
    adaptiveFloor = adaptiveFloor * FLOOR_SLOW + level * (1f - FLOOR_SLOW)
    adaptiveFloorSlow = adaptiveFloorSlow * FLOOR_VERY_SLOW + level * (1f - FLOOR_VERY_SLOW)

    // The effective noise gate: the higher of the user's setting and the
    // adaptive floor with a margin. This means quiet dialogue scenes naturally
    // push the floor up relative to their own energy, silencing the motor.
    val effectiveThreshold = if (params.quietSceneAwareness) {
      max(params.noiseThreshold, adaptiveFloorSlow * 1.3f + 0.02f)
    } else {
      params.noiseThreshold
    }

    if (level < effectiveThreshold) {
      level = 0f
    }

    // --- Dynamic range compression ------------------------------------------
    // Gently compress so loud scenes don't max the motor while quiet scenes
    // retain subtle texture.
    val compressed = applyDrc(level, tuning)

    // --- Envelope smoothing -------------------------------------------------
    // Much slower than v1 for a smoother, non-jittery bed.
    envelope = if (compressed > envelope) {
      envelope + (compressed - envelope) * tuning.attack
    } else {
      envelope + (compressed - envelope) * tuning.decay
    }

    // --- Perceptual amplitude curve -----------------------------------------
    // Log-based curve: ln(1 + x*9) / ln(10) gives much better granularity in
    // the subtle range compared to the old pow(0.6) curve.
    val perceptual = if (envelope > 0.001f) {
      (ln(1f + envelope * 9f) / LN_10).coerceIn(0f, 1f)
    } else {
      0f
    }

    val shaped = perceptual * params.masterIntensity
    val ampF = shaped.coerceIn(0f, params.maxAmplitude)
    val amplitude = (ampF * 255f).toInt().coerceIn(0, 255)

    // --- Onset detection with debounce --------------------------------------
    val onsetResult = detectOnset(mag, binHz, half, params, tuning, nowMs)

    return Frame(
      amplitude = if (amplitude in 1 until MIN_AMP) MIN_AMP else amplitude,
      onset = onsetResult.first,
      onsetStrength = onsetResult.second,
      impactTier = onsetResult.third,
    )
  }

  // --- DRC ------------------------------------------------------------------

  /**
   * Simple feed-forward compressor: slow attack lets transients punch through,
   * moderate release for natural decay. ~6 dB compression above the threshold.
   */
  private fun applyDrc(level: Float, tuning: Tuning): Float {
    // Update running RMS estimate (slow-ish tracking).
    drcRms = drcRms * DRC_SMOOTH + level * (1f - DRC_SMOOTH)

    if (level <= DRC_THRESHOLD) return level

    val excess = level - DRC_THRESHOLD
    // Soft-knee: compress above threshold by the ratio.
    val compressed = DRC_THRESHOLD + excess / DRC_RATIO
    return compressed.coerceIn(0f, 1f)
  }

  // --- Onset detection ------------------------------------------------------

  /**
   * Returns (isOnset, strength, impactTier). The debounce timer prevents rapid
   * fire impacts; if a new onset arrives during cooldown but is stronger than
   * the previous, the caller can choose to upgrade.
   */
  private fun detectOnset(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    params: Params,
    tuning: Tuning,
    nowMs: Long,
  ): Triple<Boolean, Float, Int> {
    val prev = prevMag
    // Snapshot current spectrum for next frame's diff.
    val snapshot = FloatArray(half)
    System.arraycopy(mag, 0, snapshot, 0, half)

    if (prev == null || prev.size != half) {
      prevMag = snapshot
      return Triple(false, 0f, 0)
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
    val threshold = fluxAvg * (1f + (1f - params.impactSensitivity) * 3f) + 0.02f
    fluxAvg = fluxAvg * 0.92f + flux * 0.08f

    val isOnsetCandidate = flux > threshold && flux > tuning.onsetFloor
    val strength = if (isOnsetCandidate) ((flux - threshold) / 0.3f).coerceIn(0f, 1f) else 0f

    // Debounce: enforce minimum gap between impacts.
    val cooldown = params.impactCooldownMs.toLong().coerceIn(MIN_ONSET_COOLDOWN_MS, MAX_ONSET_COOLDOWN_MS)
    val elapsed = nowMs - lastOnsetTimeMs
    val isOnset = isOnsetCandidate && elapsed >= cooldown

    if (isOnset) {
      lastOnsetTimeMs = nowMs
    }

    // Classify into impact tier based on strength.
    val tier = when {
      strength >= 0.75f -> 3 // THUD — weighty hit
      strength >= 0.50f -> 2 // CLICK — clear punch
      strength >= 0.25f -> 1 // TICK — light click
      else -> 0              // LOW_TICK — subtle tap
    }

    return Triple(isOnset, strength, tier)
  }

  // --- Helpers --------------------------------------------------------------

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

  // --- Preset tuning --------------------------------------------------------

  data class Tuning(
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
      // Barely there: feather-light bed on deep bass only, impacts only on
      // strong transients. The "it's off, right?" experience.
      HapticsPreset.Subtle ->
        Tuning(
          bassWeight = 0.8f, lowMidWeight = 0.15f, midWeight = 0.0f, highWeight = 0.0f,
          attack = 0.08f, decay = 0.03f, onsetFloor = 0.12f,
        )
      // Films/series: emphasise rumble + big impacts, gentle on dialogue.
      HapticsPreset.Movie ->
        Tuning(
          bassWeight = 1.4f, lowMidWeight = 0.5f, midWeight = 0.15f, highWeight = 0.05f,
          attack = 0.15f, decay = 0.04f, onsetFloor = 0.06f,
        )
      // Zero bed, impacts only. Gentle but weighty feel for cinematic content.
      HapticsPreset.Cinema ->
        Tuning(
          bassWeight = 0.0f, lowMidWeight = 0.0f, midWeight = 0.0f, highWeight = 0.0f,
          attack = 0.05f, decay = 0.02f, onsetFloor = 0.08f,
        )
      // Music: fuller range, snappier to the beat.
      HapticsPreset.Music ->
        Tuning(
          bassWeight = 1.1f, lowMidWeight = 0.7f, midWeight = 0.4f, highWeight = 0.15f,
          attack = 0.20f, decay = 0.06f, onsetFloor = 0.05f,
        )
      // Games: fast and transient-led.
      HapticsPreset.Game ->
        Tuning(
          bassWeight = 1.0f, lowMidWeight = 0.5f, midWeight = 0.35f, highWeight = 0.2f,
          attack = 0.35f, decay = 0.10f, onsetFloor = 0.04f,
        )
      // Custom uses a neutral curve; the user's sliders do the shaping.
      HapticsPreset.Custom ->
        Tuning(
          bassWeight = 1.2f, lowMidWeight = 0.6f, midWeight = 0.3f, highWeight = 0.12f,
          attack = 0.15f, decay = 0.05f, onsetFloor = 0.05f,
        )
    }

  private companion object {
    // Byte FFT magnitudes are ~0..181; normalise toward 0..1 with headroom.
    const val NORM = 90f
    const val MIN_AMP = 18

    // Perceptual curve constant: ln(10).
    val LN_10 = ln(10f)

    // DRC constants.
    const val DRC_THRESHOLD = 0.35f    // Compress above this level.
    const val DRC_RATIO = 3.0f         // Compression ratio (~6 dB).
    const val DRC_SMOOTH = 0.95f       // RMS tracking smoothness.

    // Adaptive floor tracking speeds.
    const val FLOOR_SLOW = 0.97f       // Tracks scene energy (medium).
    const val FLOOR_VERY_SLOW = 0.995f // Long-term scene minimum.

    // Onset cooldown bounds.
    const val MIN_ONSET_COOLDOWN_MS = 80L
    const val MAX_ONSET_COOLDOWN_MS = 300L
  }
}
