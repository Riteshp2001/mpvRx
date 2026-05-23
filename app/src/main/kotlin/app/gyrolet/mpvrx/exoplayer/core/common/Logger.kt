package app.gyrolet.mpvrx.exoplayer.core.common

import android.content.Context
import android.util.Log

object Logger {
    private var fileLogStore: FileLogStore? = null

    fun initialize(context: Context) {
        fileLogStore = FileLogStore(context.applicationContext)
    }

    fun debug(tag: String, message: String) {
        val sanitizedMessage = sanitize(message)
        runCatching { Log.d("Logger - $tag", sanitizedMessage) }
        writeToFile("D", tag, sanitizedMessage)
    }

    fun info(tag: String, message: String) {
        val sanitizedMessage = sanitize(message)
        runCatching { Log.i("Logger - $tag", sanitizedMessage) }
        writeToFile("I", tag, sanitizedMessage)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val sanitizedMessage = sanitize(message)
        val sanitizedThrowable = throwable?.stackTraceToString()?.let(::sanitize)
        val logcatMessage = buildString {
            append(sanitizedMessage)
            sanitizedThrowable?.let {
                appendLine()
                append(it)
            }
        }
        runCatching { Log.e("Logger - $tag", logcatMessage) }
        writeToFile("E", tag, sanitizedMessage, sanitizedThrowable)
    }

    fun readLogs(): String = runCatching { sanitize(fileLogStore?.read().orEmpty()) }.getOrDefault("")

    fun clearLogs() {
        runCatching { fileLogStore?.clear() }
    }

    fun exportFile() = runCatching { fileLogStore?.exportFile(readLogs()) }.getOrNull()

    private fun writeToFile(
        level: String,
        tag: String,
        message: String,
        throwable: String? = null,
    ) {
        runCatching {
            fileLogStore?.append(
                level = level,
                tag = tag,
                message = message,
                throwable = throwable,
            )
        }
    }

    private fun sanitize(text: String): String = text
        .replace(URL_PATTERN, "<url>")
        .replace(ANDROID_PATH_PATTERN, "<path>")
        .replace(WINDOWS_PATH_PATTERN, "<path>")
        .replace(EMAIL_PATTERN, "<email>")
        .replace(IPV4_PATTERN, "<ip>")

    private val URL_PATTERN = Regex("""(?i)\b(?:https?|file|content)://\S+""")
    private val ANDROID_PATH_PATTERN = Regex("""(?<![\w.])/(?:storage|sdcard|data|mnt|system|vendor|product|apex|proc|dev|cache)(?:/[^\s)\]}>,;]*)*""")
    private val WINDOWS_PATH_PATTERN = Regex("""(?i)\b[A-Z]:\\[^\s)\]}>,;]*""")
    private val EMAIL_PATTERN = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)
    private val IPV4_PATTERN = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
}

