/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.repository.subtitle.translation

import java.util.Locale

data class TranslationLanguage(
  /** ISO 639-1 code, or `zh-TW` for Traditional Chinese. */
  val code: String,
  val name: String,
  val rtl: Boolean = false,
)

object TranslationLanguages {
  val all: List<TranslationLanguage> =
    listOf(
      TranslationLanguage("af", "Afrikaans"),
      TranslationLanguage("ar", "Arabic", rtl = true),
      TranslationLanguage("bn", "Bengali"),
      TranslationLanguage("bg", "Bulgarian"),
      TranslationLanguage("ca", "Catalan"),
      TranslationLanguage("zh", "Chinese (Simplified)"),
      TranslationLanguage("zh-TW", "Chinese (Traditional)"),
      TranslationLanguage("hr", "Croatian"),
      TranslationLanguage("cs", "Czech"),
      TranslationLanguage("da", "Danish"),
      TranslationLanguage("nl", "Dutch"),
      TranslationLanguage("en", "English"),
      TranslationLanguage("et", "Estonian"),
      TranslationLanguage("fi", "Finnish"),
      TranslationLanguage("fr", "French"),
      TranslationLanguage("de", "German"),
      TranslationLanguage("el", "Greek"),
      TranslationLanguage("gu", "Gujarati"),
      TranslationLanguage("he", "Hebrew", rtl = true),
      TranslationLanguage("hi", "Hindi"),
      TranslationLanguage("hu", "Hungarian"),
      TranslationLanguage("id", "Indonesian"),
      TranslationLanguage("it", "Italian"),
      TranslationLanguage("ja", "Japanese"),
      TranslationLanguage("kn", "Kannada"),
      TranslationLanguage("ko", "Korean"),
      TranslationLanguage("lv", "Latvian"),
      TranslationLanguage("lt", "Lithuanian"),
      TranslationLanguage("ms", "Malay"),
      TranslationLanguage("ml", "Malayalam"),
      TranslationLanguage("mr", "Marathi"),
      TranslationLanguage("no", "Norwegian"),
      TranslationLanguage("fa", "Persian", rtl = true),
      TranslationLanguage("pl", "Polish"),
      TranslationLanguage("pt", "Portuguese"),
      TranslationLanguage("pa", "Punjabi"),
      TranslationLanguage("ro", "Romanian"),
      TranslationLanguage("ru", "Russian"),
      TranslationLanguage("sr", "Serbian"),
      TranslationLanguage("sk", "Slovak"),
      TranslationLanguage("sl", "Slovenian"),
      TranslationLanguage("es", "Spanish"),
      TranslationLanguage("sw", "Swahili"),
      TranslationLanguage("sv", "Swedish"),
      TranslationLanguage("ta", "Tamil"),
      TranslationLanguage("te", "Telugu"),
      TranslationLanguage("th", "Thai"),
      TranslationLanguage("tr", "Turkish"),
      TranslationLanguage("uk", "Ukrainian"),
      TranslationLanguage("ur", "Urdu", rtl = true),
      TranslationLanguage("vi", "Vietnamese"),
    )

  private val byCode = all.associateBy { it.code.lowercase(Locale.ROOT) }
  private val byName = all.associateBy { it.name.lowercase(Locale.ROOT) }

  private val rtlCodes = setOf("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv", "ckb")

  /** ISO 639-2/B codes that differ from the 639-2/T codes [Locale] knows about. */
  private val bibliographicCodes =
    mapOf(
      "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs",
      "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is",
      "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk",
      "tib" to "bo", "wel" to "cy",
    )

  private val iso3ToIso1: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { code ->
      runCatching { Locale(code).isO3Language }.getOrDefault("")
    }
  }

  private val latinScriptCodes =
    setOf(
      "af", "ca", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "hu", "id", "it", "lv", "lt", "ms", "no",
      "pl", "pt", "ro", "sk", "sl", "es", "sw", "sv", "tr", "vi", "sq", "eu", "ga", "gl", "is", "mt", "cy", "tl",
    )

  fun isRtl(code: String): Boolean = baseCode(code) in rtlCodes

  fun usesLatinScript(code: String): Boolean = baseCode(code) in latinScriptCodes

  fun find(code: String): TranslationLanguage? = byCode[code.lowercase(Locale.ROOT)]

  /** Accepts a display name ("Arabic"), an ISO code ("ar", "ara", "ar-EG") or a track language tag. */
  fun fromNameOrCode(value: String): TranslationLanguage? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    byName[trimmed.lowercase(Locale.ROOT)]?.let { return it }
    val code = normalizeCode(trimmed) ?: return null
    return byCode[code] ?: TranslationLanguage(code, displayName(code), rtl = isRtl(code))
  }

  /**
   * Normalizes track/file language tags to ISO 639-1 (`eng` → `en`, `pt-BR` → `pt`).
   * Traditional Chinese keeps its region (`zh-TW`). Returns null for unknown or undetermined tags.
   */
  fun normalizeCode(raw: String?): String? {
    val tag = raw?.trim()?.replace('_', '-')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    if (tag in setOf("und", "unk", "mul", "zxx", "mis", "qaa")) return null
    val primary = tag.substringBefore('-')
    val region = tag.substringAfter('-', "")
    if (primary == "zh" || primary == "chi" || primary == "zho") {
      return if (region in setOf("tw", "hk", "mo", "hant")) "zh-TW" else "zh"
    }
    val iso1 =
      when (primary.length) {
        2 -> primary
        3 -> bibliographicCodes[primary] ?: iso3ToIso1[primary]
        else -> null
      } ?: return null
    return when (iso1) {
      "iw" -> "he"
      "in" -> "id"
      "ji" -> "yi"
      "nb", "nn" -> "no"
      else -> iso1
    }
  }

  /** "Movie.en.srt" → "en", "Movie.2.ar.srt" → "ar", "Movie.English.forced.srt" → "en". */
  fun fromFileName(fileName: String): String? =
    fileName
      .substringAfterLast('/')
      .substringBeforeLast('.')
      .split('.', '_', ' ', '-')
      .drop(1)
      .asReversed()
      .firstNotNullOfOrNull { part ->
        when {
          part.length in 2..3 && part.all(Char::isLetter) -> normalizeCode(part)
          else -> all.firstOrNull { it.name.equals(part, ignoreCase = true) }?.code
        }
      }

  fun displayName(code: String): String =
    find(code)?.name
      ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
      ?: code

  private fun baseCode(code: String): String = code.lowercase(Locale.ROOT).substringBefore('-')
}
