package app.gyrolet.mpvrx.exoplayer.core.common

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLogStore(
    context: Context,
    private val maxSizeBytes: Long = MAX_LOG_SIZE_BYTES,
) {
    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val exportLogFile = File(context.cacheDir, EXPORT_LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun append(
        level: String,
        tag: String,
        message: String,
        throwable: String? = null,
    ) = synchronized(lock) {
        val line = buildString {
            append(dateFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            throwable?.let {
                appendLine()
                append(it)
            }
            appendLine()
        }

        logFile.parentFile?.mkdirs()
        logFile.appendText(line)
        trimToMaxSize()
    }

    fun read(): String = synchronized(lock) {
        if (!logFile.exists()) return@synchronized ""
        logFile.readText()
    }

    fun clear() = synchronized(lock) {
        if (logFile.exists()) logFile.writeText("")
    }

    fun exportFile(content: String): File = synchronized(lock) {
        exportLogFile.parentFile?.mkdirs()
        exportLogFile.writeText(content)
        exportLogFile
    }

    private fun trimToMaxSize() {
        if (logFile.length() <= maxSizeBytes) return

        val bytes = logFile.readBytes()
        val startIndex = (bytes.size - maxSizeBytes).coerceAtLeast(0).toInt()
        val tail = bytes.copyOfRange(startIndex, bytes.size).toString(StandardCharsets.UTF_8)
        val firstLineBreakIndex = tail.indexOf('\n')
        logFile.writeText(
            if (firstLineBreakIndex >= 0) tail.drop(firstLineBreakIndex + 1) else tail,
        )
    }

    companion object {
        private const val LOG_FILE_NAME = "only_player.log"
        private const val EXPORT_LOG_FILE_NAME = "only_player_sanitized.log"
        const val MAX_LOG_SIZE_BYTES = 10L * 1024L * 1024L
    }
}

