/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
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
private data class AnthropicModel(
  val id: String,
  val display_name: String? = null,
  @SerialName("created_at") val createdAt: String? = null,
  val pricing: JsonObject? = null,
)

@Serializable
private data class AnthropicModelListResponse(
  val data: List<AnthropicModel> = emptyList(),
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
private data class AnthropicErrorBody(val error: AnthropicErrorDetail? = null)

@Serializable
private data class AnthropicErrorDetail(val message: String? = null)

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
  }

  private val apiClient: OkHttpClient =
    client.newBuilder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder()
        .url("$BASE_URL/models")
        .header("x-api-key", apiKey)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .get()
        .build()

      val response = apiClient.newCall(request).execute()
      val body = response.body.string()

      if (!response.isSuccessful) throw Exception("Anthropic API error ${response.code}: ${parseError(body)}")

      val parsed = json.decodeFromString<AnthropicModelListResponse>(body)
      parsed.data.map { model ->
        AiModelInfo(
          id = model.id,
          displayName = model.display_name ?: model.id,
          isFree = AiModelPricing.isZeroCost(model.pricing),
        )
      }
    }
  }

  override suspend fun verifyKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder()
        .url("$BASE_URL/models")
        .header("x-api-key", apiKey)
        .header("anthropic-version", ANTHROPIC_VERSION)
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
  ): Result<AiGeneratedContent> = withContext(Dispatchers.IO) {
    runCatching {
      val requestBody = json.encodeToString(
        AnthropicMessagesRequest.serializer(),
        AnthropicMessagesRequest(
          model = model,
          maxTokens = options.maxTokens.coerceAtLeast(256),
          messages = listOf(
            AnthropicMessage(role = "user", content = listOf(AnthropicTextContent(text = userInput))),
          ),
          system = instruction,
          temperature = options.temperature,
        ),
      )

      val request = Request.Builder()
        .url("$BASE_URL/messages")
        .header("x-api-key", apiKey)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("Content-Type", "application/json")
        .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val response = apiClient.newCall(request).execute()
      val body = response.body.string()

      if (!response.isSuccessful) throw Exception("Anthropic generate error ${response.code}: ${parseError(body)}")

      AiResponseParser.anthropic(json, body, "Anthropic")
    }
  }

  private fun parseError(body: String): String = try {
    val error = json.decodeFromString<AnthropicErrorBody>(body)
    error.error?.message ?: body
  } catch (_: Exception) {
    body.take(200)
  }
}
