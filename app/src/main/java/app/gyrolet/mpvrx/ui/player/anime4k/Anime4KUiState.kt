/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.anime4k

import app.gyrolet.mpvrx.domain.anime4k.Anime4KManager

data class Anime4KUiState(
  val isEnabled: Boolean = false,
  val selectedMode: String = Anime4KManager.Mode.OFF.name,
  val usesGpuNext: Boolean = false,
  val usesVulkan: Boolean = false,
  val enableIn4k: Boolean = false,
  val videoWidth: Int = 0,
  val videoHeight: Int = 0,
) {
  val isHighResolution: Boolean
    get() = videoWidth >= 3840 || videoHeight >= 2160

  val allowHighRes: Boolean
    get() = enableIn4k || !isHighResolution

  val isAvailable: Boolean
    get() = isEnabled && (!usesGpuNext || usesVulkan)
}
