/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Throttles the running results of a scan on their way to a consumer that repaints as they arrive.
 *
 * A scan that only reports at the end is a spinner; one that reports per row is a visible list that
 * fills in. Neither extreme works: every row is far too often (each publish re-sorts, restarts the
 * thumbnail pipeline and recomposes a grid), and the first row is far too late. So a scan calls
 * [publishIfNeeded] per item and this decides when that is worth a callback.
 *
 * [filter] is the reason this is not just a timer. Progressive results are published *before* the
 * scan has finished deciding what belongs in the list, so a scan that discovers a row and later
 * rejects it would otherwise show it and then take it away. Anything the final result will not
 * contain belongs in [filter], which is applied to every intermediate snapshot as well as to the
 * last one, so a consumer never has to unpaint.
 *
 * Not thread safe, and deliberately not: one instance belongs to one scan, driven by one coroutine.
 * Producers running in parallel each get their own instance and merge into shared state under the
 * caller's lock, because the publish callback suspends and must never be called while a lock is
 * held.
 */
internal class ProgressiveResultsPublisher<T>(
  private val onSnapshot: (suspend (List<T>) -> Unit)?,
  private val snapshot: () -> List<T>,
  private val filter: ((T) -> Boolean)? = null,
) {
  private var published = false
  private var pendingItems = 0
  private var lastPublicationNanos = System.nanoTime()

  /**
   * @param force publishes whatever has accumulated, ignoring both thresholds. Scans call this at
   *   every step the consumer cannot infer on its own: the end of a phase, and the end of the scan.
   */
  suspend fun publishIfNeeded(force: Boolean = false) {
    currentCoroutineContext().ensureActive()
    val publish = onSnapshot ?: return
    pendingItems++
    val now = System.nanoTime()
    // A small first batch so the opening rows land almost immediately, then a much larger one:
    // by then the consumer has already painted something and can absorb more per callback.
    val batchSize = if (published) 128 else 16
    if (!force && pendingItems < batchSize && now - lastPublicationNanos < PUBLICATION_INTERVAL_NANOS) return
    publish(filtered())
    published = true
    pendingItems = 0
    lastPublicationNanos = System.nanoTime()
  }

  private fun filtered(): List<T> {
    val items = snapshot()
    val keep = filter ?: return items
    return items.filter(keep)
  }

  private companion object {
    /** Roughly six publishes a second, which is faster than a list can be read and slower than a scan. */
    const val PUBLICATION_INTERVAL_NANOS = 150_000_000L
  }
}