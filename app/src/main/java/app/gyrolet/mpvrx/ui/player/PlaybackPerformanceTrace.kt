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
import android.os.Trace
import android.util.Log
import app.gyrolet.mpvrx.BuildConfig
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight playback instrumentation intended for Perfetto/system-trace captures.
 *
 * Trace slices are emitted in every build and are effectively dormant unless app tracing is
 * enabled. Human-readable logcat milestones are restricted to debug builds so production playback
 * does not pay a continuous logging cost.
 */
object PlaybackPerformanceTrace : MPVLib.EventObserver {
  private const val TAG = "PlaybackPerf"
  private const val TRACE_PREFIX = "mpvRx:"
  private val sectionStartsNs by lazy { ConcurrentHashMap<String, Long>() }

  fun mark(
    name: String,
    detail: String? = null,
  ) {
    val traceName = buildTraceName(name, detail)
    Trace.beginSection(traceName)
    Trace.endSection()
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "${SystemClock.elapsedRealtimeNanos()} $name${detail?.let { " [$it]" }.orEmpty()}")
    }
  }

  fun begin(name: String) {
    Trace.beginSection(TRACE_PREFIX + name)
    if (BuildConfig.DEBUG) {
      sectionStartsNs[name] = SystemClock.elapsedRealtimeNanos()
      Log.d(TAG, "BEGIN $name")
    }
  }

  fun end(name: String) {
    Trace.endSection()
    if (BuildConfig.DEBUG) {
      val startedAt = sectionStartsNs.remove(name)
      val durationMs = startedAt?.let { (SystemClock.elapsedRealtimeNanos() - it) / 1_000_000.0 }
      Log.d(TAG, if (durationMs == null) "END $name" else "END $name durationMs=$durationMs")
    }
  }

  override fun eventProperty(property: String) = Unit

  override fun eventProperty(
    property: String,
    value: Long,
  ) = Unit

  override fun eventProperty(
    property: String,
    value: Boolean,
  ) = Unit

  override fun eventProperty(
    property: String,
    value: String,
  ) = Unit

  override fun eventProperty(
    property: String,
    value: Double,
  ) = Unit

  override fun eventProperty(
    property: String,
    value: MPVNode,
  ) = Unit

  override fun event(
    eventId: Int,
    data: MPVNode,
  ) {
    when (eventId) {
      MPVLib.MpvEvent.MPV_EVENT_START_FILE -> mark("MPV_START_FILE")
      MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> mark("MPV_FILE_LOADED")
      MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> mark("MPV_PLAYBACK_RESTART")
      MPVLib.MpvEvent.MPV_EVENT_END_FILE -> mark("MPV_END_FILE", endFileReason(data))
    }
  }

  private fun buildTraceName(
    name: String,
    detail: String?,
  ): String =
    if (detail.isNullOrBlank()) {
      (TRACE_PREFIX + name).take(127)
    } else {
      "$TRACE_PREFIX$name:$detail".take(127)
    }

  private fun endFileReason(data: MPVNode): String? {
    val reason = data["reason"]
    return reason?.asString() ?: reason?.asInt()?.toString()
  }
}
