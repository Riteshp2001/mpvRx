/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.presentation.crash

import android.content.Context
import android.os.Process
import android.os.SystemClock
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import android.util.Log as AndroidLog

internal enum class DebugLogLevel(
  val code: String,
  val label: String,
) {
  Verbose("V", "Verbose"),
  Debug("D", "Debug"),
  Info("I", "Info"),
  Warn("W", "Warn"),
  Error("E", "Error"),
}

internal fun debugLogLevelOf(priority: Int): DebugLogLevel =
  when {
    priority <= AndroidLog.VERBOSE -> DebugLogLevel.Verbose
    priority == AndroidLog.DEBUG -> DebugLogLevel.Debug
    priority == AndroidLog.INFO -> DebugLogLevel.Info
    priority == AndroidLog.WARN -> DebugLogLevel.Warn
    else -> DebugLogLevel.Error
  }

internal data class DebugLogEntry(
  /** Increases with every captured line, so it is a stable list key and orders entries. */
  val id: Long,
  val timeMillis: Long,
  val level: DebugLogLevel,
  val tag: String,
  val message: String,
  val pid: Int,
  val tid: Int,
) {
  /** Time of day for the viewer. */
  val timestamp: String get() = shortTimeFormat.get()!!.format(Date(timeMillis))

  /** One line in logcat's threadtime layout, for shared, saved and crash-report logs. */
  fun toLogLine(): String =
    "${longTimeFormat.get()!!.format(Date(timeMillis))} ${pid.toString().padStart(5)} ${tid.toString().padStart(5)} " +
      "${level.code} ${tag.ifBlank { AppLogging.DEFAULT_TAG }}: $message"
}

private val shortTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
private val longTimeFormat = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT) }

/**
 * Captures mpvRx's own log in-process, as it is written.
 *
 * The viewer used to read the app's lines back out of logcat, a ring buffer shared by every app and
 * the system. It routinely came back incomplete or empty: the buffer is small and other apps push
 * mpvRx's lines out, logd's spam filter drops the noisiest app (mpvRx while mpv logs), several
 * vendors drop Debug/Verbose lines or disable app logging altogether, and mpv's own messages only
 * reached it at Verbose level. Every logcat viewer library reads the same buffer, so none of them
 * can do better.
 *
 * Here every line arrives directly: app code logs through [app.gyrolet.mpvrx.utils.logging.Log],
 * a drop-in for android.util.Log that hands each line to Timber, whose trees write logcat (still
 * useful with adb) and this buffer; mpv's messages come from its log callback, and System.out/err
 * are copied line by line. None of these can be lost to another app or to the system. Lines only
 * Android has (decoders, GPU driver, libraries) are added from logcat by [LogcatTap], best effort.
 */
internal object AppLogBuffer {
  private const val MB = 1024 * 1024
  private const val DEFAULT_CAPACITY_MB = 3
  private const val MAX_ENTRIES = 100_000

  /** One huge line (a dumped response, say) must not push out the rest of the history. */
  private const val MAX_MESSAGE_CHARS = 32 * 1024

  /** Time, ids, level and separators of an exported line. */
  private const val LINE_OVERHEAD = 40

  private val lock = Any()
  private val entries = ArrayDeque<DebugLogEntry>()
  private var usedBytes = 0L
  private var capacityBytes = DEFAULT_CAPACITY_MB.toLong() * MB
  private var nextId = 0L
  private val pid = Process.myPid()

  private val _revision = MutableStateFlow(0L)

  /** Changes with every captured or cleared line. Collect it, then read [snapshot]. */
  val revision: StateFlow<Long> = _revision.asStateFlow()

  /** The history's size limit in bytes of exported text. */
  val maxBytes: Int get() = synchronized(lock) { capacityBytes.toInt() }

