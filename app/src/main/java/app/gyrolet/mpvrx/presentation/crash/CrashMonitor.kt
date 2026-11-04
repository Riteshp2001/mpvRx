/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.presentation.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes sure no crash goes unreported.
 *
 * - Java/Kotlin exceptions on any thread, including coroutines, are caught by
 *   [GlobalExceptionHandler], installed from `Application.attachBaseContext` so even crashes in
 *   content providers and early startup are covered.
 * - Deaths no exception handler can see (native crashes in libmpv or the renderer, ANRs, JNI
 *   aborts, the system killing the visible app) are read from the platform's exit history on the
 *   next launch and turned into the same crash report.
 * - A crash while the app is in the background cannot open the recovery screen, so it is shown
 *   the next time the app is opened instead of being lost.
 */
internal object CrashMonitor {
  private const val TAG = "CrashMonitor"
  private const val STATE = "crash_monitor"
  private const val KEY_EXITS_CHECKED_AT = "exits_checked_at"
  private const val MAX_TRACE_BYTES = 512 * 1024
  private const val MAX_TOMBSTONE_TEXT = 96 * 1024
  private const val MAX_EXITS = 16
  private const val SESSION_MARKER = "visible-session"

  private val startedActivities = AtomicInteger(0)
  private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "crash-monitor").apply { isDaemon = true } }
  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  // Freed first when a crash is handled, so an OutOfMemoryError still has room to write its report.
  @Volatile
  private var memoryReserve: ByteArray? = null

  @Volatile
  private var installed = false

  /** True while any activity of this process is started, i.e. the app can open a screen. */
  val isAppVisible: Boolean
    get() = startedActivities.get() > 0

  fun releaseMemoryReserve() {
    memoryReserve = null
  }

  /** Name of the running process, without needing system services (safe in attachBaseContext). */
  fun currentProcessName(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Application.getProcessName()
    } else {
      runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000').trim() }.getOrNull()
    }

  /** Call first thing in `attachBaseContext`, in every process except the crash screen's own. */
  fun install(application: Application) {
    if (installed) return
    installed = true
    memoryReserve = ByteArray(512 * 1024)
    ensureInstalled(application)
    application.registerActivityLifecycleCallbacks(
      object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
          if (startedActivities.incrementAndGet() == 1) markSessionVisible(application, visible = true)
        }

        override fun onActivityStopped(activity: Activity) {
          if (startedActivities.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) {
            markSessionVisible(application, visible = false)
          }
        }

        override fun onActivityCreated(
          activity: Activity,
          savedInstanceState: Bundle?,
        ) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
          activity: Activity,
          outState: Bundle,
        ) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
      },
    )
  }

  /** Puts the crash handler back on top if anything replaced it, keeping the replacement as fallback. */
  fun ensureInstalled(application: Application) {
    val current = Thread.getDefaultUncaughtExceptionHandler()
    if (current is GlobalExceptionHandler) return
    Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(application, CrashActivity::class.java, current))
  }

  /** Records abnormal exits of earlier sessions. Runs off the main thread. */
  fun recordPreviousExits(context: Context) {
    val appContext = context.applicationContext
    worker.execute {
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) recordExits(appContext) else recordUnexplainedExit(appContext)
      }.onFailure { Log.w(TAG, "Could not read previous exits", it) }
    }
  }

  /** A crash this process has already reported must not also count as an unexplained death. */
  fun clearSessionMarker(context: Context) {
    runCatching { sessionMarker(context).delete() }
  }

  // Android 10 and older keep no exit history. This marker exists only while the app is on screen,
  // so finding one from another process means it died in use without a Java exception: a native
  // crash (decoder, renderer, libmpv) or an app-not-responding kill. The boot count rules out
  // reboots and power loss.
  private fun sessionMarker(context: Context): File = File(context.noBackupFilesDir, SESSION_MARKER)

  private fun markSessionVisible(
    context: Context,
    visible: Boolean,
  ) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
    worker.execute {
      runCatching {
        val marker = sessionMarker(context)
        if (visible) {
          marker.writeText("${Process.myPid()} ${bootCount(context)} ${System.currentTimeMillis()}")
        } else {
          marker.delete()
        }
      }
    }
  }

  private data class VisibleSession(
    val processId: Int,
    val visibleSince: Long,
  )

  private fun previousVisibleSession(context: Context): VisibleSession? {
    val parts = sessionMarker(context).takeIf(File::isFile)?.readText()?.trim()?.split(' ') ?: return null
    val processId = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val boot = parts.getOrNull(1)?.toIntOrNull()
    if (processId == Process.myPid() || boot == null || boot != bootCount(context)) return null
    return VisibleSession(processId, parts.getOrNull(2)?.toLongOrNull() ?: 0L)
  }

  private fun bootCount(context: Context): Int =
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

  private fun recordUnexplainedExit(context: Context) {
    val session = previousVisibleSession(context)
    sessionMarker(context).delete()
    if (session == null) return
    val details =
      buildString {
        appendLine("=== Closed unexpectedly while in use ===")
        if (session.visibleSince > 0L) {
          appendLine("On screen since: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(session.visibleSince))}")
        }
        appendLine("Process: ${context.packageName} (pid ${session.processId})")
        appendLine()
        appendLine(
          "The app stopped while it was on screen, without a Java exception. Android " +
            "${Build.VERSION.RELEASE} keeps no exit reason, but this is almost always a native crash " +
            "(video decoder, renderer or libmpv) or the app being closed because it stopped responding. " +
            "Logs from that session follow if Android still has them.",
        )
      }
    CrashReportStore.captureExits(
      context = context,
      summary = "Closed unexpectedly while in use",
      details = details,
      processId = session.processId,
    )
  }

  /** Opens the recovery screen for a crash the user has not seen yet. */
  fun presentPendingReport(activity: Activity) {
    val appContext = activity.applicationContext
    worker.execute {
      val pending = runCatching { CrashReportStore.pendingReport(appContext) }.getOrNull() ?: return@execute
      mainHandler.post {
        if (activity.isFinishing || activity.isDestroyed) return@post
        runCatching {
          // Marked first: a recovery screen that fails to open must never loop on every launch.
          CrashReportStore.markPresented(appContext, pending.reportId)
          activity.startActivity(
            Intent(activity, CrashActivity::class.java).apply {
              putExtra("exception", pending.preview)
              putExtra("crash_exception_class", pending.exceptionClass)
              putExtra("crash_thread", pending.thread)
              putExtra("crash_report_id", pending.reportId)
              putExtra(CrashActivity.EXTRA_PREVIOUS_SESSION, true)
            },
          )
        }.onFailure { Log.w(TAG, "Could not open crash recovery", it) }
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun recordExits(context: Context) {
    val state = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
    val checkedAt = state.getLong(KEY_EXITS_CHECKED_AT, -1L)
    val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
    val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
    val newest = exits.maxOfOrNull(ApplicationExitInfo::getTimestamp) ?: System.currentTimeMillis()
    state.edit().putLong(KEY_EXITS_CHECKED_AT, maxOf(newest, checkedAt)).commit()
    // First run with this check: earlier history belongs to older versions and is not reported.
    if (checkedAt < 0L) return

    val crashProcess = "${context.packageName}:crash"
    val abnormal =
      exits
        .filter { it.timestamp > checkedAt && it.pid != Process.myPid() && it.processName != crashProcess }
        .filter(::isAbnormal)
        .sortedBy(ApplicationExitInfo::getTimestamp)
    if (abnormal.isEmpty()) return

    val details =
      buildString {
        abnormal.forEach { exit ->
          appendLine("=== ${reasonName(exit.reason)} in a previous session ===")
          appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date(exit.timestamp))}")
          appendLine("Process: ${exit.processName} (pid ${exit.pid})")
          appendLine("Importance: ${exit.importance}")
          appendLine("Status: ${exit.status}")
          exit.description?.let { appendLine("Description: $it") }
          appendLine("Memory: pss=${exit.pss} KB, rss=${exit.rss} KB")
          readTrace(exit)?.let { trace ->
            appendLine()
            appendLine(trace)
          }
          appendLine()
        }
      }
    val newestAbnormal = abnormal.last()
    CrashReportStore.captureExits(
      context = context,
      summary = reasonName(newestAbnormal.reason),
      details = details,
      processId = newestAbnormal.pid,
    )
  }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun isAbnormal(exit: ApplicationExitInfo): Boolean =
    when (exit.reason) {
      ApplicationExitInfo.REASON_CRASH,
      ApplicationExitInfo.REASON_CRASH_NATIVE,
      ApplicationExitInfo.REASON_ANR,
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
      -> true
      // Low memory or signal kills only matter when the user was looking at the app.
      ApplicationExitInfo.REASON_LOW_MEMORY,
      ApplicationExitInfo.REASON_SIGNALED,
      -> exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
      else -> false
    }

  @RequiresApi(Build.VERSION_CODES.R)
  private fun reasonName(reason: Int): String =
    when (reason) {
      ApplicationExitInfo.REASON_CRASH -> "Unhandled exception"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
      ApplicationExitInfo.REASON_ANR -> "App not responding (ANR)"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Startup failure"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed for excessive resource use"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed by the system for low memory"
      ApplicationExitInfo.REASON_SIGNALED -> "Killed by a signal"
      else -> "Abnormal exit ($reason)"
    }

  /** ANR traces are text; native crash tombstones are protobuf, so only their readable text is kept. */
  @RequiresApi(Build.VERSION_CODES.R)
  private fun readTrace(exit: ApplicationExitInfo): String? =
    runCatching {
      val bytes =
        exit.traceInputStream?.use { input ->
          val buffer = java.io.ByteArrayOutputStream()
          val chunk = ByteArray(16 * 1024)
          while (buffer.size() < MAX_TRACE_BYTES) {
            val read = input.read(chunk)
            if (read < 0) break
            buffer.write(chunk, 0, minOf(read, MAX_TRACE_BYTES - buffer.size()))
          }
          buffer.toByteArray()
        } ?: return@runCatching null
      if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
        "Native crash details (readable text from the tombstone):\n" + readableText(bytes)
      } else {
        bytes.toString(Charsets.UTF_8)
      }
    }.getOrNull()

  private fun readableText(bytes: ByteArray): String =
    buildString {
      val run = StringBuilder()
      fun flush() {
        if (run.length >= 4) appendLine(run)
        run.setLength(0)
      }
      for (byte in bytes) {
        val char = byte.toInt() and 0xFF
        if (char in 0x20..0x7E) run.append(char.toChar()) else flush()
        if (length >= MAX_TOMBSTONE_TEXT) break
      }
      flush()
    }
}
