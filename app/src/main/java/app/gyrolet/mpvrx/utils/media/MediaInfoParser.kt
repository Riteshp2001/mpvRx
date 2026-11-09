/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.utils.media

import android.content.Context
import android.net.Uri
import app.gyrolet.mpvrx.utils.sort.SortUtils
import java.text.Normalizer
import java.time.Year
import java.util.Locale

/**
 * Shared native Kotlin media filename parser.
 *
 * Extracts structured metadata from messy release filenames:
 * - Title (cleaned and normalized)
 * - Season number
 * - Episode number
 * - Episode title
 * - Year
 * - Media type (tv / movie)
 *
 * Handles:
 * - Regular TV series:     Dexter.S01E02.1080p.BluRay.mkv → "Dexter" S01E02
 * - Regular movies:        The.Dark.Knight.2008.1080p.BluRay.mkv → "The Dark Knight" (2008)
 * - Scene release formats: Jujutsu.Kaisen.S02E05.1080p.WEBRip.x265-PSA.mkv → "Jujutsu Kaisen" S02E05
 * - Anime naming: [SubsPlease] Frieren - 01 (1080p) [ABCD1234].mkv → "Frieren" E01
 * - Japanese seasons:      San no Shou → Season 3
 * - Multi-episode:         S01E01E02 or S01E01-E03 → S01E01
 * - Episode with title:    Breaking.Bad.S05E16.Felina.720p.mkv → "Breaking Bad" S05E16 "Felina"
 * - Cross-format episode:  1x02, EP05, Episode 5, #05 patterns
 */

data class ParsedMediaInfo(
  val title: String,
  val year: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val episodeTitle: String? = null,
  val type: String, // "movie" or "tv"
  val episodeEnd: Int? = null,
  val isEpisodeAmbiguous: Boolean = false,
)

object MediaInfoParser {
  // ── Result cache — avoids re-parsing the same filename (e.g. playlist repeat) ──
  private data class CacheEntry(val fast: ParsedMediaInfo)

  private val parseCache = android.util.LruCache<String, CacheEntry>(300)

  // ── Japanese season numbers ──────────────────────────────────────────────────
  private val JAPANESE_NUMBERS =
    mapOf(
      "ichi" to 1,
      "ni" to 2,
      "san" to 3,
      "yon" to 4,
      "shi" to 4,
      "go" to 5,
      "roku" to 6,
      "nana" to 7,
      "shichi" to 7,
      "hachi" to 8,
      "kyuu" to 9,
      "ku" to 9,
      "juu" to 10,
    )

  // ── Known release groups ─────────────────────────────────────────────────────
  private val RELEASE_GROUPS =
    setOf(
      "YTS",
      "RARBG",
      "EVO",
      "iExTV",
      "FGT",
      "PSA",
      "Batch",
      "HorribleSubs",
      "SubsPlease",
      "Erai-raws",
      "ASW",
      "Judas",
      "EMBER",
      "GHOSTS",
      "ION10",
      "SPARKS",
      "AMIABLE",
      "GECKOS",
      "YIFY",
      "ShAaNiG",
      "USURY",
      "STUTTERSHIT",
      "DDR",
      "QxR",
      "NTb",
      "NTG",
      "CAKES",
      "FLUX",
      "MZABI",
      "EDITH",
      "GalaxyRG",
      "MkvCage",
      "Joy",
      "BonsaiHD",
      "TrollHD",
      "NOGRP",
      "Pahe",
      "Tigole",
      "PHOCiS",
      "RUSTED",
      "DSNP",
      "PECULATE",
      "SuccessfulCrab",
      "PLAY",
      "HONE",
      "KOGI",
      "DEMAND",
      "RARBG",
      "SPARKS",
      "AMIABLE",
      "FGT",
      "ROVERS",
    )

  // ── Metadata keywords (noise) ────────────────────────────────────────────────
  private val VIDEO_CODECS =
    setOf(
      "h264",
      "h265",
      "x264",
      "x265",
      "hevc",
      "av1",
      "avc",
      "mpeg4",
      "divx",
      "xvid",
      "vp9",
      "vp8",
    )

  private val AUDIO_CODECS =
    setOf(
      "aac",
      "flac",
      "mp3",
      "opus",
      "ac3",
      "dts",
      "eac3",
      "truehd",
      "atmos",
      "lpcm",
      "pcm",
      "vorbis",
      "ogg",
    )

  private val SOURCE_TAGS =
    setOf(
      "bluray",
      "bdrip",
      "brrip",
      "dvdrip",
      "hdrip",
      "webdl",
      "webrip",
      "hdtv",
      "cam",
      "tc",
      "hdcam",
      "hdts",
      "dvdscr",
      "dvdr",
      "pdtv",
      "sdtv",
      "tvrip",
      "r5",
      "amzn",
      "nf",
      "hulu",
      "atvp",
      "pcok",
      "hmax",
      "crater",
      "hbo",
      "stan",
      "pmtp",
      "dsnp",
      "dnsp",
      "crav",
      "voot",
      "zee5",
      "sonyliv",
      "jio",
      "mx",
      "hotstar",
      "itunes",
      "vudu",
      "roku",
      "tubi",
    )

