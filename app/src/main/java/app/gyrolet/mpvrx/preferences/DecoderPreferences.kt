package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.Debanding
import app.gyrolet.mpvrx.ui.player.HdrScreenMode
import app.gyrolet.mpvrx.ui.player.InterpolationMode

class DecoderPreferences(
  preferenceStore: PreferenceStore,
) {
  val profile = preferenceStore.getString("mpv_profile", "fast")
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)
  val gpuNext = preferenceStore.getBoolean("gpu_next")
  val useVulkan = preferenceStore.getBoolean("use_vulkan", false)
  val hdrScreenOutput = preferenceStore.getBoolean("hdr_screen_output", false)
  val hdrScreenMode = preferenceStore.getEnum("hdr_screen_mode", HdrScreenMode.OFF)
  val useYUV420P = preferenceStore.getBoolean("use_yuv420p", false)

  val debanding = preferenceStore.getEnum("debanding", Debanding.None)
  val debandIterations = preferenceStore.getInt("deband_iterations", 1)
  val debandThreshold = preferenceStore.getInt("deband_threshold", 48)
  val debandRange = preferenceStore.getInt("deband_range", 16)
  val debandGrain = preferenceStore.getInt("deband_grain", 32)

  val brightnessFilter = preferenceStore.getInt("filter_brightness")
  val saturationFilter = preferenceStore.getInt("filter_saturation")
  val gammaFilter = preferenceStore.getInt("filter_gamma")
  val contrastFilter = preferenceStore.getInt("filter_contrast")
  val hueFilter = preferenceStore.getInt("filter_hue")
  val sharpnessFilter = preferenceStore.getInt("filter_sharpness")

  // Frame Interpolation Preferences
  val smoothMotion = preferenceStore.getBoolean("smooth_motion", false)
  val interpolationMode = preferenceStore.getEnum("interpolation_mode", InterpolationMode.Oversample)
  val interpolationFPSLimit = preferenceStore.getInt("interpolation_fps_limit", 0)

  // Anime4K Preferences
  val enableAnime4K = preferenceStore.getBoolean("enable_anime4k", false)
  val anime4kMode = preferenceStore.getString("anime4k_mode", "OFF")
  val anime4kQuality = preferenceStore.getString("anime4k_quality", "FAST")
}
