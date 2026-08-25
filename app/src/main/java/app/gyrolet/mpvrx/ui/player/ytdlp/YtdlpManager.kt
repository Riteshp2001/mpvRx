/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import android.content.Context
import android.net.Uri
import android.system.Os
import android.util.Log
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.ui.player.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class YtdlpPlaybackResolution(
  val videoUrl: String,
  val audioUrl: String? = null,
  val title: String? = null,
  val headers: Map<String, String> = emptyMap(),
)

object YtdlpManager {
  private const val TAG = "YtdlpManager"
  private const val YTDL_DIR = "ytdl"
  private const val PLAYBACK_RESOLVE_TIMEOUT_SECONDS = 60L
  private const val INFO_RETRIES = "3"
  private const val SOCKET_TIMEOUT_SECONDS = "15"

  private val directMediaExtensions =
    setOf(
      ".m3u8",
      ".m3u",
      ".mpd",
      ".mp4",
      ".m4v",
      ".mkv",
      ".webm",
      ".ts",
      ".m2ts",
      ".mov",
      ".avi",
      ".flv",
      ".wmv",
      ".mp3",
      ".m4a",
      ".aac",
      ".flac",
      ".wav",
      ".ogg",
      ".opus",
    )

  private val resolverReservedOptions =
    setOf(
      "format",
      "dump-json",
      "dump-single-json",
      "get-url",
      "print",
      "output",
      "paths",
      "exec",
      "skip-download",
      "no-simulate",
      "write-subs",
      "write-auto-subs",
      "write-thumbnail",
      "write-info-json",
      "sub-langs",
      "ignore-config",
      "no-playlist",
      "playlist-items",
      "retries",
      "socket-timeout",
    )

  @Volatile
  private var playbackSettings = YtdlpOptionSettings()

  @Volatile
  private var playbackFormat = YtdlpOptionsBuilder.build(playbackSettings).format

  fun getYtdlDir(context: Context): File = File(context.filesDir, YTDL_DIR).apply { if (!exists()) mkdirs() }

  fun getExecutablePath(context: Context): String =
    File(context.applicationInfo.nativeLibraryDir, "libytdl.so").absolutePath

  suspend fun copyAssets(context: Context) =
    withContext(Dispatchers.IO) {
      val ytdlDir = getYtdlDir(context)

      listOf("youtube-dl", "youtube-dl.sh").forEach { name ->
        File(context.filesDir, name).delete()
        File(ytdlDir, name).delete()
      }

      val ytdlFiles = arrayOf("setup.py", "wrapper", "python313.zip")
      for (name in ytdlFiles) {
        copyAssetFile(context, "ytdl/$name", File(ytdlDir, name))
      }

      copyAssetFile(context, "cacert.pem", File(context.filesDir, "cacert.pem"))
      File(ytdlDir, "wrapper").setExecutable(true)
    }

  private fun copyAssetFile(
    context: Context,
    assetPath: String,
    outFile: File,
  ): Boolean {
    return try {
      context.assets.open(assetPath).use { input ->
        val size = input.available().toLong()
        if (outFile.exists() && outFile.length() == size) {
          Log.v(TAG, "Skipping copy: $assetPath (exists same size)")
          return true
        }
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
        Log.d(TAG, "Copied asset: $assetPath")
        true
      }
    } catch (e: IOException) {
      Log.e(TAG, "Failed to copy asset: $assetPath", e)
      false
    }
  }

  fun setupMpvOptions(
    context: Context,
    ytdlPreferences: YtdlPreferences,
    subtitlesPreferences: SubtitlesPreferences,
  ) {
    configureProcessEnvironment(context)

    val settings = YtdlpOptionSettings.fromPreferences(ytdlPreferences, subtitlesPreferences)
    val resolvedOptions = YtdlpOptionsBuilder.build(settings)
    playbackSettings = settings
    playbackFormat = resolvedOptions.format

    // Follow Seal Plus' app-owned metadata-probe model. Android launches yt-dlp and parses its
    // JSON; mpv only receives the resolved media URLs. Keeping ytdl_hook disabled avoids mpv's
    // native subprocess path entirely on Android.
    PlaybackSession.setOptionString("ytdl", "no")

    // Old installs may still have the app-generated ytdl_hook configuration. Remove it so an
    // upgrade cannot accidentally re-enable the native hook path.
    runCatching { File(context.filesDir, "script-opts/ytdl_hook.conf").delete() }

    val userAgent = settings.userAgent.ifBlank { YtdlpOptionsBuilder.DEFAULT_USER_AGENT }
    PlaybackSession.setOptionString("user-agent", userAgent)

    Log.d(TAG, "Seal-style app-owned yt-dlp metadata resolver enabled; mpv ytdl_hook disabled")
    Log.d(TAG, "Setting yt-dlp playback format to: ${resolvedOptions.format}")
  }