  fun record(
    priority: Int,
    tag: String,
    message: String,
    timeMillis: Long = System.currentTimeMillis(),
    tid: Int = Process.myTid(),
  ) {
    val text =
      if (message.length <= MAX_MESSAGE_CHARS) {
        message
      } else {
        message.take(MAX_MESSAGE_CHARS) + "… (${message.length - MAX_MESSAGE_CHARS} more characters)"
      }
    val entry: DebugLogEntry
    synchronized(lock) {
      entry = DebugLogEntry(++nextId, timeMillis, debugLogLevelOf(priority), tag, text, pid, tid)
      entries.addLast(entry)
      usedBytes += cost(entry)
      trimLocked()
      _revision.value = nextId
    }
    AppLogFile.enqueue(entry)
  }

  /** The "Debug log size" setting bounds the history, and with it every log export. */
  fun setCapacityMb(megabytes: Int) {
    synchronized(lock) {
      capacityBytes = megabytes.coerceIn(1, 10).toLong() * MB
      trimLocked()
      _revision.value = ++nextId
    }
    AppLogFile.setCapacityBytes(megabytes.coerceIn(1, 10) * MB)
  }

  fun snapshot(): List<DebugLogEntry> = synchronized(lock) { entries.toList() }

  fun clear() {
    synchronized(lock) {
      entries.clear()
      usedBytes = 0L
      _revision.value = ++nextId
    }
  }

  /** The newest lines that fit in [maxBytes], oldest first. */
  fun text(
    entries: List<DebugLogEntry> = snapshot(),
    maxBytes: Int = this.maxBytes,
  ): String {
    val kept = ArrayDeque<String>()
    var used = 0
    for (entry in entries.asReversed()) {
      val line = entry.toLogLine()
      val bytes = line.toByteArray(Charsets.UTF_8).size + 1
      if (used + bytes > maxBytes) break
      kept.addFirst(line)
      used += bytes
    }
    return kept.joinToString("\n")
  }

  private fun cost(entry: DebugLogEntry): Long = (entry.tag.length + entry.message.length + LINE_OVERHEAD).toLong()

  private fun trimLocked() {
    while (entries.size > 1 && (usedBytes > capacityBytes || entries.size > MAX_ENTRIES)) {
      usedBytes -= cost(entries.removeFirst())
    }
  }
}

/**
 * Writes the captured log to a file as it arrives. A native crash (libmpv, a decoder, the renderer)
 * ends the process without any Java handler and takes the in-memory history with it; the file
 * still holds that session's lines for the crash report on the next launch.
 */
internal object AppLogFile {
  private const val DIRECTORY = "app-logs"
  private const val CURRENT = "session.log"
  private const val CURRENT_ROLLED = "session.1.log"
  private const val PREVIOUS = "previous-session.log"
  private const val PREVIOUS_ROLLED = "previous-session.1.log"
  private const val PID_HEADER = "mpvRx app log, pid "
  private const val QUEUE_CAPACITY = 20_000

  /** Bounds what waits for the writer, in characters, whatever the length of single lines. */
  private const val MAX_QUEUED_CHARS = 4L * 1024 * 1024
  private const val MAX_RETRY_DELAY_MS = 60_000L

  private val queue = LinkedBlockingQueue<DebugLogEntry>(QUEUE_CAPACITY)
  private val queuedChars = AtomicLong()

  @Volatile private var capacityBytes = 3 * 1024 * 1024

  @Volatile private var started = false

  @Volatile private var disabled = false

  fun enqueue(entry: DebugLogEntry) {
    if (disabled) return
    // Lines from before start() wait here; past the bound, the file skips them but memory keeps them.
    val chars = entry.chars()
    if (queuedChars.get() + chars > MAX_QUEUED_CHARS) return
    if (queue.offer(entry)) queuedChars.addAndGet(chars)
  }

  /** For processes that never [start], and when the file cannot be created. */
  fun disable() {
    disabled = true
    queue.clear()
    queuedChars.set(0L)
  }

  fun setCapacityBytes(bytes: Int) {
    capacityBytes = bytes
  }

