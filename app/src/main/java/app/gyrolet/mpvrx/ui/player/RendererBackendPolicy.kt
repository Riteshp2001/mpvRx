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
}
