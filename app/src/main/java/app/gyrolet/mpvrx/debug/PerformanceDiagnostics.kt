/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package app.gyrolet.mpvrx.debug

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.gyrolet.mpvrx.BuildConfig
import com.ms.square.debugoverlay.BugReportDataContributor
import com.ms.square.debugoverlay.DebugOverlay
import com.ms.square.debugoverlay.DebugTab
import com.ms.square.debugoverlay.OverlayMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Bounded, opt-in, app-specific telemetry. Nothing is collected until the seven-tap gesture.
 * File paths, URLs, usernames, tokens and other media identities must never be passed to record().
 */
object PerformanceDiagnostics {
  private const val MAX_EVENTS = 1500
  @Volatile var enabled: Boolean = false
    private set

  private data class Event(
    val timeMs: Long,
    val name: String,
    val detail: String,
    val durationMs: Long?,
  )
  private data class Counter(
    var count: Long = 0,
    var durationTotalMs: Long = 0,
    var durationMaximumMs: Long = 0,
  )

  private val lock = Any()
  private val events = ArrayDeque<Event>(MAX_EVENTS)
  private val counters = linkedMapOf<String, Counter>()
  private val tabs by lazy { listOf(DebugTab("mpvRx") { DiagnosticsTab() }) }

  /** Called after process selection. AndroidX Startup installs the library automatically. */
  fun initialize() {
    DebugOverlay.configure {
      overlayMode = OverlayMode.Hidden(customTabs = tabs)
      maxLogcatEntries = 1000
    }
    // The library's convenience factory is inline and compiled for JVM 21.
    // mpvRx targets JVM 17: implementing the interface directly avoids cross-target inlining.
    DebugOverlay.addBugReportContributor(
      object : BugReportDataContributor {
        override val filename: String = "mpvrx_performance.txt"

        override fun writeTo(outputStream: java.io.OutputStream) {
          outputStream.write(buildReport().toByteArray(Charsets.UTF_8))
        }
      },
    )
  }

  /**
   * The diagnostics library stays headless so mpvRx can draw a smaller, translucent HUD.
   * The HUD is draggable; tapping it still opens every built-in DebugOverlay tab and export.
   */
  fun toggle(context: Context): Boolean {
    val next = !enabled
    if (next) {
      synchronized(lock) {
        events.clear()
        counters.clear()
      }
    }
    enabled = next
    DebugOverlay.configure {
      overlayMode = OverlayMode.Hidden(customTabs = tabs)
    }
    if (next) {
      findActivity(context)?.let(CompactPerformanceHud::show)
      record("session", "monitor enabled")
    } else {
      CompactPerformanceHud.hide()
    }
    return next
  }

  /** Keep the HUD within the current app Activity, including fullscreen video playback. */
  fun onActivityResumed(activity: Activity) {
    if (enabled) CompactPerformanceHud.show(activity)
  }

  fun onActivityPaused(activity: Activity) {
    CompactPerformanceHud.hide(activity)
  }

  private fun findActivity(context: Context): Activity? {
    var candidate = context
    for (attempt in 0 until 12) {
      if (candidate is Activity) return candidate
      candidate = (candidate as? ContextWrapper)?.baseContext ?: return null
    }
    return null
  }

  /** Low-allocation aggregate read, rather than writing log entries every rendered frame. */
  internal fun compactHudCounts(): String = synchronized(lock) {
    fun count(name: String): Long = counters[name]?.count ?: 0L
    "Thumb M:${count("thumb.memory.hit")} D:${count("thumb.disk.hit")} G:${count("thumb.generate")}" +
      "\nScans ${count("folder.scan")}  Lists ${count("list.load")}"
  }

  fun mark(): Long = if (enabled) SystemClock.elapsedRealtime() else 0L

  fun finish(name: String, started: Long, detail: String = "") {
    if (started != 0L) {
      record(name, detail, (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L))
    }
  }

