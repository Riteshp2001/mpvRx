package app.gyrolet.mpvrx.domain.haptics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.gyrolet.mpvrx.preferences.HapticsEngineMode
import app.gyrolet.mpvrx.preferences.HapticsPreferences
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
 * v2 — Premium refinements:
 *  • Visualizer callback instead of polling thread (lower latency, less CPU)
 *  • Ramp-based bed vibration with smooth attack/sustain/decay
 *  • 4-tier impact primitives (LOW_TICK → TICK → CLICK → THUD)
 *  • Impact cooldown to prevent stacking
 *  • Graceful motor ramp-down instead of abrupt cancel
 *  • Volume-awareness (optional auto-scale with media volume)
 */
class HapticsManager(
  private val context: Context,
  private val preferences: HapticsPreferences,
) {
  private val engine = AudioHapticEngine()

  private val vibrator: Vibrator? = resolveVibrator(context)
  private val audioManager: AudioManager? =
    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

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

    val vis =
      runCatching {
        Visualizer(GLOBAL_AUDIO_SESSION).apply {
          val sizeRange = Visualizer.getCaptureSizeRange()
          captureSize = sizeRange[1].coerceAtMost(MAX_CAPTURE_SIZE)
          enabled = true
        }
      }.getOrElse { e ->
        Log.w(TAG, "Failed to start Visualizer for haptics", e)
        return
      }

    visualizer = vis
    engine.reset()
    lastBedAmp = -1
    lastBedAt = 0L
    lastImpactAt = 0L
    suppressBedUntil = 0L
    zeroFrameCount = 0
    running.set(true)

    // Register the FFT data capture listener — replaces the old polling thread.
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

    Log.d(TAG, "Haptics engine started (amplitude=$hasAmplitudeControl " +
      "primitives=$supportsPrimitives tick=$supportsTickPrimitive " +
      "lowTick=$supportsLowTickPrimitive rate=${captureRate}mHz)")
  }

  /** Stop the engine and silence the motor. Safe to call when not running. */
  @Synchronized
  fun stop() {
    if (!running.getAndSet(false)) {
      releaseVisualizer()
      return
    }
    releaseVisualizer()
    runCatching { vibrator?.cancel() }
  }

  /** Full teardown. */
  @Synchronized
  fun release() {
    stop()
  }

  /**
   * Play a short illustrative pattern so the user can feel their current
   * settings from the settings screen without needing playback. Returns false
   * if the device has no usable vibrator.
   *
   * v2: Showcases the tiered impact system.
   */
  fun previewTest(): Boolean {
    val vib = vibrator ?: return false
    if (!vib.hasVibrator()) return false

    val intensity = (preferences.masterIntensity.get() / 100f).coerceIn(0.1f, 1f)
    val maxAmp = (preferences.maxAmplitude.get() / 100f).coerceIn(0.1f, 1f)
    val mode = preferences.engineMode.get()

    runCatching {
      if (mode == HapticsEngineMode.Full && supportsPrimitives) {
        // Showcase the 4-tier system: subtle → light → punch → hit.
        val scale = (intensity * maxAmp).coerceIn(0f, 1f)
        val composition = VibrationEffect.startComposition()

        // Tier 0 — subtle tap
        if (supportsLowTickPrimitive) {
          composition.addPrimitive(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            scale * 0.3f,
          )
        } else {
          composition.addPrimitive(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            scale * 0.15f,
          )
        }

        // Tier 1 — light click (after 200ms gap)
        if (supportsTickPrimitive) {
          composition.addPrimitive(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            scale * 0.5f,
            200,
          )
        } else {
          composition.addPrimitive(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            scale * 0.35f,
            200,
          )
        }

        // Tier 2 — clear punch (after 200ms gap)
        composition.addPrimitive(
          VibrationEffect.Composition.PRIMITIVE_CLICK,
          scale * 0.7f,
          200,
        )

        // Tier 3 — weighty hit (after 200ms gap)
        composition.addPrimitive(
          VibrationEffect.Composition.PRIMITIVE_THUD,
          scale * 0.8f,
          200,
        )

        vib.vibrate(composition.compose(), mediaAttributes)
      } else {
        // Amplitude-modulated bed: gentle ramp up, hold, pulse.
        val peak = (255 * intensity * maxAmp).toInt().coerceIn(1, 255)
        // Smoother ramp than v1: 6 stages from whisper to punch.
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
  private var paramRefreshCounter = 0

  /**
   * Called on the Visualizer callback thread for each FFT frame.
   */
  private fun processFrame(fft: ByteArray, samplingRate: Int) {
    val now = System.currentTimeMillis()

    // Refresh tuning periodically so slider changes apply live.
    if (paramRefreshCounter++ % PARAM_REFRESH_FRAMES == 0) {
      cachedParams = currentParams()
      cachedMode = currentEngineMode()
    }

    val params = cachedParams
    val mode = cachedMode

    // Volume-awareness: scale down when media volume is low.
    val volumeScale = if (preferences.volumeAware.get()) {
      computeVolumeScale()
    } else {
      1f
    }
    if (volumeScale <= 0f) return // Muted — skip entirely.

    val frame = engine.process(fft, samplingRate, params, now)

    val isFull = mode == HapticsEngineMode.Full

    if (isFull && frame.onset && supportsPrimitives && frame.onsetStrength > 0f) {
      // Check our own impact cooldown (engine has its debounce too).
      val sinceLast = now - lastImpactAt
      val cooldown = params.impactCooldownMs.toLong().coerceIn(80L, 300L)
      if (sinceLast >= cooldown) {
        fireImpact(frame.onsetStrength, frame.impactTier, params, volumeScale)
        lastImpactAt = now
        suppressBedUntil = now + IMPACT_HOLD_MS
        lastBedAmp = -1
      }
    } else if (now >= suppressBedUntil) {
      val scaledAmplitude = (frame.amplitude * volumeScale).toInt().coerceIn(0, 255)
      driveBed(scaledAmplitude, now)
    }
  }

  /**
   * Drive the continuous "bed" vibration with a ramp-based waveform instead
   * of the old one-shot hammering. Uses a 3-segment ramp for smooth feel.
   */
  private fun driveBed(
    amplitude: Int,
    now: Long,
  ) {
    val vib = vibrator ?: return

    if (amplitude <= 0) {
      // Graceful ramp-down: don't cancel immediately, let the motor coast
      // for a few frames to avoid the abrupt "snap-off" feeling.
      zeroFrameCount++
      if (lastBedAmp > 0 && zeroFrameCount <= RAMP_DOWN_FRAMES) {
        // Issue a fading waveform instead of instant cancel.
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
        // 3-segment ramp: attack → sustain → decay for smooth pressure feel.
        val attackAmp = (amplitude * 0.6f).toInt().coerceIn(MIN_BED_AMP, 255)
        val sustainAmp = amplitude.coerceIn(MIN_BED_AMP, 255)
        val decayAmp = (amplitude * 0.4f).toInt().coerceIn(MIN_BED_AMP, 255)

        VibrationEffect.createWaveform(
          longArrayOf(RAMP_ATTACK_MS, RAMP_SUSTAIN_MS, RAMP_DECAY_MS),
          intArrayOf(attackAmp, sustainAmp, decayAmp),
          -1,
        )
      } else {
        // No amplitude control: gate on/off above a midpoint.
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
   * Fire an impact using the 4-tier primitive system. The tier determines
   * which primitive(s) to use, while the scale determines intensity.
   * Each tier uses a lower-energy primitive for subtlety.
   */
  private fun fireImpact(
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
          val scale = (baseScale * 0.3f).coerceIn(0.02f, 0.4f)
          if (supportsLowTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.5f)
          }
        }
        1 -> {
          // Light click — noticeable but gentle.
          val scale = (baseScale * 0.45f).coerceIn(0.05f, 0.55f)
          if (supportsTickPrimitive) {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
          } else {
            composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.6f)
          }
        }
        2 -> {
          // Clear punch — satisfying click.
          val scale = (baseScale * 0.65f).coerceIn(0.1f, 0.75f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
        }
        3 -> {
          // Weighty hit — thud with controlled intensity, never maxed out.
          val scale = (baseScale * 0.8f).coerceIn(0.15f, 0.85f)
          composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
        }
      }

      vib.vibrate(composition.compose(), mediaAttributes)
    }.onFailure { Log.w(TAG, "impact vibrate failed", it) }
  }

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

    // Bed vibration timing (ramp-based, v2).
    const val BED_DURATION_MS = 120L       // Total bed waveform duration.
    const val BED_REFRESH_MS = 90L         // Refresh interval (was 45ms).
    const val AMP_CHANGE_THRESHOLD = 12    // Hysteresis (was 8).
    const val MIN_BED_AMP = 10             // Minimum bed amplitude to issue.

    // Ramp segments for the 3-segment bed waveform.
    const val RAMP_ATTACK_MS = 30L
    const val RAMP_SUSTAIN_MS = 60L
    const val RAMP_DECAY_MS = 30L

    // Graceful ramp-down: how many zero-amplitude frames to fade over.
    const val RAMP_DOWN_FRAMES = 3

    // Impact hold: suppress bed for this long after an impact fires.
    const val IMPACT_HOLD_MS = 120L        // (was 70ms).

    fun resolveVibrator(context: Context): Vibrator? =
      runCatching {
        val manager =
          context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      }.getOrNull()
  }
}
