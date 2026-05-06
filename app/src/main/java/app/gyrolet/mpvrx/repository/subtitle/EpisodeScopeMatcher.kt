package app.gyrolet.mpvrx.repository.subtitle

internal object EpisodeScopeMatcher {
  fun filter(
    subtitles: List<OnlineSubtitle>,
    season: Int?,
    episode: Int?,
  ): List<OnlineSubtitle> {
    if (season == null || episode == null || subtitles.isEmpty()) return subtitles

    val scored = subtitles.map { subtitle -> subtitle to matchScore(subtitle, season, episode) }
    val exact = scored.filter { (_, score) -> score > 0 }
    if (exact.isNotEmpty()) return exact.map { (subtitle, _) -> subtitle }

    return scored
      .filter { (_, score) -> score >= 0 }
      .map { (subtitle, _) -> subtitle }
  }

  private fun matchScore(
    subtitle: OnlineSubtitle,
    season: Int,
    episode: Int,
  ): Int {
    val metadataSeason = subtitle.metadata["season"]?.toIntOrNull()
    val metadataEpisode = subtitle.metadata["episode"]?.toIntOrNull()
    if (metadataSeason == season && metadataEpisode == episode) return 4
    if (metadataEpisode != null && metadataEpisode != episode) return -1
    if (metadataSeason != null && metadataSeason != season) return -1

    val text = subtitle.searchableText()
    if (hasConflictingSeasonEpisode(text, season, episode)) return -1

    val compact = text.replace(Regex("[^a-z0-9]+"), "")
    if (seasonEpisodeTokens(season, episode).any { compact.contains(it) }) return 3
    if (episodeOnlyRegex(episode).containsMatchIn(text)) return 1

    return if (metadataEpisode == episode) 1 else 0
  }

  private fun OnlineSubtitle.searchableText(): String =
    buildList {
      add(displayName)
      fileName?.let(::add)
      release?.let(::add)
      media?.let(::add)
      id?.let(::add)
      add(url)
      addAll(metadata.values)
    }.joinToString(" ").lowercase()

  private fun seasonEpisodeTokens(
    season: Int,
    episode: Int,
  ): List<String> {
    val seasonRaw = season.toString()
    val episodeRaw = episode.toString()
    val seasonPadded = seasonRaw.padStart(2, '0')
    val episodePadded = episodeRaw.padStart(2, '0')
    return listOf(
      "s${seasonPadded}e$episodePadded",
      "s${seasonRaw}e$episodeRaw",
      "${seasonRaw}x$episodePadded",
      "${seasonPadded}x$episodePadded",
      "season${seasonRaw}episode$episodeRaw",
      "season${seasonPadded}episode$episodePadded",
    )
  }

  private fun episodeOnlyRegex(episode: Int): Regex =
    Regex("""\b(?:e|ep|episode)\s*0?$episode\b""", RegexOption.IGNORE_CASE)

  private fun hasConflictingSeasonEpisode(
    text: String,
    wantedSeason: Int,
    wantedEpisode: Int,
  ): Boolean {
    val sxeRegex = Regex("""\bs\s*(\d{1,2})\s*e\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    if (sxeRegex.findAll(text).any { it.groupValues[1].toIntOrNull() != wantedSeason || it.groupValues[2].toIntOrNull() != wantedEpisode }) {
      return true
    }

    val crossRegex = Regex("""\b(\d{1,2})\s*x\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    if (crossRegex.findAll(text).any { it.groupValues[1].toIntOrNull() != wantedSeason || it.groupValues[2].toIntOrNull() != wantedEpisode }) {
      return true
    }

    val seasonEpisodeRegex =
      Regex("""\bseason\s*(\d{1,2}).{0,16}\bepisode\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    return seasonEpisodeRegex.findAll(text)
      .any { it.groupValues[1].toIntOrNull() != wantedSeason || it.groupValues[2].toIntOrNull() != wantedEpisode }
  }
}
