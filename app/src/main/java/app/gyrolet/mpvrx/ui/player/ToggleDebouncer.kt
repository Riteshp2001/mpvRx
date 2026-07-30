package app.gyrolet.mpvrx.ui.player

import android.os.SystemClock

internal class ToggleDebouncer(
  private val minimumIntervalMs: Long = 350L,
  private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
  private var lastAcceptedAtMs: Long = 0L

  fun tryConsume(nowMs: Long = clock()): Boolean {
    if (lastAcceptedAtMs != 0L && (nowMs - lastAcceptedAtMs) < minimumIntervalMs) return false
    lastAcceptedAtMs = nowMs
    return true
  }

  fun reset() {
    lastAcceptedAtMs = 0L
  }
}

