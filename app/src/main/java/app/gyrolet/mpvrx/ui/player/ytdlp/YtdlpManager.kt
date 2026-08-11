/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import app.gyrolet.mpvrx.ui.player.PlaybackSession

import android.content.Context
import android.system.Os
import android.util.Log
import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

object YtdlpManager {
  private const val TAG = "YtdlpManager"
  private const val YTDL_DIR = "ytdl"
  private const val NETWORK_GUARD_SCRIPT = "mpvrx_network_guard.lua"

  // Lua patterns (ytdl_hook `exclude` syntax, `|`-separated) for direct media/manifest
  // URLs that must skip yt-dlp and go straight to mpv/ffmpeg's native demuxers. `%.'
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

    // Install a tiny MPV-side network policy before scripts are loaded. This is intentionally
    // generic: it never scrapes a specific website and never rewrites a resolved URL. Instead it
    // preserves the request context supplied by a resolver/launcher and supplies only safe
    // fallbacks that mpv otherwise lacks (cookies and a non-empty browser-like User-Agent).
    installNetworkGuardScript(context, ua)

    // Create script-opts/ytdl_hook.conf to ensure the script picks up our bridge.
    // Native demuxers get the first chance to open URLs; yt-dlp remains the fallback resolver.
    try {
      val scriptOptsDir = File(context.filesDir, "script-opts")
      if (!scriptOptsDir.exists()) scriptOptsDir.mkdirs()
      val ytdlConf = File(scriptOptsDir, "ytdl_hook.conf")
      val confContent =
        """
        ytdl_path=$ytdlBinaryPath
        all_formats=$allFormats
        try_ytdl_first=no
        exclude=$DIRECT_MEDIA_EXCLUDE
        """.trimIndent()
      ytdlConf.writeText(confContent)
      Log.d(TAG, "Created ytdl_hook.conf at ${ytdlConf.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ytdl_hook.conf", e)
    }

    // Apply options to MPV core.
    PlaybackSession.setOptionString("ytdl", "yes")
    // Keep cookies enabled for redirects, manifests and segment requests. mpv disables HTTP
    // cookie support by default, which makes otherwise-valid signed/session streams fail.
    PlaybackSession.setOptionString("cookies", "yes")

    // Use script-opts-append for runtime flexibility.
    PlaybackSession.setOptionString("script-opts-append", "ytdl_hook-ytdl_path=$ytdlBinaryPath")
    PlaybackSession.setOptionString("script-opts-append", "ytdl_hook-all_formats=$allFormats")
    PlaybackSession.setOptionString("script-opts-append", "ytdl_hook-try_ytdl_first=no")
    // Skip yt-dlp for direct media/manifest URLs (.m3u8/.mpd/.mp4/.ts/...). Without this,
    // ytdl_hook can route tokenized CDN links through a generic extractor instead of allowing
    // mpv/FFmpeg to use their native HLS/DASH demuxers.
    PlaybackSession.setOptionString("script-opts-append", "ytdl_hook-exclude=$DIRECT_MEDIA_EXCLUDE")

    // Always derive this from typed preferences so newly added format controls cannot
    // be shadowed by an older cached generated string.
    val ytdlFormat = resolvedOptions.format
    if (ytdlFormat.isNotBlank()) {
      PlaybackSession.setOptionString("ytdl-format", ytdlFormat)
    }

    // Do not pin a global User-Agent here. The built-in ytdl hook is allowed to apply a
    // site-specific User-Agent returned by yt-dlp; the MPV-side guard supplies our configured
    // browser-like value only as a per-file fallback when no resolver/caller supplied one.

