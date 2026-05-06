package app.gyrolet.mpvrx.repository.subtitlehub

import app.gyrolet.mpvrx.repository.subtitle.OnlineSubtitleSearchRequest

internal object SubtitleHubSearchMatcher {
  private val movieOnlySources =
    setOf(
      "moviesubtitles_org",
      "moviesubtitlesrt_com",
      "yifysubtitles_ch",
    )

  private val supportedSubtitleFormats = setOf("srt", "ass", "ssa", "vtt", "sub")
  private val supportedArchiveFormats = setOf("zip")

  fun sourcesFor(request: OnlineSubtitleSearchRequest, selectedSources: Set<String>): Set<String> =
    if (request.season != null || request.episode != null) {
      selectedSources - movieOnlySources
    } else {
      selectedSources
    }

  fun matchesQueryTitle(query: String, candidate: String): Boolean {
    val queryTokens = titleTokens(query)
    if (queryTokens.isEmpty()) return true
    val candidateTokens = titleTokens(candidate)
    if (candidateTokens.isEmpty()) return false

    val candidateJoined = candidateTokens.joinToString(" ")
    val queryJoined = queryTokens.joinToString(" ")
    if (candidateJoined.contains(queryJoined)) return true

    return if (queryTokens.size == 1) {
      candidateTokens.contains(queryTokens.first())
    } else {
      queryTokens.all(candidateTokens::contains)
    }
  }

  fun displayFormat(value: String?, fallbackForResolvedPage: String? = null): String? {
    val normalized = value?.lowercase()?.trim()
    if (normalized in supportedSubtitleFormats || normalized in supportedArchiveFormats) return normalized
    return fallbackForResolvedPage?.lowercase()?.takeIf { it in supportedArchiveFormats || it in supportedSubtitleFormats }
  }

  private fun titleTokens(value: String): List<String> =
    value
      .lowercase()
      .replace(Regex("""s\d{1,2}\s*e\d{1,4}"""), " ")
      .replace(Regex("""\d{1,2}x\d{1,4}"""), " ")
      .replace(Regex("""season\s*\d{1,2}"""), " ")
      .replace(Regex("""episode\s*\d{1,4}"""), " ")
      .replace(Regex("""\b(19|20)\d{2}\b"""), " ")
      .replace(Regex("""\b(?:english|subtitles?|subtitle|subs?|download|dvdrip|bluray|webrip|web-dl|hdtv|multi)\b"""), " ")
      .replace(Regex("""[^a-z0-9]+"""), " ")
      .trim()
      .split(Regex("""\s+"""))
      .filter { it.length >= 2 }
      .distinct()
}
