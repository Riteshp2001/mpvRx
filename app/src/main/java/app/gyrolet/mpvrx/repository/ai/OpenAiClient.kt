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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

@Serializable
private data class CompatibleChatRequest(
  val model: String,
  val messages: List<OpenAiMessage>,
  val temperature: Double,
  @SerialName("max_tokens") val maxTokens: Int,
)

class OpenAiClient(
  private val client: OkHttpClient,
  private val json: Json,
  private val baseUrl: () -> String = { BASE_URL },
  private val providerName: String = "OpenAI",
  private val compatible: Boolean = false,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://api.openai.com/v1"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }

  private val apiClient: OkHttpClient =
    client.withAiChatTimeouts()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val target = endpoint("models")
        val request = requestBuilder(target, apiKey).get().build()

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("$providerName at $target returned HTTP ${response.code}: ${parseError(body, apiKey)}")
          }

          val parsed = json.decodeFromString<OpenAiModelListResponse>(body)
          parsed.data.map { model ->
            AiModelInfo(
              id = model.id,
              displayName = model.id,
              isFree = AiModelPricing.isZeroCost(model.pricing),
            )
          }
        }
      }
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      runCatchingCancellable {
        val target = endpoint("models")
        val request = requestBuilder(target, apiKey).get().build()

        apiClient.newCall(request).awaitResponse().use { response ->
          if (!response.isSuccessful) {
            throw Exception(
              "$providerName at $target returned HTTP ${response.code}: ${parseError(response.body.string(), apiKey)}",
            )
          }
          if (compatible) "Endpoint ready" else "API key verified successfully"
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
        val messages = listOf(
          OpenAiMessage(role = if (!compatible && isReasoningModel(model)) "developer" else "system", content = instruction),
          OpenAiMessage(role = "user", content = userInput),
        )
        val requestBody =
          if (compatible) {
            json.encodeToString(
              CompatibleChatRequest.serializer(),
              CompatibleChatRequest(model, messages, options.temperature, options.maxTokens),
            )
          } else {
            json.encodeToString(
              OpenAiChatRequest.serializer(),
              OpenAiChatRequest(
                model = model,
                messages = messages,
                temperature = options.temperature.takeUnless { isReasoningModel(model) },
                maxCompletionTokens = options.maxTokens,
              ),
            )
          }

        val target = endpoint("chat/completions")
        val request =
          requestBuilder(target, apiKey)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        apiClient.newCall(request).awaitResponse().use { response ->
          val body = response.body.string()
          if (!response.isSuccessful) {
            throw Exception("$providerName at $target returned HTTP ${response.code}: ${parseError(body, apiKey)}")
          }
          AiResponseParser.openAiCompatible(json, body, providerName)
        }
      }
    }

  private fun endpoint(path: String): HttpUrl {
    val url = baseUrl().trim().trimEnd('/').toHttpUrlOrNull()
    requireNotNull(url) { "$providerName base URL must be an absolute HTTP or HTTPS URL" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
      "$providerName base URL must not contain credentials, a query, or a fragment"
    }
    return url.newBuilder().addPathSegments(path).build()
  }

  private fun requestBuilder(target: HttpUrl, apiKey: String): Request.Builder =
    Request.Builder().url(target).apply {
      if (apiKey.isNotBlank()) {
        require(apiKey.all { it.code in 0x21..0x7e }) { "API key must be a single printable token without spaces" }
        header("Authorization", "Bearer $apiKey")
      }
    }

  private fun parseError(body: String, apiKey: String): String {
    val message = try {
      json.decodeFromString<OpenAiErrorBody>(body).error?.message ?: body
    } catch (_: SerializationException) {
      body
    }
    return (if (apiKey.isBlank()) message else message.replace(apiKey, "[redacted]")).take(200)
  }

  private fun isReasoningModel(model: String): Boolean {
    val id = model.substringAfterLast('/').lowercase()
    return id.matches(Regex("^o\\d.*")) ||
      id.matches(Regex("^gpt-[5-9].*")) ||
      id.contains("reasoning") ||
      id.contains("codex")
  }
}
