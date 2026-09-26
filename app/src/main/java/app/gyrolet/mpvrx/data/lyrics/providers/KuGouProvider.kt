/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * Derived from BitChord's KuGou provider
 * (https://github.com/kushagrasinghx/BitChord), GPL-3.0-or-later.
 */

package app.gyrolet.mpvrx.data.lyrics.providers

import app.gyrolet.mpvrx.domain.lyrics.Lyrics
import app.gyrolet.mpvrx.domain.lyrics.LyricsProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * Line-synced lyrics from KuGou's public mobile endpoints — a Chinese
 * catalogue that also carries a great many English and Hindi tracks the other
 * sources simply do not have.
 *
 * Three unauthenticated calls, chained: search the song to get its audio
 * fingerprint, search lyric candidates against that hash, then download the
 * winning candidate. A search by keyword alone is kept as the fallback that
 * catches everything the first two miss.
 */
internal object KuGouProvider : LyricsProviderClient {
  override val id: LyricsProvider = LyricsProvider.KUGOU

  private const val DURATION_TOLERANCE_SECONDS = 8

  override suspend fun fetch(query: LyricsFetchQuery): Lyrics? {
    val seconds = query.durationMs / 1000
    for (ref in query.candidates()) {
      val keyword = keyword(ref, query.album)
      val candidate =
        searchSongs(keyword, seconds)
          ?.firstNotNullOfOrNull { hash -> searchLyrics(hash = hash).orEmpty().firstOrNull() }
          ?: searchLyrics(keyword = keyword, seconds = seconds).orEmpty().firstOrNull()
          ?: continue

      val lrc = download(candidate.id, candidate.accesskey) ?: continue
      parseLrcText(decodeEntities(lrc))?.let { return it }
    }
    return null
  }

  /**
   * Song hashes worth trying, restricted to cuts within the duration
   * tolerance of the track being played — otherwise the first result for a
   * common title is as likely to be a cover as the right recording — and
   * ordered closest match first.
   */
  private suspend fun searchSongs(
    keyword: String,
    seconds: Int,
  ): List<String>? {
    val url =
      buildUrl(
        "https://mobileservice.kugou.com/api/v3/search/song",
        "version" to "9108",
        "plat" to "0",
        "pagesize" to "8",
        "showtype" to "0",
        "keyword" to keyword,
      ) ?: return null
    val body = lyricsGet(url) ?: return null
    val response = runCatching { lyricsJson.decodeFromString<SearchSongResponse>(body) }.getOrNull()
    return response
      ?.data
      ?.info
      .orEmpty()
      .filter { seconds <= 0 || abs(it.duration - seconds) <= DURATION_TOLERANCE_SECONDS }
      .sortedBy { abs(it.duration - seconds) }
      .map { it.hash }
  }

  private suspend fun searchLyrics(
    hash: String? = null,
    keyword: String? = null,
    seconds: Int = -1,
  ): List<Candidate>? {
    if (hash == null && keyword == null) return null
    val url =
      buildUrl(
        "https://lyrics.kugou.com/search",
        "ver" to "1",
        "man" to "yes",
        "client" to "pc",
        "hash" to hash,
        "keyword" to keyword,
        "duration" to seconds.takeIf { it > 0 }?.let { (it * 1000).toString() },
      ) ?: return null
    val body = lyricsGet(url) ?: return null
    val response = runCatching { lyricsJson.decodeFromString<SearchLyricsResponse>(body) }.getOrNull()
    return response?.candidates
  }

  private suspend fun download(
    id: String,
    accessKey: String,
  ): String? {
    val url =
      buildUrl(
        "https://lyrics.kugou.com/download",
        "fmt" to "lrc",
        "charset" to "utf8",
        "client" to "pc",
        "ver" to "1",
        "id" to id,
        "accesskey" to accessKey,
      ) ?: return null
    val body = lyricsGet(url) ?: return null
    val response = runCatching { lyricsJson.decodeFromString<DownloadResponse>(body) }.getOrNull() ?: return null
    val decoded =
      runCatching {
        String(Base64.getDecoder().decode(response.content), Charsets.UTF_8)
      }.getOrNull() ?: return null
    return decoded.stripCredits()
  }

  private fun keyword(
    ref: TrackRef,
    album: String?,
  ): String =
    buildString {
      append(ref.title.stripParenthetical())
      append(" - ")
      append(ref.artist.stripParenthetical())
      if (!album.isNullOrBlank()) append(' ').append(album)
    }

  private fun String.stripParenthetical(): String = replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { this }

  /**
   * Lyric files open and close with uncredited lines — songwriter, composer,
   * arranger — that carry a real timestamp and would otherwise be sung as the
   * first and last lines of the song. Cut the same way the source client does:
   * from either end, up to the first line matching "label: value", and only
   * within the first and last 30 lines so a lyric that happens to contain a
   * colon deep in the song is left alone.
   */
  private fun String.stripCredits(): String {
    val lines = lineSequence().filter { STAMPED.matches(it) }.toList()
    if (lines.isEmpty()) return ""
    val headLimit = min(30, lines.lastIndex)
    val headCut = (headLimit downTo 0).firstOrNull { CREDIT.matches(lines[it]) }?.let { it + 1 } ?: 0
    val body = lines.drop(headCut)
    val tailLimit = min(30, body.lastIndex)
    val tailCut = (0..tailLimit).firstOrNull { CREDIT.matches(body[body.lastIndex - it]) }?.let { it + 1 } ?: 0
    return body.dropLast(tailCut).joinToString("\n")
  }

  private val STAMPED = Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")
  private val CREDIT = Regex(""".+][^\[]+[:：].+""")

  @Serializable
  private data class SearchSongResponse(
    val data: Data? = null,
  ) {
    @Serializable
    data class Data(
      val info: List<Info> = emptyList(),
    )

    @Serializable
    data class Info(
      val hash: String,
      val duration: Int = -1,
    )
  }

  @Serializable
  private data class SearchLyricsResponse(
    val candidates: List<Candidate> = emptyList(),
  )

  @Serializable
  private data class Candidate(
    val id: String,
    @SerialName("accesskey") val accesskey: String,
  )

  @Serializable
  private data class DownloadResponse(
    val content: String = "",
  )
}