  private val RESOLUTION_TAGS =
    setOf(
      "480p",
      "480i",
      "576p",
      "576i",
      "720p",
      "720i",
      "1080p",
      "1080i",
      "2160p",
      "4k",
      "8k",
      "uhd",
    )

  private val SCENE_TAGS =
    setOf(
      "proper",
      "repack",
      "remux",
      "extended",
      "unrated",
      "imax",
      "theatrical",
      "directors",
      "internal",
      "limited",
      "remastered",
      "uncut",
      "complete",
    )

  private val LANGUAGE_TAGS =
    setOf(
      "english",
      "french",
      "german",
      "spanish",
      "hindi",
      "multi",
      "dual",
      "dubbed",
      "subbed",
      "engsub",
      "vostfr",
      "vf",
      "ita",
      "jpn",
      "kor",
      "chi",
      "rus",
      "ara",
      "por",
      "pol",
      "tur",
      "dut",
      "swe",
      "nor",
      "dan",
      "fin",
      "hun",
      "cze",
      "gre",
      "rom",
      "heb",
      "tha",
      "ind",
      "vie",
    )

  private val MISC_NOISE =
    setOf(
      "10bit",
      "8bit",
      "hdr",
      "hdr10",
      "hdr10plus",
      "dolby",
      "vision",
      "dovi",
      "sdr",
      "bt709",
      "bt2020",
      "hlg",
      "pq",
      "batch",
      "dvd",
      "uncensored",
      "censored",
      "horriblesubs",
      "subsplease",
      "hybrid",
      "open",
      "matte",
    )

  private val FILE_EXTENSIONS =
    setOf(
      "mkv",
      "mp4",
      "avi",
      "mov",
      "wmv",
      "flv",
      "webm",
      "m4v",
      "mpg",
      "mpeg",
      "m2ts",
      "vob",
      "ogm",
      "rmvb",
      "srt",
      "ass",
      "ssa",
      "vtt",
      "sub",
      "ts",
      "mts",
      "m2v",
      "mp3",
      "m4a",
      "m4b",
      "flac",
      "ogg",
      "opus",
      "wav",
      "zip",
      "rar",
      "7z",
    )

  // Combine all noise into one set for quick lookup
  private val ALL_NOISE: Set<String> by lazy {
    (
      VIDEO_CODECS + AUDIO_CODECS + SOURCE_TAGS + RESOLUTION_TAGS +
        SCENE_TAGS + LANGUAGE_TAGS + MISC_NOISE + FILE_EXTENSIONS
    )
  }

  // Pre-compiled per-tag regexes — built once, reused on every parse
  private val ALL_NOISE_REGEXES: List<Regex> by lazy {
    ALL_NOISE.map { tag -> Regex("""\b${Regex.escape(tag)}\b""", RegexOption.IGNORE_CASE) }
  }
  private val RELEASE_GROUP_REGEXES: List<Regex> by lazy {
    RELEASE_GROUPS.map { g -> Regex("""\b${Regex.escape(g)}\b""", RegexOption.IGNORE_CASE) }
  }

  // ── Regex patterns ───────────────────────────────────────────────────────────

  // Season-Episode: S01E02, S1E2, S1:E1, s01e02, S01.E02, S01_E01, S01-E01, S01 - E01, [S1E1]
  private val SEASON_EPISODE_REGEX =
    Regex("""(?<![\p{L}\p{N}])[Ss](\d{1,4})[\s.:_-]*[Ee](\d{1,4})(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}]|[Ee]\d)""")

  // Cross-format: 1x02 format
  private val CROSS_FORMAT_REGEX =
    Regex("""(?<![\p{L}\p{N}])(\d{1,2})[xX](\d{1,4})(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}])""")

  // EP marker: EP05, Ep5 — with word boundary to avoid matching inside words
  private val EP_MARKER_REGEX =
    Regex("""(?<![\p{L}\p{N}])[Ee][Pp][\s.:_-]*(\d{1,4})(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}])""")

  // Standalone E-prefix: E05, E5, with token boundaries to avoid matching inside words
  private val E_PREFIX_REGEX =
    Regex("""(?<![\p{L}\p{N}])[Ee](\d{1,4})(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}]|[Ee]\d)""")