  /**
   * Probe a web page in the Android layer, following Seal Plus' extraction model: ask yt-dlp for
   * one JSON metadata object, select the requested media streams from that metadata, and hand only
   * those direct URLs and headers to libmpv. Direct media/manifest URLs bypass the probe and remain
   * native mpv/ffmpeg inputs.
   */
  internal fun resolveForPlayback(
    context: Context,
    sourceUrl: String,
  ): YtdlpPlaybackResolution? {
    if (!shouldResolveForPlayback(sourceUrl)) return null

    val ytdlDir = getYtdlDir(context)
    val ytDlpScript = File(ytdlDir, "yt-dlp")
    if (!ytDlpScript.isFile) {
      Log.w(TAG, "Skipping web extraction because yt-dlp is not installed: ${ytDlpScript.absolutePath}")
      return null
    }

    val processBuilder =
      ProcessBuilder(buildPlaybackCommand(context, sourceUrl))
        .directory(ytdlDir)
        .redirectErrorStream(false)
    configureProcessEnvironment(context, processBuilder.environment(), wrapYtdlp = true)

    return try {
      val process = processBuilder.start()
      val stdout = StringBuilder()
      val stderr = StringBuilder()
      val stdoutReader =
        Thread({
          process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> stdout.appendLine(line) }
          }
        }, "mpvrx-ytdlp-stdout")
      val stderrReader =
        Thread({
          process.errorStream.bufferedReader().use { reader ->
            reader.forEachLine { line -> stderr.appendLine(line) }
          }
        }, "mpvrx-ytdlp-stderr")

      stdoutReader.start()
      stderrReader.start()
      val finished = process.waitFor(PLAYBACK_RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!finished) process.destroyForcibly()
      stdoutReader.join(2_000L)
      stderrReader.join(2_000L)

      if (!finished || process.exitValue() != 0) {
        val detail = stderr.toString().trim().take(1_000)
        Log.w(TAG, "yt-dlp metadata probe failed for ${safeHost(sourceUrl)}: $detail")
        return null
      }

