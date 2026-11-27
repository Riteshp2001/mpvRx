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
private data class AnthropicModel(
  val id: String,
  val display_name: String? = null,
  @SerialName("created_at") val createdAt: String? = null,
  val pricing: JsonObject? = null,
)

@Serializable
private data class AnthropicModelListResponse(
  val data: List<AnthropicModel> = emptyList(),
  @SerialName("has_more") val hasMore: Boolean = false,
  @SerialName("last_id") val lastId: String? = null,
)

@Serializable
private data class AnthropicTextContent(
  val type: String = "text",
  val text: String,
)

@Serializable
private data class AnthropicMessage(
  val role: String,
  val content: List<AnthropicTextContent>,
)

@Serializable
private data class AnthropicMessagesRequest(
  val model: String,
  @SerialName("max_tokens") val maxTokens: Int = 1024,
  val messages: List<AnthropicMessage>,
  val system: String = "",
  val temperature: Double = 0.3,
)

class AnthropicClient(
  private val client: OkHttpClient,
  private val json: Json,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://api.anthropic.com/v1"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MODEL_PAGE_SIZE = 1_000
    private const val MAX_MODEL_PAGES = 20
  }

  private val apiClient: OkHttpClient =
    client.withAiChatTimeouts()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val models = mutableListOf<AnthropicModel>()
        var afterId: String? = null
        var page = 0
        while (page < MAX_MODEL_PAGES) {
          val url =
            okhttp3.HttpUrl
              .Builder()
              .scheme("https")
              .host("api.anthropic.com")
              .addPathSegments("v1/models")
              .addQueryParameter("limit", MODEL_PAGE_SIZE.toString())
              .apply { afterId?.let { addQueryParameter("after_id", it) } }
              .build()
          val request =
            Request
              .Builder()
              .url(url)
              .header("x-api-key", apiKey)
              .header("anthropic-version", ANTHROPIC_VERSION)
              .get()
              .build()

          val parsed =
            apiClient.newCall(request).awaitResponse().use { response ->
              val body = response.body.string()
              if (!response.isSuccessful) {
                throw Exception(
                  "Anthropic API error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}",
                )
              }
              json.decodeFromString<AnthropicModelListResponse>(body)
            }
          models += parsed.data
          if (!parsed.hasMore) break
          val nextAfterId =
            parsed.lastId?.takeIf { it.isNotBlank() && it != afterId }
              ?: throw IllegalStateException("Anthropic returned an invalid model pagination cursor")
          afterId = nextAfterId
          page++
        }
        if (page >=
          MAX_MODEL_PAGES
        ) {
          throw IllegalStateException("Anthropic model catalog exceeded $MAX_MODEL_PAGES pages")
        }

        models.map { model ->
          AiModelInfo(
            id = model.id,
            displayName = model.display_name ?: model.id,
            isFree = AiModelPricing.isZeroCost(model.pricing),
          )
        }
      }
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val request =
          Request
            .Builder()
            .url("$BASE_URL/models")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .get()
            .build()

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
            AnthropicMessagesRequest.serializer(),
            AnthropicMessagesRequest(
              model = model,
              maxTokens = options.maxTokens.coerceAtLeast(256),
              messages =
                listOf(
                  AnthropicMessage(role = "user", content = listOf(AnthropicTextContent(text = userInput))),
                ),
              system = instruction,
              temperature = options.temperature,
            ),
          )

        val request =
          Request
            .Builder()
            .url("$BASE_URL/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception(
              "Anthropic generate error ${response.code}: ${AiResponseParser.error(json, body, apiKey, 200)}",
            )
          }
          AiResponseParser.anthropic(json, body, "Anthropic")
        }
      }
    }
}