  // Episode word: Episode 5, EPISODE 05, Ep: 1
  private val EPISODE_WORD_REGEX =
    Regex(
      """(?<![\p{L}\p{N}])(?:ep(?:isodes?)?|episoden?|episodios?|capítulos?|épisodes?|episódios?|folgen?|эпизод|епізод|серия|серія|серії|الحلقة|حلقة|एपिसोड|कड़ी|कड़ी|エピソード)[\s.:_-]*(\d{1,4})(?:v\d{1,2})?(?=$|[^\p{L}\p{N}])""",
      RegexOption.IGNORE_CASE,
    )

  // Season word: Season 3, SEASON 3, Season: 1
  private val SEASON_WORD_REGEX =
    Regex(
      """(?<![\p{L}\p{N}])(?:season|staffel|saison|temporada|сезон|الموسم|موسم|सीज़न|सीजन|シーズン|s)[\s.:_-]*(\d{1,2})(?![\p{L}\p{N}])|(?:第)?(\d{1,2})[季期]""",
      RegexOption.IGNORE_CASE,
    )

  // Release-year candidates are validated against the current year.
  private val YEAR_REGEX = Regex("""(?<![\p{L}\p{N}])(?:18[789]\d|19\d{2}|20\d{2})(?![\p{L}\p{N}])""")

  // Hash episode: #05
  private val HASH_EPISODE_REGEX = Regex("""#(\d{1,4})(?:[Vv]\d{1,2})?(?![\p{L}\p{N}])""")
  private val CJK_EPISODE_REGEX = Regex("""(?:第)?(\d{1,4})[話话集]""")
  private val DASH_EPISODE_REGEX =
    Regex("""[\s._]+-[\s._]*(\d{1,4})(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}])""")
  private val EPISODE_CONTINUATION_REGEX =
    Regex(
      """^(?:[\s._]*[Ee](\d{1,4})|[-+&~][Ee]?(\d{1,4})|""" +
        """[\s._]*[-+&~][\s._]*[Ee](\d{1,4}))(?:[Vv]\d{1,2})?(?=$|[^\p{L}\p{N}]|[Ee]\d)""",
    )
  private val SEASON_CONTINUATION_REGEX =
    Regex("""^[\s._]*[-+&~][\s._]*[Ss]\d{1,4}[\s.:_-]*[Ee](\d{1,4})(?:[Vv]\d{1,2})?(?![\p{L}\p{N}])""")
  private val FRACTIONAL_EPISODE_REGEX = Regex("""^\.\d{1,2}(?![\p{L}\p{N}])""")

  // File size: 700MB, 1.2 GB
  private val FILESIZE_REGEX = Regex("""\b\d+\.?\d*\s*[MmGg][Bb]\b""")

  // Bitrate: 4500kbps
  private val BITRATE_REGEX = Regex("""\b\d+\s*[KkMm]bps\b""")

  // Audio channels: 5.1, 7.1, 2.0 — also DDP5.1, DD5.1, AAC2.0
  private val AUDIO_CHANNEL_REGEX = Regex("""\b(?:DDP?|AAC|DD\+?)?\.?([257])\.([01])\b""", RegexOption.IGNORE_CASE)

  // Japanese season: San no Shou
  private val JAPANESE_SEASON_REGEX = Regex("""(\w+)\s+no\s+[Ss]hou""", RegexOption.IGNORE_CASE)

  // WEB-DL special pattern (common in scene releases)
  private val WEB_DL_REGEX = Regex("""\bWEB[-.]?DL\b""", RegexOption.IGNORE_CASE)

  // H.264 / H.265 with dot notation
  private val H_CODEC_REGEX = Regex("""\b[Hh]\.?26[45]\b""")

  // DTS-HD MA and similar compound audio tags
  private val COMPOUND_AUDIO_REGEX =
    Regex("""\b(?:DTS[-.]?HD(?:[-.]?MA)?|TrueHD|DD\+?|DDP)\b""", RegexOption.IGNORE_CASE)

  // HDR10+ / HDR10Plus pattern
  private val HDR10_PLUS_REGEX = Regex("""\bHDR10\+?\b""", RegexOption.IGNORE_CASE)

  // Dolby Vision pattern (DV, DoVi)
  private val DOLBY_VISION_REGEX = Regex("""\b(?:DoVi|Dolby\.?Vision)\b""", RegexOption.IGNORE_CASE)

  private val TECHNICAL_METADATA_REGEX =
    Regex(
      """(?<![\p{L}\p{N}])(?:\d{3,4}[pi]|\d{3,4}x\d{3,4}|[48]k|uhd|""" +
        """[hx][ ._-]?26[45]|hevc|av1|xvid|divx|web[ ._-]?dl|webrip|blu[ ._-]?ray|b[dr]rip|""" +
        """dvdrip|hdtv|dts[ ._-]?hd(?:[ ._-]?ma)?|(?:aac|ddp?|dd\+)[ ._-]?[257][ .][01])(?=$|[^\p{L}\p{N}])""",
      RegexOption.IGNORE_CASE,
    )
  private val BRACKET_REGEX = Regex("""\[([^\[\]]*)]|\(([^()]*)\)|【([^【】]*)】""")
  private val LEADING_GROUP_REGEX = Regex("""^\s*\[([^\[\]]+)]\s*""")
  private val CHECKSUM_REGEX = Regex("""(?i)(?:[a-f0-9]{8}|[a-f0-9]{32}|[a-f0-9]{40}|[a-f0-9]{64})""")
  private val EXTENSION_REGEX by lazy {
    Regex("""\.(?:${FILE_EXTENSIONS.joinToString("|") { Regex.escape(it) }})$""", RegexOption.IGNORE_CASE)
  }