  /** Moves the last session's file aside for [previousSessionLog], then starts this one. Main process only. */
  @Synchronized
  fun start(context: Context) {
    if (started) return
    started = true
    val directory = File(context.noBackupFilesDir, DIRECTORY)
    runCatching {
      check(directory.isDirectory || directory.mkdirs())
      File(directory, PREVIOUS).delete()
      File(directory, PREVIOUS_ROLLED).delete()
      File(directory, CURRENT).renameTo(File(directory, PREVIOUS))
      File(directory, CURRENT_ROLLED).renameTo(File(directory, PREVIOUS_ROLLED))
    }.onFailure {
      AndroidLog.w(AppLogging.DEFAULT_TAG, "Could not prepare the app log file", it)
      disable()
      return
    }
    Thread({ writeLoop(directory) }, "mpvrx-log-writer").apply {
      isDaemon = true
      priority = Thread.MIN_PRIORITY
      start()
    }
  }

  /**
   * The log of the previous session if it was process [processId], newest lines last, within
   * [maxBytes]. Null when that session left no log.
   */
  fun previousSessionLog(
    context: Context,
    processId: Int,
    maxBytes: Int,
  ): String? {
    return runCatching {
      val directory = File(context.noBackupFilesDir, DIRECTORY)
      val log = File(directory, PREVIOUS).takeIf(File::isFile) ?: return null
      val header = log.bufferedReader().use { it.readLine() }.orEmpty()
      if (header.removePrefix(PID_HEADER).substringBefore(',').trim().toIntOrNull() != processId) return null
      val text =
        buildString {
          sequenceOf(File(directory, PREVIOUS_ROLLED), log).filter(File::isFile).forEach { file ->
            file.forEachLine { line -> if (!line.startsWith(PID_HEADER)) appendLine(line) }
          }
        }
      tailWithinBytes(text, maxBytes).ifBlank { null }
    }.getOrNull()
  }

  private fun writeLoop(directory: File) {
    val current = File(directory, CURRENT)
    var writer: BufferedWriter? = null
    var written = 0L
    var failures = 0
    try {
      while (true) {
        // Released as soon as a line leaves the queue, whatever happens to it afterwards.
        var entry: DebugLogEntry? = queue.take().also { queuedChars.addAndGet(-it.chars()) }
        try {
          val output = writer ?: open(current).also {
            writer = it
            written = current.length()
          }
          while (entry != null) {
            val line = entry.toLogLine()
            output.write(line)
            output.newLine()
            written += line.length + 1
            entry = queue.poll()?.also { queuedChars.addAndGet(-it.chars()) }
          }
          // Flushed whenever the queue runs dry, so at most the lines still in flight are lost.
          output.flush()
          failures = 0
          if (written > capacityBytes / 2) {
            // Two halves keep the file within the log size without rewriting it.
            writer = null
            output.close()
            val rolled = File(directory, CURRENT_ROLLED)
            rolled.delete()
            current.renameTo(rolled)
          }
        } catch (error: Exception) {
          // Storage full or unavailable: drop the line in hand and try again later rather than
          // stop for the rest of the session. Only logcat, as the app log would feed this writer again.
          runCatching { writer?.close() }
          writer = null
          if (failures++ == 0) AndroidLog.w(AppLogging.DEFAULT_TAG, "Could not write the app log file", error)
          Thread.sleep(minOf(MAX_RETRY_DELAY_MS, 1_000L shl minOf(failures, 6)))
        }
      }
    } catch (_: InterruptedException) {
      disable()
    }
  }

  private fun DebugLogEntry.chars(): Long = (tag.length + message.length + 40).toLong()

  private fun open(file: File): BufferedWriter {
    val fresh = !file.exists() || file.length() == 0L
    val writer = FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8)
    if (fresh) {
      writer.write("$PID_HEADER${Process.myPid()}, started ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date())}")
      writer.newLine()
    }
    return writer
  }

  private fun tailWithinBytes(
    text: String,
    maxBytes: Int,
  ): String {
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
    val kept = ArrayDeque<String>()
    var used = 0
    for (line in text.lines().asReversed()) {
      val bytes = line.toByteArray(Charsets.UTF_8).size + 1
      if (used + bytes > maxBytes) break
      kept.addFirst(line)
      used += bytes
    }
    return kept.joinToString("\n")
  }
}

