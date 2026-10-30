/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.presentation.crash

import android.content.Context
import android.content.Intent
import android.util.Log
import app.gyrolet.mpvrx.preferences.SettingsRestoreGuard
import app.gyrolet.mpvrx.utils.telemetry.MeasureTelemetry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Catches every uncaught Java/Kotlin throwable on any thread (including coroutines, whose
 * unhandled failures land here) and opens the crash recovery screen in its own process.
 *
 * [fallback] is the platform's handler. It only runs when recovery itself cannot be started, so
 * the system still records the crash and the next launch reports it from the exit history.
 */
class GlobalExceptionHandler(
  private val context: Context,
  private val activity: Class<*>,
  private val fallback: Thread.UncaughtExceptionHandler? = null,
) : Thread.UncaughtExceptionHandler {
  private val handlingCrash = AtomicBoolean(false)

  override fun uncaughtException(t: Thread, e: Throwable) {
    // A second crash while the first is being handled (often on another thread) must not race it.
    if (!handlingCrash.compareAndSet(false, true)) exitProcess(10)
    try {
      // Frees headroom so an OutOfMemoryError can still be written out.
      CrashMonitor.releaseMemoryReserve()
      runCatching { Log.e(TAG, "FATAL EXCEPTION: ${t.name}", e) }
      // Settings restored moments ago are the likeliest cause; put them back before relaunching.
      runCatching { SettingsRestoreGuard.revertAfterCrash(context) }
        .onFailure { Log.e(TAG, "Unable to revert restored settings", it) }
      // In the background Android blocks opening the recovery screen; it is shown on next launch.
      val canPresent = CrashMonitor.isAppVisible
      val reportId = runCatching { CrashReportStore.capture(context, t, e, presented = canPresent) }
        .onFailure { Log.e(TAG, "Unable to save crash report", it) }
        .getOrNull()
      // Reported here, so the next launch must not count it again as an unexplained death.
      if (reportId != null) CrashMonitor.clearSessionMarker(context)
      if (canPresent || reportId == null) {
        val stack = runCatching { e.stackTraceToString() }.getOrDefault(e.toString())
        val intent = Intent(context, activity).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
          putExtra("exception", stack.take(32 * 1024))
          putExtra("crash_exception_class", e.javaClass.name)
          putExtra("crash_thread", t.name)
          reportId?.let { putExtra("crash_report_id", it) }
        }
        context.startActivity(intent)
      }
    } catch (error: Throwable) {
      runCatching { Log.e(TAG, "Unable to open crash recovery", error) }
      // The platform handler records the crash and normally ends the process itself.
      if (!MeasureTelemetry.isEnabled(context)) fallback?.let { runCatching { it.uncaughtException(t, e) } }
    } finally {
      // mpvRx retains its recovery screen; Measure also receives opted-in fatal crashes.
      if (MeasureTelemetry.isEnabled(context)) {
        fallback?.let { runCatching { it.uncaughtException(t, e) } }
      }
      exitProcess(10)
    }
  }

  private companion object {
    const val TAG = "GlobalExceptionHandler"
  }
}