  // ── Main parse function ──────────────────────────────────────────────────────

  fun parse(fileName: String): ParsedMediaInfo {
    val normalizedName = normalizeFileName(fileName)
    return synchronized(parseCache) {
      parseCache.get(normalizedName)?.fast ?: parseFileName(normalizedName).also { result ->
        parseCache.put(normalizedName, CacheEntry(result))
      }
    }
  }

  @Suppress("UNUSED_PARAMETER")
  suspend fun parseForLookup(context: Context, fileName: String): ParsedMediaInfo = parse(fileName)

  private fun normalizeFileName(source: String): String {
    var name = source.trim()
    var isPath = false
    if (name.startsWith("archive://", ignoreCase = true)) {
      name = name.substringAfter('|', name)
      isPath = true
    } else if (Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://""").containsMatchIn(name)) {
      runCatching {
        val uri = Uri.parse(name)
        val queryName = sequenceOf("filename", "file", "title", "name", "path")
          .mapNotNull { key -> uri.getQueryParameter(key)?.takeIf(String::isNotBlank)?.let { key to it } }
          .firstOrNull()
        name = queryName?.second ?: uri.path.orEmpty()
        isPath = queryName == null || queryName.first in setOf("filename", "file", "path")
      }
    }
    if (isPath || name.startsWith('/') || name.startsWith('\\') ||
      Regex("""^[a-zA-Z]:[\\/]""").containsMatchIn(name) ||
      (EXTENSION_REGEX.containsMatchIn(name) && ('/' in name || '\\' in name))
    ) {
      name = name.replace('\\', '/').substringAfterLast('/')
    }
    return Normalizer.normalize(name, Normalizer.Form.NFKC)
      .map { character ->
        if (character.isDigit()) {
          ('0'.code + Character.digit(character, 10)).toChar()
        } else character
      }.joinToString("")
      .replace('\u2013', '-')
      .replace('\u2014', '-')
      .replace('\u2212', '-')
      .replace('\u200B', ' ')
      .replace('\uFEFF', ' ')
      .replace('\u0000', ' ')
      .take(4096)
      .trim()
  }

