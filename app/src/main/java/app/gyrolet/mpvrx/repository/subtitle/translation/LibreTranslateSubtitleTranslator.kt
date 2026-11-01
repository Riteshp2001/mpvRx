/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LibreTranslate (open source, self-hostable). Free when you run your own server; the official
 * libretranslate.com instance needs a paid API key. Sends a whole batch in one request.
 */
class LibreTranslateSubtitleTranslator(
  private val client: OkHttpClient,
  private val json: Json,
  serverUrl: String,
  private val apiKey: String,
) : SubtitleTranslator {
  private val baseUrl: String = normalizeBaseUrl(serverUrl)

  override val cacheId: String = "libre:$baseUrl"
  override val maxTextsPerRequest: Int = 40
  override val maxCharsPerRequest: Int = 3_000
  override val preservesInlineMarkup: Boolean = false

  init {
    if (baseUrl.isBlank()) {
      throw SubtitleTranslationException.Configuration("Set your LibreTranslate server address in Settings → Subtitles → Translation.")
    }
  }

  override suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> {
    val payload =
      buildJsonObject {
        putJsonArray("q") { texts.forEach { add(JsonPrimitive(it)) } }
        put("source", languageCode(sourceLanguage) ?: "auto")
        put("target", languageCode(targetLanguage))
        put("format", "text")
        if (apiKey.isNotBlank()) put("api_key", apiKey.trim())
      }
    val request =
      Request
        .Builder()
        .url("$baseUrl/translate")
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()
    val reply = client.fetch(request, SERVICE)
    val root = runCatching { json.parseToJsonElement(reply.body) as? JsonObject }.getOrNull()
    val error = root?.get("error")?.jsonPrimitive?.contentOrNull
    if (reply.code == 400 && error != null && error.contains("not supported", ignoreCase = true)) {
      throw SubtitleTranslationException.UnsupportedLanguage("This LibreTranslate server doesn't support that language: $error")
    }
    if (reply.code == 400 && error != null && error.contains("api_key", ignoreCase = true)) {
      throw SubtitleTranslationException.Auth("This LibreTranslate server needs an API key: $error")
    }
    reply.throwIfError(SERVICE, error)

    val translated = root?.get("translatedText")
    return when (translated) {
      is JsonArray -> translated.map { it.jsonPrimitive.contentOrNull.orEmpty() }
      is JsonPrimitive -> listOf(translated.contentOrNull.orEmpty())
      else -> throw SubtitleTranslationException.InvalidResponse("LibreTranslate returned an unexpected answer")
    }
  }

  /** Lists the languages the server offers; used by the settings "Test connection" button. */
  suspend fun testConnection(): Result<Int> =
    runCatching {
      val reply = client.fetch(Request.Builder().url("$baseUrl/languages").get().build(), SERVICE)
      reply.throwIfError(SERVICE)
      json.parseToJsonElement(reply.body).jsonArray.size
    }

  private fun languageCode(code: String?): String? =
    when (code) {
      null -> null
      "zh-TW" -> "zt"
      else -> code
    }

  companion object {
    private const val SERVICE = "LibreTranslate"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun normalizeBaseUrl(url: String): String {
      val trimmed = url.trim().trimEnd('/')
      if (trimmed.isBlank()) return ""
      // A bare IP or host:port is a home server (plain http); a bare domain is a public instance (https).
      val host = trimmed.substringBefore('/')
      val local = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""").matches(host) || host.contains(':') || host.startsWith("localhost")
      val withScheme =
        when {
          trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
          local -> "http://$trimmed"
          else -> "https://$trimmed"
        }
      return withScheme.removeSuffix("/translate").trimEnd('/')
    }
  }
}
