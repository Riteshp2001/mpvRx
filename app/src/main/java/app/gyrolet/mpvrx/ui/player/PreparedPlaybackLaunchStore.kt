/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.util.concurrent.atomic.AtomicLong

internal data class PreparedPlaybackLaunch(
  val token: Long,
  val items: List<PlaybackItem>,
  val currentIndex: Int,
  val isExplicitQueue: Boolean,
  val isM3u: Boolean,
)

internal sealed interface PreparedPlaybackLaunchResult {
  data class Accepted(val launch: PreparedPlaybackLaunch) : PreparedPlaybackLaunchResult

  data object Missing : PreparedPlaybackLaunchResult

  data object Stale : PreparedPlaybackLaunchResult
}

/** Holds one immutable in-process queue until PlayerActivity accepts the matching launch Intent. */
internal object PreparedPlaybackLaunchStore {
  private val sequence = AtomicLong()
  private val lock = Any()
  private var pending: PreparedPlaybackLaunch? = null
  private var lastConsumedToken = 0L

  fun stage(
    items: List<PlaybackItem>,
    currentIndex: Int,
    isExplicitQueue: Boolean = true,
    isM3u: Boolean = false,
  ): Long {
    require(items.isNotEmpty()) { "A prepared playback launch requires at least one item" }
    synchronized(lock) {
      val token = sequence.incrementAndGet()
      pending =
        PreparedPlaybackLaunch(
          token = token,
          items = items.map { item -> item.copy(headers = item.headers.toMap()) },
          currentIndex = currentIndex.coerceIn(items.indices),
          isExplicitQueue = isExplicitQueue,
          isM3u = isM3u,
        )
      return token
    }
  }

  fun pendingMediaKind(): DeclaredPlaybackMediaKind? =
    synchronized(lock) {
      pending?.let { launch -> launch.items.getOrNull(launch.currentIndex)?.declaredMediaKind() }
    }

  fun consume(token: Long): PreparedPlaybackLaunchResult {
    if (token <= 0L) return PreparedPlaybackLaunchResult.Missing
    synchronized(lock) {
      val launch = pending
      if (launch?.token == token) {
        pending = null
        lastConsumedToken = token
        return PreparedPlaybackLaunchResult.Accepted(launch)
      }
      return if (token <= lastConsumedToken || token < sequence.get()) {
        PreparedPlaybackLaunchResult.Stale
      } else {
        // The process may have been recreated after Android retained the Intent. Its URI remains
        // a valid standalone fallback even though the process-local queue no longer exists.
        PreparedPlaybackLaunchResult.Missing
      }
    }
  }
}