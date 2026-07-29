/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import android.content.Context
import android.system.Os
import android.util.Log
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

object YtdlpManager {
  private const val TAG = "YtdlpManager"
  private const val YTDL_DIR = "ytdl"

  // Lua patterns (ytdl_hook `exclude` syntax, `|`-separated) for direct media/manifest
  // URLs that must skip yt-dlp and go straight to mpv/ffmpeg's native demuxers. `%.`
  // escapes the dot in a Lua pattern; each entry matches the extension anywhere in the URL
  // so tokenized query strings (…/index.m3u8?token=…) are still excluded.
  private const val DIRECT_MEDIA_EXCLUDE =
    "%.m3u8|%.m3u|%.mpd|%.mp4|%.m4v|%.mkv|%.webm|%.ts|%.m2ts|%.mov|%.avi|" +
      "%.flv|%.wmv|%.mp3|%.m4a|%.aac|%.flac|%.wav|%.ogg|%.opus"

  fun getYtdlDir(context: Context): File = File(context.filesDir, YTDL_DIR).apply { if (!exists()) mkdirs() }

  fun getExecutablePath(context: Context): String =
    File(context.applicationInfo.nativeLibraryDir, "libytdl.so").absolutePath

  suspend fun copyAssets(context: Context) =
    withContext(Dispatchers.IO) {
      val ytdlDir = getYtdlDir(context)

      // Clean up old potentially problematic scripts from multiple possible locations
      listOf("youtube-dl", "youtube-dl.sh").forEach { name ->
        File(context.filesDir, name).delete()
        File(ytdlDir, name).delete()
      }

      // Files to copy from assets/ytdl/ to filesDir/ytdl/
      val ytdlFiles = arrayOf("setup.py", "wrapper", "python313.zip")
      for (name in ytdlFiles) {
        copyAssetFile(context, "ytdl/$name", File(ytdlDir, name))
      }

      // cacert.pem goes to filesDir/
      copyAssetFile(context, "cacert.pem", File(context.filesDir, "cacert.pem"))

      // Set executable permission on wrapper (just in case it's used)
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
        FileOutputStream(outFile).use { output ->
          input.copyTo(output)
        }
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
    val nativeLibDir = context.applicationInfo.nativeLibraryDir
    val ytdlBinaryPath = File(nativeLibDir, "libytdl.so").absolutePath
    val ytdlDir = getYtdlDir(context).absolutePath
    val ytDlpScriptPath = File(ytdlDir, "yt-dlp").absolutePath
    val pythonPath = File(nativeLibDir, "libpython.so").absolutePath

    // Set environment variables for the subprocesses started by libmpv
    try {
      Os.setenv("YTDL_PYTHON", pythonPath, true)
      Os.setenv("YTDL_SCRIPT", ytDlpScriptPath, true)
      Os.setenv("PYTHONHOME", ytdlDir, true)
      // Include both the zip and the directory itself in PYTHONPATH
      // Also include nativeLibDir for potential .so modules
      Os.setenv("PYTHONPATH", "$ytdlDir/python313.zip:$ytdlDir:$nativeLibDir", true)
      Os.setenv("SSL_CERT_FILE", File(context.filesDir, "cacert.pem").absolutePath, true)

      // Add nativeLibDir to PATH so scripts can find our bridge if they search PATH
      val currentPath = runCatching { Os.getenv("PATH") }.getOrNull()
      val newPath = if (currentPath.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentPath"
      Os.setenv("PATH", newPath, true)

      // Set LD_LIBRARY_PATH for the subprocess to find libpython.so's dependencies
      val currentLd = runCatching { Os.getenv("LD_LIBRARY_PATH") }.getOrNull()
      val newLd = if (currentLd.isNullOrBlank()) nativeLibDir else "$nativeLibDir:$currentLd"
      Os.setenv("LD_LIBRARY_PATH", newLd, true)

      Log.d(TAG, "Environment variables set for ytdl bridge")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to set environment variables", e)
    }

    // Check if yt-dlp actually exists. If not, log a warning.
    val ytDlpFile = File(ytdlDir, "yt-dlp")
    if (!ytDlpFile.exists()) {
      Log.w(TAG, "yt-dlp not found in ${ytDlpFile.absolutePath}. Subprocess will fail until installed.")
    }

    val settings = YtdlpOptionSettings.fromPreferences(ytdlPreferences, subtitlesPreferences)
    val resolvedOptions = YtdlpOptionsBuilder.build(settings)
    val ua = ytdlPreferences.customUserAgent.get().ifBlank { YtdlpOptionsBuilder.DEFAULT_USER_AGENT }
    val allFormats = if (settings.audioPreference == YtdlAudioPreference.AUTO) "no" else "yes"

    // Create script-opts/ytdl_hook.conf to ensure the script picks up our bridge
    // This is the most reliable way to override ytdl_hook options
    try {
      val scriptOptsDir = File(context.filesDir, "script-opts")
      if (!scriptOptsDir.exists()) scriptOptsDir.mkdirs()
      val ytdlConf = File(scriptOptsDir, "ytdl_hook.conf")
      val confContent =
        """
        ytdl_path=$ytdlBinaryPath
        all_formats=$allFormats
        exclude=$DIRECT_MEDIA_EXCLUDE
        """.trimIndent()
      ytdlConf.writeText(confContent)
      Log.d(TAG, "Created ytdl_hook.conf at ${ytdlConf.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ytdl_hook.conf", e)
    }

    // Apply options to MPV core
    MPVLib.setOptionString("ytdl", "yes")
    MPVLib.setOptionString("ytdl-path", ytdlBinaryPath)

    // Use script-opts-append for runtime flexibility
    MPVLib.setOptionString("script-opts-append", "ytdl_hook-path=$ytdlBinaryPath")
    MPVLib.setOptionString("script-opts-append", "ytdl_hook-ytdl_path=$ytdlBinaryPath")
    MPVLib.setOptionString("script-opts-append", "ytdl_hook-all_formats=$allFormats")
    // Skip yt-dlp for direct media/manifest URLs (.m3u8/.mpd/.mp4/.ts/...). Without this,
    // ytdl_hook intercepts every http(s) URL and routes it through yt-dlp's generic
    // extractor, which chokes on tokenized HLS/CDN links — so mpv never falls back to
    // ffmpeg's native HLS demuxer and playback fails (while MX Player/VLC play it fine).
    MPVLib.setOptionString("script-opts-append", "ytdl_hook-exclude=$DIRECT_MEDIA_EXCLUDE")

    // Always derive this from typed preferences so newly added format controls cannot
    // be shadowed by an older cached generated string.
    val ytdlFormat = resolvedOptions.format
    if (ytdlFormat.isNotBlank()) {
      MPVLib.setOptionString("ytdl-format", ytdlFormat)
    }

    // Global User-Agent to avoid blocks at the network level
    MPVLib.setOptionString("user-agent", ua)

    Log.d(TAG, "Setting ytdl-format to: $ytdlFormat")
    Log.d(TAG, "Setting ytdl-raw-options to: ${resolvedOptions.rawOptions}")
    MPVLib.setOptionString("ytdl-raw-options", resolvedOptions.rawOptions)
    MPVLib.setOptionString("script-opts-append", "ytdl_hook-user_agent=\"$ua\"")

    Log.d(TAG, "MPV ytdl options set. Binary: $ytdlBinaryPath")
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

      // We use the bridge to run setup.py
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

      val env = processBuilder.environment()
      val ytdlDir = getYtdlDir(context).absolutePath
      val nativeLibDir = context.applicationInfo.nativeLibraryDir

      // Clear YTDL_SCRIPT so the bridge doesn't try to wrap yt-dlp during setup/update
      env.remove("YTDL_SCRIPT")

      env["YTDL_PYTHON"] = File(nativeLibDir, "libpython.so").absolutePath
      env["PYTHONHOME"] = ytdlDir
      env["PYTHONPATH"] = "$ytdlDir/python313.zip"
      env["SSL_CERT_FILE"] = File(context.filesDir, "cacert.pem").absolutePath
      env["LD_LIBRARY_PATH"] = nativeLibDir

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
