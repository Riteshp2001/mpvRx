package app.gyrolet.mpvrx.core.util

import kotlin.math.abs
import kotlin.math.roundToLong

fun formatDuration(seconds: Double, forceHours: Boolean = false): String {
  if (!seconds.isFinite()) return "--:--"
  val negative = seconds < 0
  val total = abs(seconds).roundToLong()
  val h = total / 3600
  val m = (total % 3600) / 60
  val s = total % 60
  val body =
    if (h > 0 || forceHours) {
      "$h:${m.pad()}:${s.pad()}"
    } else {
      "${m.pad()}:${s.pad()}"
    }
  return if (negative) "-$body" else body
}

fun formatFileSize(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes / 1024.0
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  val rounded = (value * 10).roundToLong() / 10.0
  return "$rounded ${units[unit]}"
}

fun formatSpeed(speed: Double): String {
  val rounded = (speed * 100).roundToLong() / 100.0
  return "${rounded}x"
}

private fun Long.pad() = toString().padStart(2, '0')
