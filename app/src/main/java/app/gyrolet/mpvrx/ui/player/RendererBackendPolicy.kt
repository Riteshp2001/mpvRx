/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

internal object RendererBackendPolicy {
  fun canUseVulkan(
    buildIncludesVulkan: Boolean,
    deviceSupportsVulkan: Boolean,
    userEnabledVulkan: Boolean,
    forceOpenGlFallback: Boolean,
  ): Boolean =
    buildIncludesVulkan &&
      deviceSupportsVulkan &&
      userEnabledVulkan &&
      !forceOpenGlFallback

  fun canUseDirectMediaCodec(
    usesVulkan: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): Boolean = !usesVulkan || buildSupportsMediaCodecVulkan

  fun preferredHwdecMode(
    hardwareDecodingEnabled: Boolean,
    usesVulkan: Boolean,
    buildSupportsMediaCodecVulkan: Boolean,
  ): String {
    if (!hardwareDecodingEnabled) return "no"

    return if (canUseDirectMediaCodec(usesVulkan, buildSupportsMediaCodecVulkan)) {
      "mediacodec,mediacodec-copy,no"
    } else {
      "mediacodec-copy,no"
    }
  }
}