      val jsonLine =
        stdout
          .lineSequence()
          .map(String::trim)
          .lastOrNull { line -> line.startsWith("{") && line.endsWith("}") }
          ?: stdout.toString().trim().takeIf { value -> value.startsWith("{") }
          ?: return null
      parsePlaybackResolution(JSONObject(jsonLine))
    } catch (error: Exception) {
      Log.w(TAG, "yt-dlp metadata probe crashed for ${safeHost(sourceUrl)}", error)
      null
    }
  }

  private fun shouldResolveForPlayback(sourceUrl: String): Boolean {
    val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return false

    val path = uri.path.orEmpty().lowercase(Locale.ROOT)
    return directMediaExtensions.none { extension -> path.endsWith(extension) }
  }

  private fun buildPlaybackCommand(
    context: Context,
    sourceUrl: String,
  ): List<String> {
    val settings = playbackSettings
    val command =
      mutableListOf(
        getExecutablePath(context),
        "--ignore-config",
        "--dump-single-json",
        "--skip-download",
        "--no-warnings",
        "--no-progress",
        "--no-playlist",
        "-R",
        INFO_RETRIES,
        "--socket-timeout",
        SOCKET_TIMEOUT_SECONDS,
        "--format",
        playbackFormat,
      )

    addCliOption(command, "user-agent", settings.userAgent.ifBlank { YtdlpOptionsBuilder.DEFAULT_USER_AGENT })
    addCliOption(command, "referer", settings.referer)
    addCliOption(command, "cookies", settings.cookiesFile)
    addCliOption(command, "proxy", settings.proxy)
    addCliOption(command, "extractor-args", settings.extractorArgs)
    addCliOption(command, "format-sort", settings.formatSort)
    if (settings.geoBypass) command += "--geo-bypass"
    if (settings.liveFromStart) command += "--live-from-start"

    YtdlpOptionsBuilder.parseRawOptions(settings.rawOptions).forEach { option ->
      val key = option.key.trim().trimStart('-')
      if (key.isBlank() || key.lowercase(Locale.ROOT) in resolverReservedOptions) return@forEach
      command += "--$key"
      option.value?.takeIf(String::isNotBlank)?.let { value -> command += value }
    }

    command += sourceUrl
    return command
  }

  private fun addCliOption(
    command: MutableList<String>,
    name: String,
    value: String,
  ) {
    if (value.isBlank()) return
    command += "--$name"
    command += value.trim()
  }

  private data class SelectedStreams(
    val main: JSONObject,
    val audio: JSONObject?,
  )

  private fun parsePlaybackResolution(info: JSONObject): YtdlpPlaybackResolution? {
    val selected = selectStreams(info) ?: return null
    val videoUrl = selected.main.nonBlankString("url") ?: info.nonBlankString("url") ?: return null
    val audioUrl = selected.audio?.nonBlankString("url")?.takeUnless { it == videoUrl }

    val headers = linkedMapOf<String, String>()
    mergeJsonHeaders(headers, info.optJSONObject("http_headers"))
    mergeJsonHeaders(headers, selected.main.optJSONObject("http_headers"))
    mergeJsonHeaders(headers, selected.audio?.optJSONObject("http_headers"))

    return YtdlpPlaybackResolution(
      videoUrl = videoUrl,
      audioUrl = audioUrl,
      title = info.nonBlankString("title"),
      headers = headers,
    )
  }

  /**
   * yt-dlp normally exposes the concrete -f selection through requested_formats or
   * requested_downloads. If an extractor only supplies the broader formats list, fall back to it
   * from the end so the fallback follows yt-dlp's usual low-to-high quality ordering.
   */
  private fun selectStreams(info: JSONObject): SelectedStreams? {
    val requested = mutableListOf<JSONObject>()
    collectRequestedFormats(info.optJSONArray("requested_formats"), requested)
    if (requested.isEmpty()) collectRequestedDownloads(info.optJSONArray("requested_downloads"), requested)
    selectStreamsFrom(requested)?.let { return it }

    if (info.nonBlankString("url") != null) {
      selectStreamsFrom(listOf(info))?.let { return it }
    }

    val formats = mutableListOf<JSONObject>()
    collectFormatsInReverse(info.optJSONArray("formats"), formats)
    return selectStreamsFrom(formats)
  }

  private fun selectStreamsFrom(formats: List<JSONObject>): SelectedStreams? {
    if (formats.isEmpty()) return null

    var combined: JSONObject? = null
    var video: JSONObject? = null
    var audio: JSONObject? = null
    formats.forEach { format ->
      if (format.nonBlankString("url") == null) return@forEach
      val hasVideo = format.hasCodec("vcodec")
      val hasAudio = format.hasCodec("acodec")
      when {
        hasVideo && hasAudio && combined == null -> combined = format
        hasVideo && video == null -> video = format
        hasAudio && audio == null -> audio = format
      }
    }

    val main = combined ?: video ?: audio ?: formats.firstOrNull { it.nonBlankString("url") != null } ?: return null
    return SelectedStreams(main = main, audio = if (main === audio || combined != null) null else audio)
  }

  private fun collectRequestedFormats(
    array: JSONArray?,
    target: MutableList<JSONObject>,
  ) {
    if (array == null) return
    for (index in 0 until array.length()) {
      array.optJSONObject(index)?.let(target::add)
    }
  }

  private fun collectRequestedDownloads(
    array: JSONArray?,
    target: MutableList<JSONObject>,
  ) {
    if (array == null) return
    for (index in 0 until array.length()) {
      val download = array.optJSONObject(index) ?: continue
      val nested = download.optJSONArray("requested_formats")
      if (nested != null && nested.length() > 0) {
        collectRequestedFormats(nested, target)
      } else {
        target += download
      }
    }
  }

  private fun collectFormatsInReverse(
    array: JSONArray?,
    target: MutableList<JSONObject>,
  ) {
    if (array == null) return
    for (index in array.length() - 1 downTo 0) {
      array.optJSONObject(index)?.let(target::add)
    }
  }

  private fun JSONObject.hasCodec(key: String): Boolean {
    val codec = nonBlankString(key) ?: return false
    return !codec.equals("none", ignoreCase = true)
  }

  private fun JSONObject.nonBlankString(key: String): String? =
    if (!has(key) || isNull(key)) {
      null
    } else {
      optString(key).takeIf { value -> value.isNotBlank() && value != "null" }
    }

  private fun mergeJsonHeaders(
    target: MutableMap<String, String>,
    source: JSONObject?,
  ) {
    if (source == null) return
    val keys = source.keys()
    while (keys.hasNext()) {
      val name = keys.next()
      val value = source.optString(name).trim()
      if (name.isNotBlank() && value.isNotBlank()) target[name] = value
    }
  }

  private fun safeHost(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "web URL" }

  private fun configureProcessEnvironment(context: Context) {
    val ytdlDir = getYtdlDir(context).absolutePath
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val pythonPath = File(nativeLibDir, "libpython.so").absolutePath
    val ytDlpScriptPath = File(ytdlDir, "yt-dlp").absolutePath
    try {
      Os.setenv("YTDL_PYTHON", pythonPath, true)
      Os.setenv("YTDL_SCRIPT", ytDlpScriptPath, true)
      Os.setenv("PYTHONHOME", ytdlDir, true)
      Os.setenv("PYTHONPATH", "$ytdlDir/python313.zip:$ytdlDir:$nativeLibDir", true)
      Os.setenv("SSL_CERT_FILE", File(context.filesDir, "cacert.pem").absolutePath, true)

      val currentPath = runCatching { Os.getenv("PATH") }.getOrNull()
      Os.setenv("PATH", if (currentPath.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentPath", true)
      val currentLd = runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull()
      Os.setenv("LD_LIBRARY_PATH", if (currentLd.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentLd", true)
      Log.d(TAG, "Environment variables set for app-owned yt-dlp runtime")
    } catch (error: Exception) {
      Log.e(TAG, "Failed to set yt-dlp environment variables", error)
    }
  }

  private fun configureProcessEnvironment(
    context: Context,
    env: MutableMap<String, String>,
    wrapYtdlp: Boolean,
  ) {
    val ytdlDir = getYtdlDir(context).absolutePath
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    env["YTDL_PYTHON"] = File(nativeLibDir, "libpython.so").absolutePath
    if (wrapYtdlp) {
      env["YTDL_SCRIPT"] = File(ytdlDir, "yt-dlp").absolutePath
    } else {
      env.remove("YTDL_SCRIPT")
    }
    env["PYTHONHOME"] = ytdlDir
    env["PYTHONPATH"] = "$ytdlDir/python313.zip:$ytdlDir:$nativeLibDir"
    env["SSL_CERT_FILE"] = File(context.filesDir, "cacert.pem").absolutePath
    env["PATH"] = "$nativeLibDir:${env["PATH"].orEmpty()}".trimEnd(':')
    env["LD_LIBRARY_PATH"] = "$nativeLibDir:${env["LD_LIBRARY_PATH"].orEmpty()}".trimEnd(':')
  }

  suspend fun runInstall(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean =
    withContext(Dispatchers.IO) {
      copyAssets(context)

      val ytdlDir = getYtdlDir(context)
      val nativeLibDir = context.applicationInfo.nativeLibraryDir
      val pythonBinary = getExecutablePath(context)
      val setupPy = File(ytdlDir, "setup.py").absolutePath
      val command = mutableListOf(pythonBinary, setupPy, nativeLibDir)
      runPythonProcess("Installing yt-dlp...", command, context, onLog)
    }

  suspend fun runUpdate(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean =
    withContext(Dispatchers.IO) {
      val ytdlDir = getYtdlDir(context)
      val pythonBinary = getExecutablePath(context)
      val ytDlp = File(ytdlDir, "yt-dlp").absolutePath
      val command = mutableListOf(pythonBinary, ytDlp, "--update")
      runPythonProcess("Updating yt-dlp...", command, context, onLog)
    }

  suspend fun runUpdateToNightly(
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean =
    withContext(Dispatchers.IO) {
      val ytdlDir = getYtdlDir(context)
      val pythonBinary = getExecutablePath(context)
      val ytDlp = File(ytdlDir, "yt-dlp").absolutePath
      val command = mutableListOf(pythonBinary, ytDlp, "--update-to", "nightly")
      runPythonProcess("Updating to yt-dlp nightly...", command, context, onLog)
    }

  private fun runPythonProcess(
    title: String,
    command: List<String>,
    context: Context,
    onLog: (String) -> Unit,
  ): Boolean {
    onLog("$title\n")
    return try {
      val processBuilder =
        ProcessBuilder(command)
          .directory(getYtdlDir(context))
          .redirectErrorStream(true)
      configureProcessEnvironment(context, processBuilder.environment(), wrapYtdlp = false)

      val process = processBuilder.start()
      val reader = BufferedReader(InputStreamReader(process.inputStream))
      var line: String?
      while (reader.readLine().also { line = it } != null) {
        onLog(line + "\n")
      }
      process.waitFor() == 0
    } catch (e: Exception) {
      onLog("Error: ${e.message}\n")
      false
    }
  }
}
