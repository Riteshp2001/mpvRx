package app.gyrolet.mpvrx.domain.haptics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.preferences.HapticsEngineMode
import app.gyrolet.mpvrx.preferences.HapticsPreferences
import app.gyrolet.mpvrx.preferences.HapticsPreset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Owns the live audio-to-vibration pipeline.
 *
 * It taps the device audio output mix with [Visualizer] (session 0 = global
 * mix), feeds each FFT frame to [AudioHapticEngine], and drives the vibration
 * motor so the user can feel bass and impacts while watching video.
 *
 * Lifecycle is driven by [PlayerActivity]: [onPlaybackActive] while a video is
 * playing, [stop] on pause/background, [release] on teardown.
 *
 * Capturing the output mix requires the RECORD_AUDIO runtime permission; the
 * manager never starts without it.
 *
 * v3 — Dual-philosophy premium haptics:
 *
 *  Sony Xperia DVS (non-Music presets):
 *   • 5-tier impact primitives (LOW_TICK → TICK → CLICK → THUD → SPIN)
 *   • 5-segment layered bed waveform (soft attack → ramp → peak → sustain → slow decay)
 *   • Impact chaining for rapid action sequences
 *   • Graceful motor ramp-down with extended fade
 *   • Volume-awareness (optional auto-scale with media volume)
 *
 *  Apple iOS CoreHaptics (Music preset):
 *   • Rhythmic tap-only pattern: TICK for beats, CLICK for accents, THUD for bass drops
 *   • Zero bed vibration between taps (clean silence = Apple signature)
 *   • Micro-fade amplitude envelopes on taps for polished Taptic feel
 *   • Melodic contour modulation from spectral centroid tracking
 *
 *  Bug fix:
 *   • Delayed Visualizer start + retry to fix race condition where Visualizer
 *     captures silence when attached before audio routing is established
 *   • restartAfterAudioReconfig() for mpv audio session changes
 */
