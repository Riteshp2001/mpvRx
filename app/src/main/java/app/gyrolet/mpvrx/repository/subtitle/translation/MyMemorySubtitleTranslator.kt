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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * MyMemory (translated.net): free and keyless. Anonymous use is limited to a few thousand
 * characters per day; passing an email address raises the daily limit considerably.
 * A query may not exceed 500 bytes, so texts are sent one at a time or in small joined groups.
 */
class MyMemorySubtitleTranslator(
  private val client: OkHttpClient,
  private val json: Json,
  private val email: String,
) : SubtitleTranslator {
  override val cacheId: String = "mymemory"
  override val maxTextsPerRequest: Int = 6
  override val maxCharsPerRequest: Int = 400

  override suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> {
    val joined = texts.joinToString("\n")
    if (texts.size > 1 && joined.toByteArray().size <= MAX_QUERY_BYTES) {
      val lines = query(joined, sourceLanguage, targetLanguage).split('\n').map(String::trim).filter(String::isNotEmpty)
      if (lines.size == texts.size) return lines
    }
    // Joined request didn't line up (or was too long): one request per text.
    return texts.map { text ->
      if (text.toByteArray().size > MAX_QUERY_BYTES) {
        splitForQuery(text).map { query(it, sourceLanguage, targetLanguage) }.joinToString(" ")
      } else {
        query(text, sourceLanguage, targetLanguage)
      }
    }
  }

  private suspend fun query(
    text: String,
    sourceLanguage: String?,
    targetLanguage: String,
  ): String {
    val url =
      HttpUrl
        .Builder()
        .scheme("https")
        .host("api.mymemory.translated.net")
        .addPathSegment("get")
        .addQueryParameter("q", text)
        .addQueryParameter("langpair", "${languageCode(sourceLanguage) ?: "autodetect"}|${languageCode(targetLanguage)}")
        .apply { if (email.isNotBlank()) addQueryParameter("de", email.trim()) }
        .build()
    val reply = client.fetch(Request.Builder().url(url).get().build(), SERVICE)
    val root = runCatching { json.parseToJsonElement(reply.body).jsonObject }.getOrNull()
    val details = root?.get("responseDetails")?.jsonPrimitive?.contentOrNull
    val translated = (root?.get("responseData") as? JsonObject)?.get("translatedText")?.jsonPrimitive?.contentOrNull
    val status = root?.get("responseStatus")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: reply.code
    val quotaFinished = root?.get("quotaFinished")?.jsonPrimitive?.booleanOrNull == true

    if (quotaFinished || translated?.startsWith("MYMEMORY WARNING", ignoreCase = true) == true || status == 429 && details?.contains("QUOTA", true) == true) {
      throw SubtitleTranslationException.QuotaExceeded(
        if (email.isBlank()) {
          "MyMemory's free daily limit is used up. Add your email in translation settings for a higher limit, or switch provider."
        } else {
          "MyMemory's daily limit for your email is used up. Try again tomorrow or switch provider."
        },
      )
    }
    if (details?.contains("INVALID LANGUAGE PAIR", ignoreCase = true) == true || details?.contains("IS AN INVALID TARGET LANGUAGE", true) == true) {
      throw SubtitleTranslationException.UnsupportedLanguage("MyMemory doesn't support this language pair.")
    }
    reply.throwIfError(SERVICE, details)
    if (status !in 200..299) reply.copyWithCode(status).throwIfError(SERVICE, details)
    return translated?.let(::unescapeHtmlEntities)?.trim()
      ?: throw SubtitleTranslationException.InvalidResponse("MyMemory returned an empty answer")
  }

  private fun HttpReply.copyWithCode(code: Int) = HttpReply(code, body, retryAfterMs)

  private fun splitForQuery(text: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    for (word in text.split(' ')) {
      if (current.isNotEmpty() && (current.toString() + " " + word).toByteArray().size > MAX_QUERY_BYTES) {
        parts += current.toString()
        current.clear()
      }
      if (current.isNotEmpty()) current.append(' ')
      current.append(word)
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
  }

  private fun languageCode(code: String?): String? =
    when (code) {
      null -> null
      "zh" -> "zh-CN"
      else -> code
    }

  private companion object {
    const val SERVICE = "MyMemory"
    const val MAX_QUERY_BYTES = 480
  }
}
