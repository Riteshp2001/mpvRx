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
    client.withAiChatTimeouts()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val request =
          bearerGetRequest("$BASE_URL/models", apiKey)

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("Together API error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}")
          }

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
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val request =
          bearerGetRequest("$BASE_URL/models", apiKey)

        apiClient.newCall(request).awaitResponse().use { response ->
          if (!response.isSuccessful) throw Exception("Invalid API key: ${response.code}")
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

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception(
              "Together generate error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}",
            )
          }
          AiResponseParser.openAiCompatible(json, body, "Together")
        }
      }
    }
}