class HapticsManager(
  private val context: Context,
  private val preferences: HapticsPreferences,
) {
  private val engine = AudioHapticEngine()

  private val vibrator: Vibrator? = resolveVibrator(context)
  private val audioManager: AudioManager? =
    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private var visualizer: Visualizer? = null
  private val running = AtomicBoolean(false)

  // Motor capabilities (resolved once).
  private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true
  private val supportsPrimitives: Boolean =
    vibrator?.areAllPrimitivesSupported(
      VibrationEffect.Composition.PRIMITIVE_THUD,
      VibrationEffect.Composition.PRIMITIVE_CLICK,
    ) == true

  /** Newer primitives for richer tiered impacts. */
  private val supportsTickPrimitive: Boolean =
    vibrator?.areAllPrimitivesSupported(
      VibrationEffect.Composition.PRIMITIVE_TICK,
    ) == true

  private val supportsLowTickPrimitive: Boolean =
    runCatching {
      if (Build.VERSION.SDK_INT >= 31) {
        vibrator?.areAllPrimitivesSupported(
          VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        ) == true
      } else false
    }.getOrDefault(false)

  /** SPIN primitive for the heaviest Sony DVS impacts (API 31+). */
  private val supportsSpinPrimitive: Boolean =
    runCatching {
      if (Build.VERSION.SDK_INT >= 31) {
        vibrator?.areAllPrimitivesSupported(
          VibrationEffect.Composition.PRIMITIVE_SPIN,
        ) == true
      } else false
    }.getOrDefault(false)

  private val mediaAttributes: VibrationAttributes =
    VibrationAttributes.Builder()
      .setUsage(VibrationAttributes.USAGE_MEDIA)
      .build()

  // Bed vibration state — tracked to avoid unnecessary re-issues.
  private var lastBedAmp = -1
  private var lastBedAt = 0L

  // Impact cooldown state.
  private var lastImpactAt = 0L
  private var suppressBedUntil = 0L

  // Graceful ramp-down: count of consecutive zero-amplitude frames.
  private var zeroFrameCount = 0

  // Bug fix: Visualizer verification — counts silent frames after startup.
  private var startupFrameCount = 0
  private var startupHadSignal = false
  private var visualizerRetryCount = 0
  private var startRetryRunnable: Runnable? = null
  private var startupVerifyRunnable: Runnable? = null

  /** True if the motor exists and we can vibrate at all. */
  fun isSupported(): Boolean = vibrator?.hasVibrator() == true

  /** Whether the RECORD_AUDIO permission needed for output capture is granted. */
  fun hasAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED

  /**
   * Start the engine if the feature is enabled, supported and permitted.
   * Safe to call repeatedly; it is a no-op when already running.
   */
  @Synchronized
  fun onPlaybackActive() {
    if (running.get()) return
    if (!preferences.enabled.get()) return
    if (!isSupported() || !hasAudioPermission()) return

    startVisualizerAndEngine()
  }

  /**
   * Restart the haptics engine after an audio session reconfiguration.
   * mpv may switch audio tracks during playback, invalidating the
   * current Visualizer session.
   */
  @Synchronized
  fun restartAfterAudioReconfig() {
    if (!running.get()) return
    if (!preferences.enabled.get()) return

    Log.d(TAG, "Audio reconfigured, restarting haptics engine")
    stopInternal()
    // Small delay to let the new audio route settle.
    mainHandler.postDelayed({
      synchronized(this) {
        if (!running.get() && preferences.enabled.get()) {
          startVisualizerAndEngine()
        }
      }
    }, AUDIO_RECONFIG_DELAY_MS)
  }

  /** Stop the engine and silence the motor. Safe to call when not running. */
  @Synchronized
  fun stop() {
    startRetryRunnable?.let { mainHandler.removeCallbacks(it) }
    startupVerifyRunnable?.let { mainHandler.removeCallbacks(it) }
    stopInternal()
  }

  /** Full teardown. */
  @Synchronized
  fun release() {
    stop()
  }

  /**
   * Play a short illustrative pattern so the user can feel their current
   * settings from the settings screen without needing playback.
   *
   * v3: Adapts pattern based on preset — Sony DVS escalating rumble for
   * non-Music, Apple rhythmic tapping for Music.
   */
  fun previewTest(): Boolean {
    val vib = vibrator ?: return false
    if (!vib.hasVibrator()) return false

    val intensity = (preferences.masterIntensity.get() / 100f).coerceIn(0.1f, 1f)
    val maxAmp = (preferences.maxAmplitude.get() / 100f).coerceIn(0.1f, 1f)
    val mode = preferences.engineMode.get()
    val preset = preferences.preset.get()
    val isAppleMusic = preset == HapticsPreset.Music

    runCatching {
      if (mode == HapticsEngineMode.Full && supportsPrimitives) {
        val scale = (intensity * maxAmp).coerceIn(0f, 1f)

        if (isAppleMusic) {
          previewAppleMusic(vib, scale)
        } else {
          previewSonyDvs(vib, scale)
        }
      } else {
        // Amplitude-modulated bed: gentle ramp up, hold, pulse.
        val peak = (255 * intensity * maxAmp).toInt().coerceIn(1, 255)
        val timings = longArrayOf(60, 80, 60, 100, 60, 120)
        val amps = intArrayOf(
          peak / 6,
          peak / 4,
          peak / 3,
          peak * 2 / 3,
          peak / 3,
          peak,
        )
        val effect =
          if (hasAmplitudeControl) {
            VibrationEffect.createWaveform(timings, amps, -1)
          } else {
            VibrationEffect.createWaveform(timings, -1)
          }
        vib.vibrate(effect, mediaAttributes)
      }
    }.onFailure { Log.w(TAG, "preview failed", it) }
    return true
  }

  // -- internals -------------------------------------------------------------

  // Mutable params cache — refreshed periodically within processFrame.
  @Volatile
  private var cachedParams = currentParams()
  @Volatile
  private var cachedMode = currentEngineMode()
  @Volatile
  private var cachedIsAppleMusic = false
  private var paramRefreshCounter = 0

  /**
   * Starts the Visualizer and begins FFT capture. Contains the retry logic
   * that fixes the bug where haptics produce no vibration during video playback.
   */
  private fun startVisualizerAndEngine() {
    val vis =
      runCatching {
        Visualizer(GLOBAL_AUDIO_SESSION).apply {
          val sizeRange = Visualizer.getCaptureSizeRange()
          captureSize = sizeRange[1].coerceAtMost(MAX_CAPTURE_SIZE)
          enabled = true
        }
      }.getOrElse { e ->
        Log.w(TAG, "Failed to start Visualizer for haptics", e)
        // Retry once after a delay (audio may not be routed yet).
        if (visualizerRetryCount < MAX_VISUALIZER_RETRIES) {
          visualizerRetryCount++
          val retry = Runnable {
            synchronized(this) {
              if (!running.get() && preferences.enabled.get()) {
                startVisualizerAndEngine()
              }
            }
          }
          startRetryRunnable = retry
          mainHandler.postDelayed(retry, VISUALIZER_RETRY_DELAY_MS)
        }
        return
      }

    visualizer = vis
    engine.reset()
    lastBedAmp = -1
    lastBedAt = 0L
    lastImpactAt = 0L
    suppressBedUntil = 0L
    zeroFrameCount = 0
    startupFrameCount = 0
    startupHadSignal = false
    running.set(true)

    // Refresh cached params.
    cachedParams = currentParams()
    cachedMode = currentEngineMode()
    cachedIsAppleMusic = preferences.preset.get() == HapticsPreset.Music

    // Register the FFT data capture listener.
    val captureRate = Visualizer.getMaxCaptureRate().coerceAtMost(MAX_CAPTURE_RATE)
    vis.setDataCaptureListener(
      object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(
          visualizer: Visualizer?,
          waveform: ByteArray?,
          samplingRate: Int,
        ) {
          // Not used — we only need FFT.
        }

        override fun onFftDataCapture(
          visualizer: Visualizer?,
          fft: ByteArray?,
          samplingRate: Int,
        ) {
          if (!running.get() || fft == null) return
          processFrame(fft, samplingRate)
        }
      },
      captureRate,
      false, // waveform capture: off
      true,  // FFT capture: on
    )

    // Bug fix: Schedule a verification check. If the Visualizer captured only
    // silence after startup, it likely attached before audio was routed.
    // In that case, restart after a delay.
    val verify = Runnable {
      synchronized(this) {
        if (running.get() && !startupHadSignal && startupFrameCount > STARTUP_CHECK_FRAMES) {
          Log.w(TAG, "Visualizer captured silence after startup — restarting")
          stopInternal()
          if (visualizerRetryCount < MAX_VISUALIZER_RETRIES) {
            visualizerRetryCount++
            startVisualizerAndEngine()
          }
        }
      }
    }
    startupVerifyRunnable = verify
    mainHandler.postDelayed(verify, STARTUP_VERIFY_DELAY_MS)

    Log.d(TAG, "Haptics engine started (amplitude=$hasAmplitudeControl " +
      "primitives=$supportsPrimitives tick=$supportsTickPrimitive " +
      "lowTick=$supportsLowTickPrimitive spin=$supportsSpinPrimitive " +
      "appleMode=$cachedIsAppleMusic rate=${captureRate}mHz " +
      "retry=$visualizerRetryCount)")
  }

  /**
   * Called on the Visualizer callback thread for each FFT frame.
   */
  private fun processFrame(fft: ByteArray, samplingRate: Int) {
    val now = System.currentTimeMillis()

    // Bug fix: track whether we've received any actual audio signal.
    if (!startupHadSignal) {
      startupFrameCount++
      // Check if the FFT has any meaningful data (not all zeros).
      var hasSignal = false
      for (i in 2 until (fft.size / 2).coerceAtMost(32)) {
        if (abs(fft[i].toInt()) > 2) {
          hasSignal = true
          break
        }
      }
      if (hasSignal) {
        startupHadSignal = true
        visualizerRetryCount = 0 // Reset retry counter on success.
      }
    }

    // Refresh tuning periodically so slider changes apply live.
    if (paramRefreshCounter++ % PARAM_REFRESH_FRAMES == 0) {
      cachedParams = currentParams()
      cachedMode = currentEngineMode()
      cachedIsAppleMusic = cachedParams.preset == HapticsPreset.Music
    }

    val params = cachedParams
    val mode = cachedMode
    val isAppleMusic = cachedIsAppleMusic

    // Volume-awareness: scale down when media volume is low.
    val volumeScale = if (preferences.volumeAware.get()) {
      computeVolumeScale()
    } else {
      1f
    }
    if (volumeScale <= 0f) return // Muted — skip entirely.

    val frame = engine.process(fft, samplingRate, params, now)

    val isFull = mode == HapticsEngineMode.Full

    if (isAppleMusic) {
      // Apple Music mode: crisp taps on onsets, no continuous bed.
      processAppleMusicFrame(frame, params, volumeScale, isFull, now)
    } else {
      // Sony DVS mode: layered bed + tiered impacts.
      processSonyDvsFrame(frame, params, volumeScale, isFull, now)
    }
  }

  // =========================================================================
  // Sony Xperia DVS motor driving
  // =========================================================================

  private fun processSonyDvsFrame(
    frame: AudioHapticEngine.Frame,
    params: AudioHapticEngine.Params,
    volumeScale: Float,
    isFull: Boolean,
    now: Long,
  ) {
    if (isFull && frame.onset && supportsPrimitives && frame.onsetStrength > 0f) {
      val sinceLast = now - lastImpactAt
      val cooldown = params.impactCooldownMs.toLong().coerceIn(80L, 300L)
      if (sinceLast >= cooldown) {
        fireImpactSony(frame.onsetStrength, frame.impactTier, params, volumeScale)
        lastImpactAt = now
        suppressBedUntil = now + IMPACT_HOLD_MS
        lastBedAmp = -1
      }
    } else if (now >= suppressBedUntil) {
      val scaledAmplitude = (frame.amplitude * volumeScale).toInt().coerceIn(0, 255)
      driveBedSony(scaledAmplitude, now)
    }
  }

  /**
   * Drive the Sony DVS continuous "bed" vibration with a 5-segment layered
   * waveform: soft attack → ramp → peak → sustain → slow decay.
   * This gives the "rolling" vibration feel that Xperia is known for.
   */
  private fun driveBedSony(
    amplitude: Int,
    now: Long,
  ) {
    val vib = vibrator ?: return

    if (amplitude <= 0) {
      // Graceful ramp-down: extended fade for smoother Sony feel.
      zeroFrameCount++
      if (lastBedAmp > 0 && zeroFrameCount <= RAMP_DOWN_FRAMES) {
        val fadedAmp = (lastBedAmp * (1f - zeroFrameCount.toFloat() / RAMP_DOWN_FRAMES))
          .toInt().coerceIn(0, 255)
        if (fadedAmp > MIN_BED_AMP && hasAmplitudeControl) {
          runCatching {
            val effect = VibrationEffect.createOneShot(BED_DURATION_MS, fadedAmp)
            vib.vibrate(effect, mediaAttributes)
          }
          return
        }
      }
      if (lastBedAmp != 0) {
        runCatching { vib.cancel() }
        lastBedAmp = 0
        lastBedAt = now
      }
      return
    }

    zeroFrameCount = 0

    val changed = abs(amplitude - lastBedAmp) >= AMP_CHANGE_THRESHOLD
    val stale = now - lastBedAt >= BED_REFRESH_MS
    if (!changed && !stale) return

    runCatching {
      val effect = if (hasAmplitudeControl) {
        // 5-segment Sony DVS layered bed: soft attack → ramp → peak → sustain → slow decay.
        val softAttackAmp = (amplitude * 0.35f).toInt().coerceIn(MIN_BED_AMP, 255)
        val rampAmp = (amplitude * 0.65f).toInt().coerceIn(MIN_BED_AMP, 255)
        val peakAmp = amplitude.coerceIn(MIN_BED_AMP, 255)
        val sustainAmp = (amplitude * 0.8f).toInt().coerceIn(MIN_BED_AMP, 255)
        val decayAmp = (amplitude * 0.3f).toInt().coerceIn(MIN_BED_AMP, 255)

        VibrationEffect.createWaveform(
          longArrayOf(DVS_SOFT_ATTACK_MS, DVS_RAMP_MS, DVS_PEAK_MS, DVS_SUSTAIN_MS, DVS_DECAY_MS),
          intArrayOf(softAttackAmp, rampAmp, peakAmp, sustainAmp, decayAmp),
          -1,
        )
      } else {
        if (amplitude > 96) {
          VibrationEffect.createOneShot(BED_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
          return
        }
      }
      vib.vibrate(effect, mediaAttributes)
    }.onFailure { Log.w(TAG, "bed vibrate failed", it) }

    lastBedAmp = amplitude
    lastBedAt = now
  }

  /**
   * Fire a Sony DVS impact using the 5-tier primitive system.
   * Tier 4 uses SPIN for the heaviest, most resonant hits.
   */
  private fun fireImpactSony(
    strength: Float,
    tier: Int,
    params: AudioHapticEngine.Params,
    volumeScale: Float,
  ) {
    val vib = vibrator ?: return
    val baseScale = (strength * params.masterIntensity * params.maxAmplitude * volumeScale)
      .coerceIn(0.02f, 1f)

    runCatching {
      val composition = VibrationEffect.startComposition()

      when (tier) {
        0 -> {
          // Subtle tap — barely perceptible.
          val scale = (baseScale * 0.25f).coerceIn(0.02f, 0.35f)
          if (supportsLowTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.4f)
          }
        }
        1 -> {
          // Light click — noticeable but gentle.
          val scale = (baseScale * 0.4f).coerceIn(0.04f, 0.50f)
          if (supportsTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.55f)
          }
        }
        2 -> {
          // Clear punch — satisfying click.
          val scale = (baseScale * 0.6f).coerceIn(0.08f, 0.70f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
        }
        3 -> {
          // Weighty hit — deep thud with controlled intensity.
          val scale = (baseScale * 0.75f).coerceIn(0.12f, 0.82f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
        }
        4 -> {
          // Heavy slam — Sony DVS exclusive deep resonant vibration.
          val scale = (baseScale * 0.88f).coerceIn(0.18f, 0.92f)
          if (supportsSpinPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SPIN, scale)
          } else {
            // Fallback: double-thud for devices without SPIN.
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * 0.9f)
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale * 0.5f, 30)
          }
        }
      }

      vib.vibrate(composition.compose(), mediaAttributes)
    }.onFailure { Log.w(TAG, "impact vibrate failed", it) }
  }

  // =========================================================================
  // Apple iOS CoreHaptics Music mode motor driving
  // =========================================================================

  private fun processAppleMusicFrame(
    frame: AudioHapticEngine.Frame,
    params: AudioHapticEngine.Params,
    volumeScale: Float,
    isFull: Boolean,
    now: Long,
  ) {
    if (frame.onset && frame.onsetStrength > 0f) {
      if (isFull && supportsPrimitives) {
        fireImpactApple(frame.onsetStrength, frame.impactTier, params, volumeScale)
      } else {
        // Fallback: short amplitude tap.
        fireAppleFallbackTap(frame.onsetStrength, params, volumeScale)
      }
      lastImpactAt = now
      // In Apple mode, NO bed suppression — there's no bed to suppress.
    }
    // Apple mode: no continuous bed vibration. The motor is silent between taps.
    // If the engine produces a bed amplitude, we intentionally ignore it.
  }

  /**
   * Apple Music mode impact: crisp, clean taps that pulse with the rhythm.
   * Only 3 practical tiers: TICK (beat), CLICK (accent), THUD (bass drop).
   * The key difference from Sony is the intensity curve — Apple uses
   * lower peak intensity with sharper edges for a "polished" feel.
   */
  private fun fireImpactApple(
    strength: Float,
    tier: Int,
    params: AudioHapticEngine.Params,
    volumeScale: Float,
  ) {
    val vib = vibrator ?: return
    // Apple uses more moderate scaling — cleaner, not overwhelming.
    val baseScale = (strength * params.masterIntensity * params.maxAmplitude * volumeScale * 0.75f)
      .coerceIn(0.02f, 0.85f)

    runCatching {
      val composition = VibrationEffect.startComposition()

      when {
        tier >= 3 -> {
          // Bass drop — the only time Apple uses a heavy tap.
          val scale = (baseScale * 0.7f).coerceIn(0.10f, 0.72f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
        }
        tier == 2 -> {
          // Accented beat — crisp click, the "punch" of the rhythm.
          val scale = (baseScale * 0.5f).coerceIn(0.06f, 0.55f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
        }
        tier == 1 -> {
          // Regular beat — gentle tick that follows the groove.
          val scale = (baseScale * 0.35f).coerceIn(0.03f, 0.40f)
          if (supportsTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.5f)
          }
        }
        else -> {
          // Ghost note — barely perceptible tap for subtle rhythmic texture.
          val scale = (baseScale * 0.18f).coerceIn(0.02f, 0.22f)
          if (supportsLowTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
          } else if (supportsTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale * 0.5f)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.3f)
          }
        }
      }

      vib.vibrate(composition.compose(), mediaAttributes)
    }.onFailure { Log.w(TAG, "Apple Music impact failed", it) }
  }

  /**
   * Fallback tap for devices without composition primitives in Apple Music mode.
   * Uses a very short, crisp one-shot vibration.
   */
  private fun fireAppleFallbackTap(
    strength: Float,
    params: AudioHapticEngine.Params,
    volumeScale: Float,
  ) {
    val vib = vibrator ?: return
    val amp = (strength * params.masterIntensity * params.maxAmplitude * volumeScale * 255f * 0.6f)
      .toInt().coerceIn(20, 180)
    runCatching {
      val effect = if (hasAmplitudeControl) {
        VibrationEffect.createOneShot(APPLE_TAP_DURATION_MS, amp)
      } else {
        VibrationEffect.createOneShot(APPLE_TAP_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
      }
      vib.vibrate(effect, mediaAttributes)
    }
  }

  // =========================================================================
  // Preview patterns
  // =========================================================================

  /**
   * Sony DVS preview: escalating 5-tier pattern showing the full range
   * from subtle tap to heavy slam.
   */
  private fun previewSonyDvs(vib: Vibrator, scale: Float) {
    val composition = VibrationEffect.startComposition()

    // Tier 0 — subtle tap
    if (supportsLowTickPrimitive) {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
        scale * 0.25f,
      )
    } else {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        scale * 0.12f,
      )
    }

    // Tier 1 — light click (after 180ms gap)
    if (supportsTickPrimitive) {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        scale * 0.45f,
        180,
      )
    } else {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        scale * 0.30f,
        180,
      )
    }

    // Tier 2 — clear punch (after 180ms gap)
    composition.addPrimitive(
      VibrationEffect.Composition.PRIMITIVE_CLICK,
      scale * 0.65f,
      180,
    )

    // Tier 3 — weighty thud (after 180ms gap)
    composition.addPrimitive(
      VibrationEffect.Composition.PRIMITIVE_THUD,
      scale * 0.78f,
      180,
    )

    // Tier 4 — heavy slam / spin (after 200ms gap)
    if (supportsSpinPrimitive) {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_SPIN,
        scale * 0.88f,
        200,
      )
    } else {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_THUD,
        scale * 0.85f,
        200,
      )
    }

    vib.vibrate(composition.compose(), mediaAttributes)
  }

  /**
   * Apple Music preview: rhythmic tapping pattern that mimics a 4/4 beat.
   * Simulates: kick, hi-hat, snare, hi-hat — the "tap-along" signature.
   */
  private fun previewAppleMusic(vib: Vibrator, scale: Float) {
    val composition = VibrationEffect.startComposition()

    // Beat 1 — kick (THUD, moderate)
    composition.addPrimitive(
      VibrationEffect.Composition.PRIMITIVE_THUD,
      scale * 0.55f,
    )

    // Beat 2 — hi-hat (TICK, light)
    if (supportsTickPrimitive) {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        scale * 0.25f,
        200,
      )
    } else {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        scale * 0.15f,
        200,
      )
    }

    // Beat 3 — snare (CLICK, accent)
    composition.addPrimitive(
      VibrationEffect.Composition.PRIMITIVE_CLICK,
      scale * 0.45f,
      200,
    )

    // Beat 4 — hi-hat (TICK, light)
    if (supportsTickPrimitive) {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_TICK,
        scale * 0.25f,
        200,
      )
    } else {
      composition.addPrimitive(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        scale * 0.15f,
        200,
      )
    }

    // Beat 5 — bass drop (THUD, heavy for finale)
    composition.addPrimitive(
      VibrationEffect.Composition.PRIMITIVE_THUD,
      scale * 0.68f,
      250,
    )

    vib.vibrate(composition.compose(), mediaAttributes)
  }

  // =========================================================================
  // Shared internals
  // =========================================================================

  /**
   * Compute a 0..1 scale factor based on the current media volume.
   * Muted returns 0 (no haptics). Below ~20% returns a reduced value.
   */
  private fun computeVolumeScale(): Float {
    val am = audioManager ?: return 1f
    return runCatching {
      val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
      val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
      if (max <= 0) return@runCatching 1f
      val ratio = current.toFloat() / max
      when {
        ratio <= 0f -> 0f          // Muted: no haptics at all.
        ratio < 0.2f -> ratio * 2f // Below 20%: reduced (0..0.4 range).
        else -> 0.4f + ratio * 0.6f // 20%+: 0.52..1.0 range.
      }
    }.getOrDefault(1f)
  }

  private fun currentParams(): AudioHapticEngine.Params =
    AudioHapticEngine.Params(
      masterIntensity = preferences.masterIntensity.get() / 100f,
      bassGain = preferences.bassGain.get() / 100f,
      impactSensitivity = preferences.impactSensitivity.get() / 100f,
      noiseThreshold = (preferences.noiseThreshold.get() / 100f) * 0.5f,
      maxAmplitude = preferences.maxAmplitude.get() / 100f,
      preset = preferences.preset.get(),
      quietSceneAwareness = preferences.quietSceneAwareness.get(),
      impactCooldownMs = preferences.impactCooldown.get(),
    )

  /** Resolve the engine mode, auto-downgrading Full -> Standard if unsupported. */
  private fun currentEngineMode(): HapticsEngineMode {
    val mode = preferences.engineMode.get()
    return if (mode == HapticsEngineMode.Full && (!supportsPrimitives || !hasAmplitudeControl)) {
      HapticsEngineMode.Standard
    } else {
      mode
    }
  }

  private fun stopInternal() {
    if (!running.getAndSet(false)) {
      releaseVisualizer()
      return
    }
    releaseVisualizer()
    runCatching { vibrator?.cancel() }
  }

  private fun releaseVisualizer() {
    visualizer?.let { v ->
      runCatching { v.setDataCaptureListener(null, 0, false, false) }
      runCatching { v.enabled = false }
      runCatching { v.release() }
    }
    visualizer = null
  }

  private companion object {
    const val TAG = "HapticsManager"
    const val GLOBAL_AUDIO_SESSION = 0
    const val MAX_CAPTURE_SIZE = 1024
    const val MAX_CAPTURE_RATE = 20000 // 20 kHz max callback rate.
    const val PARAM_REFRESH_FRAMES = 8

    // Bed vibration timing.
    const val BED_DURATION_MS = 140L       // Total bed waveform duration.
    const val BED_REFRESH_MS = 85L         // Refresh interval.
    const val AMP_CHANGE_THRESHOLD = 10    // Hysteresis.
    const val MIN_BED_AMP = 10             // Minimum bed amplitude to issue.

    // Sony DVS 5-segment bed waveform timings.
    const val DVS_SOFT_ATTACK_MS = 15L
    const val DVS_RAMP_MS = 25L
    const val DVS_PEAK_MS = 30L
    const val DVS_SUSTAIN_MS = 40L
    const val DVS_DECAY_MS = 30L

    // Graceful ramp-down: extended for smoother Sony feel.
    const val RAMP_DOWN_FRAMES = 4

    // Impact hold: suppress bed after an impact fires.
    const val IMPACT_HOLD_MS = 110L

    // Apple Music mode: short tap duration for fallback vibration.
    const val APPLE_TAP_DURATION_MS = 35L

    // Bug fix: Visualizer verification and retry.
    const val STARTUP_VERIFY_DELAY_MS = 600L
    const val STARTUP_CHECK_FRAMES = 5
    const val MAX_VISUALIZER_RETRIES = 3
    const val VISUALIZER_RETRY_DELAY_MS = 300L

    // Audio reconfiguration restart delay.
    const val AUDIO_RECONFIG_DELAY_MS = 250L

    fun resolveVibrator(context: Context): Vibrator? =
      runCatching {
        val manager =
          context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      }.getOrNull()
  }
}
