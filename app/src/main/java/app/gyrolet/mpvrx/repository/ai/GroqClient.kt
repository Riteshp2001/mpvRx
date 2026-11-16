/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import app.gyrolet.mpvrx.network.awaitResponse
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

@Serializable
private data class GroqModel(
  val id: String,
  val owned_by: String? = null,
  val pricing: JsonObject? = null,
)

@Serializable
private data class GroqModelListResponse(
  val data: List<GroqModel> = emptyList(),
)

@Serializable
private data class GroqMessage(
  val role: String,
  val content: String,
)

class GroqClient(
  private val client: OkHttpClient,
  private val json: Json,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://api.groq.com/openai/v1"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }

  private val apiClient: OkHttpClient =
    client.withAiChatTimeouts()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val request =
          bearerGetRequest("$BASE_URL/models", apiKey)

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("Groq API error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}")
          }

          val parsed = json.decodeFromString<GroqModelListResponse>(body)
          parsed.data.map {
            val displayName = if (it.owned_by != null) "${it.id} (${it.owned_by})" else it.id
            AiModelInfo(
              id = it.id,
              displayName = displayName,
              isFree = AiModelPricing.isZeroCost(it.pricing),
            )
          }
        }
      }
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val request =
          bearerGetRequest("$BASE_URL/models", apiKey)

        apiClient.newCall(request).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            val body = response.body.string()
            throw Exception("Invalid API key: ${response.code} $body")
          }
          "API key verified successfully"
        }
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
      runCatchingCancellable {
        val requestBody =
          json.encodeToString(
            GroqChatRequest.serializer(),
            GroqChatRequest(
              model = model,
              messages =
                listOf(
                  GroqMessage(role = "system", content = instruction),
                  GroqMessage(role = "user", content = userInput),
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

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("Groq generate error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}")
          }
          AiResponseParser.openAiCompatible(json, body, "Groq")
        }
      }
    }
}

@Serializable
private data class GroqChatRequest(
  val model: String,
  val messages: List<GroqMessage>,
  val temperature: Double = 0.3,
  @SerialName("max_completion_tokens")
  val maxTokens: Int = 200,
)
