/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

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

