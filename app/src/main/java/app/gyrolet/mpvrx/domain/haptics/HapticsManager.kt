package app.gyrolet.mpvrx.domain.haptics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 */
class HapticsManager(
  private val context: Context,
  private val preferences: HapticsPreferences,
) {
  private val engine = AudioHapticEngine()

  private val vibrator: Vibrator? = resolveVibrator(context)

  private var visualizer: Visualizer? = null
  private var worker: Thread? = null
  private val running = AtomicBoolean(false)

  // Motor capabilities (resolved once).
  private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true
  private val supportsPrimitives: Boolean =
    vibrator?.areAllPrimitivesSupported(
      VibrationEffect.Composition.PRIMITIVE_THUD,
      VibrationEffect.Composition.PRIMITIVE_CLICK,
    ) == true

  private val mediaAttributes: VibrationAttributes =
    VibrationAttributes.Builder()
      .setUsage(VibrationAttributes.USAGE_MEDIA)
      .build()

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
    running.set(true)

    worker =
      Thread({ loop(vis) }, "haptics-engine").also {
        it.priority = Thread.NORM_PRIORITY + 1
        it.start()
      }
    Log.d(TAG, "Haptics engine started (amplitude=$hasAmplitudeControl primitives=$supportsPrimitives)")
  }

  /** Stop the engine and silence the motor. Safe to call when not running. */
  @Synchronized
  fun stop() {
    if (!running.getAndSet(false)) {
      releaseVisualizer()
      return
    }
    worker?.let { runCatching { it.join(200) } }
    worker = null
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
   */
  fun previewTest(): Boolean {
    val vib = vibrator ?: return false
    if (!vib.hasVibrator()) return false

    val intensity = (preferences.masterIntensity.get() / 100f).coerceIn(0.1f, 1f)
    val maxAmp = (preferences.maxAmplitude.get() / 100f).coerceIn(0.1f, 1f)
    val mode = preferences.engineMode.get()

    runCatching {
      if (mode == HapticsEngineMode.Full && supportsPrimitives) {
        // A rising rumble then two punches — the "impact" character.
        val scale = (intensity * maxAmp).coerceIn(0f, 1f)
        val composition =
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, scale)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale, 60)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, 80)
            .compose()
        vib.vibrate(composition, mediaAttributes)
      } else {
        // Amplitude-modulated bed: ramp up, hold, pulse.
        val peak = (255 * intensity * maxAmp).toInt().coerceIn(1, 255)
        val timings = longArrayOf(40, 60, 40, 80, 40, 120)
        val amps = intArrayOf(peak / 3, peak / 2, peak * 2 / 3, peak, peak / 2, peak)
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

  private fun loop(vis: Visualizer) {
    val samplingRate = runCatching { vis.samplingRate }.getOrDefault(44100000)
    val fft = ByteArray(vis.captureSize)

    var params = currentParams()
    var mode = currentEngineMode()
    var refreshCounter = 0
    var lastBedAmp = -1
    var lastBedAt = 0L
    var suppressBedUntil = 0L

    while (running.get()) {
      val start = System.currentTimeMillis()

      // Refresh tuning periodically so slider changes apply live.
      if (refreshCounter++ % PARAM_REFRESH_FRAMES == 0) {
        params = currentParams()
        mode = currentEngineMode()
      }

      val status = runCatching { vis.getFft(fft) }.getOrDefault(Visualizer.ERROR)
      if (status == Visualizer.SUCCESS) {
        val frame = engine.process(fft, samplingRate, params)
        val now = System.currentTimeMillis()

        val isFull = mode == HapticsEngineMode.Full
        if (isFull && frame.onset && supportsPrimitives && frame.onsetStrength > 0f) {
          fireImpact(frame.onsetStrength, params)
          suppressBedUntil = now + IMPACT_HOLD_MS
          lastBedAmp = -1
        } else if (now >= suppressBedUntil) {
          driveBed(frame.amplitude, lastBedAmp, lastBedAt, now)?.let {
            lastBedAmp = it.first
            lastBedAt = it.second
          }
        }
      }

      val elapsed = System.currentTimeMillis() - start
      val sleep = FRAME_INTERVAL_MS - elapsed
      if (sleep > 0) {
        try {
          Thread.sleep(sleep)
        } catch (_: InterruptedException) {
          break
        }
      }
    }
  }

  /**
   * Drive the continuous "bed" vibration. Re-issues a short one-shot only when
   * the amplitude changed meaningfully or the previous one is about to lapse,
   * to avoid hammering the motor. Returns the new (amp, timestamp) if it acted.
   */
  private fun driveBed(
    amplitude: Int,
    lastAmp: Int,
    lastAt: Long,
    now: Long,
  ): Pair<Int, Long>? {
    val vib = vibrator ?: return null

    if (amplitude <= 0) {
      if (lastAmp != 0) {
        runCatching { vib.cancel() }
        return 0 to now
      }
      return null
    }

    val changed = abs(amplitude - lastAmp) >= AMP_CHANGE_THRESHOLD
    val stale = now - lastAt >= BED_REFRESH_MS
    if (!changed && !stale) return null

    runCatching {
      val effect =
        if (hasAmplitudeControl) {
          VibrationEffect.createOneShot(BED_DURATION_MS, amplitude)
        } else {
          // No amplitude control: gate on/off above a midpoint.
          if (amplitude > 96) {
            VibrationEffect.createOneShot(BED_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
          } else {
            return null
          }
        }
      vib.vibrate(effect, mediaAttributes)
    }.onFailure { Log.w(TAG, "bed vibrate failed", it) }

    return amplitude to now
  }

  private fun fireImpact(
    strength: Float,
    params: AudioHapticEngine.Params,
  ) {
    val vib = vibrator ?: return
    val scale = (strength * params.masterIntensity * params.maxAmplitude).coerceIn(0.05f, 1f)
    runCatching {
      val composition = VibrationEffect.startComposition()
      if (strength > 0.6f) {
        // Hard hit: thud + click stacked for a sharp, weighty punch.
        composition
          .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
          .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale * 0.8f, 20)
      } else {
        composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
      }
      vib.vibrate(composition.compose(), mediaAttributes)
    }.onFailure { Log.w(TAG, "impact vibrate failed", it) }
  }

  private fun currentParams(): AudioHapticEngine.Params =
    AudioHapticEngine.Params(
      masterIntensity = preferences.masterIntensity.get() / 100f,
      bassGain = preferences.bassGain.get() / 100f,
      impactSensitivity = preferences.impactSensitivity.get() / 100f,
      noiseThreshold = (preferences.noiseThreshold.get() / 100f) * 0.5f,
      maxAmplitude = preferences.maxAmplitude.get() / 100f,
      preset = preferences.preset.get(),
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
      runCatching { v.enabled = false }
      runCatching { v.release() }
    }
    visualizer = null
  }

  private companion object {
    const val TAG = "HapticsManager"
    const val GLOBAL_AUDIO_SESSION = 0
    const val MAX_CAPTURE_SIZE = 1024
    const val FRAME_INTERVAL_MS = 16L // ~60 Hz analysis
    const val BED_DURATION_MS = 60L
    const val BED_REFRESH_MS = 45L
    const val AMP_CHANGE_THRESHOLD = 8
    const val IMPACT_HOLD_MS = 70L
    const val PARAM_REFRESH_FRAMES = 8

    fun resolveVibrator(context: Context): Vibrator? =
      runCatching {
        val manager =
          context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
      }.getOrNull()
  }
}
