/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import app.gyrolet.mpvrx.network.awaitResponse
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal class HttpReply(
  val code: Int,
  val body: String,
  val retryAfterMs: Long?,
)

/** Executes a request and turns transport failures into [SubtitleTranslationException.Network]. */
internal suspend fun OkHttpClient.fetch(
  request: Request,
  serviceName: String,
): HttpReply =
  try {
    newCall(request).awaitResponse().use { response ->
      HttpReply(
        code = response.code,
        body = response.body.string(),
        retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000),
      )
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: IOException) {
    if (e.message?.startsWith("Blocked:") == true) throw SubtitleTranslationException.Configuration("$serviceName — ${e.message}")
    throw SubtitleTranslationException.Network("Couldn't reach $serviceName. Check your internet connection or the server address.", e)
  }

/** Maps HTTP errors to user-facing exceptions; returns normally for 2xx. */
internal fun HttpReply.throwIfError(
  serviceName: String,
  detail: String? = null,
) {
  val suffix = detail?.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" }.orEmpty()
  when {
    code in 200..299 -> return
    code == 401 || code == 403 -> throw SubtitleTranslationException.Auth("$serviceName rejected the request (HTTP $code). Check the API key$suffix")
    code == 429 -> throw SubtitleTranslationException.RateLimited("$serviceName is rate limiting requests$suffix", retryAfterMs)
    code >= 500 -> throw SubtitleTranslationException.Server("$serviceName had a server error (HTTP $code)$suffix")
    else -> throw SubtitleTranslationException.Configuration("$serviceName returned HTTP $code$suffix")
  }
}

internal fun unescapeHtmlEntities(text: String): String =
  text
    .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.let { code -> String(Character.toChars(code)) } ?: it.value }
    .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toIntOrNull(16)?.let { code -> String(Character.toChars(code)) } ?: it.value }
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
