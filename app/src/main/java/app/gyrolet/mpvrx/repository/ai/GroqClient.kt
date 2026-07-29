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

@Serializable
private data class GroqErrorBody(
  val error: GroqErrorDetail? = null,
)

@Serializable
private data class GroqErrorDetail(
  val message: String? = null,
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
    client.newBuilder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder()
        .url("$BASE_URL/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = apiClient.newCall(request).execute()
      val body = response.body.string()

      if (!response.isSuccessful) {
        val errorMsg = parseError(body)
        throw Exception("Groq API error ${response.code}: $errorMsg")
      }

      val parsed = json.decodeFromString<GroqModelListResponse>(body)
      parsed.data
        .filter { AiModelCapabilities.isTextGenerationModel(it.id) }
        .map {
          val displayName = if (it.owned_by != null) "${it.id} (${it.owned_by})" else it.id
          AiModelInfo(
            id = it.id,
            displayName = displayName,
            isFree = AiModelPricing.isZeroCost(it.pricing),
          )
        }
    }
  }

  override suspend fun verifyKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
      val request = Request.Builder()
        .url("$BASE_URL/models")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()

      val response = apiClient.newCall(request).execute()
      if (!response.isSuccessful) {
        val body = response.body.string()
        throw Exception("Invalid API key: ${response.code} $body")
      }
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
        GroqChatRequest.serializer(),
        GroqChatRequest(
          model = model,
          messages = listOf(
            GroqMessage(role = "system", content = instruction),
            GroqMessage(role = "user", content = userInput),
          ),
          temperature = options.temperature,
          maxTokens = options.maxTokens,
        ),
      )

      val request = Request.Builder()
        .url("$BASE_URL/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
        .build()

      val response = apiClient.newCall(request).execute()
      val body = response.body.string()

      if (!response.isSuccessful) {
        val errorMsg = parseError(body)
        throw Exception("Groq generate error ${response.code}: $errorMsg")
      }

      AiResponseParser.openAiCompatible(json, body, "Groq")
    }
  }

  private fun parseError(body: String): String = try {
    val error = json.decodeFromString<GroqErrorBody>(body)
    error.error?.message ?: body
  } catch (_: Exception) {
    body.take(200)
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
