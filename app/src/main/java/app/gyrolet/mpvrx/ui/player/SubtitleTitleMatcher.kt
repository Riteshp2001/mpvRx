/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.player

/** Selects the first available title preference, with later entries used only as fallbacks. */
internal object SubtitleTitleMatcher {
  fun findBestMatchIndex(
    titles: List<String>,
    orderedKeywords: List<String>,
  ): Int? {
    for (keyword in orderedKeywords.map(String::trim).filter(String::isNotEmpty)) {
      val index = titles.indexOfFirst { matchesKeyword(it, keyword) }
      if (index >= 0) return index
    }
    return null
  }

  private fun matchesKeyword(
    title: String,
    keyword: String,
  ): Boolean {
    val isShortAsciiCode = keyword.length <= 3 && keyword.all { it.isAsciiLetterOrDigit() }
    if (!isShortAsciiCode) return title.contains(keyword, ignoreCase = true)

    // Treat short values such as eng, ch and zh as codes. Boundaries prevent
    // "ch" from accidentally matching unrelated words such as "French".
    val codePattern = Regex("(?i)(?<![a-z0-9])${Regex.escape(keyword)}(?![a-z0-9])")
    return codePattern.containsMatchIn(title)
  }

  private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
