/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.utils.media

import java.util.Locale
import java.util.MissingResourceException

internal fun normalizePlaybackLanguageTag(value: String): String? {
  val tag = value.trim().lowercase(Locale.ROOT).replace('_', '-')
  val base = playbackLanguageCodes[tag.substringBefore('-')] ?: return null
  return base +
    tag
      .substringAfter('-', "")
      .takeIf(String::isNotEmpty)
      ?.let { "-$it" }
      .orEmpty()
}

private val playbackLanguageCodes: Map<String, String> by lazy {
  buildMap {
    for (code in Locale.getISOLanguages()) {
      val locale = Locale.forLanguageTag(code)
      put(code, code)
      put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
      try {
        put(locale.isO3Language.lowercase(Locale.ROOT), code)
      } catch (_: MissingResourceException) {
        // Some Android locales do not expose an ISO 639-2 code.
      }
    }
    putAll(
      mapOf(
        "alb" to "sq",
        "arm" to "hy",
        "baq" to "eu",
        "bur" to "my",
        "chi" to "zh",
        "cze" to "cs",
        "dut" to "nl",
        "fre" to "fr",
        "geo" to "ka",
        "ger" to "de",
        "gre" to "el",
        "ice" to "is",
        "mac" to "mk",
        "mao" to "mi",
        "may" to "ms",
        "per" to "fa",
        "rum" to "ro",
        "slo" to "sk",
        "tib" to "bo",
        "wel" to "cy",
        "jp" to "ja",
        "iw" to "he",
        "in" to "id",
        "ji" to "yi",
      ),
    )
  }
}
