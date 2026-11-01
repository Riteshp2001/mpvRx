/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.preferences

import app.gyrolet.mpvrx.preferences.preference.PreferenceStore
import app.gyrolet.mpvrx.preferences.preference.getEncryptedString
import app.gyrolet.mpvrx.preferences.preference.getEnum
import app.gyrolet.mpvrx.repository.subtitle.translation.TranslationLanguages

enum class SubtitleTranslationProvider(
  val displayName: String,
  /** Cost, key and connectivity at a glance, shown under the provider name. */
  val summary: String,
) {
  ON_DEVICE(
    "Google ML Kit (on-device)",
    "Free · no key · offline after a one-time ~30 MB model download per language",
  ),
  MYMEMORY(
    "MyMemory",
    "Free · no key · internet · small daily limit (higher with your email)",
  ),
  LIBRETRANSLATE(
    "LibreTranslate",
    "Free on your own server · internet · libretranslate.com needs a paid key",
  ),
  AI(
    "AI model (from AI settings)",
    "Uses your AI provider and key · internet · best context, cost depends on provider",
  ),
}

class SubtitleTranslationPreferences(
  preferenceStore: PreferenceStore,
) {
  val enabled = preferenceStore.getBoolean("subtitle_translation_enabled", true)
  val provider = preferenceStore.getEnum("subtitle_translation_provider", SubtitleTranslationProvider.ON_DEVICE)

  val libreTranslateUrl = preferenceStore.getString("subtitle_translation_libre_url", "")
  val libreTranslateApiKey = preferenceStore.getEncryptedString("subtitle_translation_libre_api_key")
  val myMemoryEmail = preferenceStore.getString("subtitle_translation_mymemory_email", "")
  /** Wrap right-to-left translations so punctuation lands on the correct side in SRT/VTT. */
  val rtlFix = preferenceStore.getBoolean("subtitle_translation_rtl_fix", true)

  /** The language subtitles are translated into; defaults to the device language. */
  val targetLanguage = preferenceStore.getString("subtitle_translation_target", defaultTarget())

  /** Translate in the background when a video opens without a subtitle in [targetLanguage]. */
  val autoTranslate = preferenceStore.getBoolean("subtitle_translation_auto", false)

  private fun defaultTarget(): String =
    TranslationLanguages.normalizeCode(java.util.Locale.getDefault().toLanguageTag())
      ?.takeIf { TranslationLanguages.find(it) != null }
      ?: "en"
}
