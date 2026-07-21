package app.gyrolet.mpvrx.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal object PlayerDiagnostics {
  private val watchdogStarted = AtomicBoolean(false)

  fun startMainThreadWatchdog() {
    if (!watchdogStarted.compareAndSet(false, true)) return
    val handler = Handler(Looper.getMainLooper())
    val pending = AtomicBoolean(false)
    val postedAt = AtomicLong()
    Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "mpvrx-main-watchdog").apply { isDaemon = true }
    }.scheduleAtFixedRate(
      {
        if (pending.compareAndSet(false, true)) {
          postedAt.set(SystemClock.elapsedRealtime())
          handler.post { pending.set(false) }
        } else {
          val blockedMs = SystemClock.elapsedRealtime() - postedAt.get()
          if (blockedMs >= MAIN_THREAD_WARNING_MS) {
            val main = Looper.getMainLooper().thread
            Log.e(
              TAG,
              "watchdog=main_thread_blocked blockedMs=$blockedMs state=${main.state} " +
                "stack=${main.stackTrace.take(20).joinToString(" <- ")}",
            )
          }
        }
      },
      1L,
      1L,
      TimeUnit.SECONDS,
    )
  }

  fun logMemory(context: Context, sessionId: Long, phase: String) {
    val runtime = Runtime.getRuntime()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    val pssKb = runCatching {
      val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
    }.getOrDefault(0)
    Log.i(
      TAG,
      "session=$sessionId phase=$phase javaHeapMb=${javaUsed / MEBIBYTE} " +
        "nativeHeapMb=${Debug.getNativeHeapAllocatedSize() / MEBIBYTE} pssMb=${pssKb / 1024}",
    )
  }

  fun surfaceSummary(surface: Surface?): String =
    if (surface == null) {
      "surface=null"
    } else {
      "surface=${System.identityHashCode(surface)} valid=${surface.isValid}"
    }

  fun threadSummary(): String {
    val interesting = Thread.getAllStackTraces().keys
      .filter { thread ->
        val name = thread.name.lowercase()
        name.contains("main") || name.contains("mpv") || name.contains("codec") || name.contains("render")
      }.joinToString(limit = 12) { thread -> "${thread.name}:${thread.state}" }
    return "threads=[$interesting]"
  }

  private const val TAG = "PlayerDiagnostics"
  private const val MEBIBYTE = 1024L * 1024L
  private const val MAIN_THREAD_WARNING_MS = 4_000L
}
