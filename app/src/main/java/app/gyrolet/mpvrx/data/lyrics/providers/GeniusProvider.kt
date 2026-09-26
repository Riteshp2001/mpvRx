/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's Genius scraper
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.net.URLEncoder
import java.util.Locale

/**
 * Web scrape of Genius.com, used strictly as the last resort: it has no
 * timestamps at all, but an enormous catalogue, so it is only asked once every
 * timed provider has missed.
 *
 * What the request claims to be matters here. Claiming to be a browser gets a
 * bot challenge back from the CDN in front of Genius, and a challenge page
 * travels up the stack as "no lyrics for this track" — indistinguishable from a
 * song Genius genuinely does not have. A plain agent is answered normally.
 */
internal object GeniusProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.GENIUS

  private const val USER_AGENT = "mpvRx"

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    for (ref in query.candidates()) {
      val url = songUrl(ref) ?: continue
      val html = lyricsGet(url, browserHeaders()) ?: continue
      parsePage(html)?.let { return it }
    }
    return null
  }

  private suspend fun songUrl(ref: TrackRef): String? {
    for (attempt in searchAttempts(ref)) {
      val url = "https://genius.com/api/search/multi?q=${URLEncoder.encode(attempt, "UTF-8")}"
      val body = lyricsGet(url, browserHeaders()) ?: continue
      findSongUrl(body, ref)?.let { return it }
    }
    return null
  }

  /** The ways this track might have been filed, most specific first. */
  private fun searchAttempts(ref: TrackRef): List<String> {
    val title = cleanQuery(ref.title)
    val artist = cleanQuery(ref.artist)
    val parts = if (title.contains(TITLE_SEPARATOR)) title.split(TITLE_SEPARATOR, limit = 2) else null

    val extractedTitle =
      when {
        parts != null && parts[0].trim().equals(artist, ignoreCase = true) -> parts[1].trim()
        parts != null && parts[1].trim().equals(artist, ignoreCase = true) -> parts[0].trim()
        parts != null && parts[0].isNotBlank() && parts[1].isNotBlank() -> parts[1].trim()
        else -> title
      }
    val extractedArtist =
      when {
        parts != null && parts[0].trim().equals(artist, ignoreCase = true) -> artist
        parts != null && parts[1].trim().equals(artist, ignoreCase = true) -> artist
        parts != null && artist.isBlank() -> parts[0].trim()
        else -> artist
      }
    val strippedTitle =
      extractedTitle
        .replace(BRACKETED_CONTENT, " ")
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    return buildList {
      if (extractedArtist.isNotBlank() && extractedTitle.isNotBlank()) {
        add("$extractedArtist $extractedTitle".trim())
      }
      if (strippedTitle.isNotBlank() && strippedTitle != extractedTitle) {
        add("$extractedArtist $strippedTitle".trim())
      }
      if (strippedTitle.isNotBlank()) add(strippedTitle)
    }.distinct().filter { it.isNotBlank() }
  }

  private fun findSongUrl(
    body: String,
    ref: TrackRef,
  ): String? = runCatching { songUrlFrom(body, ref) }.getOrNull()

  private fun songUrlFrom(
    body: String,
    ref: TrackRef,
  ): String? {
    val root = lyricsJson.parseToJsonElement(body).jsonObject
    val response = root["response"]?.jsonObject ?: return null
    val sections = response["sections"]?.jsonArray ?: return null
    val songSection =
      sections
        .firstOrNull {
          (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "song"
        }?.jsonObject ?: return null
    val candidates =
      songSection["hits"]
        ?.jsonArray
        ?.mapNotNull { (it as? JsonObject)?.get("result")?.jsonObject }
        .orEmpty()
    return bestMatch(candidates, ref)?.get("url")?.jsonPrimitive?.contentOrNull
  }

  private fun bestMatch(
    candidates: List<JsonObject>,
    ref: TrackRef,
  ): JsonObject? {
    if (candidates.isEmpty()) return null
    val normTitle = ref.title.trim().lowercase(Locale.ROOT)
    val normArtist = ref.artist.trim().lowercase(Locale.ROOT)

    val scored =
      candidates.mapNotNull { item ->
        val title = item["title"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT) ?: ""
        val artist = item["artist_names"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT) ?: ""
        val titleMatches =
          normTitle.isNotBlank() &&
            (title == normTitle || title.contains(normTitle) || normTitle.contains(title))
        val artistMatches =
          normArtist.isNotBlank() &&
            (artist == normArtist || artist.contains(normArtist) || normArtist.contains(artist))
        if (!titleMatches && !artistMatches) return@mapNotNull null

        var score = 0
        score +=
          if (title == normTitle) {
            50
          } else if (titleMatches) {
            25
          } else {
            0
          }
        if (artistMatches) score += if (artist == normArtist) 40 else 20

        val path = item["path"]?.jsonPrimitive?.contentOrNull ?: ""
        if (path.contains("translation", ignoreCase = true) && !normTitle.contains("translation")) score -= 30
        if (path.contains("tracklist", ignoreCase = true) || path.contains("album-art", ignoreCase = true)) score -= 50

        if (score <= 0) return@mapNotNull null
        item to score
      }
    return scored.maxByOrNull { it.second }?.first
  }

  /** Extracts the lyric containers while keeping stanzas and section headers. */
  private fun parsePage(html: String): Lyrics? =
    runCatching {
      val doc = Jsoup.parse(html)
      var containers = doc.select("div[data-lyrics-container=true]")
      if (containers.isEmpty()) containers = doc.select("div.lyrics")
      if (containers.isEmpty()) return null

      val fullText =
        buildString {
          for (container in containers) {
            container
              .select(
                "[data-exclude-from-selection=true], " +
                  ".LyricsHeader__Container, " +
                  ".SongBioPreview__Container, " +
                  ".InreadAd__Container, " +
                  "button, script, style",
              ).remove()
            container.select("br").forEach { it.replaceWith(TextNode("\n")) }
            container.select("p").forEach { it.prepend("\n") }
            val text = container.wholeText()
            if (text.isNotBlank()) append(text).append('\n')
          }
        }
      lyricsFromPlain(stripArtifacts(fullText))
    }.getOrNull()

  internal fun stripArtifacts(raw: String): String =
    raw
      .replace(' ', ' ')
      .replace('​', ' ')
      .replace('\uFEFF', ' ')
      .replace(Regex("""\d*You might also like""", RegexOption.IGNORE_CASE), "")
      .trim()
      .replace(Regex("""\d*Embed\s*$""", RegexOption.IGNORE_CASE), "")
      .trim()

  private fun browserHeaders(): Map<String, String> =
    mapOf(
      "User-Agent" to USER_AGENT,
      "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,*/*;q=0.8",
      "Accept-Language" to "en-US,en;q=0.9",
    )

  private fun cleanQuery(text: String): String {
    val cleaned =
      text
        .replace(Regex("""[♪♫★☆【】《》「」~_]"""), " ")
        .replace(NOISE, " ")
        .replace(Regex("""(?i)\b(?:prod(?:uced)?\.?(?:\s+by)?)\s+.*$"""), " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifBlank { text.trim() }
  }

  private val TITLE_SEPARATOR = Regex("""\s*[-–—:]\s*""")
  private val BRACKETED_CONTENT = Regex("""\s*[\(\[].*?[\)\]]""")
  private val NOISE =
    Regex(
      """\s*[(\[]\s*(?:from|feat\.?|ft\.?|featuring|with|prod\.?|produced by|official|lyrical|video|audio|""" +
        """remix|music video|visualizer|mv|hd|4k|hq|full song)[^)\]]*[)\]]|""" +
        """\s*\b(?:official\s+(?:music\s+)?(?:video|audio)|lyrical(?:\s+video)?|full\s+song|4k\s+video|""" +
        """hd\s+video|music\s+video)\b""",
      RegexOption.IGNORE_CASE,
    )
}
