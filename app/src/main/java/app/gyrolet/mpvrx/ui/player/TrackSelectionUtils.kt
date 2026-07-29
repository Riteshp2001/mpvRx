/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player

import `is`.xyz.mpv.MPVLib

internal fun getTrackSelectionId(property: String): Int =
  runCatching { MPVLib.getPropertyString(property)?.toIntOrNull() ?: 0 }
    .getOrDefault(0)

internal fun setTrackSelectionId(
  property: String,
  id: Int?,
) {
  val value = id?.takeIf { it > 0 }?.toString() ?: "no"
  MPVLib.setPropertyString(property, value)
}