  private fun parseFileName(fileName: String): ParsedMediaInfo {
    if (fileName.isBlank()) {
      return ParsedMediaInfo(title = "", type = "movie")
    }

    // Step 1: Extract S01E02 / 1x02 patterns before any modification
    val seMatch = SEASON_EPISODE_REGEX.find(fileName)
    val crossMatch = CROSS_FORMAT_REGEX.find(fileName)
    val epWordMatch = EPISODE_WORD_REGEX.find(fileName)
    val seasonWordMatch = SEASON_WORD_REGEX.find(fileName)
    val epMarkerMatch = EP_MARKER_REGEX.find(fileName)
    val ePrefixMatch =
      E_PREFIX_REGEX.find(fileName) ?: HASH_EPISODE_REGEX.find(fileName) ?: CJK_EPISODE_REGEX.find(fileName)

    var season: Int? = null
    var episode: Int? = null
    var episodeEnd: Int? = null
    var isEpisodeAmbiguous = false

    // Priority 1: S01E02 format (handles regular TV like Dexter.S01E02 and anime alike)
    if (seMatch != null) {
      season = seMatch.groupValues[1].toIntOrNull()
      episode = seMatch.groupValues[2].toIntOrNull()
    }
    // Priority 2: 1x02 format
    else if (crossMatch != null) {
      season = crossMatch.groupValues[1].toIntOrNull()
      episode = crossMatch.groupValues[2].toIntOrNull()
    }
    // Priority 3: "Episode 5" format
    else if (epWordMatch != null) {
      episode = epWordMatch.groupValues[1].toIntOrNull()
    }
    // Priority 4: EP05 format
    else if (epMarkerMatch != null) {
      episode = epMarkerMatch.groupValues[1].toIntOrNull()
    }
    else if (ePrefixMatch != null) {
      episode = ePrefixMatch.groupValues[1].toIntOrNull()
    }

    // Season from "Season X" format (e.g., "Attack on Titan Season 3 Episode 12")
    if (season == null && seasonWordMatch != null) {
      season = seasonWordMatch.groupValues.drop(1).firstNotNullOfOrNull(String::toIntOrNull)
    }

    // Step 2: Extract year (from original filename)
    // Be careful not to grab episode numbers as years — skip if year is part of S01E2020 etc.
    val yearMatch = findYear(fileName, seMatch, crossMatch)
    val year = yearMatch?.value
    val titleSource = if (yearMatch != null) {
      fileName.replaceRange(yearMatch.range, " ".repeat(yearMatch.value.length))
    } else fileName

    // Step 3: Japanese season detection
    val japaneseSeason = extractJapaneseSeason(fileName)
    if (season == null) season = japaneseSeason

    // Step 4: Determine the title boundary
    // For S01E02 / 1x02 / Episode X patterns: title is everything before the marker
    // For movies: title is everything before the year
    val titleBoundary =
      findTitleBoundary(
        fileName,
        seMatch,
        crossMatch,
        epWordMatch,
        seasonWordMatch,
        epMarkerMatch,
        ePrefixMatch,
        yearMatch,
      )

    // Step 5: Extract and clean the title
    var cleanTitle =
      if (titleBoundary != null && titleBoundary > 0) {
        val prefix = titleSource.substring(0, titleBoundary)
        cleanRawTitle(prefix)
      } else {
        cleanRawTitle(titleSource)
      }

    // Step 6: Attempt to extract episode title (text after episode marker)
    // e.g., "Breaking.Bad.S05E16.Felina.720p.mkv" → episodeTitle = "Felina"
    var episodeTitle: String? = null
    val primaryEndIndex = getEpisodeEndIndex(seMatch, crossMatch, epWordMatch, epMarkerMatch, ePrefixMatch)
    val extent = primaryEndIndex?.let { endIndex -> episode?.let { readEpisodeExtent(fileName, endIndex, it) } }
    val episodeEndIndex = extent?.endIndex ?: primaryEndIndex
    episodeEnd = extent?.lastEpisode
    isEpisodeAmbiguous = extent?.ambiguous ?: false
    if (isEpisodeAmbiguous) episode = null
    if (episodeEndIndex != null && episodeEndIndex < fileName.length) {
      val afterEpisode = fileName.substring(episodeEndIndex)
      val candidateEpTitle = cleanRawTitle(afterEpisode)
      if (candidateEpTitle.isNotBlank() && candidateEpTitle != cleanTitle) {
        episodeTitle = candidateEpTitle
      }
    }

    // Step 7: Try to detect dash-separated episode for anime-style naming
    // Pattern: "Title - 08" or "Title - 08 - Episode Name"
    // Only if no episode was found yet AND no year-only movie pattern
    if (episode == null && !isEpisodeAmbiguous &&
      (year == null || season != null || LEADING_GROUP_REGEX.containsMatchIn(fileName))
    ) {
      val dashMatch = detectDashEpisode(fileName)
      if (dashMatch != null) {
        val firstEpisode = dashMatch.groupValues[1].toInt()
        val dashExtent = readEpisodeExtent(fileName, dashMatch.range.last + 1, firstEpisode)
        episode = firstEpisode.takeUnless { dashExtent.ambiguous }
        episodeEnd = dashExtent.lastEpisode
        isEpisodeAmbiguous = dashExtent.ambiguous
        if (season == null) season = 1
        val candidateTitle = cleanRawTitle(titleSource.substring(0, dashMatch.range.first))
        if (candidateTitle.isNotBlank()) cleanTitle = candidateTitle
        episodeTitle = cleanRawTitle(fileName.substring(dashExtent.endIndex))
          .takeIf { it.isNotBlank() && it != cleanTitle }
      }
    }

    // Step 8: Default season to 1 if episode is found but no season
    if (episode != null && season == null) {
      season = 1
    }

    // Step 9: Remove Japanese season phrase from title if season was extracted
    if (japaneseSeason != null && season == japaneseSeason) {
      cleanTitle =
        cleanTitle
          .replace(Regex("""\w+\s+no\s+[Ss]hou""", RegexOption.IGNORE_CASE), "")
          .replace(Regex("""\s+"""), " ")
          .trim()
    }

    // Step 10: Remove "Season X" from the title if season was already extracted
    if (season != null) {
      cleanTitle =
        cleanTitle
          .replace(SEASON_WORD_REGEX, "")
          .replace(Regex("""\s+"""), " ")
          .trim()
    }

    // Step 11: Final cleanup
    cleanTitle = finalCleanup(cleanTitle)

    // Step 12: Determine type
    val type =
      when {
        season != null || episode != null -> "tv"
        else -> "movie"
      }

    val result =
      ParsedMediaInfo(
        title = cleanTitle,
        year = year,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        type = type,
        episodeEnd = episodeEnd,
        isEpisodeAmbiguous = isEpisodeAmbiguous,
      )
    return result
  }

  // ── Helper: Find year without matching inside episode patterns ────────────────

