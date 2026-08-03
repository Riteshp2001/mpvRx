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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenCodeClient(
  private val client: OkHttpClient,
  private val json: Json,
) : AiClient {
  companion object {
    private const val BASE_URL = "https://opencode.ai/zen/v1"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }

  private enum class Protocol { RESPONSES, ANTHROPIC, GOOGLE, CHAT_COMPLETIONS }

  private val apiClient =
    client
      .newBuilder()
      .connectTimeout(60, TimeUnit.SECONDS)
      .readTimeout(120, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()

  override suspend fun fetchModels(apiKey: String): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val response =
          apiClient
            .newCall(
              Request
                .Builder()
                .url("$BASE_URL/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build(),
            ).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
          throw IllegalStateException("OpenCode API error ${response.code}: ${AiResponseParser.error(json, body)}")
        }
        AiResponseParser.modelArray(json, body, "OpenCode").mapNotNull { model ->
          val id = model.string("id") ?: return@mapNotNull null
          if (!AiModelCapabilities.isTextGenerationModel(id)) return@mapNotNull null
          val displayName = model.string("name") ?: model.string("display_name") ?: id
          val pricing = model["pricing"] as? JsonObject
          AiModelInfo(
            id = id,
            displayName = displayName,
            isFree = AiModelPricing.isZeroCost(pricing) || id.endsWith("-free", ignoreCase = true),
          )
        }
      }
    }

  override suspend fun verifyKey(apiKey: String): Result<String> =
    withContext(Dispatchers.IO) {
      fetchModels(apiKey).map { "API key verified successfully (${it.size} models available)" }
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
        val apiModel = model.removePrefix("opencode/")
        val protocol = protocolFor(apiModel)
        val payload =
          when (protocol) {
            Protocol.RESPONSES -> responsesPayload(apiModel, instruction, userInput, options)
            Protocol.ANTHROPIC -> anthropicPayload(apiModel, instruction, userInput, options)
            Protocol.GOOGLE -> googlePayload(instruction, userInput, options)
            Protocol.CHAT_COMPLETIONS -> chatPayload(apiModel, instruction, userInput, options)
          }
        val url =
          when (protocol) {
            Protocol.RESPONSES -> "$BASE_URL/responses"
            Protocol.ANTHROPIC -> "$BASE_URL/messages"
            Protocol.GOOGLE -> "$BASE_URL/models/$apiModel:generateContent"
            Protocol.CHAT_COMPLETIONS -> "$BASE_URL/chat/completions"
          }
        val request =
          Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .apply {
              if (protocol == Protocol.ANTHROPIC) {
                header("x-api-key", apiKey)
                header("anthropic-version", ANTHROPIC_VERSION)
              }
              if (protocol == Protocol.GOOGLE) header("x-goog-api-key", apiKey)
            }.post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = apiClient.newCall(request).execute()
        val body = response.body.string()
        if (!response.isSuccessful) {
          throw IllegalStateException("OpenCode generate error ${response.code}: ${AiResponseParser.error(json, body)}")
        }
        when (protocol) {
          Protocol.RESPONSES -> AiResponseParser.openAiResponses(json, body, "OpenCode")
          Protocol.ANTHROPIC -> AiResponseParser.anthropic(json, body, "OpenCode")
          Protocol.GOOGLE -> AiResponseParser.google(json, body, "OpenCode")
          Protocol.CHAT_COMPLETIONS -> AiResponseParser.openAiCompatible(json, body, "OpenCode")
        }
      }
    }

  private fun protocolFor(model: String): Protocol {
    val id = model.substringAfterLast('/').lowercase()
    return when {
      id.startsWith("gpt-") -> Protocol.RESPONSES
      id.startsWith("claude-") || id.startsWith("qwen") -> Protocol.ANTHROPIC
      id.startsWith("gemini-") -> Protocol.GOOGLE
      else -> Protocol.CHAT_COMPLETIONS
    }
  }

  private fun responsesPayload(
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions,
  ) = buildJsonObject {
    put("model", model)
    if (instruction.isNotBlank()) put("instructions", instruction)
    put("input", userInput)
    put("max_output_tokens", options.maxTokens)
    put("store", false)
  }

  private fun anthropicPayload(
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions,
  ) = buildJsonObject {
    put("model", model)
    put("max_tokens", options.maxTokens)
    if (instruction.isNotBlank()) put("system", instruction)
    put(
      "messages",
      buildJsonArray {
        add(
          buildJsonObject {
            put("role", "user")
            put("content", userInput)
          },
        )
      },
    )
    put("temperature", options.temperature)
  }

  private fun googlePayload(
    instruction: String,
    userInput: String,
    options: AiGenerationOptions,
  ) = buildJsonObject {
    if (instruction.isNotBlank()) {
      put(
        "systemInstruction",
        buildJsonObject {
          put("parts", buildJsonArray { add(buildJsonObject { put("text", instruction) }) })
        },
      )
    }
    put(
      "contents",
      buildJsonArray {
        add(
          buildJsonObject {
            put("role", "user")
            put("parts", buildJsonArray { add(buildJsonObject { put("text", userInput) }) })
          },
        )
      },
    )
    put(
      "generationConfig",
      buildJsonObject {
        put("maxOutputTokens", options.maxTokens)
        put("temperature", options.temperature)
      },
    )
  }

  private fun chatPayload(
    model: String,
    instruction: String,
    userInput: String,
    options: AiGenerationOptions,
  ) = buildJsonObject {
    put("model", model)
    put(
      "messages",
      buildJsonArray {
        if (instruction.isNotBlank()) {
          add(
            buildJsonObject {
              put("role", "system")
              put("content", instruction)
            },
          )
        }
        add(
          buildJsonObject {
            put("role", "user")
            put("content", userInput)
          },
        )
      },
    )
    put("max_tokens", options.maxTokens)
    put("temperature", options.temperature)
  }

  private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
