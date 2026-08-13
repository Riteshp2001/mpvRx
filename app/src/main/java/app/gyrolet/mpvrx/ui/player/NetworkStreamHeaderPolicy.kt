/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

import java.net.URI

/**
 * Provides stable HTTP defaults for queue items before they reach libmpv.
 *
 * PlayerActivity applies direct-intent headers before it constructs a standalone [PlaybackItem],
 * while PlaybackSession intentionally re-applies the headers stored on that item at the actual
 * `loadfile` boundary. Capturing the already-applied direct-request fields here prevents that
 * boundary from dropping Authorization/Cookie/Referer headers or replacing the configured
 * User-Agent with an empty string.
 *
 * Playlist items do not use [captureCurrentHttpHeaders]; they keep their own stored metadata via
 * [withHttpDefaults], so headers from one stream cannot leak into another queue item.
 */
internal object NetworkStreamHeaderPolicy {
  private const val FALLBACK_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  @Volatile
  private var configuredUserAgent: String = FALLBACK_USER_AGENT

  fun configureDefaultUserAgent(userAgent: String) {
    configuredUserAgent = userAgent.trim().ifBlank { FALLBACK_USER_AGENT }
  }

  /**
   * Snapshot the HTTP fields PlayerActivity has already prepared for one standalone direct load.
   * The mpv option is a comma-separated string list with backslash escaping.
   */
  fun captureCurrentHttpHeaders(uri: String): Map<String, String> {
    if (!isHttpUri(uri)) return emptyMap()

    val headers = parseHeaderFields(PlaybackSession.getPropertyString("http-header-fields").orEmpty())
    val currentUserAgent = PlaybackSession.getPropertyString("user-agent")?.takeIf { it.isNotBlank() }
    val result = LinkedHashMap(headers)
    if (currentUserAgent != null) result["User-Agent"] = currentUserAgent
    return withHttpDefaults(uri, result)
  }

  /**
   * Returns [headers] with a nonblank User-Agent for HTTP(S) media only.
   * Explicit caller-provided User-Agent values always win.
   */
  fun withHttpDefaults(
    uri: String,
    headers: Map<String, String> = emptyMap(),
  ): Map<String, String> {
    if (!isHttpUri(uri)) return headers

    val result = LinkedHashMap(headers)
    val userAgentEntry = result.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
    if (!userAgentEntry?.value.isNullOrBlank()) return result

    userAgentEntry?.let { result.remove(it.key) }
    result["User-Agent"] = configuredUserAgent
    return result
  }

  private fun parseHeaderFields(value: String): Map<String, String> {
    if (value.isBlank()) return emptyMap()

    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    value.forEach { char ->
      when {
        escaped -> {
          current.append(char)
          escaped = false
        }
        char == '\\' -> escaped = true
        char == ',' -> {
          fields += current.toString()
          current.setLength(0)
        }
        else -> current.append(char)
      }
    }
    if (escaped) current.append('\\')
    fields += current.toString()

    return buildMap {
      fields.forEach { field ->
        val separator = field.indexOf(':')
        if (separator <= 0) return@forEach
        val name = field.substring(0, separator).trim()
        val headerValue = field.substring(separator + 1).trimStart()
        if (name.isNotBlank()) put(name, headerValue)
      }
    }
  }

  private fun isHttpUri(raw: String): Boolean {
    val scheme =
      runCatching { URI(raw).scheme }
        .getOrNull()
        ?.lowercase()
    return scheme == "http" || scheme == "https"
  }
}