  private fun findYear(
    fileName: String,
    seMatch: MatchResult?,
    crossMatch: MatchResult?,
  ): MatchResult? {
    val markerStart = listOfNotNull(seMatch?.range?.first, crossMatch?.range?.first).minOrNull() ?: fileName.length
    val metadataStart = findMetadataBoundary(fileName) ?: fileName.length
    val maximumYear = Year.now().value + 1
    val yearCandidates = YEAR_REGEX.findAll(fileName).filter { candidate ->
      candidate.range.first < minOf(markerStart, metadataStart) &&
        candidate.value.toInt() in 1878..maximumYear &&
        cleanRawTitle(fileName.substring(0, candidate.range.first)).isNotBlank()
    }.toList()
    if (yearCandidates.isEmpty()) return null

    // Filter out any year that overlaps with S01E02 or 1x02 match ranges
    val excludeRanges = listOfNotNull(seMatch?.range, crossMatch?.range)
    val validYears =
      yearCandidates.filter { yearResult ->
        excludeRanges.none { range ->
          yearResult.range.first in range || yearResult.range.last in range
        }
      }

    return validYears.lastOrNull()
  }

  // ── Helper: Detect "Title - 08 - Episode Name" anime pattern ─────────────────

  private fun detectDashEpisode(fileName: String): MatchResult? {
    val match = DASH_EPISODE_REGEX.find(fileName) ?: return null
    val ep = match.groupValues[1].toIntOrNull() ?: return null

    if (ep in 1900..2100) return null
    if (ep in setOf(480, 720, 1080, 2160) && !LEADING_GROUP_REGEX.containsMatchIn(fileName) &&
      !TECHNICAL_METADATA_REGEX.containsMatchIn(fileName.substring(match.range.last + 1))
    ) return null
    return match
  }

  private data class EpisodeExtent(val endIndex: Int, val lastEpisode: Int? = null, val ambiguous: Boolean = false)

  private fun readEpisodeExtent(fileName: String, startIndex: Int, firstEpisode: Int): EpisodeExtent {
    var endIndex = startIndex
    var lastEpisode: Int? = null
    var ambiguous = false
    while (endIndex < fileName.length) {
      val remaining = fileName.substring(endIndex)
      val fraction = FRACTIONAL_EPISODE_REGEX.find(remaining)
      if (fraction != null) return EpisodeExtent(endIndex + fraction.value.length, lastEpisode, ambiguous = true)
      val seasonContinuation = SEASON_CONTINUATION_REGEX.find(remaining)
      val continuation = seasonContinuation ?: EPISODE_CONTINUATION_REGEX.find(remaining) ?: break
      val nextEpisode = continuation.groupValues.drop(1).firstNotNullOfOrNull { it.toIntOrNull() } ?: break
      if (seasonContinuation != null || nextEpisode <= (lastEpisode ?: firstEpisode)) ambiguous = true
      lastEpisode = nextEpisode
      endIndex += continuation.value.length
    }
    return EpisodeExtent(endIndex, lastEpisode, ambiguous)
  }

  // ── Helper: Japanese season extraction ───────────────────────────────────────

  private fun extractJapaneseSeason(text: String): Int? {
    val match = JAPANESE_SEASON_REGEX.find(text) ?: return null
    val word = match.groupValues[1].lowercase(Locale.ROOT)
    return JAPANESE_NUMBERS[word]
  }

  // ── Helper: Find title boundary ──────────────────────────────────────────────

  private fun findTitleBoundary(
    fileName: String,
    seMatch: MatchResult?,
    crossMatch: MatchResult?,
    epWordMatch: MatchResult?,
    seasonWordMatch: MatchResult?,
    epMarkerMatch: MatchResult?,
    ePrefixMatch: MatchResult?,
    yearMatch: MatchResult?,
  ): Int? {
    // For TV shows: title ends at the earliest season/episode marker
    // For movies: title ends at the year
    val candidates = mutableListOf<Int>()

    // Episode/season markers (highest priority for boundary)
    seMatch?.range?.first?.let { candidates.add(it) }
    crossMatch?.range?.first?.let { candidates.add(it) }
    epWordMatch?.range?.first?.let { candidates.add(it) }
    epMarkerMatch?.range?.first?.let { candidates.add(it) }
    ePrefixMatch?.range?.first?.let { candidates.add(it) }

    // If season word comes before episode word, use season as boundary
    seasonWordMatch?.range?.first?.let { seasonStart ->
      // Only use season as boundary if no earlier episode marker exists
      if (candidates.isEmpty() || seasonStart < (candidates.minOrNull() ?: Int.MAX_VALUE)) {
        candidates.add(seasonStart)
      }
    }

    // Year as boundary — only if no episode/season markers found,
    // or if year comes after the title but before metadata
    if (candidates.isEmpty() && yearMatch != null) {
      candidates.add(yearMatch.range.first)
    }

    return candidates.minOrNull()
  }

