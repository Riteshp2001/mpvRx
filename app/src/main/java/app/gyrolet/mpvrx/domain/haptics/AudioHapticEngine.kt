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
 * v3 — Dual-philosophy premium refinements:
 *
 *  Sony Xperia DVS path (Subtle / Movie / Cinema / Game / Custom):
 *   • Sub-bass isolation (20–80 Hz) for deep Xperia-style rumble
 *   • Multi-band DRC (low + full) so bass isn't crushed by dialogue
 *   • 5-tier impact system (LOW_TICK → TICK → CLICK → THUD → SPIN)
 *   • Dual-envelope bed (bass + full-range) for layered vibration
 *   • Impact chaining for rapid action sequences
 *
 *  Apple iOS CoreHaptics path (Music preset):
 *   • Beat-tracking onset pattern with musical swing tolerance
 *   • Melodic contour following via spectral centroid tracking
 *   • Rhythmic bed gating — vibration only on detected onsets
 *   • Very short cooldowns to catch every beat
 *   • Zero continuous rumble; crisp silence between taps
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
     * 0..4 impact tier for the current onset. Only meaningful when
     * [onset] is true. Used by [HapticsManager] to pick the right
     * vibration primitive.
     *  - 0 = subtle tap (LOW_TICK)
     *  - 1 = light click (TICK)
     *  - 2 = clear punch (CLICK)
     *  - 3 = weighty hit (THUD)
     *  - 4 = heavy slam (SPIN) — Sony Xperia only
     */
    val impactTier: Int = 0,
    /**
     * True when the current preset is Music (Apple mode) and the bed
     * should be gated (only active during onset windows).
     */
    val rhythmicGate: Boolean = false,
  )

  // --- State ----------------------------------------------------------------

  /** Smoothed envelope of the continuous "bed" vibration. */
  private var envelope = 0f

  /** Secondary bass-only envelope for Sony DVS layered feel. */
  private var bassEnvelope = 0f

  // Spectral-flux onset-detection state.
  private var prevMag: FloatArray? = null
  private var fluxAvg = 0f

  // Onset debounce: timestamp of the last onset that was emitted.
  private var lastOnsetTimeMs = 0L

  // DRC state: running RMS estimates for the 2-band compressor.
  private var drcRmsLow = 0f
  private var drcRmsFull = 0f

  // Adaptive noise floor: tracks the long-term minimum scene energy.
  private var adaptiveFloor = 0f
  private var adaptiveFloorSlow = 0f

  // Reusable magnitude buffer to avoid per-frame allocation.
  private var magBuf = FloatArray(0)

  // Pre-computed Hann window coefficients (lazily sized).
  private var hannWindow = FloatArray(0)

  // Apple Music mode: spectral centroid tracking for melodic contour.
  private var prevCentroid = 0f
  private var centroidEnvelope = 0f

  // Apple Music mode: rhythmic gate state.
  private var rhythmGateOpen = false
  private var rhythmGateCloseAt = 0L

  // Impact chaining state (Sony DVS): tracks consecutive rapid impacts.
  private var consecutiveImpacts = 0
  private var lastImpactStrength = 0f

  fun reset() {
    envelope = 0f
    bassEnvelope = 0f
    prevMag = null
    fluxAvg = 0f
    lastOnsetTimeMs = 0L
    drcRmsLow = 0f
    drcRmsFull = 0f
    adaptiveFloor = 0f
    adaptiveFloorSlow = 0f
    prevCentroid = 0f
    centroidEnvelope = 0f
    rhythmGateOpen = false
    rhythmGateCloseAt = 0L
    consecutiveImpacts = 0
    lastImpactStrength = 0f
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
    mag[0] = abs(fft[0].toInt().toFloat() * hannWindow[0])
    for (k in 1 until half) {
      val re = fft[2 * k].toInt().toFloat() * hannWindow[2 * k]
      val im = fft[2 * k + 1].toInt().toFloat() * hannWindow[2 * k + 1]
      mag[k] = sqrt(re * re + im * im)
    }

    val sampleRate = (samplingRateMilliHz / 1000f).coerceAtLeast(8000f)
    val binHz = sampleRate / captureSize

    val isAppleMusic = params.preset == HapticsPreset.Music

    return if (isAppleMusic) {
      processAppleMusicMode(mag, binHz, half, params, nowMs)
    } else {
      processSonyDvsMode(mag, binHz, half, params, nowMs)
    }
  }

  // =========================================================================
  // Sony Xperia DVS processing path
  // =========================================================================

  private fun processSonyDvsMode(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    params: Params,
    nowMs: Long,
  ): Frame {
    val tuning = tuningFor(params.preset)

    // Sub-bass (20–80 Hz) — Xperia DVS signature deep rumble.
    val subBass = bandAvg(mag, binHz, 20f, 80f, half) / NORM
    // Bass (80–200 Hz).
    val bass = bandAvg(mag, binHz, 80f, 200f, half) / NORM
    // Low-mid (200–600 Hz).
    val lowMid = bandAvg(mag, binHz, 200f, 600f, half) / NORM
    // Mid (600–2500 Hz).
    val mid = bandAvg(mag, binHz, 600f, 2500f, half) / NORM
    // High (2500–8000 Hz).
    val high = bandAvg(mag, binHz, 2500f, 8000f, half) / NORM

    // Bass gain: user 0..1 → 0.5..2.0 multiplier.
    val bassMul = 0.5f + params.bassGain * 1.5f

    // Combined low-frequency energy for the bass envelope.
    val lowLevel = (subBass * tuning.subBassWeight * bassMul +
      bass * tuning.bassWeight * bassMul).coerceIn(0f, 1f)

    // Full-range level.
    var fullLevel = (subBass * tuning.subBassWeight * bassMul +
      bass * tuning.bassWeight * bassMul +
      lowMid * tuning.lowMidWeight +
      mid * tuning.midWeight +
      high * tuning.highWeight).coerceIn(0f, 1f)

    // --- Adaptive noise floor -----------------------------------------------
    adaptiveFloor = adaptiveFloor * FLOOR_SLOW + fullLevel * (1f - FLOOR_SLOW)
    adaptiveFloorSlow = adaptiveFloorSlow * FLOOR_VERY_SLOW + fullLevel * (1f - FLOOR_VERY_SLOW)

    val effectiveThreshold = if (params.quietSceneAwareness) {
      max(params.noiseThreshold, adaptiveFloorSlow * 1.3f + 0.02f)
    } else {
      params.noiseThreshold
    }

    if (fullLevel < effectiveThreshold) {
      fullLevel = 0f
    }

    // --- Multi-band DRC (Sony DVS style) ------------------------------------
    val compressedLow = applyDrcBand(lowLevel, isDrcLow = true)
    val compressedFull = applyDrcBand(fullLevel, isDrcLow = false)

    // --- Dual envelope smoothing (Xperia layered feel) ----------------------
    bassEnvelope = if (compressedLow > bassEnvelope) {
      bassEnvelope + (compressedLow - bassEnvelope) * tuning.attack * 0.8f
    } else {
      bassEnvelope + (compressedLow - bassEnvelope) * tuning.decay * 0.6f
    }

    envelope = if (compressedFull > envelope) {
      envelope + (compressedFull - envelope) * tuning.attack
    } else {
      envelope + (compressedFull - envelope) * tuning.decay
    }

    // Blend bass and full envelopes: bass-heavy for the deep "rolling" feel.
    val blended = (bassEnvelope * 0.6f + envelope * 0.4f).coerceIn(0f, 1f)

    // --- Perceptual amplitude curve -----------------------------------------
    val perceptual = if (blended > 0.001f) {
      (ln(1f + blended * 9f) / LN_10).coerceIn(0f, 1f)
    } else {
      0f
    }

    val shaped = perceptual * params.masterIntensity
    val ampF = shaped.coerceIn(0f, params.maxAmplitude)
    val amplitude = (ampF * 255f).toInt().coerceIn(0, 255)

    // --- Onset detection with 5-tier classification --------------------------
    val onsetResult = detectOnsetSony(mag, binHz, half, params, tuning, nowMs)

    return Frame(
      amplitude = if (amplitude in 1 until MIN_AMP) MIN_AMP else amplitude,
      onset = onsetResult.first,
      onsetStrength = onsetResult.second,
      impactTier = onsetResult.third,
    )
  }

  // =========================================================================
  // Apple iOS CoreHaptics Music processing path
  // =========================================================================

  private fun processAppleMusicMode(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    params: Params,
    nowMs: Long,
  ): Frame {
    val tuning = APPLE_MUSIC_TUNING

    // For Apple Music mode we care about beat/rhythm, not bass rumble.
    val subBass = bandAvg(mag, binHz, 20f, 80f, half) / NORM
    val bass = bandAvg(mag, binHz, 80f, 200f, half) / NORM
    val lowMid = bandAvg(mag, binHz, 200f, 600f, half) / NORM
    val mid = bandAvg(mag, binHz, 600f, 2500f, half) / NORM
    val high = bandAvg(mag, binHz, 2500f, 8000f, half) / NORM

    // --- Melodic contour tracking (spectral centroid) -----------------------
    // Track how the frequency center-of-mass moves — Apple uses this to
    // create gentle amplitude modulation that "follows" the melody.
    val centroid = computeSpectralCentroid(mag, binHz, half)
    val centroidDelta = abs(centroid - prevCentroid)
    prevCentroid = centroid

    // Smooth the centroid movement into a gentle modulation signal.
    val centroidMod = (centroidDelta / 500f).coerceIn(0f, 0.15f)
    centroidEnvelope = centroidEnvelope * 0.85f + centroidMod * 0.15f

    // --- Beat-focused onset detection ---------------------------------------
    val onsetResult = detectOnsetApple(mag, binHz, half, params, tuning, nowMs)

    // --- Rhythmic bed gating ------------------------------------------------
    // Apple Music Haptics doesn't have continuous rumble. The "bed" only
    // activates during a brief window around each detected onset, creating
    // a pulsing feel that breathes with the rhythm.
    if (onsetResult.first) {
      rhythmGateOpen = true
      rhythmGateCloseAt = nowMs + APPLE_GATE_DURATION_MS
    } else if (nowMs >= rhythmGateCloseAt) {
      rhythmGateOpen = false
    }

    // Very minimal bed: only the melodic contour modulation + bass drops.
    val bassMul = 0.5f + params.bassGain * 1.5f
    val rawLevel = if (rhythmGateOpen) {
      // During gate-open: light amplitude from the onset energy.
      val onsetEnergy = (subBass * 0.3f * bassMul +
        bass * 0.2f * bassMul +
        centroidEnvelope * 0.5f).coerceIn(0f, 0.4f)
      onsetEnergy
    } else {
      // Gate closed: silence. This is the Apple signature.
      0f
    }

    // Very fast attack/decay — Apple taps are crisp with clean edges.
    envelope = if (rawLevel > envelope) {
      envelope + (rawLevel - envelope) * tuning.attack
    } else {
      envelope + (rawLevel - envelope) * tuning.decay
    }

    val shaped = envelope * params.masterIntensity
    val ampF = shaped.coerceIn(0f, params.maxAmplitude * 0.5f) // Cap lower for clean feel.
    val amplitude = (ampF * 255f).toInt().coerceIn(0, 255)

    return Frame(
      amplitude = if (amplitude in 1 until MIN_AMP_APPLE) 0 else amplitude,
      onset = onsetResult.first,
      onsetStrength = onsetResult.second,
      impactTier = onsetResult.third,
      rhythmicGate = true, // Always signal Apple mode to HapticsManager.
    )
  }

  // --- Multi-band DRC (Sony DVS) --------------------------------------------

  /**
   * Two-band compressor: separate tracking for low and full range so bass
   * doesn't get squashed by loud dialogue/music (Sony DVS design).
   */
  private fun applyDrcBand(level: Float, isDrcLow: Boolean): Float {
    if (isDrcLow) {
      drcRmsLow = drcRmsLow * DRC_SMOOTH + level * (1f - DRC_SMOOTH)
      if (level <= DRC_THRESHOLD_LOW) return level
      val excess = level - DRC_THRESHOLD_LOW
      return (DRC_THRESHOLD_LOW + excess / DRC_RATIO_LOW).coerceIn(0f, 1f)
    } else {
      drcRmsFull = drcRmsFull * DRC_SMOOTH + level * (1f - DRC_SMOOTH)
      if (level <= DRC_THRESHOLD_FULL) return level
      val excess = level - DRC_THRESHOLD_FULL
      return (DRC_THRESHOLD_FULL + excess / DRC_RATIO_FULL).coerceIn(0f, 1f)
    }
  }

  // --- Sony DVS onset detection (5-tier) ------------------------------------

  private fun detectOnsetSony(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    params: Params,
    tuning: Tuning,
    nowMs: Long,
  ): Triple<Boolean, Float, Int> {
    val prev = prevMag
    val snapshot = FloatArray(half)
    System.arraycopy(mag, 0, snapshot, 0, half)

    if (prev == null || prev.size != half) {
      prevMag = snapshot
      return Triple(false, 0f, 0)
    }

    // Sum of positive changes over ~20Hz..2kHz (wider than v2 for action).
    val loBin = max(1, (20f / binHz).toInt())
    val hiBin = min(half - 1, (2000f / binHz).toInt())
    var flux = 0f
    for (i in loBin..hiBin) {
      val d = mag[i] - prev[i]
      if (d > 0f) flux += d
    }
    flux /= (hiBin - loBin + 1).coerceAtLeast(1)
    flux /= NORM

    prevMag = snapshot

    // Adaptive threshold: tighter reaction for Sony DVS.
    val threshold = fluxAvg * (1f + (1f - params.impactSensitivity) * 2.5f) + 0.015f
    fluxAvg = fluxAvg * 0.90f + flux * 0.10f

    val isOnsetCandidate = flux > threshold && flux > tuning.onsetFloor
    val strength = if (isOnsetCandidate) ((flux - threshold) / 0.25f).coerceIn(0f, 1f) else 0f

    // Cooldown with impact chaining support.
    val cooldown = params.impactCooldownMs.toLong().coerceIn(MIN_ONSET_COOLDOWN_MS, MAX_ONSET_COOLDOWN_MS)
    val elapsed = nowMs - lastOnsetTimeMs
    val isOnset = isOnsetCandidate && elapsed >= cooldown

    if (isOnset) {
      lastOnsetTimeMs = nowMs
      // Track consecutive impacts for chaining.
      if (elapsed < cooldown * 2) {
        consecutiveImpacts = (consecutiveImpacts + 1).coerceAtMost(5)
      } else {
        consecutiveImpacts = 0
      }
      lastImpactStrength = strength
    }

    // 5-tier classification (Sony DVS).
    val tier = when {
      strength >= 0.85f -> 4 // SPIN — heavy slam (Sony exclusive)
      strength >= 0.65f -> 3 // THUD — weighty hit
      strength >= 0.45f -> 2 // CLICK — clear punch
      strength >= 0.22f -> 1 // TICK — light click
      else -> 0              // LOW_TICK — subtle tap
    }

    return Triple(isOnset, strength, tier)
  }

  // --- Apple Music onset detection ------------------------------------------

  private fun detectOnsetApple(
    mag: FloatArray,
    binHz: Float,
    half: Int,
    params: Params,
    tuning: Tuning,
    nowMs: Long,
  ): Triple<Boolean, Float, Int> {
    val prev = prevMag
    val snapshot = FloatArray(half)
    System.arraycopy(mag, 0, snapshot, 0, half)

    if (prev == null || prev.size != half) {
      prevMag = snapshot
      return Triple(false, 0f, 0)
    }

    // For Apple Music: focus on the rhythmic onset region (60–1.5kHz)
    // where beats, kicks, and snares live.
    val loBin = max(1, (60f / binHz).toInt())
    val hiBin = min(half - 1, (1500f / binHz).toInt())
    var flux = 0f
    for (i in loBin..hiBin) {
      val d = mag[i] - prev[i]
      if (d > 0f) flux += d
    }
    flux /= (hiBin - loBin + 1).coerceAtLeast(1)
    flux /= NORM

    prevMag = snapshot

    // Apple mode: higher sensitivity, lower threshold for catching every beat.
    val threshold = fluxAvg * (1f + (1f - params.impactSensitivity) * 1.8f) + 0.01f
    fluxAvg = fluxAvg * 0.88f + flux * 0.12f // Faster tracking for rhythm.

    val isOnsetCandidate = flux > threshold && flux > tuning.onsetFloor
    val strength = if (isOnsetCandidate) ((flux - threshold) / 0.2f).coerceIn(0f, 1f) else 0f

    // Apple mode: much shorter cooldown to catch rapid beats.
    val cooldown = params.impactCooldownMs.toLong()
      .coerceIn(APPLE_MIN_COOLDOWN_MS, APPLE_MAX_COOLDOWN_MS)
    val elapsed = nowMs - lastOnsetTimeMs
    val isOnset = isOnsetCandidate && elapsed >= cooldown

    if (isOnset) {
      lastOnsetTimeMs = nowMs
    }

    // Apple Music: only 3 tiers used (no SPIN, lighter feel).
    // Tier 0 = regular beat tap (TICK), Tier 1 = accented beat (CLICK),
    // Tier 2 = bass drop (THUD). No tier 3/4.
    val tier = when {
      strength >= 0.70f -> 3 // THUD — bass drop only
      strength >= 0.40f -> 2 // CLICK — accented beat
      strength >= 0.15f -> 1 // TICK — regular beat
      else -> 0              // LOW_TICK — ghost note
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

  /**
   * Compute the spectral centroid (frequency center-of-mass) for the
   * mid-range, used by Apple Music mode for melodic contour tracking.
   */
  private fun computeSpectralCentroid(
    mag: FloatArray,
    binHz: Float,
    half: Int,
  ): Float {
    val loBin = max(1, (300f / binHz).toInt())
    val hiBin = min(half - 1, (3000f / binHz).toInt())
    if (hiBin < loBin) return 0f

    var weightedSum = 0f
    var totalMag = 0f
    for (i in loBin..hiBin) {
      val freq = i * binHz
      weightedSum += freq * mag[i]
      totalMag += mag[i]
    }
    return if (totalMag > 0.001f) weightedSum / totalMag else 0f
  }

  // --- Preset tuning --------------------------------------------------------

  data class Tuning(
    val subBassWeight: Float,   // Sub-bass (20–80 Hz) — Sony DVS deep rumble.
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
      // Barely there: feather-light bed on deep bass only.
      HapticsPreset.Subtle ->
        Tuning(
          subBassWeight = 0.6f, bassWeight = 0.5f,
          lowMidWeight = 0.1f, midWeight = 0.0f, highWeight = 0.0f,
          attack = 0.06f, decay = 0.025f, onsetFloor = 0.14f,
        )
      // Films: Sony DVS deep rumble + heavy impacts, gentle on dialogue.
      HapticsPreset.Movie ->
        Tuning(
          subBassWeight = 1.8f, bassWeight = 1.2f,
          lowMidWeight = 0.4f, midWeight = 0.12f, highWeight = 0.04f,
          attack = 0.12f, decay = 0.035f, onsetFloor = 0.05f,
        )
      // Cinema: zero bed, impacts only with deep sub-bass weighting.
      HapticsPreset.Cinema ->
        Tuning(
          subBassWeight = 0.0f, bassWeight = 0.0f,
          lowMidWeight = 0.0f, midWeight = 0.0f, highWeight = 0.0f,
          attack = 0.04f, decay = 0.015f, onsetFloor = 0.07f,
        )
      // Music (Apple Taptic) — tuning is in APPLE_MUSIC_TUNING constant.
      HapticsPreset.Music -> APPLE_MUSIC_TUNING
      // Games: Sony DVS fast transients, wide spectrum.
      HapticsPreset.Game ->
        Tuning(
          subBassWeight = 1.3f, bassWeight = 0.9f,
          lowMidWeight = 0.45f, midWeight = 0.3f, highWeight = 0.18f,
          attack = 0.30f, decay = 0.08f, onsetFloor = 0.035f,
        )
      // Custom: neutral Sony DVS curve.
      HapticsPreset.Custom ->
        Tuning(
          subBassWeight = 1.4f, bassWeight = 1.0f,
          lowMidWeight = 0.5f, midWeight = 0.25f, highWeight = 0.10f,
          attack = 0.14f, decay = 0.045f, onsetFloor = 0.045f,
        )
    }

  private companion object {
    // Byte FFT magnitudes are ~0..181; normalise toward 0..1 with headroom.
    const val NORM = 90f
    const val MIN_AMP = 18
    const val MIN_AMP_APPLE = 25 // Higher gate for cleaner silence in Apple mode.

    // Perceptual curve constant: ln(10).
    val LN_10 = ln(10f)

    // Multi-band DRC constants (Sony DVS style).
    const val DRC_THRESHOLD_LOW = 0.30f   // Low-band compress threshold (lower = more bass).
    const val DRC_THRESHOLD_FULL = 0.38f  // Full-range compress threshold.
    const val DRC_RATIO_LOW = 2.5f        // Gentle bass compression.
    const val DRC_RATIO_FULL = 3.5f       // Moderate full-range compression.
    const val DRC_SMOOTH = 0.94f          // RMS tracking smoothness.

    // Adaptive floor tracking speeds.
    const val FLOOR_SLOW = 0.97f
    const val FLOOR_VERY_SLOW = 0.995f

    // Sony DVS onset cooldown bounds.
    const val MIN_ONSET_COOLDOWN_MS = 80L
    const val MAX_ONSET_COOLDOWN_MS = 300L

    // Apple Music mode constants.
    const val APPLE_MIN_COOLDOWN_MS = 50L   // Catch rapid beats (up to ~200 BPM).
    const val APPLE_MAX_COOLDOWN_MS = 180L  // Still responsive for slow songs.
    const val APPLE_GATE_DURATION_MS = 80L  // How long the rhythmic gate stays open.

    // Apple Music tuning: emphasis on rhythm, not bass rumble.
    val APPLE_MUSIC_TUNING = Tuning(
      subBassWeight = 0.3f, bassWeight = 0.4f,
      lowMidWeight = 0.5f, midWeight = 0.3f, highWeight = 0.1f,
      attack = 0.40f, decay = 0.15f, onsetFloor = 0.03f,
    )
  }
}
