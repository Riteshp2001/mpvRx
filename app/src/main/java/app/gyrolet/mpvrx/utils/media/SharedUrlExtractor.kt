/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

object SharedUrlExtractor {
  private val webUrlPattern = Regex("""(?i)\bhttps?://[^\s<>\"'‘’“”]+""")
  private val simpleTrailingPunctuation =
    charArrayOf('.', ',', ';', ':', '!', '?', '…', '。', '，', '；', '：', '！', '？', '、')

  fun normalizeInput(text: String): String = firstWebUrl(text) ?: text.trim()

  fun firstWebUrl(text: String): String? =
    webUrlPattern
      .findAll(text)
      .map { match -> trimSharePunctuation(match.value) }
      .firstOrNull { url -> url.length > "https://".length }

  private fun trimSharePunctuation(rawUrl: String): String {
    var url = rawUrl.trimEnd(*simpleTrailingPunctuation)
    url = trimUnmatchedClosingDelimiter(url, '(', ')')
    url = trimUnmatchedClosingDelimiter(url, '[', ']')
    return trimUnmatchedClosingDelimiter(url, '{', '}')
  }

  private fun trimUnmatchedClosingDelimiter(
    value: String,
    opening: Char,
    closing: Char,
  ): String {
    var result = value
    while (result.endsWith(closing) && result.count { it == closing } > result.count { it == opening }) {
      result = result.dropLast(1)
    }
    return result
  }
}
