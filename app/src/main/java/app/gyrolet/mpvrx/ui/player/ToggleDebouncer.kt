package app.gyrolet.mpvrx.ui.player

import android.os.SystemClock

internal class ToggleDebouncer(
  private val minimumIntervalMs: Long = 350L,
  private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
  private var lastAcceptedAtMs: Long = Long.MIN_VALUE

  fun tryConsume(nowMs: Long = clock()): Boolean {
    val elapsedMs = nowMs - lastAcceptedAtMs
    if (elapsedMs < minimumIntervalMs) return false
    lastAcceptedAtMs = nowMs
    return true
  }

  fun reset() {
    lastAcceptedAtMs = Long.MIN_VALUE
  }
}
