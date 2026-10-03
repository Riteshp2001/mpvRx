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
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight playback instrumentation intended for Perfetto/system-trace captures.
 *
 * Trace slices are only emitted while a capture is attached ([android.os.Trace.isEnabled]), so a
 * release build pays nothing for the atrace JNI calls or the trace-name allocation; slices reappear
 * in full the moment Perfetto/atrace is attached. The open/close bookkeeping is tracked separately
 * from the timestamps so a section is only ever closed by the [end] that matches its [begin] —
 * `Trace.endSection()` on an unopened section corrupts the trace's slice stack.
 *
 * Human-readable logcat milestones stay restricted to debug builds. The duration log is not a
 * tracing feature, so [begin] still records a timestamp whenever either tracing or a debug build
 * wants it and debug builds keep printing `durationMs=…` with no capture attached.
 */
object PlaybackPerformanceTrace : MPVLib.EventObserver {
  private const val TAG = "PlaybackPerf"
  private const val TRACE_PREFIX = "mpvRx:"
  private val sectionStartsNs = ConcurrentHashMap<String, Long>()
  private val openSections = ConcurrentHashMap.newKeySet<String>()

  /** Set by [markOpenRequested], consumed by [markFirstFrame]. Zero means no open is in flight. */
  private val openRequestedNs = AtomicLong(0L)

  /**
   * Starts the tap-to-first-frame measurement.
   *
   * Deliberately not gated on [BuildConfig.DEBUG] the way the individual marks are. The per-mark
   * milestones are development detail, but "how long did opening a video take" is the one number
   * that has to be readable from a release build, because that is the build anyone actually
   * reports a slow open from: a release log had no way to show it, so a slow open and a fast one
   * were indistinguishable.
   */
  fun markOpenRequested(detail: String? = null) {
    // compareAndSet rather than a plain set: a second open starting before the first reported would
    // otherwise overwrite the origin of the open actually in flight.
    openRequestedNs.compareAndSet(0L, SystemClock.elapsedRealtimeNanos())
    mark("OPEN_REQUEST", detail)
  }

  /** Ends the measurement started by [markOpenRequested] and reports it. */
  fun markFirstFrame() {
    mark("PLAYER_FIRST_FRAME")
    val startedNs = openRequestedNs.getAndSet(0L)
    if (startedNs <= 0L) return
    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
    Log.i(TAG, "open_latency_ms=$elapsedMs")
  }

  fun mark(
    name: String,
    detail: String? = null,
  ) {
    if (Trace.isEnabled()) {
      val traceName = buildTraceName(name, detail)
      Trace.beginSection(traceName)
      Trace.endSection()
    }
    if (BuildConfig.DEBUG) {
      Log.d(TAG, "${SystemClock.elapsedRealtimeNanos()} $name${detail?.let { " [$it]" }.orEmpty()}")
    }
  }

  fun begin(name: String) {
    val tracing = Trace.isEnabled()
    if (tracing || BuildConfig.DEBUG) {
      sectionStartsNs[name] = SystemClock.elapsedRealtimeNanos()
    }
    if (tracing) {
      openSections.add(name)
      Trace.beginSection(TRACE_PREFIX + name)
    }
    if (BuildConfig.DEBUG) Log.d(TAG, "BEGIN $name")
  }

  fun end(name: String) {
    // Only close a section this thread's begin() actually opened; an unmatched endSection() would
    // pop somebody else's slice off the trace stack.
    if (openSections.remove(name)) {
      Trace.endSection()
    }
    val startedAt = sectionStartsNs.remove(name)
    if (BuildConfig.DEBUG) {
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