/**
 * Routes the app log. [ensureInstalled] runs on the first log line of the process, before
 * Application.onCreate if anything logs that early, so no line is ever written before the trees
 * that receive it exist.
 */
internal object AppLogging {
  const val DEFAULT_TAG = "mpvRx"

  /** libmpv's native side; its messages arrive through [recordMpvMessage] instead. */
  private const val MPV_NATIVE_TAG = "mpv"
  private const val STDOUT_TAG = "System.out"
  private const val STDERR_TAG = "System.err"
  private const val MAX_APP_TAGS = 4096

  /** Tags whose lines are captured in-process, so [LogcatTap] skips their logcat copies. */
  private val appTags: MutableSet<String> =
    ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf(DEFAULT_TAG, MPV_NATIVE_TAG, STDOUT_TAG, STDERR_TAG)) }

  @Volatile private var installed = false

  fun ensureInstalled() {
    if (installed) return
    synchronized(this) {
      if (installed) return
      // BufferTree keeps the in-app copy and runs first, so a line's tag is known as the app's
      // before its logcat copy exists. DebugTree writes logcat and splits lines longer than its
      // 4 KB limit instead of truncating them.
      Timber.plant(BufferTree, Timber.DebugTree())
      // printStackTrace() and println() bypass Log; keep their lines too. No autoflush: flushing
      // Android's original streams would log partial lines and, on older versions, blank ones.
      runCatching {
        System.setOut(PrintStream(LineCapturingStream(System.out, STDOUT_TAG, AndroidLog.INFO), false, "UTF-8"))
        System.setErr(PrintStream(LineCapturingStream(System.err, STDERR_TAG, AndroidLog.WARN), false, "UTF-8"))
      }
      installed = true
    }
  }

  fun isAppTag(tag: String): Boolean = tag in appTags

  /** mpv messages, from its log callback. They reached logcat only at Verbose level, if at all. */
  fun recordMpvMessage(
    prefix: String,
    level: Int,
    text: String,
  ) {
    val priority =
      when {
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> AndroidLog.ERROR
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> AndroidLog.WARN
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> AndroidLog.INFO
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V -> AndroidLog.DEBUG
        else -> AndroidLog.VERBOSE
      }
    AppLogBuffer.record(priority, "mpv/$prefix", text.trimEnd('\n'))
  }

  private object BufferTree : Timber.Tree() {
    override fun isLoggable(
      tag: String?,
      priority: Int,
    ): Boolean = true

    override fun log(
      priority: Int,
      tag: String?,
      message: String,
      t: Throwable?,
    ) {
      val name = tag ?: DEFAULT_TAG
      if (appTags.size < MAX_APP_TAGS) appTags.add(name)
      AppLogBuffer.record(priority, name, message)
    }
  }
}

/** Passes everything on to [original] and records each complete line in the app log. */
private class LineCapturingStream(
  private val original: OutputStream,
  private val tag: String,
  private val priority: Int,
) : OutputStream() {
  private val line = ByteArrayOutputStream()

  @Synchronized
  override fun write(b: Int) {
    original.write(b)
    if (b == '\n'.code) endLine() else line.write(b)
    if (line.size() > MAX_LINE_BYTES) endLine()
  }

  @Synchronized
  override fun write(
    b: ByteArray,
    off: Int,
    len: Int,
  ) {
    original.write(b, off, len)
    var start = off
    for (index in off until off + len) {
      if (b[index] == '\n'.code.toByte()) {
        line.write(b, start, index - start)
        endLine()
        start = index + 1
      }
    }
    line.write(b, start, off + len - start)
    if (line.size() > MAX_LINE_BYTES) endLine()
  }

  @Synchronized
  override fun flush() = original.flush()

  private fun endLine() {
    val text = line.toString("UTF-8").trimEnd('\r')
    line.reset()
    if (text.isNotEmpty()) AppLogBuffer.record(priority, tag, text)
  }

  private companion object {
    const val MAX_LINE_BYTES = 32 * 1024
  }
}

