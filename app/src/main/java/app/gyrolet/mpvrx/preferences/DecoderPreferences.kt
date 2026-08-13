/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.ui.player.Debanding
import app.gyrolet.mpvrx.ui.player.HdrScreenMode

class DecoderPreferences(
  preferenceStore: PreferenceStore,
) {
  val profile = preferenceStore.getString("mpv_profile", "fast")
  val tryHWDecoding = preferenceStore.getBoolean("try_hw_dec", true)
  val gpuNext = preferenceStore.getBoolean("gpu_next")
  val useVulkan = preferenceStore.getBoolean("use_vulkan", false)
  val hdrScreenOutput = preferenceStore.getBoolean("hdr_screen_output", false)
  val hdrScreenMode = preferenceStore.getEnum("hdr_screen_mode", HdrScreenMode.OFF)
  val lastHdrMode = preferenceStore.getEnum("hdr_last_selected_mode", HdrScreenMode.BT_2020)

  /** Boost SDR content into the HDR range when using the Linear HDR pipeline. */
  val boostSdrToHdr = preferenceStore.getBoolean("boost_sdr_to_hdr", false)
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

  // Anime4K Preferences
  val enableAnime4K = preferenceStore.getBoolean("enable_anime4k", false)
  val anime4kMode = preferenceStore.getString("anime4k_mode", "OFF")
  val anime4kQuality = preferenceStore.getEnum("anime4k_quality", Anime4KManager.DEFAULT_QUALITY)
  val anime4kIn4k = preferenceStore.getBoolean("anime4k_in_4k", false)
  val anime4kDarken = preferenceStore.getBoolean("anime4k_darken", false)
  val anime4kThin = preferenceStore.getBoolean("anime4k_thin", false)
  val anime4kDeblur = preferenceStore.getBoolean("anime4k_deblur", false)
}
