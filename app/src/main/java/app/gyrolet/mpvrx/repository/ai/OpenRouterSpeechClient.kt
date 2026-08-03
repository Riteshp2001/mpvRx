/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class OpenRouterSpeechClient(
  client: OkHttpClient,
  private val json: Json,
) : SpeechToTextClient {
  companion object {
    private const val URL = "https://openrouter.ai/api/v1/audio/transcriptions"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }

  private val apiClient =
    client
      .newBuilder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(180, TimeUnit.SECONDS)
      .writeTimeout(180, TimeUnit.SECONDS)
      .build()

  override suspend fun transcribe(
    apiKey: String,
    audioFile: File,
    language: String?,
    model: String?,
  ): Result<SpeechTranscript> =
    withContext(Dispatchers.IO) {
      runCatching {
        val encoded = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val format =
          audioFile.extension.lowercase(Locale.ROOT).let {
            when (it) {
              "m4a", "mp4", "aac", "mp3", "wav", "flac", "ogg", "webm" -> it
              else -> "m4a"
            }
          }
        val payload =
          buildJsonObject {
            put("model", model?.takeIf { it.isNotBlank() } ?: "openai/whisper-large-v3-turbo")
            put(
              "input_audio",
              buildJsonObject {
                put("data", encoded)
                put("format", format)
              },
            )
            if (!language.isNullOrBlank()) put("language", language)
            put("temperature", 0)
          }
        val response =
          apiClient
            .newCall(
              Request
                .Builder()
                .url(URL)
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://mpvrx.app")
                .header("X-OpenRouter-Title", "mpvRx")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
            ).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
          throw IllegalStateException(
            "OpenRouter transcription failed ${response.code}: ${AiResponseParser.error(json, body)}",
          )
        }
        val root = json.parseToJsonElement(body).jsonObject
        val text =
          root["text"]
            ?.jsonPrimitive
            ?.contentOrNull
            .orEmpty()
            .trim()
        if (text.isBlank()) throw IllegalStateException("OpenRouter returned an empty transcription")
        SpeechTranscript(text, createHeuristicSegments(text))
      }
    }

  private fun createHeuristicSegments(text: String): List<SpeechSegment> =
    text
      .split(Regex("\\s+"))
      .filter { it.isNotBlank() }
      .chunked(9)
      .mapIndexed { index, words ->
        val startMs = index * 3_500L
        SpeechSegment(startMs, startMs + 3_200L, words.joinToString(" "))
      }
}
