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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest
import java.util.Locale

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

  @Serializable
  private data class SubtitleTranslationCache(
    val key: String,
    val translatedChunks: List<String?>,
    val updatedAtMs: Long,
  )

  data class SubtitleTranslationProgress(
    val progress: Float,
    val completedChunks: Int,
    val totalChunks: Int,
    val isResuming: Boolean = false,
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
      client.fetchModels(apiKey)
    }

  fun fetchSpeechModelsForProvider(provider: AiProvider): Result<List<AiModelInfo>> =
    when (provider) {
      AiProvider.GROQ ->
        Result.success(
          listOf(
            AiModelInfo("whisper-large-v3-turbo", "Whisper Large V3 Turbo"),
            AiModelInfo("whisper-large-v3", "Whisper Large V3"),
          ),
        )
      AiProvider.OPENAI ->
        Result.success(
          listOf(
            AiModelInfo("whisper-1", "Whisper"),
          ),
        )
      AiProvider.OPENROUTER ->
        Result.success(
          listOf(
            AiModelInfo("openai/whisper-large-v3-turbo", "Whisper Large V3 Turbo"),
            AiModelInfo("openai/whisper-large-v3", "Whisper Large V3"),
          ),
        )
      else -> Result.failure(IllegalArgumentException("$provider does not provide speech-to-text in mpvRx"))
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

  suspend fun isConfigured(): Boolean {
    val provider = preferences.provider.get()
    val apiKey = getApiKey(provider)
    return preferences.enabled.get() && apiKey.isNotBlank() && preferences.selectedModelFor(provider).get().isNotBlank()
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

  suspend fun translateSubtitle(
    content: String,
    targetLanguage: String,
    subtitleFormat: String? = null,
    onProgress: (SubtitleTranslationProgress) -> Unit = {},
  ): Result<String> =
    withContext(Dispatchers.IO) {
      try {
        val fmt = subtitleFormat?.lowercase(Locale.ROOT)
        val normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n")

        // ASS/SSA: header must be preserved verbatim; only Dialogue: lines translated
        if (fmt == "ass" || fmt == "ssa") {
          return@withContext translateAssContent(normalizedContent, targetLanguage, onProgress)
        }

        val chunks =
          when (fmt) {
            "srt", "vtt", "sbv", "srv1", "srv2", "srv3" ->
              normalizedContent.split(Regex("\n{2,}")).map(String::trim).filter { it.isNotBlank() }
            "ttml", "dfxp", "itt", "imsc" ->
              Regex("<p\\b[^>]*>.*?</p>", RegexOption.DOT_MATCHES_ALL)
                .findAll(normalizedContent)
                .map { it.value }
                .toList()
            "lrc", "krc" ->
              normalizedContent.lines().filter { it.isNotBlank() }
            else ->
              normalizedContent.lines().filter { it.isNotBlank() }
          }

        if (chunks.isEmpty()) return@withContext Result.success(content)

        val chunkSize = 15
        val totalChunks = (chunks.size + chunkSize - 1) / chunkSize
        val cacheKey = translationCacheKey(normalizedContent, targetLanguage, fmt)
        val cacheFile = translationCacheFile(cacheKey)
        val cachedChunks = loadTranslationCache(cacheKey, totalChunks)
        val translatedChunks = MutableList<String?>(totalChunks) { index -> cachedChunks.getOrNull(index) }
        val cachedCount = translatedChunks.count { it != null }
        if (cachedCount > 0) {
          onProgress(
            SubtitleTranslationProgress(
              progress = cachedCount.toFloat() / totalChunks,
              completedChunks = cachedCount,
              totalChunks = totalChunks,
              isResuming = true,
            ),
          )
        }

        for (i in 0 until totalChunks) {
          if (translatedChunks[i] != null) {
            continue
          }
          val start = i * chunkSize
          val end = minOf(start + chunkSize, chunks.size)
          val chunk = chunks.subList(start, end).joinToString("\n\n")

          val extra =
            buildString {
              append("TARGET LANGUAGE: $targetLanguage\n")
              append("OUTPUT FORMAT: keep the exact subtitle format and structure of the original file.")
              subtitleFormat?.let { append("\nSOURCE FORMAT: .$it") }
            }

          val result = generateWithAi(chunk, AiTask.TRANSLATE, extra)
          result
            .onSuccess {
              val clean = it.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
              translatedChunks[i] = clean
              saveTranslationCache(cacheFile, cacheKey, translatedChunks)
            }.onFailure { return@withContext Result.failure(it) }

          onProgress(
            SubtitleTranslationProgress(
              progress = translatedChunks.count { it != null }.toFloat() / totalChunks,
              completedChunks = translatedChunks.count { it != null },
              totalChunks = totalChunks,
              isResuming = cachedCount > 0,
            ),
          )
        }

        cacheFile.delete()
        Result.success(translatedChunks.filterNotNull().joinToString("\n\n"))
      } catch (e: Exception) {
        Log.e(TAG, "Subtitle translation failed", e)
        Result.failure(e)
      }
    }

  /**
   * ASS/SSA translation strategy:
   * - All header/style sections are kept verbatim (never sent to AI).
   * - Only the free-text field of each `Dialogue:` line is translated.
   * - A batch delimiter allows one AI call per 30 dialogue lines instead of N calls.
   */
  private suspend fun translateAssContent(
    content: String,
    targetLanguage: String,
    onProgress: (SubtitleTranslationProgress) -> Unit,
  ): Result<String> {
    data class DLine(
      val lineIdx: Int,
      val prefix: String,
      val text: String,
    )

    val lines = content.lines()
    val dialogueLines = mutableListOf<DLine>()

    lines.forEachIndexed { idx, line ->
      if (line.startsWith("Dialogue:", ignoreCase = true)) {
        // ASS has 10 comma-separated fields; text starts after the 9th comma
        var pos = -1
        var count = 0
        while (count < 9) {
          pos = line.indexOf(',', pos + 1)
          if (pos == -1) break
          count++
        }
        if (pos != -1 && pos + 1 < line.length) {
          dialogueLines.add(DLine(idx, line.substring(0, pos + 1), line.substring(pos + 1)))
        }
      }
    }

    if (dialogueLines.isEmpty()) return Result.success(content)

    val delimiter = "\u2016" // double vertical line, unlikely to appear in subtitles
    val chunks = dialogueLines.chunked(15)
    val translatedTextByIdx = mutableMapOf<Int, String>()
    val cacheKey = translationCacheKey(content, targetLanguage, "ass")
    val cacheFile = translationCacheFile(cacheKey)
    val cachedChunks = loadTranslationCache(cacheKey, chunks.size)
    val translatedChunkTexts = MutableList<String?>(chunks.size) { index -> cachedChunks.getOrNull(index) }
    val cachedCount = translatedChunkTexts.count { it != null }

    chunks.forEachIndexed { chunkIdx, chunk ->
      translatedChunkTexts[chunkIdx]?.let { cached ->
        cached.split(delimiter).forEachIndexed { partIdx, text ->
          if (partIdx < chunk.size) translatedTextByIdx[chunk[partIdx].lineIdx] = text.trim()
        }
        onProgress(
          SubtitleTranslationProgress(
            progress = translatedChunkTexts.count { it != null }.toFloat() / chunks.size,
            completedChunks = translatedChunkTexts.count { it != null },
            totalChunks = chunks.size,
            isResuming = cachedCount > 0,
          ),
        )
        return@forEachIndexed
      }

      val batchInput = chunk.joinToString(delimiter) { it.text }
      val extra =
        "TARGET LANGUAGE: $targetLanguage\n" +
          "OUTPUT FORMAT: Return ONLY the translated segments in the same order separated by '$delimiter'. " +
          "Preserve ALL ASS override tags like {\\an8}, {\\pos()}, {\\i1}, {\\b1} exactly. " +
          "Do NOT add or remove any '$delimiter' separators."

      val result = generateWithAi(batchInput, AiTask.TRANSLATE, extra)
      result
        .onSuccess { translated ->
          translatedChunkTexts[chunkIdx] = translated
          saveTranslationCache(cacheFile, cacheKey, translatedChunkTexts)
          val parts = translated.split(delimiter)
          parts.forEachIndexed { partIdx, text ->
            if (partIdx < chunk.size) translatedTextByIdx[chunk[partIdx].lineIdx] = text.trim()
          }
        }.onFailure { return Result.failure(it) }

      onProgress(
        SubtitleTranslationProgress(
          progress = translatedChunkTexts.count { it != null }.toFloat() / chunks.size,
          completedChunks = translatedChunkTexts.count { it != null },
          totalChunks = chunks.size,
          isResuming = cachedCount > 0,
        ),
      )
    }

    val resultLines =
      lines.mapIndexed { idx, line ->
        val dl = dialogueLines.find { it.lineIdx == idx }
        if (dl != null) "${dl.prefix}${translatedTextByIdx[idx] ?: dl.text}" else line
      }
    cacheFile.delete()
    return Result.success(resultLines.joinToString("\n"))
  }

  private fun translationCacheKey(
    content: String,
    targetLanguage: String,
    format: String?,
  ): String {
    val provider = preferences.provider.get()
    val model = preferences.selectedModelFor(provider).get()
    val input = listOf(content, targetLanguage, format.orEmpty(), provider.name, model).joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun translationCacheFile(key: String): File =
    File(context.filesDir, "ai_translation_cache").also { it.mkdirs() }.resolve("$key.json")

  private fun loadTranslationCache(
    key: String,
    totalChunks: Int,
  ): List<String?> {
    val file = translationCacheFile(key)
    if (!file.exists()) return emptyList()
    return runCatching {
      val cache = json.decodeFromString(SubtitleTranslationCache.serializer(), file.readText())
      if (cache.key == key && cache.translatedChunks.size == totalChunks) {
        cache.translatedChunks
      } else {
        emptyList()
      }
    }.getOrDefault(emptyList())
  }

  private fun saveTranslationCache(
    file: File,
    key: String,
    chunks: List<String?>,
  ) {
    runCatching {
      file.writeText(
        json.encodeToString(
          SubtitleTranslationCache.serializer(),
          SubtitleTranslationCache(
            key = key,
            translatedChunks = chunks,
            updatedAtMs = System.currentTimeMillis(),
          ),
        ),
      )
    }.onFailure {
      Log.w(TAG, "Could not save translation cache", it)
    }
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
