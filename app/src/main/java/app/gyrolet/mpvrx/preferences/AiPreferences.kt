/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.BuildConfig
import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

enum class AiProvider(
  val displayName: String,
) {
  OPENCODE("OpenCode"),
  GROQ("Groq"),
  OPENAI("OpenAI"),
  ANTHROPIC("Anthropic"),
  OPENROUTER("OpenRouter"),
  TOGETHER("Together"),
  CUSTOM_OPENAI("Custom OpenAI-compatible"),
}

/** Always off and ignores restored values, so a distribution can omit a feature outright. */
private class DisabledPreference(
  private val key: String,
) : Preference<Boolean> {
  override fun key(): String = key

  override fun get(): Boolean = false

  override fun set(value: Boolean) = Unit

  override fun isSet(): Boolean = false

  override fun delete() = Unit

  override fun defaultValue(): Boolean = false

  override fun changes(): Flow<Boolean> = flowOf(false)

  override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> = MutableStateFlow(false)
}

class AiPreferences(
  preferenceStore: PreferenceStore,
) {
  // Play requires in-app reporting of AI output, which mpvRx does not provide.
  val enabled: Preference<Boolean> =
    if (BuildConfig.IS_PLAY_STORE_BUILD) {
      DisabledPreference("ai_enabled")
    } else {
      preferenceStore.getBoolean("ai_enabled", false)
    }

  val provider = preferenceStore.getEnum("ai_provider", AiProvider.OPENCODE)

  val openCodeApiKey = preferenceStore.getString("ai_opencode_api_key", "")
  val groqApiKey = preferenceStore.getString("ai_groq_api_key", "")
  val openaiApiKey = preferenceStore.getString("ai_openai_api_key", "")
  val anthropicApiKey = preferenceStore.getString("ai_anthropic_api_key", "")
  val openrouterApiKey = preferenceStore.getString("ai_openrouter_api_key", "")
  val togetherApiKey = preferenceStore.getString("ai_together_api_key", "")
  val customOpenAiBaseUrl = preferenceStore.getString("ai_custom_openai_base_url", "")
  val customOpenAiApiKey = preferenceStore.getString("ai_custom_openai_api_key", "")

  val selectedModel = preferenceStore.getString("ai_selected_model", "")

  val availableModels = preferenceStore.getString("ai_available_models", "[]")

  private val openCodeSelectedModel = preferenceStore.getString("ai_selected_model_opencode", "")
  private val groqSelectedModel = preferenceStore.getString("ai_selected_model_groq", "")
  private val openAiSelectedModel = preferenceStore.getString("ai_selected_model_openai", "")
  private val anthropicSelectedModel = preferenceStore.getString("ai_selected_model_anthropic", "")
  private val openRouterSelectedModel = preferenceStore.getString("ai_selected_model_openrouter", "")
  private val togetherSelectedModel = preferenceStore.getString("ai_selected_model_together", "")
  private val customOpenAiSelectedModel = preferenceStore.getString("ai_selected_model_custom_openai", "")

  private val openCodeAvailableModels = preferenceStore.getString("ai_available_models_opencode", "[]")
  private val groqAvailableModels = preferenceStore.getString("ai_available_models_groq", "[]")
  private val openAiAvailableModels = preferenceStore.getString("ai_available_models_openai", "[]")
  private val anthropicAvailableModels = preferenceStore.getString("ai_available_models_anthropic", "[]")
  private val openRouterAvailableModels = preferenceStore.getString("ai_available_models_openrouter", "[]")
  private val togetherAvailableModels = preferenceStore.getString("ai_available_models_together", "[]")
  private val customOpenAiAvailableModels = preferenceStore.getString("ai_available_models_custom_openai", "[]")

  val subtitleGenerationOutputFormat = preferenceStore.getString("ai_subtitle_generation_output_format", "srt")

  // Speech-to-text (real-time subs + batch generation)
  val sttProvider = preferenceStore.getEnum("ai_stt_provider", AiProvider.GROQ)
  val sttModel = preferenceStore.getString("ai_stt_model", "")
  val sttAvailableModels = preferenceStore.getString("ai_stt_available_models", "[]")
  private val groqSttModel = preferenceStore.getString("ai_stt_model_groq", "")
  private val openAiSttModel = preferenceStore.getString("ai_stt_model_openai", "")
  private val openRouterSttModel = preferenceStore.getString("ai_stt_model_openrouter", "")
  private val groqSttAvailableModels = preferenceStore.getString("ai_stt_available_models_groq", "[]")
  private val openAiSttAvailableModels = preferenceStore.getString("ai_stt_available_models_openai", "[]")
  private val openRouterSttAvailableModels = preferenceStore.getString("ai_stt_available_models_openrouter", "[]")
  val sttLanguage = preferenceStore.getString("ai_stt_language", "")

  // Auto-translate target languages (comma-separated codes: "en,es,fr")
  val autoTranslateLanguages = preferenceStore.getString("ai_auto_translate_languages", "")

  val customPromptEnabled = preferenceStore.getBoolean("ai_custom_prompt_enabled", false)
  val customPrompt = preferenceStore.getString("ai_custom_prompt", "")
  val customRenamePrompt = preferenceStore.getString("ai_custom_rename_prompt", "")
  val customSubtitleTranslationPrompt = preferenceStore.getString("ai_custom_subtitle_translation_prompt", "")
  val customSubtitleFormatPrompt = preferenceStore.getString("ai_custom_subtitle_format_prompt", "")

  val renameWithAi = preferenceStore.getBoolean("ai_rename_enabled", true)
  val subtitleFormatWithAi = preferenceStore.getBoolean("ai_subtitle_format_enabled", true)
  val subtitleTranslationEnabled = preferenceStore.getBoolean("ai_subtitle_translation_enabled", false)

  // Real-time subtitle generation (speech-to-text while playing)
  val realtimeSubsEnabled = preferenceStore.getBoolean("ai_realtime_subs_enabled", true)
  val subtitleTranslationFirstTime = preferenceStore.getBoolean("ai_subtitle_translation_first_time", true)

  val lastVerified = preferenceStore.getLong("ai_last_verified", 0L)

  init {
    val currentProvider = provider.get()
    if (currentProvider != AiProvider.CUSTOM_OPENAI) {
      val providerModel = selectedModelFor(currentProvider)
      if (providerModel.get().isBlank() && selectedModel.get().isNotBlank()) {
        providerModel.set(selectedModel.get())
      }
      val providerModels = availableModelsFor(currentProvider)
      if (providerModels.get() == "[]" && availableModels.get() != "[]") {
        providerModels.set(availableModels.get())
      }
    }
    val currentSttProvider = sttProvider.get()
    val providerSttModel = sttModelFor(currentSttProvider)
    if (providerSttModel.get().isBlank() && sttModel.get().isNotBlank()) {
      providerSttModel.set(sttModel.get())
    }
    val providerSttModels = sttAvailableModelsFor(currentSttProvider)
    if (providerSttModels.get() == "[]" && sttAvailableModels.get() != "[]") {
      providerSttModels.set(sttAvailableModels.get())
    }
  }

  fun selectedModelFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.OPENCODE -> openCodeSelectedModel
      AiProvider.GROQ -> groqSelectedModel
      AiProvider.OPENAI -> openAiSelectedModel
      AiProvider.ANTHROPIC -> anthropicSelectedModel
      AiProvider.OPENROUTER -> openRouterSelectedModel
      AiProvider.TOGETHER -> togetherSelectedModel
      AiProvider.CUSTOM_OPENAI -> customOpenAiSelectedModel
    }

  fun availableModelsFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.OPENCODE -> openCodeAvailableModels
      AiProvider.GROQ -> groqAvailableModels
      AiProvider.OPENAI -> openAiAvailableModels
      AiProvider.ANTHROPIC -> anthropicAvailableModels
      AiProvider.OPENROUTER -> openRouterAvailableModels
      AiProvider.TOGETHER -> togetherAvailableModels
      AiProvider.CUSTOM_OPENAI -> customOpenAiAvailableModels
    }

  fun sttModelFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.GROQ -> groqSttModel
      AiProvider.OPENAI -> openAiSttModel
      AiProvider.OPENROUTER -> openRouterSttModel
      else -> sttModel
    }

  fun sttAvailableModelsFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.GROQ -> groqSttAvailableModels
      AiProvider.OPENAI -> openAiSttAvailableModels
      AiProvider.OPENROUTER -> openRouterSttAvailableModels
      else -> sttAvailableModels
    }
}
