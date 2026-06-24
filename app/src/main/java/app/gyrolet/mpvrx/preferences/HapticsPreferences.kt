package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum

/**
 * Preferences for the audio-driven haptics feature.
 *
 * Haptics analyses the device audio output mix in real time and drives the
 * vibration motor so the user can "feel" bass, impacts and action while
 * watching videos. All values that affect the feel are exposed here so they
 * can be tuned from the Haptics settings screen.
 */
class HapticsPreferences(
  preferenceStore: PreferenceStore,
) {
  /** Master on/off switch for the whole feature. */
  val enabled = preferenceStore.getBoolean("haptics_enabled", false)

  /** Which vibration engine to use (rich impacts vs. simple amplitude). */
  val engineMode = preferenceStore.getEnum("haptics_engine_mode", HapticsEngineMode.Full)

  /** Tuning preset. [HapticsPreset.Custom] exposes the sliders below. */
  val preset = preferenceStore.getEnum("haptics_preset", HapticsPreset.Subtle)

  /** Overall strength, 0..100. */
  val masterIntensity = preferenceStore.getInt("haptics_master_intensity", 70)

  /** Extra weight given to low frequencies (the "rumble"), 0..100. */
  val bassGain = preferenceStore.getInt("haptics_bass_gain", 70)

  /** How easily sudden sounds trigger a sharp impact, 0..100. */
  val impactSensitivity = preferenceStore.getInt("haptics_impact_sensitivity", 60)

  /** Silence floor; below this the motor stays idle, 0..100. */
  val noiseThreshold = preferenceStore.getInt("haptics_noise_threshold", 15)

  /** Hard clamp on the maximum vibration amplitude, 1..100. */
  val maxAmplitude = preferenceStore.getInt("haptics_max_amplitude", 100)

  /**
   * When enabled, the bed vibration is fully suppressed during quiet or
   * dialogue-heavy scenes. Impacts still fire on strong transients.
   */
  val quietSceneAwareness = preferenceStore.getBoolean("haptics_quiet_scene", true)

  /**
   * Minimum milliseconds between two impact (primitive-based) vibrations.
   * Prevents the "jackhammer" effect during rapid action sequences.
   * Range: 80..300, exposed only when preset is Custom.
   */
  val impactCooldown = preferenceStore.getInt("haptics_impact_cooldown", 150)

  /**
   * When enabled, haptic intensity is automatically scaled down when the
   * media volume is low, and disabled entirely when muted. Mimics Sony's
   * Dynamic Vibration System behaviour.
   */
  val volumeAware = preferenceStore.getBoolean("haptics_volume_aware", false)
}

enum class HapticsEngineMode(
  @StringRes val title: Int,
) {
  /** Continuous amplitude bed plus sharp primitive impacts on detected hits. */
  Full(R.string.pref_haptics_engine_full),

  /** Amplitude-only vibration that tracks loudness/bass. Most compatible. */
  Standard(R.string.pref_haptics_engine_standard),
}

enum class HapticsPreset(
  @StringRes val title: Int,
) {
  Subtle(R.string.pref_haptics_preset_subtle),
  Movie(R.string.pref_haptics_preset_movie),
  Cinema(R.string.pref_haptics_preset_cinema),
  Music(R.string.pref_haptics_preset_music),
  Game(R.string.pref_haptics_preset_game),
  Custom(R.string.pref_haptics_preset_custom),
}
