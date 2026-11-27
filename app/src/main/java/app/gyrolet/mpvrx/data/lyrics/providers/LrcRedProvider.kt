/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * lrc.red: Apple Music TTML filed by ISRC.
 *
 * With a known recording this is a single request for `s/{ISRC}.ttml` and cannot
 * return another edit of the song. Without one, the free-text search is used and
 * every hit has to *prove* it is the playing recording ([pick]) — a miss only
 * hands the turn to the next provider, while the wrong words scrolling in time
 * with the right song is a failure nobody notices until the second verse.
 */
internal object LrcRedProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.LRC_RED

  private const val BASE = "https://lrc.red/"

  /** Seconds a hit's length may differ from the playing file's. */
  private const val LENGTH_SLACK_SECONDS = 3.0

  private val ISRC_SHAPE = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val known = query.isrc.toDocumentUrl()
    if (known != null) document(known)?.let { return it }

    for (ref in query.candidates(limit = SEARCH_CANDIDATES)) {
      val searchUrl = buildUrl("${BASE}search.json", "q" to "${ref.title} ${ref.artist}".trim()) ?: continue
      val body = lyricsGet(searchUrl) ?: continue
      val hits =
        runCatching { lyricsJson.decodeFromString<SearchResponse>(body) }.getOrNull()?.hits.orEmpty()
      val winner = pick(hits, ref, query.durationMs / 1000.0) ?: continue
      val url = winner.isrc.toDocumentUrl()?.takeIf { it != known } ?: continue
      document(url)?.let { return it }
    }
    return null
  }

  private suspend fun document(url: String): Lyrics? = lyricsGet(url)?.let(TtmlLyrics::parse)?.let(::lyricsFromSynced)

  private fun String?.toDocumentUrl(): String? =
    this
      ?.trim()
      ?.uppercase(Locale.ROOT)
      ?.takeIf { ISRC_SHAPE.matches(it) }
      ?.let { "${BASE}s/$it.ttml" }

  /**
   * The hit that is this exact recording, or null.
   *
   * Every check is a requirement, not a score: same title core, the same
   * version words (live / remix / acoustic ...), at least one shared artist and a
   * length within [LENGTH_SLACK_SECONDS] when both sides know it. Of whatever
   * survives, the closest in length wins; ties keep the search's own order.
   */
  internal fun pick(
    hits: List<SearchHit>,
    ref: TrackRef,
    seconds: Double,
  ): SearchHit? {
    val wantedCore = core(ref.title)
    val wantedVersion = versionWords(ref.title)
    val wantedArtists = artists(ref.artist)

    fun gap(hit: SearchHit): Double = if (seconds > 0 && hit.duration != null) abs(hit.duration - seconds) else 0.0

    return hits
      .asSequence()
      .filter { hit ->
        val name = hit.title ?: return@filter false
        hit.isrc.toDocumentUrl() != null &&
          core(name) == wantedCore &&
          versionWords(name) == wantedVersion &&
          (wantedArtists.isEmpty() || artists(hit.artist.orEmpty()).any { it in wantedArtists }) &&
          gap(hit) <= LENGTH_SLACK_SECONDS
      }.minByOrNull(::gap)
  }

  /** "Song (Live) - 2011 Remaster" becomes "song". */
  private fun core(title: String): String =
    fold(title.replace(BRACKETS, " ").substringBefore(" - ")).ifEmpty { fold(title) }

  /** Words that make it a different recording, from the parts [core] drops. */
  private fun versionWords(title: String): Set<String> {
    val extras =
      BRACKETS.findAll(title).joinToString(" ") { it.value } + " " + title.substringAfter(" - ", "")
    return fold(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
  }

  private fun artists(raw: String): Set<String> =
    raw
      .split(ARTIST_SPLIT)
      .map(::fold)
      .filter { it.isNotEmpty() }
      .toSet()

  /** Lower case, accents removed, letters and digits only. */
  private fun fold(value: String): String =
    Normalizer
      .normalize(value, Normalizer.Form.NFD)
      .replace(MARKS, "")
      .lowercase(Locale.ROOT)
      .map { if (it.isLetterOrDigit()) it else ' ' }
      .joinToString("")
      .replace(SPACES, " ")
      .trim()

  private const val SEARCH_CANDIDATES = 3
  private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
  private val MARKS = Regex("""\p{Mn}+""")
  private val SPACES = Regex("""\s+""")
  private val ARTIST_SPLIT =
    Regex("""\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""", RegexOption.IGNORE_CASE)
  private val VERSION_WORDS =
    setOf(
      "live",
      "remix",
      "remixed",
      "mix",
      "acoustic",
      "unplugged",
      "instrumental",
      "karaoke",
      "cappella",
      "acapella",
      "demo",
      "edit",
      "version",
      "cover",
      "sped",
      "slowed",
      "reverb",
      "nightcore",
      "lofi",
      "orchestral",
      "extended",
    )

  @Serializable
  internal data class SearchResponse(
    val hits: List<SearchHit>? = null,
  )

  @Serializable
  internal data class SearchHit(
    val isrc: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Seconds, possibly fractional. */
    val duration: Double? = null,
  )
}
