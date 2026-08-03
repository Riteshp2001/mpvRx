/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
private data class TogModel(
  val id: String,
  val type: String? = null,
  val display_name: String? = null,
  val displayName: String? = null,
  val pricing: JsonObject? = null,
)

@Serializable
private data class TogMessage(
  val role: String,
  val content: String,
)

@Serializable
private data class TogErrorBody(
  val error: TogErrorDetail? = null,
)

@Serializable
private data class TogErrorDetail(
  val message: String? = null,
)

@Serializable
private data class TogChatRequest(
  val model: String,
  val messages: List<TogMessage>,
  val temperature: Double = 0.3,
  @SerialName("max_tokens") val maxTokens: Int = 200,
)

class TogetherClient(
  private val client: OkHttpClient,
  private val json: Json,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://api.together.xyz/v1"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }

  private val apiClient: OkHttpClient =
    client
      .newBuilder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val request =
          Request
            .Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val response = apiClient.newCall(request).execute()
        val body = response.body.string()

        if (!response.isSuccessful) throw Exception("Together API error ${response.code}: ${parseError(body)}")

        AiResponseParser
          .modelArray(json, body, "Together")
          .mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(TogModel.serializer(), element) }.getOrNull()
          }.filter { it.type == null || it.type == "chat" || it.type == "language" }
          .map { model ->
            AiModelInfo(
              id = model.id,
              displayName = model.displayName ?: model.display_name ?: model.id,
              isFree = AiModelPricing.isZeroCost(model.pricing),
            )
          }
      }
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val request =
          Request
            .Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()

        val response = apiClient.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Invalid API key: ${response.code}")
        "API key verified successfully"
      }
    }

  override suspend fun generateContent(
    apiKey: String,
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions,
  ): Result<AiGeneratedContent> =
    withContext(Dispatchers.IO) {
      runCatching {
        val requestBody =
          json.encodeToString(
            TogChatRequest.serializer(),
            TogChatRequest(
              model = model,
              messages =
                listOf(
                  TogMessage(role = "system", content = instruction),
                  TogMessage(role = "user", content = userInput),
                ),
              temperature = options.temperature,
              maxTokens = options.maxTokens,
            ),
          )

        val request =
          Request
            .Builder()
            .url("$BASE_URL/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = apiClient.newCall(request).execute()
        val body = response.body.string()

        if (!response.isSuccessful) throw Exception("Together generate error ${response.code}: ${parseError(body)}")

        AiResponseParser.openAiCompatible(json, body, "Together")
      }
    }

  private fun parseError(body: String): String =
    try {
      val error = json.decodeFromString<TogErrorBody>(body)
      error.error?.message ?: body
    } catch (_: Exception) {
      body.take(200)
    }
}
