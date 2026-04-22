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
