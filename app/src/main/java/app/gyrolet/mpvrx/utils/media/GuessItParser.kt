package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import app.gyrolet.mpvrx.runtime.OptionalRuntimePack
import app.gyrolet.mpvrx.runtime.OptionalRuntimePackManager
import app.gyrolet.mpvrx.ui.player.ytdlp.YtdlpManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object GuessItParser {
  private const val OUTPUT_PREFIX = "MPVRX_GUESSIT="
  private const val PARSE_TIMEOUT_MS = 5_000L
  private const val MAX_OUTPUT_CHARS = 65_536
  private val assetMutex = Mutex()
  private var assetsPrepared = false
  private var preparedPackVersion: Long? = null

  suspend fun parse(context: Context, fileName: String): ParsedMediaInfo? =
    withContext(Dispatchers.IO) {
      try {
        withTimeoutOrNull(PARSE_TIMEOUT_MS) {
          if (!YtdlpManager.preparePythonRuntime(context)) return@withTimeoutOrNull null
          val script = prepareAssets(context)
          readResult(context, script, fileName)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        Log.w("GuessItParser", "Offline filename parsing unavailable; using Kotlin parser", error)
        null
      }
    }

  private suspend fun prepareAssets(context: Context): File =
    assetMutex.withLock {
      val packVersion = OptionalRuntimePackManager.versionCode(context, OptionalRuntimePack.Online)
        ?: throw IOException("Online runtime pack is not installed")
      val runtimeContext = YtdlpManager.runtimeAssetContext(context)
        ?: throw IOException("Online runtime pack is not installed")
      val directory = File(context.filesDir, "guessit")
      val script = File(directory, "parse_filename.py")
      if (assetsPrepared && preparedPackVersion == packVersion) return@withLock script
      if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot prepare filename parser assets")
      val assetNames = runtimeContext.assets.list("guessit").orEmpty()
      if ("parse_filename.py" !in assetNames || "packages.json" !in assetNames) {
        throw IOException("Bundled filename parser is missing")
      }
      for (name in assetNames) {
        val target = AtomicFile(File(directory, name))
        val output = target.startWrite()
        try {
          runtimeContext.assets.open("guessit/$name").use { input ->
            if (input.copyTo(output) == 0L) throw IOException("Empty filename parser asset: $name")
          }
          target.finishWrite(output)
        } catch (error: Exception) {
          target.failWrite(output)
          throw error
        }
      }
      assetsPrepared = true
      preparedPackVersion = packVersion
      script
    }

  private suspend fun readResult(
    context: Context,
    script: File,
    fileName: String,
  ): ParsedMediaInfo? =
    coroutineScope {
      val command =
        listOf(YtdlpManager.getExecutablePath(context), "-X", "utf8", "-s", "-B", script.absolutePath, fileName)
      val process = YtdlpManager.startPythonProcess(command, context)
      val outputJob = async(Dispatchers.IO) {
        process.inputStream.reader(Charsets.UTF_8).use { reader ->
          val output = StringBuilder()
          val buffer = CharArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            check(output.length + count <= MAX_OUTPUT_CHARS) { "Filename parser output is too large" }
            output.append(buffer, 0, count)
          }
          output.toString()
        }
      }
      val exitJob = async(Dispatchers.IO) { runInterruptible { process.waitFor() } }
      try {
        val exitCode = exitJob.await()
        if (exitCode != 0) {
          Log.w("GuessItParser", "Offline filename parser exited with code $exitCode; using Kotlin parser")
          return@coroutineScope null
        }
        val payload = outputJob.await().lineSequence().lastOrNull { it.startsWith(OUTPUT_PREFIX) }
          ?.removePrefix(OUTPUT_PREFIX) ?: return@coroutineScope null
        val result = JSONObject(payload)
        val title = result.stringOrNull("title") ?: return@coroutineScope null
        ParsedMediaInfo(
          title = title,
          year = result.stringOrNull("year"),
          season = result.numberOrNull("season"),
          episode = result.numberOrNull("episode"),
          episodeTitle = result.stringOrNull("episodeTitle"),
          type = if (result.optString("type") == "tv") "tv" else "movie",
          episodeEnd = result.numberOrNull("episodeEnd"),
          isEpisodeAmbiguous = result.optBoolean("isEpisodeAmbiguous"),
        )
      } finally {
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.errorStream.close() }
        outputJob.cancel()
        exitJob.cancel()
      }
    }

  private fun JSONObject.stringOrNull(name: String): String? =
    (opt(name) as? String)?.trim()?.takeIf(String::isNotBlank)

  private fun JSONObject.numberOrNull(name: String): Int? =
    (opt(name) as? Int)?.takeIf { it in 0..9999 }
}