  /** Every event has a wall-clock timestamp and optional elapsed duration (ms). */
  fun record(name: String, detail: String = "", durationMs: Long? = null) {
    if (!enabled) return
    synchronized(lock) {
      val counter = counters.getOrPut(name) { Counter() }
      counter.count++
      if (durationMs != null) {
        counter.durationTotalMs += durationMs
        counter.durationMaximumMs = maxOf(counter.durationMaximumMs, durationMs)
      }
      if (events.size == MAX_EVENTS) events.removeFirst()
      events.addLast(Event(System.currentTimeMillis(), name, detail.take(160), durationMs))
    }
  }

  fun clear() {
    synchronized(lock) {
      events.clear()
      counters.clear()
    }
    record("session", "counters cleared")
  }

  private fun timestamp(timeMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timeMs))

  private fun buildReport(): String {
    val (metrics, timeline) = synchronized(lock) {
      counters.map { (key, count) -> key to count.copy() } to events.toList()
    }
    return buildString {
      appendLine("mpvRx performance diagnostics")
      appendLine("Export: ${timestamp(System.currentTimeMillis())}")
      appendLine("Build: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
      appendLine("Collection enabled: $enabled")
      appendLine("Timeline entries: ${timeline.size} (bounded to $MAX_EVENTS)")
      appendLine()
      appendLine("EVENT COUNTERS | count | avg ms | max ms")
      metrics.sortedBy { it.first }.forEach { (key, stat) ->
        val avg = if (stat.count == 0L) 0L else stat.durationTotalMs / stat.count
        appendLine("$key | ${stat.count} | $avg | ${stat.durationMaximumMs}")
      }
      appendLine()
      appendLine("TIMELINE (timestamp | event | duration | detail)")
      timeline.forEach { event ->
        appendLine("${timestamp(event.timeMs)} | ${event.name} | ${event.durationMs?.let { "${it}ms" } ?: "-"} | ${event.detail}")
      }
    }
  }

  /** A small live view; no per-event StateFlow recomposition on the scrolling hot path. */
  private fun compactReport(): String {
    val (metrics, timeline) = synchronized(lock) {
      counters.map { (key, count) -> key to count.copy() } to events.toList().takeLast(30)
    }
    return buildString {
      appendLine("COUNTERS   count  avg/max ms")
      metrics.sortedBy { it.first }.forEach { (key, stat) ->
        val avg = if (stat.count == 0L) 0L else stat.durationTotalMs / stat.count
        appendLine("$key: ${stat.count}  $avg/${stat.durationMaximumMs}")
      }
      appendLine()
      appendLine("LATEST EVENTS (last 30)")
      timeline.asReversed().forEach { e ->
        appendLine("${timestamp(e.timeMs).substring(11, 23)}  ${e.name}  ${e.durationMs?.let { "${it}ms" } ?: ""} ${e.detail}")
      }
    }
  }

  /** Share the full snapshot and chronological logs at any moment via the existing FileProvider. */
  fun export(context: Context) {
    runCatching {
      val directory = File(context.cacheDir, "perf_diagnostics").apply { mkdirs() }
      val file = File(directory, "mpvrx_performance_${System.currentTimeMillis()}.txt")
      file.writeText(buildReport(), Charsets.UTF_8)
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
      val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(
        Intent.createChooser(share, "Export mpvRx performance logs")
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
      )
    }.onFailure {
      Toast.makeText(context, "Unable to export performance logs", Toast.LENGTH_LONG).show()
    }
  }

  @Composable
  private fun DiagnosticsTab() {
    val context = LocalContext.current
    var content by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
      while (true) {
        content = compactReport()
        delay(750)
      }
    }
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { export(context) }) { Text("Export logs", fontSize = 11.sp) }
        TextButton(onClick = { clear() }) { Text("Clear", fontSize = 11.sp) }
      }
      Text(
        "Live • 750 ms refresh • bounded events • no media paths recorded",
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        content,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontFamily = FontFamily.Monospace,
      )
    }
  }
}
