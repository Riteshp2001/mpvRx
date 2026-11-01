/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import app.gyrolet.mpvrx.preferences.AiPreferences
import app.gyrolet.mpvrx.repository.ai.AiService

/** Uses the AI provider and model configured in AI settings (Groq, OpenRouter, OpenAI, …). */
class AiSubtitleTranslator(
  private val aiService: AiService,
  preferences: AiPreferences,
) : SubtitleTranslator {
  private val provider = preferences.provider.get()

  override val cacheId: String = "ai:${provider.name}:${preferences.selectedModelFor(provider).get()}"
  override val maxTextsPerRequest: Int = 25
  override val maxCharsPerRequest: Int = 3_500

  init {
    if (!preferences.enabled.get()) {
      throw SubtitleTranslationException.Configuration("AI features are turned off. Enable them in Settings → AI, or pick another translation provider.")
    }
    if (aiService.getApiKey(provider).isBlank()) {
      throw SubtitleTranslationException.Configuration("No API key set for ${provider.displayName}. Add one in Settings → AI.")
    }
  }

  override suspend fun translate(
    texts: List<String>,
    sourceLanguage: String?,
    targetLanguage: String,
  ): List<String> =
    aiService
      .translateCueTexts(
        texts,
        TranslationLanguages.displayName(targetLanguage),
        sourceLanguage?.let(TranslationLanguages::displayName),
      ).getOrElse { error -> throw map(error) }

  private fun map(error: Throwable): SubtitleTranslationException {
    val message = error.message.orEmpty()
    val lower = message.lowercase()
    return when {
      error is IllegalStateException && "incomplete" in lower -> SubtitleTranslationException.InvalidResponse(message)
      "quota" in lower || "insufficient" in lower || "billing" in lower || "credit" in lower ->
        SubtitleTranslationException.QuotaExceeded("${provider.displayName}: $message")
      "429" in lower || "rate limit" in lower || "too many requests" in lower ->
        SubtitleTranslationException.RateLimited("${provider.displayName} is rate limiting requests")
      "401" in lower || "403" in lower || "unauthorized" in lower || "invalid api key" in lower ->
        SubtitleTranslationException.Auth("${provider.displayName} rejected the API key")
      "timeout" in lower || "timed out" in lower || "unable to resolve host" in lower || "failed to connect" in lower ->
        SubtitleTranslationException.Network(cause = error)
      Regex("""\b5\d\d\b""").containsMatchIn(lower) -> SubtitleTranslationException.Server("${provider.displayName}: $message")
      else -> SubtitleTranslationException.Configuration("${provider.displayName}: ${message.take(200)}")
    }
  }
}
