/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.Preference
import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEnum

enum class AiProvider(
  val displayName: String,
) {
  OPENCODE("OpenCode"),
  GROQ("Groq"),
  OPENAI("OpenAI"),
  ANTHROPIC("Anthropic"),
  OPENROUTER("OpenRouter"),
  TOGETHER("Together"),
  LOCAL("Offline Model"),
}

class AiPreferences(
  preferenceStore: PreferenceStore,
) {
  val enabled = preferenceStore.getBoolean("ai_enabled", false)

  val provider = preferenceStore.getEnum("ai_provider", AiProvider.OPENCODE)

  val openCodeApiKey = preferenceStore.getString("ai_opencode_api_key", "")
  val groqApiKey = preferenceStore.getString("ai_groq_api_key", "")
  val openaiApiKey = preferenceStore.getString("ai_openai_api_key", "")
  val anthropicApiKey = preferenceStore.getString("ai_anthropic_api_key", "")
  val openrouterApiKey = preferenceStore.getString("ai_openrouter_api_key", "")
  val togetherApiKey = preferenceStore.getString("ai_together_api_key", "")

  val selectedModel = preferenceStore.getString("ai_selected_model", "")

  val availableModels = preferenceStore.getString("ai_available_models", "[]")

  private val openCodeSelectedModel = preferenceStore.getString("ai_selected_model_opencode", "")
  private val groqSelectedModel = preferenceStore.getString("ai_selected_model_groq", "")
  private val openAiSelectedModel = preferenceStore.getString("ai_selected_model_openai", "")
  private val anthropicSelectedModel = preferenceStore.getString("ai_selected_model_anthropic", "")
  private val openRouterSelectedModel = preferenceStore.getString("ai_selected_model_openrouter", "")
  private val togetherSelectedModel = preferenceStore.getString("ai_selected_model_together", "")

  private val openCodeAvailableModels = preferenceStore.getString("ai_available_models_opencode", "[]")
  private val groqAvailableModels = preferenceStore.getString("ai_available_models_groq", "[]")
  private val openAiAvailableModels = preferenceStore.getString("ai_available_models_openai", "[]")
  private val anthropicAvailableModels = preferenceStore.getString("ai_available_models_anthropic", "[]")
  private val openRouterAvailableModels = preferenceStore.getString("ai_available_models_openrouter", "[]")
  private val togetherAvailableModels = preferenceStore.getString("ai_available_models_together", "[]")

  val localModelId = preferenceStore.getString("ai_local_model_id", "")
  val localModelPath = preferenceStore.getString("ai_local_model_path", "")
  val localModelDownloaded = preferenceStore.getBoolean("ai_local_model_downloaded", false)
  val localModelDownloadProgress = preferenceStore.getFloat("ai_local_model_download_progress", 0f)
  val localModelBenchmarks = preferenceStore.getString("ai_local_model_benchmarks", "[]")
  val huggingfaceToken = preferenceStore.getString("ai_huggingface_token", "")

  val subtitleGenerationOutputFormat = preferenceStore.getString("ai_subtitle_generation_output_format", "srt")

  // Speech-to-text (real-time subs + batch generation)
  val sttProvider = preferenceStore.getEnum("ai_stt_provider", AiProvider.GROQ)
  val sttModel = preferenceStore.getString("ai_stt_model", "")
  val sttAvailableModels = preferenceStore.getString("ai_stt_available_models", "[]")
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
    if (currentProvider != AiProvider.LOCAL) {
      val providerModel = selectedModelFor(currentProvider)
      if (providerModel.get().isBlank() && selectedModel.get().isNotBlank()) {
        providerModel.set(selectedModel.get())
      }
      val providerModels = availableModelsFor(currentProvider)
      if (providerModels.get() == "[]" && availableModels.get() != "[]") {
        providerModels.set(availableModels.get())
      }
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
      AiProvider.LOCAL -> localModelId
    }

  fun availableModelsFor(provider: AiProvider): Preference<String> =
    when (provider) {
      AiProvider.OPENCODE -> openCodeAvailableModels
      AiProvider.GROQ -> groqAvailableModels
      AiProvider.OPENAI -> openAiAvailableModels
      AiProvider.ANTHROPIC -> anthropicAvailableModels
      AiProvider.OPENROUTER -> openRouterAvailableModels
      AiProvider.TOGETHER -> togetherAvailableModels
      AiProvider.LOCAL -> availableModels
    }
}
