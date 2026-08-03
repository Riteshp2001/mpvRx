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
private data class OpenAiModel(
  val id: String,
  val pricing: JsonObject? = null,
)

@Serializable
private data class OpenAiModelListResponse(
  val data: List<OpenAiModel> = emptyList(),
)

@Serializable
private data class OpenAiMessage(
  val role: String,
  val content: String,
)

@Serializable
private data class OpenAiErrorBody(
  val error: OpenAiErrorDetail? = null,
)

@Serializable
private data class OpenAiErrorDetail(
  val message: String? = null,
)

@Serializable
private data class OpenAiChatRequest(
  val model: String,
  val messages: List<OpenAiMessage>,
  val temperature: Double? = null,
  @SerialName("max_completion_tokens") val maxCompletionTokens: Int = 200,
)

class OpenAiClient(
  private val client: OkHttpClient,
  private val json: Json,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://api.openai.com/v1"
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

        if (!response.isSuccessful) throw Exception("OpenAI API error ${response.code}: ${parseError(body)}")

        val parsed = json.decodeFromString<OpenAiModelListResponse>(body)
        parsed.data.filter { AiModelCapabilities.isTextGenerationModel(it.id) }.map { model ->
          AiModelInfo(
            id = model.id,
            displayName = model.id,
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
            OpenAiChatRequest.serializer(),
            OpenAiChatRequest(
              model = model,
              messages =
                listOf(
                  OpenAiMessage(role = if (isReasoningModel(model)) "developer" else "system", content = instruction),
                  OpenAiMessage(role = "user", content = userInput),
                ),
              temperature = options.temperature.takeUnless { isReasoningModel(model) },
              maxCompletionTokens = options.maxTokens,
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

        if (!response.isSuccessful) throw Exception("OpenAI generate error ${response.code}: ${parseError(body)}")

        AiResponseParser.openAiCompatible(json, body, "OpenAI")
      }
    }

  private fun parseError(body: String): String =
    try {
      val error = json.decodeFromString<OpenAiErrorBody>(body)
      error.error?.message ?: body
    } catch (_: Exception) {
      body.take(200)
    }

  private fun isReasoningModel(model: String): Boolean {
    val id = model.substringAfterLast('/').lowercase()
    return id.startsWith("o1") ||
      id.startsWith("o3") ||
      id.startsWith("o4") ||
      id.startsWith("gpt-5") ||
      id.contains("codex")
  }
}
