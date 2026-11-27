// SPDX-License-Identifier: AGPL-3.0-or-later
package app.gyrolet.mpvrx.utils.device

import android.os.Process

private const val LOW_PRIORITY_WORK = Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE

/**
 * Runs blocking, non-urgent work (file probing, artwork decoding) below the UI and render threads,
 * so a library scan on a low-end device yields the CPU to scrolling. The block is a plain function,
 * so it cannot suspend and let another coroutine inherit the lowered priority on this pooled thread.
 */
internal fun <T> runAtLowThreadPriority(block: () -> T): T {
  val previous = runCatching { Process.getThreadPriority(Process.myTid()) }.getOrNull()
  val lowered =
    previous != null &&
      previous < LOW_PRIORITY_WORK &&
      runCatching { Process.setThreadPriority(LOW_PRIORITY_WORK) }.isSuccess
  try {
    return block()
  } finally {
    if (lowered) runCatching { Process.setThreadPriority(previous) }
  }
}
