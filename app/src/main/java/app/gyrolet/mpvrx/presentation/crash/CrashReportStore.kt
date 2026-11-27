package app.gyrolet.mpvrx.presentation.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import app.gyrolet.mpvrx.BuildConfig
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

// threadId() exists only from API 36, below which getId() is the sole source.
@Suppress("DEPRECATION")
private val Thread.stableId: Long
  get() = if (Build.VERSION.SDK_INT >= 36) threadId() else id

internal object CrashReportStore {
  private const val EXCEPTION_FILE = "exception.txt"
  private const val METADATA_FILE = "metadata.json"
  private const val MAX_PREVIEW_CHARS = 32 * 1024

  /** Of the app log in a report; the newest lines are kept. */
  const val MAX_APP_LOG_BYTES = 1024 * 1024

  /** A captured crash whose recovery screen has not been shown yet. */
  data class PendingReport(
    val reportId: String,
    val exceptionClass: String,
    val thread: String,
    val preview: String,
  )

  /**
   * Called by the app's exception handler, before the crashing process exits. [presented] says
   * whether the recovery screen can open right away; a crash while the app is in the background
   * cannot start it, so the report is shown the next time the app is opened instead.
   */
  fun capture(
    context: Context,
    thread: Thread,
    throwable: Throwable,
    presented: Boolean = true,
  ): String {
    val directory = reportDirectory(context)
    val metadata = AtomicFile(File(directory, METADATA_FILE))
    metadata.delete()
    val reportId = UUID.randomUUID().toString()
    writeAtomically(File(directory, EXCEPTION_FILE)) { writer ->
      writer.appendLine("mpvRx crash report")
      writer.appendLine("Report ID: $reportId")
      writer.appendLine("Captured: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date())}")
      writer.appendLine("Process ID: ${Process.myPid()}")
      writer.appendLine("Device uptime: ${SystemClock.elapsedRealtime()} ms since boot")
      writer.appendLine("Crash thread: ${thread.name} (id=${thread.stableId}, state=${thread.state})")
      writer.appendLine()
      writer.appendLine(deviceInfo())
      writer.appendLine()
      writer.appendLine("=== Full exception, causes and suppressed exceptions ===")
      writeThrowable(writer, throwable)
      writer.appendLine()
      writer.appendLine("=== All thread stacks at crash ===")
      try {
        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (currentThread, stack) ->
          writer.appendLine(
            "${currentThread.name} (id=${currentThread.stableId}, state=${currentThread.state}, " +
              "daemon=${currentThread.isDaemon}, priority=${currentThread.priority})",
          )
          stack.forEach { writer.appendLine("    at $it") }
          writer.appendLine()
        }
      } catch (error: Exception) {
        writer.appendLine("Thread snapshot unavailable: $error")
      }
      writer.appendLine()
      writer.appendLine("=== App log, this session ===")
      writer.appendLine(
        runCatching { AppLogBuffer.text(maxBytes = minOf(AppLogBuffer.maxBytes, MAX_APP_LOG_BYTES)) }
          .getOrElse { "App log unavailable: $it" },
      )
    }
    writeMetadata(
      directory,
      JSONObject()
        .put("reportId", reportId)
        .put("processId", Process.myPid())
        .put("throwableClass", throwable.javaClass.name)
        .put("thread", thread.name)
        .put("completed", false)
        .put("presented", presented),
    )
    return reportId
  }

  /**
   * Records process deaths no exception handler can see (native crashes, ANRs, the system killing
   * the visible app), found from the platform's exit history on the next launch. A crash report
   * that was never shown keeps its place; these exits are appended to it.
   */
  fun captureExits(
    context: Context,
    summary: String,
    details: String,
    processId: Int,
  ): String {
    val directory = reportDirectory(context)
    val existing = pendingReport(context)
    val previous =
      existing?.let {
        runCatching { AtomicFile(File(directory, EXCEPTION_FILE)).readFully().toString(Charsets.UTF_8) }.getOrNull()
      }
    val reportId = existing?.reportId ?: UUID.randomUUID().toString()
    writeAtomically(File(directory, EXCEPTION_FILE)) { writer ->
      if (previous != null) {
        writer.append(previous)
        writer.appendLine()
      } else {
        writer.appendLine("mpvRx crash report")
        writer.appendLine("Report ID: $reportId")
        writer.appendLine("Captured: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date())}")
        writer.appendLine()
        writer.appendLine(deviceInfo())
        writer.appendLine()
      }
      writer.append(details)
    }
    if (existing == null) {
      writeMetadata(
        directory,
        JSONObject()
          .put("reportId", reportId)
          .put("processId", processId)
          .put("throwableClass", summary)
          .put("thread", "")
          .put("completed", false)
          .put("presented", false),
      )
    }
    return reportId
  }

  /** The newest crash whose recovery screen never opened, if any. */
  fun pendingReport(context: Context): PendingReport? {
    val directory = reportDirectory(context)
    val metadata =
      runCatching {
        AtomicFile(File(directory, METADATA_FILE)).openRead().bufferedReader().use { JSONObject(it.readText()) }
      }.getOrNull() ?: return null
    if (metadata.optBoolean("completed") || metadata.optBoolean("presented", true)) return null
    val reportId = metadata.optString("reportId").takeIf(String::isNotBlank) ?: return null
    val preview =
      runCatching {
        AtomicFile(File(directory, EXCEPTION_FILE)).openRead().bufferedReader().use { reader ->
          val buffer = CharArray(MAX_PREVIEW_CHARS)
          val read = reader.read(buffer)
          if (read > 0) String(buffer, 0, read) else ""
        }
      }.getOrDefault("")
    return PendingReport(reportId, metadata.optString("throwableClass"), metadata.optString("thread"), preview)
  }

  /** Marks [reportId] as shown, so it is presented at most once. */
  fun markPresented(
    context: Context,
    reportId: String,
  ) {
    val directory = reportDirectory(context)
    val metadata =
      runCatching {
        AtomicFile(File(directory, METADATA_FILE)).openRead().bufferedReader().use { JSONObject(it.readText()) }
      }.getOrNull() ?: return
    if (metadata.optString("reportId") != reportId) return
    writeMetadata(directory, metadata.put("presented", true))
  }

  private fun writeThrowable(
    writer: BufferedWriter,
    throwable: Throwable,
  ) {
    val visited = IdentityHashMap<Throwable, Boolean>()
    val pending = ArrayDeque<Pair<String, Throwable>>()
    pending.addLast("Exception" to throwable)
    while (pending.isNotEmpty()) {
      val (relationship, current) = pending.removeLast()
      writer.appendLine("$relationship: $current")
      if (visited.put(current, true) != null) {
        writer.appendLine("    Circular or previously printed exception reference")
        continue
      }
      current.stackTrace.forEach { writer.appendLine("    at $it") }
      current.cause?.let { pending.addLast("Caused by" to it) }
      current.suppressed.reversed().forEach { pending.addLast("Suppressed by ${current.javaClass.name}" to it) }
    }
  }

  @Synchronized
  fun complete(
    context: Context,
    intent: Intent,
  ): File {
    val directory = reportDirectory(context)
    val crashId = intent.getStringExtra("crash_report_id").orEmpty()
    val metadata =
      runCatching {
        AtomicFile(File(directory, "metadata.json")).openRead().bufferedReader().use { JSONObject(it.readText()) }
      }.getOrNull()
    val reportId = runCatching { UUID.fromString(crashId) }.getOrNull() ?: UUID.randomUUID()
    val report = File(directory, "mpvrx-crash-$reportId.txt")
    val matchingCapture = crashId.isNotBlank() && metadata?.optString("reportId") == crashId
    if (matchingCapture && metadata.optBoolean("completed") && report.isFile) return report
    val hasFullException = matchingCapture && File(directory, "exception.txt").isFile
    writeAtomically(report) { writer ->
      if (hasFullException) {
        AtomicFile(File(directory, "exception.txt")).openRead().bufferedReader().use { it.copyTo(writer) }
      } else {
        writer.appendLine("Full on-disk exception capture was unavailable.")
        writer.appendLine(intent.getStringExtra("exception").orEmpty())
      }
      writer.appendLine()
      writer.appendLine("=== Recovery metadata ===")
      writer.appendLine("Report ID: $reportId")
      writer.appendLine("Exception type: ${intent.getStringExtra("crash_exception_class").orEmpty()}")
      writer.appendLine("Crash thread: ${intent.getStringExtra("crash_thread").orEmpty()}")
      writer.appendLine()
      writer.appendLine("=== Available logcat buffers ===")
      val crashedProcessId = metadata?.optInt("processId", -1)?.takeIf { hasFullException && it > 0 }
      val arguments =
        mutableListOf("logcat", "-b", "main", "-b", "system", "-b", "crash", "-d", "-v", "threadtime")
      if (crashedProcessId != null) arguments += "--pid=$crashedProcessId"
      try {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        try {
          process.inputStream.bufferedReader().use { it.copyTo(writer) }
          val exitCode = process.waitFor()
          if (exitCode != 0) writer.appendLine("Logcat exited with status $exitCode")
        } finally {
          process.destroy()
        }
      } catch (error: Exception) {
        writer.appendLine("Logcat unavailable: $error")
      }
    }
    writeMetadata(
      directory,
      (metadata?.takeIf { matchingCapture } ?: JSONObject())
        .put(
          "reportId",
          reportId.toString(),
        ).put("completed", true),
    )
    directory
      .listFiles { file -> file.name.startsWith("mpvrx-crash-") && file.extension == "txt" }
      ?.sortedByDescending(File::lastModified)
      ?.drop(5)
      ?.forEach { it.delete() }
    return report
  }

  fun deviceInfo(): String =
    buildString {
      appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})")
      appendLine("Package: ${BuildConfig.APPLICATION_ID}")
      appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
      appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
      appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
      appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
      appendLine("Brand: ${Build.BRAND}")
      appendLine("Hardware: ${Build.HARDWARE}")
      appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
      appendLine("Build fingerprint: ${Build.FINGERPRINT}")
      appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
      val runtime = Runtime.getRuntime()
      appendLine("Heap bytes: used=${runtime.totalMemory() - runtime.freeMemory()}, max=${runtime.maxMemory()}")
      appendLine("Native heap bytes: ${android.os.Debug.getNativeHeapAllocatedSize()}")
    }

  private fun reportDirectory(context: Context): File =
    File(context.noBackupFilesDir, "crash-reports").apply {
      check(isDirectory || mkdirs()) { "Unable to create crash report directory" }
    }

  private fun writeMetadata(
    directory: File,
    metadata: JSONObject,
  ) = writeAtomically(File(directory, "metadata.json")) { it.write(metadata.toString()) }

  private fun writeAtomically(
    file: File,
    write: (BufferedWriter) -> Unit,
  ) {
    val atomicFile = AtomicFile(file)
    val output = atomicFile.startWrite()
    try {
      val writer = output.bufferedWriter(Charsets.UTF_8)
      write(writer)
      writer.flush()
      atomicFile.finishWrite(output)
    } catch (error: Throwable) {
      atomicFile.failWrite(output)
      throw error
    }
  }
}
