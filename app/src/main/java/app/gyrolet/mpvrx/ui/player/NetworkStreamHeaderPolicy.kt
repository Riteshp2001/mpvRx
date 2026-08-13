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
 * PlayerActivity may temporarily set per-request properties before a load, while PlaybackSession
 * intentionally re-applies the headers stored on the [PlaybackItem] at the actual `loadfile`
 * boundary. Keeping the configured User-Agent on the item prevents an otherwise header-less
 * network item from replacing mpv's configured User-Agent with an empty string.
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

  private fun isHttpUri(raw: String): Boolean {
    val scheme =
      runCatching { URI(raw).scheme }
        .getOrNull()
        ?.lowercase()
    return scheme == "http" || scheme == "https"
  }
}