  private fun getEpisodeEndIndex(
    seMatch: MatchResult?,
    crossMatch: MatchResult?,
    epWordMatch: MatchResult?,
    epMarkerMatch: MatchResult?,
    ePrefixMatch: MatchResult?,
  ): Int? =
    when {
      seMatch != null -> seMatch.range.last + 1
      crossMatch != null -> crossMatch.range.last + 1
      epWordMatch != null -> epWordMatch.range.last + 1
      epMarkerMatch != null -> epMarkerMatch.range.last + 1
      ePrefixMatch != null -> ePrefixMatch.range.last + 1
      else -> null
    }

  private fun cleanRawTitle(raw: String): String {
    var title = raw.replace(EXTENSION_REGEX, "")
    while (true) {
      val group = LEADING_GROUP_REGEX.find(title) ?: break
      val remaining = title.substring(group.range.last + 1)
      val followingTitle = stripMetadataBrackets(remaining.substringBefore(" - "))
        .let { it.take(findMetadataBoundary(it) ?: it.length) }
      if (!isReleaseMetadata(group.groupValues[1]) && followingTitle.none(Char::isLetter)) break
      title = remaining
    }
    val metadataStart = findMetadataBoundary(title)
    if (metadataStart != null) title = title.substring(0, metadataStart)
    title = stripMetadataBrackets(title)
    val suffix = title.substringAfterLast('-', "").trim()
    if (RELEASE_GROUPS.any { it.equals(suffix, ignoreCase = true) }) title = title.substringBeforeLast('-')
    return finalCleanup(title.replace(Regex("""[._]"""), " ").trim(' ', '[', ']', '(', ')'))
  }

  private fun stripMetadataBrackets(value: String): String =
    value.replace(BRACKET_REGEX) { match ->
      val contents = match.groupValues.drop(1).firstOrNull(String::isNotEmpty).orEmpty()
      if (isReleaseMetadata(contents)) " " else " $contents "
    }

  private fun findMetadataBoundary(value: String): Int? =
    TECHNICAL_METADATA_REGEX.findAll(value).firstOrNull { match ->
      val suffix = stripMetadataBrackets(value.substring(match.range.first)).replace(EXTENSION_REGEX, "")
      isReleaseMetadata(suffix) ||
        isReleaseMetadata(suffix.replace(Regex("""-[\p{L}\p{N}][\p{L}\p{N}._-]*$"""), ""))
    }?.range?.first

  private fun isReleaseMetadata(value: String): Boolean {
    if (value.isBlank() || CHECKSUM_REGEX.matches(value.trim())) return true
    var remaining = value.replace(TECHNICAL_METADATA_REGEX, " ")
      .replace(AUDIO_CHANNEL_REGEX, " ")
      .replace(FILESIZE_REGEX, " ")
      .replace(BITRATE_REGEX, " ")
      .replace(WEB_DL_REGEX, " ")
      .replace(H_CODEC_REGEX, " ")
      .replace(COMPOUND_AUDIO_REGEX, " ")
      .replace(HDR10_PLUS_REGEX, " ")
      .replace(DOLBY_VISION_REGEX, " ")
    (ALL_NOISE_REGEXES + RELEASE_GROUP_REGEXES).forEach { remaining = remaining.replace(it, " ") }
    return remaining.all { it.isWhitespace() || it in "._-+&[]()" }
  }

  // ── Helper: Final cleanup ────────────────────────────────────────────────────

  private fun finalCleanup(title: String): String =
    title
      // Collapse multiple spaces
      .replace(Regex("""\s+"""), " ")
      // Remove leading/trailing separators and whitespace
      .replace(Regex("""^[\s\-_.]+"""), "")
      .replace(Regex("""[\s\-_.]+$"""), "")
      // Remove trailing dash left over from group removal
      .replace(Regex("""\s*-\s*$"""), "")
      .trim()

  /**
   * Intelligently compares two media file names using parsed season/episode numbers,
   * natural alphanumeric ordering, and file index fallback.
   */
  fun compareMediaFiles(
    name1: String,
    index1: Int?,
    name2: String,
    index2: Int?,
  ): Int {
    val p1 = parse(name1)
    val p2 = parse(name2)

    val s1 = p1.season
    val s2 = p2.season
    if (s1 != null && s2 != null && s1 != s2) {
      return s1.compareTo(s2)
    }

    val e1 = p1.episode
    val e2 = p2.episode
    if (e1 != null && e2 != null && e1 != e2) {
      return e1.compareTo(e2)
    }

    val natural = SortUtils.NaturalOrderComparator.DEFAULT.compare(name1, name2)
    if (natural != 0) return natural

    return (index1 ?: Int.MAX_VALUE).compareTo(index2 ?: Int.MAX_VALUE)
  }