    Log.d(TAG, "Setting ytdl-format to: $ytdlFormat")
    Log.d(TAG, "Setting ytdl-raw-options to: ${resolvedOptions.rawOptions}")
    PlaybackSession.setOptionString("ytdl-raw-options", resolvedOptions.rawOptions)
    Log.d(TAG, "MPV network/ytdl options set. Binary: $ytdlBinaryPath")
  }

  private fun installNetworkGuardScript(
    context: Context,
    userAgent: String,
  ) {
    runCatching {
      val scriptsDir = File(context.filesDir, "scripts").apply { mkdirs() }
      val scriptFile = File(scriptsDir, NETWORK_GUARD_SCRIPT)
      val quotedUserAgent = luaQuote(userAgent)
      val script =
        """
        local mp = require 'mp'

        local DEFAULT_USER_AGENT = $quotedUserAgent
        local CONTEXT_TTL_SECONDS = 5.0
        local cached_headers = nil
        local cached_user_agent = nil
        local cached_at = 0.0
        local ua_only = nil
        local ua_only_at = 0.0

        local function now()
          return mp.get_time() or 0.0
        end

        local function is_http_url(url)
          return type(url) == "string" and url:match("^https?://") ~= nil
        end

        local function non_empty_headers(headers)
          return type(headers) == "table" and #headers > 0
        end

        local function copy_headers(headers)
          if not non_empty_headers(headers) then return nil end
          local copy = {}
          for index, value in ipairs(headers) do
            copy[index] = value
          end
          return copy
        end

        local function has_header(headers, wanted)
          if type(headers) ~= "table" then return false end
          wanted = string.lower(wanted)
          for _, header in ipairs(headers) do
            if type(header) == "string" then
              local name = header:match("^%s*([^:]+)%s*:")
              if name and string.lower(name) == wanted then
                return true
              end
            end
          end
          return false
        end

        -- PlayerActivity prepares the exact request headers immediately before PlaybackSession
        -- calls loadfile. PlaybackSession historically rewrote empty item headers over those
        -- values. Cache the prepared context for a few seconds so on_load can restore it after
        -- that handoff without carrying credentials indefinitely between unrelated items.
        mp.observe_property("http-header-fields", "native", function(_, headers)
          if non_empty_headers(headers) then
            cached_headers = copy_headers(headers)
            local current_user_agent = mp.get_property("user-agent")
            cached_user_agent =
              current_user_agent and current_user_agent ~= "" and current_user_agent or nil
            cached_at = now()
          end
        end)

        -- Keep a short-lived UA-only snapshot too. This covers launchers that supply only a
        -- User-Agent and no additional header fields. Empty values are deliberately ignored:
        -- PlaybackSession writes an empty string during the old handoff path.
        mp.observe_property("user-agent", "string", function(_, value)
          if value and value ~= "" then
            ua_only = value
            ua_only_at = now()
          end
        end)

        -- Run before ytdl_hook/native stream opening. Preserve resolver/launcher headers and only
        -- fill missing values. File-local options reset automatically at end of the item.
        mp.add_hook("on_load", 5, function()
          local url = mp.get_property("stream-open-filename")
          if not is_http_url(url) then
            url = mp.get_property("path")
          end
          if not is_http_url(url) then return end

          mp.set_property("file-local-options/cookies", "yes")

          local current_time = now()
          local context_is_fresh =
            cached_headers ~= nil and (current_time - cached_at) <= CONTEXT_TTL_SECONDS
          local effective_headers = mp.get_property_native("http-header-fields", {})
          if not non_empty_headers(effective_headers) and context_is_fresh then
            effective_headers = copy_headers(cached_headers)
            mp.set_property_native("file-local-options/http-header-fields", effective_headers)
          end

          local active_user_agent = mp.get_property("user-agent")
          if not active_user_agent or active_user_agent == "" then
            local fallback_user_agent = nil
            if context_is_fresh and cached_user_agent and cached_user_agent ~= "" then
              fallback_user_agent = cached_user_agent
            elseif ua_only and (current_time - ua_only_at) <= CONTEXT_TTL_SECONDS then
              fallback_user_agent = ua_only
            else
              fallback_user_agent = DEFAULT_USER_AGENT
            end
            mp.set_property("file-local-options/user-agent", fallback_user_agent)
          end

          local referrer = mp.get_property("referrer")
          if (not referrer or referrer == "") and not has_header(effective_headers, "referer") then
            local origin = url:match("^(https?://[^/]+)")
            if origin then
              mp.set_property("file-local-options/referrer", origin .. "/")
            end
          end
        end)
        """.trimIndent()

      if (!scriptFile.exists() || scriptFile.readText() != script) {
        scriptFile.writeText(script)
        Log.d(TAG, "Installed MPV network guard script: ${scriptFile.absolutePath}")
      }
    }.onFailure { error ->
      Log.e(TAG, "Failed to install MPV network guard script", error)
    }
  }

  private fun luaQuote(value: String): String =
    buildString(value.length + 2) {
      append('"')
      value.forEach { char ->
        when (char) {
          '\\' -> append("\\\\")
          '"' -> append("\\\"")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          else -> append(char)
        }
      }
      append('"')
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
