/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import androidx.annotation.StringRes
import app.gyrolet.mpvrx.R

/** FongMi build: how MediaCodec frames reach Vulkan (`--android-vulkan-aimagereader-backend`). */
enum class VulkanFrameImport(
  @StringRes val titleRes: Int,
  val mpvValue: String,
) {
  Auto(R.string.pref_decoder_vulkan_import_auto, "auto"),
  Direct(R.string.pref_decoder_vulkan_import_direct, "direct"),
  Compute(R.string.pref_decoder_vulkan_import_compute, "compute"),
  Fragment(R.string.pref_decoder_vulkan_import_fragment, "fragment"),
}

/** FongMi build: Dolby Vision Profile 7 handling (`--demuxer-dovi-profile7`). */
enum class DolbyVisionProfile7(
  @StringRes val titleRes: Int,
  /** Null follows the output: matched to the phone's decoders for direct output, kept for the GPU renderer. */
  val mpvValue: String?,
) {
  Automatic(R.string.pref_decoder_dovi_p7_auto, null),
  Preserve(R.string.pref_decoder_dovi_p7_preserve, "preserve"),
  Hdr10(R.string.pref_decoder_dovi_p7_hdr10, "hdr10"),
  Profile81(R.string.pref_decoder_dovi_p7_p81, "p81"),
  ;

  /** Automatic lets mpv match direct output to the phone's decoders and keeps the layer on the GPU. */
  fun mpvValueFor(directOutput: Boolean): String = mpvValue ?: if (directOutput) "auto" else "preserve"
}