  /**
   * Extracts and formats a clean, human-readable media title from a streaming URL or fallback filename.
   */
  fun parseStreamTitle(
    source: String,
    fallbackFileName: String? = null,
  ): String {
    val genericWords =
      setOf(
        "raw", "api", "stream", "video", "play", "watch", "download", "get", "media", "v1", "v2", "v3",
        "index.m3u8", "master.m3u8", "playlist.m3u8", "manifest.mpd", "video.mp4", "audio.mp3", "file", "embed", "player", "link",
      )

    val trimmedFallback = fallbackFileName?.trim()
    if (!trimmedFallback.isNullOrBlank()) {
      val isUrlLike = trimmedFallback.startsWith("http://", ignoreCase = true) || trimmedFallback.startsWith("https://", ignoreCase = true)
      if (!isUrlLike && !genericWords.contains(trimmedFallback.lowercase())) {
        val parsed = parse(trimmedFallback)
        if (parsed.title.isNotBlank() && !genericWords.contains(parsed.title.lowercase())) {
          return formatDisplayTitle(parsed)
        }
        return trimmedFallback
      }
    }

    if (source.isBlank()) return fallbackFileName.orEmpty()

    val parsedCandidate =
      runCatching {
        val uri = Uri.parse(source)
        if (HttpUtils.isYouTubeUrl(uri)) {
          val videoId = HttpUtils.extractYouTubeVideoId(uri)
          if (!videoId.isNullOrBlank()) {
            return@runCatching "YouTube Video ($videoId)"
          }
        }
        val queryParams = listOf("path", "file", "filename", "title", "name", "url", "src", "stream", "video", "target", "source", "query", "q")
        var candidate: String? = null

        for (param in queryParams) {
          val value = uri.getQueryParameter(param)
          if (!value.isNullOrBlank()) {
            val decoded = value.trim()
            val extracted = if (param in setOf("title", "name", "query", "q")) {
              decoded
            } else {
              decoded.substringAfterLast('/').substringAfterLast('\\').trim()
            }
            if (extracted.isNotEmpty() && !genericWords.contains(extracted.lowercase())) {
              candidate = extracted
              break
            }
          }
        }

        if (candidate == null) {
          val segments = uri.pathSegments.orEmpty()
          for (i in segments.indices.reversed()) {
            val seg = segments[i].trim()
            val segClean = seg.substringAfterLast('/').substringAfterLast('\\').trim()
            if (segClean.isNotEmpty() && !genericWords.contains(segClean.lowercase())) {
              candidate = segClean
              break
            }
          }
        }

        candidate ?: uri.host?.takeIf(String::isNotBlank) ?: source
      }.getOrDefault(source)

    val parsed = parse(parsedCandidate)
    if (parsed.title.isNotBlank() && !genericWords.contains(parsed.title.lowercase())) {
      return formatDisplayTitle(parsed)
    }

    return parsedCandidate
      .replace(Regex("""\.(?:mkv|mp4|m4v|webm|avi|mov|ts|m2ts|mp3|m4a|flac|ogg|m3u8|mpd)$""", RegexOption.IGNORE_CASE), "")
      .replace(Regex("""[._\-]"""), " ")
      .replace(Regex("""\s+"""), " ")
      .trim()
      .ifBlank { source }
  }

  /**
   * Human-friendly per-file label for episode/file lists (e.g. torrent season packs), showing
   * "S01E02 - Felina" instead of the raw scene-release filename with codec/resolution noise.
   */
  fun episodeLabel(fileName: String): String {
    val info = parse(fileName)
    return when {
      info.season != null && info.episode != null -> {
        val base = "S${info.season.toString().padStart(2, '0')}E${info.episode.toString().padStart(2, '0')}"
        if (!info.episodeTitle.isNullOrBlank()) "$base - ${info.episodeTitle}" else base
      }
      info.episode != null -> {
        val base = "E${info.episode.toString().padStart(2, '0')}"
        if (!info.episodeTitle.isNullOrBlank()) "$base - ${info.episodeTitle}" else base
      }
      info.title.isNotBlank() -> info.title
      else -> fileName
    }
  }

  private fun formatDisplayTitle(info: ParsedMediaInfo): String {
    val builder = StringBuilder(info.title)
    if (info.season != null && info.episode != null) {
      builder.append(" S").append(info.season.toString().padStart(2, '0'))
      builder.append("E").append(info.episode.toString().padStart(2, '0'))
      if (!info.episodeTitle.isNullOrBlank()) {
        builder.append(" - ").append(info.episodeTitle)
      }
    } else if (info.episode != null) {
      builder.append(" E").append(info.episode.toString().padStart(2, '0'))
      if (!info.episodeTitle.isNullOrBlank()) {
        builder.append(" - ").append(info.episodeTitle)
      }
    } else if (info.year != null) {
      builder.append(" (").append(info.year).append(")")
    }
    return builder.toString()
  }
}