/**
 * While verbose logging is on, adds the lines Android itself logs for this process (hardware
 * decoders, the GPU driver, the runtime, libraries that log directly) from logcat. Best effort:
 * logcat is the only place those lines exist and it can drop them. The app's own and mpv's lines
 * never depend on it; they are captured in-process, and their logcat copies are skipped here.
 */
internal object LogcatTap {
  private const val MAX_QUICK_FAILURES = 5
  private const val QUICK_FAILURE_MS = 10_000L

  // logcat -v threadtime -v epoch: "1700000000.123  1234  5678 D Tag     : message"
  private val linePattern = Regex("""^\s*(\d+)\.(\d{3})\s+\d+\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: ?(.*)$""")

  private var enabled = false
  private var generation = 0
  private var process: java.lang.Process? = null

  @Synchronized
  fun setEnabled(value: Boolean) {
    if (value == enabled) return
    enabled = value
    generation++
    process?.destroy()
    process = null
    if (value) {
      val run = generation
      Thread({ readLoop(run) }, "mpvrx-logcat-tap").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
      }
    }
  }

  @Synchronized
  private fun isCurrent(run: Int): Boolean = enabled && generation == run

  private fun readLoop(run: Int) {
    var quickFailures = 0
    try {
      while (isCurrent(run)) {
        val startedAt = SystemClock.elapsedRealtime()
        // "-T 1" first replays the newest older line. Lines stamped before this run started are
        // skipped until the first live one; after that nothing is, as timestamps can arrive out
        // of order and the clock can be changed.
        val cutoff = System.currentTimeMillis()
        var live = false
        val child =
          ProcessBuilder("logcat", "-v", "threadtime", "-v", "epoch", "--pid=${Process.myPid()}", "-T", "1")
            .redirectErrorStream(true)
            .start()
        synchronized(this) {
          if (!isCurrent(run)) {
            child.destroy()
            return
          }
          process = child
        }
        try {
          child.inputStream.bufferedReader().forEachLine { text ->
            val match = linePattern.matchEntire(text) ?: return@forEachLine
            val (seconds, millis, tid, level, tag, message) = match.destructured
            val time = seconds.toLong() * 1000 + millis.toLong()
            if (!live) {
              if (time < cutoff) return@forEachLine
              live = true
            }
            if (AppLogging.isAppTag(tag)) return@forEachLine
            AppLogBuffer.record(priorityOf(level[0]), tag, message, time, tid.toIntOrNull() ?: 0)
          }
        } catch (_: java.io.IOException) {
          // Destroyed by setEnabled(false), or logcat went away.
        } finally {
          child.destroy()
        }
        if (!isCurrent(run)) return
        // logcat ended on its own. Restart it, but give up on a device where it keeps failing.
        quickFailures = if (SystemClock.elapsedRealtime() - startedAt < QUICK_FAILURE_MS) quickFailures + 1 else 0
        if (quickFailures >= MAX_QUICK_FAILURES) {
          AndroidLog.w(AppLogging.DEFAULT_TAG, "logcat keeps exiting; Android's own lines are left out of the app log")
          return
        }
        Thread.sleep(1_000L * quickFailures)
      }
    } catch (error: Exception) {
      AndroidLog.w(AppLogging.DEFAULT_TAG, "logcat is unavailable; Android's own lines are left out of the app log", error)
    }
  }

  private fun priorityOf(level: Char): Int =
    when (level) {
      'V' -> AndroidLog.VERBOSE
      'D' -> AndroidLog.DEBUG
      'I' -> AndroidLog.INFO
      'W' -> AndroidLog.WARN
      else -> AndroidLog.ERROR
    }
}
