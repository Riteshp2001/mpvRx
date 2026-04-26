package app.gyrolet.mpvrx.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class RuntimeManager(private val context: Context) {

    private val TAG = "RuntimeManager"
    private val ytdlpPath: String
        get() = context.noBackupFilesDir.absolutePath + "/mpvrx/ytdlp/ytdlp"

    init {
        initYTDLP()
    }

    private fun initYTDLP() {
        try {
            val ytdlpDir = File(context.noBackupFilesDir, "mpvrx/ytdlp")
            if (!ytdlpDir.exists()) {
                ytdlpDir.mkdirs()
            }
            val ytdlpFile = File(ytdlpDir, "ytdlp")
            if (!ytdlpFile.exists()) {
                context.assets.open("ytdlp").use { input ->
                    FileOutputStream(ytdlpFile).use { output ->
                        input.copyTo(output)
                    }
                }
                ytdlpFile.setExecutable(true)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to initialize yt-dlp", e)
        }
    }

    suspend fun execute(request: YTDLRequest, timeoutMs: Long = 60000): String = withContext(Dispatchers.IO) {
        try {
            val cmdList = request.buildCommand()
            Log.d(TAG, "Executing yt-dlp with args: ${cmdList.joinToString(" ")}")

            val processBuilder = ProcessBuilder().apply {
                command(listOf(ytdlpPath) + cmdList)
                directory(context.cacheDir)
                environment()["PYTHONPATH"] = ""
                environment()["HOME"] = context.noBackupFilesDir.absolutePath
                redirectErrorStream(true)
            }

            val process = processBuilder.start()

            val output = withTimeoutOrNull(timeoutMs) {
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = try { process.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                process.waitFor()
                if (process.exitValue() != 0) {
                    throw RuntimeException("yt-dlp exited with ${process.exitValue()}: $stdout $stderr")
                }
                stdout
            } ?: run {
                process.destroyForcibly()
                throw RuntimeException("yt-dlp timed out after ${timeoutMs}ms")
            }

            output
        } catch (e: Exception) {
            Log.e(TAG, "Error executing yt-dlp: ${e.message}", e)
            throw e
        }
    }
}