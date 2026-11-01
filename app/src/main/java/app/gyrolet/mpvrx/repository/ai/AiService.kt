/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.ai

import android.content.Context
import android.util.Log
import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.preferences.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AiService(
  private val context: Context,
  private val preferences: AiPreferences,
  private val openCodeClient: AiClient,
  private val groqClient: AiClient,
  private val openAiClient: AiClient,
  private val anthropicClient: AiClient,
  private val openRouterClient: AiClient,
  private val togetherClient: AiClient,
  private val json: Json,
) {
  companion object {
    private const val TAG = "AiService"
  }

  private val clients: Map<AiProvider, AiClient> =
    mapOf(
      AiProvider.OPENCODE to openCodeClient,
      AiProvider.GROQ to groqClient,
      AiProvider.OPENAI to openAiClient,
      AiProvider.ANTHROPIC to anthropicClient,
      AiProvider.OPENROUTER to openRouterClient,
      AiProvider.TOGETHER to togetherClient,
    )

  suspend fun fetchModels(): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      val provider = preferences.provider.get()
      fetchModelsForProvider(provider)
    }

  suspend fun fetchModelsForProvider(provider: AiProvider): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      val apiKey = getApiKey(provider)

      if (apiKey.isBlank()) {
        return@withContext Result.failure(Exception("API key not configured for $provider"))
      }

      val client = clients[provider] ?: return@withContext Result.failure(Exception("Unknown provider: $provider"))
      client.fetchModels(apiKey).mapCatching { fetched ->
        AiModelCapabilities
          .normalize(fetched.filter { model -> AiModelCapabilities.isTextGenerationModel(model.id) })
          .also { models -> check(models.isNotEmpty()) { "$provider returned no compatible text-generation models" } }
      }
    }

  suspend fun fetchSpeechModelsForProvider(provider: AiProvider): Result<List<AiModelInfo>> =
    withContext(Dispatchers.IO) {
      if (provider !in setOf(AiProvider.GROQ, AiProvider.OPENAI, AiProvider.OPENROUTER)) {
        return@withContext Result.failure(IllegalArgumentException("$provider does not provide speech-to-text in mpvRx"))
      }
      val apiKey = getApiKey(provider)
      if (apiKey.isBlank()) {
        return@withContext Result.failure(Exception("API key not configured for $provider"))
      }
      val client = clients[provider] ?: return@withContext Result.failure(Exception("Unknown provider: $provider"))
      client.fetchModels(apiKey).mapCatching { fetched ->
        val liveModels = fetched.filter { model -> AiModelCapabilities.isSpeechToTextModel(model.id) }
        AiModelCapabilities
          .normalize(liveModels.ifEmpty { fallbackSpeechModels(provider) })
          .also { models -> check(models.isNotEmpty()) { "$provider returned no speech-to-text models" } }
      }
    }

  private fun fallbackSpeechModels(provider: AiProvider): List<AiModelInfo> =
    when (provider) {
      AiProvider.GROQ ->
        listOf(
          AiModelInfo("whisper-large-v3-turbo", "Whisper Large V3 Turbo"),
          AiModelInfo("whisper-large-v3", "Whisper Large V3"),
        )
      AiProvider.OPENAI ->
        listOf(
          AiModelInfo("gpt-4o-mini-transcribe", "GPT-4o Mini Transcribe"),
          AiModelInfo("gpt-4o-transcribe", "GPT-4o Transcribe"),
          AiModelInfo("whisper-1", "Whisper"),
        )
      AiProvider.OPENROUTER ->
        listOf(
          AiModelInfo("openai/whisper-large-v3-turbo", "Whisper Large V3 Turbo"),
          AiModelInfo("openai/whisper-large-v3", "Whisper Large V3"),
        )
      else -> emptyList()
    }

  suspend fun verifyKey(): Result<String> =
    withContext(Dispatchers.IO) {
      val provider = preferences.provider.get()

      val apiKey = getApiKey(provider)
      if (apiKey.isBlank()) {
        return@withContext Result.failure(Exception("API key not configured for $provider"))
      }

      val client = clients[provider] ?: return@withContext Result.failure(Exception("Unknown provider: $provider"))
      client.verifyKey(apiKey)
    }

  suspend fun generateWithAi(
    userInput: String,
    task: AiTask,
    extraInstruction: String? = null,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val provider = preferences.provider.get()
      val model = preferences.selectedModelFor(provider).get()
      val apiKey = getApiKey(provider)

      if (userInput.isBlank()) {
        return@withContext Result.failure(Exception("Empty input provided to AI"))
      }
      val customPromptEnabled = preferences.customPromptEnabled.get()
      val customPrompt = preferences.customPrompt.get()
      val customRenamePrompt = preferences.customRenamePrompt.get()
      val customSubtitleTranslationPrompt = preferences.customSubtitleTranslationPrompt.get()
      val customSubtitleFormatPrompt = preferences.customSubtitleFormatPrompt.get()

      if (apiKey.isBlank()) {
        return@withContext Result.failure(Exception("API key not configured for $provider"))
      }
      if (model.isBlank()) {
        return@withContext Result.failure(Exception("No AI model selected"))
      }

      var instruction =
        AiPrompts.resolveInstruction(
          task,
          customPromptEnabled,
          customPrompt,
          customRenamePrompt,
          customSubtitleTranslationPrompt,
          customSubtitleFormatPrompt,
        )
      if (extraInstruction != null) {
        instruction = "$instruction\n\n$extraInstruction"
      }

      val client =
        clients[provider]
          ?: return@withContext Result.failure(Exception("Unknown provider: $provider"))
      val options = generationOptionsFor(task)

      client.generateContent(apiKey, model, instruction, userInput, options).map { it.text }
    }

  suspend fun translateRealtimeCues(
    texts: List<String>,
    targetLanguage: String,
  ): Result<List<String>> = translateCueTexts(texts, targetLanguage)

  /**
   * Translates single-line cue texts and validates that every cue came back. `⟦n⟧` tokens stand for
   * subtitle markup and must survive translation unchanged.
   */
  suspend fun translateCueTexts(
    texts: List<String>,
    targetLanguage: String,
    sourceLanguage: String? = null,
  ): Result<List<String>> =
    withContext(Dispatchers.IO) {
      if (texts.isEmpty()) return@withContext Result.success(emptyList())
      if (targetLanguage.isBlank()) return@withContext Result.success(texts)

      val input =
        texts.mapIndexed { index, text ->
          "[CUE_$index] ${text.replace(Regex("\\s+"), " ").trim()}"
        }.joinToString("\n")
      val instruction =
        "TARGET LANGUAGE: $targetLanguage\n" +
          (sourceLanguage?.let { "SOURCE LANGUAGE: $it\n" } ?: "") +
          "Translate each cue independently. Return exactly one line per cue using the unchanged " +
          "[CUE_n] marker followed by its translation. Keep placeholders like ⟦0⟧ exactly as written, " +
          "in a natural position. Preserve cue order and do not add commentary."

      generateWithAi(input, AiTask.TRANSLATE, instruction).mapCatching { response ->
        val cleaned = response.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
        val marker = Regex("^\\s*\\[CUE_(\\d+)]\\s*(.+?)\\s*$")
        val translated = MutableList<String?>(texts.size) { null }
        cleaned.lineSequence().forEach { line ->
          val match = marker.matchEntire(line) ?: return@forEach
          val index = match.groupValues[1].toIntOrNull() ?: return@forEach
          val value = match.groupValues[2].trim()
          if (index in translated.indices && value.isNotBlank()) translated[index] = value
        }

        if (texts.size == 1 && translated[0] == null && cleaned.isNotBlank() && !cleaned.contains("[CUE_")) {
          translated[0] = cleaned
        }
        check(translated.all { !it.isNullOrBlank() }) { "AI returned an incomplete real-time subtitle translation" }
        translated.map { requireNotNull(it) }
      }
    }

  private fun generationOptionsFor(task: AiTask): AiGenerationOptions =
    when (task) {
      AiTask.RENAME -> AiGenerationOptions(maxTokens = 1024, temperature = 0.1)
      AiTask.SUBTITLE_FORMAT -> AiGenerationOptions(maxTokens = 1024, temperature = 0.1)
      AiTask.TRANSLATE -> AiGenerationOptions(maxTokens = 2048, temperature = 0.2)
    }

  suspend fun renameWithAi(
    currentName: String,
    extension: String?,
  ): Result<String> =
    withContext(Dispatchers.IO) {
      val result = generateWithAi(currentName, AiTask.RENAME)
      result.mapCatching { aiName ->
        val candidate = extractTaskValue(aiName, listOf("filename", "name", "title", "output"))
        val normalizedExtension = extension.orEmpty()
        val withoutExtension =
          if (
            normalizedExtension.isNotBlank() && candidate.endsWith(normalizedExtension, ignoreCase = true)
          ) {
            candidate.dropLast(normalizedExtension.length)
          } else {
            candidate
          }
        val clean = sanitizeFileName(withoutExtension)
        if (clean.isBlank()) throw IllegalStateException("AI returned an empty filename")
        "$clean$normalizedExtension"
      }
    }

  suspend fun formatTitleForSubtitleSearch(fileTitle: String): Result<String> =
    withContext(Dispatchers.IO) {
      val result = generateWithAi(fileTitle, AiTask.SUBTITLE_FORMAT)
      result.mapCatching {
        extractTaskValue(it, listOf("query", "title", "name", "output"))
          .replace(Regex("[\\r\\n]+"), " ")
          .trim()
          .ifBlank { throw IllegalStateException("AI returned an empty search title") }
      }
    }

  suspend fun verifyModel(): Result<String> =
    withContext(Dispatchers.IO) {
      val provider = preferences.provider.get()

      val model = preferences.selectedModelFor(provider).get()
      if (model.isBlank()) return@withContext Result.failure(Exception("No model selected"))

      val apiKey = getApiKey(provider)
      if (apiKey.isBlank()) return@withContext Result.failure(Exception("API key not configured"))

      val stored = preferences.availableModelsFor(provider).get()
      val knownModels =
        if (stored.isNotBlank()) {
          runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AiModelInfo.serializer()), stored)
          }.getOrDefault(emptyList())
        } else {
          emptyList()
        }
      val modelInfo = knownModels.firstOrNull { it.id == model }

      val sb = StringBuilder()
      if (modelInfo != null) {
        sb.appendLine("Available")
        sb.appendLine(if (modelInfo.isFree) "Free model" else "Paid model")
      }
      val client = clients[provider] ?: return@withContext Result.failure(Exception("Unknown provider"))
      val testResult =
        client.generateContent(
          apiKey,
          model,
          "Return only the word OK.",
          "Connectivity check",
          AiGenerationOptions(maxTokens = 512, temperature = 0.0),
        )
      if (testResult.isSuccess) {
        sb.append("API access working")
      } else {
        val msg = testResult.exceptionOrNull()?.message ?: "unknown error"
        when {
          msg.contains("quota", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("insufficient_quota", ignoreCase = true) ->
            sb.append("Quota exceeded / rate limited")
          msg.contains("not found", ignoreCase = true) ||
            msg.contains("not available", ignoreCase = true) ||
            msg.contains("model_not_found", ignoreCase = true) ->
            sb.append("Model not available")
          msg.contains("billing", ignoreCase = true) ||
            msg.contains("payment", ignoreCase = true) ||
            msg.contains("credit", ignoreCase = true) ||
            msg.contains("insufficient", ignoreCase = true) ->
            sb.append("Paid model \u2014 billing required")
          else ->
            sb.append("Access error: ${testResult.exceptionOrNull()?.message?.take(100)}")
        }
      }
      Result.success(sb.toString())
    }

  fun getApiKey(provider: AiProvider): String =
    when (provider) {
      AiProvider.OPENCODE -> preferences.openCodeApiKey.get()
      AiProvider.GROQ -> preferences.groqApiKey.get()
      AiProvider.OPENAI -> preferences.openaiApiKey.get()
      AiProvider.ANTHROPIC -> preferences.anthropicApiKey.get()
      AiProvider.OPENROUTER -> preferences.openrouterApiKey.get()
      AiProvider.TOGETHER -> preferences.togetherApiKey.get()
    }

  private fun extractTaskValue(
    raw: String,
    keys: List<String>,
  ): String {
    val sanitized =
      AiOutputSanitizer
        .splitReasoning(raw)
        .finalText
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
    if (sanitized.isBlank()) return ""
    val objectValue =
      runCatching {
        val parsed = json.parseToJsonElement(AiOutputSanitizer.stripCodeFence(sanitized)) as? JsonObject
        keys.firstNotNullOfOrNull { key ->
          (parsed?.get(key) as? JsonPrimitive)?.contentOrNull
        }
      }.getOrNull()
    val value = objectValue ?: sanitized
    return AiOutputSanitizer
      .stripCodeFence(value)
      .lineSequence()
      .firstOrNull { it.isNotBlank() }
      .orEmpty()
      .trim()
      .removeSurrounding("\"")
      .removeSurrounding("'")
  }

  private fun sanitizeFileName(value: String): String =
    value
      .replace(Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]"), " ")
      .replace(Regex("\\s+"), " ")
      .trim(' ', '.')
      .take(180)
      .trimEnd(' ', '.')
}
