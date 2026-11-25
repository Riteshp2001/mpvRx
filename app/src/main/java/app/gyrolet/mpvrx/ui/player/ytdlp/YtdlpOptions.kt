/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player.ytdlp

import app.gyrolet.mpvrx.preferences.SubtitlesPreferences
import app.gyrolet.mpvrx.preferences.YtdlPreferences
import app.gyrolet.mpvrx.utils.media.normalizePlaybackLanguageTag

enum class YtdlPlaylistMode(
  val title: String,
) {
  DEFAULT("yt-dlp default"),
  SINGLE_VIDEO("Single video only"),
  WHOLE_PLAYLIST("Whole playlist"),
}

data class YtdlpOptionSettings(
  val writeSubs: Boolean = true,
  val writeAutoSubs: Boolean = false,
  val subtitleLanguages: String = "",
  val userAgent: String = "",
  val referer: String = "",
  val cookiesFile: String = "",
  val javascriptRuntime: String = "",
  val proxy: String = "",
  val extractorArgs: String = "",
  val geoBypass: Boolean = false,
  val playlistMode: YtdlPlaylistMode = YtdlPlaylistMode.DEFAULT,
  val liveFromStart: Boolean = false,
  val sponsorBlockMark: String = "",
  val sponsorBlockRemove: String = "",
  val rawOptions: String = "",
) {
  companion object {
    fun fromPreferences(
      ytdlPreferences: YtdlPreferences,
      subtitlesPreferences: SubtitlesPreferences,
    ): YtdlpOptionSettings {
      val explicitSubtitleLanguages = ytdlPreferences.subtitleLanguages.get()
      val preferredSubtitleLanguages =
        subtitlesPreferences.preferredLanguages
          .get()
          .split(",")
          .mapNotNull(::normalizePlaybackLanguageTag)
          .map { it.substringBefore('-') }
          .distinct()
          // yt-dlp uses full-match regexes; include en-US and the ASR source tag en-orig.
          .map { "$it(?:-.*)?" }
          .joinToString(",")
      return YtdlpOptionSettings(
        writeSubs = ytdlPreferences.writeSubs.get(),
        writeAutoSubs = ytdlPreferences.writeAutoSubs.get(),
        subtitleLanguages = explicitSubtitleLanguages.ifBlank { preferredSubtitleLanguages.ifBlank { "all" } },
        userAgent = ytdlPreferences.customUserAgent.get(),
        referer = ytdlPreferences.referer.get(),
        cookiesFile = ytdlPreferences.cookiesFile.get(),
        proxy = ytdlPreferences.proxy.get(),
        extractorArgs = ytdlPreferences.extractorArgs.get(),
        geoBypass = ytdlPreferences.geoBypass.get(),
        playlistMode = ytdlPreferences.playlistMode.get(),
        liveFromStart = ytdlPreferences.liveFromStart.get(),
        sponsorBlockMark = ytdlPreferences.sponsorBlockMark.get(),
        sponsorBlockRemove = ytdlPreferences.sponsorBlockRemove.get(),
        rawOptions = ytdlPreferences.customRawOptions.get(),
      )
    }

  }
}

object YtdlpOptionsBuilder {
  const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  /** Best video with best audio, else the best single file. */
  const val FORMAT = "(bv*)+ba/b*/bv*+ba/b"

  /** The ytdl-raw-options value for [settings]. */
  fun buildRawOptions(settings: YtdlpOptionSettings): String {
    val rawOptions = linkedMapOf<String, String?>()

    fun add(
      key: String,
      value: String? = "",
    ) {
      val cleanedKey = key.trim().trimStart('-').lowercase()
      if (cleanedKey.isNotEmpty()) {
        rawOptions[cleanedKey] = value?.trim()
      }
    }

    add("user-agent", settings.userAgent.ifBlank { DEFAULT_USER_AGENT })
    add("retries", "3")
    add("extractor-retries", "3")
    add("socket-timeout", "15")
    if (settings.writeSubs) add("write-subs")
    add(if (settings.writeAutoSubs) "write-auto-subs" else "no-write-auto-subs")
    settings.subtitleLanguages.ifBlank { "all" }.let { add("sub-langs", it) }
    settings.referer.ifNotBlank { add("referer", it) }
    settings.cookiesFile.ifNotBlank { add("cookies", it) }
    settings.javascriptRuntime.ifNotBlank { add("js-runtimes", it) }
    settings.proxy.ifNotBlank { add("proxy", it) }
    settings.extractorArgs.ifNotBlank { add("extractor-args", it) }
    if (settings.geoBypass) add("geo-bypass")
    if (settings.liveFromStart) add("live-from-start")
    when (settings.playlistMode) {
      YtdlPlaylistMode.DEFAULT -> {}
      YtdlPlaylistMode.SINGLE_VIDEO -> add("no-playlist")
      YtdlPlaylistMode.WHOLE_PLAYLIST -> add("yes-playlist")
    }
    settings.sponsorBlockMark.ifNotBlank { add("sponsorblock-mark", it) }
    settings.sponsorBlockRemove.ifNotBlank { add("sponsorblock-remove", it) }

    parseRawOptions(settings.rawOptions).forEach { parsed ->
      add(parsed.key, parsed.value)
    }

    return rawOptions
      .map { (key, value) -> "$key=${escapeRawOptionValue(value.orEmpty())}" }
      .joinToString(",")
  }

  fun parseRawOptions(raw: String): List<RawYtdlpOption> =
    splitRawOptionTokens(raw)
      .mapNotNull { token ->
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val normalized = trimmed.removePrefix("--")
        val eqIndex = normalized.indexOf('=')
        if (eqIndex < 0) {
          RawYtdlpOption(normalized.trim(), "")
        } else {
          RawYtdlpOption(
            key = normalized.substring(0, eqIndex).trim(),
            value = normalized.substring(eqIndex + 1).trim().trimMatchingQuotes(),
          )
        }
      }.filter { it.key.isNotBlank() }

  fun escapeRawOptionValue(value: String): String {
    if (value.isBlank()) return ""
    val needsQuotes = value.any { it == ',' || it.isWhitespace() || it == '"' || it == '\'' }
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return if (needsQuotes) "\"$escaped\"" else escaped
  }

  private fun splitRawOptionTokens(raw: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    raw.forEach { ch ->
      when {
        escaped -> {
          current.append(ch)
          escaped = false
        }
        ch == '\\' -> {
          current.append(ch)
          escaped = true
        }
        quote != null -> {
          current.append(ch)
          if (ch == quote) quote = null
        }
        ch == '"' || ch == '\'' -> {
          current.append(ch)
          quote = ch
        }
        ch == ',' || ch == '\n' || ch == '\r' -> {
          result += current.toString()
          current.clear()
        }
        else -> current.append(ch)
      }
    }
    result += current.toString()
    return result
  }

  private inline fun String.ifNotBlank(block: (String) -> Unit) {
    if (isNotBlank()) block(trim())
  }

  private fun String.trimMatchingQuotes(): String {
    if (length < 2) return this
    val first = first()
    val last = last()
    return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
      substring(1, length - 1)
    } else {
      this
    }
  }
}

data class RawYtdlpOption(
  val key: String,
  val value: String?,
)
