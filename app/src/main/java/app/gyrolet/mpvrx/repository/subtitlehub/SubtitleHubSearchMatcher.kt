/*
 * SPDX-License-Identifier: CC-BY-NC-4.0
 *
 * This work is licensed under Creative Commons Attribution-NonCommercial 4.0 International License.
 * To view a copy of this license, visit https://creativecommons.org/licenses/by-nc/4.0/
 */

package app.gyrolet.mpvrx.repository.subtitlehub

import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest

internal object SubtitleHubSearchMatcher {
  private val movieOnlySources =
    setOf(
      "moviesubtitles_org",
      "moviesubtitlesrt_com",
    )

  private val supportedSubtitleFormats = setOf("srt", "ass", "ssa", "vtt", "sub")
  private val supportedArchiveFormats = setOf("zip")

  fun sourcesFor(
    request: OnlineSubtitleSearchRequest,
    selectedSources: Set<String>,
  ): Set<String> =
    if (request.season != null || request.episode != null) {
      selectedSources - movieOnlySources
    } else {
      selectedSources
    }

  fun matchesQueryTitle(
    query: String,
    candidate: String,
  ): Boolean = titleMatchScore(query, candidate) > 0

  fun titleMatchScore(
    query: String,
    candidate: String,
  ): Int {
    val queryTokens = titleTokens(query)
    if (queryTokens.isEmpty()) return 1
    val candidateTokens = titleTokens(candidate)
    if (candidateTokens.isEmpty()) return 0

    val candidateJoined = candidateTokens.joinToString(" ")
    val queryJoined = queryTokens.joinToString(" ")
    if (candidateJoined == queryJoined) return 100
    if (candidateJoined.startsWith(queryJoined)) return 80
    if (candidateJoined.contains(queryJoined)) return 70

    return if (queryTokens.size == 1) {
      if (candidateTokens.contains(queryTokens.first())) 50 else 0
    } else {
      if (queryTokens.all(candidateTokens::contains)) {
        (40 + queryTokens.count(candidateTokens::contains) * 5).coerceAtMost(65)
      } else {
        0
      }
    }
  }

  fun displayFormat(
    value: String?,
    fallbackForResolvedPage: String? = null,
  ): String? {
    val normalized = value?.lowercase()?.trim()
    if (normalized in supportedSubtitleFormats || normalized in supportedArchiveFormats) return normalized
    return fallbackForResolvedPage?.lowercase()?.takeIf {
      it in supportedArchiveFormats ||
        it in supportedSubtitleFormats
    }
  }

  private fun titleTokens(value: String): List<String> =
    value
      .lowercase()
      .replace(Regex("""s\d{1,2}\s*e\d{1,4}"""), " ")
      .replace(Regex("""\d{1,2}x\d{1,4}"""), " ")
      .replace(Regex("""season\s*\d{1,2}"""), " ")
      .replace(Regex("""episode\s*\d{1,4}"""), " ")
      .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
      .replace(
        Regex("""\b(?:english|subtitles?|subtitle|subs?|download|dvdrip|bluray|webrip|web-dl|hdtv|multi)\b"""),
        " ",
      ).replace(Regex("""[^a-z0-9]+"""), " ")
      .trim()
      .split(Regex("""\s+"""))
      .filter { it.length >= 2 }
      .distinct()
}
